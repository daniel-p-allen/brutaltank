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
        // 3 players (not 2): two remain connected after p1 disconnects, so
        // this exercises plain auto-skip-to-next-player, not the "all but one
        // disconnected" early round-end covered by turnLapseWithAllButOne...
        // below.
        Match match = newMatch("m-disc-1");
        match.setTurnTimeoutMs(150);
        match.setReconnectGraceMs(60_000); // long grace: shouldn't fire in this test
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        assertEquals(p1.playerId(), match.activePlayerId());
        match.handleDisconnect(p1.playerId());

        // A PlayerDisconnected broadcast should have gone out to the remaining players.
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

    @Test
    @Timeout(10)
    void lastPlayerStandingAfterOthersDisconnectEndsRoundAndOpensShop() {
        // "All but one drops out" via disconnects rather than combat
        // elimination (M4: RoundEnded -> ShopOpened, not combat) — user
        // feedback asked specifically to verify this path.
        Match match = newMatch("m-disc-5");
        match.setTurnTimeoutMs(30_000);
        match.setReconnectGraceMs(100); // short: let grace really expire
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        String activeBeforeDisconnect = match.activePlayerId();
        String disconnecting = activeBeforeDisconnect.equals(p1.playerId()) ? p2.playerId() : p1.playerId();
        String survivor = activeBeforeDisconnect.equals(p1.playerId()) ? p1.playerId() : p2.playerId();

        match.handleDisconnect(disconnecting);
        waitUntil(() -> match.isDeparted(disconnecting), 5_000);

        // Once the sole other player departs, the round must end (survivor
        // wins) and the match must move on to the shop phase, not hang.
        waitUntil(() -> match.status() == Match.Status.SHOP, 5_000);
        assertEquals(1, match.roundNumber(), "round number shouldn't advance until the shop phase closes");

        FakeMessageSink survivorSink = survivor.equals(p1.playerId()) ? p1.sink() : p2.sink();
        var roundEnded = survivorSink.lastPayloadOfType("RoundEnded");
        assertNotNull(roundEnded, "expected RoundEnded to have been broadcast");
        assertEquals(survivor, roundEnded.get("winnerPlayerId").asText());
        assertNotNull(survivorSink.lastPayloadOfType("ShopOpened"), "expected ShopOpened to follow RoundEnded");
    }

    @Test
    @Timeout(10)
    void turnLapseWithAllButOneDisconnectedEndsRoundImmediatelyWithoutWaitingOutGrace() {
        // User feedback: "if a turn lapses with everyone except one
        // disconnecting, that should be the end of the game" -- don't make
        // the sole connected player wait out everyone else's full reconnect
        // grace period one at a time; end it as soon as an auto-skip lapse
        // happens and at most one alive player is still connected.
        Match match = newMatch("m-disc-6");
        match.setTurnTimeoutMs(100); // short: let turns really lapse via auto-skip
        match.setReconnectGraceMs(60_000); // long: this test must NOT depend on grace expiring
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        // p1 is active first; disconnect p2 and p3 (neither departs -- grace
        // is long), leaving p1 the only connected player.
        match.handleDisconnect(p2.playerId());
        match.handleDisconnect(p3.playerId());
        assertTrue(match.isAlive(p2.playerId()), "still alive/non-departed, just disconnected");
        assertTrue(match.isAlive(p3.playerId()), "still alive/non-departed, just disconnected");

        // p1's own turn resolves fine (still connected), advancing to p2's
        // turn, which must lapse (auto-skip) since p2 is disconnected -- that
        // lapse is what should end the round, well before the 60s grace.
        waitUntil(() -> match.status() == Match.Status.SHOP, 5_000);

        var roundEnded = p1.sink().lastPayloadOfType("RoundEnded");
        assertNotNull(roundEnded, "expected RoundEnded to have been broadcast");
        assertEquals(p1.playerId(), roundEnded.get("winnerPlayerId").asText(),
                "the sole connected player should be awarded the round, not whichever disconnected tank has the most HP");
    }
}
