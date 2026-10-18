# RareRadar Lab

An autonomous test harness that runs **inside your own Minecraft client** and writes a structured diagnostic package you can hand back for analysis. It exists to replace "play it and tell me what broke" with measured, repeatable runs.

## Safety model

- Runs only in a **local single-player world**, or a **private/loopback test server that you put on the allowlist** (`config/rareradar/lab/lab_config.json` → `allowedServers`, exact host, private address only).
- **CobbleGalaxy and every public host are always refused**, even if listed.
- Never starts by itself: you press **Start** on the dashboard **Lab** tab.
- Stops (and writes its report) when you leave the world, the connection changes, you press **Stop**, or you hit **Backspace** / the red **EMERGENCY STOP** button.
- Hard limits: session duration, max tests, per-test timeout, per-test action limit, consecutive-failure limit, bounded stuck recovery (3 attempts), damage abort, bounded log sizes (2 MB per file, one rotated backup).
- Movement keys are released after every test and on every stop path.
- Lab data lives in `config/rareradar/lab/` and never modifies production settings or data.

## Running a session

1. Start Minecraft with the RareRadar jar and Cobblemon, open a **single-player** world.
2. Press the dashboard key (default `` ` ``), open the **Lab** tab.
3. Check the **ENVIRONMENT** block says *Lab allowed*.
4. Pick a suite (button cycles), duration, max tests, optional seed. Press **Start**.
5. Watch **STATUS**. Stop whenever you like. The report is written when the session ends.
6. Use **Open report** / **Open session folder**, or collect `config/rareradar/lab/sessions/session_YYYYMMDD_HHMMSS/` and send it back.

## Suites

| Suite | Contents | Needs |
|---|---|---|
| `smoke` | Lab self-check + 3 economy fixture scenarios | nothing in-world; quickest end-to-end check |
| `economy_fixtures` | economy fixtures only | – |
| `navigation_basic` | short walks, a long walk, far-goal planning limit | open, walkable terrain |
| `detection_basic` | radar tracking vs real Pokémon entities | wild Pokémon within scan range, radar on (J) |
| `performance_basic` | 15 s stationary FPS/scanner/heap sampling | – |
| `capture_basic` | one AutoHunt run on an in-range qualifying Pokémon | single-player, Poké Balls, a qualifying target |
| `full` | a mix of all of the above | as above |

A scenario whose precondition is missing is recorded as **SKIPPED with the reason**, never as a pass.

## Session package

`summary.json` · `events.jsonl` · `navigation.jsonl` · `detection.jsonl` · `captures.jsonl` · `performance.csv` · `failures.json` · `environment.json` · `report.md`

`report.md` contains the executive summary, which verification layers actually ran, failures grouped by class, evidence-linked recommendations (only for failures that occurred), per-scenario results, performance, a comparison with the previous session of the same suite, and what was **not** run. Past summaries are kept in `config/rareradar/lab/history/`.

## Verification layers

| Layer | Meaning |
|---|---|
| FIXTURE | synthetic data (economy messages); checks logic only |
| SIMULATED | modelled world, not Minecraft (none shipped yet) |
| IN_GAME | real client state in your world; valid only for the conditions during the run |

## Controlled worlds (manual setup)

The Lab cannot build terrain or spawn Pokémon. For repeatable benchmarks create a dedicated world and keep the seed fixed:

- `LAB_FLAT_NAVIGATION`: new **Superflat** world, no structures; run `navigation_basic`.
- `LAB_FOREST_NAVIGATION`: fixed-seed Default world standing in a forest biome (note the seed in your notes).
- `LAB_POKEMON_DETECTION` / `LAB_CAPTURE_BASIC`: a creative-mode test world with Poké Balls in the inventory and a Cobblemon spawn near spawn; use `/pokespawn` (operator) to place Pokémon.
- Never run the Lab in a world you care about without a backup: the player *walks*, it does not break or place blocks, but a bug could still walk it into danger.

Use the same **seed** and suite across builds; comparisons only mean something when the scenarios match.

## Not implemented yet (be aware)

Moving-target navigation, obstacle courses with guaranteed-unreachable goals, weather/time-of-day sweeps, entity-density stress, Pokémon-density-controlled detection, STS payout detection against live messages (needs manual verification on the real server), automatic world creation.
