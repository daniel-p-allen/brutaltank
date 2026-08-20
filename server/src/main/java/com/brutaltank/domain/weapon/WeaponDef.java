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
        double bombletBlastRadius,
        int shopStock,
        double homingStrength) {

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
        this(weaponId, behavior, blastRadius, centerDamage, price, defaultQty, 1.0, 1.0, 1.0, 0.0, 8, 0.0);
    }

    // -----------------------------------------------------------------
    // PLAN.md 4.4 roster
    // -----------------------------------------------------------------

    // basic_shell is never in the shop (defaultQty -1 == unlimited already),
    // so its shopStock is irrelevant; every other weapon's shopStock is a
    // M4 addition (not in PLAN.md's original table) — a shared pool across
    // the whole match's shop phase (not per-player), replenished fresh each
    // round, sized roughly in inverse proportion to power/price so the
    // rarer/stronger weapons create real "buy it now or lose it" tactics.
    public static final WeaponDef BASIC_SHELL =
            new WeaponDef("basic_shell", Behavior.STANDARD, 30.0, 25.0, 0, -1,
                    1.0, 1.0, 1.0, 0.0, 0, 0.0);

    // homingStrength 0.06 (per user feedback: "a little bit of homing just
    // in the end, 6%") — see ProjectileSim.HOMING_ACTIVATION_RADIUS for the
    // "only near the end, only near a target" gating.
    public static final WeaponDef BABY_MISSILE =
            new WeaponDef("baby_missile", Behavior.STANDARD, 22.0, 18.0, 0, 5,
                    1.15, 1.0, 1.0, 0.0, 20, 0.06);

    public static final WeaponDef HEAVY_CANNONBALL =
            new WeaponDef("heavy_cannonball", Behavior.STANDARD, 45.0, 40.0, 150, 3,
                    0.85, 1.1, 1.3, 0.0, 10, 0.0);

    public static final WeaponDef MIRV =
            new WeaponDef("mirv", Behavior.MIRV, 25.0, 15.0, 300, 2,
                    1.0, 1.0, 1.0, 0.0, 6, 0.0);

    // Terrain signature (per Scorched Earth's manual: napalm "splashes...and
    // bursts into hot flame", it doesn't excavate) is a wide, shallow burn
    // dish rather than a normal pit: low craterDepthMultiplier against its
    // already-wide blastRadius.
    public static final WeaponDef NAPALM =
            new WeaponDef("napalm", Behavior.STANDARD, 50.0, 20.0, 250, 2,
                    1.0, 1.0, 0.2, 0.0, 8, 0.0);

    public static final WeaponDef TUNNELING_SHOT =
            new WeaponDef("tunneling_shot", Behavior.TUNNELING, 25.0, 30.0, 200, 2,
                    1.0, 1.0, 1.0, 0.0, 8, 0.0);

    public static final WeaponDef BOUNCING_BETTY =
            new WeaponDef("bouncing_betty", Behavior.BOUNCING, 30.0, 25.0, 220, 2,
                    1.0, 1.0, 1.0, 0.0, 8, 0.0);

    // bombletBlastRadius bumped from 12 (per user feedback: bomblet craters
    // read as barely-there dots, should be "obvious decent holes") — 20 now
    // matches the primary's own blastRadius, so all 5 detonation points read
    // as comparable craters rather than one real hit plus 4 dents.
    public static final WeaponDef CLUSTER_BOMB =
            new WeaponDef("cluster_bomb", Behavior.CLUSTER, 20.0, 20.0, 280, 2,
                    1.0, 1.0, 1.0, 20.0, 6, 0.0);

    // Digger now tunnels along its trajectory (Behavior.TUNNELING, like
    // Tunneling Shot) rather than detonating on first contact — but with its
    // own much shallower penetration cap (Match.DIGGER_MAX_PENETRATION,
    // 70 vs. Tunneling Shot's 160) and a bigger final crater: blastRadius
    // bumped 20->38 for a genuinely "big hole" (per user feedback), with
    // craterDepthMultiplier pulled back from 3.0 to 1.8 so maxDepth
    // (radius*0.8*multiplier ~= 55) stays a big-but-not-Nuke-sized pit
    // rather than scaling the old narrow-shaft multiplier straight up.
    public static final WeaponDef DIGGER =
            new WeaponDef("digger", Behavior.TUNNELING, 38.0, 10.0, 120, 3,
                    1.0, 1.0, 1.8, 0.0, 10, 0.0);

    // Biggest weapon gets the most dramatic terrain: with Terrain.FLOOR's
    // extended headroom, a center hit now plunges close to the true bottom
    // of the screen. Also the scarcest shop item, by design.
    // centerDamage bumped from 70 (per user feedback: "make it pretty
    // damaging if you hit") — 95 plus the existing x1.3 direct-hit bonus
    // makes a close center hit close to lethal on its own against full health.
    public static final WeaponDef NUKE =
            new WeaponDef("nuke", Behavior.STANDARD, 90.0, 95.0, 600, 1,
                    1.0, 1.0, 2.3, 0.0, 3, 0.0);

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
