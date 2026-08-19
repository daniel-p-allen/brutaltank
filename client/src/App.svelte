<script lang="ts">
	// Top-level screen routing (PLAN.md section 3.1): Menu -> Lobby -> Match ->
	// PostMatch. Derived from matchStore's status (set by LobbyUpdate/
	// MatchStarted/MatchStateSync/MatchEnded), not local UI state, so a
	// successful Rejoin (sessionStore, on app startup) drops the player back
	// into the right screen automatically.

	import ConnectionStatus from './lib/components/common/ConnectionStatus.svelte';
	import MenuScreen from './lib/components/MenuScreen.svelte';
	import LobbyScreen from './lib/components/LobbyScreen.svelte';
	import MatchScreen from './lib/components/MatchScreen.svelte';
	import PostMatchScreen from './lib/components/PostMatchScreen.svelte';
	import { connectionStore } from './lib/stores/connectionStore';
	import { matchStore } from './lib/stores/matchStore';

	type Screen = 'menu' | 'lobby' | 'match' | 'post-match';

	$: screen = ((): Screen => {
		if ($matchStore.matchEndedInfo || $matchStore.status === 'COMPLETE') return 'post-match';
		if ($matchStore.status === 'IN_PROGRESS') return 'match';
		if ($matchStore.status === 'WAITING') return 'lobby';
		return 'menu';
	})();
</script>

<main>
	<h1>BrutalTank</h1>
	<p class="subtitle">M2 — full lobby &amp; turn-based flow</p>
	<ConnectionStatus />

	{#if $connectionStore.status === 'open'}
		{#if screen === 'menu'}
			<MenuScreen />
		{:else if screen === 'lobby'}
			<LobbyScreen />
		{:else if screen === 'match'}
			<MatchScreen />
		{:else}
			<PostMatchScreen />
		{/if}
	{/if}
</main>

<style>
	main {
		max-width: 64rem;
		margin: 3rem auto;
		padding: 0 1rem;
		font-family: system-ui, sans-serif;
	}

	h1 {
		margin-bottom: 0;
	}

	.subtitle {
		color: #888;
		margin-top: 0.25rem;
	}
</style>
