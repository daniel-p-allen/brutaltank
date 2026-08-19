package com.brutaltank.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The wire envelope wrapping every message in both directions, per
 * shared/protocol.md section 1.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Envelope {

    public String type;
    public int v = 1;
    public String requestId;
    public Object payload;

    public Envelope() {
    }

    public Envelope(String type, String requestId, Object payload) {
        this.type = type;
        this.v = 1;
        this.requestId = requestId;
        this.payload = payload;
    }

    public static Envelope of(String type, Object payload) {
        return new Envelope(type, null, payload);
    }

    public static Envelope of(String type, String requestId, Object payload) {
        return new Envelope(type, requestId, payload);
    }
}
