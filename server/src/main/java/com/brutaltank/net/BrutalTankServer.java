package com.brutaltank.net;

import com.brutaltank.lobby.LobbyManager;
import com.brutaltank.match.Match;
import com.brutaltank.match.MatchRegistry;
import com.brutaltank.protocol.Envelope;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * M2 entrypoint: boots Undertow, serves the single WebSocket endpoint
 * {@code /ws}, and routes envelope-wrapped messages (see shared/protocol.md)
 * to the lobby/match layer. Handles {@code Ping}->{@code Pong} (M0), the
 * full lobby flow (M2: CreateMatch/JoinMatch/SetReady/Rejoin/LeaveMatch), and
 * turn-gated {@code Fire}->{@code ShotResolved}/{@code FireRejected}. M4 adds
 * {@code ShopPurchase}->{@code ShopUpdate}/{@code ErrorMsg}.
 */
public final class BrutalTankServer {

    private static final Logger LOG = Logger.getLogger(BrutalTankServer.class.getName());
    private static final int PORT = 8080;
    private static final String HOST = "0.0.0.0";

    private final Undertow server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MatchRegistry matchRegistry = new MatchRegistry();
    // Shared scheduler for turn-timeout / reconnect-grace timers across all matches
    // (PLAN.md 2.2: MatchRegistry is the only cross-match shared structure; a shared
    // scheduler thread is a similarly small, safe piece of shared infrastructure since
    // the actual state mutation each scheduled task performs is guarded by that match's
    // own monitor, not by the scheduler thread).
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final LobbyManager lobbyManager;
    private final ConcurrentHashMap<WebSocketChannel, PlayerSession> sessions = new ConcurrentHashMap<>();

    public BrutalTankServer() {
        this(-1, -1);
    }

    /**
     * Test-only constructor: overrides every match's turn-timeout/reconnect-grace
     * durations so integration tests can drive the real timer-based code paths
     * (auto-skip, safety-cap round-end, grace expiry) without sleeping the real
     * 30s/120s production durations. Pass -1 for either to keep the production
     * default.
     */
    public BrutalTankServer(long turnTimeoutMsOverride, long reconnectGraceMsOverride) {
        this.lobbyManager = new LobbyManager(matchRegistry, mapper, scheduler, turnTimeoutMsOverride, reconnectGraceMsOverride);

        // Per the concurrency model in PLAN.md, off-I/O-thread work runs on Java 21
        // virtual threads rather than pooled platform threads, since dispatched work
        // is mostly short (a single match's synchronized method call).
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        this.server = Undertow.builder()
                .addHttpListener(PORT, HOST)
                .setHandler(Handlers.websocket((exchange, channel) -> onConnect(exchange, channel, virtualThreadExecutor)))
                .build();
    }

    private void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel, ExecutorService executor) {
        PlayerSession session = new PlayerSession(new WebSocketMessageSink(channel));
        sessions.put(channel, session);
        LOG.info("Connected: " + channel.hashCode());

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage message) {
                String data = message.getData();
                // Dispatched via a virtual thread to keep the I/O thread free,
                // matching the "no game-state mutation on the I/O thread" rule.
                executor.execute(() -> handleMessage(ch, session, data));
            }

            @Override
            protected void onClose(WebSocketChannel webSocketChannel, io.undertow.websockets.core.StreamSourceFrameChannel channel)
                    throws java.io.IOException {
                PlayerSession closed = sessions.remove(webSocketChannel);
                if (closed != null) {
                    executor.execute(() -> lobbyManager.handleSocketClosed(closed));
                }
                super.onClose(webSocketChannel, channel);
            }
        });
        channel.resumeReceives();
    }

    private void handleMessage(WebSocketChannel channel, PlayerSession session, String data) {
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

        session.touch();
        MessageSink sink = session.sink;

        try {
            switch (envelope.type) {
                case "Ping" -> handlePing(sink, envelope);
                case "CreateMatch" -> lobbyManager.handleCreateMatch(session, sink, envelope.requestId,
                        mapper.treeToValue(payloadNode, Payloads.CreateMatch.class));
                case "JoinMatch" -> lobbyManager.handleJoinMatch(session, sink, envelope.requestId,
                        mapper.treeToValue(payloadNode, Payloads.JoinMatch.class));
                case "SetReady" -> lobbyManager.handleSetReady(session, envelope.requestId, sink,
                        mapper.treeToValue(payloadNode, Payloads.SetReady.class).ready);
                case "Rejoin" -> lobbyManager.handleRejoin(session, sink, envelope.requestId,
                        mapper.treeToValue(payloadNode, Payloads.Rejoin.class));
                case "LeaveMatch" -> lobbyManager.handleLeaveMatch(session);
                case "Fire" -> handleFire(session, sink, envelope, payloadNode);
                case "ShopPurchase" -> handleShopPurchase(session, sink, envelope, payloadNode);
                default -> LOG.fine("Ignoring unhandled message type: " + envelope.type);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling message type " + envelope.type, e);
        }
    }

    private void handlePing(MessageSink sink, Envelope envelope) {
        Payloads.Pong pong = new Payloads.Pong(System.currentTimeMillis());
        Envelopes.send(sink, mapper, "Pong", envelope.requestId, pong);
    }

    private void handleFire(PlayerSession session, MessageSink sink, Envelope envelope, JsonNode payloadNode) throws Exception {
        Payloads.Fire fire = mapper.treeToValue(payloadNode, Payloads.Fire.class);

        if (session.currentMatchId == null || session.playerId == null) {
            Envelopes.send(sink, mapper, "FireRejected", envelope.requestId,
                    new Payloads.FireRejected("MATCH_NOT_IN_PROGRESS"));
            return;
        }
        Match match = matchRegistry.get(session.currentMatchId);
        if (match == null) {
            Envelopes.send(sink, mapper, "FireRejected", envelope.requestId,
                    new Payloads.FireRejected("MATCH_NOT_IN_PROGRESS"));
            return;
        }

        Match.FireOutcome outcome = match.fire(session.playerId, envelope.requestId, fire.weaponId, fire.angleDeg, fire.power);
        if (!outcome.accepted()) {
            Envelopes.send(sink, mapper, "FireRejected", envelope.requestId,
                    new Payloads.FireRejected(outcome.rejectReason()));
        }
        // On success, Match.fire() has already broadcast ShotResolved (and any
        // round/match transition messages) to every connected player itself.
    }

    private void handleShopPurchase(PlayerSession session, MessageSink sink, Envelope envelope, JsonNode payloadNode) throws Exception {
        Payloads.ShopPurchase purchase = mapper.treeToValue(payloadNode, Payloads.ShopPurchase.class);

        if (session.currentMatchId == null || session.playerId == null) {
            Envelopes.send(sink, mapper, "ErrorMsg", envelope.requestId,
                    new Payloads.ErrorMsg("NOT_SHOP_PHASE", "You're not in a match."));
            return;
        }
        Match match = matchRegistry.get(session.currentMatchId);
        if (match == null) {
            Envelopes.send(sink, mapper, "ErrorMsg", envelope.requestId,
                    new Payloads.ErrorMsg("NOT_SHOP_PHASE", "You're not in a match."));
            return;
        }

        Match.PurchaseOutcome outcome = match.purchase(
                session.playerId, purchase.itemId, purchase.itemType, purchase.quantity);
        if (!outcome.accepted()) {
            Envelopes.send(sink, mapper, "ErrorMsg", envelope.requestId,
                    new Payloads.ErrorMsg(outcome.rejectReason(), shopRejectionMessage(outcome.rejectReason())));
        }
        // On success, Match.purchase() has already broadcast ShopUpdate to
        // every connected player itself (see handleFire's identical note).
    }

    private static String shopRejectionMessage(String reason) {
        return switch (reason) {
            case "NOT_SHOP_PHASE" -> "The shop isn't open right now.";
            case "INSUFFICIENT_CASH" -> "You can't afford that.";
            case "OUT_OF_STOCK" -> "Not enough left in stock.";
            case "INVALID_ITEM" -> "That item isn't available in the shop.";
            case "INVALID_QUANTITY" -> "Enter a valid quantity.";
            default -> "Purchase failed.";
        };
    }

    public void start() {
        server.start();
        LOG.info("BrutalTank server listening on :" + PORT + "/ws");
    }

    public void stop() {
        server.stop();
        scheduler.shutdownNow();
    }

    public static void main(String[] args) {
        new BrutalTankServer().start();
    }
}
