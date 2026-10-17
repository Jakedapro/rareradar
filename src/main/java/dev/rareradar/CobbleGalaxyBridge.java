package dev.rareradar;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.rareradar.core.dex.DexTier;
import dev.rareradar.core.hunt.HuntObjective;
import dev.rareradar.core.hunt.OverlayHuntParser;
import dev.rareradar.core.market.PriceObservation;
import dev.rareradar.core.pokebuilder.PokeBuilderCosts;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.util.Log;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Read-only bridge to the CobbleGalaxy client companion mod (see docs/COBBLEGALAXY_CLIENT_BRIDGE.md).
 * Reads data the server already sent to this client for display — pinned hunts, the GTS page being viewed,
 * PokéBuilder token costs, Dex Rewards tiers and Vote Party progress — purely through reflection, so RareRadar has no
 * dependency on CobbleGalaxy. It never calls CobbleGalaxy networking, never clicks or automates menus, and never
 * stores seller names. Each feature disables itself after its first reflection failure.
 */
public final class CobbleGalaxyBridge {
    private static final String P = "com.cobblegalaxy.client.";
    private static final Set<String> disabled = new HashSet<>();
    private static final Map<String, Integer> strikes = new HashMap<>();
    private static final Map<String, Method> methods = new HashMap<>();
    private static final Map<String, Field> fields = new HashMap<>();
    private static Boolean present;
    private static int ticks, lastHuntRevision = Integer.MIN_VALUE, lastGtsRevision = Integer.MIN_VALUE, lastDexHash, lastBuilderRevision = Integer.MIN_VALUE;
    private static Object lastGtsState, lastBuilderState;
    private static final Set<String> seenGtsKeys = new HashSet<>();
    private static boolean seenLoaded;

    /** Latest Vote Party progress seen in the Vote menu ({votes, target}), or null. */
    public static volatile int[] voteParty;
    /** GTS listing slots {used, max} seen in the Sell view, or null. */
    public static volatile int[] gtsSlots;
    public static volatile long lastHuntSync, lastGtsCapture, lastDexSync, lastBuilderSync;
    public static volatile int gtsCaptured;

    private CobbleGalaxyBridge() {}

    public static boolean present() {
        if (present == null) present = FabricLoader.getInstance().isModLoaded("cobblegalaxy");
        return present;
    }

    public static void tick(MinecraftClient client) {
        Galaxy g = Galaxy.get();
        if (!g.cfg.cobbleGalaxyBridge || !present() || client.player == null) return;
        ticks++;
        if (ticks % 20 == 0) guard("hunts", () -> syncHunts(g));
        Screen s = client.currentScreen;
        if (s == null || ticks % 5 != 0) return;
        String cn = s.getClass().getName();
        switch (cn) {
            case P + "gts.GTSScreen" -> guard("gts", () -> readGts(g, s));
            case P + "pokebuilder.PokeBuilderScreen" -> guard("pokebuilder", () -> readBuilder(g, s));
            case P + "dexrewards.DexRewardsScreen" -> guard("dexrewards", () -> readDex(g, s));
            case P + "voting.VoteScreen" -> guard("voting", () -> readVote(s));
            default -> {}
        }
    }

    private interface Job { void run() throws Exception; }

    private static void guard(String feature, Job j) {
        if (disabled.contains(feature)) return;
        try { j.run(); strikes.remove(feature); }
        catch (Throwable t) {
            // Screens can be briefly half-initialised; only give up after repeated consecutive failures.
            int n = strikes.merge(feature, 1, Integer::sum);
            if (n >= 5) {
                disabled.add(feature);
                Log.warn("CobbleGalaxy bridge: '" + feature + "' disabled after repeated errors (CobbleGalaxy client changed?): " + t);
            }
        }
    }

    public static Set<String> disabledFeatures() { return Collections.unmodifiableSet(disabled); }

    // ------------------------------------------------------------------ hunts

    private static void syncHunts(Galaxy g) throws Exception {
        Class<?> st = Class.forName(P + "hunts.HuntsOverlayState");
        int rev = (int) st.getMethod("revision").invoke(null);
        if (rev == lastHuntRevision) return;
        lastHuntRevision = rev;
        List<?> entries = (List<?>) st.getMethod("entries").invoke(null);
        long now = System.currentTimeMillis();
        List<HuntObjective> snapshot = new ArrayList<>();
        for (Object e : entries) {
            String group = text(call(e, "group"));
            String title = text(call(e, "title"));
            List<String> details = new ArrayList<>();
            for (Object d : (List<?>) call(e, "details")) details.add(text(d));
            Map.Entry<String, Set<String>> mon = CobblemonCompat.pokemonItem((ItemStack) call(e, "sprite"));
            long deadline = (long) call(e, "deadline");
            // CobbleGalaxy stores receive-time + remaining. Accept either wall-clock or monotonic time bases.
            long remaining = deadline > 1_000_000_000_000L ? deadline - now : deadline - Util.getMeasuringTimeMs();
            HuntObjective h = OverlayHuntParser.parse(group, title, mon == null ? null : mon.getKey(),
                    mon == null ? List.of() : new ArrayList<>(mon.getValue()), details, Math.max(0, remaining), now);
            if (h.species != null || !h.title.isBlank()) snapshot.add(h);
        }
        if (g.hunts.syncOverlay(snapshot, now)) Log.info("Synced " + snapshot.size() + " pinned hunt(s) from CobbleGalaxy");
        lastHuntSync = now;
    }

    // ------------------------------------------------------------------ GTS

    private static void readGts(Galaxy g, Screen s) throws Exception {
        if (!g.cfg.captureGtsListings) return;
        Object state = field(s, "state");
        if (state == null) return;
        int rev = (int) call(state, "getRevision");
        if (state == lastGtsState && rev == lastGtsRevision) return;
        lastGtsState = state;
        lastGtsRevision = rev;
        loadSeen(g);
        String view = String.valueOf(call(state, "getView"));
        Object sell = call(state, "getSell");
        if (sell != null) gtsSlots = new int[]{(int) call(sell, "getListingsUsed"), (int) call(sell, "getListingsMax")};
        boolean history = view.equals("HISTORY");
        if (!history && !view.equals("BROWSE") && !view.equals("LOOKUP")) return; // own listings / sell views are not market evidence
        Pattern sold = Pattern.compile(g.cfg.gtsSoldPattern);
        long now = System.currentTimeMillis();
        List<PriceObservation> add = new ArrayList<>();
        for (Object card : (List<?>) call(state, "getCards")) {
            Object mon = call(card, "getPokemon");
            if (mon == null) continue; // item listings are not tracked
            double price = ((Number) call(card, "getPrice")).doubleValue();
            if (!(price > 0)) continue;
            PriceObservation.Kind kind;
            if (history) {
                String status = text(call(card, "getStatusJson"));
                if (!sold.matcher(status).find()) continue;
                kind = PriceObservation.Kind.SALE;
            } else kind = PriceObservation.Kind.LISTING;
            String id = String.valueOf(call(card, "getId"));
            String key = "gts:" + kind + ":" + id + ":" + Math.round(price);
            if (seenGtsKeys.contains(key)) continue;
            PriceObservation p = new PriceObservation();
            String sp = String.valueOf(call(mon, "getSpecies"));
            p.species = PokemonObservation.norm(sp.contains(":") ? sp.substring(sp.indexOf(':') + 1) : sp);
            p.shiny = (boolean) call(mon, "getShiny");
            @SuppressWarnings("unchecked") List<Integer> ivs = (List<Integer>) call(mon, "getIvs");
            if (ivs != null && ivs.size() == 6) p.ivs = ivs.stream().mapToInt(Integer::intValue).toArray();
            p.nature = blankToNull(lastSegment(text(call(mon, "getNatureJson"))));
            p.ability = blankToNull(text(call(mon, "getAbilityJson")));
            @SuppressWarnings("unchecked") List<String> aspects = (List<String>) call(mon, "getAspects");
            p.form = regionalForm(aspects);
            p.kind = kind;
            p.price = price;
            p.timestamp = now;
            p.source = PriceObservation.Source.IMPORT;
            // The card carries no completion time, so a HISTORY sale is stamped when first seen (noted for the user).
            p.note = history ? key + " (history; sale time unknown)" : key;
            if (p.validate() == null) { add.add(p); seenGtsKeys.add(key); }
        }
        if (!add.isEmpty()) {
            g.market.importAll(add);
            gtsCaptured += add.size();
            lastGtsCapture = now;
            Log.info("Recorded " + add.size() + " GTS " + (history ? "sale(s)" : "listing(s)") + " from the page you viewed");
        }
    }

    private static void loadSeen(Galaxy g) {
        if (seenLoaded) return;
        seenLoaded = true;
        for (PriceObservation p : g.market.all())
            if (p.note != null && p.note.startsWith("gts:")) seenGtsKeys.add(p.note.replace(" (history; sale time unknown)", ""));
    }

    private static String regionalForm(List<String> aspects) {
        if (aspects == null) return null;
        for (String a : aspects) {
            String n = a.toLowerCase(Locale.ROOT);
            for (String r : new String[]{"alolan", "galarian", "hisuian", "paldean"}) if (n.contains(r)) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ PokéBuilder

    private static void readBuilder(Galaxy g, Screen s) throws Exception {
        Object st = field(s, "state");
        if (st == null) return;
        int rev = (int) call(st, "getRevision");
        if (st == lastBuilderState && rev == lastBuilderRevision) return;
        lastBuilderState = st;
        lastBuilderRevision = rev;
        PokeBuilderCosts c = new PokeBuilderCosts();
        c.observedAt = System.currentTimeMillis();
        c.source = "CobbleGalaxy PokéBuilder screen";
        String sp = String.valueOf(call(st, "getSpecies"));
        c.species = PokemonObservation.norm(sp.contains(":") ? sp.substring(sp.indexOf(':') + 1) : sp);
        c.balance = num(call(st, "getBalance"));
        c.costPerIv = num(call(st, "getCostPerIv"));
        c.costPerEv = num(call(st, "getCostPerEv"));
        c.ivMax = (int) call(st, "getIvMax");
        Object level = call(st, "getLevel");
        if (level != null) c.levelUpCost = num(call(level, "getUp1"));
        Object shiny = call(st, "getShiny");
        if (shiny != null) { c.shinyAvailable = (boolean) call(shiny, "getAvailable"); c.shinyCost = num(call(shiny, "getCost")); }
        choices(call(st, "getNatures"), c.natureCosts);
        choices(call(st, "getAbilities"), c.abilityCosts);
        choices(call(st, "getBalls"), c.ballCosts);
        g.pokeBuilderCosts = c;
        lastBuilderSync = c.observedAt;
    }

    private static void choices(Object list, Map<String, Double> into) throws Exception {
        if (!(list instanceof List<?> l)) return;
        for (Object ch : l) {
            String id = String.valueOf(call(ch, "getId")).toLowerCase(Locale.ROOT);
            into.put(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id, num(call(ch, "getCost")));
        }
    }

    // ------------------------------------------------------------------ Dex Rewards

    private static void readDex(Galaxy g, Screen s) throws Exception {
        Object state = field(s, "state");
        if (state == null) return;
        Map<?, ?> views = (Map<?, ?>) field(state, "views");
        Map<?, ?> rows = (Map<?, ?>) field(state, "speciesRows");
        if (views == null || rows == null) return;
        int hash = 31 * views.size();
        for (Object v : views.values()) hash = hash * 31 + (int) field(v, "caughtCount");
        if (hash == lastDexHash) return;
        lastDexHash = hash;
        // Use the largest view (normally the full National Dex) as the universe.
        Object best = null; int bestSize = -1; String bestKey = null;
        for (var e : views.entrySet()) {
            Object v = e.getValue();
            List<?> sp = (List<?>) rows.get(field(v, "listKey"));
            if (sp != null && sp.size() > bestSize) { best = v; bestSize = sp.size(); bestKey = String.valueOf(e.getKey()); }
        }
        if (best == null) return;
        List<?> species = (List<?>) rows.get(field(best, "listKey"));
        BitSet caught = (BitSet) field(best, "caught");
        // Build into temporaries and swap at the end so a failure never leaves a half-filled Pokédex.
        Set<String> universe = new TreeSet<>(), owned = new TreeSet<>();
        for (int i = 0; i < species.size(); i++) {
            String id = String.valueOf(call(species.get(i), "species"));
            id = PokemonObservation.norm(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id);
            universe.add(id);
            if (caught != null && caught.get(i)) owned.add(id);
        }
        List<DexTier> tiers = new ArrayList<>();
        for (var e : views.entrySet()) {
            for (Object t : (List<?>) field(e.getValue(), "tiers")) {
                tiers.add(new DexTier(String.valueOf(e.getKey()), String.valueOf(call(t, "id")), text(call(t, "name")),
                        num(call(t, "percentage")), (boolean) call(t, "claimed"), (boolean) call(t, "claimable")));
            }
        }
        g.dex.universe.clear(); g.dex.universe.addAll(universe);
        g.dex.caught.clear(); g.dex.caught.addAll(owned);
        g.dex.tiers.clear(); g.dex.tiers.addAll(tiers);
        g.dex.source = "CobbleGalaxy Dex Rewards (" + bestKey + ")";
        g.dex.updatedAt = System.currentTimeMillis();
        lastDexSync = g.dex.updatedAt;
    }

    // ------------------------------------------------------------------ Voting

    private static void readVote(Screen s) throws Exception {
        Object msg = field(s, "msg");
        if (msg == null) return;
        voteParty = new int[]{(int) call(msg, "getPartyVotes"), (int) call(msg, "getPartyTarget")};
    }

    // ------------------------------------------------------------------ reflection helpers

    private static Object call(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        String k = c.getName() + "#" + name;
        Method m = methods.get(k);
        if (m == null) {
            m = c.getMethod(name);
            m.setAccessible(true);
            methods.put(k, m);
        }
        return m.invoke(target);
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        String k = c.getName() + "." + name;
        Field f = fields.get(k);
        if (f == null) {
            f = c.getDeclaredField(name);
            f.setAccessible(true);
            fields.put(k, f);
        }
        return f.get(target);
    }

    private static double num(Object o) { return o instanceof Number n ? n.doubleValue() : Double.NaN; }

    /** Plain text from a Minecraft Text or a serialized text-component JSON string. */
    static String text(Object o) {
        if (o == null) return "";
        if (o instanceof Text t) return t.getString();
        String s = o.toString();
        String trimmed = s.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\""))) return s;
        try {
            StringBuilder sb = new StringBuilder();
            collect(JsonParser.parseString(trimmed), sb);
            return sb.toString();
        } catch (RuntimeException e) {
            return s;
        }
    }

    private static void collect(JsonElement e, StringBuilder sb) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonPrimitive()) { sb.append(e.getAsString()); return; }
        if (e.isJsonArray()) { for (JsonElement c : e.getAsJsonArray()) collect(c, sb); return; }
        var o = e.getAsJsonObject();
        if (o.has("text")) sb.append(o.get("text").getAsString());
        else if (o.has("translate")) sb.append(o.get("translate").getAsString());
        if (o.has("extra")) collect(o.get("extra"), sb);
    }

    private static String lastSegment(String s) {
        if (s == null) return null;
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf(':'));
        return (i >= 0 ? s.substring(i + 1) : s).trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
