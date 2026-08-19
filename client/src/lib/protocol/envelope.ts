// Small helpers for building/parsing the protocol envelope (protocol.md
// section 1). Kept deliberately dependency-free so it's trivial to unit test
// and to reuse from both matchStore and HUD input components.

import type { Envelope } from './types';

const PROTOCOL_VERSION = 1;

let requestCounter = 0;

/** Generates a short, unique-enough client-correlation id for requestId. */
export function nextRequestId(): string {
	requestCounter += 1;
	return `c-${Date.now().toString(36)}-${requestCounter}`;
}

export function buildEnvelope<TPayload>(
	type: string,
	payload: TPayload,
	requestId?: string
): Envelope<TPayload> {
	return {
		type,
		v: PROTOCOL_VERSION,
		...(requestId ? { requestId } : {}),
		payload
	};
}

/**
 * Parses a raw WS text frame into an Envelope, or returns null if it isn't
 * one (e.g. the legacy plain-text "Ping"/"Pong" strings still used by
 * connectionStore's M0 latency probe, or malformed JSON).
 */
export function parseEnvelope(data: string): Envelope | null {
	let parsed: unknown;
	try {
		parsed = JSON.parse(data);
	} catch {
		return null;
	}
	if (
		typeof parsed === 'object' &&
		parsed !== null &&
		typeof (parsed as Envelope).type === 'string' &&
		typeof (parsed as Envelope).v === 'number' &&
		'payload' in (parsed as Envelope)
	) {
		return parsed as Envelope;
	}
	return null;
}
