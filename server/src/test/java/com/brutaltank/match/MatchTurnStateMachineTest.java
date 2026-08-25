package com.brutaltank.match;

import com.brutaltank.domain.weapon.WeaponDef;
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
        return new Match(id, MAPPER,
                new MatchConfig(maxRounds, maxPlayers, MatchConfig.DEFAULT_BOT_COUNT, MatchConfig.DEFAULT_BOT_DIFFICULTY),
                scheduler);
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

        Match.FireOutcome outcome = match.fire(p1.playerId(), "r1", "totally_fake_weapon", 45, 50);
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
    void roundEndsWhenAtMostOneTankAliveThenOpensShopThenRespawnsForNextRound() {
        Match match = newMatch("m-turn-4", 4, 8);
        match.setTurnTimeoutMs(30_000);
        match.setShopTimeoutMs(100); // M4: short so the test doesn't wait the real 30s shop phase
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

        // Round < maxRounds (3): RoundEnded is immediately followed by a shop
        // phase (M4), not an immediate respawn — protocol.md's "no shop pause"
        // note was an M2 simplification, since superseded.
        assertEquals(Match.Status.SHOP, match.status());
        assertEquals(1, match.roundNumber(), "round number shouldn't advance until the shop phase closes");
        assertNotNull(p1.sink().lastPayloadOfType("ShopOpened"));

        // Once the (short, test-overridden) shop timeout elapses, the match
        // regenerates terrain and respawns all non-departed players alive at
        // full health for round 2.
        waitUntil(() -> match.roundNumber() >= 2, 5_000);
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

    // -----------------------------------------------------------------
    // Rematch (PlayAgain): regression coverage for a real bug -- "Back to
    // Start" used to strand players at the menu since there was no path
    // back from COMPLETE to WAITING, forcing a full re-login/match-creation
    // cycle every time instead of returning to the same lobby.
    // -----------------------------------------------------------------

    @Test
    @Timeout(10)
    void rematchResetsAnEndedMatchBackToWaitingWithTheSameRoster() {
        Match match = newMatch("m-rematch-1", 1, 8); // maxRounds = 1
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        // p2 is already down, so p1's shot (spending baby_missile ammo, so
        // the loadout reset below is actually observable) ends the round --
        // and the match, since maxRounds=1.
        match.debugSetHealth(p2.playerId(), 0);
        assertTrue(match.fire(p1.playerId(), "r1", "baby_missile", 45, 40).accepted());

        assertEquals(Match.Status.COMPLETE, match.status());

        match.rematch(p1.playerId());

        assertEquals(Match.Status.WAITING, match.status());
        assertEquals(1, match.roundNumber());
        assertEquals(500, match.cashOf(p1.playerId()));
        assertEquals(500, match.cashOf(p2.playerId()));
        assertEquals(100.0, match.healthOf(p1.playerId()));
        assertEquals(100.0, match.healthOf(p2.playerId()));
        assertTrue(match.isAlive(p1.playerId()));
        assertTrue(match.isAlive(p2.playerId()));
        // Loadout reset to defaults, not carried over depleted.
        assertEquals(WeaponDef.byId("baby_missile").defaultQty(), match.loadoutQtyOf(p1.playerId(), "baby_missile"));

        var lobbyUpdate = p1.sink().lastPayloadOfType("LobbyUpdate");
        assertNotNull(lobbyUpdate, "rematch should broadcast a LobbyUpdate so clients route back to the lobby");
        assertEquals(2, lobbyUpdate.get("players").size());
        for (var playerNode : lobbyUpdate.get("players")) {
            assertFalse(playerNode.get("ready").asBoolean(), "players should need to ready up again for the new match");
        }

        // The reset lobby is fully functional: readying both players again
        // starts a brand-new match from round 1.
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        assertEquals(Match.Status.IN_PROGRESS, match.status());
        assertEquals(1, match.roundNumber());
    }

    @Test
    @Timeout(10)
    void rematchIsANoOpUnlessTheMatchHasEnded() {
        Match match = newMatch("m-rematch-2", 4, 8);
        Joined p1 = join(match, "P1");
        join(match, "P2");

        match.rematch(p1.playerId()); // match is still WAITING -- no-op
        assertEquals(Match.Status.WAITING, match.status());

        match.setReady(p1.playerId(), true);
        // Only p1 ready -- match still WAITING, not IN_PROGRESS.
        match.rematch(p1.playerId()); // still not COMPLETE -- no-op
        assertEquals(Match.Status.WAITING, match.status());
    }

    @Test
    @Timeout(10)
    void rematchReReadiesBotsButNotHumans() {
        // Regression test for a real bug (filed and fixed 2026-08-25): bots
        // auto-ready themselves once, in addBot() -- but rematch() didn't
        // re-invoke that, so after a rematch bots reset to unready and stayed
        // stuck there forever (no client of their own to click Ready again).
        Match match = newMatch("m-rematch-3", 1, 8); // maxRounds = 1
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Match.JoinResult bot = match.addBot("Bot One", new BotProfile(
                Difficulty.MEDIUM, 8, 10, true, 0.05, 0.6, 0.6, java.util.Map.of(), 0.4, 0.2));
        assertTrue(bot.success());

        // Bot is pre-readied; the match auto-starts on the human's Ready.
        Match.ReadyResult readyResult = match.setReady(p1.playerId(), true);
        assertTrue(readyResult.matchStarted());
        assertEquals(Match.Status.IN_PROGRESS, match.status());

        match.debugSetHealth(bot.playerId(), 0);
        assertTrue(match.fire(p1.playerId(), "r1", "basic_shell", 45, 40).accepted());
        assertEquals(Match.Status.COMPLETE, match.status());

        match.rematch(p1.playerId());

        var lobbyUpdate = p1.sink().lastPayloadOfType("LobbyUpdate");
        assertNotNull(lobbyUpdate);
        for (var playerNode : lobbyUpdate.get("players")) {
            boolean isBot = playerNode.get("isBot").asBoolean();
            assertEquals(isBot, playerNode.get("ready").asBoolean(),
                    "bots should auto-ready again after a rematch; humans should not");
        }

        // The reset lobby is actually functional: the human readying alone
        // (bot is already ready) should be enough to auto-start, same as the
        // original match creation.
        Match.ReadyResult secondReady = match.setReady(p1.playerId(), true);
        assertTrue(secondReady.matchStarted(), "match should auto-start again since the bot is still ready");
    }

    @Test
    @Timeout(15)
    void sixtyTurnSafetyCapEndsRoundWithoutElimination() {
        Match match = newMatch("m-turn-6", 4, 8);
        match.setTurnTimeoutMs(15); // short: let the real auto-skip timer drive all 60 turns
        match.setShopTimeoutMs(100); // M4: short so round 2 isn't blocked on the real 30s shop phase
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
    void aimUpdateBroadcastsToEveryoneRegardlessOfWhoseTurnItIs() {
        Match match = newMatch("m-turn-aim", 4, 8);
        match.setTurnTimeoutMs(30_000);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        // p2 is not the active player, but per user feedback aim updates
        // aren't turn-gated -- anyone can play with their aim at any time.
        assertEquals(p1.playerId(), match.activePlayerId());
        match.updateAim(p2.playerId(), 77.0);

        var aiming = p1.sink().lastPayloadOfType("PlayerAiming");
        assertNotNull(aiming);
        assertEquals(p2.playerId(), aiming.get("playerId").asText());
        assertEquals(77.0, aiming.get("angleDeg").asDouble(), 0.001);

        // The sender's own client also receives the broadcast (harmless --
        // it already has the value locally, but the relay is uniform).
        var aimingOnSender = p2.sink().lastPayloadOfType("PlayerAiming");
        assertNotNull(aimingOnSender);
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
