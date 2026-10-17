# Changelog

## 3.0.0-alpha.3 — data quality from the first live capture

The first live capture on CobbleGalaxy (alpha.2) recorded 784 asking prices in about 4 minutes: 728 from the GTS screen and 56 from chat announcements. All were correctly classified as listings, with 0 sales.

### Fixed
- **Double counting:**
  - 15 chat announcements were the same listing as a GTS-screen card, and 2 were re-announcements.
  - Statistics and valuation now count a chat listing only if no listing with the same Pokémon, form, shiny state and price exists within 24 h. The GTS-screen card, which carries IVs and nature, is kept in preference.
  - Distinct screen cards are never merged.
  - This applies to already-stored data without rewriting files: on the exported real data, 784 → 767 listings.
- **Ids normalised:** abilities and natures stored as Cobblemon ids (`cobblemon.ability.hugepower`) are now shown as `hugepower`.

## 3.0.0-alpha.2 — GTS price capture repair

### Why no prices were saved in alpha.1
- Prices were only read from CobbleGalaxy's own custom GTS screen, `com.cobblegalaxy.client.gts.GTSScreen`. That screen exists only when the **CobbleGalaxy client mod** is installed.
- In every other case, `/gts` opens a regular chest-style menu, and alpha.1 ignored it completely.
- Alpha.1 also had **no chat listener**, so GTS announcements were never read.
- Result: no observation was ever created, so `market.jsonl` was never written (it is created on the first save).

### Added
- **Chest-style /gts menu capture:**
  - Reads the item names and lore that the menu displays.
  - Requires an explicitly labelled price line ("Price"/"Cost"/"Buy").
  - Takes the species from the Cobblemon Pokémon item, or by species-name lookup.
  - Pages titled history/sold/log are skipped.
  - Nothing is clicked.
- **GTS chat announcement capture:**
  - Only server/system messages that contain "GTS" and exactly one price are read.
  - Player chat is never used, so typed fake prices are ignored.
- **Classification:** everything is stored as an asking price (LISTING) unless the text explicitly says sold/bought/purchased *and* contains no listing words. A listing is never stored as a completed sale.
- **Diagnostics:**
  - At startup: which capture paths are active and whether the CobbleGalaxy client mod is installed.
  - When any GTS-looking screen opens: its class and title, and which parser applies.
  - Per-path counters (detected / parsed / rejected by reason / duplicates / saved): logged, and shown on Dashboard → Market.
  - Unparsed lines are written to `config/rareradar/data/gts_samples.txt` so the parser can be tuned to real CobbleGalaxy wording.
- Config toggles: `captureGtsChat`, `captureGtsChestMenu` (both on by default).

### Unchanged
- Existing config, watchlist, encounter, session and market files are untouched; new settings use defaults.

## 3.0.0-alpha.1 — "Galaxy Profit Engine" (unreleased, development build)

### Added
- **CI** (GitHub Actions): core tests plus a real Loom build against Cobblemon 1.8.2.
- **`core/` module**: Minecraft-independent logic with 102 unit tests (`gradle -p core test`).
- **Rarity engine**: configurable `R = w1·S + w2·L + w3·M + w4·P + w5·D` (a priority score, not a spawn probability). Adds a watchlist with JSON import/export, plus target ranking by watchlist priority and distance-discounted score.
- **Market database** (append-only JSONL with corruption recovery):
  - Listings, confirmed sales, STS payouts and purchases are kept separate.
  - Statistics: median, min, max, MAD volatility, Theil–Sen trend, and liquidity with Poisson intervals.
  - CSV import/export.
- **Valuation**:
  - Prefers confirmed sales.
  - Falls back to the lower quartile of asking prices at LOW confidence.
  - Uses a component model (`V_species + V_shiny + V_competitive + V_rarity`) when the exact bucket has no data.
  - Shows uncertainty intervals throughout.
- **Opportunity flags**: potentially undervalued listings, strong demand, shiny premiums, breeding candidates, keep/sell advice. These are always framed as possibilities, never guarantees.
- **Profit engine**: realised vs estimated profit per hour (ratio estimator, 90% intervals) and explained recommendations. It flags overlapping intervals as uncertain.
- **Hunt assistant**:
  - Manual hunts, plus **automatic import of CobbleGalaxy "Pinned Hunts"**.
  - Gamma–Poisson completion-time model and reward-per-hour ranking.
  - Hunt Point goal tracker (Mega Bracelet, 150 HP as reported by the wiki, editable).
- **Spawn intelligence**:
  - First-sighting encounter log and per-biome/time exposure ledger.
  - Exposure-normalised rates with Poisson intervals and biome recommendations.
  - Cobblemon `spawn_pool_world` JSON import.
  - Teleport/dimension-change detection that resets local statistics.
- **Breeding analyser**:
  - Exact offspring-IV distribution (Destiny Knot, Power items), checked against Monte Carlo.
  - Nature odds (Everstone), compatibility rules, and expected profit per egg and per hour.
- **PokéBuilder planner**: multiple-choice knapsack over token costs. Real costs are captured read-only from CobbleGalaxy's PokéBuilder screen.
- **Mega planner**: boss drop rate from the user's own log (interval estimate); no drop rate is assumed.
- **Pokédex planner**: real milestone tiers and the missing-species list from CobbleGalaxy's Dex Rewards screen (or the Cobblemon client Pokédex), plus catch-next suggestions.
- **Daily checklist** (manual, resets daily), **Vote Party** window tracking, **resource book** with replacement values and reserve warnings.
- **Session statistics**: daily/weekly reports, trends, CSV/JSON export, zip backup/restore (zip-slip safe, CSV formula-injection safe).
- **Optional ridge-regression price model**:
  - Enabled only if 5-fold CV error ≤ 90% of the heuristic's.
  - Never extrapolates to unseen species, forms, or shinies it has no examples of.
- **CobbleGalaxy read-only bridge**, all by reflection: pinned hunts, viewed GTS pages, PokéBuilder costs, Dex Rewards, Vote Party progress. It never sends packets or automates menus.
- **Dashboard** (`` ` `` key): 11 pages (Overview, Radar, Economy, Market, Hunts, Breed/Build, Pokédex, Spawns, Inventory, History, Config) with keyboard navigation.
- **HUD**: position, scale and background opacity; value tags with uncertainty markers; hunt-target markers; a red **AUTO … ACTIVE** banner whenever automation runs.
- **Emergency stop key** (Backspace by default).

### Changed
- **AutoHunt V2**:
  - Runs on an explicit, unit-tested state machine (`IDLE → SCAN → SELECT_TARGET → PLAN_ROUTE → APPROACH → AIM → THROW → AWAIT_RESULT → VERIFY_CAPTURE → SUCCESS/RETRY/ABORT`).
  - Captures are verified from the thrown ball's synced Cobblemon `CaptureState`; a disappearing Pokémon is never counted as a capture.
- **Automation is refused** outside single-player and explicitly allowlisted private servers, and **always refused on CobbleGalaxy**, whose rules ban macros and automation. AutoTrain is gated the same way and time-limited.
- **Ball policy**: Master Balls and other configured balls are protected; cheapest ball for low-value targets; reserves respected; per-target and per-run ball budgets.
- **Radar**:
  - Scans every 5 ticks instead of every tick.
  - Classifications are cached; outline mixins use an O(1) lookup.
  - Tier toggles now drive the V3 rarity engine (migrated automatically from `rareradar.json`).
- **Backtick key** now opens the dashboard; the classic settings screen is reachable from Config or via an optional keybind.

### Fixed
- V1 threw Master Balls first.
- V1 automation had no multiplayer gate, no run-time limit and no ball budget.
- Static target references survived world changes and disconnects.
- Stuck and approach timers reset on every re-path, so a stuck bot could loop forever (found by a new unit test).
- Pinned hunts whose titles contain progress counters no longer create duplicates, and unpinned overlay hunts go stale after 36 h.
- From an independent code review:
  - AutoHunt never reported a found path (it could only catch targets already in range).
  - The first plan after selecting a target used a stale target.
  - Captures were counted every tick while a menu was open.
  - Per-target ball budgets reset when the same target was re-selected.
  - The cheapest ball could be thrown at a rare target whose radar entry had dropped.
  - AutoHunt did not pause during the player's own battle.
  - AutoTrain could battle rares.
  - Completed hunts were resurrected by the overlay sync.
  - A crash lost observation time, overstating encounter rates; sessions are now checkpointed and recovered.
- Diagonal pathfinding moves clipped head-height corners.
- Hand-edited config values were not validated.

### Known limitations
- Builds with Loom in CI against Cobblemon 1.8.2, but has **not yet been run in Minecraft**.
