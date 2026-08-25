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
    // All centerDamage values doubled across the roster (per user feedback,
    // 2026-08-22: "every weapon needs to do double the damage") — every
    // other stat (blastRadius, price, crater shape, etc.) unchanged.
    public static final WeaponDef BASIC_SHELL =
            new WeaponDef("basic_shell", Behavior.STANDARD, 30.0, 50.0, 0, -1,
                    1.0, 1.0, 1.0, 0.0, 0, 0.0);

    // homingStrength 0.06 (per user feedback: "a little bit of homing just
    // in the end, 6%") — see ProjectileSim.HOMING_ACTIVATION_RADIUS for the
    // "only near the end, only near a target" gating.
    // gravityMultiplier 0.85 (per user feedback, 2026-08-22: "each weapon
    // should sit in a weight class consistent with what it is" — a guided
    // missile is the roster's lightest item, weight-class 1 of 3; see
    // client WEAPON_CATALOG's weightClass, kept in sync by hand same as
    // label/id already are) — flies flatter/farther than a dumb-fired shell.
    // price 0->60 (per live-playtest feedback, 2026-08-25: "baby missiles
    // are bloody free"): the starting loadout (defaultQty 5) is still free —
    // WeaponDef.price only matters for shop resupply, and unpriced terminal
    // homing ammo was a clear balance hole. Then 60->200 (explicit user
    // request, 2026-08-25) -- now priced above Digger (120) and MIRV (per
    // its own price), reflecting its homing/terminal-guidance advantage
    // rather than just its lightest/weakest-blast stats.
    public static final WeaponDef BABY_MISSILE =
            new WeaponDef("baby_missile", Behavior.STANDARD, 22.0, 36.0, 200, 5,
                    1.15, 0.85, 1.0, 0.0, 20, 0.06);

    // gravityMultiplier bumped 1.1->1.2 (weight-class 3 of 3, heaviest tier
    // alongside Digger/Nuke — see BABY_MISSILE's comment). Range at max
    // power/45deg is still ~3941 world-units, comfortably clearing the
    // 1600-unit map (per user request: "make sure the heaviest item can be
    // thrown clear across the field").
    // centerDamage/blastRadius pulled back 80/45 -> 60/38 (per live-playtest
    // feedback, 2026-08-25: "tone down the heavy cannonball a bit.. it is
    // devastating") — still the roster's second-hardest hitter behind Nuke,
    // just not disproportionate to its $150 price relative to pricier
    // weapons like Napalm/Tunneling Shot/Bouncing Betty.
    public static final WeaponDef HEAVY_CANNONBALL =
            new WeaponDef("heavy_cannonball", Behavior.STANDARD, 38.0, 60.0, 150, 3,
                    0.85, 1.2, 1.3, 0.0, 10, 0.0);

    public static final WeaponDef MIRV =
            new WeaponDef("mirv", Behavior.MIRV, 25.0, 30.0, 300, 2,
                    1.0, 1.0, 1.0, 0.0, 6, 0.0);

    // Terrain signature (per Scorched Earth's manual: napalm "splashes...and
    // bursts into hot flame", it doesn't excavate) is a wide, shallow burn
    // dish rather than a normal pit: low craterDepthMultiplier against its
    // already-wide blastRadius.
    public static final WeaponDef NAPALM =
            new WeaponDef("napalm", Behavior.STANDARD, 50.0, 40.0, 250, 2,
                    1.0, 1.0, 0.2, 0.0, 8, 0.0);

    public static final WeaponDef TUNNELING_SHOT =
            new WeaponDef("tunneling_shot", Behavior.TUNNELING, 25.0, 60.0, 200, 2,
                    1.0, 1.0, 1.0, 0.0, 8, 0.0);

    public static final WeaponDef BOUNCING_BETTY =
            new WeaponDef("bouncing_betty", Behavior.BOUNCING, 30.0, 50.0, 220, 2,
                    1.0, 1.0, 1.0, 0.0, 8, 0.0);

    // bombletBlastRadius bumped from 12 (per user feedback: bomblet craters
    // read as barely-there dots, should be "obvious decent holes") — 20 now
    // matches the primary's own blastRadius, so all 5 detonation points read
    // as comparable craters rather than one real hit plus 4 dents.
    public static final WeaponDef CLUSTER_BOMB =
            new WeaponDef("cluster_bomb", Behavior.CLUSTER, 20.0, 40.0, 280, 2,
                    1.0, 1.0, 1.0, 20.0, 6, 0.0);

    // Digger now tunnels along its trajectory (Behavior.TUNNELING, like
    // Tunneling Shot) rather than detonating on first contact — but with its
    // own much shallower penetration cap (Match.DIGGER_MAX_PENETRATION,
    // 70 vs. Tunneling Shot's 160) and a bigger final crater: blastRadius
    // bumped 20->38 for a genuinely "big hole" (per user feedback), with
    // craterDepthMultiplier pulled back from 3.0 to 1.8 so maxDepth
    // (radius*0.8*multiplier ~= 55) stays a big-but-not-Nuke-sized pit
    // rather than scaling the old narrow-shaft multiplier straight up.
    // gravityMultiplier 1.15 (weight-class 3 of 3 — a drill-tipped
    // penetrator, heaviest tier alongside Heavy Cannonball/Nuke). Range at
    // max power/45deg is still ~5692 world-units, well clear of the
    // 1600-unit map.
    public static final WeaponDef DIGGER =
            new WeaponDef("digger", Behavior.TUNNELING, 38.0, 20.0, 120, 3,
                    1.0, 1.15, 1.8, 0.0, 10, 0.0);

    // Biggest weapon gets the most dramatic terrain: with Terrain.FLOOR's
    // extended headroom, a center hit now plunges close to the true bottom
    // of the screen. Also the scarcest shop item, by design.
    // centerDamage: was bumped 70->95 (per user feedback: "make it pretty
    // damaging if you hit"), now doubled again to 190 along with the rest
    // of the roster — comfortably lethal at range via falloff, not just on
    // a direct hit.
    // gravityMultiplier 1.25 (weight-class 3 of 3 — the heaviest item in the
    // roster). Range at max power/45deg is still ~5236 world-units, well
    // clear of the 1600-unit map (per user request: "make sure the heaviest
    // item can be thrown clear across the field").
    public static final WeaponDef NUKE =
            new WeaponDef("nuke", Behavior.STANDARD, 90.0, 190.0, 600, 1,
                    1.0, 1.25, 2.3, 0.0, 3, 0.0);

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
