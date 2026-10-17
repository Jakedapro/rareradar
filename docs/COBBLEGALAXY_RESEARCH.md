# CobbleGalaxy Research (Mandatory Research Phase)

Verification date: **2026-10-17**.
Official wiki: https://cobblegalaxy-wiki.gitbook.io/cobblegalaxy-wiki (abbreviated `W` below; page paths are relative to it).

## How this research was done, and its limits

The development container could not open the wiki directly: HTTP fetch and DNS for `gitbook.io` were blocked. Web *search* worked, restricted to `cobblegalaxy-wiki.gitbook.io`, so everything below comes from **search-index snapshots of the official wiki pages**, not from reading the live pages. The search tool reported the snapshots as roughly 70–440 days old.

**Consequences:**

* Nothing here is marked "Verified (live page)". The best level available is **Reported**: a value stated on an official wiki page according to its indexed snapshot.
* Every figure should be re-checked against the live page before it is relied on. RareRadar never hardcodes any of these values into calculations. Where a value is used (e.g. the 150 Hunt Point Mega Bracelet target), it is a **user-editable default** shown with its source.

### Confidence levels used

| Level | Meaning |
|---|---|
| **Reported** | Stated on an official wiki page per its indexed snapshot (not re-read live). |
| **Standard Cobblemon** | Cobblemon behaviour verified from Cobblemon 1.8.2 source code. The server may override it. |
| **Hypothesis** | Plausible, needs in-game testing. |
| **Unknown** | Not documented in anything found. RareRadar must ask the user or learn from observations. |

## 0. Server rules relevant to this mod (MOST IMPORTANT)

Pages: `W/starting-off/rules`, `W/links/rules`. Confidence: **Reported**.

* **No cheating:** hacked clients, mods or tools giving an unfair advantage are prohibited. The examples given are **autoclickers, X-ray, macros, speed modifiers, and "any other automation or cheating software".**
* Abusing bugs, glitches or exploits is prohibited.
* **Spawn manipulation** (platforms or exploits that manipulate Pokémon spawns) is prohibited.
* Real-money trading is prohibited.
* No whitelist of allowed client mods was found. The rules say to ask staff when unsure.

**Engineering decisions that follow from these rules:**

1. **AutoHunt and AutoTrain** walk, aim, throw and battle automatically, so they are automation and macro behaviour. **They are hard-blocked on any multiplayer server unless the user adds that exact server address to a private-server allowlist.** They are additionally refused on any address containing `cobblegalaxy`, even if it is allowlisted. They remain available in single-player.
2. The radar/HUD, market database, calculators and dashboards only display information that the client already has, or that the user typed in. They send no packets and press no keys. Whether staff consider a radar acceptable is **not documented**: ask CobbleGalaxy staff before using any client mod there. The README says this explicitly.
3. RareRadar never runs server commands (`/gts`, `/hunt`, `/wild`, `/vote`, `/dailyrewards`, …) or clicks server menus.

## 1. Versions

| Item | Value | Source | Confidence |
|---|---|---|---|
| Minecraft / loader | 1.21.1, Fabric | `W/installation-guide/minecraft-installation` | Reported |
| Cobblemon version | Not stated | — | Unknown |
| Server address | `play.cobblegalaxy.com` | wiki / modpack listings | Reported |

RareRadar targets MC 1.21.1 + Fabric, which matches the server.

## 2. Mechanics table

For every mechanic, the table records: feature, command, how it works, rewards/costs, cooldowns/limits, unlock requirements, economic value, URL, verification date and confidence. All rows were verified on 2026-10-17 from search snapshots.

### Economy

| Feature | Command | How it works | Rewards / costs | Cooldown / limits | Unlock | Economic value | URL | Confidence |
|---|---|---|---|---|---|---|---|---|
| Global Trading System | `/gts`, `/gts sell pokemon <slot> <price>` | Player marketplace for Pokémon and items. The seller sets the price. Sale proceeds go to the balance automatically. Unsold listings move to "Expired" (via the Link Cable button). Listings show IVs and shininess. | Seller-chosen price. Fees/tax: **Unknown** | Listing limit / duration: **Unknown** | None stated | Primary price-discovery source. The wiki itself mentions flipping shinies and rare items. | `W/starting-off/gts` | Reported |
| Server selling (STS) | `/sts` | Sell party Pokémon to the server instantly. | Price depends on rarity, IVs/stats and special traits. Shininess adds "only slightly". Generally lower than GTS. **Exact prices Unknown.** | Legendaries and some event Pokémon cannot be sold | None stated | Price floor / liquidity of last resort | `W/starting-off/sts` | Reported |
| Player warps | `/pwarp create <name>`, `/warps` | Public warps created inside claims you own or are trusted in. | Create: **5,000 Pokédollars**. Teleporting: free | Naming rules | Own or trusted claim | Travel cost for shop/farm routes | `W/other-features` | Reported |
| Player shops | — | No dedicated system found; warps are suggested for shops. | — | — | — | — | — | Unknown |
| TM Shop | (menu) | Random selection of 21 TMs, rotating daily. | Prices **Unknown** | Daily rotation | — | Minor | `W/other-features` | Reported |
| WonderTrade | command **Unknown** | Random trade. | — | **30-minute cooldown** between trades | — | Speculative / negative EV for valuable Pokémon | `W/server-features/wondertrade` | Reported |
| Trading between players | — | Mentioned only through GTS and WonderTrade. | — | — | — | — | — | Unknown |

### Progression

| Feature | Command | How it works | Rewards / costs | Cooldown / limits | Unlock | Economic value | URL | Confidence |
|---|---|---|---|---|---|---|---|---|
| Hunts | `/hunt` | Menu with three hunt categories (names **Unknown**), each with 1★/2★/3★ difficulty. 3★ hunts may ask for rare, evolved or shiny Pokémon. Higher difficulty gives more points. Hunts count toward a Hunt Leaderboard. | **Hunt Points** (amount per tier **Unknown**); Pokédollar payouts **Unknown** | Refresh timer **Unknown** | None stated | Hunt Points buy the Mega Bracelet and PokéBuilder tokens | `W/starting-off/hunts` | Reported |
| Hunt Points shop | (Hunt Shop) | Spend Hunt Points. | **Mega Bracelet = 150 Hunt Points**; PokéBuilder tokens (rate **Unknown**) | — | — | Converts hunt effort into tokens/bracelet | `W/server-features/mega-evolution`, `W/server-features/pokebuilder` | Reported |
| Hunt XP / hunt levels / Challenge Hunts / Global Hunts / "Easy" hunts | — | **Not found** in any indexed page. | — | — | — | — | — | Unknown |
| Pokédex rewards | `/tags` | Completion percentages unlock cosmetic chat **tags**. | Thresholds **Unknown**; no monetary reward found | — | — | Cosmetic only (as far as documented) | `W/other-features` | Reported |
| Daily rewards | `/dailyrewards` | Claim once per 24 h. Ranked players get better rewards. | Contents **Unknown** | **24 h** | — | Fixed daily income | `W/starting-off/daily-rewards` | Reported |
| Calendar | `/calendar` | Daily calendar prizes, with occasional holiday bonuses. | Includes PokéBuilder Tokens, Rare Candy, Master Balls; Mega Stones sometimes | Daily | Open to all | Tokens and Master Balls have resale or use value | `W/starting-off/daily-rewards` | Reported |
| Voting | `/vote` | 6 vote sites. Each vote gives 1 Vote Key for the Vote Crate at spawn. You must be online, and a vote may take up to 1 h to register. | Crate: items, Pokédollars, Claim Blocks | Up to **6 keys/day** | — | Low-effort daily value | `W/starting-off/daily-rewards` | Reported |
| Vote Party | (automatic) | Triggers when the shared vote pool reaches **200 votes**. | Everyone online gets 1 bonus Vote Key, plus **3.0× shiny spawn rate for 30 minutes** | Event-driven | Be online | Short high-value shiny-hunting window | `W/starting-off/daily-rewards` | Reported |

### Pokémon optimisation

| Feature | Command | How it works | Rewards / costs | Cooldown / limits | Unlock | Economic value | URL | Confidence |
|---|---|---|---|---|---|---|---|---|
| Breeding | Pasture Block + Wooden Hoe | Load two compatible Pokémon (Ditto works with any compatible species) and hit the block with a wooden hoe. You must stay in the chunk for progress. A chat message announces the egg. | Breeding item costs are market-dependent | **Max 4 Pasture Blocks** at once | Craft pasture | Competitive-IV offspring for GTS | `W/server-features/breeding` | Reported |
| IV inheritance | — | **Destiny Knot passes 5 IVs** from parents. A **Power item locks a specific IV**. Other inheritance follows "typical breeding rules". | — | — | — | Core of the breeding calculator | `W/server-features/breeding` | Reported |
| Nature / Everstone, hidden abilities, shiny egg odds | — | Not documented. RareRadar assumes mainline (Gen 6+) behaviour and **labels it as an assumption**. | — | — | — | — | — | Hypothesis |
| PokéBuilder | `/pokebuilder` | Spend tokens to change IVs, EVs, level, gender, nature, ability, shiny, Poké Ball, OT. | Each option costs tokens; **per-option costs Unknown** | — | Tokens from crates, Hunt Store, daily/calendar, Battle Factory shop | Raises the value of individual Pokémon | `W/server-features/pokebuilder` | Reported |
| Mega Bracelet | Hunt Shop | Hold it in the off-hand; it activates in battle. | 150 Hunt Points, or a rare Super Crate drop | One Mega per battle | — | Gate for using Mega Stones | `W/server-features/mega-evolution` | Reported |
| Mega Stones | sneak + right-click the Pokémon with the stone (Mega Band in off-hand) | Each Pokémon needs its matching stone. | Sources: **Boss Pokémon (level 100+) drop a random Mega Stone**, crates, calendar (sometimes), trading | — | — | Tradeable resource | `W/server-features/mega-evolution` | Reported |
| Boss Pokémon | — | Wild bosses at level 100+. | Random Mega Stone drop | Spawn rate **Unknown** | — | — | `W/server-features/mega-evolution` | Reported. Drop *frequency* is Unknown; RareRadar estimates it from the user's own logged results. |

### Additional systems

| Feature | Command | Notes | URL | Confidence |
|---|---|---|---|---|
| Raids | `/raids` | Parties of 1–4, one Pokémon each, shared revive pool. **3 raids/day**, max Raid Level 30. Top 2 damage dealers get a capture chance. Raid Level improves shiny, max-IV and HA odds. | `W/server-features/raids` | Reported |
| Battle Factory | — | Rent 3 of 6, 10 floors. **3 BP (floor 1) to 100 BP (floor 10)**. Shop sells Master Balls, Rare Candy, Crate Keys, Z-Crystals and tokens. | `W/server-features/battle-factory` | Reported |
| NPC Gyms | — | Three reward tiers per gym, each claimable once. | `W/server-features/npc-gyms` | Reported |
| Shiny hunting / base shiny rate | — | Base rate **not documented**. The only documented modifier is the Vote Party ×3.0 for 30 min. | — | Unknown |
| Spawn modifiers | — | None documented apart from the Vote Party. Spawn manipulation is against the rules. | — | Unknown |
| Public warps / `/spawn` | — | Only player warps were found. | — | Unknown |
| `/wild` (wilderness teleport) | — | **Not documented.** RareRadar only *reacts* to a large position change or a dimension change after the player teleports; it never issues the command. | — | Unknown |
| Server events | — | Announced on Discord; no in-wiki schedule found. | `W/links/discord-server` | Reported |

## 3. Standard Cobblemon mechanics (verified from Cobblemon 1.8.2 source)

* A thrown `EmptyPokeBallEntity` carries a client-synced `CaptureState` ∈ {`NOT`, `HIT`, `FALL`, `CRITICAL`, `SHAKE`, `CAPTURED`, `BROKEN_FREE`}. RareRadar uses this to verify captures.
* Species and form **labels** (`legendary`, `mythical`, `ultra_beast`, `paradox`, …) are used for classification. This is existing RareRadar behaviour.
* The client receives wild Pokémon entities with species, form, shiny state, level and aspects. **Nature, ability, IVs and EVs of wild Pokémon are not reliably sent to the client.** RareRadar treats them as unknown unless the Pokémon is in the player's own party or PC.

## 4. Hypotheses requiring in-game testing

1. Hunt objectives are only visible inside a server GUI, so there is no reliable client-readable data. RareRadar therefore uses **manual hunt entry**.
2. GTS listings are only visible inside server GUIs. RareRadar therefore uses **manual price entry** plus CSV/JSON import.
3. Breeding follows mainline Gen 6+ rules beyond what is documented: 3 inherited IVs without a Destiny Knot, Everstone passes nature, and with two Power items one is chosen at random.
4. The Vote Party shiny multiplier applies to natural spawns server-wide.

## 5. Unknown (must come from the user's own observations)

GTS fees/limits; STS prices; Hunt Point amounts per tier; hunt refresh timers; Hunt XP/levels; Challenge/Global hunts; Pokédex milestone thresholds; PokéBuilder per-option token costs; Hunt Point → token exchange rate; boss spawn rate; base shiny rate; `/wild` behaviour; Cobblemon version; whether staff allow radar/HUD client mods.

**No market price, reward amount, spawn rate or cooldown is invented anywhere in RareRadar.** Every economic input is either user-entered, imported from the user's own files, or learned from the user's logged observations.
