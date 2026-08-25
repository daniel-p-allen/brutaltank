<script lang="ts">
	// PLAN.md section 3.1 LobbyScreen (PlayerReadyList, MatchConfigSummary).
	// Shown while matchStore.status === 'WAITING'. Roster comes from
	// lobbyStore (LobbyUpdate broadcasts); the local player's identity comes
	// from sessionStore so we can highlight "you" and show a host badge.

	import { lobbyStore } from '../stores/lobbyStore';
	import { sessionStore } from '../stores/sessionStore';
	import { sendSetReady } from '../net/lobbyActions';
	import InstructionsScreen from './InstructionsScreen.svelte';

	let ready = false;
	// Per user request, 2026-08-24: clicking Ready shows the instructions
	// screen first; SetReady isn't actually sent to the server until the
	// player clicks Ready again there. Cancelling ready skips straight back
	// (no need to re-read instructions just to un-ready).
	let showInstructions = false;

	function toggleReady(): void {
		if (ready) {
			ready = false;
			sendSetReady(false);
			return;
		}
		showInstructions = true;
	}

	function confirmReadyFromInstructions(): void {
		showInstructions = false;
		ready = true;
		sendSetReady(true);
	}
</script>

{#if showInstructions}
	<InstructionsScreen onReady={confirmReadyFromInstructions} />
{:else}
<div class="lobby-screen">
	<h2>Lobby</h2>
	{#if $lobbyStore.matchId}
		<p class="match-code">Match code: <code>{$lobbyStore.matchId}</code></p>
	{/if}

	<ul class="roster">
		{#each $lobbyStore.players as player (player.playerId)}
			<li class="player" class:you={player.playerId === $sessionStore.playerId}>
				<span class="name">{player.displayName}</span>
				{#if player.isBot}<span class="badge bot">Bot</span>{/if}
				{#if player.isHost}<span class="badge host">Host</span>{/if}
				{#if player.playerId === $sessionStore.playerId}<span class="badge you-badge">You</span
					>{/if}
				<span class="badge ready-state" class:ready={player.ready}>
					{player.ready ? 'Ready' : 'Not ready'}
				</span>
			</li>
		{:else}
			<li class="empty">Waiting for players...</li>
		{/each}
	</ul>

	<button class="ready-toggle" class:ready on:click={toggleReady}>
		{ready ? 'Cancel Ready' : 'Ready'}
	</button>
</div>
{/if}

<style>
	.lobby-screen {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		max-width: 28rem;
		padding: 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.match-code {
		color: #888;
		font-size: 0.9rem;
	}

	.roster {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	.player {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.4rem 0.6rem;
		border-radius: 6px;
		background: rgba(255, 255, 255, 0.04);
	}

	.player.you {
		outline: 1px solid #d0392b;
	}

	.name {
		font-weight: 600;
	}

	.badge {
		font-size: 0.75rem;
		padding: 0.1rem 0.4rem;
		border-radius: 4px;
		background: #444;
		color: #ddd;
	}

	.badge.host {
		background: #a8730a;
		color: white;
	}

	.badge.bot {
		background: #3e6e9e;
		color: white;
	}

	.badge.you-badge {
		background: #2c5f9e;
		color: white;
	}

	.badge.ready-state {
		margin-left: auto;
	}

	.badge.ready-state.ready {
		background: #2f8f4e;
		color: white;
	}

	.empty {
		color: #888;
		font-style: italic;
	}

	.ready-toggle {
		align-self: flex-start;
		padding: 0.5rem 1.25rem;
		border-radius: 6px;
		border: none;
		background: #2f8f4e;
		color: white;
		font-weight: 600;
		cursor: pointer;
	}

	.ready-toggle.ready {
		background: #999;
	}
</style>
