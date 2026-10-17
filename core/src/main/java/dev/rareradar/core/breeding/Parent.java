package dev.rareradar.core.breeding;

import java.util.List;

/** A breeding parent. Gender: "male", "female", "genderless" or null when unknown. */
public record Parent(String species, int[] ivs, String nature, String gender, List<String> eggGroups, HeldItem item) {
    public Parent {
        if (ivs == null || ivs.length != 6) throw new IllegalArgumentException("parent needs 6 IVs");
        for (int v : ivs) if (v < 0 || v > 31) throw new IllegalArgumentException("IVs must be 0-31");
        if (item == null) item = HeldItem.NONE;
        eggGroups = eggGroups == null ? List.of() : eggGroups;
    }

    public boolean isDitto() { return "ditto".equalsIgnoreCase(species); }
}
