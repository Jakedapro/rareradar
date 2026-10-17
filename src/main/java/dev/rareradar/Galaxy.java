package dev.rareradar;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.rareradar.core.dex.DexPlanner;
import dev.rareradar.core.hunt.HuntTracker;
import dev.rareradar.core.market.MarketDatabase;
import dev.rareradar.core.market.OpportunityFinder;
import dev.rareradar.core.market.Valuator;
import dev.rareradar.core.mega.BossEncounter;
import dev.rareradar.core.ml.PriceModel;
import dev.rareradar.core.pokebuilder.PokeBuilderCosts;
import dev.rareradar.core.profit.ActivityLog;
import dev.rareradar.core.rarity.RarityEngine;
import dev.rareradar.core.rarity.Watchlist;
import dev.rareradar.core.spawn.EncounterLog;
import dev.rareradar.core.spawn.ExposureLedger;
import dev.rareradar.core.spawn.SpawnRuleDatabase;
import dev.rareradar.core.stats.SessionTracker;
import dev.rareradar.core.store.AsyncWriter;
import dev.rareradar.core.store.JsonlStore;
import dev.rareradar.core.util.Log;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Owns every V3 service and its persistence. Created once at client start; all methods are for the client thread
 * except where noted. Disk writes go through {@link #io}; nothing here blocks the render thread on I/O after load.
 */
public final class Galaxy {
    private static Galaxy instance;

    public final Path dir, dataDir;
    public final AsyncWriter io = AsyncWriter.background(4096);
    public V3Config cfg;
    public final Watchlist watchlist = new Watchlist();
    public final MarketDatabase market;
    public final ActivityLog activities;
    public final HuntTracker hunts;
    public final EncounterLog encounters;
    public final ExposureLedger exposure;
    public final SessionTracker sessions;
    public final JsonlStore<BossEncounter> bossStore;
    public final List<BossEncounter> bosses;
    public final SpawnRuleDatabase spawnRules = new SpawnRuleDatabase();
    public final DexPlanner dex = new DexPlanner();
    public volatile PokeBuilderCosts pokeBuilderCosts;
    public final Valuator valuator;
    public final OpportunityFinder opportunities;
    public final RarityEngine rarity;
    private volatile PriceModel model;
    public final dev.rareradar.core.automation.ServerProfiles profiles;
    public final dev.rareradar.core.automation.AutomationController controller;
    public final dev.rareradar.core.automation.TaskStore tasks;
    public final dev.rareradar.core.automation.StrategyEvaluator strategies = new dev.rareradar.core.automation.StrategyEvaluator();
    private final JsonlStore<dev.rareradar.core.automation.AuditEvent> auditStore;
    private volatile boolean training;

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    public static Galaxy get() { return instance; }

    public static Galaxy init() {
        if (instance == null) instance = new Galaxy();
        return instance;
    }

    private Galaxy() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("rareradar");
        dataDir = dir.resolve("data");
        cfg = loadConfig();
        loadWatchlist();
        market = new MarketDatabase(dataDir.resolve("market.jsonl"), io);
        activities = new ActivityLog(dataDir.resolve("activities.jsonl"), io);
        hunts = new HuntTracker(dataDir.resolve("hunts.jsonl"), io);
        encounters = new EncounterLog(dataDir.resolve("encounters.jsonl"), io);
        exposure = new ExposureLedger(dataDir.resolve("exposure.json"), io);
        sessions = new SessionTracker(dataDir.resolve("sessions.jsonl"), io);
        bossStore = new JsonlStore<>(dataDir.resolve("bosses.jsonl"), BossEncounter.class, io);
        bosses = new java.util.ArrayList<>(bossStore.load());
        Path spawnDir = dir.resolve("spawn_pool_world");
        if (Files.isDirectory(spawnDir)) {
            int[] r = spawnRules.loadDirectory(spawnDir);
            Log.info("Loaded spawn rules from " + r[0] + " file(s), " + r[1] + " failed");
        }
        profiles = loadProfiles();
        auditStore = new JsonlStore<>(dataDir.resolve("automation_audit.jsonl"), dev.rareradar.core.automation.AuditEvent.class, io);
        dev.rareradar.core.automation.AutomationController.Limits lim = new dev.rareradar.core.automation.AutomationController.Limits();
        lim.maxRunMinutes = cfg.safety.maxRunMinutes;
        controller = new dev.rareradar.core.automation.AutomationController(lim, auditStore::append);
        tasks = new dev.rareradar.core.automation.TaskStore(dataDir.resolve("automation_tasks.jsonl"), io);
        if (!tasks.interruptedOnLoad().isEmpty())
            Log.warn(tasks.interruptedOnLoad().size() + " automation task(s) were interrupted by the last shutdown; none will resume automatically");
        valuator = new Valuator(market, cfg.valuation);
        opportunities = new OpportunityFinder(market, valuator, cfg.valuation);
        rarity = new RarityEngine(cfg.rarity, watchlist,
                o -> valuator.demand(o.species, o.form, System.currentTimeMillis()),
                this::dexMissing);
        retrainModelAsync();
        Log.info("RareRadar V3 data: " + market.size() + " prices, " + encounters.all().size() + " encounters, "
                + hunts.all().size() + " hunts, " + sessions.history().size() + " sessions");
    }

    private boolean dexMissing(String species) {
        if (!dex.universe.isEmpty()) return dex.universe.contains(species) && !dex.caught.contains(species);
        return CobblemonCompat.dexOwned(species) == Boolean.FALSE;
    }

    public PriceModel model() { return model; }

    /** Trains the price model on a background thread from a snapshot of the market data. */
    public void retrainModelAsync() {
        if (training) return;
        training = true;
        List<dev.rareradar.core.market.PriceObservation> snapshot = List.copyOf(market.all());
        Thread t = new Thread(() -> {
            try {
                PriceModel m = new PriceModel();
                m.train(new java.util.ArrayList<>(snapshot), 42);
                model = m;
                Log.info("Price model: " + m.metrics().summary());
            } catch (Throwable e) {
                Log.error("Price model training failed; heuristic valuation stays active", e);
            } finally {
                training = false;
            }
        }, "RareRadar-ML");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Called on the client thread when a trained model is available; hands it to the valuator. */
    public void adoptModel() {
        PriceModel m = model;
        if (m != null) valuator.setModel(m);
    }

    // ---------------- config & watchlist persistence ----------------

    private V3Config loadConfig() {
        Path f = dir.resolve("v3.json");
        V3Config c = null;
        try {
            if (Files.exists(f)) c = PRETTY.fromJson(Files.readString(f), V3Config.class);
        } catch (Exception e) {
            Log.warn("config/rareradar/v3.json is invalid (" + e.getMessage() + "); a backup was kept and defaults are used");
            backup(f);
        }
        if (c == null) {
            c = new V3Config();
            migrateFromV1(c);
        }
        c.sanitize();
        return c;
    }

    /** Carries the V1 tier toggles into the V3 rarity config on first start. */
    private static void migrateFromV1(V3Config c) {
        Settings s = Settings.S;
        c.rarity.shiny = s.shiny;
        c.rarity.legendary = s.legendary;
        c.rarity.mythical = s.mythical;
        c.rarity.ultraBeast = s.ultraBeast;
        c.rarity.paradox = s.paradox;
        Log.info("Created V3 config (tier toggles migrated from rareradar.json)");
    }

    private dev.rareradar.core.automation.ServerProfiles loadProfiles() {
        Path f = dir.resolve("servers.json");
        dev.rareradar.core.automation.ServerProfiles p = null;
        try {
            if (Files.exists(f)) p = PRETTY.fromJson(Files.readString(f), dev.rareradar.core.automation.ServerProfiles.class);
        } catch (Exception e) {
            Log.warn("config/rareradar/servers.json is invalid (" + e.getMessage() + "); defaults used, bad file kept");
            backup(f);
        }
        if (p == null) {
            p = dev.rareradar.core.automation.ServerProfiles.defaults();
            // Migrate V3's private-server allowlist: those servers were explicitly authorised by the user.
            for (String host : cfg.privateServerAllowlist) {
                var prof = p.resolve(false, host);
                if (!prof.automationAuthorized && !prof.id.equals("unknown"))
                    dev.rareradar.core.automation.ServerProfiles.authorize(prof, "Migrated from the V3 private-server allowlist", System.currentTimeMillis());
            }
        }
        p.sanitize();
        return p;
    }

    public void saveProfiles() {
        String json = PRETTY.toJson(profiles);
        Path f = dir.resolve("servers.json");
        io.submit(() -> writeAtomic(f, json));
    }

    public void saveConfig() {
        String json = PRETTY.toJson(cfg);
        Path f = dir.resolve("v3.json");
        io.submit(() -> writeAtomic(f, json));
    }

    private void loadWatchlist() {
        Path f = dir.resolve("watchlist.json");
        try {
            if (Files.exists(f)) watchlist.importJson(Files.readString(f), true);
        } catch (Exception e) {
            Log.warn("watchlist.json is invalid (" + e.getMessage() + "); starting with an empty watchlist");
            backup(f);
        }
    }

    public void saveWatchlist() {
        String json = watchlist.exportJson();
        Path f = dir.resolve("watchlist.json");
        io.submit(() -> writeAtomic(f, json));
    }

    static void writeAtomic(Path f, String text) {
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Log.error("Could not write " + f, e);
        }
    }

    private static void backup(Path f) {
        try { Files.copy(f, f.resolveSibling(f.getFileName() + ".invalid-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING); }
        catch (Exception ignored) { /* best effort: the warning above already reports the problem */ }
    }

    public void logBoss(BossEncounter b) {
        bosses.add(b);
        bossStore.append(b);
    }

    /** Flushes and stops the IO thread (client shutdown). */
    public void shutdown() {
        exposure.save();
        saveConfig();
        saveProfiles();
        io.flush(3000);
        io.close();
    }
}
