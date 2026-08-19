<script lang="ts">
	import { onMount } from 'svelte';
	import { connectionStore } from '../../stores/connectionStore';

	onMount(() => {
		connectionStore.connect();
	});

	$: statusLabel = (() => {
		switch ($connectionStore.status) {
			case 'connecting':
				return 'Connecting...';
			case 'open':
				return 'Connected';
			case 'reconnecting':
				return 'Reconnecting...';
			case 'error':
				return 'Connection error';
			case 'closed':
			default:
				return 'Disconnected';
		}
	})();

	$: statusClass = $connectionStore.status === 'open' ? 'ok' : 'warn';

	function sendPing() {
		connectionStore.ping();
	}
</script>

<div class="connection-status">
	<div class="row">
		<span class="dot {statusClass}"></span>
		<span class="label">{statusLabel}</span>
	</div>

	<div class="row">
		<button on:click={sendPing} disabled={$connectionStore.status !== 'open'}>
			Send Ping
		</button>
		{#if $connectionStore.latencyMs !== null}
			<span class="latency">{Math.round($connectionStore.latencyMs)} ms</span>
		{/if}
	</div>

	{#if $connectionStore.lastMessage !== null}
		<div class="row echo">
			<span class="echo-label">Last message:</span>
			<code>{$connectionStore.lastMessage}</code>
		</div>
	{/if}

	{#if $connectionStore.reconnectAttempts > 0}
		<div class="row reconnects">Reconnect attempts: {$connectionStore.reconnectAttempts}</div>
	{/if}
</div>

<style>
	.connection-status {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		padding: 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: monospace;
		max-width: 24rem;
	}

	.row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.dot {
		width: 0.75rem;
		height: 0.75rem;
		border-radius: 50%;
		background: #888;
	}

	.dot.ok {
		background: #3ecf5f;
	}

	.dot.warn {
		background: #e0a020;
	}

	.latency {
		color: #888;
	}

	.echo code {
		word-break: break-all;
	}

	.reconnects {
		color: #888;
		font-size: 0.85em;
	}
</style>
