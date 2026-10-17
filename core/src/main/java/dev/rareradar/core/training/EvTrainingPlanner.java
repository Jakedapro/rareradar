package dev.rareradar.core.training;

import dev.rareradar.core.knowledge.KnowledgeBase;
import dev.rareradar.core.knowledge.Species;
import dev.rareradar.core.knowledge.Stats;

import java.util.*;

/**
 * EV-training planner: ranks species by EVs-per-hour for one stat, using verified Cobblemon 1.8.2 EV yields (knowledge
 * base) and the user's own observed encounter rates. Assumes each defeated Pokémon grants its listed EV yield
 * (standard rule; EV-boosting items and modifiers are not modelled) and caps progress at 252 per stat / 510 total.
 */
public final class EvTrainingPlanner {
    public enum Stat { HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED }

    public record Candidate(String speciesId, String name, int evPerKo, double encountersPerHour, double evsPerHour) {}

    public record Plan(Stat stat, int current, int target, int remaining, List<Candidate> candidates, double hoursForBest, List<String> assumptions) {}

    public static final int MAX_PER_STAT = 252, MAX_TOTAL = 510;

    private EvTrainingPlanner() {}

    public static int evYieldFor(Stats s, Stat stat) {
        if (s == null) return 0;
        return switch (stat) {
            case HP -> s.hp(); case ATTACK -> s.attack(); case DEFENCE -> s.defence();
            case SPECIAL_ATTACK -> s.specialAttack(); case SPECIAL_DEFENCE -> s.specialDefence(); case SPEED -> s.speed();
        };
    }

    /**
     * @param encounterRates species id (or name) → observed encounters per hour where the user trains
     * @param totalOtherEvs  EVs already invested in other stats (for the 510 cap)
     */
    public static Plan plan(KnowledgeBase kb, Stat stat, int current, int target, int totalOtherEvs, Map<String, Double> encounterRates) {
        int tgt = Math.min(MAX_PER_STAT, Math.max(0, target));
        int room = Math.max(0, MAX_TOTAL - totalOtherEvs - current);
        int remaining = Math.max(0, Math.min(tgt - current, room));
        List<Candidate> out = new ArrayList<>();
        for (var e : encounterRates.entrySet()) {
            Species sp = kb.species(e.getKey());
            if (sp == null || e.getValue() == null || !(e.getValue() > 0)) continue;
            int ev = evYieldFor(sp.evYield(), stat);
            if (ev <= 0) continue;
            out.add(new Candidate(sp.id(), sp.name(), ev, e.getValue(), ev * e.getValue()));
        }
        out.sort(Comparator.comparingDouble(Candidate::evsPerHour).reversed());
        double hours = out.isEmpty() || remaining == 0 ? (remaining == 0 ? 0 : Double.NaN) : remaining / out.get(0).evsPerHour();
        List<String> assumptions = List.of(
                "EV yields from Cobblemon 1.8.2 species data (servers may change them)",
                "every encounter of the species is defeated; EV items, boosts and battle time are not modelled",
                "encounter rates are your own observations in the logged areas");
        return new Plan(stat, current, tgt, remaining, out, hours, assumptions);
    }
}
