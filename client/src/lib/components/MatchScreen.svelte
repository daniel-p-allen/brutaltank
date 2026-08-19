<script lang="ts">
	// M1 hardcoded match screen: no lobby, auto-join (PLAN.md M1 scope).
	// Shown once connectionStore reports 'open'; matchStore populates itself
	// as soon as the server's (auto-joined) MatchStateSync arrives.

	import GameCanvas from './game/GameCanvas.svelte';
	import FireControls from './hud/FireControls.svelte';
	import { matchStore } from '../stores/matchStore';
</script>

<div class="match-screen">
	<GameCanvas />
	<FireControls />

	{#if $matchStore.matchId === null}
		<p class="waiting">Waiting for match state from server...</p>
	{:else}
		<div class="players">
			{#each $matchStore.players as player (player.playerId)}
				<span class="player" style="color: {player.color}">
					{player.displayName}: {player.tank.health} hp{player.tank.alive ? '' : ' (destroyed)'}
				</span>
			{/each}
			{#if $matchStore.wind}
				<span class="wind">wind {$matchStore.wind.strength * $matchStore.wind.directionSign}</span>
			{/if}
		</div>
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

	.players {
		display: flex;
		gap: 1rem;
		font-family: monospace;
		font-size: 0.9rem;
	}

	.wind {
		color: #888;
		margin-left: auto;
	}
</style>
