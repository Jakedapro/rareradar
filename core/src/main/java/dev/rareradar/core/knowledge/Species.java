package dev.rareradar.core.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A species record from {@code species.json.gz}. Nullable boxed fields are null when the Cobblemon data file
 * omits the value (never defaulted here). {@code implemented == null} means the file has no {@code implemented}
 * key; Cobblemon's own default for that is {@code false}.
 */
public record Species(String id, int dex, String name, Boolean implemented, String primaryType, String secondaryType,
                      Stats baseStats, List<String> abilities, Integer catchRate, Integer baseExperienceYield,
                      String experienceGroup, Stats evYield, List<String> eggGroups, Double maleRatio,
                      Set<String> labels, List<String> aspects, String preEvolution, List<Evolution> evolutions,
                      List<String> moves, List<Form> forms, String sourceFile, String confidence) {

    public boolean hasLabel(String label) { return labels.contains(label); }
    public boolean isLegendary() { return labels.contains("legendary"); }
    public boolean isMythical() { return labels.contains("mythical"); }
    public boolean isUltraBeast() { return labels.contains("ultra_beast"); }
    public boolean isParadox() { return labels.contains("paradox"); }

    /** Abilities marked hidden in the data ({@code "h:"} prefix), without the prefix. */
    public List<String> hiddenAbilities() {
        List<String> out = new ArrayList<>(1);
        for (String a : abilities) if (a.startsWith("h:")) out.add(a.substring(2));
        return out;
    }

    /** Form by name (case- and punctuation-insensitive) or by one of its aspects; null if none. */
    public Form form(String nameOrAspect) {
        if (nameOrAspect == null) return null;
        String k = KnowledgeBase.normKey(nameOrAspect);
        for (Form f : forms) if (KnowledgeBase.normKey(f.name()).equals(k)) return f;
        for (Form f : forms) for (String a : f.aspects()) if (KnowledgeBase.normKey(a).equals(k)) return f;
        return null;
    }

    public Stats baseStatsFor(String form) {
        Form f = form(form);
        return f != null && f.baseStats() != null ? f.baseStats() : baseStats;
    }

    public Stats evYieldFor(String form) {
        Form f = form(form);
        return f != null && f.evYield() != null ? f.evYield() : evYield;
    }

    public Integer catchRateFor(String form) {
        Form f = form(form);
        return f != null && f.catchRate() != null ? f.catchRate() : catchRate;
    }

    public List<String> labelsFor(String form) {
        Form f = form(form);
        return f != null && f.labels() != null ? f.labels() : List.copyOf(labels);
    }

    /** {primary, secondary}; secondary may be null. Mirrors FormData.kt: species types only if the form sets neither. */
    public String[] typesFor(String form) {
        Form f = form(form);
        if (f != null && f.setsTypes()) {
            return new String[]{f.primaryType() != null ? f.primaryType() : primaryType, f.secondaryType()};
        }
        return new String[]{primaryType, secondaryType};
    }
}
