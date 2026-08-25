<script lang="ts">
	// M3 weapon-select HUD (PLAN.md 5 M3 checkpoint: "weapon-select HUD"):
	// a strip of labeled chips for all 10 weapons + 3 shields, showing the
	// local player's remaining quantity from matchStore's synced loadout.
	// Plain text chips are intentional — icons are an M5 art-pass item per
	// PLAN.md 3.3 ("still pre-art-pass").

	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import {
		weaponSelectStore,
		WEAPON_CATALOG,
		formatQuantity,
		hasAmmo,
		damagePieGradient,
		SHIELD_EFFECT_BLURB
	} from '../../stores/weaponSelectStore';

	export let disabled = false;

	$: localPlayer = $matchStore.players.find((p) => p.playerId === $sessionStore.playerId);
	$: loadout = localPlayer?.loadout ?? {};
	$: stockRemaining = $matchStore.shop?.stockRemaining ?? null;

	// Hover-card state (2026-08-25 user request: "I like the hover idea...
	// let's try it" -- a per-weapon detail popup, no new page/button needed).
	// Keyboard-selectable chips don't get a hover state on touch devices;
	// that's an acceptable gap for v1 same as the rest of this HUD, which is
	// mouse/keyboard-first (PLAN.md 3.3 is still pre-art-pass/pre-mobile).
	let hoveredId: string | null = null;
</script>

<div class="weapon-select">
	{#each WEAPON_CATALOG as entry (entry.id)}
		{@const qty = loadout[entry.id]}
		{@const selectable = hasAmmo(qty) && !disabled}
		<div
			class="chip-wrap"
			role="group"
			aria-label="{entry.label} details"
			on:mouseenter={() => (hoveredId = entry.id)}
			on:mouseleave={() => (hoveredId = null)}
		>
			<button
				type="button"
				class="chip"
				class:shield={entry.isShield}
				class:selected={$weaponSelectStore === entry.id}
				disabled={!selectable}
				on:click={() => weaponSelectStore.select(entry.id)}
				on:focus={() => (hoveredId = entry.id)}
				on:blur={() => (hoveredId = null)}
			>
				<span class="label">{entry.label}</span>
				<span class="meta-row">
					<span class="qty">×{formatQuantity(qty)}</span>
					{#if entry.weightClass}
						<span class="weight">{'★'.repeat(entry.weightClass)}</span>
					{/if}
				</span>
				{#if entry.centerDamage}
					<span class="dmg-pie" style="background: {damagePieGradient(entry.centerDamage)}" title="Damage rating"></span>
				{/if}
			</button>

			{#if hoveredId === entry.id}
				<div class="info-card">
					<div class="info-title">{entry.label}</div>
					{#if entry.centerDamage}
						<div class="info-row">
							<span class="info-label">Damage</span>
							<span class="info-value">
								<span
									class="dmg-pie small"
									style="background: {damagePieGradient(entry.centerDamage)}"
								></span>
								{entry.centerDamage}
							</span>
						</div>
					{/if}
					{#if entry.blastRadius}
						<div class="info-row"><span class="info-label">Blast radius</span><span class="info-value">{entry.blastRadius}</span></div>
					{/if}
					{#if entry.weightClass}
						<div class="info-row"><span class="info-label">Weight</span><span class="info-value weight">{'★'.repeat(entry.weightClass)}</span></div>
					{/if}
					{#if entry.isShield && SHIELD_EFFECT_BLURB[entry.id]}
						<div class="info-row"><span class="info-label">Effect</span><span class="info-value">{SHIELD_EFFECT_BLURB[entry.id]}</span></div>
					{/if}
					{#if entry.price !== undefined}
						<div class="info-row">
							<span class="info-label">Price</span>
							<span class="info-value">{entry.price === 0 ? 'Free' : `$${entry.price}`}</span>
						</div>
					{/if}
					<div class="info-row"><span class="info-label">Owned</span><span class="info-value">{formatQuantity(qty)}</span></div>
					{#if stockRemaining !== null}
						<div class="info-row">
							<span class="info-label">Shop stock</span>
							<span class="info-value">{entry.id in stockRemaining ? stockRemaining[entry.id] : '—'}</span>
						</div>
					{/if}
				</div>
			{/if}
		</div>
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

	.chip-wrap {
		position: relative;
	}

	.chip {
		position: relative;
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

	/* Damage rating (2026-08-25 user request): a small pie sampling a FIXED
	   color wheel -- 1 o'clock (~8.3%) = yellow, 6 o'clock (50%) = orange,
	   12 o'clock (100%, a full lap) = red -- revealed only up to the
	   weapon's own damage fraction (see weaponSelectStore.damagePieGradient).
	   A weak weapon's small wedge stays yellow; only a near-max weapon's
	   wedge sweeps far enough to reach red. */
	.dmg-pie {
		position: absolute;
		top: -6px;
		right: -6px;
		width: 16px;
		height: 16px;
		border-radius: 50%;
		border: 2px solid #191d24;
		box-shadow: 0 1px 3px rgba(0, 0, 0, 0.5);
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

	/* Hover info card (2026-08-25 user request). Positioned above the chip
	   so it never collides with the fire controls typically below the
	   hotbar; pointer-events: none so it can never itself become the hover
	   target and flicker the card open/closed. */
	.info-card {
		position: absolute;
		bottom: calc(100% + 8px);
		left: 50%;
		transform: translateX(-50%);
		min-width: 11rem;
		background: #17191f;
		border: 1px solid #555;
		border-radius: 6px;
		padding: 0.5rem 0.65rem;
		font-size: 0.72rem;
		color: #eee;
		box-shadow: 0 4px 14px rgba(0, 0, 0, 0.5);
		z-index: 20;
		pointer-events: none;
	}

	.info-title {
		font-weight: 600;
		margin-bottom: 0.3rem;
		white-space: nowrap;
	}

	.info-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		padding: 0.08rem 0;
	}

	.info-label {
		color: #999;
	}

	.info-value {
		display: flex;
		align-items: center;
		gap: 0.3rem;
		font-family: monospace;
	}

	.info-value.weight {
		font-family: inherit;
		color: #e0a020;
	}

	.dmg-pie.small {
		position: static;
		width: 10px;
		height: 10px;
		border: none;
		box-shadow: none;
	}
</style>
