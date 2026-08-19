package com.brutaltank.domain.weapon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static weapon stats for the full M3 roster (PLAN.md 4.4: 10 weapons).
 * Behavior differences beyond raw blast/damage numbers are expressed via
 * {@link Behavior} and a handful of optional per-weapon modifiers that
 * default to "no effect" so {@link Behavior#STANDARD} weapons only need to
 * set id/blastRadius/centerDamage/price/defaultQty.
 */
public record WeaponDef(
        String weaponId,
        Behavior behavior,
        double blastRadius,
        double centerDamage,
        int price,
        int defaultQty,
        double powerScaleMultiplier,
        double gravityMultiplier,
        double craterDepthMultiplier,
        double bombletBlastRadius) {

    /**
     * Behavior tag driving {@link ProjectileSim}/{@link com.brutaltank.match.Match}
     * dispatch. See PLAN.md 4.2/4.4 for the per-behavior description.
     */
    public enum Behavior {
        /** Plain ballistic arc; only stats (radius/damage/power/gravity) vary. */
        STANDARD,
        /** Small blast, deeper crater (terrain-shaping tool). */
        DIGGER,
        /** Continues through terrain up to a max penetration depth before detonating. */
        TUNNELING,
        /** Reflects off shallow-angle terrain hits, up to N bounces, before detonating. */
        BOUNCING,
        /** Splits into several children at the trajectory apex. */
        MIRV,
        /** Primary detonation plus several sideways bomblet detonations. */
        CLUSTER
    }

    /** Convenience constructor for weapons that don't need the optional modifiers. */
    public WeaponDef(String weaponId, Behavior behavior, double blastRadius, double centerDamage,
                      int price, int defaultQty) {
        this(weaponId, behavior, blastRadius, centerDamage, price, defaultQty, 1.0, 1.0, 1.0, 0.0);
    }

    // -----------------------------------------------------------------
    // PLAN.md 4.4 roster
    // -----------------------------------------------------------------

    public static final WeaponDef BASIC_SHELL =
            new WeaponDef("basic_shell", Behavior.STANDARD, 30.0, 25.0, 0, -1);

    public static final WeaponDef BABY_MISSILE =
            new WeaponDef("baby_missile", Behavior.STANDARD, 22.0, 18.0, 0, 5,
                    1.15, 1.0, 1.0, 0.0);

    public static final WeaponDef HEAVY_CANNONBALL =
            new WeaponDef("heavy_cannonball", Behavior.STANDARD, 45.0, 40.0, 150, 3,
                    0.85, 1.1, 1.0, 0.0);

    public static final WeaponDef MIRV =
            new WeaponDef("mirv", Behavior.MIRV, 25.0, 15.0, 300, 2);

    public static final WeaponDef NAPALM =
            new WeaponDef("napalm", Behavior.STANDARD, 50.0, 20.0, 250, 2);

    public static final WeaponDef TUNNELING_SHOT =
            new WeaponDef("tunneling_shot", Behavior.TUNNELING, 25.0, 30.0, 200, 2);

    public static final WeaponDef BOUNCING_BETTY =
            new WeaponDef("bouncing_betty", Behavior.BOUNCING, 30.0, 25.0, 220, 2);

    public static final WeaponDef CLUSTER_BOMB =
            new WeaponDef("cluster_bomb", Behavior.CLUSTER, 20.0, 20.0, 280, 2,
                    1.0, 1.0, 1.0, 12.0);

    public static final WeaponDef DIGGER =
            new WeaponDef("digger", Behavior.DIGGER, 20.0, 10.0, 120, 3,
                    1.0, 1.0, 1.8, 0.0);

    public static final WeaponDef NUKE =
            new WeaponDef("nuke", Behavior.STANDARD, 90.0, 70.0, 600, 1);

    private static final Map<String, WeaponDef> WEAPONS_BY_ID = buildWeaponIndex();

    private static Map<String, WeaponDef> buildWeaponIndex() {
        Map<String, WeaponDef> map = new LinkedHashMap<>();
        for (WeaponDef w : new WeaponDef[] {
                BASIC_SHELL, BABY_MISSILE, HEAVY_CANNONBALL, MIRV, NAPALM,
                TUNNELING_SHOT, BOUNCING_BETTY, CLUSTER_BOMB, DIGGER, NUKE
        }) {
            map.put(w.weaponId(), w);
        }
        return map;
    }

    public static WeaponDef byId(String weaponId) {
        return WEAPONS_BY_ID.get(weaponId);
    }

    /** All weapon defs, in a stable order — used to build the default starting loadout. */
    public static Map<String, WeaponDef> all() {
        return WEAPONS_BY_ID;
    }
}
