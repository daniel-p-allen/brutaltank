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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1 integration test: boots the real server, connects real WS clients via
 * the JDK's HttpClient WebSocket, and exercises the actual protocol —
 * Ping/Pong, MatchStateSync on connect, and Fire -> ShotResolved broadcast
 * identically to both connected players (the M1 checkpoint from PLAN.md 5).
 */
class BrutalTankServerTest {

    // Fresh server (and therefore a fresh hardcoded Match with empty player
    // slots) per test, since M1's slot assignment is first-come-first-served
    // and tests would otherwise contend for the same two slots.
    private BrutalTankServer server;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void startServer() {
        server = new BrutalTankServer();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    /** Minimal test client: collects every full text frame received. */
    private static final class TestClient {
        final List<String> messages = new CopyOnWriteArrayList<>();
        volatile CompletableFuture<String> nextMessage = new CompletableFuture<>();
        final WebSocket socket;
        private final StringBuilder buffer = new StringBuilder();

        TestClient() throws Exception {
            HttpClient httpClient = HttpClient.newHttpClient();
            this.socket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:8080/ws"), new WebSocket.Listener() {
                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                String full = buffer.toString();
                                buffer.setLength(0);
                                messages.add(full);
                                nextMessage.complete(full);
                            }
                            webSocket.request(1);
                            return null;
                        }
                    })
                    .get(5, TimeUnit.SECONDS);
        }

        String awaitNext() throws Exception {
            String msg = nextMessage.get(5, TimeUnit.SECONDS);
            nextMessage = new CompletableFuture<>();
            return msg;
        }

        void send(String json) {
            socket.sendText(json, true);
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
            client.awaitNext(); // MatchStateSync on connect

            client.send("{\"type\":\"Ping\",\"v\":1,\"requestId\":\"abc123\",\"payload\":{}}");
            String response = client.awaitNext();

            JsonNode node = MAPPER.readTree(response);
            assertEquals("Pong", node.get("type").asText());
            assertEquals("abc123", node.get("requestId").asText());
            assertTrue(node.get("payload").get("serverTimeMs").asLong() > 0);
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(10)
    void connectReceivesMatchStateSyncWithHardcodedTanks() throws Exception {
        TestClient client = new TestClient();
        try {
            String sync = client.awaitNext();
            JsonNode node = MAPPER.readTree(sync);

            assertEquals("MatchStateSync", node.get("type").asText());
            JsonNode payload = node.get("payload");
            assertEquals("m-1", payload.get("matchId").asText());
            assertTrue(payload.get("terrain").get("heights").isArray());
            assertEquals(1600, payload.get("terrain").get("heights").size());
            assertEquals(2, payload.get("players").size());
            assertEquals("p-1", payload.get("players").get(0).get("playerId").asText());
            assertEquals(100.0, payload.get("players").get(0).get("tank").get("health").asDouble());
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(15)
    void fireBroadcastsIdenticalShotResolvedToBothPlayers() throws Exception {
        TestClient clientA = new TestClient();
        TestClient clientB = new TestClient();
        try {
            // Drain each client's initial MatchStateSync (order across sockets isn't
            // guaranteed to interleave, so just consume one message per client).
            clientA.awaitNext();
            clientB.awaitNext();

            clientA.send("{\"type\":\"Fire\",\"v\":1,\"requestId\":\"r10\",\"payload\":"
                    + "{\"weaponId\":\"basic_shell\",\"angleDeg\":45,\"power\":70}}");

            String resolvedA = clientA.awaitNext();
            String resolvedB = clientB.awaitNext();

            JsonNode nodeA = MAPPER.readTree(resolvedA);
            JsonNode nodeB = MAPPER.readTree(resolvedB);

            assertEquals("ShotResolved", nodeA.get("type").asText());
            assertEquals("ShotResolved", nodeB.get("type").asText());
            // Identical payload for both clients (the core M1 checkpoint).
            assertEquals(nodeA.get("payload"), nodeB.get("payload"));

            JsonNode payload = nodeA.get("payload");
            assertEquals("p-1", payload.get("shooterId").asText());
            assertEquals("basic_shell", payload.get("weaponId").asText());
            assertTrue(payload.get("trajectory").size() >= 2);
            assertTrue(payload.get("trajectory").size() <= 40);
            JsonNode terrainDelta = payload.get("terrainDelta");
            assertTrue(terrainDelta.get("endX").asInt() >= terrainDelta.get("startX").asInt());
            assertEquals(
                    terrainDelta.get("endX").asInt() - terrainDelta.get("startX").asInt() + 1,
                    terrainDelta.get("heights").size());
        } finally {
            clientA.close();
            clientB.close();
        }
    }

    @Test
    @Timeout(10)
    void firingUnknownWeaponIsRejectedNotCrashed() throws Exception {
        TestClient client = new TestClient();
        try {
            client.awaitNext(); // MatchStateSync

            client.send("{\"type\":\"Fire\",\"v\":1,\"requestId\":\"r11\",\"payload\":"
                    + "{\"weaponId\":\"nuke\",\"angleDeg\":45,\"power\":70}}");

            String response = client.awaitNext();
            JsonNode node = MAPPER.readTree(response);
            assertEquals("FireRejected", node.get("type").asText());
            assertFalse(node.get("payload").get("reason").asText().isEmpty());
        } finally {
            client.close();
        }
    }
}
