import { describe, it, expect, beforeEach } from 'vitest';
import { get } from 'svelte/store';
import {
	weaponSelectStore,
	WEAPON_CATALOG,
	formatQuantity,
	hasAmmo,
	damagePieColorAt,
	damagePieGradient,
	MAX_DAMAGE_RATING
} from './weaponSelectStore';

describe('weaponSelectStore', () => {
	beforeEach(() => {
		weaponSelectStore.reset();
	});

	it('defaults to basic_shell', () => {
		expect(get(weaponSelectStore)).toBe('basic_shell');
	});

	it('select() updates the current selection', () => {
		weaponSelectStore.select('mirv');
		expect(get(weaponSelectStore)).toBe('mirv');
	});

	it('reset() restores the default selection', () => {
		weaponSelectStore.select('nuke');
		weaponSelectStore.reset();
		expect(get(weaponSelectStore)).toBe('basic_shell');
	});

	it('catalog lists all 10 weapons + 3 shields', () => {
		const weapons = WEAPON_CATALOG.filter((e) => !e.isShield);
		const shields = WEAPON_CATALOG.filter((e) => e.isShield);
		expect(weapons).toHaveLength(10);
		expect(shields).toHaveLength(3);
		expect(shields.map((s) => s.id)).toEqual(['absorb_shield', 'deflect_shield', 'reflect_shield']);
	});
});

describe('formatQuantity', () => {
	it('displays -1 as unlimited', () => {
		expect(formatQuantity(-1)).toBe('∞');
	});

	it('displays a real count as-is', () => {
		expect(formatQuantity(5)).toBe('5');
		expect(formatQuantity(0)).toBe('0');
	});

	it('displays missing loadout entries as 0', () => {
		expect(formatQuantity(undefined)).toBe('0');
	});
});

describe('hasAmmo', () => {
	it('is true for unlimited (-1) and any positive count', () => {
		expect(hasAmmo(-1)).toBe(true);
		expect(hasAmmo(3)).toBe(true);
	});

	it('is false at exactly 0 or when missing from the loadout', () => {
		expect(hasAmmo(0)).toBe(false);
		expect(hasAmmo(undefined)).toBe(false);
	});
});

describe('damagePieColorAt', () => {
	it('is pure yellow at and below the 1 o\'clock anchor (~8.3%)', () => {
		expect(damagePieColorAt(0)).toBe('#ffd166');
		expect(damagePieColorAt(8.3)).toBe('#ffd166');
	});

	it('is pure orange exactly at the 6 o\'clock anchor (50%)', () => {
		expect(damagePieColorAt(50)).toBe('#ff8c42');
	});

	it('is pure red at a full lap (100%, the 12 o\'clock anchor)', () => {
		expect(damagePieColorAt(100)).toBe('#e63946');
	});

	it('is a fixed function of position, independent of any one weapon', () => {
		// Same clock position must always yield the same color, regardless
		// of what fraction a particular weapon's own wedge happens to be --
		// this is the property the user explicitly asked to fix (a single
		// wheel every weapon samples from, not a per-weapon rescaled one).
		expect(damagePieColorAt(30)).toBe(damagePieColorAt(30));
		expect(damagePieColorAt(30)).not.toBe(damagePieColorAt(60));
	});
});

describe('damagePieGradient', () => {
	it('a low-damage weapon\'s wedge never reaches past yellow', () => {
		const gradient = damagePieGradient(20); // Digger: 20/190 = 10.5%
		expect(gradient).toContain('#ffd166');
		expect(gradient).not.toContain('#ff8c42');
		expect(gradient).not.toContain('#e63946');
	});

	it('Nuke (the roster max) sweeps the full wheel through to red', () => {
		const gradient = damagePieGradient(MAX_DAMAGE_RATING);
		expect(gradient).toContain('#ffd166');
		expect(gradient).toContain('#ff8c42');
		expect(gradient).toContain('#e63946');
	});

	it('Cluster Bomb is rated as 2 connecting hits (40 * 2 = 80), not the 5-point sum (200) or the raw per-point value (40)', () => {
		const clusterBomb = WEAPON_CATALOG.find((w) => w.id === 'cluster_bomb');
		expect(clusterBomb?.centerDamage).toBe(80);
	});

	it('MIRV is rated at its already-per-child value (30), not summed across 3-5 children', () => {
		const mirv = WEAPON_CATALOG.find((w) => w.id === 'mirv');
		expect(mirv?.centerDamage).toBe(30);
	});

	it('shields have no damage rating', () => {
		const shields = WEAPON_CATALOG.filter((w) => w.isShield);
		expect(shields.every((s) => s.centerDamage === undefined)).toBe(true);
	});
});
