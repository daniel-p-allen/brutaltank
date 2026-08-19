package com.brutaltank.domain.player;

/** A player's tank: position (derived from terrain height at spawn) and health. */
public final class Tank {

    public double x;
    public double y;
    public double health;
    public boolean alive;

    public Tank(double x, double y, double health) {
        this.x = x;
        this.y = y;
        this.health = health;
        this.alive = health > 0;
    }
}
