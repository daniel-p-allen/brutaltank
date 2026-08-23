<script lang="ts">
	// One buyable row in ShopOverlay. Reused for both weapons and shields
	// (PLAN.md 3.1 lists separate WeaponCard/ShieldCard, but the two would be
	// identical except for a label — kept as one component instead of
	// duplicating the same markup/logic twice).

	import type { PriceListEntry } from '../../protocol/types';
	import { sendShopPurchase } from '../../game/input/shopInput';

	export let entry: PriceListEntry;
	export let label: string;
	export let cash: number;
	// Two-word "what it does" blurb, shields only (ShopOverlay's
	// SHIELD_BLURB_BY_ID) -- weapons pass nothing.
	export let description: string | undefined = undefined;

	let quantity = 1;

	$: outOfStock = entry.stock <= 0;
	$: clampedQuantity = Math.max(1, Math.min(quantity, Math.max(1, entry.stock)));
	$: totalCost = entry.price * clampedQuantity;
	$: canBuy = !outOfStock && clampedQuantity <= entry.stock && cash >= totalCost;

	function buy(): void {
		if (!canBuy) return;
		sendShopPurchase(entry.itemId, entry.itemType, clampedQuantity);
	}
</script>

<div class="card" class:shield={entry.itemType === 'SHIELD'} class:out-of-stock={outOfStock}>
	<div class="header">
		<span class="label">{label}</span>
		<span class="price">${entry.price}</span>
	</div>
	{#if description}
		<div class="blurb">{description}</div>
	{/if}
	<div class="stock">{outOfStock ? 'Out of stock' : `${entry.stock} left`}</div>
	<div class="controls">
		<input
			type="number"
			min="1"
			max={Math.max(1, entry.stock)}
			bind:value={quantity}
			disabled={outOfStock}
		/>
		<button type="button" on:click={buy} disabled={!canBuy}>Buy (${totalCost})</button>
	</div>
</div>

<style>
	.card {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
		padding: 0.5rem 0.6rem;
		border-radius: 6px;
		border: 1px solid #555;
		background: #222;
		font-family: system-ui, sans-serif;
		font-size: 0.85rem;
		min-width: 9rem;
	}

	/* Violet accent (unused elsewhere in this UI) makes shields read as a
	   distinct category at a glance rather than blending into the weapon
	   list with just a border-style difference -- per user feedback,
	   2026-08-24 ("make them a bit more obvious"), design approved via the
	   shop mockup example first. */
	.card.shield {
		border-color: #a06fe0;
		background: rgba(160, 111, 224, 0.1);
	}

	.card.shield .controls button:not(:disabled) {
		background: #a06fe0;
	}

	.card.out-of-stock {
		opacity: 0.5;
	}

	.blurb {
		font-size: 0.78rem;
		font-weight: 600;
		color: #a06fe0;
	}

	.header {
		display: flex;
		justify-content: space-between;
		gap: 0.5rem;
		font-weight: 600;
		color: #eee;
	}

	.price {
		color: #9fd68a;
	}

	.stock {
		font-family: monospace;
		font-size: 0.75rem;
		color: #aaa;
	}

	.controls {
		display: flex;
		gap: 0.35rem;
	}

	.controls input {
		width: 3.5rem;
		background: #111;
		color: #eee;
		border: 1px solid #555;
		border-radius: 4px;
		padding: 0.15rem 0.3rem;
	}

	.controls button {
		flex: 1;
		padding: 0.25rem 0.5rem;
		border-radius: 4px;
		border: none;
		background: #d0392b;
		color: white;
		cursor: pointer;
		font-size: 0.8rem;
	}

	.controls button:disabled {
		background: #999;
		cursor: default;
	}
</style>
