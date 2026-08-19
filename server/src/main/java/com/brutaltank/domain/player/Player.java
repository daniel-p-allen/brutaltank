package com.brutaltank.domain.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** A connected player's persistent state within a match. */
public final class Player {

    public final String playerId;
    public final String displayName;
    public final String color;
    public int cash;
    public final Map<String, Integer> loadout = new LinkedHashMap<>();
    public String activeShieldId;
    /**
     * Cumulative damage absorbed by the current Absorb shield activation
     * (M3, PLAN.md 4.4: "up to 80 cumulative absorption before breaking").
     * Reset to 0 whenever a new shield is activated; unused by other shields.
     */
    public double shieldAbsorbedSoFar;
    public final Tank tank;

    public Player(String playerId, String displayName, String color, int cash, Tank tank) {
        this.playerId = playerId;
        this.displayName = displayName;
        this.color = color;
        this.cash = cash;
        this.tank = tank;
    }
}
