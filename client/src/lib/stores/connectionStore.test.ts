import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { get } from 'svelte/store';
import { MockWebSocket } from '../net/mockWebSocket.test-util';

describe('connectionStore', () => {
	let connectionStore: typeof import('./connectionStore').connectionStore;

	beforeEach(async () => {
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		// connectionStore wraps the `wsClient` singleton, created at module load,
		// so it must be re-imported fresh per test (after stubbing WebSocket)
		// rather than reusing one shared across the suite.
		vi.resetModules();
		({ connectionStore } = await import('./connectionStore'));
	});

	afterEach(() => {
		vi.unstubAllGlobals();
	});

	it('reflects the underlying socket state', () => {
		connectionStore.connect();
		expect(get(connectionStore).status).toBe('connecting');

		MockWebSocket.latest().emitOpen();
		expect(get(connectionStore).status).toBe('open');
	});

	it('counts reconnect attempts', () => {
		connectionStore.connect();
		MockWebSocket.latest().emitOpen();
		expect(get(connectionStore).reconnectAttempts).toBe(0);

		MockWebSocket.latest().emitClose();
		expect(get(connectionStore).reconnectAttempts).toBe(1);
	});

	it('measures round-trip latency on ping()', () => {
		vi.useFakeTimers();
		connectionStore.connect();
		MockWebSocket.latest().emitOpen();

		connectionStore.ping();
		expect(MockWebSocket.latest().sent).toEqual(['Ping']);

		vi.advanceTimersByTime(25);
		MockWebSocket.latest().emitMessage('Ping');

		const info = get(connectionStore);
		expect(info.latencyMs).not.toBeNull();
		expect(info.latencyMs).toBeGreaterThanOrEqual(0);
		expect(info.lastMessage).toBe('Ping');
		vi.useRealTimers();
	});
});
