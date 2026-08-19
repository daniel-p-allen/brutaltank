package com.brutaltank;

import com.brutaltank.net.BrutalTankServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M0 smoke test: boots the real server, opens a WebSocket connection to
 * /ws with the JDK's HttpClient WebSocket, sends a text frame, and asserts
 * it comes back byte-for-byte (echo semantics for M0).
 */
class BrutalTankServerTest {

    private static BrutalTankServer server;

    @BeforeAll
    static void startServer() {
        server = new BrutalTankServer();
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @Timeout(10)
    void echoesTextMessageBackToClient() throws Exception {
        CompletableFuture<String> received = new CompletableFuture<>();
        StringBuilder buffer = new StringBuilder();

        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    received.complete(buffer.toString());
                }
                webSocket.request(1);
                return null;
            }
        };

        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:8080/ws"), listener)
                .get(5, TimeUnit.SECONDS);

        try {
            String message = "ping-" + System.nanoTime();
            webSocket.sendText(message, true);

            String echoed = received.get(5, TimeUnit.SECONDS);
            assertEquals(message, echoed);
            assertTrue(echoed.startsWith("ping-"));
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        }
    }
}
