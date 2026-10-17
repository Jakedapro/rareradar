package dev.rareradar;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;

import java.util.HashSet;
import java.util.Set;

public enum Rarity {
    SHINY("Shiny", 0xFFD700),
    LEGENDARY("Legendary", 0xB44CFF),
    MYTHICAL("Mythical", 0xFF4FD8),
    ULTRA_BEAST("Ultra Beast", 0x33E0FF),
    PARADOX("Paradox", 0xFF6A2B);

    public final String label;
    public final int color;

    Rarity(String label, int color) {
        this.label = label;
        this.color = color;
    }

    /** Returns the rarest tier of a wild Pokemon entity, or null if it is not rare. */
    public static Rarity of(PokemonEntity entity) {
        Pokemon p = entity.getPokemon();
        if (p == null || entity.getOwnerUuid() != null) return null;
        Set<String> labels = new HashSet<>(p.getSpecies().getLabels());
        labels.addAll(p.getForm().getLabels());
        if (labels.contains("mythical")) return MYTHICAL;
        if (labels.contains("legendary")) return LEGENDARY;
        if (labels.contains("ultra_beast")) return ULTRA_BEAST;
        if (labels.contains("paradox")) return PARADOX;
        if (p.getShiny()) return SHINY;
        return null;
    }
}
