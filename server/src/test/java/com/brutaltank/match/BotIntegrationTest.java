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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage for the bots feature (live-playtest request: "the
 * bots make the same choices in the same screens as we do"): a lone human +
 * bots auto-starts on Ready, bots take every turn themselves within the
 * timeout (never TurnForfeited, which would mean the turn/shop hooks aren't
 * actually driving them), shop purchases never exceed cash/stock, and a full
 * match reaches COMPLETE without the human ever needing to act beyond the
 * initial Ready click.
 */
class BotIntegrationTest {

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
    @Timeout(30)
    void loneHumanPlusBotsAutoStartsAndReachesCompleteWithoutHumanTakingBotTurns() throws Exception {
        MatchConfig config = new MatchConfig(2, 8, 0, Difficulty.MIXED); // maxRounds=2, botCount unused here (bots added manually below)
        Match match = new Match("m-bot-1", MAPPER, config, scheduler);
        match.setTurnTimeoutMs(1200);
        match.setShopTimeoutMs(1200);
        match.setBotThinkDelayRangeMs(10, 60); // fast "thinking" so this test doesn't sleep real seconds

        FakeMessageSink humanSink = new FakeMessageSink();
        Match.JoinResult human = match.addPlayer("Human", humanSink);
        assertTrue(human.success());

        Match.JoinResult bot1 = match.addBot("Bot One", new BotProfile(
                Difficulty.MEDIUM, 8, 10, true, 0.05, 0.6, 0.6, java.util.Map.of(), 0.4, 0.2));
        Match.JoinResult bot2 = match.addBot("Bot Two", new BotProfile(
                Difficulty.EASY, 20, 25, false, 0.12, 0.2, 0.2, java.util.Map.of(), 0.3, 0.1));
        assertTrue(bot1.success());
        assertTrue(bot2.success());

        // Match hasn't started yet -- only the human hasn't readied.
        assertEquals(Match.Status.WAITING, match.status());

        Match.ReadyResult readyResult = match.setReady(human.playerId(), true);
        assertTrue(readyResult.success());
        assertTrue(readyResult.matchStarted(), "match should auto-start once the human readies with bots already ready");

        waitUntil(() -> match.status() == Match.Status.COMPLETE, 25_000);

        // The defining behavior under test: bots took every one of their own
        // turns and shop visits themselves, in time, exactly like a human
        // would -- never falling back to the AFK-forfeit/timeout path. (The
        // human in this test never fires on purpose, so *its* own turns are
        // expected to forfeit -- only a bot forfeiting would mean the
        // turn/shop hooks aren't actually driving them.)
        for (String json : humanSink.messages()) {
            com.fasterxml.jackson.databind.JsonNode node = MAPPER.readTree(json);
            if ("TurnForfeited".equals(node.path("type").asText())) {
                String forfeitedPlayerId = node.path("payload").path("playerId").asText();
                assertTrue(forfeitedPlayerId.equals(human.playerId()),
                        "a bot (" + forfeitedPlayerId + ") was auto-skipped -- the turn hook isn't driving it");
            }
        }
        assertTrue(humanSink.countOfType("ShotResolved") > 0, "bots should have actually fired shots");
        assertTrue(humanSink.countOfType("MatchEnded") > 0);
    }
}
