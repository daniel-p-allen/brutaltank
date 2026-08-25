package com.brutaltank.match;

import com.brutaltank.protocol.Payloads;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Drives every bot in one {@link Match}. Reacts to the same "a turn/shop
 * phase started" moments a real client would react to over the wire (see
 * {@code Match#beginTurn}/{@code Match#openShop}'s hook calls into this
 * class), but calls {@code Match}'s existing {@code fire}/{@code purchase}/
 * {@code shopContinue} methods directly instead of round-tripping through
 * JSON/WebSocket -- a bot is just a {@code MatchPlayer} with no socket (see
 * {@link Match#addBot}).
 *
 * <p>Every scheduled action re-validates its turn/shop token is still
 * current before acting (mirrors {@code Match#onTurnTimeout}'s existing
 * {@code token != turnToken} staleness guard) so a stale callback -- e.g.
 * the match ended, or somehow the turn already resolved -- safely no-ops
 * instead of firing into invalid state.
 */
final class BotController {

    // "Thinking" delay before a bot acts, so bots don't fire/shop instantly
    // (per the request that bots "feel human") and so several bots' shop
    // decisions in the same phase don't all land in the same instant.
    private static final long DEFAULT_TURN_DELAY_MIN_MS = 1500;
    private static final long DEFAULT_TURN_DELAY_MAX_MS = 4500;
    private static final long DEFAULT_SHOP_DELAY_MIN_MS = 800;
    private static final long DEFAULT_SHOP_DELAY_MAX_MS = 3300;

    private final Match match;
    private final ScheduledExecutorService scheduler;
    private final Map<String, BotProfile> bots = new LinkedHashMap<>();
    private final Random random = new Random();
    private final long turnDelayMinMs;
    private final long turnDelayMaxMs;
    private final long shopDelayMinMs;
    private final long shopDelayMaxMs;

    /** {@code overrideMinMs}/{@code overrideMaxMs} < 0 means "use the production defaults for both turn and shop delays" (see Match#setBotThinkDelayRangeMs). */
    BotController(Match match, ScheduledExecutorService scheduler, long overrideMinMs, long overrideMaxMs) {
        this.match = match;
        this.scheduler = scheduler;
        if (overrideMinMs >= 0 && overrideMaxMs >= overrideMinMs) {
            this.turnDelayMinMs = overrideMinMs;
            this.turnDelayMaxMs = overrideMaxMs;
            this.shopDelayMinMs = overrideMinMs;
            this.shopDelayMaxMs = overrideMaxMs;
        } else {
            this.turnDelayMinMs = DEFAULT_TURN_DELAY_MIN_MS;
            this.turnDelayMaxMs = DEFAULT_TURN_DELAY_MAX_MS;
            this.shopDelayMinMs = DEFAULT_SHOP_DELAY_MIN_MS;
            this.shopDelayMaxMs = DEFAULT_SHOP_DELAY_MAX_MS;
        }
    }

    void registerBot(String playerId, BotProfile profile) {
        bots.put(playerId, profile);
    }

    void onTurnStarted(String activePlayerId, int turnToken) {
        BotProfile profile = bots.get(activePlayerId);
        if (profile == null) {
            return; // a human's turn
        }
        long delayMs = turnDelayMinMs + random.nextLong(turnDelayMaxMs - turnDelayMinMs + 1);
        scheduler.schedule(() -> takeTurn(activePlayerId, profile, turnToken), delayMs, TimeUnit.MILLISECONDS);
    }

    void onShopOpened(int shopToken) {
        for (Map.Entry<String, BotProfile> entry : bots.entrySet()) {
            long delayMs = shopDelayMinMs + random.nextLong(shopDelayMaxMs - shopDelayMinMs + 1);
            scheduler.schedule(() -> takeShopTurn(entry.getKey(), entry.getValue(), shopToken),
                    delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void takeTurn(String playerId, BotProfile profile, int expectedToken) {
        if (!match.isTurnTokenCurrent(playerId, expectedToken)) {
            return; // turn already resolved another way, or the match moved on
        }
        Match.TankSnapshot self = null;
        List<Match.TankSnapshot> all = match.tankSnapshots();
        for (Match.TankSnapshot t : all) {
            if (t.playerId().equals(playerId)) {
                self = t;
                break;
            }
        }
        if (self == null) {
            return; // departed between scheduling and firing -- nothing to do
        }
        BotAimPlanner.Plan plan = BotAimPlanner.plan(
                match.terrainSnapshot(), self, all, match.windStrength(),
                match.loadoutSnapshot(playerId), profile, random);
        match.fire(playerId, null, plan.weaponId(), plan.angleDeg(), plan.power(), false);
    }

    private void takeShopTurn(String playerId, BotProfile profile, int expectedShopToken) {
        if (!match.isShopTokenCurrent(expectedShopToken) || match.isDeparted(playerId)) {
            return;
        }
        List<Payloads.PriceListEntry> priceList = match.priceListSnapshot();
        int cash = match.cashOf(playerId);
        List<BotShopPlanner.Purchase> purchases = BotShopPlanner.plan(priceList, cash, profile, random);
        for (BotShopPlanner.Purchase purchase : purchases) {
            match.purchase(playerId, purchase.itemId(), purchase.itemType(), purchase.quantity());
        }
        match.shopContinue(playerId);
    }
}
