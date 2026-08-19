package com.brutaltank.net;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * M0 scaffolding entrypoint: boots Undertow and serves a bare WebSocket echo
 * endpoint at {@code /ws}. Real protocol handling (envelope parsing,
 * MatchActor command dispatch, etc.) arrives in later milestones.
 */
public final class BrutalTankServer {

    private static final Logger LOG = Logger.getLogger(BrutalTankServer.class.getName());
    private static final int PORT = 8080;
    private static final String HOST = "0.0.0.0";

    private final Undertow server;

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
        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel ch, BufferedTextMessage message) {
                String data = message.getData();
                // Dispatched via a virtual thread to keep the I/O thread free,
                // matching the "no game-state mutation on the I/O thread" rule
                // that MatchActor command handling will follow in later milestones.
                executor.execute(() -> WebSockets.sendText(data, ch, null));
            }
        });
        channel.resumeReceives();
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
