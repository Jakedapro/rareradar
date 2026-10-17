#!/usr/bin/env python3
"""Build RareRadar's offline knowledge datasets from a Cobblemon source checkout.

Standard library only. Run isolated so nothing is imported from the checkout:

    python3 -I tools/knowledge/build_knowledge.py \
        --source /path/to/cobblemon-checkout \
        --tag 1.8.2 --commit 643bf9068b51c829316429916373e5aa9864b8b9

The checkout is treated as untrusted data: JSON files are parsed, nothing is executed.
Outputs (gzip-compressed JSON, deterministic apart from meta.json's generatedAt):

    species.json.gz     species + forms (form overrides only, as written in the data)
    spawns.json.gz      every spawn entry in spawn_pool_world/ plus the raw spawn_detail_presets
    biome_tags.json.gz  data/cobblemon/tags/worldgen/biome/**
    meta.json           schema version, source, counts, sha256 per dataset
    NOTICE              MPL-2.0 attribution for the Cobblemon data

Missing values are written as null; nothing is inferred or invented.
"""

import argparse
import datetime
import gzip
import hashlib
import io
import json
import os
import sys

SCHEMA_VERSION = 1
DEFAULT_REPO = "https://gitlab.com/cable-mc/cobblemon.git"
DATA_ROOT = "common/src/main/resources/data/cobblemon"
CONFIDENCE = "verified"  # extracted verbatim from the official data files of the named commit

STAT_KEYS = ("hp", "attack", "defence", "special_attack", "special_defence", "speed")

# Form keys copied as overrides (FormData.kt falls back to the species for every one of these
# except evolutions, which default to empty, and secondaryType, see docs/KNOWLEDGE_BASE.md).
FORM_OVERRIDE_KEYS = (
    "primaryType", "secondaryType", "baseStats", "evYield", "abilities", "catchRate",
    "baseExperienceYield", "experienceGroup", "eggGroups", "maleRatio", "labels", "moves",
    "preEvolution", "battleOnly", "baseFriendship", "eggCycles", "height", "weight",
)


def fail(msg):
    print("build_knowledge: error: " + msg, file=sys.stderr)
    sys.exit(2)


def load_json(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def rel(source, path):
    return os.path.relpath(path, source).replace(os.sep, "/")


def walk_json(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames):
            if name.endswith(".json"):
                out.append(os.path.join(dirpath, name))
    out.sort()
    return out


def read_head_commit(source):
    """Returns the checked-out commit by reading .git files (no git subprocess), or None."""
    git_dir = os.path.join(source, ".git")
    if os.path.isfile(git_dir):  # worktree / submodule pointer file
        with open(git_dir, "r", encoding="utf-8") as f:
            line = f.read().strip()
        if not line.startswith("gitdir:"):
            return None
        git_dir = os.path.normpath(os.path.join(source, line[len("gitdir:"):].strip()))
    head_path = os.path.join(git_dir, "HEAD")
    if not os.path.isfile(head_path):
        return None
    with open(head_path, "r", encoding="utf-8") as f:
        head = f.read().strip()
    if not head.startswith("ref:"):
        return head
    ref = head[4:].strip()
    ref_file = os.path.join(git_dir, *ref.split("/"))
    if os.path.isfile(ref_file):
        with open(ref_file, "r", encoding="utf-8") as f:
            return f.read().strip()
    packed = os.path.join(git_dir, "packed-refs")
    if os.path.isfile(packed):
        with open(packed, "r", encoding="utf-8") as f:
            for line in f:
                parts = line.strip().split(" ")
                if len(parts) == 2 and parts[1] == ref:
                    return parts[0]
    return None


def stats(obj):
    if not isinstance(obj, dict):
        return None
    out = {k: obj.get(k) for k in STAT_KEYS}
    vals = [v for v in out.values() if isinstance(v, int)]
    out["total"] = sum(vals) if len(vals) == len(STAT_KEYS) else None
    return out


def evolution(ev):
    return {
        "id": ev.get("id"),
        "variant": ev.get("variant"),
        "result": ev.get("result"),
        "consumeHeldItem": ev.get("consumeHeldItem"),
        "requirements": ev.get("requirements"),       # raw structured JSON, null when absent
        "requiredContext": ev.get("requiredContext"),  # raw, null when absent
        "learnableMoves": ev.get("learnableMoves"),
    }


def species_record(source, path):
    d = load_json(path)
    sid = os.path.splitext(os.path.basename(path))[0]
    forms = []
    for fo in d.get("forms") or []:
        overrides = {}
        for k in FORM_OVERRIDE_KEYS:
            if k in fo:
                v = fo[k]
                overrides[k] = stats(v) if k in ("baseStats", "evYield") else v
        forms.append({
            "name": fo.get("name"),
            "aspects": fo.get("aspects"),
            "overrides": overrides,
            "evolutions": [evolution(e) for e in fo["evolutions"]] if "evolutions" in fo else None,
        })
    return {
        "id": "cobblemon:" + sid,
        "nationalPokedexNumber": d.get("nationalPokedexNumber"),
        "name": d.get("name"),
        "implemented": d.get("implemented"),  # null when the file omits it (Species.kt default: false)
        "primaryType": d.get("primaryType"),
        "secondaryType": d.get("secondaryType"),
        "baseStats": stats(d.get("baseStats")),
        "abilities": d.get("abilities"),
        "catchRate": d.get("catchRate"),
        "baseExperienceYield": d.get("baseExperienceYield"),
        "experienceGroup": d.get("experienceGroup"),
        "evYield": stats(d.get("evYield")),
        "eggGroups": d.get("eggGroups"),
        "maleRatio": d.get("maleRatio"),
        "labels": d.get("labels"),
        "aspects": d.get("aspects"),
        "preEvolution": d.get("preEvolution"),
        "evolutions": [evolution(e) for e in d.get("evolutions") or []],
        "moves": d.get("moves"),
        "forms": forms,
        "sourceFile": rel(source, path),
        "confidence": CONFIDENCE,
    }


def biomes_of(cond):
    if isinstance(cond, dict) and isinstance(cond.get("biomes"), list):
        return [b for b in cond["biomes"] if isinstance(b, str)]
    return []


def spawn_records(source, path):
    d = load_json(path)
    out = []
    file_meta = {
        "poolEnabled": d.get("enabled"),  # null when omitted (SpawnSet.kt default: true)
        "neededInstalledMods": d.get("neededInstalledMods"),
        "neededUninstalledMods": d.get("neededUninstalledMods"),
    }
    for s in d.get("spawns") or []:
        if not isinstance(s, dict):
            continue
        pokemon = s.get("pokemon")
        species = properties = None
        if isinstance(pokemon, str) and pokemon.strip():
            head, _, rest = pokemon.strip().partition(" ")
            species = head
            properties = rest.strip() or None
        herd = s.get("herdablePokemon")
        herd_species = None
        if isinstance(herd, list):
            herd_species = [h["pokemon"].split(" ")[0] for h in herd
                            if isinstance(h, dict) and isinstance(h.get("pokemon"), str)]
        cond, anti = s.get("condition"), s.get("anticondition")
        rec = {
            "id": s.get("id"),
            "type": s.get("type"),
            "pokemon": pokemon,
            "species": species,
            "properties": properties,
            "herdSpecies": herd_species,
            "herdablePokemon": herd,
            "maxHerdSize": s.get("maxHerdSize"),
            "bucket": s.get("bucket"),
            "level": s.get("level"),
            "levelRange": s.get("levelRange"),
            "weight": s.get("weight"),
            "percentage": s.get("percentage"),
            "weightMultiplier": s.get("weightMultiplier"),
            "weightMultipliers": s.get("weightMultipliers"),
            # Cobblemon accepts "context" as an alternate name for spawnablePositionType.
            "spawnablePositionType": s.get("spawnablePositionType", s.get("context")),
            "condition": cond,
            "anticondition": anti,
            "biomes": biomes_of(cond),
            "excludedBiomes": biomes_of(anti),
            "presets": s.get("presets"),
            "drops": s.get("drops"),
            "sourceFile": rel(source, path),
            "confidence": CONFIDENCE,
        }
        rec.update(file_meta)
        out.append(rec)
    return out


def biome_tag_record(source, tag_root, path):
    d = load_json(path)
    rel_tag = os.path.splitext(os.path.relpath(path, tag_root))[0].replace(os.sep, "/")
    values = []
    for v in d.get("values") or []:
        if isinstance(v, str):
            values.append({"id": v, "required": True})
        elif isinstance(v, dict) and isinstance(v.get("id"), str):
            values.append({"id": v["id"], "required": v.get("required", True)})
    return {
        "id": "cobblemon:" + rel_tag,
        "replace": d.get("replace"),
        "values": values,
        "sourceFile": rel(source, path),
        "confidence": CONFIDENCE,
    }


def write_gz(path, payload):
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=False).encode("utf-8")
    buf = io.BytesIO()
    # mtime=0 and no filename -> byte-identical output for identical input
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as gz:
        gz.write(raw)
    data = buf.getvalue()
    with open(path, "wb") as f:
        f.write(data)
    return len(raw), len(data), hashlib.sha256(data).hexdigest()


NOTICE = """RareRadar knowledge datasets
============================

The files species.json.gz, spawns.json.gz and biome_tags.json.gz in this folder are derived
from the data files of Cobblemon (https://gitlab.com/cable-mc/cobblemon, by cable-mc and the
Cobblemon contributors), version {tag}, commit {commit}, path {root}/.

Cobblemon is licensed under the Mozilla Public License, Version 2.0 (MPL-2.0).
A copy of the license is available at https://mozilla.org/MPL/2.0/ and in the Cobblemon
repository (LICENSE). The values in these datasets were extracted and reformatted (re-keyed,
filtered, gzip-compressed) by tools/knowledge/build_knowledge.py; they were not altered.
The source form of the data is available from the repository and commit named above.
"""


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", required=True, help="path to a Cobblemon git checkout")
    ap.add_argument("--tag", required=True, help="Cobblemon version/tag, e.g. 1.8.2")
    ap.add_argument("--commit", required=True, help="expected full commit sha of the checkout")
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--out", default=None, help="output dir (default: <repo>/src/main/resources/assets/rareradar/knowledge)")
    ap.add_argument("--allow-unverified-commit", action="store_true",
                    help="do not abort when the checkout's HEAD cannot be read")
    args = ap.parse_args(argv)

    source = os.path.abspath(args.source)
    head = read_head_commit(source)
    if head is None:
        if not args.allow_unverified_commit:
            fail("cannot read the checkout's HEAD commit; pass --allow-unverified-commit to proceed anyway")
    elif head != args.commit:
        fail("checkout HEAD is %s, expected --commit %s" % (head, args.commit))

    data_root = os.path.join(source, *DATA_ROOT.split("/"))
    dirs = {k: os.path.join(data_root, k) for k in ("species", "spawn_pool_world", "spawn_detail_presets")}
    tag_root = os.path.join(data_root, "tags", "worldgen", "biome")
    for k, p in list(dirs.items()) + [("tags/worldgen/biome", tag_root)]:
        if not os.path.isdir(p):
            fail("missing directory %s (sparse checkout incomplete?)" % p)

    repo_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out = os.path.abspath(args.out or os.path.join(repo_root, "src", "main", "resources", "assets", "rareradar", "knowledge"))
    os.makedirs(out, exist_ok=True)

    dataset_source = {
        "name": "Cobblemon",
        "repo": args.repo,
        "version": args.tag,
        "commit": args.commit,
        "commitVerified": head == args.commit,
        "dataRoot": DATA_ROOT,
        "license": "MPL-2.0",
        "confidence": CONFIDENCE,
    }

    species = [species_record(source, p) for p in walk_json(dirs["species"])]
    species.sort(key=lambda r: (r["nationalPokedexNumber"] if isinstance(r["nationalPokedexNumber"], int) else 1 << 30, r["id"]))

    spawns = []
    spawn_files = walk_json(dirs["spawn_pool_world"])
    for p in spawn_files:
        spawns.extend(spawn_records(source, p))

    presets = {}
    for p in walk_json(dirs["spawn_detail_presets"]):
        name = os.path.splitext(os.path.relpath(p, dirs["spawn_detail_presets"]))[0].replace(os.sep, "/")
        presets[name] = {"definition": load_json(p), "sourceFile": rel(source, p), "confidence": CONFIDENCE}
    unresolved = sorted({n for s in spawns for n in (s.get("presets") or []) if n not in presets})

    tags = [biome_tag_record(source, tag_root, p) for p in walk_json(tag_root)]
    tags.sort(key=lambda t: t["id"])

    results = {}
    results["species.json.gz"] = write_gz(os.path.join(out, "species.json.gz"), {
        "schemaVersion": SCHEMA_VERSION, "dataset": "species", "source": dataset_source, "species": species})
    results["spawns.json.gz"] = write_gz(os.path.join(out, "spawns.json.gz"), {
        "schemaVersion": SCHEMA_VERSION, "dataset": "spawns", "source": dataset_source,
        "presets": presets, "unresolvedPresets": unresolved, "spawns": spawns})
    results["biome_tags.json.gz"] = write_gz(os.path.join(out, "biome_tags.json.gz"), {
        "schemaVersion": SCHEMA_VERSION, "dataset": "biome_tags", "source": dataset_source, "tags": tags})

    meta = {
        "schemaVersion": SCHEMA_VERSION,
        "cobblemonVersion": args.tag,
        "sourceRepo": args.repo,
        "sourceCommit": args.commit,
        "commitVerified": head == args.commit,
        "license": "MPL-2.0",
        "confidence": CONFIDENCE,
        "confidenceNote": "Extracted from the official Cobblemon data files of the named commit. "
                          "Not validated against any server's datapack overrides (e.g. CobbleGalaxy).",
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "generator": "tools/knowledge/build_knowledge.py",
        "counts": {
            "species": len(species),
            "forms": sum(len(s["forms"]) for s in species),
            "spawnFiles": len(spawn_files),
            "spawns": len(spawns),
            "spawnPresets": len(presets),
            "unresolvedPresets": len(unresolved),
            "biomeTags": len(tags),
        },
        "datasets": {name: {"sha256": sha, "bytes": gz_len, "uncompressedBytes": raw_len}
                     for name, (raw_len, gz_len, sha) in sorted(results.items())},
    }
    with open(os.path.join(out, "meta.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")
    with open(os.path.join(out, "NOTICE"), "w", encoding="utf-8", newline="\n") as f:
        f.write(NOTICE.format(tag=args.tag, commit=args.commit, root=DATA_ROOT))

    total = 0
    for name, (raw_len, gz_len, sha) in sorted(results.items()):
        total += gz_len
        print("%-20s %9d bytes gz (%10d raw) sha256=%s" % (name, gz_len, raw_len, sha))
    print("total compressed: %d bytes; counts: %s" % (total, json.dumps(meta["counts"])))
    if unresolved:
        print("warning: unresolved presets: %s" % ", ".join(unresolved))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
