# Weapon Roster Gap Analysis

For each of the 10 weapons: what the real-world thing (or, where there is no
real analog, the established artillery-genre convention) leads a player to
expect ("Should be"), set directly against what our code currently does
("We have"). Every entry ends with an explicit gap — this document records
findings only; it does not decide anything or propose a build order. See
`PLAN.md`'s "Future ideas" section for which of these have since been filed
as things to maybe act on.

Also published as a formatted artifact during the research session (private,
not linked here since artifact URLs aren't durable repo references — this
file is the source of record).

## Holds true for every weapon — the baseline gap

- **Tracking**: should-be varies by weapon (see below); what-we-have is
  identical everywhere — one ballistic parabola per launch, nothing homes or
  adjusts mid-flight, ever (as of this writing — see the Baby Missile homing
  idea filed in `PLAN.md`).
- **Damage**: should-be varies (kinetic vs. blast vs. burn vs. fragmentation);
  what-we-have is the same formula for all 10 — `centerDamage *
  smoothstep(distance / blastRadius)`, ×1.3 within 8 units (a "direct hit").
- **Explosion visual**: should-be a distinct look per weapon type; what-we-have
  is one identical generic effect for all 10 — an orange flash growing from
  radius 6 to 26 over 250ms. The crater shape is the only thing that
  currently varies per weapon.

## The roster

### Basic Shell — 25 dmg / 30 rad, free & unlimited
*generic unguided shell*

- **Should be**: no specific real analog — the plain, no-gimmick baseline
  every artillery game has. Straight ballistic arc, standard blast.
- **We have**: exactly that — default power scale, default gravity, default
  crater depth (1.0×). Free and unlimited ammo.
- **Gap**: none — this is the reference case, by design.
- **Sound target**: sharp high-pitched crack on firing; deep low-frequency
  thump on impact, sharper up close and more rolling at distance.

### Baby Missile — 18 dmg / 22 rad, free starter, qty 5
*generic light missile*

- **Should be**: no specific real analog. Generically: a light, fast, cheap
  projectile — and a missile's launch should sound and read as
  flight-propelled, not gun-fired.
- **We have**: same ballistic arc as Basic Shell, 15% faster/flatter velocity,
  smaller/shallower crater. Weaker damage, cheap starter stock.
- **Gap**: stats-level none; presentation-level no visual/audio distinction
  from a plain shell yet.
- **Sound target**: a sustained "whoosh" from exhaust gases — a longer sound
  event than a gun's single crack, not currently distinguished from Basic
  Shell's launch.

### Heavy Cannonball — 40 dmg / 45 rad, 150, qty 3
*pre-explosive solid round shot*

- **Should be**: the real weapon this name references was *pure kinetic* —
  solid cast iron, no charge, no cavity. It battered by impact force alone,
  could skip along open ground, and could plow through several targets in a
  line rather than stopping at first contact. No crater, no blast radius.
- **We have**: a heavier, slower-arcing (velocity ×0.85, gravity ×1.1)
  STANDARD explosive shot with a wider blast radius and a deeper crater
  (×1.3) than Basic Shell.
- **Gap**: the real thing didn't explode or crater at all. Ours is,
  mechanically, just a bigger Basic Shell — none of the actual
  kinetic/pass-through/no-blast character is modeled.
- **Sound target**: same shell fire/impact character as Basic Shell, just
  louder/deeper — not currently distinguished.

### MIRV — 15 dmg / 25 rad ×4, 300, qty 2
*Multiple Independently targetable Reentry Vehicle*

- **Should be**: real weapon — a "bus" releases warheads sequentially after
  burnout, maneuvering between releases to send each toward a different
  target at a different time/angle. Genre convention (Scorched Earth and
  every descendant) instead expects a simultaneous fan-out split at one
  shared apex — the more relevant bar here, since this is an artillery game,
  not a missile-defense sim.
- **We have**: matches genre convention — one shot climbs to its apex, splits
  into exactly 4 children simultaneously, fanned ±5°/±15° around the apex's
  real direction of travel (fixed in the same session as this analysis —
  children used to relaunch at the original steep angle and shoot further up
  instead of fanning out).
- **Gap**: none against the genre-convention bar. Against the literal
  real-world MIRV (sequential, independently-targeted release) — total, but
  that was never the applicable expectation for this genre.
- **Sound target**: N/A for the real weapon — separation happens silently in
  space, no reference sound exists.

### Napalm — 20 dmg / 50 rad, 250, qty 2
*incendiary gel*

- **Should be**: not primarily explosive — a sticky gel that clings to
  surfaces and burns over time at 1,000–2,700°C. Damage should be
  sustained/spreading, not instant. The "boom" people picture is the
  delivery canister rupturing, not the napalm detonating.
- **We have**: standard single-point instant detonation like every other
  weapon. The only nod to napalm's real character is shape: lowest depth
  multiplier in the roster (0.2×) against the widest blast radius (50) — a
  shallow, wide dish instead of a pit.
- **Gap**: the core real behavior — sustained burn/damage-over-time, clinging
  to surfaces, spreading into dips — has no mechanic at all. Only the
  crater's shape gestures at it; the damage itself is identical in kind to a
  normal shell.
- **Sound target**: a whooshing roar + crackle as fuel ignites and spreads —
  longer and lower than an explosive crack. (Thin sourcing; inferred from the
  ignition-not-detonation mechanism.)

### Tunneling Shot — 30 dmg / 25 rad, 200, qty 2
*earth-penetrating / "bunker buster"*

- **Should be**: hardened casing + kinetic energy burrows through soil/rock
  before a delayed fuze (deceleration/strain-sensing) detonates — penetrate
  first, explode after, at depth. Real examples (GBU-28, GBU-57) reach
  150–200+ ft into earth.
- **We have**: continues through terrain after first contact (collision
  checks suspended) for up to 40 world-units, then detonates. A visible
  bore-track of 3 marks leads from entry point to the final, larger crater.
- **Gap**: none significant — penetrate-then-detonate is the real
  expectation, and it's modeled, including a visible track.
- **Sound target**: a "muffled boom" — more energy into the ground, less into
  the air — not currently distinguished from a surface detonation.

### Bouncing Betty — 25 dmg / 30 rad, 220, qty 2
*S-mine (WWII German bounding mine)*

- **Should be**: a buried mine — pressure/tripwire trigger, ~4s delay, a
  propellant charge launches the body ~1m straight up, then a second
  ~0.5s-delay fuze detonates it mid-air, scattering shrapnel 360°. It
  launches itself once — it never skips along the ground.
- **We have**: reflects off shallow-angle terrain hits (<35°) at ×0.6
  velocity, up to 3 times, while still in ballistic flight — a
  skipping-stone behavior — leaving skip-mark divots at each bounce, before a
  final detonation.
- **Gap**: the name references a self-launching bounding mine; the behavior
  modeled is a skipping projectile, which is a different real-world thing
  entirely (closer to a bounced solid cannonball).
- **Sound target**: two distinct real events — a propellant "pop" at launch,
  then a separate airburst bang ~0.5s later. Ours currently plays neither
  event distinctly.

### Cluster Bomb — 20 dmg / 20 rad primary + 4×12 rad bomblets, 280, qty 2
*cluster munition*

- **Should be**: a dispenser opens mid-air, scattering many bomblets over a
  wide area; each fuzes individually on landing, at slightly different
  times. (Real cluster munitions are banned under the 2008 Convention on
  Cluster Munitions — noted as real-world context, not a design constraint.)
- **We have**: one ballistic shot to impact, which detonates a primary blast
  plus 4 bomblets at fixed ±25/±50 world-unit offsets, all simultaneously —
  no individual bomblet flight physics (a documented simplification in the
  code).
- **Gap**: simultaneous vs. staggered bomblet timing is the main difference —
  a reasonable arcade-scale approximation of "one shot, several explosions
  over an area."
- **Sound target**: a canister-opening event, then a spray of successive
  smaller explosions (potentially over several seconds) rather than one
  centralized boom — ours would currently sound like one boom.

### Digger — 10 dmg / 20 rad, 120, qty 3
*no real named analog*

- **Should be**: no real munition is called this. The closest real concept is
  generic earth-penetration (see Tunneling Shot) — "Digger" itself is only a
  generic/fictional label or demining-equipment name, not a distinct weapon
  class, so there's no specific real target to match.
- **We have**: small blast radius (20) with a high depth multiplier (3.0×) —
  narrow radius plus extreme depth, run through the unmodified crater
  falloff math, reads as a narrow deep shaft rather than a wide bowl. No new
  mechanic, purely a stats/shape difference from Basic Shell.
- **Gap**: not applicable — there's no real weapon to be measured against.
- **Sound target**: N/A — no real device to source from.

### Nuke — 70 dmg / 90 rad, 600, qty 1
*deliberately not modeled*

- **Should be**: the genre expectation, not the literal real-world one — the
  single biggest, most dramatic weapon in the roster. (No real nuclear
  weapon — blast wave, thermal pulse, radiation, fallout — is a reasonable
  bar for an artillery game to try to meet.)
- **We have**: same STANDARD behavior as Basic Shell, scaled to the largest
  blast radius (90) and a 2.3× depth multiplier that, combined with the
  terrain floor's headroom, lets a center hit plunge close to the actual
  bottom of the screen. Scarcest shop item (3 in stock per phase).
- **Gap**: none against the applicable (genre) bar — "biggest, most
  dramatic" is what it delivers.
- **Sound target**: a two-phase event — sharp initial bang, then a long, deep
  thundering rumble that "resonates in the chest," shockwave arriving
  several seconds after the visual. Ours currently plays one instantaneous
  effect, same as every other weapon.

## Sound: broader findings

Not scheduled — filed as a future plan item, not implemented.

- **Distance shapes character, not just volume**: across sources, blast
  sound near the source reads as a sharp, high-pressure crack; the same
  event at distance reads as a rolling, lower-frequency boom — a shape
  change, not just quieter. Not something a fixed-camera 2D artillery game
  can vary by literal listener distance, but worth knowing as a reference
  point.
- **Scorched Earth's own sound design**: thinly sourced — the original 1991
  game used PC-speaker-era audio, sparse effects, no music. No documentation
  found on its specific per-weapon sound choices, so it's not a usable
  reference in itself.

## Sources

War History Online (S-mine); Wikipedia (MIRV, cluster munition, bunker
buster, round shot, S-mine, Scorched Earth); Arms Control Association & Arms
Control Center (MIRV, cluster munitions); Britannica & HowStuffWorks (napalm,
bunker buster); WION (bunker-buster fuzing); HandWiki (round shot); Quora &
DENIX (artillery/blast noise); Zapsplat & Wikipedia (missile launch
acoustics); Jalopnik (cluster munition sound); SoundCy & Gizmodo (nuclear
blast acoustics).
