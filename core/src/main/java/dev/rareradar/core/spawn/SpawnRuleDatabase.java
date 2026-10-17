package dev.rareradar.core.spawn;

import com.google.gson.*;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Spawn conditions parsed from Cobblemon {@code spawn_pool_world/*.json} files that the user points RareRadar at
 * (e.g. extracted from their Cobblemon jar or a server datapack). Nothing is bundled, so no data is invented.
 */
public final class SpawnRuleDatabase {
    private final Map<String, List<SpawnRule>> bySpecies = new HashMap<>();

    public List<SpawnRule> forSpecies(String species) {
        return bySpecies.getOrDefault(PokemonObservation.norm(species), List.of());
    }

    public int size() { return bySpecies.values().stream().mapToInt(List::size).sum(); }

    /** Loads every *.json under {@code dir}. Returns {files read, files failed}. */
    public int[] loadDirectory(Path dir) {
        int ok = 0, bad = 0;
        try (Stream<Path> s = Files.walk(dir, 4)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".json"))::iterator) {
                try { parse(Files.readString(p), p.getFileName().toString()); ok++; }
                catch (RuntimeException | IOException e) { bad++; if (bad <= 3) Log.warn("Spawn file " + p + " skipped: " + e.getMessage()); }
            }
        } catch (IOException e) {
            Log.warn("Could not read spawn directory " + dir + ": " + e);
        }
        return new int[]{ok, bad};
    }

    /** Parses one spawn-pool file in Cobblemon's format ({"enabled":…, "spawns":[…]}). Returns rules added. */
    public int parse(String json, String source) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("enabled") && !root.get("enabled").getAsBoolean()) return 0;
        JsonArray spawns = root.getAsJsonArray("spawns");
        if (spawns == null) return 0;
        int n = 0;
        for (JsonElement el : spawns) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (o.has("type") && !"pokemon".equals(str(o, "type"))) continue;
            SpawnRule r = new SpawnRule();
            r.id = str(o, "id");
            String pk = str(o, "pokemon");
            if (pk == null) continue;
            r.species = PokemonObservation.norm(pk.split(" ")[0]);
            if (pk.contains(" ")) r.otherConditions.add("properties: " + pk.substring(pk.indexOf(' ') + 1));
            r.bucket = str(o, "bucket");
            r.context = str(o, "context");
            r.positionType = str(o, "spawnablePositionType");
            r.level = str(o, "level");
            r.weight = o.has("weight") ? o.get("weight").getAsDouble() : Double.NaN;
            r.source = source;
            if (o.has("presets")) for (JsonElement p : o.getAsJsonArray("presets")) r.presets.add(p.getAsString());
            if (o.has("condition")) readCondition(o.getAsJsonObject("condition"), r, false);
            if (o.has("anticondition")) readCondition(o.getAsJsonObject("anticondition"), r, true);
            bySpecies.computeIfAbsent(r.species, k -> new ArrayList<>()).add(r);
            n++;
        }
        return n;
    }

    private static void readCondition(JsonObject c, SpawnRule r, boolean anti) {
        for (var e : c.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (k.equals("biomes") && v.isJsonArray()) {
                for (JsonElement b : v.getAsJsonArray()) (anti ? r.excludedBiomes : r.biomes).add(b.getAsString());
            } else if (anti) {
                r.otherConditions.add("not " + k + "=" + v);
            } else switch (k) {
                case "timeRange" -> r.timeRange = v.getAsString();
                case "isRaining" -> r.raining = v.getAsBoolean();
                case "isThundering" -> r.thundering = v.getAsBoolean();
                case "canSeeSky" -> r.canSeeSky = v.getAsBoolean();
                case "minY" -> r.minY = v.getAsInt();
                case "maxY" -> r.maxY = v.getAsInt();
                case "minSkyLight" -> r.minSkyLight = v.getAsInt();
                case "maxSkyLight" -> r.maxSkyLight = v.getAsInt();
                default -> r.otherConditions.add(k + "=" + v);
            }
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }
}
