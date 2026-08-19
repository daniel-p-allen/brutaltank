package com.brutaltank.net;

import com.brutaltank.match.Match;
import com.brutaltank.protocol.Envelope;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M1 entrypoint: boots Undertow, serves the single WebSocket endpoint {@code
 * /ws}, and routes envelope-wrapped messages (see shared/protocol.md) to the
 * one hardcoded match. Handles {@code Ping}->{@code Pong} (M0 behavior),
 * sends {@code MatchStateSync} on connect, and {@code Fire}->{@code
 * ShotResolved} (broadcast to all connected sessions).
 */
public final class BrutalTankServer {

    private static final Logger LOG = Logger.getLogger(BrutalTankServer.class.getName());
    private static final int PORT = 8080;
    private static final String HOST = "0.0.0.0";

    private final Undertow server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Match match = new Match(mapper);

    public BrutalTankServer() {
        // Per the concurrency model in PLAN.md, off-I/O-thread work (and later,
        // per-match actors) run on Java 21 virtual threads rather than pooled
        // platform threads, since match threads are mostly idle waiting on work.
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        this.server = Undertow.builder()
                .addHttpListener(PORT, HOST)
                .setHandler(Handlers.websocket((exchange, channel) -> onConnect(exchange, channel, virtualThreadExecutor)))
                .build();
    }

    private void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel, ExecutorService executor) {
        executor.execute(() -> {
            String playerId = match.assignSlot(channel);
            match.sendTo(channel, "MatchStateSync", match.buildStateSync());
            LOG.info("Connected: " + channel.hashCode() + " as " + (playerId == null ? "spectator" : playerId));
        });

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage message) {
                String data = message.getData();
                // Dispatched via a virtual thread to keep the I/O thread free,
                // matching the "no game-state mutation on the I/O thread" rule.
                executor.execute(() -> handleMessage(ch, data));
            }

            @Override
            protected void onClose(WebSocketChannel webSocketChannel, io.undertow.websockets.core.StreamSourceFrameChannel channel)
                    throws java.io.IOException {
                match.removeChannel(webSocketChannel);
                super.onClose(webSocketChannel, channel);
            }
        });
        channel.resumeReceives();
    }

    private void handleMessage(WebSocketChannel channel, String data) {
        Envelope envelope;
        JsonNode payloadNode;
        try {
            JsonNode root = mapper.readTree(data);
            envelope = new Envelope();
            envelope.type = root.path("type").asText(null);
            envelope.v = root.path("v").asInt(1);
            envelope.requestId = root.hasNonNull("requestId") ? root.get("requestId").asText() : null;
            payloadNode = root.path("payload");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Malformed envelope", e);
            return;
        }

        if (envelope.type == null) {
            return;
        }

        try {
            switch (envelope.type) {
                case "Ping" -> handlePing(channel, envelope);
                case "Fire" -> handleFire(channel, envelope, payloadNode);
                default -> LOG.fine("Ignoring unhandled message type: " + envelope.type);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling message type " + envelope.type, e);
        }
    }

    private void handlePing(WebSocketChannel channel, Envelope envelope) {
        Payloads.Pong pong = new Payloads.Pong(System.currentTimeMillis());
        match.sendTo(channel, "Pong", envelope.requestId, pong);
    }

    private void handleFire(WebSocketChannel channel, Envelope envelope, JsonNode payloadNode) throws Exception {
        Payloads.Fire fire = mapper.treeToValue(payloadNode, Payloads.Fire.class);
        String shooterId = match.playerIdFor(channel);

        if (shooterId == null || !"basic_shell".equals(fire.weaponId)) {
            Payloads.FireRejected rejected = new Payloads.FireRejected(
                    shooterId == null ? "SPECTATOR_CANNOT_FIRE" : "INVALID_WEAPON");
            match.sendTo(channel, "FireRejected", envelope.requestId, rejected);
            return;
        }

        Payloads.ShotResolved resolved = match.fire(shooterId, fire.weaponId, fire.angleDeg, fire.power);
        if (resolved == null) {
            match.sendTo(channel, "FireRejected", envelope.requestId, new Payloads.FireRejected("INVALID_FIRE"));
            return;
        }
        match.broadcast("ShotResolved", envelope.requestId, resolved);
    }

    public void start() {
        server.start();
        LOG.info("BrutalTank server listening on :" + PORT + "/ws");
    }

    public void stop() {
        server.stop();
    }

    public static void main(String[] args) {
        new BrutalTankServer().start();
    }
}
