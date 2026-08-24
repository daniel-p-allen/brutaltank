import { describe, it, expect } from 'vitest';
import { HOTBAR_WEAPON_IDS, isTypingTarget } from './keyboardInput';
import { WEAPON_CATALOG } from '../../stores/weaponSelectStore';

describe('HOTBAR_WEAPON_IDS', () => {
	it('has exactly 10 entries, one per numeric key (1-9 then 0)', () => {
		expect(HOTBAR_WEAPON_IDS).toHaveLength(10);
	});

	it('matches the first 10 (non-shield) WEAPON_CATALOG entries in order', () => {
		expect(HOTBAR_WEAPON_IDS).toEqual(WEAPON_CATALOG.filter((w) => !w.isShield).map((w) => w.id));
	});

	it('excludes shields', () => {
		const shieldIds = WEAPON_CATALOG.filter((w) => w.isShield).map((w) => w.id);
		for (const id of shieldIds) {
			expect(HOTBAR_WEAPON_IDS).not.toContain(id);
		}
	});
});

describe('isTypingTarget', () => {
	// Regression test: a naive "any <input> is a typing target" check blocks
	// every shortcut the instant the angle/power range sliders have focus --
	// found live 2026-08-24 ("nothing to do with the keyboard is working"),
	// since dragging a slider is the most natural first action a player takes.
	it('is false for a range input (the angle/power sliders)', () => {
		const el = document.createElement('input');
		el.type = 'range';
		expect(isTypingTarget(el)).toBe(false);
	});

	it('is false for a button', () => {
		expect(isTypingTarget(document.createElement('button'))).toBe(false);
	});

	it('is true for a text input', () => {
		const el = document.createElement('input');
		el.type = 'text';
		expect(isTypingTarget(el)).toBe(true);
	});

	it('is true for a textarea', () => {
		expect(isTypingTarget(document.createElement('textarea'))).toBe(true);
	});

	// jsdom doesn't implement HTMLElement.isContentEditable (always reports
	// false regardless of the contentEditable attribute), so the
	// contenteditable branch can't be exercised under this test runner --
	// verified manually in a real browser instead.
});
