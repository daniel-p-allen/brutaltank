package com.brutaltank.match;

import com.brutaltank.domain.player.Player;
import com.brutaltank.domain.player.Tank;
import com.brutaltank.domain.terrain.Terrain;
import com.brutaltank.domain.terrain.TerrainGenerator;
import com.brutaltank.domain.weapon.DamageCalculator;
import com.brutaltank.domain.weapon.ProjectileSim;
import com.brutaltank.domain.weapon.WeaponDef;
import com.brutaltank.protocol.Payloads;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * M1's single hardcoded match: two fixed tank slots, one terrain, no lobby,
 * no turn enforcement (PLAN.md M1 milestone / task spec). A later milestone
 * replaces this with the full MatchActor/queue system from PLAN.md 2.2 — for
 * now a single synchronized in-memory object is sufficient, since all state
 * mutation is still kept off the Undertow I/O thread by the caller (dispatch
 * happens on the shared virtual-thread executor before reaching here).
 */
public final class Match {

    public static final String MATCH_ID = "m-1";
    private static final int STARTING_HEALTH = 100;
    private static final int STARTING_CASH = 500;

    private final ObjectMapper mapper;
    private final Terrain terrain;
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final List<String> turnOrder = new ArrayList<>();
    private final Map<String, WebSocketChannel> sessions = new LinkedHashMap<>();
    private int windStrength;
    private int windDirectionSign;

    public Match(ObjectMapper mapper) {
        this.mapper = mapper;
        this.terrain = TerrainGenerator.generate(1L);

        addPlayer("p-1", "Player 1", "#e33", TerrainGenerator.SPAWN_X_1);
        addPlayer("p-2", "Player 2", "#33e", TerrainGenerator.SPAWN_X_2);

        SplittableRandom windRng = new SplittableRandom(1L);
        this.windStrength = windRng.nextInt(-20, 21);
        this.windDirectionSign = windStrength >= 0 ? 1 : -1;
    }

    private void addPlayer(String playerId, String displayName, String color, int spawnX) {
        double y = terrain.heightAt(spawnX);
        Tank tank = new Tank(spawnX, y, STARTING_HEALTH);
        Player player = new Player(playerId, displayName, color, STARTING_CASH, tank);
        player.loadout.put("basic_shell", -1); // unlimited, per protocol convention
        players.put(playerId, player);
        turnOrder.add(playerId);
    }

    /**
     * Assigns the connecting channel a player slot: first connection -> p-1,
     * second -> p-2. A third+ connection is accepted as a spectator (no
     * playerId, receives MatchStateSync/ShotResolved broadcasts but cannot
     * fire) rather than rejected outright — simplest thing that can't crash
     * the socket for M1's scope.
     */
    public synchronized String assignSlot(WebSocketChannel channel) {
        for (String playerId : turnOrder) {
            if (!sessions.containsKey(playerId)) {
                sessions.put(playerId, channel);
                return playerId;
            }
        }
        // Spectator: track under a synthetic key so it still receives broadcasts.
        String spectatorKey = "spectator-" + System.nanoTime();
        sessions.put(spectatorKey, channel);
        return null;
    }

    public synchronized void removeChannel(WebSocketChannel channel) {
        sessions.values().removeIf(ch -> ch == channel);
    }

    /** Returns the playerId assigned to this channel, or null for a spectator/unknown channel. */
    public synchronized String playerIdFor(WebSocketChannel channel) {
        for (Map.Entry<String, WebSocketChannel> entry : sessions.entrySet()) {
            if (entry.getValue() == channel) {
                String key = entry.getKey();
                return key.startsWith("spectator-") ? null : key;
            }
        }
        return null;
    }

    public synchronized Payloads.MatchStateSync buildStateSync() {
        Payloads.MatchStateSync sync = new Payloads.MatchStateSync();
        sync.matchId = MATCH_ID;
        sync.status = "IN_PROGRESS";
        sync.roundNumber = 1;
        sync.maxRounds = 1;
        sync.terrain = new Payloads.TerrainDto(terrain.snapshotHeights());
        sync.players = new ArrayList<>();
        for (Player p : players.values()) {
            Payloads.TankDto tankDto = new Payloads.TankDto(p.tank.x, p.tank.y, p.tank.health, p.tank.alive);
            sync.players.add(new Payloads.PlayerDto(
                    p.playerId, p.displayName, p.color, p.cash, p.loadout, p.activeShieldId, tankDto));
        }
        sync.turnOrder = new ArrayList<>(turnOrder);
        sync.currentTurnIndex = 0;
        sync.wind = new Payloads.WindDto(windStrength, windDirectionSign);
        return sync;
    }

    /**
     * Resolves a Fire from the given player (no turn enforcement in M1).
     * Returns the ShotResolved payload to broadcast, or null if the fire
     * was invalid (unknown weapon, unknown/spectator shooter, dead shooter).
     */
    public synchronized Payloads.ShotResolved fire(String shooterId, String weaponId, double angleDeg, double power) {
        if (shooterId == null) {
            return null; // spectators cannot fire
        }
        WeaponDef weapon = WeaponDef.byId(weaponId);
        if (weapon == null) {
            return null;
        }
        Player shooter = players.get(shooterId);
        if (shooter == null || !shooter.tank.alive) {
            return null;
        }

        double clampedPower = Math.max(0, Math.min(100, power));

        List<ProjectileSim.TankTarget> targets = new ArrayList<>();
        for (Player p : players.values()) {
            if (p.playerId.equals(shooterId) || !p.tank.alive) {
                continue;
            }
            targets.add(new ProjectileSim.TankTarget(p.playerId, p.tank.x, p.tank.y));
        }

        ProjectileSim.Result simResult = ProjectileSim.simulate(
                shooter.tank.x, shooter.tank.y, angleDeg, clampedPower, windStrength, terrain, targets);

        Terrain.CraterResult crater = terrain.applyCrater(
                (int) Math.round(simResult.impactX), WeaponDef.BASIC_SHELL.blastRadius());

        List<DamageCalculator.TankState> tankStates = new ArrayList<>();
        for (Player p : players.values()) {
            if (!p.tank.alive) {
                continue;
            }
            tankStates.add(new DamageCalculator.TankState(p.playerId, p.tank.x, p.tank.y, p.tank.health));
        }

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                shooterId, simResult.impactX, simResult.impactY,
                weapon.blastRadius(), weapon.centerDamage(), tankStates);

        List<Payloads.DamageEvent> damageEvents = new ArrayList<>();
        for (DamageCalculator.DamageResult d : outcome.damageEvents) {
            Player target = players.get(d.playerId());
            target.tank.health = d.newHealth();
            if (d.eliminated()) {
                target.tank.alive = false;
            }
            damageEvents.add(new Payloads.DamageEvent(d.playerId(), d.damage(), d.newHealth(), d.eliminated()));
        }

        List<Payloads.CashEarned> cashEarned = new ArrayList<>();
        for (DamageCalculator.CashResult c : outcome.cashEarned) {
            Player earner = players.get(c.playerId());
            if (earner != null) {
                earner.cash += c.amount();
            }
            cashEarned.add(new Payloads.CashEarned(c.playerId(), c.amount()));
        }

        Payloads.ShotResolved resolved = new Payloads.ShotResolved();
        resolved.shooterId = shooterId;
        resolved.weaponId = weaponId;
        resolved.trajectory = new ArrayList<>();
        for (double[] pt : simResult.resampledTrajectory) {
            resolved.trajectory.add(new Payloads.TrajectoryPoint(pt[0], pt[1]));
        }
        resolved.impact = new Payloads.Impact(simResult.impactX, simResult.impactY);
        resolved.terrainDelta = new Payloads.TerrainDelta(crater.startX(), crater.endX(), crater.heights());
        resolved.damageEvents = damageEvents;
        resolved.cashEarned = cashEarned;
        return resolved;
    }

    public synchronized List<WebSocketChannel> connectedChannels() {
        return new ArrayList<>(sessions.values());
    }

    public void broadcast(String type, Object payload) {
        broadcast(type, null, payload);
    }

    public void broadcast(String type, String requestId, Object payload) {
        com.brutaltank.protocol.Envelope envelope = com.brutaltank.protocol.Envelope.of(type, requestId, payload);
        String json = writeJson(envelope);
        for (WebSocketChannel channel : connectedChannels()) {
            if (channel.isOpen()) {
                WebSockets.sendText(json, channel, null);
            }
        }
    }

    public void sendTo(WebSocketChannel channel, String type, Object payload) {
        sendTo(channel, type, null, payload);
    }

    public void sendTo(WebSocketChannel channel, String type, String requestId, Object payload) {
        com.brutaltank.protocol.Envelope envelope = com.brutaltank.protocol.Envelope.of(type, requestId, payload);
        WebSockets.sendText(writeJson(envelope), channel, null);
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
