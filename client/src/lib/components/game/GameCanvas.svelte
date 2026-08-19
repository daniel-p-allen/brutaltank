<script lang="ts">
	// The only component touching the 2D context (PLAN.md section 3.2).
	// Owns a requestAnimationFrame loop started in onMount, cancelled in
	// onDestroy. Draw order: terrain -> tanks -> active projectile animation.

	import { onMount, onDestroy } from 'svelte';
	import { matchStore } from '../../stores/matchStore';
	import { pendingShotAnimation, clearShotAnimation } from '../../stores/shotAnimationStore';
	import { drawTerrain } from '../../game/render/terrainRenderer';
	import { drawTanks } from '../../game/render/tankRenderer';
	import { drawProjectile, isAnimationFinished } from '../../game/render/projectileRenderer';
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
		awaitingShotResolution: false
	};
	let activeShot: PendingShotAnimation | null = null;

	const unsubMatch = matchStore.subscribe((s) => (scene = s));
	const unsubShot = pendingShotAnimation.subscribe((s) => (activeShot = s));

	function frame(): void {
		const ctx = canvasEl?.getContext('2d');
		if (ctx) {
			const viewport = { canvasWidth: width, canvasHeight: height };
			ctx.clearRect(0, 0, width, height);

			// Sky background.
			ctx.fillStyle = '#8fc5e8';
			ctx.fillRect(0, 0, width, height);

			drawTerrain(ctx, scene.terrain.heights, viewport);
			drawTanks(ctx, scene.players, viewport);

			if (activeShot) {
				const elapsed = performance.now() - activeShot.startedAtMs;
				drawProjectile(ctx, activeShot.trajectory, activeShot.impact, elapsed, viewport);
				if (isAnimationFinished(elapsed)) {
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
	});
</script>

<canvas bind:this={canvasEl} {width} {height} class="game-canvas"></canvas>

<style>
	.game-canvas {
		display: block;
		border: 1px solid #333;
		border-radius: 4px;
		max-width: 100%;
	}
</style>
