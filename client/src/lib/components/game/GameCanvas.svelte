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
	import {
		drawProjectile,
		isAnimationFinished,
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

	// Tracks which shot's sound sequence has already been triggered, so it
	// fires exactly once per shot rather than every frame the flight-end
	// condition below is true. Bouncing Betty only, for this pilot — see
	// soundManager.ts.
	let soundedShot: PendingShotAnimation | null = null;

	const unsubMatch = matchStore.subscribe((s) => (scene = s));
	const unsubShot = pendingShotAnimation.subscribe((s) => (activeShot = s));
	const unsubSession = sessionStore.subscribe((s) => (localPlayerId = s.playerId));
	const unsubAim = aimStore.subscribe((s) => (aim = s));

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
						const pre = activeShot!.preShotHealth[p.playerId];
						return pre ? { ...p, tank: { ...p.tank, health: pre.health, alive: pre.alive } } : p;
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

			if (activeShot) {
				drawProjectile(ctx, activeShot.trajectory, activeShot.impacts, activeShot.weaponId, elapsed, viewport);

				// Sound, once per shot, right as the flight animation ends and
				// the impact effects start (matches drawProjectile's own
				// flashElapsed=0 moment). Bouncing Betty keeps its own staggered
				// ricochet-per-bounce sequence (from the pilot); every other
				// weapon's impact sequencing (staggered bomblets, scrape-then-
				// boom, etc.) is handled inside soundManager.playImpacts itself.
				if (elapsed >= PROJECTILE_ANIMATION_DURATION_MS && soundedShot !== activeShot) {
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

				if (isAnimationFinished(elapsed, activeShot.weaponId)) {
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
	});
</script>

<div class="canvas-wrap">
	<canvas bind:this={canvasEl} {width} {height} class="game-canvas"></canvas>
	{#if scene.wind}
		<div class="wind-overlay">
			<WindIndicator strength={scene.wind.strength * scene.wind.directionSign} />
		</div>
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
</style>
