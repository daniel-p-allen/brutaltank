import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { MockWebSocket } from './mockWebSocket.test-util';

describe('WsClient', () => {
	let WsClient: typeof import('./wsClient').WsClient;

	beforeEach(async () => {
		vi.useFakeTimers();
		MockWebSocket.reset();
		vi.stubGlobal('WebSocket', MockWebSocket as unknown as typeof WebSocket);
		// Re-import fresh each test since WsClient is a plain class (no module
		// singleton state to worry about here — connectionStore.test.ts is the
		// one that needs isolation from the shared `wsClient` singleton).
		vi.resetModules();
		({ WsClient } = await import('./wsClient'));
	});

	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	it('transitions connecting -> open and notifies state handlers', () => {
		const client = new WsClient('ws://test/ws');
		const states: string[] = [];
		client.onStateChange((s) => states.push(s));

		client.connect();
		expect(states).toEqual(['connecting']);

		MockWebSocket.latest().emitOpen();
		expect(states).toEqual(['connecting', 'open']);
		expect(client.getState()).toBe('open');
	});

	it('delivers incoming messages to registered handlers', () => {
		const client = new WsClient('ws://test/ws');
		const received: string[] = [];
		client.onMessage((data) => received.push(data));

		client.connect();
		MockWebSocket.latest().emitOpen();
		MockWebSocket.latest().emitMessage('Pong');

		expect(received).toEqual(['Pong']);
	});

	it('sends only while open, and warns otherwise', () => {
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		const client = new WsClient('ws://test/ws');

		client.send('too-early');
		expect(warn).toHaveBeenCalled();

		client.connect();
		MockWebSocket.latest().emitOpen();
		client.send('Ping');

		expect(MockWebSocket.latest().sent).toEqual(['Ping']);
		warn.mockRestore();
	});

	it('auto-reconnects on unexpected close with exponential backoff, capped', () => {
		const client = new WsClient('ws://test/ws');
		const states: string[] = [];
		client.onStateChange((s) => states.push(s));

		client.connect();
		MockWebSocket.latest().emitOpen();
		expect(MockWebSocket.instances.length).toBe(1);

		// Unexpected close -> reconnecting, then a new socket after backoff.
		MockWebSocket.latest().emitClose();
		expect(client.getState()).toBe('reconnecting');

		vi.advanceTimersByTime(500); // INITIAL_BACKOFF_MS
		expect(MockWebSocket.instances.length).toBe(2);

		// Second failure should back off further (doubled), not retry immediately.
		MockWebSocket.latest().emitClose();
		vi.advanceTimersByTime(500);
		expect(MockWebSocket.instances.length).toBe(2); // not yet, backoff doubled to 1000ms
		vi.advanceTimersByTime(500);
		expect(MockWebSocket.instances.length).toBe(3);
	});

	it('does not reconnect after an explicit disconnect()', () => {
		const client = new WsClient('ws://test/ws');
		client.connect();
		MockWebSocket.latest().emitOpen();

		client.disconnect();
		expect(client.getState()).toBe('closed');

		vi.advanceTimersByTime(10_000);
		expect(MockWebSocket.instances.length).toBe(1);
	});
});
