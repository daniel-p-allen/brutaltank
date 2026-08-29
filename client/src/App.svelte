<script lang="ts">
	// Top-level screen routing (PLAN.md section 3.1): Menu -> Lobby -> Match ->
	// PostMatch. Derived from matchStore's status (set by LobbyUpdate/
	// MatchStarted/MatchStateSync/MatchEnded), not local UI state, so a
	// successful Rejoin (sessionStore, on app startup) drops the player back
	// into the right screen automatically.

	import { onDestroy } from 'svelte';
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

	// Per user feedback: replace the "Your turn" popup card with the heading
	// itself flipping to say so — same tank-colored heading already used as
	// a "this one's you" indicator, just repurposed as the turn indicator too.
	// The countdown lives here too ("Your Turn - 40 seconds"), so it needs its
	// own tick, same pattern as MatchScreen's remainingTurnSec.
	let now = Date.now();
	const headingTickInterval = setInterval(() => (now = Date.now()), 250);
	onDestroy(() => clearInterval(headingTickInterval));

	$: isYourTurn =
		$matchStore.status === 'IN_PROGRESS' &&
		$matchStore.activePlayerId !== null &&
		$matchStore.activePlayerId === $sessionStore.playerId;

	$: remainingTurnSec = (() => {
		const { turnStartedAtMs, turnTimeoutSec } = $matchStore;
		if (turnStartedAtMs === null || turnTimeoutSec === null) return null;
		const elapsed = (now - turnStartedAtMs) / 1000;
		return Math.max(0, Math.ceil(turnTimeoutSec - elapsed));
	})();

	$: headingText = isYourTurn
		? remainingTurnSec !== null
			? `Your Turn - ${remainingTurnSec} seconds`
			: 'Your Turn'
		: 'BrutalTank';

	// "Restart Session" (2026-08-25 user request): a break-glass recovery
	// control, always reachable regardless of which screen is stuck --
	// unlike PostMatchScreen's "Back to Start" (sendPlayAgain, a graceful
	// server-side reset that only works once a match has actually
	// COMPLETEd, and depends on the server cooperating), this is a pure
	// client-side teardown that works even when something is genuinely
	// broken: clears the persisted session (sessionStore's sessionStorage
	// entry, so the next load doesn't attempt a Rejoin into whatever state
	// broke) and reloads the page, landing cleanly on MenuScreen's
	// enter-your-name screen every time.
	function restartSession(): void {
		sessionStore.clear();
		window.location.reload();
	}

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
	<!-- TEMPORARY (2026-08-29): the game server's hosting VM is being retired
	     in favor of a dedicated one; this client can't reach a game server
	     until that's done. Remove once the new server is live -- see
	     README.md / CLAUDE.md for status. -->
	<div class="maintenance-banner">
		Live server is temporarily down while we move to new hosting. Please
		check back soon.
	</div>
	<div class="game-frame" style="border-color: {localPlayerColor ?? '#444'}">
		<h1 style={localPlayerColor ? `color: ${localPlayerColor}` : ''}>{headingText}</h1>
		<div class="status-row">
			<ConnectionStatus />
			<button type="button" class="restart-session" on:click={restartSession}>Restart Session</button>
		</div>

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
	</div>
</main>

<style>
	main {
		max-width: 64rem;
		margin: 3rem auto;
		padding: 0 1rem;
		font-family: system-ui, sans-serif;
	}

	/* Per user request: the whole game/UI sits in one rounded container,
	   border colored to the local player's own tank color (same source as
	   the heading), interior unchanged (black background). 10pt padding on
	   every edge, including above the heading — and everything inside
	   (heading, match screen) centers on the container's own width rather
	   than each stretching to a different width, which is what previously
	   made the game canvas look narrower than the HUD below it. */
	.game-frame {
		display: flex;
		flex-direction: column;
		align-items: center;
		width: fit-content;
		max-width: 100%;
		margin: 0 auto;
		padding: 10px;
		border: 2px solid #444;
		border-radius: 16px;
		background: #000;
	}

	h1 {
		margin: 0 0 1.5rem 0;
	}

	.status-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		width: 100%;
		margin-bottom: 1rem;
	}

	/* Deliberately subtle -- a break-glass recovery control, not something
	   that should draw attention during normal play. */
	.restart-session {
		padding: 0.2rem 0.6rem;
		border: 1px solid #444;
		border-radius: 6px;
		background: transparent;
		color: #777;
		font-size: 0.75rem;
		cursor: pointer;
	}

	.restart-session:hover {
		color: #ccc;
		border-color: #777;
	}

	.maintenance-banner {
		max-width: 64rem;
		margin: 0 auto 1rem auto;
		padding: 0.6rem 1rem;
		border: 1px solid #7a5a00;
		border-radius: 8px;
		background: #3a2c00;
		color: #ffcc66;
		font-size: 0.9rem;
		text-align: center;
	}
</style>
