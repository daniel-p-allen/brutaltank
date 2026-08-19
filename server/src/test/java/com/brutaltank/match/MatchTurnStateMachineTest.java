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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Turn state machine coverage (PLAN.md 2.3 / M2 task spec): non-active-player
 * Fire rejected, turn order advances to the next alive/connected player,
 * round-end at <=1 alive, the 60-turn safety cap, round -> next-round
 * transition (fresh terrain + respawn), and match-end at maxRounds.
 *
 * <p>Uses short, injected turn-timeout/reconnect-grace durations (per the
 * task's explicit "do not literally sleep 30/120 real seconds" instruction)
 * and a real {@link ScheduledExecutorService} so the timer-driven code paths
 * genuinely execute, polled for with short waits rather than fixed sleeps.
 */
class MatchTurnStateMachineTest {

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

    private Match newMatch(String id, int maxRounds, int maxPlayers) {
        return new Match(id, MAPPER, new MatchConfig(maxRounds, maxPlayers), scheduler);
    }

    private record Joined(String playerId, FakeMessageSink sink) {
    }

    private Joined join(Match match, String name) {
        FakeMessageSink sink = new FakeMessageSink();
        Match.JoinResult result = match.addPlayer(name, sink);
        assertTrue(result.success(), "join should succeed: " + result.errorReason());
        return new Joined(result.playerId(), sink);
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
    void nonActivePlayerFireIsRejectedNotYourTurn() {
        Match match = newMatch("m-turn-1", 4, 8);
        match.setTurnTimeoutMs(30_000); // long enough that no auto-skip interferes with this test
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        assertEquals(p1.playerId(), match.activePlayerId());

        Match.FireOutcome outcome = match.fire(p2.playerId(), "r1", "basic_shell", 45, 50);
        assertFalse(outcome.accepted());
        assertEquals("NOT_YOUR_TURN", outcome.rejectReason());

        // Active player is unchanged, and no ShotResolved should be broadcast.
        assertEquals(p1.playerId(), match.activePlayerId());
        assertEquals(0, p1.sink().countOfType("ShotResolved"));
    }

    @Test
    @Timeout(10)
    void invalidWeaponIsRejected() {
        Match match = newMatch("m-turn-2", 4, 8);
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        Match.FireOutcome outcome = match.fire(p1.playerId(), "r1", "nuke", 45, 50);
        assertFalse(outcome.accepted());
        assertEquals("INVALID_WEAPON", outcome.rejectReason());
    }

    @Test
    @Timeout(10)
    void turnAdvancesRoundRobinAcrossAlivePlayers() {
        Match match = newMatch("m-turn-3", 4, 8);
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        assertEquals(p1.playerId(), match.activePlayerId());
        assertTrue(match.fire(p1.playerId(), "r1", "basic_shell", 45, 40).accepted());
        assertEquals(p2.playerId(), match.activePlayerId());
        assertTrue(match.fire(p2.playerId(), "r2", "basic_shell", 45, 40).accepted());
        assertEquals(p3.playerId(), match.activePlayerId());
        assertTrue(match.fire(p3.playerId(), "r3", "basic_shell", 45, 40).accepted());
        // Wraps back around to p1.
        assertEquals(p1.playerId(), match.activePlayerId());
        assertEquals(3, match.turnsThisRound());
    }

    @Test
    @Timeout(10)
    void roundEndsWhenAtMostOneTankAliveAndRespawnsForNextRound() {
        Match match = newMatch("m-turn-4", 4, 8);
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        Joined p3 = join(match, "P3");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.setReady(p3.playerId(), true);

        // Force p2 and p3 down to 0 health so p1 firing ends the round.
        match.debugSetHealth(p2.playerId(), 0);
        match.debugSetHealth(p3.playerId(), 0);
        assertFalse(match.isAlive(p2.playerId()));
        assertFalse(match.isAlive(p3.playerId()));

        assertEquals(1, match.roundNumber());
        assertTrue(match.fire(p1.playerId(), "r1", "basic_shell", 45, 40).accepted());

        var roundEnded = p1.sink().lastPayloadOfType("RoundEnded");
        assertNotNull(roundEnded);
        assertEquals(p1.playerId(), roundEnded.get("winnerPlayerId").asText());

        // Round < maxRounds (4): match immediately regenerates terrain and respawns
        // all non-departed players alive at full health for round 2 (M2 simplification,
        // no shop pause per shared/protocol.md's M2 note).
        assertEquals(2, match.roundNumber());
        assertTrue(match.isAlive(p2.playerId()), "p2 should be respawned alive for round 2");
        assertTrue(match.isAlive(p3.playerId()), "p3 should be respawned alive for round 2");
        assertEquals(0, p1.sink().countOfType("MatchEnded"));
    }

    @Test
    @Timeout(10)
    void matchEndsAfterMaxRounds() {
        Match match = newMatch("m-turn-5", 1, 8); // maxRounds = 1
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        match.debugSetHealth(p2.playerId(), 0);
        assertTrue(match.fire(p1.playerId(), "r1", "basic_shell", 45, 40).accepted());

        assertEquals(Match.Status.COMPLETE, match.status());
        var matchEnded = p1.sink().lastPayloadOfType("MatchEnded");
        assertNotNull(matchEnded);
        assertTrue(matchEnded.get("finalStandings").isArray());
        assertEquals(2, matchEnded.get("finalStandings").size());
    }

    @Test
    @Timeout(15)
    void sixtyTurnSafetyCapEndsRoundWithoutElimination() {
        Match match = newMatch("m-turn-6", 4, 8);
        match.setTurnTimeoutMs(15); // short: let the real auto-skip timer drive all 60 turns
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        // Nobody ever fires: every turn times out (a "skip"), so both tanks stay
        // alive at full health and the 60-turn safety cap must trigger round-end.
        waitUntil(() -> match.roundNumber() >= 2, 10_000);

        assertTrue(match.isAlive(p1.playerId()));
        assertTrue(match.isAlive(p2.playerId()));
        long roundEndedCount = p1.sink().countOfType("RoundEnded");
        assertTrue(roundEndedCount >= 1, "expected at least one RoundEnded broadcast");
        // No ShotResolved should ever appear since every turn was a timeout-skip.
        assertEquals(0, p1.sink().countOfType("ShotResolved"));
    }

    @Test
    @Timeout(10)
    void turnTimeoutAutoSkipsWithoutFiringAndAdvancesTurn() {
        Match match = newMatch("m-turn-7", 4, 8);
        match.setTurnTimeoutMs(150);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        assertEquals(p1.playerId(), match.activePlayerId());
        waitUntil(() -> p2.playerId().equals(match.activePlayerId()), 5_000);
        assertEquals(0, p1.sink().countOfType("ShotResolved"));
        assertEquals(1, match.turnsThisRound());
    }
}
