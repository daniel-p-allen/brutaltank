// Lightweight WebSocket client for BrutalTank (M0 scaffolding).
//
// Responsibilities for M0:
//   - connect to the server WS endpoint
//   - expose connection state (connecting/open/closed/error)
//   - send(message) helper
//   - onMessage callback registration
//   - simple auto-reconnect with backoff on unexpected close
//
// Full reconnect/playerToken/rejoin flow is a later milestone (see PLAN.md
// section 2.1) — this is intentionally minimal.

export type ConnectionState =
	| 'connecting'
	| 'open'
	| 'closed'
	| 'reconnecting'
	| 'error';

export type MessageHandler = (data: string) => void;
export type StateHandler = (state: ConnectionState) => void;

const DEFAULT_URL = 'ws://localhost:8080/ws';
const INITIAL_BACKOFF_MS = 500;
const MAX_BACKOFF_MS = 8000;

export class WsClient {
	private url: string;
	private socket: WebSocket | null = null;
	private state: ConnectionState = 'closed';
	private backoffMs = INITIAL_BACKOFF_MS;
	private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
	private shouldReconnect = true;

	private messageHandlers = new Set<MessageHandler>();
	private stateHandlers = new Set<StateHandler>();

	constructor(url: string = DEFAULT_URL) {
		this.url = url;
	}

	connect(): void {
		this.shouldReconnect = true;
		this.open();
	}

	disconnect(): void {
		this.shouldReconnect = false;
		if (this.reconnectTimer) {
			clearTimeout(this.reconnectTimer);
			this.reconnectTimer = null;
		}
		this.socket?.close();
	}

	send(message: string): void {
		if (this.socket && this.state === 'open') {
			this.socket.send(message);
		} else {
			console.warn('[wsClient] cannot send, socket not open:', message);
		}
	}

	onMessage(handler: MessageHandler): () => void {
		this.messageHandlers.add(handler);
		return () => this.messageHandlers.delete(handler);
	}

	onStateChange(handler: StateHandler): () => void {
		this.stateHandlers.add(handler);
		return () => this.stateHandlers.delete(handler);
	}

	getState(): ConnectionState {
		return this.state;
	}

	private open(): void {
		this.setState(this.backoffMs === INITIAL_BACKOFF_MS ? 'connecting' : 'reconnecting');

		const socket = new WebSocket(this.url);
		this.socket = socket;

		socket.addEventListener('open', () => {
			this.backoffMs = INITIAL_BACKOFF_MS;
			this.setState('open');
		});

		socket.addEventListener('message', (event: MessageEvent) => {
			const data = typeof event.data === 'string' ? event.data : String(event.data);
			for (const handler of this.messageHandlers) handler(data);
		});

		socket.addEventListener('close', () => {
			this.socket = null;
			if (this.shouldReconnect) {
				this.setState('reconnecting');
				this.scheduleReconnect();
			} else {
				this.setState('closed');
			}
		});

		socket.addEventListener('error', () => {
			this.setState('error');
		});
	}

	private scheduleReconnect(): void {
		if (this.reconnectTimer) return;
		this.reconnectTimer = setTimeout(() => {
			this.reconnectTimer = null;
			this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS);
			if (this.shouldReconnect) this.open();
		}, this.backoffMs);
	}

	private setState(state: ConnectionState): void {
		this.state = state;
		for (const handler of this.stateHandlers) handler(state);
	}
}

// Shared singleton for the app to use — a single connection to the server.
export const wsClient = new WsClient();
