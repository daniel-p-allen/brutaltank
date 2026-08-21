package com.brutaltank.domain.weapon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static shield stats, PLAN.md 4.4. Shields are activated by spending a turn
 * (see {@code Match.resolveShot}'s shield branch) rather than firing a
 * projectile; the mitigation math each id implies lives in
 * {@code Match}'s damage post-processing, kept out of {@link DamageCalculator}
 * per the task spec ("don't rewrite DamageCalculator's core falloff math").
 * {@code shopStock} (M4) is a shared pool across the whole match's shop
 * phase, not per-player — see {@code WeaponDef}'s equivalent field.
 */
public record ShieldDef(String shieldId, double damageMultiplier, int price, int shopStock) {

    /** -50% incoming damage while active, breaks once cumulative absorption exceeds 160. */
    public static final ShieldDef ABSORB = new ShieldDef("absorb_shield", 0.5, 200, 5);
    // Doubled 80->160 alongside WeaponDef's roster-wide damage doubling
    // (2026-08-22), to preserve the original ~N-hits-before-breaking
    // durability — otherwise Absorb would break in about half as many hits
    // as it used to, an unintended side effect of the damage change rather
    // than a deliberate shield retune.
    public static final double ABSORB_BREAK_THRESHOLD = 160.0;

    /** First direct hit fully negated then breaks; near-miss splash still applies at 60%. */
    public static final ShieldDef DEFLECT = new ShieldDef("deflect_shield", 0.0, 250, 5);
    public static final double DEFLECT_NEAR_MISS_MULTIPLIER = 0.6;

    /** -30% damage taken, 20% of blocked damage returned as bonus cash. Never breaks. */
    public static final ShieldDef REFLECT = new ShieldDef("reflect_shield", 0.7, 300, 5);
    public static final double REFLECT_CASHBACK_FRACTION = 0.2;

    private static final Map<String, ShieldDef> SHIELDS_BY_ID = buildIndex();

    private static Map<String, ShieldDef> buildIndex() {
        Map<String, ShieldDef> map = new LinkedHashMap<>();
        for (ShieldDef s : new ShieldDef[] {ABSORB, DEFLECT, REFLECT}) {
            map.put(s.shieldId(), s);
        }
        return map;
    }

    public static ShieldDef byId(String shieldId) {
        return SHIELDS_BY_ID.get(shieldId);
    }

    public static boolean isShieldId(String id) {
        return SHIELDS_BY_ID.containsKey(id);
    }

    /** All shield defs, in a stable order — used to build the default starting loadout. */
    public static Map<String, ShieldDef> all() {
        return SHIELDS_BY_ID;
    }
}
