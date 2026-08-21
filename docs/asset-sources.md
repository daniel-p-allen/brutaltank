# Audio Asset Sources

Per `PLAN.md` §7.3's sourcing policy: every non-synthesized audio asset in
the repo, its origin, and its license. Synthesized sounds (the majority —
see `client/src/lib/audio/soundManager.ts`) need no entry here since
there's no external source to track.

| File | What it is | Source | License |
|---|---|---|---|
| `client/src/assets/audio/nuke-warning-siren.mp3` | Air raid siren field recording, used for Nuke's launch-to-impact warning | [archive.org/details/air_raid_siren](https://archive.org/details/air_raid_siren) | Public Domain Mark 1.0 — no attribution required |
| `client/src/assets/audio/nuke-falling-bomb.mp3` | Falling-bomb whistle, used for Nuke's final-descent scream | [pixabay.com/sound-effects/film-special-effects-falling-bomb-41038](https://pixabay.com/sound-effects/film-special-effects-falling-bomb-41038/) (originally posted to the Freesound community, CC0, by Daleonfire) | Pixabay Content License — no attribution required |

## Checked but not used

Found during research, deliberately excluded — recorded so the exclusion
is on record, not silently dropped:

- **"Bomb Dropping" by Mike Koenig** (soundbible.com/30-Bomb-Dropping.html) — Attribution 3.0. Would need on-record credit to Mike Koenig if ever reconsidered.
- **"Bomb Whistle long.wav" by chimerical** (freesound.org/people/chimerical/sounds/104551) — Attribution-NonCommercial 4.0. Excluded outright per project policy (no NC-licensed assets).
- **"Slide Whistle - Bomb Dropping" by Universfield** (pixabay.com/sound-effects/slide-whistle-bomb-dropping-352754) — Pixabay Content License, no attribution required, but not the pick (superseded by the falling-bomb recording above).
