package dev.rareradar.core.knowledge;

import java.util.List;

/**
 * A form as written in its species file. Every field other than {@link #name()} and {@link #aspects()} is an
 * <em>override</em>: null means the form file does not set it and Cobblemon falls back to the species value
 * (see {@link Species#baseStatsFor(String)} and friends). {@link #evolutions()} null means "not overridden";
 * Cobblemon then gives the form no evolutions of its own (FormData.kt).
 */
public record Form(String name, List<String> aspects, String primaryType, String secondaryType, boolean setsTypes,
                   Stats baseStats, Stats evYield, List<String> abilities, Integer catchRate,
                   Integer baseExperienceYield, String experienceGroup, List<String> eggGroups, Double maleRatio,
                   List<String> labels, List<String> moves, String preEvolution, Boolean battleOnly,
                   List<Evolution> evolutions) {
}
