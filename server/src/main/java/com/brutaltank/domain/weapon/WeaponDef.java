package com.brutaltank.domain.weapon;

/**
 * Static weapon stats. M1 scope only defines the Basic Shell (PLAN.md 4.4);
 * the other 9 weapons are added in M3.
 */
public record WeaponDef(String weaponId, double blastRadius, double centerDamage) {

    public static final WeaponDef BASIC_SHELL = new WeaponDef("basic_shell", 30.0, 25.0);

    public static WeaponDef byId(String weaponId) {
        if (BASIC_SHELL.weaponId().equals(weaponId)) {
            return BASIC_SHELL;
        }
        return null;
    }
}
