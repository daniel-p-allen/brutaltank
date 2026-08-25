package com.brutaltank.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M4 coverage (PLAN.md 4.5 / shared/protocol.md section 5): shop-phase
 * gating, purchase validation (funds/phase/item), cash/loadout bookkeeping,
 * and the shared match-wide stock pool (an addition beyond the original
 * protocol.md table — see WeaponDef.shopStock / ShieldDef.shopStock).
 */
class ShopTest {

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
        Match m = new Match(id, MAPPER,
                new MatchConfig(4, 8, MatchConfig.DEFAULT_BOT_COUNT, MatchConfig.DEFAULT_BOT_DIFFICULTY), scheduler);
        m.setTurnTimeoutMs(30_000);
        m.setShopTimeoutMs(30_000);
        return m;
    }

    private record Joined(String playerId, FakeMessageSink sink) {
    }

    private Joined join(Match match, String name) {
        FakeMessageSink sink = new FakeMessageSink();
        Match.JoinResult result = match.addPlayer(name, sink);
        assertTrue(result.success(), "join should succeed: " + result.errorReason());
        return new Joined(result.playerId(), sink);
    }

    @Test
    @Timeout(10)
    void purchaseIsRejectedOutsideShopPhase() {
        Match match = newMatch("m-shop-phase");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);

        Match.PurchaseOutcome outcome = match.purchase(p1.playerId(), "heavy_cannonball", "WEAPON", 1);
        assertFalse(outcome.accepted());
        assertEquals("NOT_SHOP_PHASE", outcome.rejectReason());
    }

    @Test
    @Timeout(10)
    void successfulPurchaseDeductsCashAndGrowsLoadoutAndDecrementsStock() {
        Match match = newMatch("m-shop-buy");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();

        match.debugSetCash(p1.playerId(), 1000);
        int startingQty = match.loadoutQtyOf(p1.playerId(), "heavy_cannonball");

        Match.PurchaseOutcome outcome = match.purchase(p1.playerId(), "heavy_cannonball", "WEAPON", 2);
        assertTrue(outcome.accepted(), outcome.rejectReason());

        // heavy_cannonball price is 150 (WeaponDef.HEAVY_CANNONBALL) -> 2x = 300.
        assertEquals(1000 - 300, match.cashOf(p1.playerId()));
        assertEquals(startingQty + 2, match.loadoutQtyOf(p1.playerId(), "heavy_cannonball"));

        // heavy_cannonball's shopStock is 10 (WeaponDef.HEAVY_CANNONBALL) -> 10-2=8.
        assertEquals(8, outcome.update().stockRemaining.get("heavy_cannonball"));
    }

    @Test
    @Timeout(10)
    void purchaseIsRejectedWhenCashIsInsufficient() {
        Match match = newMatch("m-shop-poor");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();

        match.debugSetCash(p1.playerId(), 10); // nuke costs 600
        Match.PurchaseOutcome outcome = match.purchase(p1.playerId(), "nuke", "WEAPON", 1);

        assertFalse(outcome.accepted());
        assertEquals("INSUFFICIENT_CASH", outcome.rejectReason());
        assertEquals(10, match.cashOf(p1.playerId()), "a rejected purchase must not touch cash");
    }

    @Test
    @Timeout(10)
    void basicShellIsNotPurchasableSinceItsAlreadyUnlimited() {
        Match match = newMatch("m-shop-basic-shell");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();

        match.debugSetCash(p1.playerId(), 100_000);
        Match.PurchaseOutcome outcome = match.purchase(p1.playerId(), "basic_shell", "WEAPON", 1);

        assertFalse(outcome.accepted());
        assertEquals("INVALID_ITEM", outcome.rejectReason());
    }

    @Test
    @Timeout(10)
    void unknownItemAndInvalidQuantityAreRejected() {
        Match match = newMatch("m-shop-invalid");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();
        match.debugSetCash(p1.playerId(), 100_000);

        assertEquals("INVALID_ITEM", match.purchase(p1.playerId(), "totally_fake_item", "WEAPON", 1).rejectReason());
        assertEquals("INVALID_QUANTITY", match.purchase(p1.playerId(), "heavy_cannonball", "WEAPON", 0).rejectReason());
        assertEquals("INVALID_QUANTITY", match.purchase(p1.playerId(), "heavy_cannonball", "WEAPON", -3).rejectReason());
    }

    @Test
    @Timeout(10)
    void shieldsArePurchasableTheSameWayAsWeapons() {
        Match match = newMatch("m-shop-shield");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();
        match.debugSetCash(p1.playerId(), 1000);

        int startingQty = match.loadoutQtyOf(p1.playerId(), "absorb_shield");
        Match.PurchaseOutcome outcome = match.purchase(p1.playerId(), "absorb_shield", "SHIELD", 1);

        assertTrue(outcome.accepted(), outcome.rejectReason());
        assertEquals(1000 - 200, match.cashOf(p1.playerId())); // absorb_shield price 200
        assertEquals(startingQty + 1, match.loadoutQtyOf(p1.playerId(), "absorb_shield"));
    }

    @Test
    @Timeout(10)
    void stockPoolIsSharedAcrossPlayersAndBlocksOverdraw() {
        Match match = newMatch("m-shop-stock");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();
        match.debugSetCash(p1.playerId(), 100_000);
        match.debugSetCash(p2.playerId(), 100_000);

        // nuke's shopStock is 3 (WeaponDef.NUKE): drain it via two different
        // players' purchases to prove the pool is match-wide, not per-player.
        assertTrue(match.purchase(p1.playerId(), "nuke", "WEAPON", 2).accepted());
        assertTrue(match.purchase(p2.playerId(), "nuke", "WEAPON", 1).accepted());

        Match.PurchaseOutcome depleted = match.purchase(p1.playerId(), "nuke", "WEAPON", 1);
        assertFalse(depleted.accepted());
        assertEquals("OUT_OF_STOCK", depleted.rejectReason());
    }

    @Test
    @Timeout(10)
    void shopOpenedPriceListExcludesBasicShellAndReportsFreshStock() {
        Match match = newMatch("m-shop-pricelist");
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        match.debugOpenShop();

        var opened = p1.sink().lastPayloadOfType("ShopOpened");
        assertNotNull(opened);
        var priceList = opened.get("priceList");
        assertTrue(priceList.isArray());

        boolean hasBasicShell = false;
        boolean hasNuke = false;
        for (var entry : priceList) {
            String itemId = entry.get("itemId").asText();
            if (itemId.equals("basic_shell")) {
                hasBasicShell = true;
            }
            if (itemId.equals("nuke")) {
                hasNuke = true;
                assertEquals(600, entry.get("price").asInt());
                assertEquals(3, entry.get("stock").asInt());
            }
        }
        assertFalse(hasBasicShell, "basic_shell is already unlimited, shouldn't be in the shop");
        assertTrue(hasNuke, "expected nuke in the price list");
    }

    @Test
    @Timeout(10)
    void shopTimeoutAdvancesToNextRoundRegardlessOfPurchases() {
        Match match = newMatch("m-shop-timeout");
        match.setShopTimeoutMs(100);
        Joined p1 = join(match, "P1");
        Joined p2 = join(match, "P2");
        match.setReady(p1.playerId(), true);
        match.setReady(p2.playerId(), true);
        int roundBefore = match.roundNumber();

        match.debugOpenShop();
        assertEquals(Match.Status.SHOP, match.status());

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && match.status() == Match.Status.SHOP) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        assertEquals(Match.Status.IN_PROGRESS, match.status());
        assertEquals(roundBefore + 1, match.roundNumber());
    }
}
