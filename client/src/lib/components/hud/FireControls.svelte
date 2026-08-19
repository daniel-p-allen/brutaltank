<script lang="ts">
	// Angle/power sliders + Fire button (PLAN.md section 3.1/3.2 HUD:
	// AngleDial, PowerBar). M1 has no weapon-select UI — weaponId is
	// hardcoded to basic_shell in fireInput.ts.

	import { onDestroy } from 'svelte';
	import { wsClient } from '../../net/wsClient';
	import { parseEnvelope } from '../../protocol/envelope';
	import { sendFire } from '../../game/input/fireInput';

	let angleDeg = 45;
	let power = 50;
	let disabled = false;

	// Optimistic-disable per PLAN.md 3.2: disable on send, re-enable as soon
	// as the next ShotResolved (or FireRejected) arrives.
	const unsub = wsClient.onMessage((data) => {
		const envelope = parseEnvelope(data);
		if (!envelope) return;
		if (envelope.type === 'ShotResolved' || envelope.type === 'FireRejected') {
			disabled = false;
		}
	});

	onDestroy(unsub);

	function fire(): void {
		if (disabled) return;
		disabled = true;
		sendFire(angleDeg, power);
	}
</script>

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
		{disabled ? 'Firing...' : 'Fire'}
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
