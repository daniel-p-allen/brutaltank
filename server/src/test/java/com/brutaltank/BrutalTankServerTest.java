package com.brutaltank;

import com.brutaltank.net.BrutalTankServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * M2 live integration test: boots the real server with real WS clients (JDK
 * HttpClient WebSocket) and exercises the M2 checkpoint from PLAN.md 5 —
 * "full multi-client match from lobby through round completion with turn
 * gating": CreateMatch -> JoinMatch -> SetReady(x2) -> MatchStarted -> several
 * turns of Fire/ShotResolved with turn gating actually enforced (a
 * non-active player's Fire is rejected) -> through to RoundEnded.
 *
 * <p>Uses the test-only short turn-timeout constructor so the run doesn't
 * need to sleep 30s per turn to reach round-end via the 60-turn safety cap.
 */
class BrutalTankServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrutalTankServer server;

    @BeforeEach
    void startServer() {
        server = new BrutalTankServer(150, -1); // short turn timeout, default reconnect grace
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** Minimal test client: queues every full text frame received, in order. */
    private static final class TestClient {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final WebSocket socket;
        private final StringBuilder buffer = new StringBuilder();

        TestClient() throws Exception {
            HttpClient httpClient = HttpClient.newHttpClient();
            this.socket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:6154/ws"), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                String full = buffer.toString();
                                buffer.setLength(0);
                                queue.add(full);
                            }
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);
        }

        void send(String json) {
            socket.sendText(json, true);
        }

        void sendEnvelope(String type, String requestId, Object payload) throws Exception {
            java.util.Map<String, Object> env = new java.util.LinkedHashMap<>();
            env.put("type", type);
            env.put("v", 1);
            if (requestId != null) {
                env.put("requestId", requestId);
            }
            env.put("payload", payload);
            send(MAPPER.writeValueAsString(env));
        }

        /** Waits for and returns the next raw frame, of any type. */
        JsonNode awaitNext() throws Exception {
            String raw = queue.poll(5, TimeUnit.SECONDS);
            if (raw == null) {
                fail("timed out waiting for next message");
            }
            return MAPPER.readTree(raw);
        }

        /** Drains messages until one of the given type arrives (or times out), returning its payload. */
        JsonNode awaitType(String type, long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                String raw = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
                if (raw == null) {
                    break;
                }
                JsonNode node = MAPPER.readTree(raw);
                if (type.equals(node.path("type").asText())) {
                    return node.path("payload");
                }
            }
            fail("timed out waiting for message of type " + type);
            return null;
        }

        void close() {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }

    @Test
    @Timeout(10)
    void pingReceivesPong() throws Exception {
        TestClient client = new TestClient();
        try {
            client.send("{\"type\":\"Ping\",\"v\":1,\"requestId\":\"abc123\",\"payload\":{}}");
            JsonNode node = client.awaitNext();

            assertEquals("Pong", node.get("type").asText());
            assertEquals("abc123", node.get("requestId").asText());
            assertTrue(node.get("payload").get("serverTimeMs").asLong() > 0);
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(60)
    void fullLobbyToRoundEndFlowWithTurnGating() throws Exception {
        TestClient a = new TestClient();
        TestClient b = new TestClient();
        try {
            // --- CreateMatch / JoinMatch ---
            // Note: Match.addPlayer() broadcasts LobbyUpdate to all current members
            // *before* the direct MatchCreated/MatchJoined reply is sent back to the
            // actor, so LobbyUpdate is observed first on the wire for each step below.
            a.sendEnvelope("CreateMatch", "r1", java.util.Map.of("displayName", "Alice"));
            a.awaitType("LobbyUpdate", 5000); // roster update for the (sole) host
            JsonNode created = a.awaitType("MatchCreated", 5000);
            String matchId = created.get("matchId").asText();
            String playerIdA = created.get("playerId").asText();
            assertNotNull(matchId);

            b.sendEnvelope("JoinMatch", "r2", java.util.Map.of("matchId", matchId, "displayName", "Bob"));
            a.awaitType("LobbyUpdate", 5000); // roster update reflecting Bob's join
            b.awaitType("LobbyUpdate", 5000);
            JsonNode joined = b.awaitType("MatchJoined", 5000);
            String playerIdB = joined.get("playerId").asText();
            assertEquals(matchId, joined.get("matchId").asText());

            // --- SetReady x2 -> MatchStarted ---
            a.sendEnvelope("SetReady", "r3", java.util.Map.of("ready", true));
            a.awaitType("LobbyUpdate", 5000); // not all ready yet

            b.sendEnvelope("SetReady", "r4", java.util.Map.of("ready", true));

            JsonNode startedA = a.awaitType("MatchStarted", 5000);
            JsonNode startedB = b.awaitType("MatchStarted", 5000);
            assertEquals(2, startedA.get("players").size());
            assertEquals(startedA, startedB);

            JsonNode syncA = a.awaitType("MatchStateSync", 5000);
            assertEquals("IN_PROGRESS", syncA.get("status").asText());
            b.awaitType("MatchStateSync", 5000);

            JsonNode turnA = a.awaitType("TurnStarted", 5000);
            JsonNode turnB = b.awaitType("TurnStarted", 5000);
            String activePlayerId = turnA.get("playerId").asText();
            assertEquals(activePlayerId, turnB.get("playerId").asText());

            TestClient active = activePlayerId.equals(playerIdA) ? a : b;
            TestClient inactive = activePlayerId.equals(playerIdA) ? b : a;

            // --- Turn gating: the non-active player's Fire must be rejected ---
            inactive.sendEnvelope("Fire", "rBad", java.util.Map.of(
                    "weaponId", "basic_shell", "angleDeg", 45, "power", 50));
            JsonNode rejected = inactive.awaitType("FireRejected", 5000);
            assertEquals("NOT_YOUR_TURN", rejected.get("reason").asText());

            // --- The active player's Fire is accepted and broadcast identically ---
            active.sendEnvelope("Fire", "rGood", java.util.Map.of(
                    "weaponId", "basic_shell", "angleDeg", 40, "power", 60));
            JsonNode shotA = a.awaitType("ShotResolved", 5000);
            JsonNode shotB = b.awaitType("ShotResolved", 5000);
            assertEquals(shotA, shotB);
            assertEquals(activePlayerId, shotA.get("shooterId").asText());

            // --- Drive the rest of the round via the short auto-skip timer (no
            // more firing) until RoundEnded arrives; the 60-turn safety cap
            // guarantees this terminates even with nobody eliminated. ---
            JsonNode roundEnded = a.awaitType("RoundEnded", 20000);
            assertNotNull(roundEnded);
            assertTrue(roundEnded.get("standings").isArray());
            assertEquals(2, roundEnded.get("standings").size());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    @Timeout(60)
    void weaponRosterAndShieldWorkOverRealWebSockets() throws Exception {
        // M3 live-WS coverage: exercises a couple of distinct weapons (not just
        // basic_shell) and a shield activation through the real lobby -> match
        // flow, over real WebSocket connections, per the M3 task spec.
        TestClient a = new TestClient();
        TestClient b = new TestClient();
        try {
            a.sendEnvelope("CreateMatch", "r1", java.util.Map.of("displayName", "Alice"));
            a.awaitType("LobbyUpdate", 5000);
            JsonNode created = a.awaitType("MatchCreated", 5000);
            String matchId = created.get("matchId").asText();
            String playerIdA = created.get("playerId").asText();

            b.sendEnvelope("JoinMatch", "r2", java.util.Map.of("matchId", matchId, "displayName", "Bob"));
            a.awaitType("LobbyUpdate", 5000);
            b.awaitType("LobbyUpdate", 5000);
            JsonNode joined = b.awaitType("MatchJoined", 5000);
            String playerIdB = joined.get("playerId").asText();

            a.sendEnvelope("SetReady", "r3", java.util.Map.of("ready", true));
            a.awaitType("LobbyUpdate", 5000);
            b.sendEnvelope("SetReady", "r4", java.util.Map.of("ready", true));

            a.awaitType("MatchStarted", 5000);
            b.awaitType("MatchStarted", 5000);
            JsonNode syncA = a.awaitType("MatchStateSync", 5000);
            b.awaitType("MatchStateSync", 5000);

            // Confirm the full M3 starting loadout is present (not just basic_shell).
            JsonNode playerAJson = null;
            for (JsonNode p : syncA.get("players")) {
                if (p.get("playerId").asText().equals(playerIdA)) {
                    playerAJson = p;
                }
            }
            assertNotNull(playerAJson);
            JsonNode loadout = playerAJson.get("loadout");
            assertEquals(-1, loadout.get("basic_shell").asInt());
            assertEquals(5, loadout.get("baby_missile").asInt());
            assertEquals(3, loadout.get("heavy_cannonball").asInt());
            assertEquals(1, loadout.get("absorb_shield").asInt());
            assertEquals(1, loadout.get("nuke").asInt());

            JsonNode turnA = a.awaitType("TurnStarted", 5000);
            b.awaitType("TurnStarted", 5000);
            String activePlayerId = turnA.get("playerId").asText();
            TestClient active = activePlayerId.equals(playerIdA) ? a : b;
            TestClient inactive = activePlayerId.equals(playerIdA) ? b : a;

            // --- Fire a non-basic weapon (baby_missile), a distinct weapon from M1/M2. ---
            active.sendEnvelope("Fire", "rBaby", java.util.Map.of(
                    "weaponId", "baby_missile", "angleDeg", 40, "power", 55));
            JsonNode shot1a = a.awaitType("ShotResolved", 5000);
            JsonNode shot1b = b.awaitType("ShotResolved", 5000);
            assertEquals(shot1a, shot1b);
            assertEquals("baby_missile", shot1a.get("weaponId").asText());

            // Turn passes to the other player; fire heavy_cannonball, another
            // distinct behavior/stats weapon.
            a.awaitType("TurnStarted", 5000);
            b.awaitType("TurnStarted", 5000);
            inactive.sendEnvelope("Fire", "rHeavy", java.util.Map.of(
                    "weaponId", "heavy_cannonball", "angleDeg", 35, "power", 60));
            JsonNode shot2a = a.awaitType("ShotResolved", 5000);
            JsonNode shot2b = b.awaitType("ShotResolved", 5000);
            assertEquals(shot2a, shot2b);
            assertEquals("heavy_cannonball", shot2a.get("weaponId").asText());

            // --- Shield activation: whoever's turn it is now activates absorb_shield. ---
            a.awaitType("TurnStarted", 5000);
            JsonNode turnC = b.awaitType("TurnStarted", 5000);
            String shieldTurnPlayerId = turnC.get("playerId").asText();
            TestClient shieldActivator = shieldTurnPlayerId.equals(playerIdA) ? a : b;

            shieldActivator.sendEnvelope("Fire", "rShield", java.util.Map.of(
                    "weaponId", "absorb_shield", "angleDeg", 0, "power", 0));
            JsonNode shieldShotA = a.awaitType("ShotResolved", 5000);
            JsonNode shieldShotB = b.awaitType("ShotResolved", 5000);
            assertEquals(shieldShotA, shieldShotB);
            assertEquals("absorb_shield", shieldShotA.get("weaponId").asText());
            // Shield-activation encoding (documented judgment call): zero-length
            // trajectory/no-op terrainDelta, no damage/cash.
            assertEquals(1, shieldShotA.get("trajectory").size());
            assertEquals(0, shieldShotA.get("damageEvents").size());
            assertEquals(0, shieldShotA.get("cashEarned").size());
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    @Timeout(15)
    void joiningNonexistentMatchGetsErrorMsg() throws Exception {
        TestClient client = new TestClient();
        try {
            client.sendEnvelope("JoinMatch", "r1", java.util.Map.of("matchId", "m-bogus", "displayName", "X"));
            JsonNode error = client.awaitType("ErrorMsg", 5000);
            assertEquals("MATCH_NOT_FOUND", error.get("code").asText());
        } finally {
            client.close();
        }
    }
}
