<script lang="ts">
	// Shown once between clicking Ready in the lobby and actually sending
	// SetReady to the server (per user request, 2026-08-24: "click ready,
	// instructions screen, click ready again, in game mode"). Purely
	// client-local -- no protocol change needed, LobbyScreen just delays
	// sendSetReady until this screen's own Ready button is clicked.
	//
	// Direction "A: Illustrated Diagram" from the 3-option design canvas
	// (approved 2026-08-24), rebuilt with colors/shapes pulled from the
	// actual rendering code rather than generic illustration colors, per
	// user follow-up request ("images or diagrams that reflect our
	// finished UI UX"):
	//   - sky/terrain: GameCanvas.svelte's #8fc5e8 / terrainRenderer.ts's #4a7c3f
	//   - tank colors: Match.java's COLORS[] -- #e33 (player 1) / #33e (player 2)
	//   - the mini weapon chip, sliders, Fire/Trajectory-Help buttons, and
	//     shield card below are literal copies of WeaponSelect.svelte's/
	//     FireControls.svelte's/ShopItemCard.svelte's own colors and shapes,
	//     not redrawn icons, so this screen shows the real HUD.

	export let onReady: () => void;
</script>

<div class="instructions-screen">
	<h2>How to Play</h2>

	<div class="diagram">
		<svg viewBox="0 0 820 300" width="100%" height="260" style="display:block;">
			<rect x="0" y="0" width="820" height="300" fill="#8fc5e8" />
			<path
				d="M0,250 L60,232 L150,240 L240,196 L320,208 L400,178 L480,190 L560,160 L640,172 L720,150 L780,162 L820,155 L820,300 L0,300 Z"
				fill="#4a7c3f"
				stroke="#3a6432"
				stroke-width="2"
			/>

			<!-- you (blue, matches Match.java COLORS[1] "#33e") -->
			<g transform="translate(130,192)">
				<rect x="-20" y="-6" width="40" height="16" rx="3" fill="#33e" />
				<circle cx="0" cy="-8" r="11" fill="#33e" />
				<line x1="0" y1="-8" x2="30" y2="-30" stroke="#33e" stroke-width="6" stroke-linecap="round" />
			</g>

			<!-- target (red, matches Match.java COLORS[0] "#e33") -->
			<g transform="translate(680,142)">
				<rect x="-20" y="-6" width="40" height="16" rx="3" fill="#e33" />
				<circle cx="0" cy="-8" r="11" fill="#e33" />
				<line x1="0" y1="-8" x2="-30" y2="-28" stroke="#e33" stroke-width="6" stroke-linecap="round" />
			</g>

			<!-- trajectory, amber to match the real power slider's accent -->
			<path d="M 130 174 Q 400 20 660 132" fill="none" stroke="#f0b23c" stroke-width="3" stroke-dasharray="7 7" />
			<circle cx="660" cy="132" r="5" fill="#f0b23c" />

			<!-- wind, blue to match the real angle slider's accent -->
			<g transform="translate(380,40)">
				<line x1="-60" y1="0" x2="55" y2="0" stroke="#244f8a" stroke-width="4" stroke-linecap="round" />
				<path d="M55,0 L40,-9 L40,9 Z" fill="#244f8a" />
			</g>
		</svg>
		<div class="callout wind">Wind &mdash; pushes shots off-course, less for heavy weapons</div>
		<div class="callout trajectory">Trajectory (optional dotted preview)</div>
		<div class="callout you">You</div>
		<div class="callout target">Target</div>
	</div>

	<div class="steps">
		<div class="step">
			<div class="step-num">1</div>
			<div class="step-title">Pick a weapon</div>
			<div class="mini-weapon-chip">
				<span class="label">MIRV</span>
				<span class="meta-row"><span class="qty">&times;3</span><span class="weight">&#9733;&#9733;</span></span>
			</div>
			<div class="step-desc">Weight class (stars) affects fall speed and wind resistance.</div>
		</div>

		<div class="step">
			<div class="step-num">2</div>
			<div class="step-title">Set angle &amp; power</div>
			<div class="mini-sliders">
				<div class="mini-slider angle"><div class="mini-thumb"></div></div>
				<div class="mini-slider power"><div class="mini-thumb"></div></div>
			</div>
			<div class="step-desc">Drag to aim, then watch the wind arrow above.</div>
		</div>

		<div class="step">
			<div class="step-num">3</div>
			<div class="step-title">Fire</div>
			<div class="mini-buttons">
				<div class="mini-th-button">Trajectory Help: Off</div>
				<div class="mini-fire-button">Fire</div>
			</div>
			<div class="step-desc">Off gives +25% damage &amp; ~2.5&times; cash on that shot.</div>
		</div>

		<div class="step">
			<div class="step-num">4</div>
			<div class="step-title">Shop &amp; repeat</div>
			<div class="mini-shield-card">
				<span class="label">Absorb</span>
				<span class="blurb">Halves damage</span>
			</div>
			<div class="step-desc">Shields (violet) reset off each round &mdash; buy fresh every time.</div>
		</div>
	</div>

	<button type="button" class="ready-button" on:click={onReady}>Ready</button>
</div>

<style>
	.instructions-screen {
		display: flex;
		flex-direction: column;
		gap: 1rem;
		max-width: 46rem;
		padding: 1rem;
		border: 1px solid #444;
		border-radius: 8px;
		font-family: system-ui, sans-serif;
	}

	h2 {
		margin: 0;
	}

	.diagram {
		position: relative;
		border: 1px solid #444;
		border-radius: 8px;
		overflow: hidden;
	}

	.callout {
		position: absolute;
		font-size: 0.7rem;
		font-weight: 700;
		padding: 0.1rem 0.4rem;
		border-radius: 4px;
		background: rgba(0, 0, 0, 0.55);
		color: white;
	}

	.callout.wind {
		left: 0.75rem;
		top: 0.5rem;
		color: #9fc0ea;
	}

	.callout.trajectory {
		left: 45%;
		top: 0.5rem;
		color: #f5c877;
	}

	.callout.you {
		left: 8rem;
		top: 7.5rem;
		color: #9aa8ff;
	}

	.callout.target {
		right: 3rem;
		top: 5.5rem;
		color: #ff9a92;
	}

	.steps {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		gap: 0.75rem;
	}

	.step {
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		padding: 0.6rem;
		border: 1px solid #444;
		border-radius: 8px;
		background: rgba(255, 255, 255, 0.03);
	}

	.step-num {
		font-size: 1.1rem;
		font-weight: 800;
		color: #2f8f4e;
	}

	.step-title {
		font-weight: 700;
		font-size: 0.85rem;
	}

	.step-desc {
		font-size: 0.72rem;
		color: #999;
		line-height: 1.35;
	}

	/* The snippets below are literal copies of the real components' styling
	   (WeaponSelect.svelte's .chip, FireControls.svelte's sliders/buttons,
	   ShopItemCard.svelte's shield card) -- not new illustrations. */

	.mini-weapon-chip {
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
		padding: 0.3rem 0.5rem;
		border-radius: 6px;
		border: 1px solid #d0392b;
		background: rgba(208, 57, 43, 0.25);
		font-size: 0.72rem;
		color: #eee;
	}

	.mini-weapon-chip .meta-row {
		display: flex;
		justify-content: space-between;
	}

	.mini-weapon-chip .qty {
		font-family: monospace;
		color: #fff;
	}

	.mini-weapon-chip .weight {
		color: #e0a020;
	}

	.mini-sliders {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
	}

	.mini-slider {
		height: 0.5rem;
		border-radius: 999px;
		position: relative;
	}

	.mini-slider.angle {
		background: #244266;
	}

	.mini-slider.power {
		background: #5c4416;
	}

	.mini-thumb {
		position: absolute;
		top: 50%;
		left: 55%;
		transform: translate(-50%, -50%);
		width: 0.85rem;
		height: 0.85rem;
		border-radius: 50%;
		border: 2px solid #111;
	}

	.mini-slider.angle .mini-thumb {
		background: #5b9bf0;
	}

	.mini-slider.power .mini-thumb {
		background: #f0b23c;
	}

	.mini-buttons {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
	}

	.mini-th-button {
		padding: 0.25rem 0.5rem;
		border-radius: 6px;
		border: 1px solid #555;
		background: #222;
		color: #aaa;
		font-size: 0.68rem;
		font-weight: 600;
		text-align: center;
	}

	.mini-fire-button {
		padding: 0.3rem 0.5rem;
		border-radius: 6px;
		background: #d0392b;
		color: white;
		font-size: 0.72rem;
		font-weight: 600;
		text-align: center;
	}

	.mini-shield-card {
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
		padding: 0.3rem 0.5rem;
		border-radius: 6px;
		border: 1px solid #a06fe0;
		background: rgba(160, 111, 224, 0.1);
		font-size: 0.72rem;
	}

	.mini-shield-card .label {
		font-weight: 600;
		color: #eee;
	}

	.mini-shield-card .blurb {
		font-weight: 600;
		color: #a06fe0;
	}

	.ready-button {
		align-self: center;
		padding: 0.7rem 2.4rem;
		border-radius: 8px;
		border: none;
		background: #2f8f4e;
		color: white;
		font-weight: 700;
		font-size: 1rem;
		cursor: pointer;
	}

	.ready-button:hover {
		background: #368f57;
	}
</style>
