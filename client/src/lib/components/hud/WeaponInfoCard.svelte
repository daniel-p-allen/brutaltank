<script lang="ts">
	// Shared hover/info-card body for a weapon or shield (2026-08-25 user
	// request: "those hover info cards for weapons need to be available in
	// the shop as well" -- factored out of WeaponSelect.svelte, which
	// originally had this markup inline, so the hotbar and the shop render
	// an identical card rather than two hand-kept-in-sync copies.

	import type { WeaponCatalogEntry } from '../../stores/weaponSelectStore';
	import { damagePieGradient, SHIELD_EFFECT_BLURB, formatQuantity } from '../../stores/weaponSelectStore';

	export let entry: WeaponCatalogEntry;
	/** Player's current owned quantity. Omit to hide the row (e.g. context where it isn't known). */
	export let owned: number | undefined = undefined;
	/** Live shop stock remaining. Omit/null to hide the row -- callers that already show stock inline (the shop card) should omit this to avoid showing it twice. */
	export let stockRemaining: number | null = null;
	/** The shop card already shows price inline; the hotbar's chip doesn't, so it wants this row too. */
	export let showPrice = true;
</script>

<div class="info-card">
	<div class="info-title">{entry.label}</div>
	{#if entry.description}
		<div class="info-desc">{entry.description}</div>
	{:else if entry.isShield && SHIELD_EFFECT_BLURB[entry.id]}
		<div class="info-desc">{SHIELD_EFFECT_BLURB[entry.id]}</div>
	{/if}
	<div class="info-stats">
		{#if entry.centerDamage}
			<div class="info-row">
				<span class="info-label">Damage</span>
				<span class="info-value">
					<span class="dmg-pie small" style="background: {damagePieGradient(entry.centerDamage)}"></span>
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
		{#if showPrice && entry.price !== undefined}
			<div class="info-row">
				<span class="info-label">Price</span>
				<span class="info-value">{entry.price === 0 ? 'Free' : `$${entry.price}`}</span>
			</div>
		{/if}
		{#if owned !== undefined}
			<div class="info-row"><span class="info-label">Owned</span><span class="info-value">{formatQuantity(owned)}</span></div>
		{/if}
		{#if stockRemaining !== null}
			<div class="info-row"><span class="info-label">Shop stock</span><span class="info-value">{stockRemaining}</span></div>
		{/if}
	</div>
</div>

<style>
	.info-card {
		position: absolute;
		bottom: calc(100% + 8px);
		left: 50%;
		transform: translateX(-50%);
		min-width: 11rem;
		max-width: 14rem;
		background: #17191f;
		border: 1px solid #555;
		border-radius: 6px;
		padding: 0.45rem 0.6rem;
		font-size: 0.7rem;
		color: #eee;
		box-shadow: 0 4px 14px rgba(0, 0, 0, 0.5);
		z-index: 20;
		pointer-events: none;
		font-family: system-ui, sans-serif;
	}

	.info-title {
		font-weight: 600;
		white-space: nowrap;
	}

	.info-desc {
		color: #9aa4b5;
		font-style: italic;
		line-height: 1.25;
		margin: 0.15rem 0 0.35rem;
		padding-bottom: 0.3rem;
		border-bottom: 1px solid #2c3341;
	}

	.info-stats {
		display: flex;
		flex-direction: column;
		gap: 0.02rem;
	}

	.info-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.75rem;
		line-height: 1.5;
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
		width: 10px;
		height: 10px;
		border-radius: 50%;
	}
</style>
