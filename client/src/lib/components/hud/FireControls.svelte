<script lang="ts">
	// Angle/power sliders + Fire button (PLAN.md section 3.1/3.2 HUD:
	// AngleDial, PowerBar). M1 has no weapon-select UI — weaponId is
	// hardcoded to basic_shell in fireInput.ts.
	//
	// M2: the server enforces active-player-only firing (protocol.md
	// section 4, Fire "only accepted from the player whose turn it currently
	// is"), but the client should still reflect that in the UI rather than
	// let the local player click Fire and get silently rejected — so Fire is
	// disabled whenever it isn't the local player's turn, in addition to the
	// existing optimistic-disable while a shot is in flight. The angle/power
	// sliders themselves stay enabled regardless of turn (per user feedback:
	// you should be able to play with your aim even when it's not your
	// shot) — only the Fire button and weapon select are turn-gated.

	import { sendFire } from '../../game/input/fireInput';
	import { sendAimUpdate } from '../../game/input/aimInput';
	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import { aimStore } from '../../stores/aimStore';
	import WeaponSelect from './WeaponSelect.svelte';

	$: isMyTurn =
		$matchStore.activePlayerId !== null && $matchStore.activePlayerId === $sessionStore.playerId;
	$: disabled = !isMyTurn || $matchStore.awaitingShotResolution;

	// Broadcasts the local player's live aim angle so every connected client's
	// tankRenderer can show this tank's barrel tracking it, not just the
	// local view — throttled inside sendAimUpdate. Not turn-gated, matching
	// the sliders themselves staying enabled outside your turn.
	$: sendAimUpdate($aimStore.angleDeg);

	function fire(): void {
		if (disabled) return;
		sendFire($aimStore.angleDeg, $aimStore.power);
	}
</script>

<WeaponSelect disabled={disabled} />

<div class="fire-controls">
	<label class="control">
		<span>Angle: {$aimStore.angleDeg}&deg;</span>
		<input
			class="angle-slider"
			type="range"
			min="0"
			max="180"
			step="1"
			bind:value={$aimStore.angleDeg}
		/>
	</label>

	<label class="control">
		<span>Power: {$aimStore.power}</span>
		<input type="range" min="0" max="100" step="1" bind:value={$aimStore.power} />
	</label>

	<button class="fire-button" on:click={fire} disabled={disabled}>
		{$matchStore.awaitingShotResolution ? 'Firing...' : isMyTurn ? 'Fire' : 'Not your turn'}
	</button>
</div>

<style>
	.fire-controls {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		padding: 0.75rem 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.control {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		font-size: 0.85rem;
	}

	/* Angle 0deg points screen-right, 90deg points up (tankRenderer.ts convention),
	   so a plain left-to-right slider drags the thumb *away* from the direction the
	   barrel tip visibly sweeps. rtl flips the track so dragging the thumb right
	   sweeps the barrel right too, per user feedback that drag direction and barrel
	   sweep direction must match. */
	.angle-slider {
		direction: rtl;
	}

	.fire-button {
		margin-left: auto;
		padding: 0.5rem 1.25rem;
		font-size: 1rem;
		font-weight: 600;
		border-radius: 6px;
		border: none;
		background: #d0392b;
		color: white;
		cursor: pointer;
	}

	.fire-button:disabled {
		background: #999;
		cursor: default;
	}
</style>
