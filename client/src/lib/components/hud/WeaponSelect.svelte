<script lang="ts">
	// M3 weapon-select HUD (PLAN.md 5 M3 checkpoint: "weapon-select HUD"):
	// a strip of labeled chips for all 10 weapons + 3 shields, showing the
	// local player's remaining quantity from matchStore's synced loadout.
	// Plain text chips are intentional — icons are an M5 art-pass item per
	// PLAN.md 3.3 ("still pre-art-pass").

	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import { weaponSelectStore, WEAPON_CATALOG, formatQuantity, hasAmmo } from '../../stores/weaponSelectStore';

	export let disabled = false;

	$: localPlayer = $matchStore.players.find((p) => p.playerId === $sessionStore.playerId);
	$: loadout = localPlayer?.loadout ?? {};
</script>

<div class="weapon-select">
	{#each WEAPON_CATALOG as entry (entry.id)}
		{@const qty = loadout[entry.id]}
		{@const selectable = hasAmmo(qty) && !disabled}
		<button
			type="button"
			class="chip"
			class:shield={entry.isShield}
			class:selected={$weaponSelectStore === entry.id}
			disabled={!selectable}
			on:click={() => weaponSelectStore.select(entry.id)}
		>
			<span class="label">{entry.label}</span>
			<span class="meta-row">
				<span class="qty">×{formatQuantity(qty)}</span>
				{#if entry.weightClass}
					<span class="weight">{'★'.repeat(entry.weightClass)}</span>
				{/if}
			</span>
		</button>
	{/each}
</div>

<style>
	.weapon-select {
		display: flex;
		flex-wrap: wrap;
		gap: 0.35rem;
		padding: 0.5rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.chip {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.2rem;
		min-width: 6rem;
		padding: 0.35rem 0.6rem;
		border-radius: 6px;
		border: 1px solid #555;
		background: #222;
		color: #eee;
		font-size: 0.8rem;
		cursor: pointer;
	}

	.label {
		white-space: nowrap;
	}

	.meta-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		width: 100%;
		gap: 0.5rem;
	}

	.chip.shield {
		border-style: dashed;
	}

	.chip.selected {
		border-color: #d0392b;
		background: rgba(208, 57, 43, 0.25);
	}

	.chip:disabled {
		opacity: 0.4;
		cursor: default;
	}

	.qty {
		font-family: monospace;
		font-size: 0.75rem;
		color: #aaa;
	}

	.chip.selected .qty {
		color: #fff;
	}

	.weight {
		font-size: 0.7rem;
		letter-spacing: 0.05em;
		color: #e0a020;
	}
</style>
