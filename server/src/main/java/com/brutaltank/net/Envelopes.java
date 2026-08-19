package com.brutaltank.net;

import com.brutaltank.protocol.Envelope;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Small shared helper for writing an envelope-wrapped payload to a {@link MessageSink}. */
public final class Envelopes {

    private static final Logger LOG = Logger.getLogger(Envelopes.class.getName());

    private Envelopes() {
    }

    public static void send(MessageSink sink, ObjectMapper mapper, String type, String requestId, Object payload) {
        if (sink == null || !sink.isOpen()) {
            return;
        }
        try {
            String json = mapper.writeValueAsString(Envelope.of(type, requestId, payload));
            sink.send(json);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to serialize/send " + type, e);
        }
    }
}
