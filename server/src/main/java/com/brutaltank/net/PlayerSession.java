package com.brutaltank.net;

import java.util.UUID;

/**
 * Per-connection bookkeeping (PLAN.md 2.1). Created when a WS channel
 * connects, before the socket has joined any match. {@code playerId} /
 * {@code currentMatchId} / {@code playerToken} are populated once the
 * connection successfully joins or creates a match (or re-attaches via
 * {@code Rejoin}), and are what the server's message dispatch uses to route
 * {@code Fire}/{@code SetReady}/{@code LeaveMatch} to the right {@code Match}.
 *
 * <p>Mutable fields are {@code volatile} since they may be read from the
 * Undertow I/O thread (e.g. on close) while being written from a
 * virtual-thread dispatch of a lobby/match command.
 */
public final class PlayerSession {

    public final String sessionId = UUID.randomUUID().toString();
    public final MessageSink sink;

    public volatile String playerId;
    public volatile String currentMatchId;
    public volatile String playerToken;
    public volatile boolean connected = true;
    public volatile long lastSeenAt = System.currentTimeMillis();

    public PlayerSession(MessageSink sink) {
        this.sink = sink;
    }

    public void touch() {
        this.lastSeenAt = System.currentTimeMillis();
    }
}
