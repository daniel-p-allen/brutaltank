package com.brutaltank.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Payload DTOs for the M1 slice of the protocol (shared/protocol.md):
 * envelope (see {@link Envelope}), Ping/Pong, MatchStateSync, Fire,
 * ShotResolved and their nested shapes. Field names match the protocol
 * doc's JSON exactly (camelCase) so Jackson can (de)serialize with no
 * custom naming strategy.
 */
public final class Payloads {

    private Payloads() {
    }

    /** Client -> Server: {@code Ping{}} (empty payload). */
    public static final class Ping {
    }

    /** Server -> Client: {@code Pong{serverTimeMs}}. */
    public static final class Pong {
        public long serverTimeMs;

        public Pong() {
        }

        public Pong(long serverTimeMs) {
            this.serverTimeMs = serverTimeMs;
        }
    }

    /** Nested terrain shape used inside {@link MatchStateSync}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class TerrainDto {
        public int[] heights;

        public TerrainDto() {
        }

        public TerrainDto(int[] heights) {
            this.heights = heights;
        }
    }

    /** Nested tank shape inside {@link PlayerDto}. */
    public static final class TankDto {
        public double x;
        public double y;
        public double health;
        public boolean alive;

        public TankDto() {
        }

        public TankDto(double x, double y, double health, boolean alive) {
            this.x = x;
            this.y = y;
            this.health = health;
            this.alive = alive;
        }
    }

    /** Nested player shape inside {@link MatchStateSync}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class PlayerDto {
        public String playerId;
        public String displayName;
        public String color;
        public int cash;
        public Map<String, Integer> loadout;
        public String activeShieldId;
        public TankDto tank;
        public boolean isBot;

        public PlayerDto() {
        }

        public PlayerDto(String playerId, String displayName, String color, int cash,
                          Map<String, Integer> loadout, String activeShieldId, TankDto tank, boolean isBot) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.color = color;
            this.cash = cash;
            this.loadout = loadout;
            this.activeShieldId = activeShieldId;
            this.tank = tank;
            this.isBot = isBot;
        }
    }

    /** Nested wind shape inside {@link MatchStateSync}. */
    public static final class WindDto {
        public int strength;
        public int directionSign;

        public WindDto() {
        }

        public WindDto(int strength, int directionSign) {
            this.strength = strength;
            this.directionSign = directionSign;
        }
    }

    /** Server -> Client: {@code MatchStateSync}, the only full-state message. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class MatchStateSync {
        public String matchId;
        public String status;
        public int roundNumber;
        public int maxRounds;
        public TerrainDto terrain;
        public List<PlayerDto> players;
        public List<String> turnOrder;
        public int currentTurnIndex;
        public WindDto wind;

        public MatchStateSync() {
        }
    }

    /** Client -> Server: {@code Fire{weaponId, angleDeg, power, trajectoryHelpUsed}}. */
    public static final class Fire {
        public String weaponId;
        public double angleDeg;
        public double power;
        /**
         * Whether the client had Trajectory Help toggled on for this shot
         * (per user decision, 2026-08-23: firing without it grants a
         * risk/reward bonus — see Match.applyDetonations's
         * damageMultiplier/cashMultiplier). Defaults to false (Jackson's
         * primitive-boolean default) for any older client that doesn't send
         * it, which is the "no help, get the bonus" side — a safe default.
         */
        public boolean trajectoryHelpUsed;

        public Fire() {
        }
    }

    /**
     * Client -> Server: {@code AimUpdate{angleDeg}}. Cosmetic-only, not
     * turn-gated (any player can drag their aim slider at any time per user
     * feedback) — purely relayed so every client's tankRenderer can show
     * everyone's barrel tracking their live angle, not just the local
     * player's own. No server-side validation beyond "sender is a real,
     * non-departed player in this match"; never affects gameplay state.
     */
    public static final class AimUpdate {
        public double angleDeg;

        public AimUpdate() {
        }
    }

    /** Server -> Client: {@code PlayerAiming{playerId, angleDeg}}, broadcast relay of an AimUpdate. */
    public static final class PlayerAiming {
        public String playerId;
        public double angleDeg;

        public PlayerAiming() {
        }

        public PlayerAiming(String playerId, double angleDeg) {
            this.playerId = playerId;
            this.angleDeg = angleDeg;
        }
    }

    /**
     * Client -> Server: {@code TrajectoryHelpUpdate{enabled}}. Cosmetic-only,
     * not turn-gated, same pattern as {@link AimUpdate} — relayed as {@link
     * PlayerTrajectoryHelp} so every client can show every player's current
     * Trajectory Help on/off status (per user request, 2026-08-23: shown per
     * player in the players list alongside cash).
     */
    public static final class TrajectoryHelpUpdate {
        public boolean enabled;

        public TrajectoryHelpUpdate() {
        }
    }

    /** Server -> Client: {@code PlayerTrajectoryHelp{playerId, enabled}}, broadcast relay of a TrajectoryHelpUpdate. */
    public static final class PlayerTrajectoryHelp {
        public String playerId;
        public boolean enabled;

        public PlayerTrajectoryHelp() {
        }

        public PlayerTrajectoryHelp(String playerId, boolean enabled) {
            this.playerId = playerId;
            this.enabled = enabled;
        }
    }

    /** Server -> Client: {@code FireRejected{reason}}. */
    public static final class FireRejected {
        public String reason;

        public FireRejected() {
        }

        public FireRejected(String reason) {
            this.reason = reason;
        }
    }

    /** A single resampled trajectory point inside {@link ShotResolved}. */
    public static final class TrajectoryPoint {
        public double x;
        public double y;

        public TrajectoryPoint() {
        }

        public TrajectoryPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Final impact/detonation point inside {@link ShotResolved}. */
    public static final class Impact {
        public double x;
        public double y;

        public Impact() {
        }

        public Impact(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    /** Affected terrain column range inside {@link ShotResolved}. */
    public static final class TerrainDelta {
        public int startX;
        public int endX;
        public int[] heights;

        public TerrainDelta() {
        }

        public TerrainDelta(int startX, int endX, int[] heights) {
            this.startX = startX;
            this.endX = endX;
            this.heights = heights;
        }
    }

    /** One tank-affected-by-blast entry inside {@link ShotResolved}. */
    public static final class DamageEvent {
        public String playerId;
        public double damage;
        public double newHealth;
        public boolean eliminated;
        /**
         * The target's activeShieldId immediately after this hit was
         * mitigated/resolved (null if none) — lets clients learn a shield
         * broke (or is still holding) without waiting for the next full
         * MatchStateSync, same reasoning as ShotResolved.ammoRemaining.
         */
        public String activeShieldId;

        public DamageEvent() {
        }

        public DamageEvent(String playerId, double damage, double newHealth, boolean eliminated, String activeShieldId) {
            this.playerId = playerId;
            this.damage = damage;
            this.newHealth = newHealth;
            this.eliminated = eliminated;
            this.activeShieldId = activeShieldId;
        }
    }

    /**
     * One tank whose ground gave way this shot (crater/gully undermined it,
     * or the post-crater slope-settle pass ate the ground under it) and
     * dropped to the new terrain level. Position-only — any resulting fall
     * damage is folded into {@link ShotResolved#damageEvents} instead, so a
     * tank that's both blasted and falls gets one consistent health change.
     */
    public static final class TankFall {
        public String playerId;
        public double newY;

        public TankFall() {
        }

        public TankFall(String playerId, double newY) {
            this.playerId = playerId;
            this.newY = newY;
        }
    }

    /** One cash-credit entry inside {@link ShotResolved}. */
    public static final class CashEarned {
        public String playerId;
        public int amount;

        public CashEarned() {
        }

        public CashEarned(String playerId, int amount) {
            this.playerId = playerId;
            this.amount = amount;
        }
    }

    /** Server -> Client: {@code ShotResolved}, broadcast identically to all connected clients. */
    public static final class ShotResolved {
        public String shooterId;
        public String weaponId;
        public List<TrajectoryPoint> trajectory;
        public Impact impact;
        public TerrainDelta terrainDelta;
        public List<DamageEvent> damageEvents;
        public List<CashEarned> cashEarned;
        public List<TankFall> tankFalls;
        /**
         * The shooter's remaining quantity of {@code weaponId} after this
         * shot (-1 == unlimited, same convention as loadout elsewhere).
         * Without this, clients only ever saw ammo counts refresh on the
         * next full {@code MatchStateSync} (i.e. the next round) even though
         * the server decremented it correctly on every shot.
         */
        public int ammoRemaining;
        /**
         * Every "real" (non-cosmetic, damage-capable) detonation point this
         * shot produced, in order — for a single-impact weapon this is just
         * {@code [impact]}; for MIRV/Cluster Bomb it's every child/bomblet's
         * landing point. Without this, the client's single flight-animation
         * dot only ever flashed at {@code impact} (the shared MIRV apex /
         * primary Cluster point), so a multi-impact shot's other detonations
         * silently changed the terrain with no explosion shown there at all.
         * Cosmetic zero-damage marks (Tunneling's bore track, Bouncing
         * Betty's skip marks) are deliberately excluded — those already read
         * as a trail, not additional explosions.
         */
        public List<Impact> allImpacts;

        public ShotResolved() {
        }
    }

    // ---------------------------------------------------------------
    // M2: lobby messages (shared/protocol.md section 3)
    // ---------------------------------------------------------------

    /** Optional overrides carried on {@code CreateMatch.matchConfig}; null fields fall back to server defaults. */
    public static final class MatchConfigDto {
        public Integer maxRounds;
        public Integer maxPlayers;
        public Integer botCount;
        public String botDifficulty;

        public MatchConfigDto() {
        }

        public MatchConfigDto(Integer maxRounds, Integer maxPlayers) {
            this.maxRounds = maxRounds;
            this.maxPlayers = maxPlayers;
        }
    }

    /** Client -> Server: {@code CreateMatch{displayName, matchConfig?}}. */
    public static final class CreateMatch {
        public String displayName;
        public MatchConfigDto matchConfig;

        public CreateMatch() {
        }
    }

    /** Client -> Server: {@code JoinMatch{matchId, displayName}}. */
    public static final class JoinMatch {
        public String matchId;
        public String displayName;

        public JoinMatch() {
        }
    }

    /** Client -> Server: {@code SetReady{ready}}. */
    public static final class SetReady {
        public boolean ready;

        public SetReady() {
        }
    }

    /** Client -> Server: {@code Rejoin{matchId, playerToken}}. */
    public static final class Rejoin {
        public String matchId;
        public String playerToken;

        public Rejoin() {
        }
    }

    /** Client -> Server: {@code LeaveMatch{}} (empty payload). */
    public static final class LeaveMatch {
    }

    /** Server -> Client: {@code MatchCreated}, sent only to the creator. */
    public static final class MatchCreated {
        public String matchId;
        public String joinCode;
        public String playerToken;
        public String playerId;

        public MatchCreated() {
        }

        public MatchCreated(String matchId, String joinCode, String playerToken, String playerId) {
            this.matchId = matchId;
            this.joinCode = joinCode;
            this.playerToken = playerToken;
            this.playerId = playerId;
        }
    }

    /** Server -> Client: {@code MatchJoined}, sent only to the joiner. */
    public static final class MatchJoined {
        public String matchId;
        public String playerToken;
        public String playerId;

        public MatchJoined() {
        }

        public MatchJoined(String matchId, String playerToken, String playerId) {
            this.matchId = matchId;
            this.playerToken = playerToken;
            this.playerId = playerId;
        }
    }

    /** One roster entry inside {@link LobbyUpdate}. */
    public static final class LobbyPlayerDto {
        public String playerId;
        public String displayName;
        public boolean ready;
        public boolean isHost;
        public boolean isBot;

        public LobbyPlayerDto() {
        }

        public LobbyPlayerDto(String playerId, String displayName, boolean ready, boolean isHost, boolean isBot) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.ready = ready;
            this.isHost = isHost;
            this.isBot = isBot;
        }
    }

    /** Server -> Client: {@code LobbyUpdate}, broadcast on roster/readiness changes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class LobbyUpdate {
        public String matchId;
        public List<LobbyPlayerDto> players;
        public String hostId;

        public LobbyUpdate() {
        }

        public LobbyUpdate(String matchId, List<LobbyPlayerDto> players, String hostId) {
            this.matchId = matchId;
            this.players = players;
            this.hostId = hostId;
        }
    }

    /** One roster entry inside {@link MatchStarted}. */
    public static final class StartedPlayerDto {
        public String playerId;
        public String displayName;
        public String color;
        public int cash;
        public boolean isBot;

        public StartedPlayerDto() {
        }

        public StartedPlayerDto(String playerId, String displayName, String color, int cash, boolean isBot) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.color = color;
            this.cash = cash;
            this.isBot = isBot;
        }
    }

    /** Resolved match config (both fields always populated) inside {@link MatchStarted}. */
    public static final class ResolvedMatchConfigDto {
        public int maxRounds;
        public int maxPlayers;

        public ResolvedMatchConfigDto() {
        }

        public ResolvedMatchConfigDto(int maxRounds, int maxPlayers) {
            this.maxRounds = maxRounds;
            this.maxPlayers = maxPlayers;
        }
    }

    /** Server -> Client: {@code MatchStarted}, broadcast once on WAITING -> IN_PROGRESS. */
    public static final class MatchStarted {
        public ResolvedMatchConfigDto matchConfig;
        public List<StartedPlayerDto> players;

        public MatchStarted() {
        }

        public MatchStarted(ResolvedMatchConfigDto matchConfig, List<StartedPlayerDto> players) {
            this.matchConfig = matchConfig;
            this.players = players;
        }
    }

    /** Server -> Client: {@code PlayerDisconnected{playerId}} / {@code PlayerReconnected{playerId}}. */
    public static final class PlayerIdPayload {
        public String playerId;

        public PlayerIdPayload() {
        }

        public PlayerIdPayload(String playerId) {
            this.playerId = playerId;
        }
    }

    /** Server -> Client: {@code TurnForfeited{playerId, penalty, newCash, eliminated}} — broadcast when a turn times out without a Fire (per user feedback: "if you miss your turn without taking a shot, you lost 50"). */
    public static final class TurnForfeited {
        public String playerId;
        public int penalty;
        public int newCash;
        public boolean eliminated;

        public TurnForfeited() {
        }

        public TurnForfeited(String playerId, int penalty, int newCash, boolean eliminated) {
            this.playerId = playerId;
            this.penalty = penalty;
            this.newCash = newCash;
            this.eliminated = eliminated;
        }
    }

    // ---------------------------------------------------------------
    // M2: match/turn messages (shared/protocol.md section 4)
    // ---------------------------------------------------------------

    /** Server -> Client: {@code TurnStarted}. */
    public static final class TurnStarted {
        public String playerId;
        public WindDto wind;
        public int turnTimeoutSec;

        public TurnStarted() {
        }

        public TurnStarted(String playerId, WindDto wind, int turnTimeoutSec) {
            this.playerId = playerId;
            this.wind = wind;
            this.turnTimeoutSec = turnTimeoutSec;
        }
    }

    /** One entry inside {@link RoundEnded#standings}. */
    public static final class Standing {
        public String playerId;
        public int cash;

        public Standing() {
        }

        public Standing(String playerId, int cash) {
            this.playerId = playerId;
            this.cash = cash;
        }
    }

    /** Server -> Client: {@code RoundEnded{winnerPlayerId?, standings[]}}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class RoundEnded {
        public String winnerPlayerId;
        public List<Standing> standings;

        public RoundEnded() {
        }

        public RoundEnded(String winnerPlayerId, List<Standing> standings) {
            this.winnerPlayerId = winnerPlayerId;
            this.standings = standings;
        }
    }

    /** One entry inside {@link MatchEnded#finalStandings}. */
    public static final class FinalStanding {
        public String playerId;
        public int cash;
        public int damageDealt;
        public int kills;

        public FinalStanding() {
        }

        public FinalStanding(String playerId, int cash, int damageDealt, int kills) {
            this.playerId = playerId;
            this.cash = cash;
            this.damageDealt = damageDealt;
            this.kills = kills;
        }
    }

    /** Server -> Client: {@code MatchEnded{finalStandings[]}}. */
    public static final class MatchEnded {
        public List<FinalStanding> finalStandings;

        public MatchEnded() {
        }

        public MatchEnded(List<FinalStanding> finalStandings) {
            this.finalStandings = finalStandings;
        }
    }

    // ---------------------------------------------------------------
    // M4: shop messages (shared/protocol.md section 5)
    // ---------------------------------------------------------------

    /** Client -> Server: {@code ShopPurchase{itemId, itemType, quantity}}. */
    public static final class ShopPurchase {
        public String itemId;
        public String itemType;
        public int quantity;

        public ShopPurchase() {
        }
    }

    /**
     * One priced item inside {@link ShopOpened#priceList}. {@code stock} (M4
     * addition beyond the original protocol.md table) is a shared pool
     * across every player in the match for this shop phase, not per-player —
     * it plays into shop tactics ("buy the last one before someone else
     * does"), replenished fresh each round.
     */
    public static final class PriceListEntry {
        public String itemId;
        public String itemType;
        public int price;
        public int stock;

        public PriceListEntry() {
        }

        public PriceListEntry(String itemId, String itemType, int price, int stock) {
            this.itemId = itemId;
            this.itemType = itemType;
            this.price = price;
            this.stock = stock;
        }
    }

    /** Server -> Client: {@code ShopOpened{timeoutSec, priceList[]}}, broadcast at shop-phase start. */
    public static final class ShopOpened {
        public int timeoutSec;
        public List<PriceListEntry> priceList;

        public ShopOpened() {
        }

        public ShopOpened(int timeoutSec, List<PriceListEntry> priceList) {
            this.timeoutSec = timeoutSec;
            this.priceList = priceList;
        }
    }

    /**
     * Server -> Client: {@code ShopUpdate{playerId, cash, loadout, stockRemaining}},
     * broadcast to everyone after a successful purchase (not just the buyer)
     * so every client's price list reflects the shared stock pool shrinking
     * in real time — that's what makes stock scarcity an actual multiplayer
     * tactic rather than a per-player detail.
     */
    public static final class ShopUpdate {
        public String playerId;
        public int cash;
        public Map<String, Integer> loadout;
        public Map<String, Integer> stockRemaining;

        public ShopUpdate() {
        }

        public ShopUpdate(String playerId, int cash, Map<String, Integer> loadout, Map<String, Integer> stockRemaining) {
            this.playerId = playerId;
            this.cash = cash;
            this.loadout = loadout;
            this.stockRemaining = stockRemaining;
        }
    }

    /** Server -> Client: {@code ErrorMsg{code, message}}. */
    public static final class ErrorMsg {
        public String code;
        public String message;

        public ErrorMsg() {
        }

        public ErrorMsg(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}
