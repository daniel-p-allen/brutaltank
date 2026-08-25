<script lang="ts">
	// PLAN.md section 3.1: "create/join match form, display name entry".
	// Sends CreateMatch or JoinMatch; the corresponding MatchCreated/
	// MatchJoined reply is picked up by sessionStore, and the LobbyUpdate
	// that follows populates lobbyStore — App.svelte's routing then switches
	// to LobbyScreen once matchStore.status becomes 'WAITING'.

	import { sendCreateMatch, sendJoinMatch } from '../net/lobbyActions';

	let displayName = '';
	let joinMatchId = '';
	let submitting = false;

	// Bots (per user request, 2026-08-25): chosen once at match creation,
	// alongside the existing Create Match flow -- no separate screen/step.
	// 0 keeps the human-only flow byte-identical (matchConfig passed as
	// null, same as before bots existed).
	let botCount = 0;
	let botDifficulty: 'EASY' | 'MEDIUM' | 'HARD' | 'MIXED' = 'MIXED';

	function createMatch(): void {
		const name = displayName.trim();
		if (!name || submitting) return;
		submitting = true;
		sendCreateMatch(name, botCount > 0 ? { botCount, botDifficulty } : null);
	}

	function joinMatch(): void {
		const name = displayName.trim();
		const matchId = joinMatchId.trim();
		if (!name || !matchId || submitting) return;
		submitting = true;
		sendJoinMatch(matchId, name);
	}
</script>

<div class="menu-screen">
	<h2>Play</h2>

	<label class="field">
		<span>Display name</span>
		<input type="text" bind:value={displayName} maxlength="24" placeholder="Your name" />
	</label>

	<div class="bot-config">
		<label class="field">
			<span>Bots</span>
			<select bind:value={botCount}>
				{#each [0, 1, 2, 3, 4, 5, 6] as n}
					<option value={n}>{n === 0 ? 'None' : n}</option>
				{/each}
			</select>
		</label>
		{#if botCount > 0}
			<label class="field">
				<span>Bot difficulty</span>
				<select bind:value={botDifficulty}>
					<option value="MIXED">Mixed</option>
					<option value="EASY">Easy</option>
					<option value="MEDIUM">Medium</option>
					<option value="HARD">Hard</option>
				</select>
			</label>
		{/if}
	</div>

	<div class="actions">
		<button class="primary" on:click={createMatch} disabled={!displayName.trim() || submitting}>
			Create Match
		</button>

		<div class="join">
			<input
				type="text"
				bind:value={joinMatchId}
				maxlength="16"
				placeholder="Match / join code"
				on:keydown={(e) => e.key === 'Enter' && joinMatch()}
			/>
			<button
				on:click={joinMatch}
				disabled={!displayName.trim() || !joinMatchId.trim() || submitting}
			>
				Join Match
			</button>
		</div>
	</div>
</div>

<style>
	.menu-screen {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		max-width: 24rem;
		padding: 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.field {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		font-size: 0.85rem;
	}

	input,
	select {
		padding: 0.5rem;
		border-radius: 4px;
		border: 1px solid #666;
		background: transparent;
		color: inherit;
		font: inherit;
	}

	.bot-config {
		display: flex;
		gap: 0.75rem;
	}

	.bot-config .field {
		flex: 1;
	}

	.actions {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.join {
		display: flex;
		gap: 0.5rem;
	}

	.join input {
		flex: 1;
	}

	button {
		padding: 0.5rem 1rem;
		border-radius: 6px;
		border: 1px solid #666;
		background: transparent;
		color: inherit;
		cursor: pointer;
		font: inherit;
	}

	button:disabled {
		opacity: 0.5;
		cursor: default;
	}

	button.primary {
		background: #d0392b;
		border-color: #d0392b;
		color: white;
		font-weight: 600;
	}
</style>
