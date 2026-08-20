<script lang="ts">
	// M4 between-round shop phase (PLAN.md 3.1's ShopOverlay). Shown whenever
	// matchStore.status === 'SHOP'; lists every purchasable weapon/shield
	// with its price and *live* shared stock (matchStore.shop.stockRemaining,
	// updated in real time by every player's ShopUpdate — see matchStore.ts).

	import { onDestroy } from 'svelte';
	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import { WEAPON_CATALOG } from '../../stores/weaponSelectStore';
	import ShopItemCard from './ShopItemCard.svelte';

	const LABEL_BY_ID = new Map(WEAPON_CATALOG.map((w) => [w.id, w.label]));

	let now = Date.now();
	const tickInterval = setInterval(() => (now = Date.now()), 250);
	onDestroy(() => clearInterval(tickInterval));

	$: shop = $matchStore.shop;
	$: localPlayer = $matchStore.players.find((p) => p.playerId === $sessionStore.playerId);
	$: remainingSec = shop ? Math.max(0, Math.ceil(shop.timeoutSec - (now - shop.openedAtMs) / 1000)) : 0;
	$: entries = shop
		? shop.priceList.map((e) => ({ ...e, stock: shop.stockRemaining[e.itemId] ?? e.stock }))
		: [];
	$: weapons = entries.filter((e) => e.itemType === 'WEAPON');
	$: shields = entries.filter((e) => e.itemType === 'SHIELD');
</script>

{#if shop}
	<div class="shop-overlay">
		<div class="shop-header">
			<h3>Shop</h3>
			<span class="timer" class:urgent={remainingSec <= 5}>{remainingSec}s</span>
			<span class="cash">${localPlayer?.cash ?? 0}</span>
		</div>

		{#if $matchStore.shopErrorReason}
			<div class="toast reject">{$matchStore.shopErrorReason}</div>
		{/if}

		<div class="section">
			<h4>Weapons</h4>
			<div class="grid">
				{#each weapons as entry (entry.itemId)}
					<ShopItemCard {entry} label={LABEL_BY_ID.get(entry.itemId) ?? entry.itemId} cash={localPlayer?.cash ?? 0} />
				{/each}
			</div>
		</div>

		<div class="section">
			<h4>Shields</h4>
			<div class="grid">
				{#each shields as entry (entry.itemId)}
					<ShopItemCard {entry} label={LABEL_BY_ID.get(entry.itemId) ?? entry.itemId} cash={localPlayer?.cash ?? 0} />
				{/each}
			</div>
		</div>
	</div>
{/if}

<style>
	.shop-overlay {
		display: flex;
		flex-direction: column;
		gap: 0.6rem;
		padding: 0.75rem 1rem;
		border-radius: 8px;
		background: rgba(0, 0, 0, 0.3);
		border: 1px solid #444;
		font-family: system-ui, sans-serif;
	}

	.shop-header {
		display: flex;
		align-items: baseline;
		gap: 0.75rem;
	}

	.shop-header h3 {
		margin: 0;
	}

	.timer {
		font-family: monospace;
		color: #888;
	}

	.timer.urgent {
		color: #d0392b;
	}

	.cash {
		margin-left: auto;
		font-family: monospace;
		color: #9fd68a;
		font-weight: 600;
	}

	.section h4 {
		margin: 0 0 0.35rem 0;
		font-size: 0.85rem;
		color: #aaa;
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}

	.grid {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.toast.reject {
		padding: 0.4rem 0.75rem;
		border-radius: 6px;
		background: rgba(208, 57, 43, 0.2);
		border: 1px solid #d0392b;
		font-family: monospace;
		font-size: 0.85rem;
	}
</style>
