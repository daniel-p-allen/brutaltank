// Audio infrastructure (PLAN.md section 7.2/7.3). Started as the Bouncing
// Betty pilot (ricochet + impactLight); this expansion wires the confirmed
// sound set for the rest of the weapon roster, all synthesized via Web
// Audio except two real recordings for Nuke (see docs/asset-sources.md).
// Every synthesis recipe here was iterated live against user feedback on a
// throwaway preview board before landing here — only the confirmed picks
// made it into this file, not the rejected alternatives.

import nukeWarningSirenUrl from '../../assets/audio/nuke-warning-siren.mp3';
import nukeFallingBombUrl from '../../assets/audio/nuke-falling-bomb.mp3';

const MUTE_STORAGE_KEY = 'brutaltank.audio.muted';

let audioCtx: AudioContext | null = null;
let muted = readMutedFromStorage();
let nukeSirenEl: HTMLAudioElement | null = null;
let nukeFallingBombEl: HTMLAudioElement | null = null;

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
	if (nukeSirenEl === null) {
		nukeSirenEl = new Audio(nukeWarningSirenUrl);
		nukeSirenEl.preload = 'auto';
	}
	if (nukeFallingBombEl === null) {
		nukeFallingBombEl = new Audio(nukeFallingBombUrl);
		nukeFallingBombEl.preload = 'auto';
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

// ---------------------------------------------------------------------
// Low-level synthesis helpers
// ---------------------------------------------------------------------

function noiseBurst(
	c: AudioContext,
	opts: { duration: number; startFreq: number; endFreq: number; gain: number; filterType?: BiquadFilterType }
): void {
	const { duration, startFreq, endFreq, gain, filterType = 'lowpass' } = opts;
	const bufferSize = Math.ceil(c.sampleRate * duration);
	const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) {
		const decay = 1 - i / bufferSize;
		data[i] = (Math.random() * 2 - 1) * decay * decay;
	}
	const src = c.createBufferSource();
	src.buffer = buffer;
	const filter = c.createBiquadFilter();
	filter.type = filterType;
	filter.frequency.setValueAtTime(startFreq, c.currentTime);
	filter.frequency.exponentialRampToValueAtTime(Math.max(endFreq, 20), c.currentTime + duration);
	const g = c.createGain();
	g.gain.setValueAtTime(gain, c.currentTime);
	g.gain.exponentialRampToValueAtTime(0.001, c.currentTime + duration);
	src.connect(filter).connect(g).connect(c.destination);
	src.start();
	src.stop(c.currentTime + duration);
}

function tone(
	c: AudioContext,
	opts: { duration: number; startFreq: number; endFreq: number; gain: number; type?: OscillatorType; delay?: number }
): void {
	const { duration, startFreq, endFreq, gain, type = 'sine', delay = 0 } = opts;
	const osc = c.createOscillator();
	osc.type = type;
	osc.frequency.setValueAtTime(startFreq, c.currentTime + delay);
	osc.frequency.exponentialRampToValueAtTime(Math.max(endFreq, 20), c.currentTime + delay + duration);
	const g = c.createGain();
	g.gain.setValueAtTime(0.0001, c.currentTime + delay);
	g.gain.exponentialRampToValueAtTime(gain, c.currentTime + delay + 0.01);
	g.gain.exponentialRampToValueAtTime(0.001, c.currentTime + delay + duration);
	osc.connect(g).connect(c.destination);
	osc.start(c.currentTime + delay);
	osc.stop(c.currentTime + delay + duration);
}

// ---------------------------------------------------------------------
// Confirmed sound recipes (one per family/moment)
// ---------------------------------------------------------------------

function shellCrackLight(c: AudioContext): void {
	noiseBurst(c, { duration: 0.07, startFreq: 6000, endFreq: 2000, gain: 0.5, filterType: 'highpass' });
}
function shellCrackHeavy(c: AudioContext): void {
	noiseBurst(c, { duration: 0.1, startFreq: 5000, endFreq: 900, gain: 0.6, filterType: 'highpass' });
}
function impactLight(c: AudioContext): void {
	// Layers a sharp broadband crack under the bass thump so it reads as an
	// explosion, not a soft thud (confirmed fix from the Bouncing Betty pilot).
	noiseBurst(c, { duration: 0.14, startFreq: 5000, endFreq: 400, gain: 0.55, filterType: 'lowpass' });
	tone(c, { duration: 0.35, startFreq: 160, endFreq: 45, gain: 0.6 });
}
function impactHeavy(c: AudioContext): void {
	tone(c, { duration: 0.55, startFreq: 110, endFreq: 30, gain: 0.7 });
}
function missileWhoosh(c: AudioContext): void {
	const dur = 0.6;
	const bufferSize = Math.ceil(c.sampleRate * dur);
	const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) data[i] = Math.random() * 2 - 1;
	const src = c.createBufferSource();
	src.buffer = buffer;
	const filter = c.createBiquadFilter();
	filter.type = 'bandpass';
	filter.frequency.setValueAtTime(1800, c.currentTime);
	filter.frequency.exponentialRampToValueAtTime(400, c.currentTime + dur);
	filter.Q.value = 0.8;
	const g = c.createGain();
	g.gain.setValueAtTime(0.0001, c.currentTime);
	g.gain.exponentialRampToValueAtTime(0.4, c.currentTime + 0.08);
	g.gain.exponentialRampToValueAtTime(0.001, c.currentTime + dur);
	src.connect(filter).connect(g).connect(c.destination);
	src.start();
	src.stop(c.currentTime + dur);
}
function muffledBoom(c: AudioContext): void {
	const dur = 0.5;
	noiseBurst(c, { duration: dur, startFreq: 500, endFreq: 80, gain: 0.55, filterType: 'lowpass' });
	tone(c, { duration: dur, startFreq: 80, endFreq: 30, gain: 0.5 });
}
function dirtScrape(c: AudioContext): void {
	const dur = 0.55;
	const bufferSize = Math.ceil(c.sampleRate * dur);
	const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) data[i] = Math.random() * 2 - 1;
	const src = c.createBufferSource();
	src.buffer = buffer;
	const filter = c.createBiquadFilter();
	filter.type = 'bandpass';
	filter.frequency.value = 1100;
	filter.Q.value = 1.4;
	const lfo = c.createOscillator();
	lfo.type = 'sine';
	lfo.frequency.value = 13;
	const lfoGain = c.createGain();
	lfoGain.gain.value = 350;
	lfo.connect(lfoGain).connect(filter.frequency);
	lfo.start();
	lfo.stop(c.currentTime + dur);
	const g = c.createGain();
	g.gain.setValueAtTime(0.0001, c.currentTime);
	g.gain.exponentialRampToValueAtTime(0.3, c.currentTime + 0.06);
	g.gain.setValueAtTime(0.3, c.currentTime + dur - 0.15);
	g.gain.exponentialRampToValueAtTime(0.001, c.currentTime + dur);
	src.connect(filter).connect(g).connect(c.destination);
	src.start();
	src.stop(c.currentTime + dur);
}
function incendiaryWhooshCrackle(c: AudioContext): void {
	const dur = 1.0;
	const bufferSize = Math.ceil(c.sampleRate * dur);
	const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) data[i] = Math.random() * 2 - 1;
	const src = c.createBufferSource();
	src.buffer = buffer;
	const filter = c.createBiquadFilter();
	filter.type = 'bandpass';
	filter.frequency.setValueAtTime(700, c.currentTime);
	filter.frequency.linearRampToValueAtTime(350, c.currentTime + dur);
	filter.Q.value = 0.6;
	const g = c.createGain();
	g.gain.setValueAtTime(0.0001, c.currentTime);
	g.gain.exponentialRampToValueAtTime(0.35, c.currentTime + 0.15);
	g.gain.exponentialRampToValueAtTime(0.05, c.currentTime + dur);
	src.connect(filter).connect(g).connect(c.destination);
	src.start();
	src.stop(c.currentTime + dur);
	for (let i = 0; i < 14; i++) {
		const delay = Math.random() * 0.9;
		const cdur = 0.02 + Math.random() * 0.03;
		const cbuf = c.createBuffer(1, Math.ceil(c.sampleRate * cdur), c.sampleRate);
		const cdata = cbuf.getChannelData(0);
		for (let j = 0; j < cdata.length; j++) cdata[j] = (Math.random() * 2 - 1) * (1 - j / cdata.length);
		const csrc = c.createBufferSource();
		csrc.buffer = cbuf;
		const cfilter = c.createBiquadFilter();
		cfilter.type = 'highpass';
		cfilter.frequency.value = 2500 + Math.random() * 1500;
		const cg = c.createGain();
		cg.gain.setValueAtTime(0.18, c.currentTime + delay);
		cg.gain.exponentialRampToValueAtTime(0.001, c.currentTime + delay + cdur);
		csrc.connect(cfilter).connect(cg).connect(c.destination);
		csrc.start(c.currentTime + delay);
		csrc.stop(c.currentTime + delay + cdur);
	}
}
function twoPhaseRumble(c: AudioContext): void {
	noiseBurst(c, { duration: 0.03, startFreq: 9500, endFreq: 3500, gain: 1.0, filterType: 'highpass' });
	for (let i = 0; i < 3; i++) {
		const delay = Math.random() * 0.035;
		const cdur = 0.02 + Math.random() * 0.03;
		const cbuf = c.createBuffer(1, Math.ceil(c.sampleRate * cdur), c.sampleRate);
		const cdata = cbuf.getChannelData(0);
		for (let j = 0; j < cdata.length; j++) cdata[j] = (Math.random() * 2 - 1) * (1 - j / cdata.length);
		const csrc = c.createBufferSource();
		csrc.buffer = cbuf;
		const cfilter = c.createBiquadFilter();
		cfilter.type = 'highpass';
		cfilter.frequency.value = 2500 + Math.random() * 1500;
		const cg = c.createGain();
		cg.gain.setValueAtTime(0.72, c.currentTime + delay);
		cg.gain.exponentialRampToValueAtTime(0.001, c.currentTime + delay + cdur);
		csrc.connect(cfilter).connect(cg).connect(c.destination);
		csrc.start(c.currentTime + delay);
		csrc.stop(c.currentTime + delay + cdur);
	}
	const dur = 2.4;
	const bufferSize = Math.ceil(c.sampleRate * dur);
	const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
	const data = buffer.getChannelData(0);
	for (let i = 0; i < bufferSize; i++) data[i] = Math.random() * 2 - 1;
	const src = c.createBufferSource();
	src.buffer = buffer;
	const filter = c.createBiquadFilter();
	filter.type = 'lowpass';
	filter.frequency.value = 90;
	const g = c.createGain();
	g.gain.setValueAtTime(0.0001, c.currentTime + 0.12);
	g.gain.exponentialRampToValueAtTime(0.91, c.currentTime + 0.35);
	g.gain.exponentialRampToValueAtTime(0.001, c.currentTime + dur);
	src.connect(filter).connect(g).connect(c.destination);
	src.start(c.currentTime + 0.12);
	src.stop(c.currentTime + dur);
}
function canisterPop(c: AudioContext): void {
	noiseBurst(c, { duration: 0.16, startFreq: 4500, endFreq: 500, gain: 0.6, filterType: 'lowpass' });
	tone(c, { duration: 0.18, startFreq: 130, endFreq: 40, gain: 0.45 });
}
function clusterLaunch(c: AudioContext): void {
	tone(c, { duration: 0.16, startFreq: 210, endFreq: 70, gain: 0.55, type: 'triangle' });
	noiseBurst(c, { duration: 0.11, startFreq: 2200, endFreq: 700, gain: 0.3, filterType: 'bandpass' });
}
function ricochet(c: AudioContext): void {
	noiseBurst(c, { duration: 0.1, startFreq: 4000, endFreq: 600, gain: 0.5, filterType: 'lowpass' });
}

/** Fades an <audio> element's volume to 0 over fadeMs, then pauses/resets it. */
function fadeOutAndStop(el: HTMLAudioElement, fadeMs: number, fromVolume: number): void {
	const steps = 10;
	const stepMs = fadeMs / steps;
	let i = 0;
	const timer = setInterval(() => {
		i++;
		el.volume = Math.max(0, fromVolume * (1 - i / steps));
		if (i >= steps) {
			clearInterval(timer);
			el.pause();
			el.currentTime = 0;
			el.volume = fromVolume;
		}
	}, stepMs);
}

function playNukeSiren(): void {
	if (!nukeSirenEl) return;
	const totalMs = 5000;
	const fadeMs = 500;
	const targetVolume = 0.85;
	nukeSirenEl.currentTime = 0;
	nukeSirenEl.volume = targetVolume;
	void nukeSirenEl.play();
	setTimeout(() => nukeSirenEl && fadeOutAndStop(nukeSirenEl, fadeMs, targetVolume), totalMs - fadeMs);
}

function playNukeFallingBomb(): void {
	if (!nukeFallingBombEl) return;
	const totalMs = 600;
	const fadeMs = 80;
	const targetVolume = 0.65;
	nukeFallingBombEl.currentTime = 0;
	nukeFallingBombEl.volume = targetVolume;
	void nukeFallingBombEl.play();
	setTimeout(() => nukeFallingBombEl && fadeOutAndStop(nukeFallingBombEl, fadeMs, targetVolume), totalMs - fadeMs);
}

// ---------------------------------------------------------------------
// Public API — one call at launch, one call when the flight animation
// ends (impact phase begins). Internal per-weapon sequencing (staggered
// bomblets, scrape-then-boom, etc.) is handled here, not by the caller.
// ---------------------------------------------------------------------

/** Called once, right when a Fire is sent (a real user gesture — also where unlockAudio() should be called). */
export function playLaunch(weaponId: string): void {
	if (muted || audioCtx === null) return;
	const c = audioCtx;
	switch (weaponId) {
		case 'baby_missile':
			missileWhoosh(c);
			return;
		case 'heavy_cannonball':
			shellCrackHeavy(c);
			return;
		case 'cluster_bomb':
			clusterLaunch(c);
			return;
		case 'nuke':
			shellCrackHeavy(c);
			playNukeSiren(); // runs independently for 5s, not tied to flight duration
			return;
		case 'bouncing_betty':
			return; // no launch sound designed for this weapon — unchanged from the pilot
		default:
			shellCrackLight(c); // basic_shell, mirv, napalm, tunneling_shot, digger
	}
}

/** Called once, when the flight animation ends and impact effects start. impactCount = ShotResolved.allImpacts.length. */
export function playImpacts(weaponId: string, impactCount: number): void {
	if (muted || audioCtx === null) return;
	const c = audioCtx;
	switch (weaponId) {
		case 'basic_shell':
		case 'baby_missile':
		case 'mirv':
			for (let i = 0; i < impactCount; i++) impactLight(c);
			return;
		case 'heavy_cannonball':
			impactHeavy(c);
			return;
		case 'cluster_bomb':
			canisterPop(c);
			for (let i = 0; i < impactCount; i++) missileWhoosh(c);
			return;
		case 'napalm':
			incendiaryWhooshCrackle(c);
			return;
		case 'tunneling_shot':
		case 'digger':
			dirtScrape(c);
			setTimeout(() => audioCtx && muffledBoom(audioCtx), 450);
			return;
		case 'nuke':
			playNukeFallingBomb(); // starts the 2nd-half-of-flight scream
			setTimeout(() => audioCtx && twoPhaseRumble(audioCtx), 600); // crack+rumble at impact
			return;
		case 'bouncing_betty':
			// Unchanged from the pilot — bounce-hit ricochets are triggered
			// per-point elsewhere (GameCanvas already has this wired), the
			// final detonation reuses impactLight.
			impactLight(c);
			return;
		default:
			impactLight(c);
	}
}

/** Exposed for Bouncing Betty's existing per-bounce trigger (unchanged from the pilot). */
export function playRicochet(): void {
	if (muted || audioCtx === null) return;
	ricochet(audioCtx);
}
