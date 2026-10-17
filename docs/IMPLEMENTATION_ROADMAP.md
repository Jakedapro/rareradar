# RareRadar V3 — Implementation Roadmap

## Guiding constraints

* **No invented data.** Prices, rewards, rates and costs come only from user entry, user imports, or the user's logged observations. Documented CobbleGalaxy values (`docs/COBBLEGALAXY_RESEARCH.md`) appear only as editable defaults that cite their source.
* **No automation on public servers.** AutoHunt and AutoTrain are allowed only in single-player and on explicitly allowlisted private servers, and never on CobbleGalaxy, whose rules ban macros and automation.
* **Separate the pure logic from the Minecraft glue.** All algorithms live in `core/`, which has no Minecraft dependencies, compiles on its own, and is unit-tested. The Fabric layer (`src/main/java`) only reads game state, renders, and forwards events to `core`.
* **No heavy work on the client thread.** Disk writes go through a bounded single-thread writer. Classifications are cached. Scans are rate-limited.

## Persistence decision

The brief allows "SQLite or another lightweight embedded database". V3 uses **append-only JSON Lines files with in-memory indexes**, one file per dataset under `config/rareradar/data/`, for these reasons:

* SQLite-JDBC is a ~13 MB jar with per-OS native libraries. Shipping it inside a Fabric mod needs jar-in-jar plus native extraction, which is fragile across launchers. H2 adds ~2.5 MB.
* The data volume is tiny: thousands of manual observations, not millions.
* JSONL is human-readable and trivially backed up.
* Corruption tolerance is per line: a bad line is skipped and logged, and the original file is backed up before any rewrite.

The storage interface (`JsonlStore`) is small, so it can be swapped for SQLite later if the data volume ever requires it.

## Milestones (each one compiles, is tested, and is committed)

| # | Milestone | Content | Status |
|---|---|---|---|
| M0 | Audit, research, roadmap | `docs/ARCHITECTURE_AUDIT.md`, `docs/COBBLEGALAXY_RESEARCH.md`, this file | Done |
| M1 | Core module + infrastructure | `core/` Gradle build, statistics utilities, `Estimate`/`Confidence`, `JsonlStore`, `AsyncWriter` | |
| M2 | Rarity engine + watchlist | Observation model with explicit unknowns, weighted score `R = Σwᵢxᵢ`, target priorities, watchlist import/export | |
| M3 | Market intelligence | Price observations (listing vs confirmed sale), robust statistics (median, MAD, Theil–Sen trend, liquidity), heuristic valuation with intervals, opportunity flags | |
| M4 | Profit engine | Activity log (realised vs estimated), profit/hour with confidence intervals, ranked recommendations with stated assumptions | |
| M5 | Hunt assistant | Manual hunt objectives, Gamma–Poisson completion-time estimate, efficiency ranking, Hunt Point goal tracker | |
| M6 | Spawn intelligence | Encounter log, exposure-normalised per-biome and per-time rates with Poisson intervals, biome recommendations, teleport detection | |
| M7 | Breeding analyser | Exact IV-inheritance distribution (Destiny Knot / Power items / nature), offspring value, profit | |
| M8 | PokéBuilder planner | Token allocation as a 0/1 knapsack over user-defined upgrade options | |
| M9 | Mega, Pokédex, daily, inventory | Boss drop-rate estimate (Jeffreys interval), Pokédex planner, daily checklist, Vote Party log, resource valuation and warnings | |
| M10 | Session statistics + export | Persistent sessions, daily/weekly reports, CSV/JSON export, backup/restore | |
| M11 | ML (only where measurable) | Ridge regression on log price with k-fold CV; used only when it beats the heuristic on held-out error | |
| M12 | AutoHunt V2 core | Pure state machine, capture-outcome classifier, ball policy, safety limits, environment gate | |
| M13 | Minecraft integration | Reliability fixes (classification cache, world-change shutdown, protected balls), capture verification via `CaptureState`, data wiring, dashboard screens | |
| M14 | Docs | README, CHANGELOG, known limitations | |

## Known environment blocker

The Minecraft mod cannot be compiled in the development container because `maven.fabricmc.net` is blocked and there is no Cobblemon jar. The `core` module is fully built and tested. Minecraft-layer code is checked by hand against Yarn 1.21.1 mapping files and the Cobblemon 1.8.2 sources. It **must be compiled and tested in-game** before release.
