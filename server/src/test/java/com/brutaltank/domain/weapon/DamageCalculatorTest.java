package com.brutaltank.domain.weapon;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageCalculatorTest {

    private static final double BLAST_RADIUS = 30.0;
    private static final double CENTER_DAMAGE = 25.0;

    @Test
    void directHitAtCenterDealsFullDamageWithBonus() {
        List<DamageCalculator.TankState> tanks = List.of(
                new DamageCalculator.TankState("p-2", 100, 500, 100));

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                "p-1", 100, 500, BLAST_RADIUS, CENTER_DAMAGE, tanks);

        assertEquals(1, outcome.damageEvents.size());
        DamageCalculator.DamageResult result = outcome.damageEvents.get(0);
        // Direct hit (distance 0 <= 8) applies the x1.3 bonus on top of full falloff (1.0).
        assertEquals(CENTER_DAMAGE * 1.3, result.damage(), 0.001);
        assertEquals(100 - CENTER_DAMAGE * 1.3, result.newHealth(), 0.001);
        assertFalse(result.eliminated());
    }

    @Test
    void damageFallsOffWithDistanceAndZeroAtEdge() {
        List<DamageCalculator.TankState> tanks = List.of(
                new DamageCalculator.TankState("near", 115, 500, 100),   // distance 15, within blast, outside direct-hit
                new DamageCalculator.TankState("edge", 130, 500, 100));  // distance 30 == radius, falloff -> 0

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                "p-1", 100, 500, BLAST_RADIUS, CENTER_DAMAGE, tanks);

        double nearDamage = outcome.damageEvents.stream()
                .filter(d -> d.playerId().equals("near")).findFirst().orElseThrow().damage();
        double edgeDamage = outcome.damageEvents.stream()
                .filter(d -> d.playerId().equals("edge")).findFirst().orElseThrow().damage();

        assertTrue(nearDamage > 0 && nearDamage < CENTER_DAMAGE, "near damage=" + nearDamage);
        assertEquals(0.0, edgeDamage, 0.01);
    }

    @Test
    void tanksOutsideBlastRadiusAreUnaffected() {
        List<DamageCalculator.TankState> tanks = List.of(
                new DamageCalculator.TankState("far", 500, 500, 100));

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                "p-1", 100, 500, BLAST_RADIUS, CENTER_DAMAGE, tanks);

        assertTrue(outcome.damageEvents.isEmpty());
        assertTrue(outcome.cashEarned.isEmpty());
    }

    @Test
    void eliminationTriggersAtHealthZero() {
        List<DamageCalculator.TankState> tanks = List.of(
                new DamageCalculator.TankState("p-2", 100, 500, 10));

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                "p-1", 100, 500, BLAST_RADIUS, CENTER_DAMAGE, tanks);

        DamageCalculator.DamageResult result = outcome.damageEvents.get(0);
        assertEquals(0.0, result.newHealth());
        assertTrue(result.eliminated());
    }

    @Test
    void cashRewardedToShooterForDamageToOthersOnly() {
        List<DamageCalculator.TankState> tanks = List.of(
                new DamageCalculator.TankState("p-1", 100, 500, 100), // shooter itself, no self-cash
                new DamageCalculator.TankState("p-2", 105, 500, 100));

        DamageCalculator.Outcome outcome = DamageCalculator.resolve(
                "p-1", 100, 500, BLAST_RADIUS, CENTER_DAMAGE, tanks);

        assertEquals(1, outcome.cashEarned.size());
        assertEquals("p-1", outcome.cashEarned.get(0).playerId());

        double p2Damage = outcome.damageEvents.stream()
                .filter(d -> d.playerId().equals("p-2")).findFirst().orElseThrow().damage();
        int expectedCash = (int) Math.round(p2Damage * DamageCalculator.CASH_PER_DAMAGE);
        assertEquals(expectedCash, outcome.cashEarned.get(0).amount());
    }
}
