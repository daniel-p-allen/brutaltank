import { describe, it, expect, beforeEach } from 'vitest';
import { get } from 'svelte/store';
import { weaponSelectStore, WEAPON_CATALOG, formatQuantity, hasAmmo } from './weaponSelectStore';

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
