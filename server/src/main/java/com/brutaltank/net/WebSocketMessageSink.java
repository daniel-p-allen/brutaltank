package com.brutaltank.net;

import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

/** Real {@link MessageSink} backed by a live Undertow {@link WebSocketChannel}. */
public final class WebSocketMessageSink implements MessageSink {

    private final WebSocketChannel channel;

    public WebSocketMessageSink(WebSocketChannel channel) {
        this.channel = channel;
    }

    @Override
    public void send(String json) {
        if (channel.isOpen()) {
            WebSockets.sendText(json, channel, null);
        }
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    public WebSocketChannel channel() {
        return channel;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebSocketMessageSink other)) {
            return false;
        }
        return channel == other.channel;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(channel);
    }
}
