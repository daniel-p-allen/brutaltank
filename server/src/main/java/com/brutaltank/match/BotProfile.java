package com.brutaltank.match;

import com.brutaltank.domain.weapon.WeaponDef;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * A single bot's personality/skill, randomized once at bot creation from a
 * {@link Difficulty} tier (per live playtest request: bots must "make
 * mistakes", "not get every shot right", and vary "how good they are" —
 * this is the knob for all of that). Every field is a *range* per tier,
 * jittered per-bot, so even two same-tier bots aren't identical, and
 * {@link Difficulty#MIXED} resolves to a randomly-picked concrete tier per
 * bot rather than one blended-average skill level.
 */
public record BotProfile(
        Difficulty difficulty,
        double maxAngleErrorDeg,
        double maxPowerError,
        boolean windAware,
        double wildMissChance,
        double moneySense,
        double valueAwareness,
        Map<String, Double> weaponPreference,
        double shieldCaution,
        double targetFocus) {

    public static BotProfile forDifficulty(Difficulty requested, Random rng) {
        Difficulty resolved = requested == null || requested == Difficulty.MIXED
                ? pickRandomTier(rng)
                : requested;

        double angleErrorLo, angleErrorHi, powerErrorLo, powerErrorHi, wildMissBase;
        double moneySenseLo, moneySenseHi, valueAwarenessLo, valueAwarenessHi;
        double windAwareChance;
        switch (resolved) {
            case EASY -> {
                // Power error is scaled down from the original design pass:
                // this game's physics (POWER_SCALE=12, multi-second flight
                // times) makes even a handful of power units a large swing
                // in landing distance, so "large error" here needs to be
                // small in absolute power-unit terms to still read as
                // "usually roughly the right area" rather than "always
                // lands nowhere near the target."
                angleErrorLo = 12;
                angleErrorHi = 25;
                powerErrorLo = 6;
                powerErrorHi = 12;
                wildMissBase = 0.12;
                windAwareChance = 0.0;
                moneySenseLo = 0.0;
                moneySenseHi = 0.35;
                valueAwarenessLo = 0.0;
                valueAwarenessHi = 0.35;
            }
            case HARD -> {
                angleErrorLo = 1;
                angleErrorHi = 5;
                powerErrorLo = 0.5;
                powerErrorHi = 2;
                wildMissBase = 0.01;
                windAwareChance = 1.0;
                moneySenseLo = 0.65;
                moneySenseHi = 1.0;
                valueAwarenessLo = 0.65;
                valueAwarenessHi = 1.0;
            }
            default -> { // MEDIUM
                angleErrorLo = 5;
                angleErrorHi = 12;
                powerErrorLo = 2;
                powerErrorHi = 5;
                wildMissBase = 0.05;
                windAwareChance = 0.5;
                moneySenseLo = 0.35;
                moneySenseHi = 0.65;
                valueAwarenessLo = 0.35;
                valueAwarenessHi = 0.65;
            }
        }

        Map<String, Double> preference = new LinkedHashMap<>();
        for (String weaponId : WeaponDef.all().keySet()) {
            preference.put(weaponId, 0.3 + rng.nextDouble() * 1.2);
        }

        return new BotProfile(
                resolved,
                lerpRandom(rng, angleErrorLo, angleErrorHi),
                lerpRandom(rng, powerErrorLo, powerErrorHi),
                rng.nextDouble() < windAwareChance,
                wildMissBase,
                lerpRandom(rng, moneySenseLo, moneySenseHi),
                lerpRandom(rng, valueAwarenessLo, valueAwarenessHi),
                preference,
                0.3 + rng.nextDouble() * 0.5,
                0.2 + rng.nextDouble() * 0.4);
    }

    private static Difficulty pickRandomTier(Random rng) {
        Difficulty[] tiers = {Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD};
        return tiers[rng.nextInt(tiers.length)];
    }

    private static double lerpRandom(Random rng, double lo, double hi) {
        return lo + rng.nextDouble() * (hi - lo);
    }
}
