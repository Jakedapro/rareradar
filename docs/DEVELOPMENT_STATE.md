# RareRadar — development state (fresh-session handoff)

Read this first; do not re-read the whole repo. Keep it current at the end of every milestone.

## Repository facts
- Branch of record for V4 + Lab: `claude/awesome-hopper-dflmk3` (built on `claude/compassionate-carson-l7fgoh` @ e4a37fe). `main` is the old v1.1.0.
- Mod: Fabric client mod, MC 1.21.1, Java 21, Cobblemon 1.8.2 (compile-only jar fetched in CI from Modrinth). Version `4.0.0-alpha.1`.
- Layout: `core/` = Minecraft-independent logic (JUnit, `gradle -p core test`); `src/` = Minecraft-facing code; `tools/stubcheck` = type-check of `src/` against hand-written API stubs (`gradle -p tools/stubcheck compileJava`). The real Loom build only runs in GitHub Actions (this sandbox cannot reach maven.fabricmc.net).
- Packages added by the Lab: `core/.../core/lab` (framework), `src/.../dev/rareradar/lab` (in-game scenarios + runtime), `ui/LabTab.java`.

## Current milestone
**RareRadar Lab, phase 1 — first end-to-end vertical slice.** Environment verification → test execution → structured logging → report/history → clean shutdown, plus dashboard tab.

## What exists, and how far it is verified
Levels: IMPLEMENTED < UNIT TESTED < INTEGRATION TESTED < TYPE-CHECKED (stubs) < BUILT (Actions) < IN-GAME VERIFIED.

| Item | Level |
|---|---|
| EnvironmentVerifier (SP / allowlisted private host; never CobbleGalaxy/public) | Unit tested |
| LabController (limits, pause/resume, emergency stop, env-change abort, exception isolation, release of controls) | Integration tested (fake host) |
| SessionRecorder (bounded files, rotation, redaction), ReportGenerator, HistoryStore/regression compare | Integration tested |
| StuckDetector, PerformanceMonitor, TextWrap, EarningsBreakdown | Unit tested |
| Economy fixtures (balance parse/change, rule matching, dedupe, reconcile, realised vs hypothetical) | Unit/integration tested on synthetic messages only — says nothing about CobbleGalaxy's real formats |
| In-game scenarios NAV_RANDOM / NAV_LONG / NAV_FAR_GOAL, DETECT_SCAN, PERF_STATIONARY, CAPTURE_BASIC | Implemented + type-checked against stubs. **Not run in Minecraft.** |
| McLabHost, LabRuntime wiring (tick, Backspace, disconnect, AutoHunt gate) | Implemented + type-checked. Not run in Minecraft |
| Lab dashboard tab; dashboard layout fixes (tab flow, widget reflow, text wrap, scissor clip, scrollbar) | Implemented + type-checked (TextWrap unit tested). Not seen in-game |
| Economy tab now separates REALISED / OBSERVED asks / HYPOTHETICAL; old "Catching everything … $605M/h" option renamed "HYPOTHETICAL ceiling … NOT INCOME" | Implemented + type-checked |

Everything V4 had before (radar, AutoHunt, GTS capture, planners…) keeps its previous status in `docs/V4_VERIFICATION.md`. Nothing in the Lab has been observed working in a real client yet.

## Auto Hunt changes after the Lab milestone
- Auto Hunt now **explores** (long legs on validated routes, `Explorer.java`) while no qualifying Pokémon is in range; setting `exploreWhileIdle` (default on). Type-checked only, not run in-game.
- New rarity category **VALUABLE**: species whose market demand >= `valuableDemand` (0.6) qualify as targets (needs market data; unknown demand never qualifies). Unit tested. Wild IVs are not visible on the client, so "good IVs" cannot be a target filter.
- Idle/route-failure reasons are shown in the status line and action bar.

## Known limitations / bugs (honest list)
- Lab cannot spawn Pokémon or build terrain; CAPTURE_BASIC only works when a qualifying target is already in range. "CAPTURE_SUCCESS" is AutoHunt's own ball-state classification, cross-checked only against the ball count.
- DETECT_SCAN species/shiny checks read the same Cobblemon fields as the scanner (catches cache/refresh faults, not wrong data).
- NAV_FAR_GOAL tests the planner's refusal of far goals; guaranteed-unreachable, moving-target, water-crossing-by-design courses are not implemented (no way to build them from the client).
- Performance "heap growth" is indicative only (JVM garbage). Loaded-chunk count is not collected (-1).
- Git SHA in diagnostics comes from `GITHUB_SHA`/`git rev-parse` at build time; local non-git builds show `unknown`.
- No SIMULATED-layer scenarios yet (Pathfinder is bound to ClientWorld; extracting a block-access interface would allow it).
- The economy "$605M/hour" came from asking prices × all encounters × 100% capture; it is now labelled hypothetical but the model itself is unchanged.

## Outstanding tasks (priority order)
1. User runs `smoke`, then `navigation_basic`, `detection_basic`, `performance_basic` in singleplayer and returns the session folders → fix what they show.
2. Extract Pathfinder's world access behind an interface; add SIMULATED navigation scenarios (water edge, dead end, narrow corridor, unreachable).
3. Moving-target navigation, density/time/weather sweeps for detection and performance.
4. STS payout / GTS fixtures from *real* exported chat lines (needs user-provided samples); mark server integration as manually verified only.
5. Optionally run a headless client in CI (not feasible today without a launcher + Cobblemon + display).

## Testing instructions
- Unit/integration: `gradle -p core test` (opt-in benchmarks: `-Pbench`). Type-check: `gradle -p tools/stubcheck compileJava`.
- In game: `docs/LAB.md`. Start with suite `smoke`, then the others.

## Build / commit status
- Code commit: `852377f` on `claude/awesome-hopper-dflmk3`.
- GitHub Actions run 39124029467: **success** (core tests, stub type-check, real Loom build, jar content check incl. Lab classes).
  https://github.com/Jakedapro/rareradar/actions/runs/39124029467 — artifact `rareradar-jar`; also published to the `dev-latest` pre-release.
- BUILT successfully is **not** in-game verified. No Lab scenario has been run in Minecraft yet.
