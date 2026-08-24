<script lang="ts">
	// Angle/power sliders + Fire button (PLAN.md section 3.1/3.2 HUD:
	// AngleDial, PowerBar). M1 has no weapon-select UI — weaponId is
	// hardcoded to basic_shell in fireInput.ts.
	//
	// M2: the server enforces active-player-only firing (protocol.md
	// section 4, Fire "only accepted from the player whose turn it currently
	// is"), but the client should still reflect that in the UI rather than
	// let the local player click Fire and get silently rejected — so Fire is
	// disabled whenever it isn't the local player's turn, in addition to the
	// existing optimistic-disable while a shot is in flight. The angle/power
	// sliders themselves stay enabled regardless of turn (per user feedback:
	// you should be able to play with your aim even when it's not your
	// shot) — only the Fire button and weapon select are turn-gated.

	import { onMount, onDestroy } from 'svelte';
	import { sendFire } from '../../game/input/fireInput';
	import { sendAimUpdate } from '../../game/input/aimInput';
	import { sendTrajectoryHelpUpdate } from '../../game/input/trajectoryHelpInput';
	import { attachKeyboardControls, detachKeyboardControls } from '../../game/input/keyboardInput';
	import { matchStore } from '../../stores/matchStore';
	import { sessionStore } from '../../stores/sessionStore';
	import { aimStore } from '../../stores/aimStore';
	import { trajectoryHelpStore } from '../../stores/trajectoryHelpStore';
	import { hasAmmo } from '../../stores/weaponSelectStore';
	import { weaponSelectStore } from '../../stores/weaponSelectStore';
	import WeaponSelect from './WeaponSelect.svelte';

	$: isMyTurn =
		$matchStore.activePlayerId !== null && $matchStore.activePlayerId === $sessionStore.playerId;
	$: localPlayer = $matchStore.players.find((p) => p.playerId === $sessionStore.playerId);
	$: loadout = localPlayer?.loadout ?? {};
	// disabled (turn/in-flight only) gates weapon *selection* — a weapon
	// with 0 ammo is separately excluded there via WeaponSelect's own
	// hasAmmo check per chip, so this must stay ammo-agnostic or every chip
	// (including ones with ammo left) would wrongly disable together
	// whenever the *currently selected* weapon happened to be empty.
	$: disabled = !isMyTurn || $matchStore.awaitingShotResolution;
	// fireDisabled additionally blocks Fire itself once the *selected*
	// weapon specifically is out of ammo (per user report, 2026-08-22: Fire
	// was only turn-gated, so a spent weapon could still be fired again,
	// sending a shot the server would reject — but only after sendFire had
	// already optimistically played the launch sound).
	$: fireDisabled = disabled || !hasAmmo(loadout[$weaponSelectStore]);

	// Trajectory Help is deliberately unavailable for Nuke (user decision,
	// 2026-08-23 — a rare/premium weapon shouldn't get an aim assist, not a
	// bug to fix). Button reads disabled and the dotted preview is
	// suppressed in GameCanvas.svelte's NUKE_WEAPON_ID check.
	$: trajectoryHelpUnavailable = $weaponSelectStore === 'nuke';

	// Broadcasts the local player's live aim angle so every connected client's
	// tankRenderer can show this tank's barrel tracking it, not just the
	// local view — throttled inside sendAimUpdate. Not turn-gated, matching
	// the sliders themselves staying enabled outside your turn.
	$: sendAimUpdate($aimStore.angleDeg);

	// Broadcasts the local player's Trajectory Help on/off toggle so it can be
	// shown in every connected client's players list (per user request,
	// 2026-08-23) — same not-turn-gated, always-live pattern as the aim
	// broadcast above.
	$: sendTrajectoryHelpUpdate($trajectoryHelpStore);

	function fire(): void {
		if (fireDisabled) return;
		sendFire($aimStore.angleDeg, $aimStore.power);
	}

	// A/D angle, W/S power, Space fire, 1-0 weapon select (per user request,
	// 2026-08-24 follow-up) -- lives for as long as FireControls is mounted,
	// same lifetime as the fire controls themselves.
	onMount(attachKeyboardControls);
	onDestroy(detachKeyboardControls);
</script>

<WeaponSelect disabled={disabled} />

<div class="fire-controls">
	<label class="control">
		<span>Angle: {$aimStore.angleDeg}&deg;</span>
		<input
			class="angle-slider"
			type="range"
			min="0"
			max="180"
			step="1"
			bind:value={$aimStore.angleDeg}
		/>
	</label>

	<label class="control">
		<span>Power: {$aimStore.power}</span>
		<input
			class="power-slider"
			type="range"
			min="0"
			max="100"
			step="1"
			bind:value={$aimStore.power}
		/>
	</label>

	<button
		type="button"
		class="trajectory-help-button"
		class:active={$trajectoryHelpStore && !trajectoryHelpUnavailable}
		disabled={trajectoryHelpUnavailable}
		on:click={() => trajectoryHelpStore.toggle()}
		title={trajectoryHelpUnavailable
			? 'Trajectory Help is not available for Nuke'
			: "Show a dotted preview of where the shot would land — accounts for the weapon's weight, ignores wind"}
	>
		Trajectory Help: {trajectoryHelpUnavailable ? 'N/A' : $trajectoryHelpStore ? 'On' : 'Off'}
	</button>

	<button class="fire-button" on:click={fire} disabled={fireDisabled}>
		{$matchStore.awaitingShotResolution
			? 'Firing...'
			: !isMyTurn
				? 'Not your turn'
				: !hasAmmo(loadout[$weaponSelectStore])
					? 'Out of ammo'
					: 'Fire'}
	</button>
</div>

<style>
	.fire-controls {
		display: flex;
		align-items: center;
		gap: 1.25rem;
		padding: 0.75rem 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	.control {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		font-size: 0.85rem;
	}

	/* Angle 0deg points screen-right, 90deg points up (tankRenderer.ts convention),
	   so a plain left-to-right slider drags the thumb *away* from the direction the
	   barrel tip visibly sweeps. rtl flips the track so dragging the thumb right
	   sweeps the barrel right too, per user feedback that drag direction and barrel
	   sweep direction must match. */
	.angle-slider {
		direction: rtl;
	}

	/* Angle and Power are the primary aiming controls (per user feedback,
	   2026-08-24: "these are the main tools") -- sized and colored to stand
	   out from the rest of the HUD's smaller buttons rather than blend in as
	   plain browser-default range inputs. Angle gets blue (matches the
	   existing "you"/local-player accent used elsewhere in the app);
	   Power gets amber (an otherwise-unused hue in this UI, reading as
	   "energy" without colliding with cash-green or fire-red). Thumb/track
	   styling needs both -webkit- and -moz- prefixed pseudo-elements --
	   there's no unprefixed standard for range input styling yet. */
	.angle-slider,
	.power-slider {
		width: 11rem;
		height: 1.5rem;
		margin: 0;
		background: transparent;
		cursor: pointer;
		appearance: none;
		-webkit-appearance: none;
	}

	/* 30% longer than angle, per user request 2026-08-24. */
	.power-slider {
		width: 14.3rem;
	}

	.angle-slider::-webkit-slider-runnable-track,
	.power-slider::-webkit-slider-runnable-track {
		height: 0.7rem;
		border-radius: 999px;
	}

	.angle-slider::-moz-range-track,
	.power-slider::-moz-range-track {
		height: 0.7rem;
		border-radius: 999px;
	}

	.angle-slider::-webkit-slider-thumb,
	.power-slider::-webkit-slider-thumb {
		appearance: none;
		-webkit-appearance: none;
		width: 1.7rem;
		height: 1.7rem;
		margin-top: -0.5rem;
		border-radius: 50%;
		border: 3px solid #111;
		box-shadow: 0 1px 3px rgba(0, 0, 0, 0.6);
		cursor: pointer;
	}

	.angle-slider::-moz-range-thumb,
	.power-slider::-moz-range-thumb {
		width: 1.7rem;
		height: 1.7rem;
		border-radius: 50%;
		border: 3px solid #111;
		box-shadow: 0 1px 3px rgba(0, 0, 0, 0.6);
		cursor: pointer;
	}

	.angle-slider::-webkit-slider-runnable-track {
		background: #244266;
	}
	.angle-slider::-moz-range-track {
		background: #244266;
	}
	.angle-slider::-webkit-slider-thumb,
	.angle-slider::-moz-range-thumb {
		background: #5b9bf0;
	}

	.power-slider::-webkit-slider-runnable-track {
		background: #5c4416;
	}
	.power-slider::-moz-range-track {
		background: #5c4416;
	}
	.power-slider::-webkit-slider-thumb,
	.power-slider::-moz-range-thumb {
		background: #f0b23c;
	}

	.trajectory-help-button {
		margin-left: auto;
		padding: 0.4rem 0.75rem;
		font-size: 0.8rem;
		font-weight: 600;
		border-radius: 6px;
		border: 1px solid #555;
		background: #222;
		color: #aaa;
		cursor: pointer;
	}

	.trajectory-help-button.active {
		border-color: #4a9;
		background: rgba(74, 170, 153, 0.18);
		color: #7fd9c4;
	}

	.trajectory-help-button:disabled {
		background: #1a1a1a;
		color: #666;
		border-color: #333;
		cursor: default;
	}

	.fire-button {
		padding: 0.5rem 1.25rem;
		font-size: 1rem;
		font-weight: 600;
		border-radius: 6px;
		border: none;
		background: #d0392b;
		color: white;
		cursor: pointer;
	}

	.fire-button:disabled {
		background: #999;
		cursor: default;
	}
</style>
