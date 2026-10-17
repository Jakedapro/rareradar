package dev.rareradar;

import com.cobblemon.mod.common.CobblemonItemComponents;
import com.cobblemon.mod.common.api.pokedex.PokedexEntryProgress;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.cobblemon.mod.common.entity.pokeball.EmptyPokeBallEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.PokeBallItem;
import com.cobblemon.mod.common.item.components.PokemonItemComponent;
import com.cobblemon.mod.common.pokemon.Pokemon;
import dev.rareradar.core.autohunt.CaptureClassifier;
import dev.rareradar.core.breeding.HeldItem;
import dev.rareradar.core.breeding.Parent;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Log;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Every Cobblemon API call RareRadar makes, in one place (verified against Cobblemon 1.8.2 sources). Each method
 * catches {@link LinkageError}/{@link RuntimeException} so an API change in another Cobblemon version disables the
 * affected feature with one log line instead of crashing the game.
 */
public final class CobblemonCompat {
    private static final Set<String> warned = new HashSet<>();
    private static final Stats[] STAT_ORDER = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};

    private CobblemonCompat() {}

    static void warnOnce(String what, Throwable t) {
        if (warned.add(what)) Log.warn("Cobblemon API problem in " + what + " (feature disabled for this session): " + t);
    }

    /** Client-visible facts about a wild/visible Pokémon entity. Hidden stats are left null on purpose. */
    public static PokemonObservation observe(PokemonEntity e) {
        try {
            Pokemon p = e.getPokemon();
            if (p == null) return null;
            PokemonObservation o = PokemonObservation.of(p.getSpecies().getResourceIdentifier().getPath());
            String form = p.getForm().getName();
            o.form = form == null || form.equalsIgnoreCase("normal") || form.equalsIgnoreCase("base") ? null : PokemonObservation.norm(form);
            o.shiny = p.getShiny();
            Set<String> labels = new HashSet<>(p.getSpecies().getLabels());
            labels.addAll(p.getForm().getLabels());
            o.labels = labels;
            o.level = p.getLevel();
            o.owned = e.getOwnerUuid() != null;
            o.entityKey = e.getId();
            return o;
        } catch (LinkageError | RuntimeException t) {
            warnOnce("observe", t);
            return null;
        }
    }

    /** Full details for one of the player's own Pokémon (party), including IVs, nature and ability. */
    public static PokemonObservation observeOwn(Pokemon p) {
        try {
            PokemonObservation o = PokemonObservation.of(p.getSpecies().getResourceIdentifier().getPath());
            String form = p.getForm().getName();
            o.form = form == null || form.equalsIgnoreCase("normal") ? null : PokemonObservation.norm(form);
            o.shiny = p.getShiny();
            o.labels = new HashSet<>(p.getSpecies().getLabels());
            o.level = p.getLevel();
            o.owned = true;
            o.nature = p.getNature().getName().getPath();
            o.ability = p.getAbility().getName();
            o.gender = p.getGender().name().toLowerCase(Locale.ROOT);
            int[] ivs = new int[6];
            for (int i = 0; i < 6; i++) ivs[i] = p.getIvs().getOrDefault(STAT_ORDER[i]);
            o.ivs = ivs;
            return o;
        } catch (LinkageError | RuntimeException t) {
            warnOnce("observeOwn", t);
            return null;
        }
    }

    /** Party members as breeding parents (held items are not read; the breeding screen lets the user set them). */
    public static List<Parent> partyParents() {
        List<Parent> out = new ArrayList<>();
        for (Pokemon p : party()) {
            PokemonObservation o = observeOwn(p);
            if (o == null) continue;
            List<String> groups = new ArrayList<>();
            try { for (var g : p.getSpecies().getEggGroups()) groups.add(g.name().toLowerCase(Locale.ROOT)); }
            catch (LinkageError | RuntimeException t) { warnOnce("eggGroups", t); }
            out.add(new Parent(o.species, o.ivs, o.nature, o.gender, groups, HeldItem.NONE));
        }
        return out;
    }

    public static List<Pokemon> party() {
        try {
            List<Pokemon> l = new ArrayList<>();
            for (Pokemon p : CobblemonClient.INSTANCE.getStorage().getParty().getSlots()) if (p != null) l.add(p);
            return l;
        } catch (LinkageError | RuntimeException t) {
            warnOnce("party", t);
            return List.of();
        }
    }

    /** TRUE if the client Pokédex says owned, FALSE if not, null when unknown. */
    public static Boolean dexOwned(String species) {
        try {
            var k = CobblemonClient.INSTANCE.getClientPokedexData().getKnowledgeForSpecies(Identifier.of("cobblemon", species));
            return k == PokedexEntryProgress.OWNED;
        } catch (LinkageError | RuntimeException t) {
            warnOnce("pokedex", t);
            return null;
        }
    }

    /** Species ids the client Pokédex lists as owned (used to seed the Pokédex planner). */
    public static Set<String> dexOwnedSpecies() {
        Set<String> out = new TreeSet<>();
        try {
            for (var e : CobblemonClient.INSTANCE.getClientPokedexData().getSpeciesRecords().entrySet())
                if (e.getValue().getKnowledge() == PokedexEntryProgress.OWNED) out.add(e.getKey().getPath());
        } catch (LinkageError | RuntimeException t) {
            warnOnce("pokedexRecords", t);
        }
        return out;
    }

    /** Paths of all species Cobblemon marks as implemented (the client receives species data from the server). */
    public static Set<String> implementedSpecies() {
        Set<String> out = new TreeSet<>();
        try {
            for (var sp : com.cobblemon.mod.common.api.pokemon.PokemonSpecies.getImplemented()) out.add(sp.getResourceIdentifier().getPath());
        } catch (LinkageError | RuntimeException t) {
            warnOnce("implementedSpecies", t);
        }
        return out;
    }

    /** Ball id ("poke_ball", "master_ball", …) of a stack, or null if it is not a Poké Ball. */
    public static String ballId(ItemStack s) {
        try {
            if (s != null && !s.isEmpty() && s.getItem() instanceof PokeBallItem ball) return ball.getPokeBall().getName().getPath();
        } catch (LinkageError | RuntimeException t) {
            warnOnce("ballId", t);
        }
        return null;
    }

    /** Species path and aspects from a Cobblemon PokemonItem stack (used by CobbleGalaxy's hunt sprites). */
    public static Map.Entry<String, Set<String>> pokemonItem(ItemStack s) {
        try {
            if (s == null || s.isEmpty()) return null;
            PokemonItemComponent c = s.get(CobblemonItemComponents.POKEMON_ITEM);
            if (c == null) return null;
            return Map.entry(c.getSpecies().getPath(), c.getAspects());
        } catch (LinkageError | RuntimeException t) {
            warnOnce("pokemonItem", t);
            return null;
        }
    }

    /** Synced capture state of a thrown ball. */
    public static CaptureClassifier.BallState ballState(EmptyPokeBallEntity ball) {
        try {
            return CaptureClassifier.BallState.valueOf(ball.getCaptureState().name());
        } catch (LinkageError | RuntimeException t) {
            warnOnce("captureState", t);
            return null;
        }
    }

    public static boolean isBattling(PokemonEntity e) {
        try { return e.isBattling(); } catch (LinkageError | RuntimeException t) { warnOnce("isBattling", t); return false; }
    }
}
