# RareRadar V4 — Architecture report (Phase 1 audit)

Audit date: 2026-10-17. Baseline: `3.0.0-alpha.3` (commit `3e4c0b4`-era).
* **Live evidence from the user** (alpha.2/alpha.3): 784 raw GTS observations, of which 728 came from the screen and 56 from chat; 767 remain after deduplication; 0 confirmed sales.
* **Development evidence:** 113 core tests passing; CI builds the jar with Loom.

## 1. Module map (V3, preserved)

| Layer | Package / class | Role |
|---|---|---|
| **Core** (pure Java, 4.5 k LOC, unit-tested) | `core.util` | Statistics (median, MAD, Theil–Sen, Wilson/Poisson intervals), `Estimate`/`Confidence`, `Money`, `Log` |
| | `core.store` | `JsonlStore` (append-only, atomic rewrite, corrupt-line recovery), `AsyncWriter` (bounded single IO thread) |
| | `core.rarity` | `RarityEngine` (weighted score), `Watchlist`, `TargetRanker` |
| | `core.market` | `MarketDatabase`, `MarketStats`, `Valuator`, `OpportunityFinder`, `MarketCsv`, `GtsTextParser`, `Listings` (dedupe), `CaptureStats` |
| | `core.profit` | `ActivityLog`, `ProfitEngine` (ratio estimator, explained recommendation) |
| | `core.hunt` | `HuntTracker` (overlay sync), `HuntOptimizer`, `HuntPointGoal`, `OverlayHuntParser` |
| | `core.spawn` | `EncounterLog`, `ExposureLedger`, `EncounterStats`, `BiomeRecommender`, `SpawnRuleDatabase`, `TeleportDetector` |
| | `core.breeding`, `core.pokebuilder`, `core.mega`, `core.dex`, `core.daily`, `core.inventory` | Planners (exact IV inheritance, token knapsack, boss drop rates, Pokédex tiers, checklist, ball policy) |
| | `core.stats` | `SessionTracker` (checkpoint/recovery), `Reports`, `DataExport` (CSV/JSON/zip) |
| | `core.ml` | `PriceModel` (CV-gated ridge) |
| | `core.autohunt` | `AutoHuntMachine` (state machine), `CaptureClassifier`, `EnvironmentGate`, `SafetyLimits` |
| | `core.report` | `DashboardText` (tested dashboard text) |
| **Minecraft glue** (3.2 k LOC) | `RareRadarClient` | Entrypoint, key bindings, lifecycle events, tick dispatch, inventory counting |
| | `Galaxy` | Service container and persistence root (`config/rareradar/`) |
| | `RadarScanner` | Rate-limited entity scan, classification cache, encounter/exposure recording, teleport detection |
| | `CobblemonCompat` | All Cobblemon API calls (fail-soft) |
| | `CobbleGalaxyBridge` | Read-only reflection: pinned hunts, GTS screen, PokéBuilder, Dex Rewards, Vote |
| | `GtsCapture` | Chat announcements and chest-menu GTS parsing, with diagnostics |
| | `AutoHunt`, `Navigator`, `Pathfinder` | Automation execution (hunt/train) |
| | `RadarHud`, `ui.DashboardScreen`, `ui.Tabs` | HUD and the 11-page dashboard |
| | `mixin.*` | Outline colour and glow |
| **Tooling** | `tools/stubcheck` | javac type-check against verified API stubs |
| | `.github/workflows/build.yml` | Core tests, Loom build, empty-jar guard, `dev-latest` pre-release |

## 2. Persistence

All data lives under `config/rareradar/`.
* **Append-only JSONL:** market, activities, hunts, encounters, sessions, bosses.
* **Plain JSON:** exposure ledger, `v3.json` config, `watchlist.json`.
* **Behaviour:**
  * Writes go through one background thread.
  * Rewrites are atomic and keep a `.bak`.
  * Corrupt lines are skipped and the original is backed up.
  * Sessions are checkpointed every 2 minutes and recovered after a crash.
* **Gap:** there is no schema version per data file yet. V4 adds a `schema` field to new files and migrates by tolerant reading; old files keep loading unchanged.

## 3. Client hooks

`END_CLIENT_TICK`, `HudRenderCallback`, `ClientPlayConnectionEvents.JOIN/DISCONNECT`, `ClientLifecycleEvents.CLIENT_STOPPING`, `ClientReceiveMessageEvents.GAME`, and two mixins (`MinecraftClient.hasOutline`, `Entity.getTeamColorValue`).

## 4. Technical debt relevant to V4

| # | Issue | V4 action |
|---|---|---|
| D1 | Automation permission is a hard-coded host block plus an allowlist in `V3Config`, with no notion of a server profile | Replace with persistent **server profiles** (§6) |
| D2 | `AutoHunt` owns movement keys directly; AutoTrain and AutoHunt each manage their own state | One **AutomationController** owns movement/interaction leases, limits, pause/stop and the audit log |
| D3 | No persistent task state; a crash loses what was running (no irreversible actions exist yet, so nothing unsafe) | Persistent task records with never-replay semantics for irreversible actions |
| D4 | Game knowledge is limited to runtime labels and optional user-supplied spawn files | Versioned knowledge database built from Cobblemon 1.8.2 data files, with provenance |
| D5 | Capture success is inferred only from the ball state, with no prior probability model | Port of the 1.8.2 capture calculator, returning *unknown* when inputs are not observable |
| D6 | Profit options come only from the activity log plus the top hunt | Planner combines measured activities, knowledge-based hunting estimates and module availability |

## 5. Research sources actually used (V4)

| Source | Status from the development sandbox |
|---|---|
| Cobblemon repository, tag `1.8.2` (GitLab) | **Reachable.** Species, spawn pools, presets, biome tags, capture calculator and stat code read directly. This is the authoritative 1.8.2 data. |
| wiki.cobblemon.com, minecraft.wiki, docs.fabricmc.net, cobblegalaxy-wiki.gitbook.io | **Not reachable** (egress policy). Not reviewed; nothing in V4 is attributed to them. |
| CobbleGalaxy modpack 2.5.0 (user upload) | Client mod class signatures, plus the server list entry **`forge.cobblegalaxy.com`** |
| Live CobbleGalaxy observations (user exports) | 784 GTS observations used as regression fixtures |

## 6. Automation authorisation model (V4)

* **Per-server profiles** decide what may run. Profiles are matched by the actual connection address, which is logged.
* **CobbleGalaxy:**
  * The profile matches hosts ending in `cobblegalaxy.com`. The modpack's server list uses `forge.cobblegalaxy.com`.
  * It is marked automation-authorised **on the user's statement (2026-10-17) that staff granted permission**, with that source recorded in the profile.
  * The publicly indexed wiki rules prohibit macros and automation. The profile carries this note.
  * Automation still requires the user to enable it each session.
* **Other multiplayer servers** stay unauthorised until the user explicitly confirms permission on the dashboard.
* **Single-player** is authorised locally.

## 7. Scope decisions (with reasons)

* **No anti-cheat evasion or "humanisation".** Automation is visible: a HUD banner and an audit log.
* **GTS purchasing (Mode C) is not implemented.**
  * There is no verified, documented purchase interface. The only path would be sending CobbleGalaxy's private menu packets or clicking server GUIs blind.
  * Purchases are irreversible spending.
  * Modes A (observe) and B (assisted recommendations) are implemented, and the controller exposes Mode C as *unavailable* with this reason.
* **No reinforcement learning.** Predictions are recalibrated with transparent statistics (prediction error per strategy).

## Money tracking (`core/economy`, `MoneyCapture`, `SidebarReader`)

RareRadar never guesses what a server money message means.

**Messages.** Every system message carrying exactly one amount, and not already parsed as a GTS listing broadcast, is offered as a *candidate* on the Money page.
- The player labels a candidate once: STS payout, my GTS sale, my GTS purchase, other income or other spending.
- `TransactionRule.learn` turns that real message into an exact-format, fully anchored regex:
  - the amount becomes a capture group;
  - a recognised species name becomes the subject;
  - other numbers are generalised;
  - everything else stays literal.
- Matching messages become `Transaction`s in `data/transactions.jsonl`. They update session income or spending.
- Labelled STS payouts and own sales naming a species also become `STS` / `SALE` market evidence (`Source.OWN_TRADE`).

**Balance.** `SidebarReader` reads the scoreboard sidebar exactly as vanilla draws it. `BalanceTracker` takes the single line matching the balance label:
- The first reading per connection is a baseline, never income.
- Two candidate lines count as ambiguous and are ignored.
- A balance change within 5 s of a labelled transaction with the same signed amount marks that transaction ✔ confirmed.

Nothing is clicked or sent. Unlabelled messages are never counted as money.
