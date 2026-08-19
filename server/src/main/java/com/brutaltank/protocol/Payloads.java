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
}
