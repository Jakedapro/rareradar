# Knowledge base (V4)

RareRadar ships an offline, read-only copy of stock **Cobblemon 1.8.2** game data: species, forms,
evolutions, spawn entries and biome tags. It is generated from the official Cobblemon data files and
loaded by `dev.rareradar.core.knowledge.KnowledgeBase`.

## Files

Location: `src/main/resources/assets/rareradar/knowledge/` (packaged in the jar as
`assets/rareradar/knowledge/`).

| File | Contents | Records (1.8.2) | Size (gzip) |
|---|---|---|---|
| `species.json.gz` | species + forms | 1025 species, 396 forms | 344,556 B |
| `spawns.json.gz` | every entry of every `spawn_pool_world` file + raw `spawn_detail_presets` | 5044 entries from 1591 files, 28 presets | 172,652 B |
| `biome_tags.json.gz` | `data/cobblemon/tags/worldgen/biome/**` | 192 tags | 9,636 B |
| `meta.json` | schema version, source, counts, sha256 of each dataset | | ~1.2 KB |
| `NOTICE` | MPL-2.0 attribution for the Cobblemon data | | |

Total compressed: 526,844 bytes. The generator is deterministic: the `.gz` files are byte-identical for the
same input; only `meta.json`'s `generatedAt` changes.

## Provenance

* Source: <https://gitlab.com/cable-mc/cobblemon.git>, tag `1.8.2`, commit
  `643bf9068b51c829316429916373e5aa9864b8b9`, path `common/src/main/resources/data/cobblemon/`.
* Every dataset carries a top-level `source` object (`name`, `repo`, `version`, `commit`,
  `commitVerified`, `dataRoot`, `license`, `confidence`). The generator reads the checkout's `.git/HEAD`
  and refuses to run if it is not the `--commit` given.
* Every record carries `sourceFile` (repository-relative path of the JSON it came from) and
  `confidence: "verified"`.
* **"verified" means only:** extracted unchanged from the official Cobblemon 1.8.2 data files at that commit.
  It has **not** been validated against CobbleGalaxy (or any other server's) datapack overrides, nor against
  the Cobblemon wiki (the wiki was not reachable when this was built and was not used).
* License: Cobblemon is MPL-2.0. The `NOTICE` file in the dataset folder attributes the data and names the
  source commit. Keep it next to the datasets.

## Schema (schemaVersion 1)

All values are copied from the data files. **A value the file does not contain is written as `null` (or
omitted from form `overrides`); nothing is defaulted, inferred or invented.**

### species.json.gz

```
{ "schemaVersion": 1, "dataset": "species", "source": {...}, "species": [ <species>, ... ] }   // sorted by dex
```

Species record:

| Field | Notes |
|---|---|
| `id` | `cobblemon:<file name>`, e.g. `cobblemon:mrmime` |
| `nationalPokedexNumber`, `name` | |
| `implemented` | `true`, or `null` when the file has no `implemented` key (125 species in 1.8.2). Cobblemon's in-code default for a missing key is `false` (`Species.kt`). |
| `primaryType`, `secondaryType` | |
| `baseStats`, `evYield` | `{hp, attack, defence, special_attack, special_defence, speed, total}`; `total` is computed by the generator only when all six are present |
| `abilities` | raw strings; hidden abilities keep the data's `h:` prefix |
| `catchRate`, `baseExperienceYield`, `experienceGroup`, `eggGroups`, `maleRatio` | `maleRatio` is `-1` for genderless species, as in the data |
| `labels` | exactly as in the data (`legendary`, `mythical`, `ultra_beast`, `paradox`, `restricted`, `starter`, `baby`, `fossil`, `powerhouse`, `genN`, `*_form`, …) |
| `aspects`, `preEvolution` | |
| `evolutions` | `{id, variant, result, consumeHeldItem, requirements, requiredContext, learnableMoves}`; `requirements`/`requiredContext` are the raw JSON (null when absent) |
| `moves` | raw strings, e.g. `"1:tackle"`, `"egg:curse"`, `"tm:bodyslam"`; prefixes in 1.8.2: level numbers, `egg`, `tm`, `tutor`, `legacy`, `special`, `form_change` |
| `forms` | `[{name, aspects, overrides, evolutions}]` |
| `sourceFile`, `confidence` | provenance |

Form `overrides` contain only keys the form file sets, from: `primaryType, secondaryType, baseStats,
evYield, abilities, catchRate, baseExperienceYield, experienceGroup, eggGroups, maleRatio, labels, moves,
preEvolution, battleOnly, baseFriendship, eggCycles, height, weight`. Form `evolutions` is `null` when the
form does not declare any. Inheritance follows Cobblemon's `FormData.kt`: an unset override falls back to
the species value; `secondaryType` falls back only if the form sets neither type; a form's evolutions do
not fall back. `Species.baseStatsFor/evYieldFor/catchRateFor/labelsFor/typesFor(form)` implement this.

Not included: behaviour, drops, hitbox/scale, riding, lighting, pokédex text keys, `dex_entries/`.

### spawns.json.gz

```
{ "schemaVersion": 1, "dataset": "spawns", "source": {...},
  "presets": { "<name>": { "definition": <raw preset JSON>, "sourceFile", "confidence" } },
  "unresolvedPresets": [],            // preset names used by spawns but not found (none in 1.8.2)
  "spawns": [ <entry>, ... ] }        // file order, then entry order
```

Entry: `id, type` (`pokemon` / `pokemon-herd` / `empty`), `pokemon` (raw species + properties string),
`species` (first token), `properties` (rest, e.g. `region_bias=alola`), `herdSpecies`, `herdablePokemon`
(raw), `maxHerdSize`, `bucket`, `level`, `levelRange`, `weight`, `percentage`, `weightMultiplier`,
`weightMultipliers` (raw), `spawnablePositionType` (falls back to a `context` key if that spelling is used),
`condition`, `anticondition` (raw objects), `biomes` / `excludedBiomes` (copied from
`condition.biomes` / `anticondition.biomes`), `presets` (raw names), `drops` (raw), file-level
`poolEnabled` (`null` when omitted; Cobblemon default `true`, `SpawnSet.kt`), `neededInstalledMods`,
`neededUninstalledMods`, `sourceFile`, `confidence`.

**Weights are Cobblemon's raw relative weights within a bucket. They are not probabilities** and nothing
here converts them.

Presets are stored once, raw. They are not merged into entries, because Cobblemon's merge
(`SpawnDetailPreset.apply`) copies preset condition fields into each of the entry's conditions according to
a merge mode and adds the preset anticondition as an extra anticondition; reproducing that is left to a
consumer that needs it. `KnowledgeBase.preset(name)` returns the raw definition.

### biome_tags.json.gz

```
{ "schemaVersion": 1, "dataset": "biome_tags", "source": {...},
  "tags": [ { "id": "cobblemon:is_forest", "replace": false,
              "values": [ {"id": "#minecraft:is_forest", "required": true},
                          {"id": "minecraft:cherry_grove", "required": true},
                          {"id": "biomesoplenty:orchard", "required": false}, ... ],
              "sourceFile", "confidence" } ] }
```

Sub-folders become part of the id (`cobblemon:nether/is_forest`, `cobblemon:evolution/regional/...`).
Plain string values are written with `required: true` (Minecraft's tag semantics). Vanilla
(`#minecraft:…`) and convention (`#c:…`) tags are referenced but not defined here; they come from Minecraft
/ other mods.

### meta.json

`schemaVersion`, `cobblemonVersion`, `sourceRepo`, `sourceCommit`, `commitVerified`, `license`,
`confidence`, `confidenceNote`, `generatedAt` (UTC ISO-8601), `generator`, `counts` (species, forms,
spawnFiles, spawns, spawnPresets, unresolvedPresets, biomeTags) and `datasets.<file>.{sha256, bytes,
uncompressedBytes}`.

## Loader (`dev.rareradar.core.knowledge`)

```java
KnowledgeBase kb = KnowledgeBase.loadFromClasspath(MyMod.class.getClassLoader());   // in the mod
KnowledgeBase kb = KnowledgeBase.load(KnowledgeBase.DatasetSource.directory(dir));   // tests / tools
if (!kb.isLoaded()) log(kb.errors());
```

* **Fail-soft, all-or-nothing:** a missing file, non-gzip data, malformed JSON, a sha256 that does not match
  `meta.json`, an unsupported `schemaVersion` or a throwing source yields an *empty* `KnowledgeBase` with
  `errors()` set. `load` never throws; queries on an empty base return null / empty lists.
* Streams each gzip file with Gson's `JsonReader`, one record tree at a time, and interns repeated strings
  (move ids, types, biome refs) during load. Raw JSON (conditions, requirements, multipliers) is kept as
  compact strings and parsed only on request (`SpawnEntry.condition()`, `Evolution.requirements()`).
* Queries: `species(idOrName)` (accepts `cobblemon:mrmime`, `mrmime`, `Mr. Mime`, `pikachu region_bias=alola`),
  `byDex`, `forms`, `evolutionsFrom` (species + form evolutions), `evolutionsTo`, `evYield`, `catchRate`,
  `labels`, `withLabel`, `legendaries`, `mythicals`, `spawnsFor` (includes herd membership),
  `biomeRefs(biomeId, runtimeTags)`, `spawnsInBiome`, `speciesSpawnableInBiome`, `preset`, `validate()`.
* Biome matching: `biomeRefs` starts from the biome id and its runtime tags (pass the tags the live biome
  registry reports) and adds, transitively, every Cobblemon biome tag containing any of them. A spawn entry
  is *biome-eligible* if its `condition.biomes` is empty or contains one of those refs and its
  `anticondition.biomes` contains none. **Only biome lists are checked** — light, sky, time, height,
  structures, blocks, presets, weather, lure/bait and every other condition are not evaluated, so
  "biome-eligible" is not "will spawn here".
* `validate()` reports: duplicate ids / dex numbers, missing dex/name/type/catch rate, incomplete base stats
  or EV yield (species and forms), unknown evolution results / pre-evolutions, spawn entries or herd members
  naming unknown species, missing weight/bucket, references to undefined `#cobblemon:` biome tags (in spawns
  and in required tag values), unresolved presets, and counts that differ from `meta.json`. On the 1.8.2
  data it returns an empty list (asserted in `KnowledgeBaseTest`; no known issues are allow-listed).

## Regenerating

```
# 1. Sparse checkout of the Cobblemon data at the tag (only data folders are needed).
#    Example commands: the committed files were generated from an existing sparse checkout of that
#    commit, so this step was not re-run verbatim.
git clone --filter=blob:none --no-checkout https://gitlab.com/cable-mc/cobblemon.git cobblemon-src
git -C cobblemon-src sparse-checkout set --no-cone \
    '/common/src/main/resources/data/cobblemon/species/' \
    '/common/src/main/resources/data/cobblemon/spawn_pool_world/' \
    '/common/src/main/resources/data/cobblemon/spawn_detail_presets/' \
    '/common/src/main/resources/data/cobblemon/tags/' '/LICENSE'
git -C cobblemon-src checkout 643bf9068b51c829316429916373e5aa9864b8b9     # tag 1.8.2

# 2. Generate (Python 3 standard library only; -I so nothing is imported from the checkout)
python3 -I tools/knowledge/build_knowledge.py \
    --source cobblemon-src --tag 1.8.2 --commit 643bf9068b51c829316429916373e5aa9864b8b9

# 3. Test
gradle -p core test
```

The command in step 2 is exactly what produced the committed files (with `--source` pointing at a sparse
checkout of that commit). For a new Cobblemon version, pass the new tag/commit, then update the concrete
facts and counts asserted in `core/src/test/java/dev/rareradar/core/knowledge/KnowledgeBaseTest.java`
after checking them against the new source files. Bump `schemaVersion` (generator and
`KnowledgeBase.SUPPORTED_SCHEMA`) only when the file layout changes.

## Limits

* **Stock Cobblemon only.** Servers such as CobbleGalaxy can add, remove or change species, forms and spawn
  pools with datapacks or server-side mods. None of that is reflected here, and nothing in this dataset was
  checked against CobbleGalaxy. On a live server, the client's synced runtime registries (species, biome
  tags) are the authority; use this knowledge base as an offline fallback and for static facts.
* Cobblemon spawns are decided server-side; clients do not receive spawn pools. Spawn data here describes
  stock 1.8.2 rules, not what a server actually uses.
* Spawn eligibility helpers check biome lists only (see above). Weights are relative, not probabilities.
* Vanilla and `#c:` biome tags are not defined in this dataset; pass the biome's runtime tags to resolve them.
* Wiki pages (wiki.cobblemon.com and others) were not retrieved; no data comes from them.
* `dex_entries/`, behaviour, drops, riding and model/lighting data are not included.
