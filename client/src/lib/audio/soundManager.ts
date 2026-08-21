// Minimal audio infrastructure (PLAN.md section 7.2/7.3) — first built for
// the Bouncing Betty pilot. There is otherwise zero audio anywhere in the
// client. Scoped deliberately small: just the two sounds this weapon needs,
// synthesized via Web Audio rather than sourced (per section 7.3's
// sanctioned fallback — sourcing/vetting CC0 assets is real process
// overhead not worth spending on a single pilot weapon that might still
// change after playtest). If this pilot is approved, sourcing real CC0
// assets for the full roster's sound families is the natural next step,
// reusing this same play()/unlock()/mute plumbing.

export type SoundId = 'ricochet' | 'impact_light';

const MUTE_STORAGE_KEY = 'brutaltank.audio.muted';

let audioCtx: AudioContext | null = null;
let muted = readMutedFromStorage();

function readMutedFromStorage(): boolean {
	try {
		return localStorage.getItem(MUTE_STORAGE_KEY) === '1';
	} catch {
		return false;
	}
}

/** Creates (or resumes) the shared AudioContext. Must be called from a real user gesture — e.g. the player's first Fire click — to satisfy browser autoplay policy. */
export function unlockAudio(): void {
	if (audioCtx === null) {
		audioCtx = new AudioContext();
	}
	if (audioCtx.state === 'suspended') {
		void audioCtx.resume();
	}
}

export function isMuted(): boolean {
	return muted;
}

export function setMuted(next: boolean): void {
	muted = next;
	try {
		localStorage.setItem(MUTE_STORAGE_KEY, next ? '1' : '0');
	} catch {
		// sessionStorage/localStorage unavailable (e.g. private browsing) — mute
		// preference just won't persist across reloads, not worth surfacing.
	}
}

/** Short percussive noise burst through a fast lowpass sweep — "skipping stone hitting ground." */
function playRicochet(ctx: AudioContext): void {
	const duration = 0.1;
	const bufferSize = Math.ceil(ctx.sampleRate * duration);
	const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) {
		// Exponentially-decaying white noise — a sharp, short "tick" rather than a sustained hiss.
		const decay = 1 - i / bufferSize;
		data[i] = (Math.random() * 2 - 1) * decay * decay;
	}

	const noise = ctx.createBufferSource();
	noise.buffer = buffer;

	const filter = ctx.createBiquadFilter();
	filter.type = 'lowpass';
	filter.frequency.setValueAtTime(4000, ctx.currentTime);
	filter.frequency.exponentialRampToValueAtTime(600, ctx.currentTime + duration);

	const gain = ctx.createGain();
	gain.gain.setValueAtTime(0.5, ctx.currentTime);
	gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);

	noise.connect(filter).connect(gain).connect(ctx.destination);
	noise.start();
	noise.stop(ctx.currentTime + duration);
}

/** Short low thump/boom — the actual detonation. Deliberately generic (PLAN.md section 7.2's "Family #1: light shell"), meant to be reused once the rest of the roster's sounds are built. */
function playImpactLight(ctx: AudioContext): void {
	const duration = 0.35;

	const osc = ctx.createOscillator();
	osc.type = 'sine';
	osc.frequency.setValueAtTime(160, ctx.currentTime);
	osc.frequency.exponentialRampToValueAtTime(45, ctx.currentTime + duration);

	const gain = ctx.createGain();
	gain.gain.setValueAtTime(0.6, ctx.currentTime);
	gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration);

	osc.connect(gain).connect(ctx.destination);
	osc.start();
	osc.stop(ctx.currentTime + duration);
}

export function play(soundId: SoundId): void {
	if (muted || audioCtx === null) return;
	if (soundId === 'ricochet') {
		playRicochet(audioCtx);
	} else {
		playImpactLight(audioCtx);
	}
}
