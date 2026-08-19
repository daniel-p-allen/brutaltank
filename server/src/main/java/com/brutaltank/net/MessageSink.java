package com.brutaltank.net;

/**
 * A destination a server-authored message can be written to. Abstracts over
 * the real Undertow {@code WebSocketChannel} ({@link WebSocketMessageSink})
 * so that {@code Match}/{@code LobbyManager} logic can be unit-tested without
 * a live socket (see test doubles under {@code src/test}).
 */
public interface MessageSink {

    void send(String json);

    boolean isOpen();
}
