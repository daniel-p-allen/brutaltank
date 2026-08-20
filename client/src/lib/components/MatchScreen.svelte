<script lang="ts">
	// PLAN.md section 3.1: MatchScreen persists across rounds. M1 built the
	// canvas + fire controls with no lobby/turn enforcement; M2 adds real
	// turn-state reflection: whose turn it is, a turn timer countdown,
	// FireRejected reasons, PlayerDisconnected/Reconnected status per player,
	// and a round-end banner shown between RoundEnded and the next round's
	// MatchStateSync (protocol.md sections 3-4).

	import { onDestroy } from 'svelte';
	import GameCanvas from './game/GameCanvas.svelte';
	import FireControls from './hud/FireControls.svelte';
	import ShopOverlay from './shop/ShopOverlay.svelte';
	import { matchStore } from '../stores/matchStore';
	import { sessionStore } from '../stores/sessionStore';

	// Turn timer: recomputed once a second from turnStartedAtMs/turnTimeoutSec
	// rather than stored reactively, since it's purely a display concern.
	let now = Date.now();
	const tickInterval = setInterval(() => (now = Date.now()), 250);
	onDestroy(() => clearInterval(tickInterval));

	$: remainingTurnSec = (() => {
		const { turnStartedAtMs, turnTimeoutSec } = $matchStore;
		if (turnStartedAtMs === null || turnTimeoutSec === null) return null;
		const elapsed = (now - turnStartedAtMs) / 1000;
		return Math.max(0, Math.ceil(turnTimeoutSec - elapsed));
	})();

	function playerName(playerId: string | null): string {
		if (!playerId) return '';
		return $matchStore.players.find((p) => p.playerId === playerId)?.displayName ?? playerId;
	}

	$: activePlayerIsYou =
		$matchStore.activePlayerId !== null && $matchStore.activePlayerId === $sessionStore.playerId;
</script>

<div class="match-screen">
	<GameCanvas />

	{#if $matchStore.matchId === null}
		<p class="waiting">Waiting for match state from server...</p>
	{:else}
		{#if $matchStore.activePlayerId}
			<div class="turn-banner" class:you={activePlayerIsYou}>
				<span class="turn-label">
					{activePlayerIsYou ? "Your turn" : `${playerName($matchStore.activePlayerId)}'s turn`}
				</span>
				{#if remainingTurnSec !== null}
					<span class="turn-timer" class:urgent={remainingTurnSec <= 5}>{remainingTurnSec}s</span>
				{/if}
			</div>
		{/if}

		{#if $matchStore.fireRejectedReason}
			<div class="toast reject">Fire rejected: {$matchStore.fireRejectedReason}</div>
		{/if}

		{#if $matchStore.status === 'SHOP'}
			<ShopOverlay />
		{:else}
			<FireControls />
		{/if}

		<div class="players">
			{#each $matchStore.players as player (player.playerId)}
				<span class="player" style="color: {player.color}">
					{player.displayName}: {player.tank.health} hp{player.tank.alive ? '' : ' (destroyed)'}
					{#if $matchStore.disconnectedPlayerIds.includes(player.playerId)}
						<span class="disconnected-badge">disconnected</span>
					{/if}
				</span>
			{/each}
		</div>

		{#if $matchStore.roundEndedInfo}
			<div class="round-end-overlay">
				<h3>Round Complete</h3>
				<p>
					{#if $matchStore.roundEndedInfo.winnerPlayerId}
						Winner: {playerName($matchStore.roundEndedInfo.winnerPlayerId)}
					{:else}
						Draw (safety cap reached)
					{/if}
				</p>
				<ul>
					{#each $matchStore.roundEndedInfo.standings as standing (standing.playerId)}
						<li>{playerName(standing.playerId)}: ${standing.cash}</li>
					{/each}
				</ul>
				<p class="next-round-note">Next round starting...</p>
			</div>
		{/if}
	{/if}
</div>

<style>
	.match-screen {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.waiting {
		color: #888;
		font-family: monospace;
	}

	.turn-banner {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.4rem 0.75rem;
		border-radius: 6px;
		background: rgba(255, 255, 255, 0.04);
		font-family: system-ui, sans-serif;
		font-weight: 600;
	}

	.turn-banner.you {
		background: rgba(208, 57, 43, 0.15);
		outline: 1px solid #d0392b;
	}

	.turn-timer {
		margin-left: auto;
		font-family: monospace;
		color: #888;
	}

	.turn-timer.urgent {
		color: #d0392b;
	}

	.toast.reject {
		padding: 0.4rem 0.75rem;
		border-radius: 6px;
		background: rgba(208, 57, 43, 0.2);
		border: 1px solid #d0392b;
		font-family: monospace;
		font-size: 0.85rem;
	}

	.players {
		display: flex;
		gap: 1rem;
		font-family: monospace;
		font-size: 0.9rem;
	}

	.disconnected-badge {
		margin-left: 0.25rem;
		font-size: 0.7rem;
		color: #e0a020;
	}

	.round-end-overlay {
		padding: 0.75rem 1rem;
		border-radius: 8px;
		background: rgba(0, 0, 0, 0.3);
		border: 1px solid #444;
		font-family: system-ui, sans-serif;
	}

	.round-end-overlay ul {
		margin: 0.25rem 0;
		padding-left: 1.25rem;
	}

	.next-round-note {
		color: #888;
		font-size: 0.85rem;
		margin-bottom: 0;
	}
</style>
