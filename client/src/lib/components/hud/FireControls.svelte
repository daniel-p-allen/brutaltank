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
	// existing optimistic-disable while a shot is in flight.

	import { sendFire } from '../../game/input/fireInput';
	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import WeaponSelect from './WeaponSelect.svelte';

	let angleDeg = 45;
	let power = 50;

	$: isMyTurn =
		$matchStore.activePlayerId !== null && $matchStore.activePlayerId === $sessionStore.playerId;
	$: disabled = !isMyTurn || $matchStore.awaitingShotResolution;

	function fire(): void {
		if (disabled) return;
		sendFire(angleDeg, power);
	}
</script>

<WeaponSelect disabled={disabled} />

<div class="fire-controls">
	<label class="control">
		<span>Angle: {angleDeg}&deg;</span>
		<input type="range" min="0" max="90" step="1" bind:value={angleDeg} disabled={disabled} />
	</label>

	<label class="control">
		<span>Power: {power}</span>
		<input type="range" min="0" max="100" step="1" bind:value={power} disabled={disabled} />
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
