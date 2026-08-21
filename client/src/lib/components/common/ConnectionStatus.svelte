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
</script>

<div class="connection-status">
	<span class="dot {statusClass}"></span>
	<span class="label">{statusLabel}</span>
	{#if $connectionStore.reconnectAttempts > 0}
		<span class="reconnects">(reconnect attempts: {$connectionStore.reconnectAttempts})</span>
	{/if}
</div>

<style>
	.connection-status {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-family: monospace;
		font-size: 0.85em;
	}

	.dot {
		width: 0.6rem;
		height: 0.6rem;
		border-radius: 50%;
		background: #888;
		flex-shrink: 0;
	}

	.dot.ok {
		background: #3ecf5f;
	}

	.dot.warn {
		background: #e0a020;
	}

	.reconnects {
		color: #888;
	}
</style>
