// Builders/senders for the lobby & session client->server messages
// (protocol.md section 3): CreateMatch, JoinMatch, SetReady, Rejoin,
// LeaveMatch. Kept separate from the stores that react to the corresponding
// server replies (sessionStore, lobbyStore) so screens can call these
// directly without importing wsClient/envelope helpers themselves.

import { wsClient } from './wsClient';
import { buildEnvelope, nextRequestId } from '../protocol/envelope';
import type {
	CreateMatchPayload,
	JoinMatchPayload,
	LeaveMatchPayload,
	MatchConfig,
	RejoinPayload,
	SetReadyPayload
} from '../protocol/types';

export function sendCreateMatch(
	displayName: string,
	matchConfig: Partial<MatchConfig> | null = null
): string {
	const requestId = nextRequestId();
	const payload: CreateMatchPayload = { displayName, matchConfig };
	wsClient.sendJson(buildEnvelope('CreateMatch', payload, requestId));
	return requestId;
}

export function sendJoinMatch(matchId: string, displayName: string): string {
	const requestId = nextRequestId();
	const payload: JoinMatchPayload = { matchId, displayName };
	wsClient.sendJson(buildEnvelope('JoinMatch', payload, requestId));
	return requestId;
}

export function sendSetReady(ready: boolean): string {
	const requestId = nextRequestId();
	const payload: SetReadyPayload = { ready };
	wsClient.sendJson(buildEnvelope('SetReady', payload, requestId));
	return requestId;
}

export function sendRejoin(matchId: string, playerToken: string): string {
	const requestId = nextRequestId();
	const payload: RejoinPayload = { matchId, playerToken };
	wsClient.sendJson(buildEnvelope('Rejoin', payload, requestId));
	return requestId;
}

export function sendLeaveMatch(): string {
	const requestId = nextRequestId();
	const payload: LeaveMatchPayload = {};
	wsClient.sendJson(buildEnvelope('LeaveMatch', payload, requestId));
	return requestId;
}
