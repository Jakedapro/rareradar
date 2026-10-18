package dev.rareradar;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import dev.rareradar.core.market.CaptureStats;
import dev.rareradar.core.market.GtsTextParser;
import dev.rareradar.core.market.PriceObservation;
import dev.rareradar.core.util.Log;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Passive GTS price capture from text the client already displays. Two paths besides the CobbleGalaxy custom screen
 * (handled in {@link CobbleGalaxyBridge}):
 * <ul>
 *   <li><b>Server chat announcements</b> — system ("game") messages only, never player chat, so other players cannot
 *       inject fake prices by typing them;</li>
 *   <li><b>Chest-style /gts menus</b> — any container screen whose title mentions GTS; the item names and lore the
 *       menu shows are parsed. Nothing is clicked.</li>
 * </ul>
 * Every path keeps counters (detected/parsed/rejected/saved) that are logged and shown on the dashboard, and the
 * first unparsed lines are written to data/gts_samples.txt so the parser can be improved from real CobbleGalaxy text.
 */
public final class GtsCapture {
    public static final CaptureStats CHAT = new CaptureStats("GTS chat announcements");
    public static final CaptureStats MENU = new CaptureStats("GTS chest menu");
    private static final Set<String> seenKeys = new HashSet<>();
    private static final Set<String> screensLogged = new HashSet<>();
    private static int samplesWritten, ticks;
    private static int lastMenuSignature;
    private static boolean seenLoaded;

    private GtsCapture() {}

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return; // action-bar text, not announcements
            String text = message.getString();
            MinecraftClient.getInstance().execute(() -> onSystemMessage(text));
        });
    }

    private static boolean isSpecies(String id) {
        try { return PokemonSpecies.getByName(id) != null; }
        catch (LinkageError | RuntimeException e) { CobblemonCompat.warnOnce("speciesLookup", e); return false; }
    }

    static void onSystemMessage(String text) {
        if (MoneyCapture.onSystemMessage(text)) return; // a labelled money message (STS payout, own sale, …)
        Galaxy g = Galaxy.get();
        if (g == null || !g.cfg.captureGtsChat) return;
        GtsTextParser.Result r = GtsTextParser.parseAnnouncement(text, GtsCapture::isSpecies);
        if (r.status() == GtsTextParser.Status.IGNORED) return;
        CHAT.detected++;
        if (r.status() == GtsTextParser.Status.REJECTED) {
            CHAT.reject(r.reason(), text);
            sample("chat", r.reason(), text);
            Log.info("GTS chat line not used (" + r.reason() + "): " + text);
            return;
        }
        CHAT.parsed++;
        // Announcements carry no listing id: identical subject+price+kind within the session counts once.
        save(g, CHAT, r, "chat:" + r.kind() + ":" + r.subject() + ":" + Math.round(r.price()) + ":" + (System.currentTimeMillis() / 600_000), "GTS chat");
    }

    /** Called every client tick; reads chest-style GTS menus at most twice a second and only when they change. */
    public static void tick(MinecraftClient client) {
        Galaxy g = Galaxy.get();
        if (g == null || client.player == null) return;
        Screen s = client.currentScreen;
        if (s == null) { lastMenuSignature = 0; return; }
        logScreenOnce(s);
        if (!g.cfg.captureGtsChestMenu || !(s instanceof HandledScreen<?> hs) || ++ticks % 10 != 0) return;
        String title = s.getTitle().getString();
        if (!title.toLowerCase(Locale.ROOT).contains("gts")) return;
        if (title.toLowerCase(Locale.ROOT).matches(".*\\b(history|sold|log)\\b.*")) return; // never treat history pages as listings
        List<Slot> slots = new ArrayList<>();
        int sig = title.hashCode();
        for (Slot slot : hs.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue; // only the menu, not the player's own items
            ItemStack st = slot.getStack();
            if (st.isEmpty()) continue;
            slots.add(slot);
            sig = sig * 31 + st.getName().getString().hashCode() * 7 + loreOf(st).hashCode();
        }
        if (sig == lastMenuSignature) return;
        lastMenuSignature = sig;
        int before = (int) MENU.saved;
        for (Slot slot : slots) readMenuEntry(g, title, slot.getStack());
        if (MENU.saved > before || MENU.changedSinceLastLog()) Log.info("[GTS capture] " + MENU.summary());
    }

    private static void readMenuEntry(Galaxy g, String title, ItemStack st) {
        String name = st.getName().getString();
        List<String> lore = loreOf(st);
        if (lore.isEmpty()) return; // decorative panes / buttons
        Map.Entry<String, Set<String>> mon = CobblemonCompat.pokemonItem(st);
        MENU.detected++;
        GtsTextParser.Result r = GtsTextParser.parseMenuEntry(name, lore, mon == null ? null : mon.getKey(), GtsCapture::isSpecies);
        if (r.status() != GtsTextParser.Status.PARSED) {
            MENU.reject(r.reason(), name + " | " + String.join(" / ", lore));
            sample("menu:" + title, r.reason(), name + " | " + String.join(" / ", lore));
            return;
        }
        MENU.parsed++;
        save(g, MENU, r, "menu:" + r.subject() + ":" + Math.round(r.price()) + ":" + String.join("/", lore).hashCode(), "GTS menu");
    }

    private static List<String> loreOf(ItemStack st) {
        List<String> out = new ArrayList<>();
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore != null) for (Text t : lore.lines()) out.add(t.getString());
        return out;
    }

    private static void save(Galaxy g, CaptureStats stats, GtsTextParser.Result r, String key, String source) {
        loadSeen(g);
        if (!seenKeys.add(key)) { stats.duplicates++; return; }
        PriceObservation p = new PriceObservation();
        p.species = r.subject();
        p.shiny = r.shiny();
        p.ivs = r.ivs();
        p.kind = r.kind();
        p.price = r.price();
        p.timestamp = System.currentTimeMillis();
        p.source = PriceObservation.Source.IMPORT;
        p.note = key + " (" + source + ")";
        String err = p.validate();
        if (err != null) { seenKeys.remove(key); stats.reject("invalid: " + err, key); return; }
        g.market.add(p);
        TradeAssistant.onNewListing(p);
        stats.saved++;
        Log.info("[GTS capture] saved " + p.kind + " " + p.species + (Boolean.TRUE.equals(p.shiny) ? " (shiny)" : "") + " $" + Math.round(p.price) + " from " + source);
    }

    private static void loadSeen(Galaxy g) {
        if (seenLoaded) return;
        seenLoaded = true;
        for (PriceObservation p : g.market.all()) if (p.note != null) {
            int i = p.note.indexOf(" (");
            seenKeys.add(i > 0 ? p.note.substring(0, i) : p.note);
        }
    }

    /** Logs the class and title of GTS-looking screens once, so it is clear which capture path applies. */
    private static void logScreenOnce(Screen s) {
        String cls = s.getClass().getName();
        String title = s.getTitle() == null ? "" : s.getTitle().getString();
        boolean gtsLike = cls.toLowerCase(Locale.ROOT).contains("gts") || title.toLowerCase(Locale.ROOT).contains("gts");
        if (!gtsLike || !screensLogged.add(cls + "|" + title)) return;
        String path = cls.equals("com.cobblegalaxy.client.gts.GTSScreen") ? "CobbleGalaxy custom screen (bridge)"
                : s instanceof HandledScreen<?> ? "chest-style menu (lore parser)" : "unsupported screen type";
        Log.info("[GTS capture] GTS screen opened: class=" + cls + ", title=\"" + title + "\" → " + path);
    }

    private static void sample(String where, String reason, String text) {
        if (samplesWritten >= 200) return;
        samplesWritten++;
        Path f = Galaxy.get().dataDir.resolve("gts_samples.txt");
        String line = java.time.Instant.now() + "\t" + where + "\t" + reason + "\t" + text.replace('\n', ' ') + System.lineSeparator();
        Galaxy.get().io.submit(() -> {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                Log.warn("Could not write " + f + ": " + e);
            }
        });
    }

    /** Periodic one-line summary (every 5 minutes, only when something changed). */
    public static void logSummaries() {
        if (CHAT.changedSinceLastLog()) Log.info("[GTS capture] " + CHAT.summary());
        if (MENU.changedSinceLastLog()) Log.info("[GTS capture] " + MENU.summary());
        if (CobbleGalaxyBridge.GUI.changedSinceLastLog()) Log.info("[GTS capture] " + CobbleGalaxyBridge.GUI.summary());
    }
}
