package com.brutaltank.match;

import com.brutaltank.domain.weapon.ShieldDef;
import com.brutaltank.domain.weapon.WeaponDef;
import com.brutaltank.protocol.Payloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Pure decision logic for a bot's shop-phase purchases. Stateless: given a
 * price-list/cash/profile snapshot, returns the purchases to make. Per live
 * playtest request ("perhaps they are smart with money or dumb so they mess
 * up the shop"), {@link BotProfile#moneySense}/{@link
 * BotProfile#valueAwareness} drive both how much of their cash a bot risks
 * and how sensibly they pick what to spend it on.
 */
final class BotShopPlanner {

    private BotShopPlanner() {
    }

    record Purchase(String itemId, String itemType, int quantity) {
    }

    private static final int MAX_PURCHASES_PER_VISIT = 6;

    static List<Purchase> plan(List<Payloads.PriceListEntry> priceList, int cash, BotProfile profile, Random rng) {
        List<Purchase> purchases = new ArrayList<>();
        if (priceList.isEmpty() || cash <= 0) {
            return purchases;
        }

        // Smart bots keep a reserve and spend a fairly narrow, sensible
        // fraction; dumb bots roll from a much wider range -- sometimes
        // blowing the whole budget, sometimes buying almost nothing.
        double spendLo = 0.40 - 0.40 * (1 - profile.moneySense());
        double spendHi = 0.70 + 0.30 * (1 - profile.moneySense());
        double spendFraction = spendLo + rng.nextDouble() * (spendHi - spendLo);
        int budget = (int) Math.round(cash * Math.max(0, Math.min(1, spendFraction)));

        // Live, mutable view of remaining stock so this planner's own picks
        // don't outrun what's actually available (the caller still re-checks
        // against the real match-wide pool via Match.purchase, this is just
        // to keep the planned list internally consistent).
        java.util.Map<String, Integer> remainingStock = new java.util.HashMap<>();
        for (Payloads.PriceListEntry e : priceList) {
            remainingStock.put(e.itemId, e.stock);
        }

        int remainingBudget = budget;
        for (int i = 0; i < MAX_PURCHASES_PER_VISIT && remainingBudget > 0; i++) {
            int budgetSnapshot = remainingBudget;
            List<Payloads.PriceListEntry> affordable = priceList.stream()
                    .filter(e -> e.price > 0 && e.price <= budgetSnapshot && remainingStock.getOrDefault(e.itemId, 0) > 0)
                    .toList();
            if (affordable.isEmpty()) {
                break;
            }

            Payloads.PriceListEntry choice = rng.nextDouble() < profile.valueAwareness()
                    ? pickByValue(affordable, rng)
                    : affordable.get(rng.nextInt(affordable.size()));

            purchases.add(new Purchase(choice.itemId, choice.itemType, 1));
            remainingBudget -= choice.price;
            remainingStock.merge(choice.itemId, -1, Integer::sum);

            // Dumb bots are also more likely to stop shopping early even with
            // budget left over -- "forgetting" to spend, not just overspending.
            double stopChance = 0.10 + 0.30 * (1 - profile.moneySense());
            if (rng.nextDouble() < stopChance) {
                break;
            }
        }
        return purchases;
    }

    private static Payloads.PriceListEntry pickByValue(List<Payloads.PriceListEntry> affordable, Random rng) {
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (Payloads.PriceListEntry e : affordable) {
            double value = valueScore(e);
            weights.add(value);
            total += value;
        }
        if (total <= 0) {
            return affordable.get(rng.nextInt(affordable.size()));
        }
        double roll = rng.nextDouble() * total;
        double cumulative = 0;
        for (int i = 0; i < affordable.size(); i++) {
            cumulative += weights.get(i);
            if (roll <= cumulative) {
                return affordable.get(i);
            }
        }
        return affordable.get(affordable.size() - 1);
    }

    /** Rough "bang for buck" heuristic: damage/price for weapons, mitigation/price (scaled) for shields. */
    private static double valueScore(Payloads.PriceListEntry entry) {
        if ("SHIELD".equals(entry.itemType)) {
            ShieldDef shield = ShieldDef.byId(entry.itemId);
            if (shield == null || entry.price <= 0) {
                return 0.01;
            }
            return ((1.0 - shield.damageMultiplier()) / entry.price) * 100.0;
        }
        WeaponDef weapon = WeaponDef.byId(entry.itemId);
        if (weapon == null || entry.price <= 0) {
            return 0.01;
        }
        return weapon.centerDamage() / entry.price;
    }
}
