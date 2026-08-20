// Builds and sends the ShopPurchase envelope (M4). Response arrives
// asynchronously as either ShopUpdate (success, matchStore.applyShopUpdate)
// or ErrorMsg (rejection, matchStore.applyErrorMsg) — no optimistic UI here,
// same "wait for the server" convention as fireInput.ts.

import { wsClient } from '../../net/wsClient';
import { buildEnvelope, nextRequestId } from '../../protocol/envelope';
import type { ShopPurchasePayload } from '../../protocol/types';

export function sendShopPurchase(itemId: string, itemType: 'WEAPON' | 'SHIELD', quantity: number): string {
	const requestId = nextRequestId();
	const payload: ShopPurchasePayload = { itemId, itemType, quantity };
	const envelope = buildEnvelope('ShopPurchase', payload, requestId);
	wsClient.sendJson(envelope);
	return requestId;
}
