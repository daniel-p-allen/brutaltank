package com.brutaltank.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Disconnect/reconnect coverage (PLAN.md 2.1 / M2 task spec): disconnecting
 * mid-turn doesn't hang the match (the existing turn timer auto-skips), and
 * a {@code Rejoin} within the grace period restores the session and gets a
 * {@code MatchStateSync}. Uses short injected grace/timeout durations.
 */
class MatchDisconnectReconnectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private Match newMatch(String id) {
        return new Match(id, MAPPER, MatchConfig.defaultConfig(), scheduler);
    }

    private record Joined(String playerId, String token, FakeMessageSink sink) {
    }

    private Joined join(Match match, String name) {
        FakeMessageSink sink = new FakeMessageSink();
        Match.JoinResult result = match.addPlayer(name, sink);
        assertTrue(result.success());
        return new Joined(result.playerId(), result.playerToken(), sink);
    }

    private static void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting");
            }
        }
        fail("condition not met within " + timeoutMs + "ms");
    }

    @Test
    @Timeout(10)
    void disconnectingActivePlayerDoesNotHangMatchTurnAutoSkips() {
        Match match = newMatch("m-disc-1");
        match.setTurnTimeoutMs(150);
        match.setReconnectGraceMs(60_000); // long grace: shouldn't fire in this test
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        assertEquals(p1.playerId(), match.activePlayerId());
        match.handleDisconnect(p1.playerId());

        // A PlayerDisconnected broadcast should have gone out to the remaining player.
        assertNotNull(p2.sink().lastPayloadOfType("PlayerDisconnected"));

        // The match must not hang: the normal 30s(150ms in test) turn timer
        // auto-skips the disconnected active player's turn.
        waitUntil(() -> p2.playerId().equals(match.activePlayerId()), 5_000);
    }

    @Test
    @Timeout(10)
    void rejoinWithinGracePeriodRestoresSessionAndSendsMatchStateSync() {
        Match match = newMatch("m-disc-2");
        match.setTurnTimeoutMs(30_000);
        match.setReconnectGraceMs(5_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        match.handleDisconnect(p2.playerId());

        FakeMessageSink newSink = new FakeMessageSink();
        Match.RejoinResult result = match.rejoin(p2.token(), newSink);

        assertTrue(result.success());
        assertEquals(p2.playerId(), result.playerId());

        var sync = newSink.lastPayloadOfType("MatchStateSync");
        assertNotNull(sync, "rejoining client should receive a full MatchStateSync");
        assertEquals("m-disc-2", sync.get("matchId").asText());

        var reconnected = p1.sink().lastPayloadOfType("PlayerReconnected");
        assertNotNull(reconnected);
        assertEquals(p2.playerId(), reconnected.get("playerId").asText());
    }

    @Test
    @Timeout(10)
    void rejoinWithInvalidTokenFails() {
        Match match = newMatch("m-disc-3");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        FakeMessageSink newSink = new FakeMessageSink();
        Match.RejoinResult result = match.rejoin("tok-bogus", newSink);
        assertTrue(!result.success());
        assertEquals("INVALID_TOKEN_OR_EXPIRED", result.errorReason());
    }

    @Test
    @Timeout(10)
    void expiredGracePeriodRemovesPlayerAndMatchContinues() {
        Match match = newMatch("m-disc-4");
        match.setTurnTimeoutMs(30_000);
        match.setReconnectGraceMs(100); // short: let the grace timer really expire
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        // p2 is not currently active (p1 is); disconnect them and let grace expire.
        match.handleDisconnect(p2.playerId());
        waitUntil(() -> match.isDeparted(p2.playerId()), 5_000);

        assertTrue(match.turnOrderSnapshot().stream().noneMatch(id -> id.equals(p2.playerId())),
                "departed player should be removed from turn order");

        // A late rejoin past the grace period must fail.
        FakeMessageSink lateSink = new FakeMessageSink();
        Match.RejoinResult result = match.rejoin(p2.token(), lateSink);
        assertTrue(!result.success());

        // Match still functions: p1 (still active) can fire.
        assertEquals(p1.playerId(), match.activePlayerId());
        assertTrue(match.fire(p1.playerId(), "r1", "basic_shell", 45, 40).accepted());
    }
}
