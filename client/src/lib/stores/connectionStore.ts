// Svelte store wrapping WS connection state, per PLAN.md section 3.2
// (`connectionStore`: status/reconnects/latency).
//
// M0: latency is measured via a simple Ping/echo round-trip stub. The real
// protocol's `Ping{}` / server echo shape will replace this in a later
// milestone once shared/protocol.md is implemented server-side.

import { writable, derived } from 'svelte/store';
import { wsClient, type ConnectionState } from '../net/wsClient';

export interface ConnectionInfo {
	status: ConnectionState;
	reconnectAttempts: number;
	latencyMs: number | null;
	lastMessage: string | null;
}

function createConnectionStore() {
	const { subscribe, update } = writable<ConnectionInfo>({
		status: 'closed',
		reconnectAttempts: 0,
		latencyMs: null,
		lastMessage: null
	});

	let pingSentAt: number | null = null;

	wsClient.onStateChange((state) => {
		update((info) => ({
			...info,
			status: state,
			reconnectAttempts:
				state === 'reconnecting' ? info.reconnectAttempts + 1 : info.reconnectAttempts
		}));
	});

	wsClient.onMessage((data) => {
		if (pingSentAt !== null) {
			const latencyMs = performance.now() - pingSentAt;
			pingSentAt = null;
			update((info) => ({ ...info, latencyMs, lastMessage: data }));
		} else {
			update((info) => ({ ...info, lastMessage: data }));
		}
	});

	function connect(): void {
		wsClient.connect();
	}

	function ping(): void {
		pingSentAt = performance.now();
		wsClient.send('Ping');
	}

	return { subscribe, connect, ping };
}

export const connectionStore = createConnectionStore();

export const connectionStatus = derived(connectionStore, ($c) => $c.status);
