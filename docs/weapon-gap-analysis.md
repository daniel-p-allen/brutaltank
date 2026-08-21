# Weapon & Shield Gap Analysis

For each of the 10 weapons and 3 shields: what the real-world thing (or,
where there is no real analog, the established artillery-genre convention)
leads a player to expect ("Should be"), set directly against what our code
currently does ("We have"). Every entry ends with an explicit gap — this
document records findings only; it does not decide anything or propose a
build order. See `PLAN.md`'s "Future ideas" section for which of these have
since been filed as things to maybe act on, and `PLAN.md` section 7 for the
audio/visual implementation plan this research feeds into.

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
*pre-explosive solid round shot; genre precedent = Scorched Earth's "Roller"*

- **Should be**: the real weapon this name references was *pure kinetic* —
  solid cast iron, no charge, no cavity. It battered by impact force alone,
  could skip along open ground, and could plow through several targets in a
  line rather than stopping at first contact. No crater, no blast radius.
  Separately, **the filed "rolls downhill" idea has a direct genre precedent**:
  Scorched Earth's own "Roller" — confirmed via the original manual/community
  docs — *"When they hit ground, they roll downhill until reaching a valley
  or a tank. They then explode"* (blast radius 26, damage 36 in the original
  game). This answers two of the open scoping questions in `PLAN.md`'s filed
  idea directly: **what stops it** (a valley — i.e. a local terrain minimum —
  or a tank), and **does it re-detonate fully or partially** (fully; one
  explosion at the end of the roll, not repeated partial hits).
- **We have**: a heavier, slower-arcing (velocity ×0.85, gravity ×1.1)
  STANDARD explosive shot with a wider blast radius and a deeper crater
  (×1.3) than Basic Shell. No rolling behavior.
- **Gap**: the real thing didn't explode or crater at all. Ours is,
  mechanically, just a bigger Basic Shell — none of the actual
  kinetic/pass-through/no-blast character, nor the Roller-style post-impact
  rolling, is modeled.
- **Sound target**: same shell fire/impact character as Basic Shell, just
  louder/deeper — not currently distinguished. A rolling phase (if built)
  would need its own sound: a low rumbling/scraping roll, distinct from the
  single impact thump every other weapon uses.

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
- **We have (as of the pilot redesign)**: reflects off *every* terrain hit —
  no angle gate — for a budget of 3-5 bounces fixed at the first ground
  contact (flatter first hit = more bounces, steeper = fewer), leaving
  skip-mark divots at each bounce, before a final full detonation. This
  replaces the original `<35°`-incidence-gated version, which turned out to
  be almost unreachable under normal arcing play (a symmetric 45° shot
  lands at ~45° incidence, never qualifying) — the user had never actually
  seen a bounce happen because of it. Both velocity components now decay
  per bounce (per user feedback: "the speed of the bounce must diminish
  after each hit" — vertical loss alone wasn't enough, the shot kept
  moving forward at identical speed for its whole flight regardless of
  bounce count): `vy` ×0.6 (`BOUNCING_ENERGY_RETENTION`), `vx` ×0.85
  (`BOUNCING_HORIZONTAL_RETENTION`, milder since a real skip loses most of
  its energy into the bounce itself, not the forward glide). Also
  confirmed: a close in-flight pass near a tank (within
  `ProjectileSim.TANK_HITBOX_RADIUS`, 14 units) still short-circuits the
  whole bounce sequence into an immediate full-damage direct hit, same as
  every other weapon — this is by design, not a bug, even though the
  always-bounce redesign makes it easier to trigger mid-sequence than it
  used to be. Each bounce deals flat,
  direct-hit-only damage (`Match.BOUNCE_DAMAGE_FRACTION = 0.25`, 25% of
  `centerDamage`, no blast falloff) to any tank within `BOUNCE_DAMAGE_RADIUS`
  (30 units); **confirmed final by the user** — the earlier open question
  (taper vs. flat vs. distance-falloff) is resolved in favor of keeping it
  flat, with the explicit constraint that only the final resting point ever
  triggers the real munition detonation. A bounce that connects also now
  gets its own small client-side "spark" visual and a ricochet sound,
  distinct from the final blast's flash/boom (previously bounce damage
  produced zero visual feedback at all — a bug, fixed alongside the
  mechanic).
- **Gap**: the name references a self-launching bounding mine; the behavior
  modeled is a skipping projectile, which is a different real-world thing
  entirely (closer to a bounced solid cannonball). The damage-per-bounce
  mechanic itself is a reasonable arcade approximation regardless of that
  naming gap.
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
*no real-world named analog; genre precedent = Scorched Earth's "Digger"/"Sandhog" family*

- **Should be**: no real munition is called this, but the genre convention is
  well documented — Scorched Earth's own Digger: *"tunnel when they hit
  ground. If they hit a tank, they fizzle."* That's a real, specific
  mechanical detail our filed "tunnel-then-collapse" idea is currently
  missing: **a direct tank hit is a no-op (fizzle), not a detonation** — only
  a ground hit triggers the tunnel-and-blast behavior.
- **We have**: small blast radius (20) with a high depth multiplier (3.0×) —
  narrow radius plus extreme depth, run through the unmodified crater
  falloff math, reads as a narrow deep shaft rather than a wide bowl. No
  tank-hit special case; a direct tank hit currently behaves like every
  other weapon's direct hit (bonus damage), not a fizzle.
- **Gap**: not applicable against a real weapon (none exists) — but there
  *is* a gap against the genre precedent: no fizzle-on-tank-hit behavior.
- **Sound target**: N/A — no real device to source from. A fizzle (if built)
  would want a distinct anticlimactic sound (a dud/thud, not a bang) to sell
  the "this one didn't work" moment.

### Sandhog — not in our roster
*Scorched Earth weapon, genuinely new concept for us — shield-piercing burrower*

- **Genre precedent**: a Digger variant that *"employs alternate technology"*
  — a small but powerful charge capable of destroying a tank from beneath,
  specifically useful for *"burrowing beneath enemy shields"*. This is a
  **shield-bypass mechanic** — nothing in our current roster ignores an
  active shield; `DamageCalculator`'s shield mitigation currently applies
  uniformly regardless of attack origin or type.
- **Status**: not filed in `PLAN.md` before this write-up. New candidate for
  the roster, not a variant of an existing weapon — would need its own
  scoping pass (how it detects "beneath" a tank on a 1D heightmap, how it
  interacts with `ShieldDef`'s mitigation path).

### Tracer — not in our roster
*Scorched Earth weapon, genuinely new concept for us — non-damaging utility weapon*

- **Genre precedent**: zero destructive capability. Fired purely to see a
  trajectory — *"the trajectory of each shot fired with tracer will stay on
  the screen for some time after the shot is made"* — a targeting/aim-assist
  tool, not a weapon in the damage sense.
- **Status**: not filed in `PLAN.md` before this write-up. We have no
  non-damaging utility category at all; every current `WeaponDef` has a
  `centerDamage`/`blastRadius`. Would need either a zero-damage `WeaponDef`
  or a wholly separate message/mechanic outside the current `Fire` → damage
  pipeline.

### Dirt-restoration weapons — not in our roster
*Scorched Earth family: Dirt Clod/Ball/Ton, Liquid Dirt, Dirt Charge, Earth Disrupter*

- **Genre precedent**: the inverse of every weapon we have — they *build*
  terrain instead of destroying it. Liquid Dirt *"oozes out wherever it
  lands, filling holes and smoothing the terrain."* Dirt Clod/Ball/Ton
  *"explode into a sphere of dirt when hitting something,"* larger variants
  making bigger mounds.
- **Status**: not filed in `PLAN.md` before this write-up. All 10 of our
  weapons only carve craters (`Terrain.applyCrater`); there is no
  terrain-raising operation anywhere in `Terrain.java`. A real new
  mechanic, not a stats variant.

### Riot Charge / Riot Blast / Riot Bomb — not in our roster
*Scorched Earth family — self-rescue digging*

- **Genre precedent**: *"destroy a wedge-shaped section of dirt from around
  your turret"* (Riot Charge/Blast, wider angle for Blast) or *"a spherical
  section of dirt wherever they detonate — they do no damage to tanks"*
  (Riot Bomb). Purpose: digging yourself out after being buried by a
  dirt-restoration weapon or a terrain collapse.
- **Status**: not filed in `PLAN.md` before this write-up. Directly relevant
  to our existing `tankFalls`/terrain-collapse mechanics (a tank whose
  ground gives way already falls per `Match.applyDetonations`) — a
  self-rescue weapon would be the natural counterpart once burial/collapse
  can actually trap a tank rather than just drop it.

### Leapfrog — not in our roster
*Scorched Earth weapon — sequential multi-warhead, distinct from our MIRV*

- **Genre precedent**: *"three warheads which launch one after another"* —
  sequential, not simultaneous. Our MIRV splits all children at once at a
  shared apex; Leapfrog's pattern (launch, then re-launch, then re-launch)
  is a different multi-hit shape entirely.
- **Status**: not filed in `PLAN.md` before this write-up. Would reuse
  MIRV's split infrastructure conceptually but on a delay/re-trigger basis
  rather than a single apex-split.

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

## Sources (weapons)

War History Online (S-mine); Wikipedia (MIRV, cluster munition, bunker
buster, round shot, S-mine, Scorched Earth); Arms Control Association & Arms
Control Center (MIRV, cluster munitions); Britannica & HowStuffWorks (napalm,
bunker buster); WION (bunker-buster fuzing); HandWiki (round shot); Quora &
DENIX (artillery/blast noise); Zapsplat & Wikipedia (missile launch
acoustics); Jalopnik (cluster munition sound); SoundCy & Gizmodo (nuclear
blast acoustics).

---

# Shield Gap Analysis

For each of the 3 shields: what genre convention (Scorched Earth's own
shield tiers) and the nearest real-world analog lead a player to expect
("Should be"), set directly against what our code currently does ("We
have"). Same format and evidentiary standard as the weapon roster above —
findings only, no build order implied.

## Holds true for every shield — the baseline gap

- **Visual**: zero distinct visual for any of the three. Activation resolves
  as a plain no-op `ShotResolved` with no projectile and no special-cased
  client rendering — Scorched Earth's own reference implementation drew a
  literal circle around the tank on activation (per the manual: *"a circle
  will appear around your tank, indicating that the shield is active. Once
  the shield is destroyed, the circle will disappear"*) and our client
  currently does nothing analogous.
- **Sound**: zero distinct sound for any of the three, at any of their three
  natural sound moments — activation, absorbing-a-hit, and breaking. All
  three currently reuse whatever generic UI feedback a no-op turn produces.
- **Mechanical uniformity despite different real mechanics**: Absorb,
  Deflect, and Reflect model three meaningfully different real behaviors
  (graduated percentage mitigation vs. binary one-hit block vs. mitigation-
  plus-cashback), but all three are wired through the identical
  `activeShieldId` no-op path in `Fire` resolution — the server-side
  differentiation lives entirely in `DamageCalculator`'s use of
  `damageMultiplier` and the two break-condition constants, not in anything
  the client can currently show the player mid-match.
- **No feedback on remaining shield health**: Scorched Earth's Tank Control
  Panel displayed shield strength as a running percentage while engaged. Our
  `ABSORB_BREAK_THRESHOLD=80` is a hidden cumulative counter server-side —
  nothing surfaces "how much shield is left" to the client at all, for any
  of the three types.

## The roster

### Absorb — dmg×0.5, price 200, stock 5, breaks at 80 cumulative absorbed
*genre precedent = Scorched Earth's base-tier "Shield" ($20,000/3); no real personal-energy-shield analog*

- **Should be**: Scorched Earth's plain Shield is the closest genre
  precedent — general damage absorption up to a cumulative capacity roughly
  equal to a tank's own life total (*"a shield can take about as much damage
  as a tank, so in effect they double your life capacity"*), a direct hit on
  the shield itself doesn't detonate (*"a weapon which hits a shield
  directly will not explode, though it will damage the shield slightly"*),
  and — notably — the base-tier Shield (unlike Heavy Shield) is subject to a
  **random shield-failure chance**: a small per-hit probability the shield
  simply fails to absorb at all, letting the hit through undiminished.
  There is no real "personal energy shield" technology to check against
  (same category as our Nuke precedent — genre convention is the applicable
  bar, not physics), though reactive armor's "explosive sandwich that
  dissipates a fraction of incoming energy per hit, degrading with
  cumulative hits" is a loose real-world behavioral cousin worth naming for
  visual/behavioral grounding, not literal derivation.
- **We have**: a flat 50% damage reduction on every hit while active,
  cumulative absorbed damage tracked against an 80-point hidden threshold,
  no failure-chance randomness, no visible remaining-capacity indicator.
- **Gap**: the core "graduated capacity, cumulative wear" shape matches
  genre precedent well. Two things genre precedent has that we don't: (1)
  the random failure-chance flavor that makes the base tier riskier/cheaper
  than the top tier — currently all three of our shields are deterministic;
  (2) any visible readout of remaining capacity, which the original UI had
  (percentage display) and ours doesn't.
- **Sound target**: thin sourcing (no documented Scorched Earth-specific
  audio) — falls back on the broader sci-fi energy-shield sound trope, which
  is a well-established, well-documented category (multiple commercial SFX
  libraries independently converge on the same shape): a rising electronic
  "power-up" hum/whine on activation, a lower ambient hum while sustained, a
  sharp electronic crackle/zap on each absorbed hit (louder/more distorted
  as cumulative damage climbs toward the break threshold, if a graduated cue
  is wanted), and a distinct short "power-down"/fizzle on break — not the
  generic explosion-thump used for damage-dealing weapons.

### Deflect — dmg×0.0 (first hit), near-miss splash ×0.6, price 250, stock 5, breaks after one use
*genre precedent = Scorched Earth's "Force Shield" ($25,000/3); real analog = active protection systems (Trophy APS) and explosive reactive armor*

- **Should be**: Scorched Earth's Force Shield is described as deflecting
  projectiles away rather than simply soaking them (*"Force Shields deflect
  projectiles away from you, and are generally capable of sustaining more
  damage than normal shields"*) — a step up from the base Shield in both
  mechanic-flavor (deflection vs. absorption) and capacity. The nearest real
  analog for "deflect/negate one incoming projectile" is a modern active
  protection system: Trophy APS detects an incoming projectile via 360°
  radar, computes an intercept point, and fires a directed countermeasure
  burst that shreds the incoming warhead in under a tenth of a second — a
  genuinely real "one incoming shot, fully neutralized before it lands"
  precedent, distinct from Absorb's graduated-mitigation model. Explosive
  reactive armor is the closer "one-time, breaks-after-first-use" cousin: a
  single-use explosive sandwich that reacts to one impact and is then
  spent, exactly matching our "breaks after use" framing, though ERA
  disrupts the projectile's angle/momentum rather than fully negating it.
- **We have**: the first direct hit while active is fully negated (×0.0
  damage), splash from a near-miss still applies at 60%, and the shield
  breaks after that one use — a faithful match to "one full negation, then
  spent."
- **Gap**: none significant on the core mechanic — full-negation-then-break
  lines up with both the genre's Force Shield framing and the real APS/ERA
  precedent. The gap is entirely presentational: none of APS's "detect,
  compute, fire a visible countermeasure burst" character, nor ERA's
  "visible violent counter-detonation," nor even Scorched Earth's plain
  deflection circle, is expressed anywhere in our client.
- **Sound target**: two distinct real-world-groundable events, same shape as
  our existing weapons' two-phase entries (Bouncing Betty, Nuke): a sharp,
  fast metallic "ping/deflect" or brief energy-crackle at the moment of the
  blocked hit (echoing APS's near-instantaneous directed countermeasure, or
  the sci-fi "shield deflect" SFX trope specifically catalogued as its own
  subcategory separate from generic "shield hit"), followed immediately by
  the break/fizzle cue since Deflect always breaks on that same hit — the
  two events are essentially simultaneous for this shield, unlike Absorb
  where break is a separate, later moment.

### Reflect — dmg×0.7 (-30%), cashback 20% of blocked dmg next turn, price 300, stock 5
*genre precedent = Scorched Earth's "Heavy Shield" ($30,000/2); no direct real analog for the cashback mechanic*

- **Should be**: Scorched Earth's top tier, Heavy Shield, is framed as the
  reliability upgrade rather than a different mechanic: *"immune to the
  shield failures which often plague lesser shields... capable of
  sustaining tremendous amounts of damage."* Read against the base Shield's
  random-failure quirk noted above, Heavy Shield's genre-precedent identity
  is specifically "the deterministic, high-capacity, no-surprises tier" —
  not a reflection/cashback mechanic at all. Our Reflect's actual behavior
  (name notwithstanding — the spec is explicit that this is simplified
  mitigation + cashback, not literal projectile reflection) has no
  genre-precedent or real-world analog for the "20% of blocked damage
  returned as bonus cash" part; that appears to be a bespoke
  economy-integration mechanic layered onto a shield-shaped chassis, tying
  it into M4's shop/economy system rather than into any historical or genre
  shield behavior.
- **We have**: -30% damage taken (between Absorb's -50% and Deflect's
  -100%-on-one-hit), plus 20% of the damage that was blocked returned as
  cash on the player's next turn. No projectile ever actually reflects back
  at an opponent.
- **Gap**: the name "Reflect" sets an expectation (return the shot
  elsewhere) that neither the real Force Shield precedent's actual
  damage-avoidance flavor nor our own implementation delivers — it's
  mitigation+economy, and the doc that defines it is explicit that literal
  reflection was deliberately deferred. Separately, our numeric positioning
  (mildest percentage mitigation of the three, since -30% sits between
  Absorb's -50% and Deflect's binary block) inverts Scorched Earth's own
  tier ordering, where the equivalent top-price tier (Heavy Shield,
  $30,000, our most expensive at 300) is framed as the *strongest*/most
  reliable, not a middle-ground percentage. Whether that's worth
  reconsidering is a design call (see Gameplay integration recommendations
  below), not a firm finding.
- **Sound target**: no real-world or genre-documented reference exists for a
  "damage mitigated + cash returned" event, so this is necessarily inferred
  rather than sourced (flagged, same evidentiary weight as the weapon doc's
  Napalm sound-target caveat) — a hit-absorption cue in the same family as
  Absorb's crackle (since the underlying mechanic is also percentage
  mitigation, just milder), plus a separate, distinct short "cha-ching"/
  currency cue on the following turn when cashback is credited, to make the
  economic payoff legible as its own event rather than folding silently
  into the next turn's UI.

## Gameplay integration recommendations

**Absorb**
- *Activation*: rising electronic hum/whine (0.3-0.5s) as the shield stands
  up, paired with a translucent dome or ring rendered around the tank sprite
  — directly modeled on Scorched Earth's own reference UI (*"a circle will
  appear around your tank"*), just rendered as a shader/sprite instead of a
  flat circle.
- *Absorbing a hit*: a sharp crackle/zap layered under the existing generic
  blast SFX (not replacing it — the incoming shot still detonates against
  the shield, it just does less), with the dome flashing or rippling at the
  impact point; consider scaling the crackle's intensity/pitch as cumulative
  absorbed damage climbs toward the 80-point break threshold, giving players
  an audible read on "this shield is getting close to failing" without
  needing a numeric readout.
- *Breaking*: a short, lower "power-down" fizzle distinct from the
  activation hum (down-sweep rather than up-sweep), dome visibly
  shattering/dissipating rather than just vanishing.

**Deflect**
- *Activation*: same dome-stand-up language as Absorb for visual consistency
  across the three (players should be able to tell "a shield is up" at a
  glance regardless of type), but a tighter/harder-edged shader (e.g.
  faceted/geometric rather than Absorb's soft glow) to read as "hard block"
  rather than "soaking" — echoing the APS framing of a fast, precise
  intercept rather than general absorption.
- *Absorbing a hit*: since this is always the shield's only and final
  moment, combine impact and break into one compact event — a bright, fast
  metallic "ping" or energy-crackle burst (the sci-fi "shield deflect" SFX
  subgenre specifically, not the generic "shield hit" one) simultaneous
  with the dome visibly shattering outward, distinguishing it from Absorb's
  softer fizzle.
- *Breaking*: same event as above (they're the same moment for this shield)
  — no separate break cue needed, unlike Absorb.

**Reflect**
- *Activation*: same base dome/hum language, perhaps tinted or textured
  differently (e.g. a warmer color, since this is the "economy" shield) to
  visually distinguish it from Absorb at a glance despite the similar
  percentage-mitigation mechanic.
- *Absorbing a hit*: a crackle cue similar to Absorb's but milder (since
  -30% is a smaller mitigation than -50%), immediately followed on the
  *next turn's start* by a distinct short currency/cashback cue (a
  "cha-ching" or coin-chime, separate from the shield-audio family entirely,
  since it's an economy event not a combat one) — this is the one moment
  across all three shields that isn't purely a combat-feedback cue, and
  treating it as its own event class prevents cashback from getting lost
  inside next turn's generic UI.
- *Breaking*: reuse Absorb's fizzle-down cue if Reflect gets a
  cumulative-wear break condition (see mechanics note in `PLAN.md`'s Future
  ideas); if it stays a fixed-charges/uses model instead, a break cue closer
  to Deflect's sharper shatter may read better.

**Speculative, optional mechanics reconsiderations** — filed in `PLAN.md`'s
Future Ideas as explicitly speculative, not recommendations:
- Scorched Earth's own tier ordering (Shield → Force Shield → Heavy Shield)
  frames the *most expensive* tier as the most reliable/highest-capacity,
  with the *cheapest* tier carrying the random-failure risk. Our current
  price ordering (Absorb 200 < Deflect 250 < Reflect 300) puts the mildest
  percentage mitigation (-30%) at the highest price point.
- Scorched Earth's base Shield's random per-hit failure chance (absent from
  Heavy Shield) is a real, documented genre mechanic none of our three
  shields currently model — all three are deterministic.
- A fourth, Heavy-Shield-equivalent top tier isn't currently in our roster
  at all.

## Sources (shields)

Scorched Earth 2000 User Manual (scorch2000.com, mirrored at
cs.brandeis.edu) — general shield-absorption description, activation-circle
UI description; Abandonware DOS manual text (abandonwaredos.com) — exact
per-tier pricing and descriptions for Shield/Force Shield/Heavy Shield, and
the shield-failure-chance / Heavy Shield immunity detail; TV Tropes
(Scorched Earth video game page) — corroborating description of Force
Shield deflection and Heavy Shield failure-immunity; Military Machine,
SlashGear, The Defense Post, Leonardo DRS (Trophy Active Protection System)
— real-world "detect incoming projectile, intercept before impact"
precedent for Deflect; Military History Wiki/Fandom, DefenseFeeds
(explosive reactive armor) — real-world "one-time-use, disrupts on first
impact" precedent for Deflect's break condition; Pond5, Motion Array,
Epidemic Sound, SFX Engine, Envato Elements (sci-fi energy-shield SFX
library listings) — corroborating a well-established genre sound convention
(activation hum, ambient drone, impact crackle, distinct deflect-ping
subcategory) across multiple independent commercial sources, used as
thin-but-convergent sourcing in the same evidentiary spirit as the weapon
doc's Napalm sound-target caveat; Pond5/Envato (riot-shield/baton-impact SFX
listings) — checked as a possible real-world grounding reference for impact
character, found less specifically useful than the sci-fi energy-shield
convention and not relied on further.
