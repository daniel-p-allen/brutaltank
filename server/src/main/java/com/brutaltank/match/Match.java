package com.brutaltank.match;

import com.brutaltank.domain.player.Player;
import com.brutaltank.domain.player.Tank;
import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.terrain.TerrainGenerator;
import com.brutaltank.domain.weapon.DamageCalculator;
import com.brutaltank.domain.weapon.ProjectileSim;
import com.brutaltank.domain.weapon.ShieldDef;
import com.brutaltank.domain.weapon.WeaponDef;
import com.brutaltank.net.Envelopes;
import com.brutaltank.net.MessageSink;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * One match's full lifecycle: lobby (WAITING) -> turn-based play (IN_PROGRESS,
 * per PLAN.md 2.3's TURN_START/AWAITING_FIRE/RESOLVING/round-end state
 * machine) -> COMPLETE. Reuses the M1 domain code
 * ({@link ProjectileSim}/{@link Terrain}/{@link DamageCalculator}) as-is for
 * shot resolution.
 *
 * <p><b>Concurrency</b>: per PLAN.md 2.2, a match is the unit of sequential
 * consistency. Rather than the full queue-based {@code MatchActor}, this
 * class uses the simpler "single lock" variant the M2 task spec explicitly
 * allows: every public mutator is {@code synchronized} on {@code this}, and
 * the single trickiest race — a turn's 30s auto-skip timer firing at the same
 * moment a valid {@code Fire} arrives — is resolved by a {@code turnToken}
 * counter checked *inside* the same monitor both paths acquire, so only the
 * first to actually acquire the lock can mutate turn state; the other
 * observes the now-stale token/status and no-ops. This guarantees a turn is
 * resolved exactly once.
 */
public final class Match {

    public enum Status { WAITING, IN_PROGRESS, SHOP, COMPLETE }

    private static final int STARTING_HEALTH = 100;
    private static final int STARTING_CASH = 500;
    private static final int MAX_TURNS_PER_ROUND = 60;
    private static final int ROUND_SURVIVAL_BONUS = 50;
    private static final int ELIMINATION_BONUS = 100;
    // Per user request, 2026-08-22: "price for a win should be $500" — a
    // distinct round-winner prize on top of the flat ROUND_SURVIVAL_BONUS
    // every surviving player already gets (including the winner).
    private static final int ROUND_WIN_BONUS = 500;
    static final long DEFAULT_TURN_TIMEOUT_MS = 30_000;
    static final long DEFAULT_RECONNECT_GRACE_MS = 120_000;
    // M4 (PLAN.md 4.5: "Shop phase duration: 30s, server-enforced") — bumped
    // to 120s (per user feedback, 2026-08-22: shop "randomly" timing out was
    // annoying), then to 600s (per user feedback, 2026-08-24: it still fired
    // during normal play while genuinely shopping, forcing the round to
    // start mid-browse) now that ShopContinue is the primary way a shop
    // phase ends; this is purely a stall-safety fallback for an
    // AFK-but-still-connected player, not meant to fire during normal play.
    static final long DEFAULT_SHOP_TIMEOUT_MS = 600_000;
    private static final String[] COLORS = {
            "#e33", "#33e", "#3e3", "#ee3", "#e3e", "#3ee", "#f80", "#a3f"
    };

    public final String matchId;
    public final String joinCode;

    private final ObjectMapper mapper;
    private final MatchConfig config;
    private final ScheduledExecutorService scheduler;

    private Status status = Status.WAITING;
    private final Map<String, MatchPlayer> players = new LinkedHashMap<>();
    private final List<String> turnOrder = new ArrayList<>();
    private int currentTurnIndex;
    private int roundNumber = 1;
    private Terrain terrain;
    private int windStrength;
    private int windDirectionSign = 1;
    private int turnsThisRound;
    private int turnToken;
    private int shopToken;
    // Shared across all players for the current shop phase (M4: shop stock
    // is a match-wide pool, not per-player — see WeaponDef/ShieldDef.shopStock).
    private final Map<String, Integer> shopStockRemaining = new LinkedHashMap<>();
    // Per user feedback: shop shouldn't be purely timer-driven — a Continue
    // button lets players signal readiness, advancing early once everyone
    // connected has clicked it. The timer stays as an anti-stall safety net
    // (a disconnected/AFK player could otherwise hold the match hostage),
    // just no longer the primary way a shop phase normally ends.
    private final Set<String> shopReadyPlayerIds = new java.util.HashSet<>();
    private ScheduledFuture<?> pendingTimeout;

    private volatile long turnTimeoutMs = DEFAULT_TURN_TIMEOUT_MS;
    private volatile long reconnectGraceMs = DEFAULT_RECONNECT_GRACE_MS;
    private volatile long shopTimeoutMs = DEFAULT_SHOP_TIMEOUT_MS;

    public Match(String matchId, ObjectMapper mapper, MatchConfig config, ScheduledExecutorService scheduler) {
        this.matchId = matchId;
        this.joinCode = matchId;
        this.mapper = mapper;
        this.config = config;
        this.scheduler = scheduler;
    }

    /**
     * Tuning/test hook: overrides the 30s turn-timeout default. Public (not
     * exposed over the wire protocol) so both unit tests and
     * {@code LobbyManager}'s optional short-timeout mode (used by the live-WS
     * integration test to reach round-end without sleeping 30s per turn) can
     * set it without waiting real production durations.
     */
    public void setTurnTimeoutMs(long ms) {
        this.turnTimeoutMs = ms;
    }

    /** Tuning/test hook: overrides the 120s reconnect-grace default. See {@link #setTurnTimeoutMs}. */
    public void setReconnectGraceMs(long ms) {
        this.reconnectGraceMs = ms;
    }

    /** Tuning/test hook: overrides the 30s shop-phase-timeout default. See {@link #setTurnTimeoutMs}. */
    public void setShopTimeoutMs(long ms) {
        this.shopTimeoutMs = ms;
    }

    // =================================================================
    // Result types
    // =================================================================

    public record JoinResult(boolean success, String playerId, String playerToken, String errorReason) {
        static JoinResult ok(String playerId, String token) {
            return new JoinResult(true, playerId, token, null);
        }

        static JoinResult fail(String reason) {
            return new JoinResult(false, null, null, reason);
        }
    }

    public record ReadyResult(boolean success, boolean matchStarted, String errorReason) {
        static ReadyResult ok(boolean started) {
            return new ReadyResult(true, started, null);
        }

        static ReadyResult fail(String reason) {
            return new ReadyResult(false, false, reason);
        }
    }

    public record RejoinResult(boolean success, String playerId, String errorReason) {
        static RejoinResult ok(String playerId) {
            return new RejoinResult(true, playerId, null);
        }

        static RejoinResult fail(String reason) {
            return new RejoinResult(false, null, reason);
        }
    }

    public record FireOutcome(boolean accepted, Payloads.ShotResolved shotResolved, String rejectReason) {
        static FireOutcome ok(Payloads.ShotResolved resolved) {
            return new FireOutcome(true, resolved, null);
        }

        static FireOutcome rejected(String reason) {
            return new FireOutcome(false, null, reason);
        }
    }

    public record PurchaseOutcome(boolean accepted, Payloads.ShopUpdate update, String rejectReason) {
        static PurchaseOutcome ok(Payloads.ShopUpdate update) {
            return new PurchaseOutcome(true, update, null);
        }

        static PurchaseOutcome rejected(String reason) {
            return new PurchaseOutcome(false, null, reason);
        }
    }

    // =================================================================
    // Per-player match-runtime state
    // =================================================================

    private static final class MatchPlayer {
        final Player player;
        final String playerToken;
        volatile MessageSink sink;
        volatile boolean connected;
        boolean ready;
        boolean isHost;
        boolean departed;
        int damageDealt;
        int kills;
        ScheduledFuture<?> graceTask;

        MatchPlayer(Player player, String playerToken, MessageSink sink) {
            this.player = player;
            this.playerToken = playerToken;
            this.sink = sink;
            this.connected = true;
        }
    }

    // =================================================================
    // Lobby
    // =================================================================

    public synchronized JoinResult addPlayer(String displayName, MessageSink sink) {
        if (status != Status.WAITING) {
            return JoinResult.fail("MATCH_IN_PROGRESS");
        }
        if (players.size() >= config.maxPlayers()) {
            return JoinResult.fail("MATCH_FULL");
        }
        String playerId = "p-" + UUID.randomUUID().toString().substring(0, 8);
        String token = "tok-" + UUID.randomUUID();
        String color = COLORS[players.size() % COLORS.length];
        Player domainPlayer = new Player(playerId, displayName, color, STARTING_CASH, new Tank(0, 0, STARTING_HEALTH));
        // M3 has no shop yet (M4): grant every player a full starting loadout
        // (all 10 weapons at their PLAN.md 4.4 default quantities, plus 1 of
        // each shield) so the whole roster is immediately testable without a
        // shop. -1 conventionally means "unlimited" (basic_shell).
        for (WeaponDef w : WeaponDef.all().values()) {
            domainPlayer.loadout.put(w.weaponId(), w.defaultQty());
        }
        for (ShieldDef s : ShieldDef.all().values()) {
            domainPlayer.loadout.put(s.shieldId(), 1);
        }

        MatchPlayer mp = new MatchPlayer(domainPlayer, token, sink);
        mp.isHost = players.isEmpty();
        players.put(playerId, mp);

        broadcast("LobbyUpdate", buildLobbyUpdate());
        return JoinResult.ok(playerId, token);
    }

    public synchronized ReadyResult setReady(String playerId, boolean ready) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed) {
            return ReadyResult.fail("UNKNOWN_PLAYER");
        }
        if (status != Status.WAITING) {
            return ReadyResult.fail("MATCH_NOT_WAITING");
        }
        mp.ready = ready;

        boolean started = false;
        long connectedCount = players.values().stream().filter(p -> p.connected && !p.departed).count();
        boolean allReady = connectedCount >= 2 && players.values().stream()
                .filter(p -> p.connected && !p.departed)
                .allMatch(p -> p.ready);
        if (allReady) {
            startMatch();
            started = true;
        } else {
            broadcast("LobbyUpdate", buildLobbyUpdate());
        }
        return ReadyResult.ok(started);
    }

    public synchronized void leaveMatch(String playerId) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed) {
            return;
        }
        if (status == Status.WAITING) {
            players.remove(playerId);
            reassignHostIfNeeded();
            broadcast("LobbyUpdate", buildLobbyUpdate());
        } else if (status == Status.IN_PROGRESS) {
            handleDisconnect(playerId);
        }
    }

    private void reassignHostIfNeeded() {
        boolean anyHost = players.values().stream().anyMatch(p -> p.isHost);
        if (!anyHost) {
            players.values().stream().findFirst().ifPresent(p -> p.isHost = true);
        }
    }

    // =================================================================
    // Disconnect / reconnect (PLAN.md 2.1)
    // =================================================================

    public synchronized void handleDisconnect(String playerId) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed || !mp.connected) {
            return;
        }
        mp.connected = false;
        mp.sink = null;

        if (status == Status.WAITING) {
            broadcast("LobbyUpdate", buildLobbyUpdate());
            return;
        }
        if (status != Status.IN_PROGRESS) {
            return;
        }

        broadcast("PlayerDisconnected", new Payloads.PlayerIdPayload(playerId));
        mp.graceTask = scheduler.schedule(() -> onGraceExpired(playerId), reconnectGraceMs, TimeUnit.MILLISECONDS);
    }

    public synchronized RejoinResult rejoin(String playerToken, MessageSink sink) {
        MatchPlayer target = null;
        for (MatchPlayer mp : players.values()) {
            if (!mp.departed && mp.playerToken.equals(playerToken)) {
                target = mp;
                break;
            }
        }
        if (target == null) {
            return RejoinResult.fail("INVALID_TOKEN_OR_EXPIRED");
        }
        if (target.graceTask != null) {
            target.graceTask.cancel(false);
            target.graceTask = null;
        }
        target.connected = true;
        target.sink = sink;

        if (status == Status.IN_PROGRESS) {
            broadcast("PlayerReconnected", new Payloads.PlayerIdPayload(target.player.playerId));
            Envelopes.send(sink, mapper, "MatchStateSync", null, buildStateSync());
        } else if (status == Status.WAITING) {
            broadcast("LobbyUpdate", buildLobbyUpdate());
        }
        return RejoinResult.ok(target.player.playerId);
    }

    private synchronized void onGraceExpired(String playerId) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.connected || mp.departed) {
            return; // already reconnected, or already handled
        }
        mp.departed = true;

        if (status != Status.IN_PROGRESS) {
            turnOrder.remove(playerId);
            if (status == Status.WAITING) {
                broadcast("LobbyUpdate", buildLobbyUpdate());
            }
            return;
        }
        if (turnOrder.isEmpty()) {
            return;
        }

        boolean wasActive = turnOrder.get(currentTurnIndex).equals(playerId);
        String activeBefore = wasActive ? null : turnOrder.get(currentTurnIndex);
        turnOrder.remove(playerId);

        if (turnOrder.isEmpty()) {
            status = Status.COMPLETE;
            cancelPendingTimeout();
            return;
        }

        if (wasActive) {
            cancelPendingTimeout();
            turnToken++; // invalidate any in-flight timeout callback for the departed player's turn
            currentTurnIndex = currentTurnIndex % turnOrder.size();
            resolveTurnAdvance(null, null);
        } else {
            currentTurnIndex = turnOrder.indexOf(activeBefore);
            List<String> aliveIds = aliveNonDepartedIds();
            if (aliveIds.size() <= 1) {
                cancelPendingTimeout();
                endRound(aliveIds);
            }
        }
    }

    // =================================================================
    // Match/round start
    // =================================================================

    private void startMatch() {
        status = Status.IN_PROGRESS;
        turnOrder.clear();
        for (MatchPlayer mp : players.values()) {
            if (mp.connected && !mp.departed) {
                turnOrder.add(mp.player.playerId);
            }
        }
        roundNumber = 1;
        broadcast("MatchStarted", buildMatchStarted());
        startRound();
    }

    private void startRound() {
        turnsThisRound = 0;
        long seed = matchId.hashCode() * 31L + roundNumber;
        int[] spawnXs = TerrainGenerator.computeSpawnXs(turnOrder.size());
        terrain = TerrainGenerator.generate(seed, TerrainGenerator.WORLD_WIDTH, spawnXs);

        for (int i = 0; i < turnOrder.size(); i++) {
            MatchPlayer mp = players.get(turnOrder.get(i));
            int spawnX = spawnXs[i];
            double y = terrain.heightAt(spawnX);
            mp.player.tank.x = spawnX;
            mp.player.tank.y = y;
            mp.player.tank.health = STARTING_HEALTH;
            mp.player.tank.alive = true;
            // Per user feedback: shields (and their visuals) shouldn't carry
            // over into a new round — fresh terrain, fresh health, fresh
            // shield state too, whether or not the previous shield had
            // already broken.
            mp.player.activeShieldId = null;
            mp.player.shieldAbsorbedSoFar = 0;
        }

        currentTurnIndex = 0;
        broadcast("MatchStateSync", buildStateSync());
        beginTurn();
    }

    private void rotateTurnOrder() {
        if (turnOrder.size() > 1) {
            String first = turnOrder.remove(0);
            turnOrder.add(first);
        }
    }

    // =================================================================
    // Turn state machine (PLAN.md 2.3)
    // =================================================================

    // Max wind magnitude, i.e. wind rolls in [-WIND_MAX, WIND_MAX]. Halved
    // per user feedback (was 20) after doubling ProjectileSim.POWER_SCALE
    // made wind drift feel overly strong relative to shot power.
    private static final int WIND_MAX = 10;

    // Wind persists across 2 consecutive turns before rerolling (per user
    // feedback), rather than rerolling fresh every single turn. turnsThisRound
    // is 0 at the first turn of a round (always rerolls, so every round
    // starts with a fresh wind) and increments once per resolved turn, so
    // rerolling only on even values pairs turns (0,1), (2,3), (4,5), ...
    private void beginTurn() {
        if (turnsThisRound % 2 == 0) {
            windStrength = ThreadLocalRandom.current().nextInt(-WIND_MAX, WIND_MAX + 1);
            windDirectionSign = windStrength >= 0 ? 1 : -1;
        }

        turnToken++;
        int myToken = turnToken;
        String activePlayerId = turnOrder.get(currentTurnIndex);

        Payloads.TurnStarted turnStarted = new Payloads.TurnStarted(
                activePlayerId,
                new Payloads.WindDto(windStrength, windDirectionSign),
                (int) (turnTimeoutMs / 1000));
        broadcast("TurnStarted", turnStarted);

        pendingTimeout = scheduler.schedule(() -> onTurnTimeout(myToken), turnTimeoutMs, TimeUnit.MILLISECONDS);
    }

    // Per user feedback: "if you miss your turn without taking a shot, you
    // lost 50. if you go to 0 dollars you die and are eliminated." Only
    // applied on a genuine timeout (this method) — not on the separate
    // departed-player skip path (resolveTurnAdvance is also called with
    // resolved==null from removePlayer/handleDisconnect's full-leave case,
    // where the player is already gone and there's nothing to penalize).
    private static final int TURN_FORFEIT_PENALTY = 50;

    private synchronized void onTurnTimeout(int token) {
        if (status != Status.IN_PROGRESS || token != turnToken) {
            return; // stale: a Fire (or another timeout/disconnect) already resolved this turn
        }
        applyTurnForfeitPenalty(turnOrder.get(currentTurnIndex));
        resolveTurnAdvance(null, null); // timeout skip: no shot, just advance
    }

    private void applyTurnForfeitPenalty(String playerId) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed || !mp.player.tank.alive) {
            return;
        }
        mp.player.cash = Math.max(0, mp.player.cash - TURN_FORFEIT_PENALTY);
        boolean eliminated = mp.player.cash <= 0;
        if (eliminated) {
            mp.player.tank.alive = false;
        }
        broadcast("TurnForfeited", new Payloads.TurnForfeited(playerId, TURN_FORFEIT_PENALTY, mp.player.cash, eliminated));
    }

    /**
     * Relays a live aim-angle update to every connected client (cosmetic
     * only — see {@link Payloads.AimUpdate}). Not turn-gated: any player can
     * play with their aim slider at any time and have their barrel track it
     * on everyone's screen, per user feedback.
     */
    public synchronized void updateAim(String playerId, double angleDeg) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed) {
            return;
        }
        broadcast("PlayerAiming", new Payloads.PlayerAiming(playerId, angleDeg));
    }

    /**
     * Cosmetic-only live relay of a player's Trajectory Help on/off toggle
     * (mirrors {@link #updateAim}) — purely informational for the players
     * list; the actual risk/reward bonus is read fresh off each {@code Fire}
     * message's own {@code trajectoryHelpUsed} field, not this live value,
     * since a player can toggle between shots.
     */
    public synchronized void updateTrajectoryHelp(String playerId, boolean enabled) {
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed) {
            return;
        }
        broadcast("PlayerTrajectoryHelp", new Payloads.PlayerTrajectoryHelp(playerId, enabled));
    }

    /** Convenience overload for callers (mainly tests) that don't care about the no-help risk/reward bonus — assumes help was used, i.e. no bonus. */
    public synchronized FireOutcome fire(String playerId, String requestId, String weaponId, double angleDeg, double power) {
        return fire(playerId, requestId, weaponId, angleDeg, power, true);
    }

    public synchronized FireOutcome fire(String playerId, String requestId, String weaponId, double angleDeg,
                                          double power, boolean trajectoryHelpUsed) {
        if (status != Status.IN_PROGRESS) {
            return FireOutcome.rejected("MATCH_NOT_IN_PROGRESS");
        }
        String activePlayerId = turnOrder.get(currentTurnIndex);
        if (!activePlayerId.equals(playerId)) {
            return FireOutcome.rejected("NOT_YOUR_TURN");
        }
        MatchPlayer shooter = players.get(playerId);
        if (shooter == null || shooter.departed || !shooter.player.tank.alive) {
            return FireOutcome.rejected("NOT_YOUR_TURN");
        }
        // M3: validated against the player's loadout, including quantity. -1 is the
        // "unlimited" convention (protocol.md MatchStateSync note) and never decrements;
        // any other value is a real remaining count, rejected at 0 and decremented on
        // a successful fire. This applies uniformly to weapons and shields (shields are
        // also loadout entries, consumed on activation since there's no shop yet).
        Integer qty = shooter.player.loadout.get(weaponId);
        if (qty == null) {
            return FireOutcome.rejected("INVALID_WEAPON");
        }
        if (qty == 0) {
            return FireOutcome.rejected("OUT_OF_AMMO");
        }

        cancelPendingTimeout();

        if (qty != -1) {
            shooter.player.loadout.put(weaponId, qty - 1);
        }

        Payloads.ShotResolved resolved = resolveShot(shooter, weaponId, angleDeg, power, trajectoryHelpUsed);
        resolveTurnAdvance(resolved, requestId);
        return FireOutcome.ok(resolved);
    }

    /**
     * Single mutation point for "this turn is over": broadcasts the shot (if
     * any), advances turnsThisRound, checks the round-end condition, and
     * either ends the round/match or advances to the next alive+active
     * player and starts their turn. Both {@link #fire} and
     * {@link #onTurnTimeout} funnel through here while holding the monitor,
     * so a turn can only be resolved once (see class javadoc).
     */
    private void resolveTurnAdvance(Payloads.ShotResolved resolved, String requestId) {
        if (resolved != null) {
            broadcast("ShotResolved", requestId, resolved);
        }
        turnsThisRound++;

        List<String> aliveIds = aliveNonDepartedIds();
        // A disconnected (but not yet past the full reconnect-grace period,
        // so still counted "alive/non-departed") player's turn always ends
        // in an auto-skip lapse (resolved == null), never a Fire. So if a
        // lapse just happened and at most one of the remaining alive players
        // is actually still connected, the game is effectively over — per
        // user feedback, end it now rather than making the sole connected
        // player wait out everyone else's full ~120s grace period one at a
        // time. Only checked on a lapse, not a normal Fire, so a brief
        // reconnect blip doesn't instantly end an otherwise-live round.
        List<String> connectedAliveIds = aliveIds.stream()
                .filter(id -> {
                    MatchPlayer p = players.get(id);
                    return p != null && p.connected;
                })
                .toList();
        boolean lastConnectedPlayerStanding = resolved == null && aliveIds.size() > 1
                && connectedAliveIds.size() <= 1;

        if (aliveIds.size() <= 1 || turnsThisRound >= MAX_TURNS_PER_ROUND) {
            endRound(aliveIds);
            return;
        }
        if (lastConnectedPlayerStanding) {
            // Award the round to the sole connected survivor specifically
            // (not "whoever has the most HP among aliveIds", which could be
            // a disconnected tank that just never took damage) — disconnected
            // players who effectively forfeited don't get the round-survival
            // cash bonus either, only whoever actually saw it through.
            endRound(connectedAliveIds);
            return;
        }

        advanceTurnIndex();
        beginTurn();
    }

    private void advanceTurnIndex() {
        int n = turnOrder.size();
        for (int step = 1; step <= n; step++) {
            int idx = (currentTurnIndex + step) % n;
            MatchPlayer p = players.get(turnOrder.get(idx));
            if (p != null && !p.departed && p.player.tank.alive) {
                currentTurnIndex = idx;
                return;
            }
        }
    }

    private List<String> aliveNonDepartedIds() {
        List<String> alive = new ArrayList<>();
        for (String pid : turnOrder) {
            MatchPlayer p = players.get(pid);
            if (p != null && !p.departed && p.player.tank.alive) {
                alive.add(pid);
            }
        }
        return alive;
    }

    private void endRound(List<String> aliveIds) {
        cancelPendingTimeout();

        String winnerId = null;
        if (aliveIds.size() == 1) {
            winnerId = aliveIds.get(0);
        } else if (aliveIds.size() > 1) {
            // 60-turn safety cap draw: award to highest remaining health (PLAN.md 4.3).
            winnerId = aliveIds.stream()
                    .max(Comparator.comparingDouble(pid -> players.get(pid).player.tank.health))
                    .orElse(null);
        }

        // M2 economy bookkeeping is best-effort/minimal (real shop economy is M4):
        // a flat round-survival bonus to everyone still alive at round end.
        for (String pid : aliveIds) {
            players.get(pid).player.cash += ROUND_SURVIVAL_BONUS;
        }
        if (winnerId != null) {
            players.get(winnerId).player.cash += ROUND_WIN_BONUS;
        }

        List<Payloads.Standing> standings = new ArrayList<>();
        for (MatchPlayer mp : players.values()) {
            standings.add(new Payloads.Standing(mp.player.playerId, mp.player.cash));
        }
        standings.sort((a, b) -> Integer.compare(b.cash, a.cash));

        broadcast("RoundEnded", new Payloads.RoundEnded(winnerId, standings));

        if (roundNumber >= config.maxRounds()) {
            endMatch();
        } else {
            openShop();
        }
    }

    // =================================================================
    // Shop (M4, PLAN.md 2.3/4.5, shared/protocol.md section 5): a timed
    // between-round phase where cash earned in the round just ended can be
    // spent on additional weapon/shield quantities before the next round's
    // fresh terrain and full-health respawn.
    // =================================================================

    private void openShop() {
        cancelPendingTimeout();
        status = Status.SHOP;
        shopToken++;
        int myShopToken = shopToken;
        shopReadyPlayerIds.clear();

        shopStockRemaining.clear();
        for (WeaponDef w : WeaponDef.all().values()) {
            if (w.defaultQty() != -1) {
                shopStockRemaining.put(w.weaponId(), w.shopStock());
            }
        }
        for (ShieldDef s : ShieldDef.all().values()) {
            shopStockRemaining.put(s.shieldId(), s.shopStock());
        }

        Payloads.ShopOpened opened = new Payloads.ShopOpened((int) (shopTimeoutMs / 1000), buildPriceList());
        broadcast("ShopOpened", opened);

        pendingTimeout = scheduler.schedule(() -> onShopTimeout(myShopToken), shopTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private synchronized void onShopTimeout(int token) {
        if (status != Status.SHOP || token != shopToken) {
            return; // stale: a newer shop phase (shouldn't happen) already superseded this one
        }
        advanceToNextRoundOrEnd();
    }

    /**
     * Per user feedback ("do not run the timer, there should be a continue
     * button"): a player signals they're done shopping; once every connected,
     * non-departed player has, the shop ends immediately rather than waiting
     * out the timer. The timer itself stays as an anti-stall safety net (an
     * AFK/disconnected player could otherwise hold the match hostage
     * indefinitely) — it just isn't the primary way a shop phase ends anymore.
     */
    public synchronized void shopContinue(String playerId) {
        if (status != Status.SHOP) {
            return;
        }
        MatchPlayer mp = players.get(playerId);
        if (mp == null || mp.departed) {
            return;
        }
        shopReadyPlayerIds.add(playerId);

        boolean allReady = true;
        for (MatchPlayer p : players.values()) {
            if (p.departed || !p.connected) {
                continue; // a departed/disconnected player can't click Continue; don't block on them
            }
            if (!shopReadyPlayerIds.contains(p.player.playerId)) {
                allReady = false;
                break;
            }
        }
        if (allReady) {
            cancelPendingTimeout();
            advanceToNextRoundOrEnd();
        }
    }

    private void advanceToNextRoundOrEnd() {
        roundNumber++;
        rotateTurnOrder();
        if (turnOrder.isEmpty()) {
            status = Status.COMPLETE;
        } else {
            // startRound() itself doesn't touch status (it's also called from
            // startMatch(), which already set IN_PROGRESS) — the SHOP -> next
            // round transition is the one path that needs to flip it back.
            status = Status.IN_PROGRESS;
            startRound();
        }
    }

    /** basic_shell (defaultQty -1, "unlimited") is excluded: there's nothing meaningful to buy more of. */
    private List<Payloads.PriceListEntry> buildPriceList() {
        List<Payloads.PriceListEntry> list = new ArrayList<>();
        for (WeaponDef w : WeaponDef.all().values()) {
            if (w.defaultQty() == -1) {
                continue;
            }
            list.add(new Payloads.PriceListEntry(w.weaponId(), "WEAPON", w.price(),
                    shopStockRemaining.getOrDefault(w.weaponId(), 0)));
        }
        for (ShieldDef s : ShieldDef.all().values()) {
            list.add(new Payloads.PriceListEntry(s.shieldId(), "SHIELD", s.price(),
                    shopStockRemaining.getOrDefault(s.shieldId(), 0)));
        }
        return list;
    }

    /**
     * Validates and applies one {@code ShopPurchase}. Rejections are reported
     * via {@code ErrorMsg} per protocol.md section 6 ("Insufficient funds or
     * an out-of-phase purchase gets an ErrorMsg/rejection"), not a dedicated
     * rejection type like {@code FireRejected} — shop purchases aren't the
     * high-frequency case that distinction is for.
     */
    public synchronized PurchaseOutcome purchase(String playerId, String itemId, String itemType, int quantity) {
        MatchPlayer buyer = players.get(playerId);
        if (buyer == null || buyer.departed) {
            return PurchaseOutcome.rejected("UNKNOWN_PLAYER");
        }
        if (status != Status.SHOP) {
            return PurchaseOutcome.rejected("NOT_SHOP_PHASE");
        }
        if (quantity <= 0) {
            return PurchaseOutcome.rejected("INVALID_QUANTITY");
        }

        int price;
        if ("WEAPON".equals(itemType)) {
            WeaponDef w = WeaponDef.byId(itemId);
            if (w == null || w.defaultQty() == -1) {
                return PurchaseOutcome.rejected("INVALID_ITEM");
            }
            price = w.price();
        } else if ("SHIELD".equals(itemType)) {
            ShieldDef s = ShieldDef.byId(itemId);
            if (s == null) {
                return PurchaseOutcome.rejected("INVALID_ITEM");
            }
            price = s.price();
        } else {
            return PurchaseOutcome.rejected("INVALID_ITEM");
        }

        long totalCost = (long) price * quantity;
        if (buyer.player.cash < totalCost) {
            return PurchaseOutcome.rejected("INSUFFICIENT_CASH");
        }

        // Shared match-wide stock pool (M4 addition, see WeaponDef.shopStock):
        // first-come-first-served across all players, checked/decremented
        // here so a race between two players' near-simultaneous purchases is
        // resolved correctly (this method is synchronized on the match).
        int remaining = shopStockRemaining.getOrDefault(itemId, 0);
        if (quantity > remaining) {
            return PurchaseOutcome.rejected("OUT_OF_STOCK");
        }

        buyer.player.cash -= (int) totalCost;
        buyer.player.loadout.merge(itemId, quantity, Integer::sum);
        shopStockRemaining.put(itemId, remaining - quantity);

        Payloads.ShopUpdate update = new Payloads.ShopUpdate(
                playerId, buyer.player.cash, new LinkedHashMap<>(buyer.player.loadout),
                new LinkedHashMap<>(shopStockRemaining));
        broadcast("ShopUpdate", update);
        return PurchaseOutcome.ok(update);
    }

    private void endMatch() {
        status = Status.COMPLETE;
        List<Payloads.FinalStanding> finalStandings = new ArrayList<>();
        for (MatchPlayer mp : players.values()) {
            finalStandings.add(new Payloads.FinalStanding(
                    mp.player.playerId, mp.player.cash, mp.damageDealt, mp.kills));
        }
        finalStandings.sort((a, b) -> Integer.compare(b.cash, a.cash));
        broadcast("MatchEnded", new Payloads.MatchEnded(finalStandings));
    }

    private void cancelPendingTimeout() {
        if (pendingTimeout != null) {
            pendingTimeout.cancel(false);
            pendingTimeout = null;
        }
    }

    // =================================================================
    // Shot resolution (reuses M1 domain code as-is)
    // =================================================================

    /**
     * One blast detonation point within a shot: most weapons produce exactly
     * one; MIRV (children) and cluster bomb (primary + bomblets) produce
     * several, all merged into a single {@code ShotResolved} per
     * shared/protocol.md 4 ("this document does not mandate a separate
     * per-child sub-message at v1").
     */
    private record DetonationSpec(double x, double y, double blastRadius, double centerDamage, double craterDepthMultiplier) {
        DetonationSpec(double x, double y, double blastRadius, double centerDamage) {
            this(x, y, blastRadius, centerDamage, 1.0);
        }
    }

    private static final double[] MIRV_SPREAD_OFFSETS_DEG = {-15.0, -5.0, 5.0, 15.0};
    private static final double[] CLUSTER_BOMBLET_OFFSETS_X = {-50.0, -25.0, 25.0, 50.0};
    // Cosmetic, zero-damage detonations that give Tunneling/Bouncing a
    // visible terrain track (a bore shaft, a line of skip marks) instead of
    // just the one final crater every other STANDARD-shaped weapon leaves.
    private static final double TUNNEL_TRACK_RADIUS = 10.0;
    private static final double BOUNCE_SKIP_MARK_RADIUS = 8.0;
    // Digger reuses Tunneling Shot's TUNNELING behavior (see WeaponDef.DIGGER)
    // but with its own shallower penetration depth than
    // ProjectileSim.TUNNELING_MAX_PENETRATION's 160, so it reads as a
    // shorter bore-then-big-crater rather than a long tunnel. Was 70 — per
    // user feedback ("did not see the terrain go.. it just went into the
    // terrain"), that read as no tunnel at all, the same complaint
    // Tunneling Shot itself had before its own penetration was extended
    // (see ProjectileSim.TUNNELING_MAX_PENETRATION's history). 110 keeps
    // Digger distinctly shorter than Tunneling Shot while actually reading
    // as a bore, not a disappearance.
    private static final double DIGGER_MAX_PENETRATION = 110.0;
    // Fraction of Bouncing Betty's centerDamage dealt at each bounce point to
    // any tank within BOUNCE_DAMAGE_RADIUS (per user feedback: "make it
    // bounce, damage on bounce"). No blast-radius falloff — a bounce is a
    // glancing hit, not a detonation.
    private static final double BOUNCE_DAMAGE_FRACTION = 0.25;
    // Deliberately wider than ProjectileSim.TANK_HITBOX_RADIUS (14): a tank
    // within that tighter radius would already have been caught by
    // ProjectileSim's own per-step tank-hit check *during* the simulation,
    // terminating the shot as a normal direct hit before a bounce there was
    // ever recorded — so bouncePoints can never legitimately contain a point
    // within 14 units of a live tank. Using the same radius here would make
    // this whole mechanic unreachable dead code; a wider "clipped by the
    // bounce, not squarely hit" radius is a genuinely different case.
    private static final double BOUNCE_DAMAGE_RADIUS = 30.0;

    // Risk/reward bonus for firing without Trajectory Help (user decision,
    // 2026-08-23: "if you choose NOT to use the trajectory help, your
    // earnings are doubled and damage is 25% more"). Applied in
    // applyDetonations to this shot's own blast/bounce damage and the cash
    // earned from it — deliberately NOT applied to fall damage (an
    // incidental terrain-collapse side effect, not something the shooter's
    // aim choice caused). Since Trajectory Help is permanently unavailable
    // for Nuke (see FireControls.svelte's trajectoryHelpUnavailable), every
    // Nuke shot always gets this bonus (confirmed with the user).
    private static final double NO_HELP_DAMAGE_MULTIPLIER = 1.25;
    private static final double NO_HELP_CASH_MULTIPLIER = 2.0;

    // Tank-fall tuning (PLAN.md-adjacent, per live-playtest feedback): a
    // tank whose column's ground drops out from under it (crater, gully, or
    // the post-crater slope-settle pass) snaps down to the new ground level
    // for free below this drop distance; past it, a Scorched-Earth-style
    // "fell too far" fall damage applies, scaled by how far past the
    // threshold and capped.
    private static final double FALL_SNAP_EPSILON = 0.5;
    private static final double FALL_DAMAGE_THRESHOLD = 60.0;
    private static final double FALL_DAMAGE_RATE = 0.5;
    private static final double FALL_DAMAGE_CAP = 40.0;

    // Mirrors tankRenderer.ts's TANK_WORLD_HEIGHT/BARREL_LENGTH so shots launch
    // from the same barrel tip the client draws, not the tank's ground-level
    // x/y. Keep these two files' geometry in sync by hand if the tank shape
    // changes again.
    static final double TANK_WORLD_HEIGHT = 17.0;
    static final double BARREL_LENGTH = 30.0 * 1.15;

    private Payloads.ShotResolved resolveShot(MatchPlayer shooter, String weaponId, double angleDeg, double power,
                                               boolean trajectoryHelpUsed) {
        if (ShieldDef.isShieldId(weaponId)) {
            return resolveShieldActivation(shooter, weaponId);
        }

        WeaponDef weapon = WeaponDef.byId(weaponId);
        double clampedPower = Math.max(0, Math.min(100, power));

        List<ProjectileSim.TankTarget> targets = new ArrayList<>();
        for (MatchPlayer p : players.values()) {
            if (p.player.playerId.equals(shooter.player.playerId) || p.departed || !p.player.tank.alive) {
                continue;
            }
            targets.add(new ProjectileSim.TankTarget(p.player.playerId, p.player.tank.x, p.player.tank.y));
        }

        // Launch from the barrel tip (turret pivot + BARREL_LENGTH along the
        // fire angle), matching what the player actually sees on screen,
        // rather than the tank's ground-level x/y.
        double turretX = shooter.player.tank.x;
        double turretY = shooter.player.tank.y - TANK_WORLD_HEIGHT;
        double angleRad = Math.toRadians(angleDeg);
        double startX = turretX + Math.cos(angleRad) * BARREL_LENGTH;
        double startY = turretY - Math.sin(angleRad) * BARREL_LENGTH;

        List<double[]> mergedTrajectory = new ArrayList<>();
        List<DetonationSpec> detonations = new ArrayList<>();
        // Only populated by the BOUNCING case: direct-hit-only damage points
        // (no blast falloff) for a tank actually skipped over mid-flight.
        List<double[]> bounceDamagePoints = List.of();
        double bounceDamagePerHit = 0.0;
        double impactX;
        double impactY;

        switch (weapon.behavior()) {
            case TUNNELING -> {
                // Digger reuses this same behavior (see WeaponDef.DIGGER) with
                // its own shallower penetration cap instead of Tunneling
                // Shot's — everything else about the dispatch is identical.
                double maxPenetration = "digger".equals(weaponId)
                        ? DIGGER_MAX_PENETRATION : ProjectileSim.TUNNELING_MAX_PENETRATION;
                ProjectileSim.Result sim = ProjectileSim.simulate(startX, startY, angleDeg, clampedPower,
                        windStrength, terrain, targets, WeaponDef.Behavior.TUNNELING,
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false,
                        maxPenetration, weapon.homingStrength());
                mergedTrajectory.addAll(sim.resampledTrajectory);
                // Bore track: a small, shallow, zero-damage crater at every
                // step of the shot's actual underground path (not a few
                // dots interpolated between entry/exit), so the tunnel
                // visibly follows the real curved trajectory instead of
                // reading as nothing at all next to the final crater.
                for (double[] point : sim.undergroundPath) {
                    detonations.add(new DetonationSpec(point[0], point[1], TUNNEL_TRACK_RADIUS, 0));
                }
                detonations.add(new DetonationSpec(sim.impactX, sim.impactY, weapon.blastRadius(), weapon.centerDamage(),
                        weapon.craterDepthMultiplier()));
                impactX = sim.impactX;
                impactY = sim.impactY;
            }
            case BOUNCING -> {
                ProjectileSim.Result sim = ProjectileSim.simulate(startX, startY, angleDeg, clampedPower,
                        windStrength, terrain, targets, WeaponDef.Behavior.BOUNCING,
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false);
                mergedTrajectory.addAll(sim.resampledTrajectory);
                // Skip marks: a small, shallow, zero-damage ding at each bounce
                // point before the final detonation, like a skipped stone —
                // plus (per user feedback: "make it bounce, damage on bounce")
                // a direct-hit-only damage check against any tank actually
                // skipped over, applied below via applyDetonations so it merges
                // into the same health/DamageEvent bookkeeping as everything
                // else in this shot. The bounce sequence doesn't stop early on
                // a hit — every bounce still happens regardless.
                for (double[] bp : sim.bouncePoints) {
                    detonations.add(new DetonationSpec(bp[0], bp[1], BOUNCE_SKIP_MARK_RADIUS, 0));
                }
                bounceDamagePoints = sim.bouncePoints;
                bounceDamagePerHit = weapon.centerDamage() * BOUNCE_DAMAGE_FRACTION;
                detonations.add(new DetonationSpec(sim.impactX, sim.impactY, weapon.blastRadius(), weapon.centerDamage()));
                impactX = sim.impactX;
                impactY = sim.impactY;
            }
            case MIRV -> {
                // Simplification (see class notes / report): find the apex via a
                // STANDARD-shaped simulate() call with stopAtApex=true, then re-simulate
                // 4 children from that point at +/-5/15 degree spread around the actual
                // apex velocity direction (near-horizontal, since vy~=0 at the apex by
                // definition) — NOT the original launch angle. Re-using the original
                // angle here was a bug: for any shot steeper than ~horizontal, children
                // would rocket further upward off the split point instead of fanning
                // outward and immediately arcing down, which reads as "the MIRV fires
                // uselessly straight up". If the shot hits something before ever
                // reaching an apex (e.g. a very steep, short, near-point-blank shot),
                // it detonates as a single non-split blast using the per-child stats
                // rather than splitting mid-flight.
                ProjectileSim.Result apexSim = ProjectileSim.simulate(startX, startY, angleDeg, clampedPower,
                        windStrength, terrain, targets, WeaponDef.Behavior.STANDARD,
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), true);
                mergedTrajectory.addAll(apexSim.resampledTrajectory);
                if (!apexSim.stoppedAtApex) {
                    detonations.add(new DetonationSpec(apexSim.impactX, apexSim.impactY, weapon.blastRadius(), weapon.centerDamage()));
                    impactX = apexSim.impactX;
                    impactY = apexSim.impactY;
                } else {
                    // mergedTrajectory intentionally stays just the shared apex
                    // ascent here: the client plays this list back as ONE
                    // continuous flight with a single animated dot
                    // (GameCanvas/projectileRenderer), so appending each
                    // child's full post-split descent path after it would
                    // make that dot repeatedly jump backward to the split
                    // point to "fly" the next child — 4 visible rewinds.
                    // Craters/damage from every child still land correctly
                    // via detonations below; only the animated flight path
                    // is limited to the one shared, non-rewinding leg.
                    // Reconstruct an equivalent angle/power pair from the apex's actual
                    // velocity so each child continues from where the parent really was
                    // heading (near-horizontal), not from the original steep launch angle.
                    double apexAngleDeg = Math.toDegrees(Math.atan2(-apexSim.finalVy, apexSim.finalVx));
                    double apexSpeed = Math.hypot(apexSim.finalVx, apexSim.finalVy);
                    double apexPower = apexSpeed / ProjectileSim.POWER_SCALE;
                    for (double offset : MIRV_SPREAD_OFFSETS_DEG) {
                        ProjectileSim.Result child = ProjectileSim.simulate(
                                apexSim.impactX, apexSim.impactY, apexAngleDeg + offset, apexPower,
                                windStrength, terrain, targets, WeaponDef.Behavior.STANDARD, 1.0, 1.0, false);
                        detonations.add(new DetonationSpec(child.impactX, child.impactY, weapon.blastRadius(), weapon.centerDamage()));
                    }
                    // Reported impact is the split point itself, the one meaningful
                    // single point for a multi-child shot.
                    impactX = apexSim.impactX;
                    impactY = apexSim.impactY;
                }
            }
            case CLUSTER -> {
                ProjectileSim.Result sim = ProjectileSim.simulate(startX, startY, angleDeg, clampedPower,
                        windStrength, terrain, targets, WeaponDef.Behavior.STANDARD,
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false);
                mergedTrajectory.addAll(sim.resampledTrajectory);
                impactX = sim.impactX;
                impactY = sim.impactY;
                // Primary detonation uses the weapon's primary blastRadius/centerDamage.
                detonations.add(new DetonationSpec(impactX, impactY, weapon.blastRadius(), weapon.centerDamage()));
                // 4 bomblets at fixed sideways world-x offsets (no real bomblet flight
                // physics, per task spec's explicit simplification): each detonates at
                // the local ground height sampled *before* any of this shot's craters are
                // applied (bomblets are conceptually simultaneous with the primary hit),
                // using the bomblet blast radius but the same center damage as primary.
                for (double offset : CLUSTER_BOMBLET_OFFSETS_X) {
                    int bombletX = (int) Math.round(Math.max(0, Math.min(terrain.width() - 1, impactX + offset)));
                    double bombletY = terrain.heightAt(bombletX);
                    detonations.add(new DetonationSpec(bombletX, bombletY, weapon.bombletBlastRadius(), weapon.centerDamage()));
                }
            }
            default -> { // STANDARD (DIGGER now dispatches via TUNNELING above)
                ProjectileSim.Result sim = ProjectileSim.simulate(startX, startY, angleDeg, clampedPower,
                        windStrength, terrain, targets, WeaponDef.Behavior.STANDARD,
                        weapon.powerScaleMultiplier(), weapon.gravityMultiplier(), false,
                        ProjectileSim.TUNNELING_MAX_PENETRATION, weapon.homingStrength());
                mergedTrajectory.addAll(sim.resampledTrajectory);
                detonations.add(new DetonationSpec(sim.impactX, sim.impactY, weapon.blastRadius(), weapon.centerDamage(),
                        weapon.craterDepthMultiplier()));
                impactX = sim.impactX;
                impactY = sim.impactY;
            }
        }

        return applyDetonations(shooter, weaponId, mergedTrajectory, impactX, impactY, detonations,
                bounceDamagePoints, bounceDamagePerHit, trajectoryHelpUsed);
    }

    /**
     * Shield activation (PLAN.md 4.4: "Shields are activated by spending a
     * turn... the Fire message's weaponId can reference a shield id;
     * RESOLVING sets activeShieldId instead of running projectile physics").
     *
     * <p><b>Wire encoding choice</b> (protocol.md doesn't mandate a shape for
     * this, since it predates M3): to keep every turn resolving as a normal
     * {@code ShotResolved} broadcast (so clients don't need a special-case
     * message type), this sends a zero-length "shot": a single trajectory
     * point and impact at the shooter's own tank position, and a one-column
     * {@code terrainDelta} at that same column reporting the (unchanged)
     * current height there — a genuine no-op delta, not a real crater.
     * {@code damageEvents}/{@code cashEarned} are empty.
     */
    private Payloads.ShotResolved resolveShieldActivation(MatchPlayer shooter, String shieldId) {
        shooter.player.activeShieldId = shieldId;
        shooter.player.shieldAbsorbedSoFar = 0;

        int tankX = (int) Math.round(shooter.player.tank.x);
        double tankY = shooter.player.tank.y;
        int col = Math.max(0, Math.min(terrain.width() - 1, tankX));

        Payloads.ShotResolved resolved = new Payloads.ShotResolved();
        resolved.shooterId = shooter.player.playerId;
        resolved.weaponId = shieldId;
        resolved.trajectory = new ArrayList<>();
        resolved.trajectory.add(new Payloads.TrajectoryPoint(tankX, tankY));
        resolved.impact = new Payloads.Impact(tankX, tankY);
        resolved.terrainDelta = new Payloads.TerrainDelta(col, col, new int[] {terrain.heightAt(col)});
        resolved.damageEvents = new ArrayList<>();
        resolved.cashEarned = new ArrayList<>();
        resolved.tankFalls = new ArrayList<>();
        resolved.ammoRemaining = shooter.player.loadout.getOrDefault(shieldId, -1);
        resolved.allImpacts = List.of(resolved.impact);
        return resolved;
    }

    /**
     * Applies one or more detonations (craters + damage) from a single shot
     * and merges the result into one {@code ShotResolved}, per-target shield
     * mitigation applied before health is subtracted (PLAN.md 4.3). Detonations
     * are processed in order so a multi-impact weapon's later hits see the
     * damage/shield state left by its earlier hits within the same shot.
     */
    private Payloads.ShotResolved applyDetonations(MatchPlayer shooter, String weaponId,
                                                     List<double[]> trajectory, double impactX, double impactY,
                                                     List<DetonationSpec> detonations,
                                                     List<double[]> bounceDamagePoints, double bounceDamagePerHit,
                                                     boolean trajectoryHelpUsed) {
        double damageMultiplier = trajectoryHelpUsed ? 1.0 : NO_HELP_DAMAGE_MULTIPLIER;
        double cashMultiplier = trajectoryHelpUsed ? 1.0 : NO_HELP_CASH_MULTIPLIER;
        Map<String, Double> workingHealth = new LinkedHashMap<>();
        for (MatchPlayer p : players.values()) {
            if (!p.departed && p.player.tank.alive) {
                workingHealth.put(p.player.playerId, p.player.tank.health);
            }
        }

        Map<String, Payloads.DamageEvent> damageByPlayer = new LinkedHashMap<>();
        List<Payloads.CashEarned> cashEarned = new ArrayList<>();
        int cashFromDamage = 0;
        int minStart = Integer.MAX_VALUE;
        int maxEnd = Integer.MIN_VALUE;

        // Bouncing Betty: direct-hit-only damage at each bounce point (no
        // blast-radius falloff — a bounce is a glancing hit, not a real
        // detonation), applied before the normal detonations loop below so
        // it merges into the same workingHealth/damageByPlayer bookkeeping
        // as the final blast (one consistent DamageEvent per tank, not two
        // conflicting ones).
        //
        // connectedBouncePoints tracks which bounce points actually landed a
        // hit, so they can be folded into allImpacts below — without this,
        // a bounce that damages a tank produces no client-visible flash at
        // all (only the health change), since bounce points are otherwise
        // added to `detonations` with centerDamage=0 (cosmetic skip-mark
        // only) and excluded from allImpacts on that basis.
        double effectiveBounceDamagePerHit = bounceDamagePerHit * damageMultiplier;
        List<double[]> connectedBouncePoints = new ArrayList<>();
        for (double[] bp : bounceDamagePoints) {
            for (MatchPlayer p : players.values()) {
                Double hp = workingHealth.get(p.player.playerId);
                if (hp == null || hp <= 0.0) {
                    continue;
                }
                if (distance(p.player.tank.x, p.player.tank.y, bp[0], bp[1]) > BOUNCE_DAMAGE_RADIUS) {
                    continue;
                }
                double mitigatedDamage = applyShieldMitigation(p, effectiveBounceDamagePerHit, true, cashEarned);
                double newHealth = Math.max(0.0, Math.round(hp - mitigatedDamage));
                boolean eliminated = newHealth <= 0.0;
                workingHealth.put(p.player.playerId, newHealth);
                connectedBouncePoints.add(bp);

                Payloads.DamageEvent existing = damageByPlayer.get(p.player.playerId);
                double cumulativeDamage = (existing != null ? existing.damage : 0.0) + mitigatedDamage;
                damageByPlayer.put(p.player.playerId,
                        new Payloads.DamageEvent(p.player.playerId, cumulativeDamage, newHealth, eliminated, p.player.activeShieldId));

                if (!p.player.playerId.equals(shooter.player.playerId)) {
                    cashFromDamage += (int) Math.round(mitigatedDamage * DamageCalculator.CASH_PER_DAMAGE * cashMultiplier);
                }
            }
        }

        for (DetonationSpec d : detonations) {
            Terrain.CraterResult crater = terrain.applyCrater(
                    (int) Math.round(d.x()), d.blastRadius(), d.craterDepthMultiplier());
            minStart = Math.min(minStart, crater.startX());
            maxEnd = Math.max(maxEnd, crater.endX());

            List<DamageCalculator.TankState> tankStates = new ArrayList<>();
            for (MatchPlayer p : players.values()) {
                Double hp = workingHealth.get(p.player.playerId);
                if (hp == null) {
                    continue;
                }
                tankStates.add(new DamageCalculator.TankState(p.player.playerId, p.player.tank.x, p.player.tank.y, hp));
            }

            DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                    shooter.player.playerId, d.x(), d.y(), d.blastRadius(), d.centerDamage() * damageMultiplier, tankStates);

            for (DamageCalculator.DamageResult dr : outcome.damageEvents) {
                MatchPlayer target = players.get(dr.playerId());
                boolean isDirectHit = distance(target.player.tank.x, target.player.tank.y, d.x(), d.y())
                        <= DamageCalculator.DIRECT_HIT_RADIUS;
                double mitigatedDamage = applyShieldMitigation(target, dr.damage(), isDirectHit, cashEarned);

                double prevHealth = workingHealth.get(dr.playerId());
                double newHealth = Math.max(0.0, Math.round(prevHealth - mitigatedDamage));
                boolean eliminated = newHealth <= 0.0;
                workingHealth.put(dr.playerId(), newHealth);

                Payloads.DamageEvent existing = damageByPlayer.get(dr.playerId());
                double cumulativeDamage = (existing != null ? existing.damage : 0.0) + mitigatedDamage;
                damageByPlayer.put(dr.playerId(), new Payloads.DamageEvent(dr.playerId(), cumulativeDamage, newHealth, eliminated, target.player.activeShieldId));

                if (!dr.playerId().equals(shooter.player.playerId)) {
                    cashFromDamage += (int) Math.round(mitigatedDamage * DamageCalculator.CASH_PER_DAMAGE * cashMultiplier);
                }
            }
        }

        // Slope settling: erode any implausibly steep cliff the craters above
        // just cut into flat ground (heightmap-appropriate stand-in for
        // Scorched Earth's real per-pixel "unsupported dirt falls" rule).
        if (minStart <= maxEnd) {
            int[] settled = terrain.settleSlopes(minStart, maxEnd);
            minStart = Math.min(minStart, settled[0]);
            maxEnd = Math.max(maxEnd, settled[1]);
        }

        // Tank falls: any surviving tank whose column's ground is now lower
        // than it (undermined by a crater/gully, or by the settle pass
        // above) drops to the new ground level, taking fall damage past a
        // threshold. Merged into the same damageByPlayer/workingHealth/
        // cashFromDamage bookkeeping as blast damage so a tank that's both
        // blasted and falls gets one consistent final health change.
        List<Payloads.TankFall> tankFalls = new ArrayList<>();
        for (MatchPlayer p : players.values()) {
            if (p.departed || !p.player.tank.alive) {
                continue;
            }
            Double hp = workingHealth.get(p.player.playerId);
            if (hp == null || hp <= 0.0) {
                continue;
            }
            int col = Math.max(0, Math.min(terrain.width() - 1, (int) Math.round(p.player.tank.x)));
            double groundY = terrain.heightAt(col);
            if (groundY <= p.player.tank.y + FALL_SNAP_EPSILON) {
                continue;
            }
            double dropDistance = groundY - p.player.tank.y;
            p.player.tank.y = groundY;
            tankFalls.add(new Payloads.TankFall(p.player.playerId, groundY));

            if (dropDistance > FALL_DAMAGE_THRESHOLD) {
                double fallDamage = Math.min(FALL_DAMAGE_CAP,
                        Math.round((dropDistance - FALL_DAMAGE_THRESHOLD) * FALL_DAMAGE_RATE));
                double newHealth = Math.max(0.0, Math.round(hp - fallDamage));
                boolean eliminated = newHealth <= 0.0;
                workingHealth.put(p.player.playerId, newHealth);

                Payloads.DamageEvent existing = damageByPlayer.get(p.player.playerId);
                double cumulativeDamage = (existing != null ? existing.damage : 0.0) + fallDamage;
                damageByPlayer.put(p.player.playerId,
                        new Payloads.DamageEvent(p.player.playerId, cumulativeDamage, newHealth, eliminated, p.player.activeShieldId));

                if (!p.player.playerId.equals(shooter.player.playerId)) {
                    cashFromDamage += (int) Math.round(fallDamage * DamageCalculator.CASH_PER_DAMAGE);
                }
            }
        }

        if (cashFromDamage > 0) {
            shooter.player.cash += cashFromDamage;
            cashEarned.add(new Payloads.CashEarned(shooter.player.playerId, cashFromDamage));
        }

        List<Payloads.DamageEvent> damageEvents = new ArrayList<>();
        for (Payloads.DamageEvent ev : damageByPlayer.values()) {
            MatchPlayer target = players.get(ev.playerId);
            target.player.tank.health = ev.newHealth;
            if (ev.eliminated) {
                target.player.tank.alive = false;
            }
            damageEvents.add(ev);

            if (!ev.playerId.equals(shooter.player.playerId)) {
                shooter.damageDealt += (int) Math.round(ev.damage);
                if (ev.eliminated) {
                    shooter.kills++;
                    shooter.player.cash += ELIMINATION_BONUS;
                    cashEarned.add(new Payloads.CashEarned(shooter.player.playerId, ELIMINATION_BONUS));
                }
            }
        }

        Payloads.ShotResolved resolved = new Payloads.ShotResolved();
        resolved.shooterId = shooter.player.playerId;
        resolved.weaponId = weaponId;
        resolved.trajectory = new ArrayList<>();
        for (double[] pt : trajectory) {
            resolved.trajectory.add(new Payloads.TrajectoryPoint(pt[0], pt[1]));
        }
        resolved.impact = new Payloads.Impact(impactX, impactY);
        if (minStart <= maxEnd) {
            resolved.terrainDelta = new Payloads.TerrainDelta(minStart, maxEnd, terrain.heightsInRange(minStart, maxEnd));
        } else {
            int col = Math.max(0, Math.min(terrain.width() - 1, (int) Math.round(impactX)));
            resolved.terrainDelta = new Payloads.TerrainDelta(col, col, new int[] {terrain.heightAt(col)});
        }
        resolved.damageEvents = damageEvents;
        resolved.cashEarned = cashEarned;
        resolved.tankFalls = tankFalls;
        resolved.ammoRemaining = shooter.player.loadout.getOrDefault(weaponId, -1);
        // Every real (damage-capable) detonation, so the client can flash an
        // explosion at each one — not just the shared `impact` point, which
        // for MIRV/Cluster Bomb is only the split point / primary hit while
        // the children/bomblets actually land (and crater) elsewhere.
        // Cosmetic zero-damage marks (tunnel bore track, bounce skip marks
        // that missed) are excluded; those already read as a trail, not
        // explosions. Bounce points that DID connect (in connectedBouncePoints)
        // are real damage events despite carrying centerDamage=0 in
        // `detonations` (a bounce is a direct-hit-only graze, not a blast
        // radius detonation — see the bounce-damage loop above) — added
        // first so the actual final detonation (always last in `detonations`
        // for a BOUNCING shot) stays the last entry, which the client relies
        // on to distinguish "grazed" from "exploded".
        List<Payloads.Impact> allImpacts = new ArrayList<>();
        for (double[] bp : connectedBouncePoints) {
            allImpacts.add(new Payloads.Impact(bp[0], bp[1]));
        }
        for (DetonationSpec d : detonations) {
            if (d.centerDamage() > 0) {
                allImpacts.add(new Payloads.Impact(d.x(), d.y()));
            }
        }
        if (allImpacts.isEmpty()) {
            allImpacts.add(resolved.impact);
        }
        resolved.allImpacts = allImpacts;
        return resolved;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Shield mitigation, applied before subtracting from health (PLAN.md
     * 4.3). Contained here rather than in {@link DamageCalculator} per the
     * task spec ("wrap/post-process around it in Match" rather than rewrite
     * its falloff math). Judgment calls on exactly when each shield clears
     * {@code activeShieldId} (PLAN.md 4.4's wording is loose on this):
     * <ul>
     *   <li><b>Absorb</b> clears once cumulative absorbed damage (rawDamage -
     *       mitigatedDamage, summed across hits since activation) would
     *       exceed the 80 threshold — "breaking" mid-resolution of the hit
     *       that pushes it over.</li>
     *   <li><b>Deflect</b> clears immediately after negating one direct hit
     *       (binary block, "first direct hit... then breaks"); a near-miss
     *       (splash, not a direct hit) applies at 60% and does not break it,
     *       so it keeps blocking direct hits until one lands.</li>
     *   <li><b>Reflect</b> never breaks on its own — it's a standing -30%/
     *       cash-back effect per PLAN.md ("never breaks... true reflection
     *       deferred as stretch goal") — it only clears when the player
     *       spends a later turn activating a different/new shield.</li>
     * </ul>
     * The "20% of blocked damage returned as bonus cash next turn" (Reflect)
     * is simplified to "immediately", documented in the class-level report
     * to the same effect as the PLAN.md text already flags as simplifiable.
     */
    private double applyShieldMitigation(MatchPlayer target, double rawDamage, boolean isDirectHit,
                                          List<Payloads.CashEarned> cashEarnedOut) {
        String shieldId = target.player.activeShieldId;
        if (shieldId == null) {
            return rawDamage;
        }
        ShieldDef shield = ShieldDef.byId(shieldId);
        if (shield == null) {
            return rawDamage;
        }

        if (shield == ShieldDef.ABSORB) {
            double mitigated = rawDamage * shield.damageMultiplier();
            double absorbedThisHit = rawDamage - mitigated;
            target.player.shieldAbsorbedSoFar += absorbedThisHit;
            if (target.player.shieldAbsorbedSoFar >= ShieldDef.ABSORB_BREAK_THRESHOLD) {
                target.player.activeShieldId = null;
                target.player.shieldAbsorbedSoFar = 0;
            }
            return mitigated;
        }

        if (shield == ShieldDef.DEFLECT) {
            if (isDirectHit) {
                target.player.activeShieldId = null; // binary block, breaks after negating one direct hit
                return 0.0;
            }
            return rawDamage * ShieldDef.DEFLECT_NEAR_MISS_MULTIPLIER;
        }

        if (shield == ShieldDef.REFLECT) {
            double mitigated = rawDamage * shield.damageMultiplier();
            double blocked = rawDamage - mitigated;
            int cashBack = (int) Math.round(blocked * ShieldDef.REFLECT_CASHBACK_FRACTION);
            if (cashBack > 0) {
                target.player.cash += cashBack;
                cashEarnedOut.add(new Payloads.CashEarned(target.player.playerId, cashBack));
            }
            return mitigated; // Reflect never breaks on its own (see method javadoc).
        }

        return rawDamage;
    }

    // =================================================================
    // Snapshot/DTO builders
    // =================================================================

    private Payloads.LobbyUpdate buildLobbyUpdate() {
        List<Payloads.LobbyPlayerDto> dtos = new ArrayList<>();
        String hostId = null;
        for (MatchPlayer mp : players.values()) {
            if (mp.departed) {
                continue;
            }
            dtos.add(new Payloads.LobbyPlayerDto(mp.player.playerId, mp.player.displayName, mp.ready, mp.isHost));
            if (mp.isHost) {
                hostId = mp.player.playerId;
            }
        }
        return new Payloads.LobbyUpdate(matchId, dtos, hostId);
    }

    private Payloads.MatchStarted buildMatchStarted() {
        List<Payloads.StartedPlayerDto> dtos = new ArrayList<>();
        for (String pid : turnOrder) {
            MatchPlayer mp = players.get(pid);
            dtos.add(new Payloads.StartedPlayerDto(mp.player.playerId, mp.player.displayName, mp.player.color, mp.player.cash));
        }
        return new Payloads.MatchStarted(new Payloads.ResolvedMatchConfigDto(config.maxRounds(), config.maxPlayers()), dtos);
    }

    public synchronized Payloads.MatchStateSync buildStateSync() {
        Payloads.MatchStateSync sync = new Payloads.MatchStateSync();
        sync.matchId = matchId;
        sync.status = status.name();
        sync.roundNumber = roundNumber;
        sync.maxRounds = config.maxRounds();
        sync.terrain = terrain != null ? new Payloads.TerrainDto(terrain.snapshotHeights()) : new Payloads.TerrainDto(new int[0]);
        sync.players = new ArrayList<>();
        for (String pid : turnOrder) {
            MatchPlayer mp = players.get(pid);
            if (mp == null) {
                continue;
            }
            Payloads.TankDto tankDto = new Payloads.TankDto(mp.player.tank.x, mp.player.tank.y, mp.player.tank.health, mp.player.tank.alive);
            sync.players.add(new Payloads.PlayerDto(
                    mp.player.playerId, mp.player.displayName, mp.player.color, mp.player.cash,
                    mp.player.loadout, mp.player.activeShieldId, tankDto));
        }
        sync.turnOrder = new ArrayList<>(turnOrder);
        sync.currentTurnIndex = currentTurnIndex;
        sync.wind = new Payloads.WindDto(windStrength, windDirectionSign);
        return sync;
    }

    // =================================================================
    // Broadcast plumbing
    // =================================================================

    private void broadcast(String type, Object payload) {
        broadcast(type, null, payload);
    }

    private void broadcast(String type, String requestId, Object payload) {
        String json;
        try {
            json = mapper.writeValueAsString(com.brutaltank.protocol.Envelope.of(type, requestId, payload));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        for (MatchPlayer mp : players.values()) {
            if (mp.connected && mp.sink != null && mp.sink.isOpen()) {
                mp.sink.send(json);
            }
        }
    }

    // =================================================================
    // Accessors used by LobbyManager/BrutalTankServer for routing
    // =================================================================

    public synchronized Status status() {
        return status;
    }

    public synchronized boolean hasActivePlayer(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp != null && !mp.departed;
    }

    // ---- test-support introspection (package-private: used only by unit tests) ----

    synchronized String activePlayerId() {
        return turnOrder.isEmpty() ? null : turnOrder.get(currentTurnIndex);
    }

    synchronized int roundNumber() {
        return roundNumber;
    }

    synchronized List<String> turnOrderSnapshot() {
        return new ArrayList<>(turnOrder);
    }

    synchronized boolean isAlive(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp != null && mp.player.tank.alive;
    }

    synchronized boolean isDeparted(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null || mp.departed;
    }

    synchronized int cashOf(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null ? -1 : mp.player.cash;
    }

    synchronized int turnsThisRound() {
        return turnsThisRound;
    }

    synchronized boolean isReady(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp != null && mp.ready;
    }

    synchronized double healthOf(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null ? -1 : mp.player.tank.health;
    }

    synchronized String activeShieldIdOf(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null ? null : mp.player.activeShieldId;
    }

    synchronized double shieldAbsorbedSoFarOf(String playerId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null ? -1 : mp.player.shieldAbsorbedSoFar;
    }

    synchronized Integer loadoutQtyOf(String playerId, String weaponId) {
        MatchPlayer mp = players.get(playerId);
        return mp == null ? null : mp.player.loadout.get(weaponId);
    }

    synchronized Terrain debugTerrain() {
        return terrain;
    }

    /**
     * Test-only hook: overrides the round's terrain with a caller-supplied one
     * (e.g. flat terrain) so weapon-behavior tests can predict exact
     * impact/apex/child-detonation points deterministically instead of
     * fighting the real fractal terrain generator.
     */
    synchronized void debugSetTerrain(Terrain terrain) {
        this.terrain = terrain;
    }

    /** Test-only hook: overrides the current turn's wind so shots land deterministically. */
    synchronized void debugSetWind(int strength) {
        this.windStrength = strength;
        this.windDirectionSign = strength >= 0 ? 1 : -1;
    }

    /** Test-only hook: repositions a tank without going through terrain-derived spawn logic. */
    synchronized void debugSetTankPosition(String playerId, double x, double y) {
        MatchPlayer mp = players.get(playerId);
        if (mp != null) {
            mp.player.tank.x = x;
            mp.player.tank.y = y;
        }
    }

    /**
     * Test-only hook to force a tank's health (and derived alive flag)
     * without depending on the (wind-randomized, therefore non-deterministic
     * in a unit test) real projectile physics — lets turn-state-machine
     * tests deterministically exercise elimination/round-end while still
     * driving them through the real {@link #fire} / {@link #onTurnTimeout}
     * code paths.
     */
    synchronized void debugSetHealth(String playerId, double health) {
        MatchPlayer mp = players.get(playerId);
        if (mp != null) {
            mp.player.tank.health = health;
            mp.player.tank.alive = health > 0;
        }
    }

    /** Test-only hook: sets a player's cash directly, so M4 shop-purchase tests don't depend on real damage/elimination cash flow. */
    synchronized void debugSetCash(String playerId, int cash) {
        MatchPlayer mp = players.get(playerId);
        if (mp != null) {
            mp.player.cash = cash;
        }
    }

    /** Test-only hook: forces the match straight into the shop phase without needing to drive a full round to elimination first. */
    synchronized void debugOpenShop() {
        openShop();
    }
}
