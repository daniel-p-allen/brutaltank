<script lang="ts">
	// The only component touching the 2D context (PLAN.md section 3.2).
	// Owns a requestAnimationFrame loop started in onMount, cancelled in
	// onDestroy. Draw order: terrain -> tanks -> active projectile animation.

	import { onMount, onDestroy } from 'svelte';
	import { matchStore } from '../../stores/matchStore';
	import { pendingShotAnimation, clearShotAnimation } from '../../stores/shotAnimationStore';
	import { sessionStore } from '../../stores/sessionStore';
	import { aimStore, type AimState } from '../../stores/aimStore';
	import { drawTerrain } from '../../game/render/terrainRenderer';
	import { drawTanks } from '../../game/render/tankRenderer';
	import { drawShields } from '../../game/render/shieldRenderer';
	import { computeTrajectoryPreview, drawTrajectoryPreview, WEAPON_PHYSICS } from '../../game/render/trajectoryPreview';
	import { trajectoryHelpStore } from '../../stores/trajectoryHelpStore';
	import { weaponSelectStore } from '../../stores/weaponSelectStore';
	import {
		drawProjectile,
		isAnimationFinished,
		getImpactPhaseStartMs,
		PROJECTILE_ANIMATION_DURATION_MS
	} from '../../game/render/projectileRenderer';
	import { playImpacts, playRicochet } from '../../audio/soundManager';
	import WindIndicator from './WindIndicator.svelte';
	import type { MatchState } from '../../stores/matchStore';
	import type { PendingShotAnimation } from '../../stores/shotAnimationStore';

	export let width = 960;
	export let height = 420;

	let canvasEl: HTMLCanvasElement;
	let rafId: number | null = null;

	// Mutable scene state updated by store subscriptions rather than
	// subscribing every frame, per PLAN.md's "avoids per-frame reactivity
	// overhead" guidance.
	let scene: MatchState = {
		matchId: null,
		status: null,
		roundNumber: null,
		maxRounds: null,
		terrain: { heights: [] },
		players: [],
		turnOrder: [],
		currentTurnIndex: 0,
		wind: null,
		awaitingShotResolution: false,
		activePlayerId: null,
		turnTimeoutSec: null,
		turnStartedAtMs: null,
		fireRejectedReason: null,
		disconnectedPlayerIds: [],
		roundEndedInfo: null,
		matchEndedInfo: null,
		shop: null,
		shopErrorReason: null,
		remoteAim: {}
	};
	let activeShot: PendingShotAnimation | null = null;
	let localPlayerId: string | null = null;
	let aim: AimState = { angleDeg: 45, power: 60 };
	let trajectoryHelpEnabled = false;
	let selectedWeaponId = 'basic_shell';

	// Rough turret-height offset for the preview's launch point — doesn't
	// need to match tankRenderer's exact barrel-tip geometry since the
	// preview is already a deliberately-inaccurate guide (per user request:
	// "it can not be 100 percent accurate otherwise it is not fun").
	const PREVIEW_LAUNCH_HEIGHT_OFFSET = 17;

	// Trajectory Help is deliberately unavailable for Nuke (per user
	// decision, 2026-08-23 — not a bug: a rare/premium weapon shouldn't get
	// an aim assist). FireControls.svelte shows the button as disabled and
	// this skips drawing the dotted preview to match.
	const NUKE_WEAPON_ID = 'nuke';

	// Tracks which shot's sound sequence has already been triggered, so it
	// fires exactly once per shot rather than every frame the flight-end
	// condition below is true. Bouncing Betty only, for this pilot — see
	// soundManager.ts.
	let soundedShot: PendingShotAnimation | null = null;

	const unsubMatch = matchStore.subscribe((s) => (scene = s));
	const unsubShot = pendingShotAnimation.subscribe((s) => (activeShot = s));
	const unsubSession = sessionStore.subscribe((s) => (localPlayerId = s.playerId));

	$: localPlayer = scene.players.find((p) => p.playerId === localPlayerId) ?? null;
	const unsubAim = aimStore.subscribe((s) => (aim = s));
	const unsubTrajectoryHelp = trajectoryHelpStore.subscribe((enabled) => (trajectoryHelpEnabled = enabled));
	const unsubWeaponSelect = weaponSelectStore.subscribe((id) => (selectedWeaponId = id));

	function frame(): void {
		const ctx = canvasEl?.getContext('2d');
		if (ctx) {
			const viewport = { canvasWidth: width, canvasHeight: height };
			ctx.clearRect(0, 0, width, height);

			// Sky background.
			ctx.fillStyle = '#8fc5e8';
			ctx.fillRect(0, 0, width, height);

			// While a shot is still in flight, keep drawing the terrain/health
			// as they were *before* this shot's delta, so the crater/damage
			// don't visibly land before the projectile does (matchStore
			// already applied the authoritative state on receipt; only the
			// rendering is deferred here).
			const elapsed = activeShot ? performance.now() - activeShot.startedAtMs : Infinity;
			const inFlight = activeShot !== null && elapsed < PROJECTILE_ANIMATION_DURATION_MS;
			const heightsToDraw =
				inFlight && activeShot!.preShotHeights.length ? activeShot!.preShotHeights : scene.terrain.heights;
			const playersToDraw = inFlight
				? scene.players.map((p) => {
						const preHealth = activeShot!.preShotHealth[p.playerId];
						const preY = activeShot!.preShotTankY[p.playerId];
						if (!preHealth && preY === undefined) return p;
						return {
							...p,
							tank: {
								...p.tank,
								...(preHealth ? { health: preHealth.health, alive: preHealth.alive } : {}),
								...(preY !== undefined ? { y: preY } : {})
							}
						};
					})
				: scene.players;

			// Local player's own live drag always wins over its last broadcast
			// PlayerAiming echo (zero-latency local feedback); every other
			// player's barrel tracks their last-known live angle from
			// matchStore.remoteAim.
			const aimAngleByPlayerId = { ...scene.remoteAim };
			if (localPlayerId != null) {
				aimAngleByPlayerId[localPlayerId] = aim.angleDeg;
			}

			drawTerrain(ctx, heightsToDraw, viewport);
			drawTanks(ctx, playersToDraw, viewport, aimAngleByPlayerId);
			drawShields(ctx, playersToDraw, viewport, performance.now());

			if (
				trajectoryHelpEnabled &&
				localPlayer &&
				localPlayer.tank.alive &&
				!activeShot &&
				selectedWeaponId !== NUKE_WEAPON_ID
			) {
				// Defensive: an exception here would otherwise propagate out of
				// frame() and silently stop the whole rAF loop (the trailing
				// requestAnimationFrame(frame) call below never runs).
				try {
					const physics = WEAPON_PHYSICS[selectedWeaponId] ?? { powerScaleMultiplier: 1.0, gravityMultiplier: 1.0 };
					const points = computeTrajectoryPreview(
						localPlayer.tank.x,
						localPlayer.tank.y - PREVIEW_LAUNCH_HEIGHT_OFFSET,
						aim.angleDeg,
						aim.power,
						heightsToDraw,
						physics.powerScaleMultiplier,
						physics.gravityMultiplier
					);
					drawTrajectoryPreview(ctx, points, viewport);
				} catch (err) {
					console.warn('Trajectory preview failed for weapon', selectedWeaponId, err);
				}
			}

			if (activeShot) {
				drawProjectile(ctx, activeShot.trajectory, activeShot.impacts, activeShot.weaponId, elapsed, viewport);

				// Sound, once per shot, right as the impact effects actually start
				// (matches drawProjectile's own flashElapsed=0 moment — for MIRV
				// with multiple children that's after the fall phase, not right
				// at the flight animation's end, so it doesn't play before the
				// children visually land). Bouncing Betty keeps its own staggered
				// ricochet-per-bounce sequence (from the pilot); every other
				// weapon's impact sequencing (staggered bomblets, scrape-then-
				// boom, etc.) is handled inside soundManager.playImpacts itself.
				const impactPhaseStart = getImpactPhaseStartMs(activeShot.weaponId, activeShot.impacts.length);
				if (elapsed >= impactPhaseStart && soundedShot !== activeShot) {
					soundedShot = activeShot;
					if (activeShot.weaponId === 'bouncing_betty') {
						const bounceCount = Math.max(0, activeShot.impacts.length - 1);
						for (let i = 0; i < bounceCount; i++) {
							setTimeout(() => playRicochet(), i * 90);
						}
						setTimeout(() => playImpacts('bouncing_betty', 1), bounceCount * 90);
					} else {
						playImpacts(activeShot.weaponId, activeShot.impacts.length);
					}
				}

				if (isAnimationFinished(elapsed, activeShot.weaponId, activeShot.impacts.length)) {
					clearShotAnimation();
				}
			}
		}
		rafId = requestAnimationFrame(frame);
	}

	onMount(() => {
		rafId = requestAnimationFrame(frame);
	});

	onDestroy(() => {
		if (rafId !== null) cancelAnimationFrame(rafId);
		unsubMatch();
		unsubShot();
		unsubSession();
		unsubAim();
		unsubTrajectoryHelp();
		unsubWeaponSelect();
	});
</script>

<div class="canvas-wrap">
	<canvas bind:this={canvasEl} {width} {height} class="game-canvas"></canvas>
	{#if scene.wind}
		<div class="wind-overlay">
			<!-- wind.strength is already signed (matches server ProjectileSim's
			     windAccel = windStrength * WIND_ACCEL_PER_STRENGTH, applied
			     directly to vx) — directionSign is just sign(strength), so
			     multiplying by it here squared the sign and made the arrow
			     always point the same way regardless of actual wind direction
			     (per user report, 2026-08-22: "is that the direction the
			     projectile is pushed... there was some weird things"). Fixed
			     by passing strength straight through. -->
			<WindIndicator strength={scene.wind.strength} />
		</div>
	{/if}
	{#if localPlayer}
		<div class="cash-overlay">Your Wallet: <span class="cash-amount">${localPlayer.cash}</span></div>
	{/if}
</div>

<style>
	.canvas-wrap {
		position: relative;
		display: inline-block;
		max-width: 100%;
		/* MatchScreen's flex column defaults to align-items: stretch, which
		   would otherwise stretch this wrapper to the full page width while
		   the canvas itself stays at its natural size — decoupling
		   .wind-overlay's `right` offset from the canvas's actual edge and
		   leaving it floating in empty space instead of over the sky. */
		align-self: flex-start;
	}

	.game-canvas {
		display: block;
		border: 1px solid #333;
		border-radius: 4px;
		max-width: 100%;
	}

	.wind-overlay {
		position: absolute;
		top: 0.5rem;
		right: 0.5rem;
	}

	.cash-overlay {
		position: absolute;
		bottom: 0.5rem;
		left: 0.5rem;
		padding: 0.3rem 0.65rem;
		border-radius: 6px;
		background: rgba(0, 0, 0, 0.55);
		border: 1px solid #4a9;
		font-family: monospace;
		font-weight: 600;
		font-size: 0.85rem;
		color: #ccc;
	}

	.cash-amount {
		color: #9fd68a;
	}
</style>
