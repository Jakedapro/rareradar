# CobbleGalaxy client-mod bridge (read-only)

Source: the official **CobbleGalaxy modpack 2.5.0** (CurseForge export supplied by the user). Inspected 2026-10-17.

* `manifest.json`: Minecraft **1.21.1**, Fabric Loader **0.19.3**, 47 CurseForge mods. They are referenced by project ID only, so their versions cannot be resolved offline. Cobblemon is CurseForge project 687131, file 9124652.
* `overrides/mods/CobbleGalaxy.jar`: the server's own client companion mod (`id: cobblegalaxy`, version 2.5.0, licence `LicenseRef-CobbleGalaxy-Modpack`). It nests `spaceship-common-*` protocol jars for hunts, GTS, dexrewards, voting, pokebuilder, pass, gyms and more.

The jar was inspected only for class and field **signatures** (`javap`), to interoperate. No CobbleGalaxy code is copied or redistributed. RareRadar accesses these classes **only through reflection**, so:

* RareRadar has no compile-time or runtime dependency on CobbleGalaxy. Every access fails soft when the class or field is missing or renamed (for example after a CobbleGalaxy update), and the corresponding feature falls back to manual entry.
* RareRadar **never** calls `GTSNetworking.send`, `PokeBuilderNetworking.send`, or any other method that sends packets or acts on menus. It only reads data the server has already sent to this client for display.
* Seller names in GTS cards are never stored.

## Data the server sends to the client (and what RareRadar does with it)

| Feature | Client class (reflection target) | Fields used | RareRadar use |
|---|---|---|---|
| Pinned hunts overlay | `com.cobblegalaxy.client.hunts.HuntsOverlayState` (static) | `entries()` → `Entry(group, groupIcon, title, sprite, details, deadline, totalMillis, accent)`; `revision()`. `sprite` is a Cobblemon `PokemonItem` stack whose `cobblemon:pokemon_item` component holds the species id and aspects. | Imports pinned hunts automatically: species, aspects (e.g. `shiny`), title, details and deadline. Hunt targets are highlighted on the radar. |
| GTS | `com.cobblegalaxy.client.gts.GTSScreen` field `state` (`GTSSnapshot`) | `revision`, `view` ∈ {BROWSE, SELL, MINE, EXPIRED, HISTORY, LOOKUP}, `cards[]` → `GTSCard(id, price, statusJson, pokemon=GTSPokemon(species, aspects, level, gender, shiny, natureJson, abilityJson, ivs, evs))`; `sell.listingsUsed/listingsMax` | Records the listings the user is looking at as **LISTING** observations, deduplicated by card id and price. HISTORY cards are recorded as **SALE** only when their status text matches a configurable "sold" pattern. That mapping is a hypothesis; verify it in-game. |
| PokéBuilder | `com.cobblegalaxy.client.pokebuilder.PokeBuilderScreen` field `state` (`PokeBuilderSnapshot`) | `balance`, `costPerIv`, `costPerEv`, `ivMax`, `natures[].cost`, `abilities[].cost`, `balls[].cost`, `shiny.cost/available`, `level.up1` | Real per-option token costs for the PokéBuilder planner, replacing the "Unknown" in the research doc. |
| Pokédex rewards | `com.cobblegalaxy.client.dexrewards.DexRewardsScreen` field `state` (`DexRewardsState`) → `views` map of `TypeView(label, listKey, tiers[], caught BitSet, caughtCount, percentage)`, `speciesRows` map `listKey → [SpeciesRow(species, dexNumber, name)]`; `TierRow(id, percentage, claimed, claimable, name)` | Real milestone thresholds, claimed/claimable flags and the exact list of missing species for the Pokédex optimiser. |
| Voting | `com.cobblegalaxy.client.voting.VoteScreen` field `msg` (`VoteViewMsg`) | `partyVotes`, `partyTarget` | Vote Party progress on the daily checklist. |

All of these are client-visible only while the corresponding server menu is open, or for hunts, while hunts are pinned. RareRadar samples them at most a few times per second while a screen is open, and once per revision change.
