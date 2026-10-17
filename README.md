# RareRadar V4 — Project Autonomy (4.0.0-alpha.1)

Client-side Fabric mod for **Minecraft 1.21.1 + Cobblemon 1.8.x**. It finds rare Pokémon, tracks encounters and GTS prices, and acts as a data-driven hunting and trading assistant for the CobbleGalaxy server.

> **Development status: please read.**
> * **Automated tests (passing in CI):** the core unit tests (187) and a real Loom build against Minecraft 1.21.1 and Cobblemon 1.8.2. CI also checks that the jar contains the knowledge datasets and the mechanics classes.
> * **Verified in-game by the player:** encounter tracking and GTS capture (screen and chat). Captured rows are all listings so far, since no sales were observed yet.
> * **Implemented but not yet verified in-game:** the V4 automation controller, server profiles, Knowledge, Automation and Market pages, capture chance display, assisted trade alerts and the V4 HUD header.

## Server rules first

* **Automation (AutoHunt/AutoTrain)** runs only through the central controller, and only on an authorised server profile.
  * **CobbleGalaxy** is included as authorised because you stated that staff granted permission. It still needs enabling every session.
  * Other multiplayer servers need an explicit, recorded confirmation.
  * Backspace is the emergency stop.
* **Trading is never automated.** Assisted mode only posts chat alerts, and only when backed by confirmed sales; you decide and click yourself.
* **Everything else** (radar, HUD, dashboard, calculators, and the read-only CobbleGalaxy bridge) only *displays* information your client already received or you typed in.

## Installation

1. Install Fabric Loader ≥ 0.17.2 for Minecraft 1.21.1, Fabric API, and Cobblemon 1.8.x. The CobbleGalaxy 2.5.0 modpack ships Loader 0.19.3.
2. Put `rareradar-<version>.jar` in `mods/`.

### Building

Requirements: Java 21, Gradle 8.x, and network access to `maven.fabricmc.net`.

```
# 1. Cobblemon is compile-only and not committed:
cp /path/to/Cobblemon-fabric-1.8.2+1.21.1.jar libs/cobblemon.jar
# 2. Build the mod (core/ sources are compiled in automatically):
gradle build
# 3. Core logic tests (no Minecraft needed):
gradle -p core test
gradle -p core test -Pbench --tests '*CoreBenchmark*'   # micro-benchmarks
# Without access to the Fabric maven only: type-check the mod sources against verified API stubs
gradle -p tools/stubcheck compileJava
```

## Keys (all rebindable under Controls → Rare Radar)

| Key | Action |
|---|---|
| `` ` `` | Open the **dashboard** |
| `J` | Toggle radar |
| `K` | Toggle AutoHunt (private worlds only) |
| `L` | Toggle AutoTrain (private worlds only) |
| `Backspace` | **Emergency stop** for all automation |
| (unbound) | Classic radar settings screen |

Dashboard navigation: `←`/`→` (or `Tab`) switch pages, `↑`/`↓`/`PgUp`/`PgDn`/mouse wheel scroll, `Esc` closes.

## Features

* **Radar**:
  * Highlights and lists shiny, legendary, mythical, Ultra Beast and Paradox Pokémon, plus watchlist and hunt targets.
  * Shows distance, direction and coordinates, with an estimated value and uncertainty marker (`~` medium, `?` low).
  * The priority score `R = w1·S + w2·L + w3·M + w4·P + w5·D` is configurable. It is a sorting heuristic, **not a spawn probability**.
* **Market database**:
  * Ways to add data:
    * manual GTS entry (dashboard → Market);
    * CSV import from `config/rareradar/import/market.csv`;
    * with the CobbleGalaxy bridge, automatic recording of the GTS pages you *view*.
  * Asking prices and confirmed sales are kept separate: a $1,000,000 listing proves nothing about value.
  * Statistics: median, min, max, volatility, trend, liquidity.
* **Valuation**:
  * Confirmed-sale statistics first.
  * Otherwise a heuristic `V = V_species + V_shiny + V_competitive + V_rarity`.
  * Otherwise an optional ridge-regression model, used **only** when cross-validation shows it beats the heuristic.
  * Every estimate shows a range and a confidence level. Unknown is shown as unknown, never as $0.
* **Profit engine**:
  * Log sessions per activity: received money (realised), estimated value of unsold items, and costs.
  * It compares profit/hour with 90% ranges and explains its recommendation and assumptions.
* **Hunts**:
  * Pinned CobbleGalaxy hunts are imported automatically; manual hunts are supported too.
  * Ranking by reward ÷ expected time, using your own encounter rates.
  * Hunt Point goal tracker (Mega Bracelet, 150 HP per the wiki; editable).
* **Spawn intelligence**:
  * Exposure-normalised encounters per hour by biome and time of day, with intervals, plus biome recommendations.
  * Optional Cobblemon spawn-rule lookup: copy `spawn_pool_world/*.json` into `config/rareradar/spawn_pool_world/`.
  * After any long-distance teleport you start yourself (e.g. `/wild`), RareRadar resets local statistics and summarises the new area.
* **Breeding**: exact offspring IV distributions (Destiny Knot / Power items), nature odds, egg-group compatibility, and expected profit per egg and per hour.
* **PokéBuilder**: real token costs are captured when you open `/pokebuilder`. It then estimates whether maxing IVs is worth it at your token value.
* **Mega planner**: boss Mega Stone drop rate estimated only from your own logged wins.
* **Pokédex**:
  * Real milestone tiers, claimable rewards and missing species from CobbleGalaxy's Dex Rewards menu.
  * Catch-next suggestions.
* **Daily checklist** (`/dailyrewards`, `/calendar`, `/vote`, …): reminders only, and Vote Party progress.
* **Inventory**: ball, candy and item counts; replacement value from your prices; low-reserve warnings; protected items.
* **History**: daily and weekly reports, trends, CSV/JSON export, one-click zip backup.

### GTS price capture (read-only)

RareRadar records asking prices from three sources. It never clicks, buys or lists anything.

1. **CobbleGalaxy's custom GTS screen**: only when the CobbleGalaxy client mod is installed.
2. **Chest-style `/gts` menu**: item names plus a lore line labelled Price/Cost/Buy.
3. **Server GTS announcements in chat**: system messages only.

Check whether it works in the log (`[GTS capture]` lines) or on Dashboard → Market. Lines it could not parse are saved to `config/rareradar/data/gts_samples.txt`.

### AutoHunt V2 (single-player / your own server only)

* **State machine**: scan → select → plan → approach → aim → throw → await → verify.
* **Capture verification**: a capture counts only when the thrown ball's synced Cobblemon state reaches `CAPTURED`.
* **Ball policy**: Master Balls and other configured balls are never thrown without explicit permission; the cheapest ball is used for low-value targets; reserves are respected.
* **Limits**: maximum run time, balls per run, balls per target, stuck and approach timeouts, minimum health, and repeated-plan-failure abort.
* **Stops automatically** on world change or disconnect, on an internal error, or when you press the emergency stop key.
* **Visibility**: a red banner is shown while it runs.

## Configuration and data

| Path | Contents |
|---|---|
| `config/rareradar.json` | Classic radar settings (range, HUD rows, throw range, …) |
| `config/rareradar/v3.json` | V3 settings: rarity weights, valuation, ball policy, safety limits, allowlist, HUD, economics inputs, checklist, resources |
| `config/rareradar/watchlist.json` | Watchlist |
| `config/rareradar/data/*.jsonl` | Market, activities, hunts, encounters, sessions, bosses (append-only, human-readable) |
| `config/rareradar/data/backups/` | Zip backups |
| `config/rareradar/export/`, `import/` | CSV/JSON exports; files to import |

Corrupt lines are skipped and the original file is backed up. An invalid config file is kept as `*.invalid-<time>` and defaults are used.

### Market CSV format

```
timestamp,species,form,shiny,kind,price,ivs,nature,ability,note
1760700000000,gible,,true,SALE,250000,31/31/31/0/31/31,jolly,,bought from GTS
```

`kind` is one of `LISTING`, `SALE`, `STS`, `PURCHASE`. Prices accept `12k` and `1.5m` in the dashboard.

### How profit is calculated

* `P = R − C`, and `P/h = P / T × 60` (T in minutes).
* The engine uses ΣP / ΣT over your logged sessions (longer sessions weigh more), with a 90% delta-method interval.
* "Realised" uses only money actually received; "estimated" adds the value of unsold catches and items.

## Troubleshooting

* **Nothing on the radar:** the client only knows Pokémon the server has sent it, so range is limited by entity tracking.
* **"Cobblemon API problem … feature disabled":** your Cobblemon version differs from 1.8.2. That feature turns itself off; the rest keeps working.
* **"CobbleGalaxy bridge: '<feature>' disabled":** the CobbleGalaxy client mod changed. Use manual entry for that feature.
* **AutoHunt "refused":** expected on any multiplayer server not on your private allowlist, and always on CobbleGalaxy.
* Logs use the `RareRadar` logger in `logs/latest.log`.

## Known limitations

* Builds in CI but has not yet been run in-game (see the status note above).
* Wild Pokémon's nature, ability and IVs are not visible to the client, so they stay unknown. IVs are used only for your own Pokémon and for GTS listings.
* CobbleGalaxy economic constants (hunt rewards, STS prices, GTS fees, boss drop rates) are undocumented. RareRadar learns them from your data and never invents them. See `docs/COBBLEGALAXY_RESEARCH.md`.
* GTS **History** cards are recorded as sales only when their status text matches a configurable pattern; this mapping is a hypothesis to verify in-game.
* Catch success rate is only measured from AutoHunt throws. For manual play, hunt-time estimates use a neutral prior and are marked low confidence.
* Breeding rules beyond the documented Destiny Knot and Power-item behaviour are mainline Gen 6+ assumptions, and are labelled as such.

## Documentation

* `docs/ARCHITECTURE_AUDIT.md`: V1 audit, baseline build, technical debt.
* `docs/COBBLEGALAXY_RESEARCH.md`: what the server documents (and does not), with confidence levels.
* `docs/COBBLEGALAXY_CLIENT_BRIDGE.md`: exactly what the read-only bridge reads.
* `docs/IMPLEMENTATION_ROADMAP.md`: milestones and design decisions.
* `docs/PERFORMANCE.md`: measured micro-benchmarks and what is not yet measured.
* `CHANGELOG.md`
