package com.brutaltank.match;

import com.brutaltank.domain.player.Player;
import com.brutaltank.domain.player.Tank;
import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.terrain.TerrainGenerator;
import com.brutaltank.domain.weapon.DamageCalculator;
import com.brutaltank.domain.weapon.ProjectileSim;
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

    public enum Status { WAITING, IN_PROGRESS, COMPLETE }

    private static final int STARTING_HEALTH = 100;
    private static final int STARTING_CASH = 500;
    private static final int MAX_TURNS_PER_ROUND = 60;
    private static final int ROUND_SURVIVAL_BONUS = 50;
    private static final int ELIMINATION_BONUS = 100;
    static final long DEFAULT_TURN_TIMEOUT_MS = 30_000;
    static final long DEFAULT_RECONNECT_GRACE_MS = 120_000;
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
    private ScheduledFuture<?> pendingTimeout;

    private volatile long turnTimeoutMs = DEFAULT_TURN_TIMEOUT_MS;
    private volatile long reconnectGraceMs = DEFAULT_RECONNECT_GRACE_MS;

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
        domainPlayer.loadout.put("basic_shell", -1); // unlimited, per protocol convention

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

    private void beginTurn() {
        windStrength = ThreadLocalRandom.current().nextInt(-20, 21);
        windDirectionSign = windStrength >= 0 ? 1 : -1;

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

    private synchronized void onTurnTimeout(int token) {
        if (status != Status.IN_PROGRESS || token != turnToken) {
            return; // stale: a Fire (or another timeout/disconnect) already resolved this turn
        }
        resolveTurnAdvance(null, null); // timeout skip: no shot, just advance
    }

    public synchronized FireOutcome fire(String playerId, String requestId, String weaponId, double angleDeg, double power) {
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
        // M2 scope: weapon roster is just the free/unlimited basic shell (M3 adds the rest);
        // still validated against the player's loadout rather than hardcoded, per task spec.
        Integer qty = shooter.player.loadout.get(weaponId);
        if (qty == null) {
            return FireOutcome.rejected("INVALID_WEAPON");
        }

        cancelPendingTimeout();

        Payloads.ShotResolved resolved = resolveShot(shooter, weaponId, angleDeg, power);
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
        if (aliveIds.size() <= 1 || turnsThisRound >= MAX_TURNS_PER_ROUND) {
            endRound(aliveIds);
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

        List<Payloads.Standing> standings = new ArrayList<>();
        for (MatchPlayer mp : players.values()) {
            standings.add(new Payloads.Standing(mp.player.playerId, mp.player.cash));
        }
        standings.sort((a, b) -> Integer.compare(b.cash, a.cash));

        broadcast("RoundEnded", new Payloads.RoundEnded(winnerId, standings));

        if (roundNumber >= config.maxRounds()) {
            endMatch();
        } else {
            roundNumber++;
            rotateTurnOrder();
            if (turnOrder.isEmpty()) {
                status = Status.COMPLETE;
            } else {
                startRound();
            }
        }
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

    private Payloads.ShotResolved resolveShot(MatchPlayer shooter, String weaponId, double angleDeg, double power) {
        WeaponDef weapon = WeaponDef.byId(weaponId);
        double clampedPower = Math.max(0, Math.min(100, power));

        List<ProjectileSim.TankTarget> targets = new ArrayList<>();
        for (MatchPlayer p : players.values()) {
            if (p.player.playerId.equals(shooter.player.playerId) || p.departed || !p.player.tank.alive) {
                continue;
            }
            targets.add(new ProjectileSim.TankTarget(p.player.playerId, p.player.tank.x, p.player.tank.y));
        }

        ProjectileSim.Result sim = ProjectileSim.simulate(
                shooter.player.tank.x, shooter.player.tank.y, angleDeg, clampedPower,
                windStrength, terrain, targets);

        Terrain.CraterResult crater = terrain.applyCrater(
                (int) Math.round(sim.impactX), weapon.blastRadius());

        List<DamageCalculator.TankState> tankStates = new ArrayList<>();
        for (MatchPlayer p : players.values()) {
            if (p.departed || !p.player.tank.alive) {
                continue;
            }
            tankStates.add(new DamageCalculator.TankState(p.player.playerId, p.player.tank.x, p.player.tank.y, p.player.tank.health));
        }

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                shooter.player.playerId, sim.impactX, sim.impactY,
                weapon.blastRadius(), weapon.centerDamage(), tankStates);

        List<Payloads.DamageEvent> damageEvents = new ArrayList<>();
        List<Payloads.CashEarned> cashEarned = new ArrayList<>();

        for (DamageCalculator.DamageResult d : outcome.damageEvents) {
            MatchPlayer target = players.get(d.playerId());
            target.player.tank.health = d.newHealth();
            if (d.eliminated()) {
                target.player.tank.alive = false;
            }
            damageEvents.add(new Payloads.DamageEvent(d.playerId(), d.damage(), d.newHealth(), d.eliminated()));

            if (!target.player.playerId.equals(shooter.player.playerId)) {
                shooter.damageDealt += (int) Math.round(d.damage());
                if (d.eliminated()) {
                    shooter.kills++;
                    shooter.player.cash += ELIMINATION_BONUS;
                    cashEarned.add(new Payloads.CashEarned(shooter.player.playerId, ELIMINATION_BONUS));
                }
            }
        }

        for (DamageCalculator.CashResult c : outcome.cashEarned) {
            MatchPlayer earner = players.get(c.playerId());
            if (earner != null) {
                earner.player.cash += c.amount();
            }
            cashEarned.add(new Payloads.CashEarned(c.playerId(), c.amount()));
        }

        Payloads.ShotResolved resolved = new Payloads.ShotResolved();
        resolved.shooterId = shooter.player.playerId;
        resolved.weaponId = weaponId;
        resolved.trajectory = new ArrayList<>();
        for (double[] pt : sim.resampledTrajectory) {
            resolved.trajectory.add(new Payloads.TrajectoryPoint(pt[0], pt[1]));
        }
        resolved.impact = new Payloads.Impact(sim.impactX, sim.impactY);
        resolved.terrainDelta = new Payloads.TerrainDelta(crater.startX(), crater.endX(), crater.heights());
        resolved.damageEvents = damageEvents;
        resolved.cashEarned = cashEarned;
        return resolved;
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
}
