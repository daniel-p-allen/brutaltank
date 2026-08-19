package com.brutaltank.match;

import com.brutaltank.net.MessageSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link MessageSink} test double: records every frame it "sent". */
public final class FakeMessageSink implements MessageSink {

    private final List<String> messages = new CopyOnWriteArrayList<>();
    private volatile boolean open = true;

    @Override
    public void send(String json) {
        if (open) {
            messages.add(json);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    public List<String> messages() {
        return messages;
    }

    /** Returns the payload JSON node of the last message of the given type, or null. */
    public com.fasterxml.jackson.databind.JsonNode lastPayloadOfType(String type) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode last = null;
        for (String m : messages) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(m);
                if (type.equals(node.path("type").asText())) {
                    last = node.path("payload");
                }
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return last;
    }

    public long countOfType(String type) {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        long count = 0;
        for (String m : messages) {
            try {
                com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(m);
                if (type.equals(node.path("type").asText())) {
                    count++;
                }
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return count;
    }
}
