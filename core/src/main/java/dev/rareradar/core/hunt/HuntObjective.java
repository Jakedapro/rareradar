package dev.rareradar.core.hunt;

import dev.rareradar.core.rarity.PokemonObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One /hunt objective. Reward amounts are nullable: CobbleGalaxy does not document per-tier rewards, so they are
 * only known when the user enters them or the overlay text states them.
 */
public final class HuntObjective {
    public enum Source { MANUAL, OVERLAY }

    public String id = UUID.randomUUID().toString();
    public Source source = Source.MANUAL;
    public String overlayKey;          // stable key for overlay-imported hunts (group|title|species)
    public String group = "";          // hunt category as shown by the server
    public String title = "";
    public int stars;                  // 1..3 when known, 0 unknown
    public String species;             // required species id (null when the hunt is not species-specific)
    public List<String> aspects = new ArrayList<>(); // e.g. "shiny"
    public List<String> details = new ArrayList<>();
    public int required = 1;
    public int progress;
    public Double rewardHuntPoints;    // null = unknown
    public Double rewardMoney;         // null = unknown
    public Double rewardOtherValue;    // user-estimated value of item rewards; null = unknown
    public long createdAt;
    public Long deadline;              // epoch millis, null = none/unknown
    public Long completedAt;
    public boolean abandoned;
    public long lastSeen;              // overlay hunts: last time the server's overlay listed it

    /** Overlay hunts that vanished from the overlay without a deadline are considered stale after this long. */
    public static final long OVERLAY_STALE_MS = 36L * 3600_000L;

    public static HuntObjective manual(String title, String species, int required, long now) {
        HuntObjective h = new HuntObjective();
        h.title = title == null ? "" : title;
        h.species = PokemonObservation.norm(species);
        h.required = Math.max(1, required);
        h.createdAt = now;
        return h;
    }

    public int remaining() { return Math.max(0, required - progress); }
    public boolean active(long now) {
        if (completedAt != null || abandoned || (deadline != null && deadline <= now)) return false;
        return source != Source.OVERLAY || lastSeen <= 0 || now - lastSeen < OVERLAY_STALE_MS;
    }
    public boolean requiresShiny() { return aspects.stream().anyMatch(a -> a.equalsIgnoreCase("shiny")); }

    /** Whether a seen Pokémon counts toward this hunt (species + required aspects such as shiny). */
    public boolean matches(PokemonObservation o) {
        if (species == null || o == null || !species.equals(o.species)) return false;
        if (requiresShiny() && !o.isShiny()) return false;
        Set<String> formAspects = Set.of("alolan", "galarian", "hisuian", "paldean");
        for (String a : aspects) {
            String n = PokemonObservation.norm(a);
            if (formAspects.contains(n) && (o.form == null || !o.form.contains(n))) return false;
        }
        return true;
    }

    public String validate() {
        if (required < 1 || required > 10_000) return "required count must be 1-10000";
        if (progress < 0) return "progress cannot be negative";
        for (Double d : new Double[]{rewardHuntPoints, rewardMoney, rewardOtherValue})
            if (d != null && (!Double.isFinite(d) || d < 0)) return "rewards must be non-negative";
        if (stars < 0 || stars > 3) return "stars must be 0-3";
        return null;
    }
}
