<script lang="ts">
	// PLAN.md section 3.1 PostMatchScreen: final standings from MatchEnded
	// (protocol.md section 4), sorted by cash descending per the protocol doc.

	import { matchStore } from '../stores/matchStore';
	import { sessionStore } from '../stores/sessionStore';
	import { sendPlayAgain } from '../net/lobbyActions';

	function playerName(playerId: string): string {
		return $matchStore.players.find((p) => p.playerId === playerId)?.displayName ?? playerId;
	}

	// Sends PlayAgain so the server resets this same match back to WAITING
	// with the same roster/session (Match.rematch()) instead of the old
	// client-only matchStore.reset()/lobbyStore.reset(), which stranded the
	// player at the menu with no lobby to rejoin, forcing a full re-login/
	// match-creation cycle every time (per live-playtest feedback,
	// 2026-08-24). matchStore's own LobbyUpdate handler clears the
	// COMPLETE/matchEndedInfo state once the server confirms the reset.
	function backToStart(): void {
		sendPlayAgain();
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
