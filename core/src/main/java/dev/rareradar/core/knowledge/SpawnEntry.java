package dev.rareradar.core.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * One entry of a Cobblemon {@code spawn_pool_world} file. Weights are the raw Cobblemon weights
 * (relative within a bucket), never probabilities. Condition objects are kept as raw JSON text.
 *
 * @param type          {@code pokemon}, {@code pokemon-herd} or {@code empty}
 * @param pokemon       raw species + properties string, null for herds/empty entries
 * @param speciesId     {@code cobblemon:<first token of pokemon>}, null for herds/empty entries
 * @param herdSpeciesIds species of a herd's {@code herdablePokemon}, empty otherwise
 * @param biomes        {@code condition.biomes} (ids or {@code #tag} refs), empty if unrestricted
 * @param excludedBiomes {@code anticondition.biomes}
 * @param presets       raw preset names; definitions via {@link KnowledgeBase#preset(String)}
 * @param poolEnabled   file-level {@code enabled}; null when omitted (Cobblemon default: true)
 */
public record SpawnEntry(String id, String type, String pokemon, String speciesId, String properties,
                         List<String> herdSpeciesIds, Integer maxHerdSize, String bucket, String level,
                         String levelRange, Double weight, Double percentage, String weightMultiplierJson,
                         String weightMultipliersJson, String positionType, String conditionJson,
                         String anticonditionJson, List<String> biomes, List<String> excludedBiomes,
                         List<String> presets, String herdablePokemonJson, Boolean poolEnabled,
                         List<String> neededInstalledMods, List<String> neededUninstalledMods,
                         String sourceFile, String confidence) {

    public boolean involves(String speciesIdOrNull) {
        return speciesIdOrNull != null && (speciesIdOrNull.equals(speciesId) || herdSpeciesIds.contains(speciesIdOrNull));
    }

    public JsonElement condition() { return conditionJson == null ? null : JsonParser.parseString(conditionJson); }
    public JsonElement anticondition() { return anticonditionJson == null ? null : JsonParser.parseString(anticonditionJson); }
}
