<script lang="ts">
	// PLAN.md section 3.1 PostMatchScreen: final standings from MatchEnded
	// (protocol.md section 4), sorted by cash descending per the protocol doc.

	import { matchStore } from '../stores/matchStore';
	import { lobbyStore } from '../stores/lobbyStore';
	import { sessionStore } from '../stores/sessionStore';

	function playerName(playerId: string): string {
		return $matchStore.players.find((p) => p.playerId === playerId)?.displayName ?? playerId;
	}

	// Both stores' reset() already existed for exactly this purpose but were
	// never wired to anything real (only used in tests) -- this is the only
	// caller. Client-side only: the match is already over server-side by the
	// time this screen shows, so there's no in-progress session to tell the
	// server we're leaving.
	function backToStart(): void {
		matchStore.reset();
		lobbyStore.reset();
	}
</script>

<div class="post-match-screen">
	<h2>Match Complete</h2>

	{#if $matchStore.matchEndedInfo}
		<ol class="standings">
			{#each $matchStore.matchEndedInfo.finalStandings as standing, i (standing.playerId)}
				<li class:you={standing.playerId === $sessionStore.playerId}>
					<span class="rank">#{i + 1}</span>
					<span class="name">{playerName(standing.playerId)}</span>
					<span class="cash">${standing.cash}</span>
					<span class="stat">{standing.damageDealt} dmg</span>
					<span class="stat">{standing.kills} kills</span>
				</li>
			{/each}
		</ol>
	{:else}
		<p>No standings available.</p>
	{/if}

	<button class="back-to-start" on:click={backToStart}>Back to Start</button>
</div>

<style>
	.post-match-screen {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		max-width: 28rem;
		padding: 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.standings {
		list-style: none;
		margin: 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	.standings li {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.4rem 0.6rem;
		border-radius: 6px;
		background: rgba(255, 255, 255, 0.04);
	}

	.standings li.you {
		outline: 1px solid #d0392b;
	}

	.rank {
		font-weight: 700;
		color: #888;
		width: 2rem;
	}

	.name {
		font-weight: 600;
		flex: 1;
	}

	.cash {
		color: #2f8f4e;
		font-weight: 600;
	}

	.stat {
		color: #888;
		font-size: 0.85rem;
	}

	.back-to-start {
		align-self: center;
		padding: 0.6rem 1.4rem;
		border: 1px solid #666;
		border-radius: 8px;
		background: #1a1a1a;
		color: #eee;
		font-size: 1rem;
		font-weight: 600;
		cursor: pointer;
	}

	.back-to-start:hover {
		background: #262626;
		border-color: #888;
	}
</style>
