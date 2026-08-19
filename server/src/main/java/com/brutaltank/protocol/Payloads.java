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

        public PlayerDto() {
        }

        public PlayerDto(String playerId, String displayName, String color, int cash,
                          Map<String, Integer> loadout, String activeShieldId, TankDto tank) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.color = color;
            this.cash = cash;
            this.loadout = loadout;
            this.activeShieldId = activeShieldId;
            this.tank = tank;
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

    /** Client -> Server: {@code Fire{weaponId, angleDeg, power}}. */
    public static final class Fire {
        public String weaponId;
        public double angleDeg;
        public double power;

        public Fire() {
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

        public DamageEvent() {
        }

        public DamageEvent(String playerId, double damage, double newHealth, boolean eliminated) {
            this.playerId = playerId;
            this.damage = damage;
            this.newHealth = newHealth;
            this.eliminated = eliminated;
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

        public LobbyPlayerDto() {
        }

        public LobbyPlayerDto(String playerId, String displayName, boolean ready, boolean isHost) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.ready = ready;
            this.isHost = isHost;
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

        public StartedPlayerDto() {
        }

        public StartedPlayerDto(String playerId, String displayName, String color, int cash) {
            this.playerId = playerId;
            this.displayName = displayName;
            this.color = color;
            this.cash = cash;
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
