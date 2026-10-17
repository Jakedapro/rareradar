# RareRadar — Codebase Audit (Phase 1)

Audit date: 2026-10-17. Audited commit: `1b1ed47` ("Bump version to 1.1.0").

## 1. Versions (from `gradle.properties`, `build.gradle`, `fabric.mod.json`)

| Component | Version | Source |
|---|---|---|
| Minecraft | 1.21.1 | `gradle.properties` |
| Yarn mappings | 1.21.1+build.3 | `gradle.properties` |
| Fabric Loader | 0.17.2 | `gradle.properties` |
| Fabric API | 0.116.6+1.21.1 | `gradle.properties` |
| Fabric Loom | 1.8-SNAPSHOT | `build.gradle` |
| Cobblemon | 1.8.2 (README); `modCompileOnly name: 'cobblemon'` from an uncommitted `libs/cobblemon.jar`; `fabric.mod.json` accepts `*` | README / build |
| Kotlin stdlib | 2.0.21 (compileOnly, needed for Cobblemon's Kotlin API) | `build.gradle` |
| Java | 21 | `build.gradle` |

## 2. Verified existing features

Every claimed feature was checked against the source:

| Feature | Present? | Where |
|---|---|---|
| Client-side Pokémon entity scan | Yes, every client tick over `world.getEntities()` | `RareRadarClient.scan` |
| Legendary / Mythical / Ultra Beast / Paradox detection | Yes, via Cobblemon species/form labels (`legendary`, `mythical`, `ultra_beast`, `paradox`) | `Rarity.of` |
| Shiny detection | Yes, `Pokemon.getShiny()` | `Rarity.of` |
| Highlighting | Yes, glow outline via two mixins (`hasOutline`, `getTeamColorValue`) | `mixin/*` |
| Distance, direction arrow, coordinates HUD | Yes | `RareRadarClient.render/arrow` |
| AutoHunt | Yes: walks to the nearest rare and throws balls from the hotbar | `AutoHunt` |
| A* pathfinding | Yes: 8-way, 1-block step-up, 3-block drop, hazard blocks, 6000-node cap | `Pathfinder` |
| Automated aiming | Yes: smooth yaw/pitch clamp (7°/tick) | `AutoHunt.faceToward` |
| Poké Ball selection | Yes, but **ranks Master Ball first** (see debt #3) | `AutoHunt.selectBall` |
| Battle move selection | Yes ("Auto Train"): power×accuracy heuristic | `AutoHunt.handleBattle` |
| Stuck detection | Yes: repath after 30 still ticks, abort after 160 | `AutoHunt.follow` |
| Settings screen + JSON config | Yes (`` ` `` key, `config/rareradar.json`) | `Settings`, `SettingsScreen` |
| Tests | **None** | — |

There are no Cobblemon event subscriptions and no network/packet hooks. All state comes from client-visible entities and `CobblemonClient` storage/battle.

## 3. Architecture (before V3)

```
                    Fabric client entrypoint
                    RareRadarClient.onInitializeClient
                           │
       ┌───────────────────┼──────────────────────────────┐
       │                   │                              │
 KeyBindings (J,K,L,`)  END_CLIENT_TICK                 HudRenderCallback
       │                   │                              │
       │          ┌────────┴────────┐                     │
       │          ▼                 ▼                     ▼
       │   scan(): all entities   AutoHunt.tick()     render(): hits list
       │   → Rarity.of() → hits   ├─ nearestRare()      (copies list per frame)
       │                          ├─ Pathfinder.find()  (sync A*, ≤6000 nodes)
       │                          ├─ faceToward()/keys
       │                          ├─ selectBall()/interactItem
       │                          └─ handleBattle() → CobblemonClient battle
       ▼
 SettingsScreen ── Settings (Gson, config/rareradar.json)

 Mixins (render thread, per entity, per frame):
   MinecraftClient.hasOutline ─┐
   Entity.getTeamColorValue  ──┴─► Rarity.of(entity)  (allocates a HashSet each call)
```

## 4. Technical debt and risks found

| # | Severity | Issue | Location |
|---|---|---|---|
| 1 | High (perf) | `Rarity.of` allocates a `HashSet` and copies label sets. It is called from both mixins for **every entity, every rendered frame**, plus once per entity per tick in `scan` and `nearestRare`. That is O(entities × FPS) allocations. | `Rarity.of`, mixins |
| 2 | High (safety) | AutoHunt and AutoTrain run on **any** server; nothing gates public multiplayer. | `AutoHunt.toggle` |
| 3 | High (resources) | Ball preference is `master_ball, ultra_ball, …`, so a Master Ball is spent on the first rare found. There are no reserves and no protected balls. | `AutoHunt.BALL_PREFERENCE` |
| 4 | Medium | The static `AutoHunt.target` holds a strong entity reference across world changes and disconnects. When `world == null` it sets `active=false` without releasing movement keys. | `AutoHunt.tick` |
| 5 | Medium | There is no capture verification. The bot keeps throwing on a cooldown and never learns whether a ball hit, broke free or captured. | `AutoHunt.tick` |
| 6 | Medium | There is no maximum run time and no ball-spend limit. The emergency stop is only the toggle key. | `AutoHunt` |
| 7 | Medium (perf) | The A* runs synchronously on the client tick, up to 6000 expansions × 8 neighbours with several `getBlockState` calls each. There is no wall-clock budget. | `Pathfinder.find` |
| 8 | Low | The diagonal-move corner check only tests foot-level blocks, so the player can clip head-height corners. | `Pathfinder.find` |
| 9 | Low | Config values are not validated (e.g. `scanRange` ≤ 0 or huge from hand-edited JSON). A corrupt config is silently replaced by defaults with no backup. | `Settings.load` |
| 10 | Low (perf) | The HUD copies the hit list every frame (`new ArrayList<>(hits)`); tick and render are both on the client thread, so the copy is unnecessary. `scan` re-sorts and re-allocates every tick even when the HUD is off. | `RareRadarClient` |
| 11 | Low | `handleBattle` answers any battle request while AutoTrain is active, including battles the user started manually. | `AutoHunt.handleBattle` |
| 12 | Build | The build requires a hand-supplied `libs/cobblemon.jar` (gitignored) and there is no Gradle wrapper, so it is not reproducible. | `build.gradle` |

Threading: all game access happens on the client thread (tick and HUD callbacks), so there are no data races today. New V3 disk I/O must stay off this thread (§ Phase 17).

## 5. Baseline build (before any change)

Command: `gradle build` (Gradle 8.14.3, OpenJDK 21.0.12), run in the cloud development container.

Result: **FAILED at plugin resolution.**

```
Plugin [id: 'fabric-loom', version: '1.8-SNAPSHOT'] was not found ...
  could not resolve plugin artifact 'fabric-loom:fabric-loom.gradle.plugin:1.8-SNAPSHOT'
  Searched in: maven(https://maven.fabricmc.net/), MavenRepo, Gradle Central Plugin Repository
```

Cause: the container's network policy blocks `maven.fabricmc.net`, Mojang's piston/libraries hosts and Modrinth. `libs/cobblemon.jar` is also absent. **The Minecraft mod cannot be compiled in this environment, neither before nor after V3 changes.** Maven Central is reachable, so the new Minecraft-independent `core/` module (see roadmap) is compiled and unit-tested here. Minecraft-facing code was checked by hand against the Cobblemon 1.8.2 sources (GitLab tag `1.8.2`) and Yarn 1.21.1 mapping files (GitHub `FabricMC/yarn`). It must still be compiled and tested in-game by a developer with normal network access.

## 6. Verified Cobblemon client API facts used by V3

From the Cobblemon `1.8.2` sources:

* `EmptyPokeBallEntity.CaptureState` = `NOT, HIT, FALL, CRITICAL, SHAKE, CAPTURED, BROKEN_FREE`. The state is a synched entity data field (`CAPTURE_STATE`), so it **is visible to the client**. This gives a real capture-verification signal: the client can watch its own thrown ball reach `CAPTURED` or `BROKEN_FREE` instead of inferring a capture from the Pokémon disappearing.
* `CobblemonClient.clientPokedexData` (`ClientPokedexManager`) holds the player's Pokédex data on the client.

## 7. Post-V3 verification (2026-10-17)

* `gradle -p core test`: **98 tests, 0 failures.**
* **Mod sources** (`src/main/java`, 18 files):
  * `javac` type-check against hand-written stubs. Each stub signature was verified against Yarn 1.21.1 mapping files, the Cobblemon 1.8.2 sources and the Fabric API 1.21.1 sources.
  * Real jars were used for fastutil 8.5.12, slf4j 2.0.9 and LWJGL 3.3.3.
  * Result: **0 errors.** One lint warning (a `@JvmStatic` call through `INSTANCE`) has been fixed.
  * Fabric Loader and Mixin APIs could not be obtained and were stubbed from documentation.
* **Still outstanding:** a real Loom build, mixin application, and in-game testing of every feature. None of these were possible in this environment.
