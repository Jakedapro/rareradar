# Capture and stat mechanics (`core/mechanics`)

Ported from **Cobblemon 1.8.2** (tag `1.8.2`, commit `643bf9068b51c829316429916373e5aa9864b8b9`). Source of truth: the code; the wiki was not reachable.

## Capture probability

Source files:
* `pokeball/catching/calculators/CobblemonCaptureCalculator.kt` (the default calculator per `CobblemonConfig.kt:240-241`);
* `api/pokeball/catching/calculators/CriticalCaptureProvider.kt`;
* ball definitions in `api/pokeball/PokeBalls.kt`.

```
m  = mutator((3·maxHP − 2·curHP) · darkGrass(=1) · catchRate · inBattle(1 | 0.5), ballBonus) / (3·maxHP)
m *= statusBonus (sleep/freeze 2.5; paralysis/burn/poison 1.5) · levelBonus (lvl < 13: max((36 − 2·lvl)/10, 1), integer division)
m *= levelPenalty, only when a battling thrower's highest level is below the target's:
     max(0.1, min(1, 1 − ((lvl − thrower)/(maxLevel/2))))   (integer division)
S  = round(65536 / (255/m)^0.1875)      one shake succeeds when nextInt(65537) < S
c  = round(m · caughtMultiplier / 6)     critical capture when nextInt(256) < c
     caughtMultiplier by Pokédex caught count: ≤30 → 0, ≤150 → 0.5, ≤300 → 1, ≤450 → 1.5, ≤600 → 2, else 2.5
P  = pc · ps + (1 − pc) · ps⁴             critical capture needs 1 shake; otherwise 4
```

* A Master Ball (`GuaranteedModifier`) always succeeds.
* Expected balls per capture is `1/P`, a geometric model of independent throws.

## What the client cannot see

A wild Pokémon's **current HP** and **status** are not reliably visible to the client, nor is the server-side Pokédex caught count.
* `CaptureMath.evaluate` returns *unknown* and lists the missing inputs.
* `CaptureMath.bounds` gives the minimum and maximum over the unknown ranges where that is sound.
* RareRadar therefore never states a single capture percentage for a wild Pokémon without saying which inputs were assumed.

## Stats and experience

`StatMath` covers stat formulas (base, IV, EV, level, nature), the nature table and experience per level per experience group. Each part is transcribed from the 1.8.2 sources, cited in code comments.

## Caveats

* Servers (including CobbleGalaxy) can change the config or add plugins that alter capture. **Nothing here is verified against a live server.**
* The port was written by a background worker and reviewed by the lead. The review was a line-by-line check of lines 55–98 of the calculator, the ball list in `PokeBalls.kt`, and a hand-computed test (`reviewerHandCalculation`).
