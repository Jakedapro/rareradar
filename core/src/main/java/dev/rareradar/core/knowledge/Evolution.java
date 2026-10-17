package dev.rareradar.core.knowledge;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * One evolution entry from a species (or form) file. Requirements are kept as the raw JSON text from the data
 * so no requirement type is lost; {@link #requirementVariants()} lists their {@code variant}s for quick filtering.
 *
 * @param fromSpeciesId   species declaring the evolution, e.g. {@code cobblemon:bulbasaur}
 * @param fromForm        form name when declared on a form, else null
 * @param result          raw result string, e.g. {@code "raichu alolan"}
 * @param resultSpeciesId species id of the result's first token, e.g. {@code cobblemon:raichu}
 * @param requirementsJson raw {@code requirements} array, null when the file omits it
 */
public record Evolution(String fromSpeciesId, String fromForm, String id, String variant, String result,
                        String resultSpeciesId, Boolean consumeHeldItem, String requirementsJson,
                        String requiredContextJson, List<String> requirementVariants) {

    /** Parses {@link #requirementsJson()} on demand; null when absent. */
    public JsonElement requirements() {
        return requirementsJson == null ? null : JsonParser.parseString(requirementsJson);
    }

    /** The result's aspect/property tokens after the species name (e.g. {@code "alolan"}), or "" if none. */
    public String resultProperties() {
        int sp = result == null ? -1 : result.indexOf(' ');
        return sp < 0 ? "" : result.substring(sp + 1).trim();
    }
}
