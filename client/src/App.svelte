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
	import { lobbyStore } from './lib/stores/lobbyStore';
	import { sessionStore } from './lib/stores/sessionStore';

	type Screen = 'menu' | 'lobby' | 'match' | 'post-match';

	// Titles the page in the local player's own tank color once they're in a
	// match, so it doubles as a "this one's you" indicator — falls back to
	// the default heading color (unset, inherits from CSS) before joining,
	// since there's no player color to show yet.
	$: localPlayerColor =
		$matchStore.players.find((p) => p.playerId === $sessionStore.playerId)?.color ?? null;

	// The server never actually sends a MatchStateSync (the only message that
	// sets matchStore.status) while a match is WAITING — that status only
	// becomes known client-side via LobbyUpdate, which populates lobbyStore
	// instead. So the lobby phase must be detected from lobbyStore.matchId,
	// not matchStore.status === 'WAITING' (which never actually happens).
	$: screen = ((): Screen => {
		if ($matchStore.matchEndedInfo || $matchStore.status === 'COMPLETE') return 'post-match';
		// SHOP (M4) is part of the match screen (MatchScreen swaps in
		// ShopOverlay for FireControls during it) — without this, the shop
		// phase fell through to the `lobbyStore.matchId` check below and
		// incorrectly routed back to LobbyScreen, since lobbyStore.matchId is
		// never cleared once a match starts (LobbyUpdate simply stops
		// arriving; nothing resets it) so it's still truthy at that point.
		if ($matchStore.status === 'IN_PROGRESS' || $matchStore.status === 'SHOP') return 'match';
		if ($lobbyStore.matchId) return 'lobby';
		return 'menu';
	})();
</script>

<main>
	<h1 style={localPlayerColor ? `color: ${localPlayerColor}` : ''}>BrutalTank</h1>
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
</style>
