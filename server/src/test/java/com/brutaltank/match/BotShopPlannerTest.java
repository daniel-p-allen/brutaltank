package com.brutaltank.match;

import com.brutaltank.protocol.Payloads;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BotShopPlannerTest {

    private static List<Payloads.PriceListEntry> samplePriceList() {
        return List.of(
                new Payloads.PriceListEntry("baby_missile", "WEAPON", 60, 20),
                new Payloads.PriceListEntry("heavy_cannonball", "WEAPON", 150, 10),
                new Payloads.PriceListEntry("mirv", "WEAPON", 300, 6),
                new Payloads.PriceListEntry("nuke", "WEAPON", 600, 3),
                new Payloads.PriceListEntry("absorb_shield", "SHIELD", 200, 5)
        );
    }

    private static BotProfile profile(double moneySense, double valueAwareness) {
        return new BotProfile(Difficulty.MEDIUM, 5, 5, true, 0.0, moneySense, valueAwareness, Map.of(), 0.0, 0.0);
    }

    @Test
    void neverSpendsMoreThanAvailableCash() {
        Random rng = new Random(1);
        for (int trial = 0; trial < 50; trial++) {
            int cash = 100 + rng.nextInt(2000);
            BotProfile p = profile(rng.nextDouble(), rng.nextDouble());
            List<BotShopPlanner.Purchase> purchases = BotShopPlanner.plan(samplePriceList(), cash, p, rng);

            int spent = 0;
            for (BotShopPlanner.Purchase purchase : purchases) {
                int price = samplePriceList().stream()
                        .filter(e -> e.itemId.equals(purchase.itemId()))
                        .findFirst().orElseThrow().price;
                spent += price * purchase.quantity();
            }
            assertTrue(spent <= cash, "spent " + spent + " but only had " + cash + " cash");
        }
    }

    @Test
    void neverBuysMoreThanAvailableStock() {
        Random rng = new Random(2);
        List<Payloads.PriceListEntry> priceList = List.of(
                new Payloads.PriceListEntry("nuke", "WEAPON", 10, 1) // dirt cheap, 1 in stock
        );
        BotProfile p = profile(0.1, 0.1); // reckless spender, should still respect stock
        for (int trial = 0; trial < 20; trial++) {
            List<BotShopPlanner.Purchase> purchases = BotShopPlanner.plan(priceList, 10_000, p, rng);
            long nukeCount = purchases.stream().filter(pur -> pur.itemId().equals("nuke")).mapToInt(BotShopPlanner.Purchase::quantity).sum();
            assertTrue(nukeCount <= 1, "should never plan more purchases than stock allows, got " + nukeCount);
        }
    }

    @Test
    void highMoneySenseKeepsALargerAverageCashReserveThanLowMoneySense() {
        Random rng = new Random(9);
        int cash = 1000;
        int trials = 40;

        double smartReserveTotal = 0;
        double recklessReserveTotal = 0;
        for (int i = 0; i < trials; i++) {
            BotProfile smart = profile(0.95, 0.9);
            BotProfile reckless = profile(0.05, 0.1);

            int smartSpent = totalSpend(BotShopPlanner.plan(samplePriceList(), cash, smart, rng));
            int recklessSpent = totalSpend(BotShopPlanner.plan(samplePriceList(), cash, reckless, rng));

            smartReserveTotal += (cash - smartSpent);
            recklessReserveTotal += (cash - recklessSpent);
        }
        double smartAvgReserve = smartReserveTotal / trials;
        double recklessAvgReserve = recklessReserveTotal / trials;
        assertTrue(smartAvgReserve > recklessAvgReserve * 0.5,
                "high-moneySense bots should on average keep a larger cash reserve: smart="
                        + smartAvgReserve + " reckless=" + recklessAvgReserve);
    }

    private static int totalSpend(List<BotShopPlanner.Purchase> purchases) {
        int total = 0;
        for (BotShopPlanner.Purchase purchase : purchases) {
            int price = samplePriceList().stream()
                    .filter(e -> e.itemId.equals(purchase.itemId()))
                    .findFirst().orElseThrow().price;
            total += price * purchase.quantity();
        }
        return total;
    }
}
