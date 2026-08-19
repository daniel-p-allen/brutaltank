// Minimal fake WebSocket for unit tests. Real WS behavior (framing,
// handshake, etc.) is exercised manually / by the server's own echo test —
// this only needs to emit the same events real WebSocket does so wsClient's
// state machine and reconnect logic can be tested without a network.

export class MockWebSocket {
	static instances: MockWebSocket[] = [];
	static OPEN = 1;
	static CLOSED = 3;

	url: string;
	readyState = 0;
	sent: string[] = [];

	onopen: (() => void) | null = null;
	onclose: (() => void) | null = null;
	onerror: (() => void) | null = null;
	onmessage: ((event: { data: string }) => void) | null = null;

	private listeners: Record<string, Set<(event?: unknown) => void>> = {
		open: new Set(),
		close: new Set(),
		error: new Set(),
		message: new Set()
	};

	constructor(url: string) {
		this.url = url;
		MockWebSocket.instances.push(this);
	}

	addEventListener(type: string, handler: (event?: unknown) => void): void {
		this.listeners[type]?.add(handler);
	}

	removeEventListener(type: string, handler: (event?: unknown) => void): void {
		this.listeners[type]?.delete(handler);
	}

	send(data: string): void {
		this.sent.push(data);
	}

	close(): void {
		this.readyState = MockWebSocket.CLOSED;
		this.emit('close');
	}

	// --- test-side helpers to drive the fake socket ---

	emitOpen(): void {
		this.readyState = MockWebSocket.OPEN;
		this.emit('open');
	}

	emitMessage(data: string): void {
		this.emit('message', { data });
	}

	emitClose(): void {
		this.readyState = MockWebSocket.CLOSED;
		this.emit('close');
	}

	emitError(): void {
		this.emit('error');
	}

	private emit(type: string, event?: unknown): void {
		for (const handler of this.listeners[type]) handler(event);
	}

	static reset(): void {
		MockWebSocket.instances = [];
	}

	static latest(): MockWebSocket {
		const ws = MockWebSocket.instances.at(-1);
		if (!ws) throw new Error('no MockWebSocket instance created yet');
		return ws;
	}
}
