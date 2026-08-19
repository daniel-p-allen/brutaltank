package com.brutaltank.lobby;

import com.brutaltank.match.Match;
import com.brutaltank.match.MatchConfig;
import com.brutaltank.match.MatchRegistry;
import com.brutaltank.net.Envelopes;
import com.brutaltank.net.MessageSink;
import com.brutaltank.net.PlayerSession;
import com.brutaltank.protocol.Payloads;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Routes lobby-phase (and cross-match) client messages to the right
 * {@link Match}, per PLAN.md 2.1. Thin by design: all actual state
 * mutation/broadcasting lives on {@link Match}; this class resolves "which
 * match" from the {@link PlayerSession}/message, calls into it, and sends
 * the direct (non-broadcast) replies that are addressed to a single
 * requester (e.g. {@code MatchCreated}, {@code MatchJoined}, error replies).
 */
public final class LobbyManager {

    private final MatchRegistry registry;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    // -1 (default) means "use Match's built-in production defaults" (30s/120s).
    // Only ever overridden by tests that need to reach round-end/grace-expiry
    // without sleeping real production durations (see the live-WS integration test).
    private final long turnTimeoutMsOverride;
    private final long reconnectGraceMsOverride;

    public LobbyManager(MatchRegistry registry, ObjectMapper mapper, ScheduledExecutorService scheduler) {
        this(registry, mapper, scheduler, -1, -1);
    }

    public LobbyManager(MatchRegistry registry, ObjectMapper mapper, ScheduledExecutorService scheduler,
                         long turnTimeoutMsOverride, long reconnectGraceMsOverride) {
        this.registry = registry;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.turnTimeoutMsOverride = turnTimeoutMsOverride;
        this.reconnectGraceMsOverride = reconnectGraceMsOverride;
    }

    public void handleCreateMatch(PlayerSession session, MessageSink sink, String requestId, Payloads.CreateMatch payload) {
        String matchId = "m-" + UUID.randomUUID().toString().substring(0, 8);
        MatchConfig config = MatchConfig.fromDto(payload.matchConfig);
        Match match = new Match(matchId, mapper, config, scheduler);
        if (turnTimeoutMsOverride > 0) {
            match.setTurnTimeoutMs(turnTimeoutMsOverride);
        }
        if (reconnectGraceMsOverride > 0) {
            match.setReconnectGraceMs(reconnectGraceMsOverride);
        }
        registry.put(match);

        Match.JoinResult result = match.addPlayer(displayNameOrDefault(payload.displayName), sink);
        if (!result.success()) {
            // Should not happen for a brand-new match, but stay defensive.
            registry.remove(matchId);
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg(result.errorReason(), "Failed to create match."));
            return;
        }

        session.playerId = result.playerId();
        session.currentMatchId = matchId;
        session.playerToken = result.playerToken();

        Envelopes.send(sink, mapper, "MatchCreated", requestId,
                new Payloads.MatchCreated(matchId, match.joinCode, result.playerToken(), result.playerId()));
    }

    public void handleJoinMatch(PlayerSession session, MessageSink sink, String requestId, Payloads.JoinMatch payload) {
        Match match = registry.get(payload.matchId);
        if (match == null) {
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg("MATCH_NOT_FOUND", "No match with that code exists."));
            return;
        }

        Match.JoinResult result = match.addPlayer(displayNameOrDefault(payload.displayName), sink);
        if (!result.success()) {
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg(result.errorReason(), humanReadable(result.errorReason())));
            return;
        }

        session.playerId = result.playerId();
        session.currentMatchId = match.matchId;
        session.playerToken = result.playerToken();

        Envelopes.send(sink, mapper, "MatchJoined", requestId,
                new Payloads.MatchJoined(match.matchId, result.playerToken(), result.playerId()));
    }

    public void handleSetReady(PlayerSession session, String requestId, MessageSink sink, boolean ready) {
        Match match = matchFor(session);
        if (match == null) {
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg("NOT_IN_MATCH", "You are not currently in a match."));
            return;
        }
        match.setReady(session.playerId, ready);
    }

    public void handleRejoin(PlayerSession session, MessageSink sink, String requestId, Payloads.Rejoin payload) {
        Match match = registry.get(payload.matchId);
        if (match == null) {
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg("MATCH_NOT_FOUND", "No match with that code exists."));
            return;
        }
        Match.RejoinResult result = match.rejoin(payload.playerToken, sink);
        if (!result.success()) {
            Envelopes.send(sink, mapper, "ErrorMsg", requestId,
                    new Payloads.ErrorMsg(result.errorReason(), "Could not rejoin: token invalid or expired."));
            return;
        }
        session.playerId = result.playerId();
        session.currentMatchId = match.matchId;
        session.playerToken = payload.playerToken;
    }

    public void handleLeaveMatch(PlayerSession session) {
        Match match = matchFor(session);
        if (match != null) {
            match.leaveMatch(session.playerId);
        }
        session.currentMatchId = null;
        session.playerId = null;
        session.playerToken = null;
    }

    /** Called from the server on socket close: marks the player's match-side state disconnected. */
    public void handleSocketClosed(PlayerSession session) {
        session.connected = false;
        Match match = matchFor(session);
        if (match != null) {
            match.handleDisconnect(session.playerId);
        }
    }

    public Match matchFor(PlayerSession session) {
        if (session == null || session.currentMatchId == null || session.playerId == null) {
            return null;
        }
        return registry.get(session.currentMatchId);
    }

    private static String displayNameOrDefault(String displayName) {
        return (displayName == null || displayName.isBlank()) ? "Player" : displayName;
    }

    private static String humanReadable(String reason) {
        return switch (reason) {
            case "MATCH_FULL" -> "That match is full.";
            case "MATCH_IN_PROGRESS" -> "That match has already started.";
            default -> "Could not join match.";
        };
    }
}
