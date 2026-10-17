# RareRadar V4 — verification report

Verification levels used below:

| Level | Meaning |
|---|---|
| **Unit** | Logic passes automated tests in `core/` |
| **Type** | Minecraft-facing code type-checks against verified API stubs (`tools/stubcheck`) |
| **Build** | A real Loom build against Minecraft 1.21.1 and Cobblemon 1.8.2 succeeds in GitHub Actions, and the jar contains classes, the knowledge datasets, `fabric.mod.json` and the mixin config |
| **Client** | Observed working inside Minecraft |
| **Live** | Observed working on CobbleGalaxy |

| Feature | Highest level reached | Evidence |
|---|---|---|
| Radar, encounter tracking, HUD, sessions, exposure | **Client** | User report: alpha.3 loads; 197 encounters recorded |
| GTS screen and chat capture | **Live** | User export: 784 observations (728 screen, 56 chat), all LISTING |
| Listing dedupe, ability/nature normalisation | Unit, run against the real export | 784 → 767 listings on the exported data |
| Knowledge base (1,025 species, 5,044 spawn entries) | Unit + Build | Tests assert facts read from the 1.8.2 data files; CI checks the datasets are in the jar |
| Server profiles (CobbleGalaxy recognition, unknown-server confirmation) | Unit + Type + Build | `AutomationTest` |
| Automation controller (lease, pause, emergency stop, error-stop, run-time limit) | Unit + Type + Build | `AutomationTest` |
| Task persistence (no resume after restart; irreversible tasks never replayed) | Unit | `tasksAreNeverResumedAfterRestart` |
| AutoHunt/AutoTrain through the controller | Type + Build | **Not yet observed in-game** |
| Planner, strategy calibration, hunting value estimates, EV planner | Unit + Build | Tests; EV planner tested on the real dataset |
| Assisted trading alerts (Mode B) | Unit + Type + Build | `TradeAlertEvidenceTest`: asking prices alone or 1–2 sales never trigger an alert; the margin is typical sale × (1 − fee) − price; an unknown fee is not treated as 0. Not yet observed in-game |
| Capture probability (Cobblemon 1.8.2 port) | Unit + Build | `CaptureMathTest` (hand calculation), `BallCatalogTest`, `CaptureAdvisorTest`; reviewed line by line against `CobblemonCaptureCalculator.kt`. Not compared against throws on a live server |
| Capture chances on Knowledge and Radar pages | Type + Build | Not yet observed in-game. Wild-Pokémon level visibility on the client is unverified |
| Spawn probability model and biome ranking | Unit + Type + Build | `SpawnProbabilityTest` (hand-calculated bucket/weight cases, real-data sums). It is a model and has not been compared with real spawn counts |
| Shiny-hunt planner, STS vs GTS, lifetime stats and Overview summary | Unit + Type + Build | `ShinyHuntPlannerTest`, `SellChannelAdvisorTest`, `LifetimeTest`. Not yet observed in-game |
| Automated purchasing (Mode C) | **Not implemented**, by design | No verified purchase interface; purchases are irreversible |

## How to verify the new V4 parts in-game

1. Join CobbleGalaxy. `latest.log` should show `[Automation] connected to <address> → profile 'cobblegalaxy'`. Dashboard → Automation should show the profile, its recorded authorisation and its modules.
2. Single-player first:
   1. Press **K**. The HUD should show `RARERADAR V4 · Single-player · Automation: ACTIVE (hunting)`.
   2. Dashboard → Automation should show a running task and audit entries.
   3. Press **Backspace**. Everything should stop, and the audit log should record `emergency_stop`.
3. Quit while AutoHunt is running and restart. The log should report the interrupted task, and nothing should start on its own.
4. Dashboard → Knowledge:
   1. Look up a species.
   2. Check the capture-chance table and the "Best biomes" list. The list may show "computing…" for a moment.
   3. Check "Most likely … spawns now" and the "can spawn here" list.
   4. Set an EV goal.
5. Dashboard → Radar with a Pokémon in range: a `capture/throw` line should appear for the balls in your inventory. If it never appears, the client is not receiving wild-Pokémon levels.
6. Dashboard → Hunts: select a hunt with a species to see the shiny-hunt plan. Spend 15+ minutes in a biome to give it a measured encounter rate.
7. Dashboard → Market: the "STS vs GTS" section stays UNKNOWN until you log STS payouts and 3+ confirmed sales, and set your GTS fee.
8. Dashboard → Overview: the "At a glance" block should match your History page totals.
