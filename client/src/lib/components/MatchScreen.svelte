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
	import { playRoundWin } from '../audio/soundManager';

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

	// Players list sorted highest-cash-first (per user request, 2026-08-23:
	// "a really good visual way to show our running totals") so the current
	// leader is always immediately visible at a glance rather than reading
	// left-to-right in fixed turn order.
	$: rankedPlayers = [...$matchStore.players].sort((a, b) => b.cash - a.cash);
	$: maxCash = Math.max(1, ...$matchStore.players.map((p) => p.cash));

	function playerColor(playerId: string | null): string {
		if (!playerId) return '#fff';
		return $matchStore.players.find((p) => p.playerId === playerId)?.color ?? '#fff';
	}

	// WINNER flash + fanfare (per user request, 2026-08-23: "WINNNER!! FLASHES
	// FOR 5 SECONDS WITH A LITTLE BIT OF MUSIC") — fires once per RoundEnded,
	// on the null -> non-null transition, not on every reactive re-render
	// (roundEndedInfo stays non-null for the whole shop/next-round window, far
	// longer than the 5s flash should last).
	let winnerFlashing = false;
	let flashTimer: ReturnType<typeof setTimeout> | null = null;

	// Round-end splash (per user request, 2026-08-24: "once the round ends
	// we need a round ended screen, even if it is a splash screen for two
	// seconds") -- the shop opens immediately server-side the instant a round
	// ends (Match.java's openShop() runs right after RoundEnded), so without
	// this the shop UI just appears with zero transition. Purely a client-
	// side visual gate: for splashShowing's 2s window, this covers the
	// screen so the round result actually registers before the shop
	// underneath becomes visible/interactive, rather than changing when the
	// server actually opens the shop.
	let splashShowing = false;
	let splashTimer: ReturnType<typeof setTimeout> | null = null;

	let lastRoundEndedInfo: typeof $matchStore.roundEndedInfo = null;
	$: {
		const info = $matchStore.roundEndedInfo;
		if (info !== null && lastRoundEndedInfo === null) {
			splashShowing = true;
			if (splashTimer !== null) clearTimeout(splashTimer);
			splashTimer = setTimeout(() => (splashShowing = false), 2000);

			if (info.winnerPlayerId) {
				winnerFlashing = true;
				playRoundWin();
				if (flashTimer !== null) clearTimeout(flashTimer);
				flashTimer = setTimeout(() => (winnerFlashing = false), 5000);
			}
		}
		lastRoundEndedInfo = info;
	}
	onDestroy(() => {
		if (flashTimer !== null) clearTimeout(flashTimer);
		if (splashTimer !== null) clearTimeout(splashTimer);
	});
</script>

<div class="match-screen">
	{#if splashShowing && $matchStore.roundEndedInfo}
		<div class="round-end-splash">
			{#if $matchStore.roundEndedInfo.winnerPlayerId}
				<h2 style="--winner-color: {playerColor($matchStore.roundEndedInfo.winnerPlayerId)}">
					Round Over &mdash; {playerName($matchStore.roundEndedInfo.winnerPlayerId)} wins!
				</h2>
			{:else}
				<h2>Round Over &mdash; Draw</h2>
			{/if}
		</div>
	{/if}

	<GameCanvas />

	{#if $matchStore.matchId === null}
		<p class="waiting">Waiting for match state from server...</p>
	{:else}
		{#if $matchStore.activePlayerId && !activePlayerIsYou}
			<div class="turn-banner">
				<span class="turn-label">{playerName($matchStore.activePlayerId)}'s turn</span>
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
			{#each rankedPlayers as player, rank (player.playerId)}
				<div
					class="player-card"
					class:leader={rank === 0 && player.cash > 0}
					style="--player-color: {player.color}"
				>
					<div class="player-card-header">
						<span class="player-name">{player.displayName}</span>
						{#if $matchStore.disconnectedPlayerIds.includes(player.playerId)}
							<span class="disconnected-badge">disconnected</span>
						{/if}
					</div>
					<div class="player-stat-row">
						<span class="stat-label">HP</span>
						<span class="stat-value">{player.tank.health}{player.tank.alive ? '' : ' (destroyed)'}</span>
					</div>
					<div class="player-stat-row">
						<span class="stat-label">Cash</span>
						<span class="stat-value">${player.cash}</span>
					</div>
					<!-- Running-totals bar: width relative to the current match leader's
					     cash, so relative standing is visible at a glance without reading
					     every number (per user request, 2026-08-23). -->
					<div class="cash-bar-track">
						<div class="cash-bar-fill" style="width: {(player.cash / maxCash) * 100}%" />
					</div>
					<div class="player-stat-row">
						<span class="stat-label">Help</span>
						<span class="stat-value trajectory-help-badge" class:on={$matchStore.remoteTrajectoryHelp[player.playerId]}>
							{$matchStore.remoteTrajectoryHelp[player.playerId] ? 'on' : 'off'}
						</span>
					</div>
				</div>
			{/each}
		</div>

		{#if $matchStore.roundEndedInfo}
			<div class="round-end-overlay">
				{#if $matchStore.roundEndedInfo.winnerPlayerId}
					<h2
						class="winner-banner"
						class:flashing={winnerFlashing}
						style="--winner-color: {playerColor($matchStore.roundEndedInfo.winnerPlayerId)}"
					>
						WINNER!! {playerName($matchStore.roundEndedInfo.winnerPlayerId)}
					</h2>
				{:else}
					<h3>Draw (safety cap reached)</h3>
				{/if}
				<ul>
					{#each $matchStore.roundEndedInfo.standings as standing (standing.playerId)}
						<li>{playerName(standing.playerId)}: ${standing.cash}</li>
					{/each}
				</ul>
				<p class="next-round-note">Next round starting...</p>
			</div>
		{/if}

		<p class="weight-legend">★ weight class — heavier (more stars) falls faster and travels less far per power</p>
	{/if}
</div>

<style>
	.match-screen {
		position: relative;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		/* Anchored to GameCanvas's default rendered width (960px) so every
		   card below the canvas (turn banner, fire controls/weapon-select,
		   players list) stretches to match it exactly, rather than the
		   container sizing itself off whichever child happens to be widest —
		   WeaponSelect's chip row wraps visually but its *unwrapped*
		   max-content width is much wider than the canvas, which was
		   silently winning that sizing contest and stretching everything
		   past the canvas's edge. */
		width: 960px;
		max-width: 100%;
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
		flex-wrap: wrap;
		gap: 0.6rem;
		font-family: system-ui, sans-serif;
	}

	/* One consolidated card per player (name/HP/cash/help together, per user
	   request 2026-08-23 — previously spread across separate inline spans) —
	   all-same-color per player so at a glance every stat is legible as
	   belonging to one player, via --player-color driving both the border
	   and every accent inside. */
	.player-card {
		--player-color: #fff;
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
		min-width: 9.5rem;
		padding: 0.5rem 0.65rem;
		border-radius: 8px;
		border: 1.5px solid var(--player-color);
		background: color-mix(in srgb, var(--player-color) 10%, transparent);
	}

	.player-card.leader {
		box-shadow: 0 0 0 1px var(--player-color), 0 0 10px 1px color-mix(in srgb, var(--player-color) 60%, transparent);
	}

	.player-card-header {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 0.4rem;
	}

	.player-name {
		font-weight: 700;
		color: var(--player-color);
	}

	.player-stat-row {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-family: monospace;
		font-size: 0.85rem;
	}

	.stat-label {
		color: #888;
	}

	.stat-value {
		color: var(--player-color);
	}

	.disconnected-badge {
		font-size: 0.65rem;
		color: #e0a020;
	}

	/* Running-totals bar: fill width is set inline per-player (relative to
	   the match leader's cash), per user request 2026-08-23. */
	.cash-bar-track {
		height: 4px;
		border-radius: 2px;
		background: rgba(255, 255, 255, 0.08);
		overflow: hidden;
	}

	.cash-bar-fill {
		height: 100%;
		background: var(--player-color);
		border-radius: 2px;
		transition: width 0.3s ease-out;
	}

	.trajectory-help-badge {
		color: #666;
	}

	.trajectory-help-badge.on {
		color: #7fd9c4;
	}

	/* 2s full-cover splash on round end (per user request, 2026-08-24) --
	   the shop already renders underneath by the time this shows (the
	   server opens it immediately), this just visually gates it for a
	   beat so the round result registers before the shop grabs attention. */
	.round-end-splash {
		position: absolute;
		inset: 0;
		z-index: 10;
		display: flex;
		align-items: center;
		justify-content: center;
		background: rgba(0, 0, 0, 0.88);
		border-radius: 8px;
		animation: splash-fade 2s ease-in-out forwards;
	}

	.round-end-splash h2 {
		margin: 0;
		padding: 0 1.5rem;
		text-align: center;
		font-family: system-ui, sans-serif;
		font-size: 1.8rem;
		letter-spacing: 0.03em;
		color: var(--winner-color, #fff);
		text-shadow: 0 0 10px color-mix(in srgb, var(--winner-color, #fff) 60%, transparent);
	}

	@keyframes splash-fade {
		0%,
		75% {
			opacity: 1;
		}
		100% {
			opacity: 0;
		}
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

	/* WINNER!! flash + fanfare (per user request, 2026-08-23) — pulses for
	   5 seconds (JS removes the .flashing class via setTimeout), then settles
	   to a plain static heading so the round-end card doesn't keep animating
	   for the whole shop/next-round window. */
	.winner-banner {
		margin: 0 0 0.4rem;
		text-align: center;
		font-size: 1.6rem;
		letter-spacing: 0.04em;
		color: var(--winner-color, #fff);
		text-shadow: 0 0 8px color-mix(in srgb, var(--winner-color, #fff) 70%, transparent);
	}

	.winner-banner.flashing {
		animation: winner-pulse 0.5s ease-in-out infinite;
	}

	@keyframes winner-pulse {
		0%,
		100% {
			opacity: 1;
			transform: scale(1);
		}
		50% {
			opacity: 0.55;
			transform: scale(1.06);
		}
	}

	.next-round-note {
		color: #888;
		font-size: 0.85rem;
		margin-bottom: 0;
	}

	.weight-legend {
		margin: 0;
		font-family: system-ui, sans-serif;
		font-size: 0.7rem;
		color: #777;
	}
</style>
