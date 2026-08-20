<script lang="ts">
	// Visual wind readout (per user feedback: a rotating direction arrow plus
	// a way to see strength, replacing the old plain "wind N" text). Wind is
	// 1D (server only ever blows left/right, see Match.WIND_MAX), so the
	// arrow only ever points fully right or fully left — strength is instead
	// conveyed by the arrow's length/opacity and a numeric readout.

	export let strength: number; // signed: strength * directionSign
	export let maxStrength: number = 10; // keep in sync with server Match.WIND_MAX

	$: magnitude = Math.abs(strength);
	$: pointsRight = strength >= 0;
	$: intensity = Math.min(1, magnitude / maxStrength);
	// Arrow shaft length grows with strength so a calm wind reads as a short
	// stub and a strong one reads as a long, confident arrow.
	$: shaftLength = 8 + intensity * 22;
</script>

<div class="wind-indicator" title="Wind: {strength} ({pointsRight ? 'blowing right' : 'blowing left'})">
	<svg
		class="arrow"
		class:pointing-left={!pointsRight}
		width="40"
		height="24"
		viewBox="0 0 40 24"
		style="opacity: {0.35 + intensity * 0.65}"
	>
		<line
			x1={20 - shaftLength / 2}
			y1="12"
			x2={20 + shaftLength / 2}
			y2="12"
			stroke="#cfe8ff"
			stroke-width="3"
			stroke-linecap="round"
		/>
		<polygon points="{20 + shaftLength / 2 - 2},4 {20 + shaftLength / 2 + 8},12 {20 + shaftLength / 2 - 2},20" fill="#cfe8ff" />
	</svg>
	<span class="magnitude">{magnitude === 0 ? 'calm' : magnitude}</span>
</div>

<style>
	.wind-indicator {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.15rem;
		padding: 0.25rem 0.5rem;
		border-radius: 6px;
		background: rgba(0, 0, 0, 0.35);
		font-family: monospace;
		pointer-events: none;
	}

	.arrow {
		transition: opacity 0.2s ease;
	}

	.arrow.pointing-left {
		transform: scaleX(-1);
	}

	.magnitude {
		font-size: 0.75rem;
		color: #cfe8ff;
	}
</style>
