package dev.rareradar.ui;

import dev.rareradar.*;
import dev.rareradar.core.breeding.*;
import dev.rareradar.core.dex.DexPlanner;
import dev.rareradar.core.hunt.*;
import dev.rareradar.core.inventory.ResourceBook;
import dev.rareradar.core.market.*;
import dev.rareradar.core.mega.BossEncounter;
import dev.rareradar.core.mega.MegaPlanner;
import dev.rareradar.core.pokebuilder.PokeBuilderCosts;
import dev.rareradar.core.profit.*;
import dev.rareradar.core.rarity.PokemonObservation;
import dev.rareradar.core.rarity.Watchlist;
import dev.rareradar.core.report.DashboardText;
import dev.rareradar.core.report.Line;
import dev.rareradar.core.spawn.*;
import dev.rareradar.core.stats.DataExport;
import dev.rareradar.core.stats.Reports;
import dev.rareradar.core.stats.SessionRecord;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Estimate;
import dev.rareradar.core.util.Money;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.*;

/** Dashboard pages. Text content is produced by {@link DashboardText} (unit-tested in core). */
final class Tabs {
    private Tabs() {}

    static Galaxy g() { return Galaxy.get(); }
    static long now() { return System.currentTimeMillis(); }

    /** Hunt optimiser inputs from the user's own logs. */
    static HuntOptimizer.Inputs huntInputs() {
        Galaxy g = g();
        HuntOptimizer.Inputs in = new HuntOptimizer.Inputs();
        double hours = g.exposure.totalHours();
        List<EncounterRecord> log = g.encounters.all();
        Map<String, Double> byBiome = new HashMap<>();
        for (String b : g.exposure.biomes()) byBiome.put(b, g.exposure.hours(b));
        in.rate = h -> {
            if (h.species == null || hours <= 0) return null;
            // Best measured area: the area's encounter rate × the species' share there (sightings, blended with the
            // spawn model when cached), times the shiny odds for shiny hunts. Falls back to the all-areas count.
            var opts = dev.rareradar.core.hunting.ShinyHuntPlanner.rank(h.species, log, byBiome,
                    cachedModelShare(h.species), h.requiresShiny() ? g.cfg.shinyRate : 1);
            for (var o : opts) if (o.rateKnown())
                return new HuntOptimizer.RateInput(0, o.exposureHours(), "in " + o.where() + " (" + o.shareSource().name().toLowerCase(Locale.ROOT) + " share"
                        + (h.requiresShiny() ? String.format(Locale.ROOT, ", 1/%.0f shiny", g.cfg.shinyRate) : "") + ")", o.targetShinyPerHour());
            long n = 0;
            for (EncounterRecord r : log) if (h.species.equals(r.species) && (!h.requiresShiny() || r.shiny)) n++;
            return new HuntOptimizer.RateInput(n, hours, "across all logged areas");
        };
        for (SessionRecord s : g.sessions.allIncludingCurrent()) { in.catchAttempts += s.ballsThrown; in.catchSuccesses += s.caught; }
        in.travelMinutes = g.cfg.travelMinutes;
        in.huntPointValue = g.cfg.huntPointValue;
        return in;
    }

    static List<ProfitEngine.Option> options() {
        Galaxy g = g();
        List<ProfitEngine.Option> opts = new ArrayList<>(ProfitEngine.measured(g.activities.all(), now() - 14L * 86_400_000L, true));
        huntingOptions(opts);
        List<HuntOptimizer.Ranked> ranked = HuntOptimizer.rank(g.hunts.active(now()), huntInputs());
        if (!ranked.isEmpty() && ranked.get(0).valuePerHour().known()) {
            HuntOptimizer.Ranked r = ranked.get(0);
            opts.add(new ProfitEngine.Option("Hunt: " + (r.hunt().title.isBlank() ? r.hunt().species : r.hunt().title), Activity.HUNTS,
                    r.valuePerHour(), "Estimated from encounter rates and your entered reward: " + r.explanation(), false));
        }
        return opts;
    }

    /** Knowledge-free, data-driven hunting estimates from the user's encounter log and market data (not measured profit). */
    static void huntingOptions(List<ProfitEngine.Option> opts) {
        Galaxy g = g();
        double hours = g.exposure.totalHours();
        if (hours < 0.25) return;
        Map<String, Long> all = new HashMap<>(), legendary = new HashMap<>();
        long enc = 0, shinies = 0;
        for (EncounterRecord r : g.encounters.all()) {
            all.merge(r.species, 1L, Long::sum);
            enc++;
            if (r.shiny) shinies++;
            if (r.rarity != null && (r.rarity.equals("Legendary") || r.rarity.equals("Mythical"))) legendary.merge(r.species, 1L, Long::sum);
        }
        long now = now();
        var v = g.valuator;
        java.util.function.Function<Map<String, Long>, List<dev.rareradar.core.hunting.HuntingValueEstimator.SpeciesCount>> toList = m -> {
            List<dev.rareradar.core.hunting.HuntingValueEstimator.SpeciesCount> l = new ArrayList<>();
            m.forEach((k, c) -> l.add(new dev.rareradar.core.hunting.HuntingValueEstimator.SpeciesCount(k, c)));
            return l;
        };
        java.util.function.ToDoubleFunction<String> base = sp -> { var e = v.bucketValue(new MarketKey(sp, null, false), now); return e.known() ? e.value() : Double.NaN; };
        java.util.function.ToDoubleFunction<String> shinyVal = sp -> { var e = v.bucketValue(new MarketKey(sp, null, true), now); return e.known() ? e.value() : Double.NaN; };
        double pShiny = dev.rareradar.core.hunting.HuntingValueEstimator.shinyProbability(shinies, enc,
                dev.rareradar.core.hunting.HuntingValueEstimator.COBBLEMON_DEFAULT_SHINY_RATE, dev.rareradar.core.hunting.HuntingValueEstimator.COBBLEMON_DEFAULT_SHINY_RATE)[0];
        add(opts, "HYPOTHETICAL ceiling: catch everything seen (asking prices, nothing sold)", dev.rareradar.core.hunting.HuntingValueEstimator.estimate(toList.apply(all), hours, 1, base, Double.NaN));
        add(opts, "HYPOTHETICAL ceiling: shiny hunting (asking prices, nothing sold)", dev.rareradar.core.hunting.HuntingValueEstimator.estimate(toList.apply(all), hours, pShiny, shinyVal, Double.NaN));
        if (!legendary.isEmpty()) add(opts, "HYPOTHETICAL ceiling: legendary/mythical hunting (asking prices, nothing sold)", dev.rareradar.core.hunting.HuntingValueEstimator.estimate(toList.apply(legendary), hours, 1, base, Double.NaN));
    }

    private static void add(List<ProfitEngine.Option> opts, String name, dev.rareradar.core.hunting.HuntingValueEstimator.Result r) {
        if (!r.perHour().known()) return;
        opts.add(new ProfitEngine.Option(name, Activity.RARE_HUNTING, r.perHour(),
                String.format(Locale.ROOT, "NOT INCOME — a modelled upper bound built from asking prices; no sale has confirmed it. %s; %.0f%% of encounters valued; %s", r.perHour().basis(), r.coverage() * 100, String.join("; ", r.assumptions())), false));
    }

    static Estimate inventoryValue() {
        double[] v = g().cfg.resources.totalValue();
        if (v[0] == 0 && v[1] > 0) return Estimate.unknown("prices missing");
        return new Estimate(v[0], v[0], v[0], 1, v[1] > 0 ? Confidence.LOW : Confidence.MEDIUM,
                v[1] > 0 ? (int) v[1] + " item type(s) unpriced" : "manual prices");
    }

    static void writeFile(DashboardScreen s, Path f, String content, String what) {
        g().io.submit(() -> {
            try {
                Files.createDirectories(f.getParent());
                Files.writeString(f, content);
                MinecraftClient.getInstance().execute(() -> s.flash("§aSaved " + what + " → " + f));
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> s.flash("§cCould not save " + what + ": " + e.getMessage()));
            }
        });
    }

    // =================================================================== Overview
    static final class Overview implements Tab {
        public String name() { return "Overview"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            var daily = g().cfg.daily;
            String today = dev.rareradar.core.daily.DailyChecklist.today(ZoneId.systemDefault(), now());
            int col = 0, bw = Math.min(200, (w - 8) / 3);
            for (String item : daily.lastDone.keySet()) {
                String label = (daily.done(item, today) ? "§a[✔] " : "[ ] ") + item.replaceAll(" \\(.*\\)", "");
                int cx = x + (col % 3) * (bw + 4), cy = y + (col / 3) * 20;
                s.button(cx, cy, bw, label, () -> { daily.toggle(item, today); g().saveConfig(); s.rebuild(); });
                col++;
            }
            int rowsUsed = ((col + 2) / 3) * 20;
            s.button(x, y + rowsUsed, 180, "Vote Party started now", () -> {
                g().cfg.voteParty.start = now();
                g().saveConfig();
                s.flash("§aVote Party window recorded (" + Math.round(g().cfg.voteParty.durationMinutes) + " min)");
            });
            return rowsUsed + 22;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            String today = dev.rareradar.core.daily.DailyChecklist.today(ZoneId.systemDefault(), now());
            out.add(Line.dim("GALAXY DAILY CHECKLIST: " + g.cfg.daily.remaining(today) + " item(s) left today (reminders only — RareRadar never runs commands)"));
            int[] vp = CobbleGalaxyBridge.voteParty;
            if (vp != null) out.add(Line.t("Vote Party progress: " + vp[0] + " / " + vp[1] + " (from the /vote menu)"));
            voteParty(out, g);
            out.add(Line.blank());
            atAGlance(out, g);
            out.add(Line.blank());
            DashboardText.session(out, g.sessions.current(), inventoryValue());
            out.add(Line.blank());
            DashboardText.recommendation(out, ProfitEngine.recommend(options()));
            for (String w : g.cfg.resources.warnings()) out.add(Line.warn("⚠ " + w));
        }
    }

    /** Which of the player's balls to throw at a sighted wild Pokémon (overworld, full HP, no status). */
    static String captureLine(dev.rareradar.core.rarity.PokemonObservation o) {
        var kb = g().knowledge;
        var mc = MinecraftClient.getInstance();
        if (o.level == null || !kb.isLoaded() || mc.player == null || mc.world == null) return null;
        var sp = kb.species(o.species);
        if (sp == null) return null;
        Integer cr = sp.catchRateFor(o.form);
        var st = sp.baseStatsFor(o.form);
        if (cr == null || st == null) return null;
        Set<String> owned = new LinkedHashSet<>();
        for (var stack : mc.player.getInventory().main) { String id = CobblemonCompat.ballId(stack); if (id != null) owned.add(id); }
        if (owned.isEmpty()) return "    capture: no Poké Balls in your inventory";
        Set<String> types = new HashSet<>();
        for (String t : sp.typesFor(o.form)) if (t != null) types.add(t.toLowerCase(Locale.ROOT));
        var ctx = dev.rareradar.core.mechanics.BallContext.builder().inBattle(false).level(o.level).types(types)
                .baseSpeed(st.speed()).ultraBeast(sp.labelsFor(o.form).contains("ultra_beast"))
                .status(dev.rareradar.core.mechanics.PersistentStatus.NONE).dayTime(mc.world.getTimeOfDay()).build();
        var rows = dev.rareradar.core.mechanics.CaptureAdvisor.rankOwned(cr, st.hp(), o.level, ctx, owned);
        StringBuilder b = new StringBuilder("    capture/throw (overworld, full HP, model):");
        int n = 0;
        for (var r : rows) {
            if (n++ >= 3) break;
            String name = r.ball().replace("_ball", "");
            if (r.guaranteed()) b.append(" ").append(name).append(" 100%");
            else if (Double.isNaN(r.probability())) b.append(" ").append(name).append(" ?");
            else b.append(String.format(Locale.ROOT, " %s %.1f%% (~%.0f)", name, 100 * r.probability(), r.expectedBalls()));
        }
        return b.toString();
    }

    /** Unified summary: hunting, trading and earnings from recorded data only (unknowns stay unknown). */
    static void atAGlance(List<Line> out, Galaxy g) {
        long now = now();
        var l = dev.rareradar.core.stats.Reports.lifetime(g.sessions.allIncludingCurrent(), now);
        var all = l.all();
        out.add(Line.h("AT A GLANCE — hunting · trading · earnings (from your recorded sessions and logs)"));
        double expectedShinies = all.encountered() / g.cfg.shinyRate;
        out.add(Line.t(String.format(Locale.ROOT, "Hunting:  %.1f h over %d sessions · %d encounters · %d shiny (%.2f expected at 1/%.0f) · %d caught · %s balls/catch · %d active hunts",
                all.hours(), all.sessions(), all.encountered(), all.shiny(), expectedShinies, g.cfg.shinyRate, all.caught(),
                Double.isFinite(l.ballsPerCatch()) ? String.format(Locale.ROOT, "%.1f", l.ballsPerCatch()) : "?", g.hunts.active(now).size())));
        int listings = 0, sales = 0, sts = 0;
        for (var p : g.market.all()) switch (p.kind) { case LISTING -> listings++; case SALE, PURCHASE -> sales++; case STS -> sts++; }
        long under = g.opportunities.marketWide(now).stream().filter(o -> o.type() == dev.rareradar.core.market.Opportunity.Type.POTENTIALLY_UNDERVALUED).count();
        long gtsBetter = dev.rareradar.core.market.SellChannelAdvisor.compareAll(g.market, g.cfg.gtsFeePercent, now).stream()
                .filter(c -> c.verdict() == dev.rareradar.core.market.SellChannelAdvisor.Verdict.GTS).count();
        out.add(Line.t(String.format(Locale.ROOT, "Trading:  %d asking prices · %d confirmed sales · %d STS payouts · %d possibly underpriced listings · %d species better sold on GTS · mode %s",
                listings, sales, sts, under, gtsBetter, g.cfg.tradingMode == TradeAssistant.Mode.ASSISTED ? "assisted alerts" : "observe")));
        if (sales == 0) out.add(Line.dim("          No confirmed sales yet: value estimates rest on asking prices (LOW confidence) and trade alerts stay silent."));
        double chg = l.profitPerHourChange();
        out.add(Line.t(String.format(Locale.ROOT, "Earnings: %s realised in total · last 7 days %s/h · previous 7 days %s/h%s",
                Money.fmt(all.realisedProfit()), Money.fmt(l.last7().profitPerHour()), Money.fmt(l.prev7().profitPerHour()),
                Double.isFinite(chg) ? String.format(Locale.ROOT, " (%+.0f%%)", 100 * chg) : "")));
        out.add(l.observedHours() > 0
                ? Line.t(String.format(Locale.ROOT, "          Observed balance change (sidebar): %s over %.1f h = %s/h — the most direct measure of what you actually earned",
                        Money.fmt(l.observedBalanceNet()), l.observedHours(), Money.fmt(l.observedPerHour())))
                : Line.dim("          No sidebar balance readings yet (Money page) — earnings above are only what you logged or labelled."));
        if (l.best() != null)
            out.add(Line.dim(String.format(Locale.ROOT, "          Best session: %s/h over %.0f min (%s).", Money.fmt(l.best().realisedProfit() / (l.best().minutes() / 60)),
                    l.best().minutes(), java.time.Instant.ofEpochMilli(l.best().start).atZone(ZoneId.systemDefault()).toLocalDate())));
    }

    /** Vote Party window efficiency: shinies seen during the window vs the user's own baseline shiny rate. */
    static void voteParty(List<Line> out, Galaxy g) {
        var vp = g.cfg.voteParty;
        if (vp.start <= 0) return;
        long end = vp.start + (long) (vp.durationMinutes * 60_000);
        vp.encounters = 0;
        vp.shinies = 0;
        for (EncounterRecord r : g.encounters.since(vp.start)) if (r.ts <= end) { vp.encounters++; if (r.shiny) vp.shinies++; }
        double observedMin = Math.min(vp.durationMinutes, Math.max(0, (now() - vp.start) / 60_000.0));
        if (vp.active(now())) out.add(Line.good(String.format(Locale.ROOT, "VOTE PARTY ACTIVE — %.0f min left (wiki: ×%.1f shiny rate)", vp.minutesLeft(now()), vp.reportedMultiplier)));
        else if (now() - end > 24 * 3600_000L) return;
        double hours = g.exposure.totalHours();
        long allShiny = 0;
        for (EncounterRecord r : g.encounters.all()) if (r.shiny && (r.ts < vp.start || r.ts > end)) allShiny++;
        double baseline = hours > 0 ? allShiny / hours : Double.NaN;
        double mult = vp.observedMultiplier(observedMin, baseline);
        out.add(Line.t(String.format(Locale.ROOT, "Vote Party window: %d encounters, %d shinies · observed shiny rate %s · vs your baseline %s",
                vp.encounters, vp.shinies, rateText(vp.shinyRate(observedMin)),
                Double.isFinite(mult) ? String.format(Locale.ROOT, "×%.1f", mult) : "unknown")));
        out.add(Line.dim("A handful of shinies is a tiny sample: treat the observed multiplier as very rough."));
    }

    static String rateText(Estimate e) {
        return e.known() ? String.format(Locale.ROOT, "%.1f/h (%.1f–%.1f, %s)", e.value(), e.low(), e.high(), e.confidence().label) : "unknown";
    }

    // =================================================================== Radar
    static final class Radar implements Tab {
        public String name() { return "Radar"; }

        public void lines(List<Line> out) {
            out.add(Line.h("NEARBY  (" + RadarScanner.hits().size() + " qualifying · biome " + RadarScanner.currentBiome + " · " + RadarScanner.currentTime + ")"));
            for (RadarScanner.Hit h : RadarScanner.hits()) {
                Valuation v = h.rarity.qualifies() ? RadarScanner.value(h) : null;
                out.add(Line.t(String.format(Locale.ROOT, "%s%s %s [%s] %dm  score %.1f%s", h.huntTarget ? "★ " : "", h.obs.species,
                        h.obs.isShiny() ? "(shiny)" : "", h.rarity.qualifies() ? h.rarity.primary().label : "Hunt", (int) h.distance,
                        h.rarity.score(), h.rarity.demandKnown() ? "" : " (demand unknown)")));
                if (v != null) out.add(Line.dim("    value " + Money.range(v.total()) + " · " + v.method()));
                String cap = captureLine(h.obs);
                if (cap != null) out.add(Line.dim(cap));
            }
            if (RadarScanner.hits().isEmpty()) out.add(Line.dim("Nothing qualifying in range. The client only knows Pokémon the server has sent it."));
            out.add(Line.blank());
            out.add(Line.h("PERFORMANCE"));
            long n = Math.max(1, RadarScanner.scans);
            out.add(Line.t(String.format(Locale.ROOT, "scan avg %.3f ms · last %.3f ms · max %.3f ms over %d scans (every %d ticks)",
                    RadarScanner.totalScanNanos / 1e6 / n, RadarScanner.lastScanNanos / 1e6, RadarScanner.maxScanNanos / 1e6, RadarScanner.scans, g().cfg.scanIntervalTicks)));
            Runtime rt = Runtime.getRuntime();
            out.add(Line.dim(String.format(Locale.ROOT, "JVM heap used %d MB of %d MB · IO queue drops %d", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, g().io.dropped())));
            if (AutoHunt.machine() != null) {
                out.add(Line.blank());
                out.add(Line.h("AUTOHUNT (private worlds only) — " + (AutoHunt.active ? "ACTIVE" : "inactive")));
                out.add(Line.t(AutoHunt.status()));
                for (var t : AutoHunt.machine().history()) out.add(Line.dim("  " + t.from() + " → " + t.to() + " : " + t.why()));
            }
        }
    }

    // =================================================================== Economy
    static final class Economy implements Tab {
        private TextFieldWidget minutes, revenue, estimated, costs;
        private static Activity activity = Activity.HUNTS;

        public String name() { return "Economy"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            ButtonWidget[] act = new ButtonWidget[1];
            act[0] = s.button(x, y, 150, activity.label, () -> {
                activity = Activity.values()[(activity.ordinal() + 1) % Activity.values().length];
                act[0].setMessage(Text.literal(activity.label));
            });
            minutes = s.field(x + 154, y, 60, "minutes");
            revenue = s.field(x + 218, y, 80, "received $");
            estimated = s.field(x + 302, y, 80, "est. value $");
            costs = s.field(x + 386, y, 70, "costs $");
            s.button(x + 460, y, 60, "Log", () -> {
                double m = Parse.money(minutes.getText());
                if (!(m > 0)) throw new IllegalArgumentException("Enter the minutes spent");
                if (revenue.getText().isBlank() && estimated.getText().isBlank() && costs.getText().isBlank())
                    throw new IllegalArgumentException("Enter what you received, the estimated value or costs (type 0 if nothing)");
                ActivityRecord r = ActivityRecord.of(activity, now() - (long) (m * 60_000), m,
                        nz(Parse.money(revenue.getText())), nz(Parse.money(estimated.getText())), nz(Parse.money(costs.getText())));
                g().activities.add(r);
                g().sessions.reward(r.realisedRevenue);
                g().sessions.spend(r.costs);
                s.flash("§aLogged " + activity.label);
                s.rebuild();
            });
            return 22;
        }

        private static double nz(double v) { return Double.isFinite(v) ? v : 0; }

        public void lines(List<Line> out) {
            List<ProfitEngine.Option> opts = options();
            // Keep observed, confirmed and hypothetical money apart: only confirmed payouts/sales count as income.
            List<Double> asks = new ArrayList<>();
            for (var o : g().market.all()) if (o.kind == PriceObservation.Kind.LISTING && o.price > 0) asks.add(o.price);
            var eb = dev.rareradar.core.economy.EarningsBreakdown.compute(asks, g().transactions.all(), Double.NaN);
            out.add(Line.h("REALISED (confirmed) vs OBSERVED vs HYPOTHETICAL"));
            out.add(eb.hasRealisedData() ? Line.good(eb.headline()) : Line.warn(eb.headline()));
            out.add(Line.t(String.format(Locale.ROOT, "Observed asking prices: %d%s", eb.askCount(),
                    eb.askCount() == 0 ? "" : String.format(Locale.ROOT, " (median $%,.0f, middle half $%,.0f–$%,.0f) — what sellers want, not what buyers paid", eb.askMedian(), eb.askLow(), eb.askHigh()))));
            for (String n : eb.notes()) out.add(Line.dim("  " + n));
            out.add(Line.dim("Options marked HYPOTHETICAL below are modelled ceilings, not income. Rank decisions by measured results."));
            out.add(Line.blank());
            DashboardText.activities(out, opts);
            out.add(Line.blank());
            DashboardText.recommendation(out, ProfitEngine.recommend(opts));
            out.add(Line.blank());
            List<ActivityRecord> all = new ArrayList<>(g().activities.all());
            DashboardText.recentActivities(out, all, 10);
            out.add(Line.dim("“received” = Pokédollars actually paid to you (realised). “est. value” = worth of unsold catches/items (estimated)."));
        }
    }

    // =================================================================== Market
    static final class Market implements Tab {
        private TextFieldWidget species, price, ivs;
        private static PriceObservation.Kind kind = PriceObservation.Kind.SALE;
        private static boolean shiny;

        public String name() { return "Market"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            species = s.field(x, y, 100, "species");
            price = s.field(x + 104, y, 80, "price (12k)");
            ButtonWidget[] k = new ButtonWidget[2];
            k[0] = s.button(x + 188, y, 70, kind.name(), () -> {
                kind = PriceObservation.Kind.values()[(kind.ordinal() + 1) % PriceObservation.Kind.values().length];
                k[0].setMessage(Text.literal(kind.name()));
            });
            k[1] = s.button(x + 262, y, 60, shiny ? "Shiny" : "Normal", () -> { shiny = !shiny; k[1].setMessage(Text.literal(shiny ? "Shiny" : "Normal")); });
            ivs = s.field(x + 326, y, 120, "IVs 31/31/.. (opt)");
            s.button(x + 450, y, 50, "Add", () -> {
                PriceObservation p = PriceObservation.of(species.getText(), shiny, kind, Parse.money(price.getText()), now());
                p.ivs = Parse.ivs(ivs.getText());
                if (!ivs.getText().isBlank() && p.ivs == null) throw new IllegalArgumentException("IVs must be six numbers 0-31");
                g().market.add(p);
                s.flash("§aAdded " + kind + " " + p.species + " " + Money.fmt(p.price));
                if (g().market.size() % 10 == 0) g().retrainModelAsync();
                s.rebuild();
            });
            Path imp = g().dir.resolve("import").resolve("market.csv");
            s.button(x, y + 22, 160, "Import import/market.csv", () -> g().io.submit(() -> {
                try {
                    MarketCsv.Parsed parsed = MarketCsv.parse(Files.readString(imp));
                    MinecraftClient.getInstance().execute(() -> {
                        int[] r = g().market.importAll(parsed.rows());
                        g().retrainModelAsync();
                        s.flash("§aImported " + r[0] + " rows; " + (parsed.errors().size() + r[1]) + " rejected" + (parsed.errors().isEmpty() ? "" : " (first: " + parsed.errors().get(0) + ")"));
                    });
                } catch (Exception e) {
                    MinecraftClient.getInstance().execute(() -> s.flash("§cPut a CSV at " + imp + " (" + e.getMessage() + ")"));
                }
            }));
            s.button(x + 288, y + 22, 170, "Trading: " + (g().cfg.tradingMode == TradeAssistant.Mode.ASSISTED ? "§eASSISTED alerts" : "observe only"), () -> {
                g().cfg.tradingMode = g().cfg.tradingMode == TradeAssistant.Mode.ASSISTED ? TradeAssistant.Mode.OBSERVE : TradeAssistant.Mode.ASSISTED;
                g().saveConfig();
                s.rebuild();
            });
            s.button(x + 164, y + 22, 120, "Export market CSV", () ->
                    writeFile(s, g().dir.resolve("export").resolve("market.csv"), MarketCsv.export(g().market.all()), "market CSV"));
            return 44;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            long now = now();
            out.add(Line.dim("CSV columns: " + MarketCsv.HEADER + "   kinds: LISTING (asking) · SALE (confirmed) · STS (server payout) · PURCHASE"));
            if (CobbleGalaxyBridge.present())
                out.add(Line.dim("CobbleGalaxy bridge: " + (g.cfg.captureGtsListings ? CobbleGalaxyBridge.gtsCaptured + " GTS entries recorded this session from pages you viewed" : "GTS capture off")
                        + (CobbleGalaxyBridge.gtsSlots != null ? " · your listing slots " + CobbleGalaxyBridge.gtsSlots[0] + "/" + CobbleGalaxyBridge.gtsSlots[1] : "")));
            var model = g.model();
            out.add(Line.dim("Price model: " + (model == null ? "training…" : model.metrics().summary())));
            out.add(Line.blank());
            out.add(Line.h("GTS CAPTURE DIAGNOSTICS  (detected → parsed → saved; rejected lines are logged and kept in data/gts_samples.txt)"));
            out.add(Line.t("CobbleGalaxy client mod: " + (CobbleGalaxyBridge.present() ? "installed" : "not installed — its custom /gts screen can't be read; chat + chest-menu capture still work")));
            for (var st : List.of(CobbleGalaxyBridge.GUI, GtsCapture.MENU, GtsCapture.CHAT)) {
                out.add(st.saved > 0 ? Line.good("  " + st.summary()) : Line.t("  " + st.summary()));
                for (String smp : st.recentRejectedSamples().subList(Math.max(0, st.recentRejectedSamples().size() - 3), st.recentRejectedSamples().size()))
                    out.add(Line.dim("     rejected: " + (smp.length() > 140 ? smp.substring(0, 140) + "…" : smp)));
            }
            out.add(Line.blank());
            resaleCandidates(out, g, now);
            out.add(Line.blank());
            DashboardText.opportunities(out, g.opportunities.marketWide(now), 8);
            out.add(Line.blank());
            stsVsGts(out, g, now);
            out.add(Line.blank());
            DashboardText.marketSummary(out, g.market, g.valuator, now, 30);
        }
    }

    /** Listings from the last 3 days that could be resold at a profit, judged on confirmed sales, fee and liquidity. */
    static void resaleCandidates(List<Line> out, Galaxy g, long now) {
        var list = g.opportunities.assessRecentListings(g.cfg.gtsFeePercent, now);
        out.add(Line.h("RESALE CANDIDATES (recent listings · confirmed sales only · fee and liquidity counted · you decide and buy yourself)"));
        if (list.isEmpty()) { out.add(Line.dim("  None. This needs 3+ confirmed sales per Pokémon (MEDIUM confidence) and a listing below them.")); return; }
        for (int i = 0; i < Math.min(8, list.size()); i++) {
            var a = list.get(i);
            String l = String.format(Locale.ROOT, "  %-8s %s asking %s · margin %s (below-typical %s) · ~%s/sale — %s",
                    a.verdict() == dev.rareradar.core.market.OpportunityFinder.Verdict.BUY_CANDIDATE ? "STRONG" : "maybe", a.listing().key(),
                    Money.fmt(a.listing().price), Money.fmt(a.expectedNet()), Money.fmt(a.conservativeNet()),
                    Double.isFinite(a.daysToSell()) ? String.format(Locale.ROOT, "%.1fd", a.daysToSell()) : "?", a.reason());
            out.add(a.verdict() == dev.rareradar.core.market.OpportunityFinder.Verdict.BUY_CANDIDATE ? Line.good(l) : Line.t(l));
        }
    }

    /** STS (instant) vs GTS (confirmed sales after fee) per bucket. */
    static void stsVsGts(List<Line> out, Galaxy g, long now) {
        var all = dev.rareradar.core.market.SellChannelAdvisor.compareAll(g.market, g.cfg.gtsFeePercent, now);
        out.add(Line.h("STS vs GTS  (STS = your logged server payouts · GTS = confirmed sales × (1 − fee); asking prices never count)"));
        if (all.isEmpty()) { out.add(Line.dim("  Log STS payouts (kind STS) and confirmed sales (kind SALE) to compare channels.")); return; }
        if (!Double.isFinite(g.cfg.gtsFeePercent)) out.add(Line.warn("  GTS fee unknown — set it on the Config page; GTS net can't be computed without it."));
        for (int i = 0; i < Math.min(10, all.size()); i++) {
            var c = all.get(i);
            String line = String.format(Locale.ROOT, "  %-22s %-7s STS %s · GTS net %s (%d sales%s) — %s", c.key(), c.verdict(), Money.fmt(c.stsPayout()),
                    Money.fmt(c.gtsNet()), c.sales(), Double.isFinite(c.expectedWaitDays()) ? String.format(Locale.ROOT, ", ~%.1f d/sale", c.expectedWaitDays()) : "", c.reason());
            out.add(c.verdict() == dev.rareradar.core.market.SellChannelAdvisor.Verdict.GTS ? Line.good(line) : Line.t(line));
        }
    }

    // =================================================================== Money (transactions + balance)
    static final class MoneyTab implements Tab {
        private static int selected;
        private TextFieldWidget labelField;

        public String name() { return "Money"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            s.button(x, y, 24, "◀", () -> selected = Math.max(0, selected - 1));
            s.button(x + 26, y, 24, "▶", () -> selected++);
            int bx = x + 56;
            for (var k : dev.rareradar.core.economy.TransactionKind.values()) {
                s.button(bx, y, 92, k.label, () -> {
                    var list = g().moneyMessages.recent();
                    if (list.isEmpty()) throw new IllegalArgumentException("No unlabelled money message yet");
                    var e = list.get(Math.min(selected, list.size() - 1));
                    var r = MoneyCapture.label(e.message(), k);
                    g().moneyMessages.remove(e);
                    s.flash("§aLearned \"" + k.label + "\" format from: " + e.message());
                    RareRadarClient.LOG.info("[Money] learned rule {} ({}) regex {}", r.id, k, r.regex);
                    s.rebuild();
                });
                bx += 94;
            }
            int y2 = y + 22;
            s.button(x, y2, 130, "Delete newest rule", () -> {
                var rules = g().cfg.transactionRules;
                if (rules.isEmpty()) throw new IllegalArgumentException("No rules");
                var r = rules.remove(rules.size() - 1);
                g().saveConfig();
                s.flash("§eDeleted rule learned from: " + r.example);
                s.rebuild();
            });
            labelField = s.field(x + 134, y2, 200, "balance line regex (blank = default)");
            labelField.setText(g().cfg.balanceLabel);
            s.button(x + 338, y2, 60, "Set", () -> {
                g().cfg.balanceLabel = labelField.getText().trim();
                g().balance.setLabel(g().cfg.balanceLabel);
                g().balance.resetSession();
                g().saveConfig();
            });
            s.button(x + 402, y2, 110, "Tracking: " + (g().cfg.trackMoney ? "on" : "off"), () -> { g().cfg.trackMoney = !g().cfg.trackMoney; g().saveConfig(); s.rebuild(); });
            return 44;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            long now = now();
            var b = g.balance;
            out.add(Line.h("BALANCE (scoreboard sidebar, read-only)"));
            if (Double.isNaN(b.current())) out.add(Line.dim("  No balance line found on the sidebar. If your sidebar shows your money, set a regex that matches that line."));
            else {
                out.add(Line.t("  Now " + dev.rareradar.core.util.Money.fmt(b.current()) + " · this session " + signed(b.sessionNet()) + " (observed) · from line \"" + b.lastLine() + "\""));
                var ch = b.changes();
                for (int i = Math.max(0, ch.size() - 5); i < ch.size(); i++)
                    out.add(Line.dim("  " + ago(ch.get(i).ts()) + "  " + signed(ch.get(i).delta())));
            }
            out.add(Line.blank());
            out.add(Line.h("RECORDED TRANSACTIONS (only formats you labelled; ✔ = matched by a balance change)"));
            var sess = g.sessions.current();
            long[] since = {sess == null ? now : sess.start, now - 7 * 86_400_000L, 0};
            String[] names = {"session", "7 days", "all time"};
            for (int i = 0; i < 3; i++) {
                var t = g.transactions.totals(since[i]);
                StringBuilder sb = new StringBuilder("  " + names[i] + ": net " + signed(g.transactions.net(since[i])));
                for (var e : t.entrySet()) sb.append(" · ").append(e.getKey().label).append(" ").append(dev.rareradar.core.util.Money.fmt(e.getValue()));
                out.add(Line.t(sb.toString()));
            }
            var recent = g.transactions.all();
            for (int i = recent.size() - 1, k = 0; i >= 0 && k < 6; i--, k++) {
                var t = recent.get(i);
                out.add(Line.dim(String.format(Locale.ROOT, "  %s %s %s %s%s", t.balanceConfirmed ? "✔" : " ", ago(t.ts), t.kind.label, signed(t.signed()),
                        t.subject == null ? "" : " (" + t.subject + ")")));
            }
            out.add(Line.blank());
            var cands = g.moneyMessages.recent();
            out.add(Line.h("UNLABELLED MONEY MESSAGES (" + cands.size() + ") — select with ◀ ▶, then click what it is"));
            if (cands.isEmpty()) out.add(Line.dim("  None yet. Sell to the server, or wait for one of your GTS listings to sell; the server's message will appear here."));
            selected = Math.min(selected, Math.max(0, cands.size() - 1));
            for (int i = 0; i < Math.min(10, cands.size()); i++) {
                var e = cands.get(i);
                String l = (i == selected ? "▶ " : "  ") + ago(e.ts()) + "  " + e.message();
                out.add(i == selected ? Line.good(l) : Line.t(l));
            }
            out.add(Line.dim("  Label only messages about YOUR money. Labelling creates an exact-format rule; other players' chat can't match it."));
            out.add(Line.blank());
            out.add(Line.h("LEARNED FORMATS (" + g.cfg.transactionRules.size() + ")"));
            for (var r : g.cfg.transactionRules) out.add(Line.dim("  " + r.kind.label + ": " + r.example));
        }

        private static String signed(double v) {
            return Double.isNaN(v) ? "?" : (v >= 0 ? "+" : "−") + dev.rareradar.core.util.Money.fmt(Math.abs(v));
        }
    }

    // =================================================================== Hunts
    static final class Hunts implements Tab {
        private TextFieldWidget title, species, count, points, money, current;
        /** Selected hunt by id: the ranking re-orders as progress and time change, so an index would drift. */
        private static String selectedId;

        public String name() { return "Hunts"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            title = s.field(x, y, 110, "title");
            species = s.field(x + 114, y, 90, "species");
            count = s.field(x + 208, y, 40, "count");
            points = s.field(x + 252, y, 60, "hunt pts");
            money = s.field(x + 316, y, 70, "reward $");
            s.button(x + 390, y, 50, "Add", () -> {
                HuntObjective h = HuntObjective.manual(title.getText(), species.getText().isBlank() ? null : species.getText(), Parse.integer(count.getText(), 1), now());
                double p = Parse.money(points.getText()), m = Parse.money(money.getText());
                if (Double.isFinite(p)) h.rewardHuntPoints = p;
                if (Double.isFinite(m)) h.rewardMoney = m;
                g().hunts.add(h);
                s.rebuild();
            });
            int y2 = y + 22;
            s.button(x, y2, 24, "◀", () -> move(-1));
            s.button(x + 26, y2, 24, "▶", () -> move(1));
            s.button(x + 54, y2, 50, "+1", () -> act(h -> g().hunts.progress(h.id, 1, now())));
            s.button(x + 106, y2, 50, "−1", () -> act(h -> g().hunts.progress(h.id, -1, now())));
            s.button(x + 158, y2, 70, "Complete", () -> act(h -> { g().hunts.complete(h.id, now()); g().sessions.huntCompleted();
                if (h.rewardHuntPoints != null) { g().cfg.huntGoal.currentPoints += h.rewardHuntPoints; g().saveConfig(); } }));
            s.button(x + 230, y2, 70, "Abandon", () -> act(h -> g().hunts.abandon(h.id)));
            current = s.field(x + 310, y2, 70, "my points");
            s.button(x + 384, y2, 90, "Set points", () -> {
                double p = Parse.money(current.getText());
                if (!Double.isFinite(p)) throw new IllegalArgumentException("Enter your current Hunt Points");
                g().cfg.huntGoal.currentPoints = p;
                g().saveConfig();
            });
            return 44;
        }

        private static List<HuntObjective> ordered() {
            List<HuntObjective> l = new ArrayList<>();
            for (HuntOptimizer.Ranked r : HuntOptimizer.rank(g().hunts.active(now()), huntInputs())) l.add(r.hunt());
            return l;
        }

        private static HuntObjective selected() {
            List<HuntObjective> l = ordered();
            if (l.isEmpty()) return null;
            for (HuntObjective h : l) if (h.id.equals(selectedId)) return h;
            selectedId = l.get(0).id;
            return l.get(0);
        }

        private static void move(int d) {
            List<HuntObjective> l = ordered();
            if (l.isEmpty()) return;
            HuntObjective cur = selected();
            int i = Math.floorMod(l.indexOf(cur) + d, l.size());
            selectedId = l.get(i).id;
        }

        private static void act(java.util.function.Consumer<HuntObjective> f) {
            HuntObjective h = selected();
            if (h == null) throw new IllegalArgumentException("No active hunt selected");
            f.accept(h);
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            long now = now();
            List<HuntOptimizer.Ranked> ranked = HuntOptimizer.rank(g.hunts.active(now), huntInputs());
            HuntObjective sel = selected();
            if (sel != null) out.add(Line.good("Selected for ◀ ▶ / +1 / Complete: " + (sel.title.isBlank() ? sel.species : sel.title)));
            if (CobbleGalaxyBridge.present()) out.add(Line.dim("Pinned CobbleGalaxy hunts sync automatically (last sync " + ago(CobbleGalaxyBridge.lastHuntSync) + ")."));
            DashboardText.hunts(out, ranked, now);
            out.add(Line.blank());
            HuntPointGoal goal = g.cfg.huntGoal;
            DashboardText.huntGoal(out, goal, goal.project(g.hunts.completed()));
            out.add(Line.blank());
            shinyHunting(out, g, sel);
            out.add(Line.blank());
            out.add(Line.h("COMPLETION HISTORY (" + g.hunts.completed().size() + ")"));
            List<HuntObjective> done = g.hunts.completed();
            for (int i = done.size() - 1, k = 0; i >= 0 && k < 8; i--, k++) {
                HuntObjective h = done.get(i);
                out.add(Line.dim("  " + (h.title.isBlank() ? h.species : h.title) + " · " + (h.rewardHuntPoints == null ? "?" : Math.round(h.rewardHuntPoints)) + " HP"));
            }
        }
    }

    private static final Map<String, java.util.concurrent.CompletableFuture<Map<String, Double>>> MODEL_SHARE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Spawn-model share per biome for a species, computed once in the background; empty until ready. */
    static Map<String, Double> cachedModelShare(String species) {
        var kb = g().knowledge;
        if (!kb.isLoaded() || species == null) return Map.of();
        if (MODEL_SHARE.size() > 64) MODEL_SHARE.clear();
        var f = MODEL_SHARE.computeIfAbsent(species.toLowerCase(Locale.ROOT), sp -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Map<String, Double> m = new HashMap<>();
            for (var r : dev.rareradar.core.knowledge.SpawnProbability.bestBiomes(kb, sp, dev.rareradar.core.knowledge.SpawnProbability.Context.openSky(null, null), 400))
                m.merge(r.biomeId(), r.share().share(), Math::max);
            return m;
        }));
        return f.isDone() && !f.isCompletedExceptionally() ? f.join() : Map.of();
    }

    /** Shiny-hunt plan for the selected hunt's species (or the radar's top target), plus the overall dry streak. */
    static void shinyHunting(List<Line> out, Galaxy g, HuntObjective sel) {
        double rate = g.cfg.shinyRate;
        out.add(Line.h(String.format(Locale.ROOT, "SHINY HUNTING  (1 in %.0f per Pokémon%s)", rate, rate == 8192 ? ", Cobblemon default — the server may differ" : ", from your config")));
        var log = g.encounters.all();
        var streak = dev.rareradar.core.hunting.ShinyHuntPlanner.streak(log, null, rate);
        out.add(Line.t("  " + streak.note()));
        String species = sel != null && sel.species != null ? sel.species : null;
        if (species == null) { out.add(Line.dim("  Select a hunt with a species (◀ ▶) to see where to shiny-hunt it.")); return; }
        Map<String, Double> model = cachedModelShare(species);
        if (model.isEmpty() && g.knowledge.isLoaded()) out.add(Line.dim("  computing spawn model…"));
        Map<String, Double> hours = new HashMap<>();
        for (String b : g.exposure.biomes()) hours.put(b, g.exposure.hours(b));
        var opts = dev.rareradar.core.hunting.ShinyHuntPlanner.rank(species, log, hours, model, rate);
        // Only visited biomes have a measured rate; show those plus the top few model suggestions.
        int shown = 0, unvisited = 0;
        for (var o : opts) {
            if (o.rateKnown() && shown < 5) {
                shown++;
                out.add(Line.t(String.format(Locale.ROOT, "  %-30s %.0f enc/h × %.1f%% %s → ~%.0f h expected · 50%% by %.0f h · 90%% by %.0f h",
                        o.where(), o.encountersPerHour(), 100 * o.share(), o.shareSource().name().toLowerCase(Locale.ROOT), o.expectedHours(), o.hours50(), o.hours90())));
            } else if (!o.rateKnown() && unvisited < 3) {
                unvisited++;
                out.add(Line.dim(String.format(Locale.ROOT, "  %-30s %.2f%% of spawns (%s) — spend 15+ min there to measure its encounter rate",
                        o.where(), 100 * o.share(), o.shareSource().name().toLowerCase(Locale.ROOT))));
            }
        }
        if (opts.isEmpty()) out.add(Line.dim("  No sightings of " + species + " and no ordinary natural spawn in the model."));
        var sp = dev.rareradar.core.hunting.ShinyHuntPlanner.streak(log, species, rate);
        out.add(Line.dim("  " + species + ": " + sp.sinceLastShiny() + " seen since the last shiny " + species + " (" + sp.shiniesSeen() + " shiny seen in total)."));
    }

    static String ago(long t) {
        if (t <= 0) return "never";
        long s = (now() - t) / 1000;
        return s < 60 ? s + "s ago" : s < 3600 ? s / 60 + "m ago" : s / 3600 + "h ago";
    }

    // =================================================================== Breeding (+ PokéBuilder, Mega)
    static final class Breeding implements Tab {
        private static int minPerfect = 5;
        private static boolean knot = true, everstone;
        private TextFieldWidget hitValue, missValue, eggCost, eggMinutes, nature;

        public String name() { return "Breed/Build"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            // Toggles relabel in place (no rebuild) so typed economics inputs are kept.
            ButtonWidget[] b = new ButtonWidget[3];
            b[0] = s.button(x, y, 90, "Target " + minPerfect + "×31", () -> { minPerfect = minPerfect >= 6 ? 1 : minPerfect + 1; b[0].setMessage(Text.literal("Target " + minPerfect + "×31")); });
            b[1] = s.button(x + 94, y, 90, "Knot: " + (knot ? "yes" : "no"), () -> { knot = !knot; b[1].setMessage(Text.literal("Knot: " + (knot ? "yes" : "no"))); });
            b[2] = s.button(x + 188, y, 100, "Everstone: " + (everstone ? "yes" : "no"), () -> { everstone = !everstone; b[2].setMessage(Text.literal("Everstone: " + (everstone ? "yes" : "no"))); });
            nature = s.field(x + 292, y, 70, "nature");
            hitValue = s.field(x, y + 22, 80, "value if hit $");
            missValue = s.field(x + 84, y + 22, 80, "value if miss $");
            eggCost = s.field(x + 168, y + 22, 70, "cost/egg $");
            eggMinutes = s.field(x + 242, y + 22, 70, "min/egg");
            s.button(x + 316, y + 22, 70, "Analyse", s::rebuildLater);
            return 44;
        }

        public void lines(List<Line> out) {
            List<Parent> party = new ArrayList<>(CobblemonCompat.partyParents());
            // Apply the chosen held items to every candidate (the client can't reliably see held breeding items).
            List<Parent> withItems = new ArrayList<>();
            for (int i = 0; i < party.size(); i++) {
                Parent p = party.get(i);
                HeldItem item = i == 0 && knot ? HeldItem.DESTINY_KNOT : i == 1 && everstone ? HeldItem.EVERSTONE : HeldItem.NONE;
                withItems.add(new Parent(p.species(), p.ivs(), p.nature(), p.gender(), p.eggGroups(), item));
            }
            String nat = nature == null || nature.getText().isBlank() ? null : nature.getText().trim().toLowerCase(Locale.ROOT);
            BreedingAnalyzer.Economics eco = null;
            if (hitValue != null && Double.isFinite(Parse.money(hitValue.getText())) && Double.isFinite(Parse.money(eggCost.getText())))
                eco = new BreedingAnalyzer.Economics(Parse.money(hitValue.getText()), Parse.money(missValue.getText()),
                        Parse.money(eggCost.getText()), Parse.money(eggMinutes.getText()), 4);
            var results = new BreedingAnalyzer(new BreedingRules()).bestPairs(withItems, new BreedingAnalyzer.Target(new int[6], minPerfect, nat), eco, 6);
            DashboardText.breeding(out, results);
            out.add(Line.dim("Items: first party slot gets the Destiny Knot, second the Everstone (toggle above). Up to 4 pastures assumed (wiki)."));
            out.add(Line.blank());
            pokeBuilder(out);
            out.add(Line.blank());
            mega(out);
        }

        private static void pokeBuilder(List<Line> out) {
            out.add(Line.h("POKÉBUILDER"));
            PokeBuilderCosts c = g().pokeBuilderCosts;
            if (c == null) { out.add(Line.dim("Token costs unknown — open /pokebuilder once on CobbleGalaxy (read-only capture), costs then appear here.")); return; }
            out.add(Line.t(String.format(Locale.ROOT, "Balance %s tokens · per IV point %s · per EV point %s · shiny %s · level +1 %s  (%s, %s)",
                    fmt(c.balance), fmt(c.costPerIv), fmt(c.costPerEv), c.shinyAvailable ? fmt(c.shinyCost) : "n/a", fmt(c.levelUpCost), c.source, ago(c.observedAt))));
            for (Pokemon p : partyObs()) {
                double ivCost = c.ivUpgradeCost(p.obs.ivs, 31);
                Valuation now = g().valuator.value(p.obs, now());
                PokemonObservation perfect = PokemonObservation.of(p.obs.species).shiny(p.obs.isShiny()).ivs(31, 31, 31, 31, 31, 31);
                Valuation after = g().valuator.value(perfect, now());
                double gain = now.total().known() && after.total().known() ? after.total().value() - now.total().value() : Double.NaN;
                out.add(Line.t(String.format(Locale.ROOT, "  %s: max IVs costs %s tokens · value %s → %s (gain %s)%s", p.obs.species, fmt(ivCost),
                        Money.fmt(now.total().value()), Money.fmt(after.total().value()), Money.fmt(gain),
                        Double.isFinite(gain) && Double.isFinite(g().cfg.tokenValue) && gain < ivCost * g().cfg.tokenValue ? " §6not worth it at your token value" : "")));
            }
        }

        private record Pokemon(PokemonObservation obs) {}

        private static List<Pokemon> partyObs() {
            List<Pokemon> l = new ArrayList<>();
            for (var p : CobblemonCompat.party()) { var o = CobblemonCompat.observeOwn(p); if (o != null) l.add(new Pokemon(o)); }
            return l;
        }

        private static String fmt(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.0f", v) : "?"; }

        private static void mega(List<Line> out) {
            MegaPlanner m = g().cfg.mega;
            out.add(Line.h("MEGA EVOLUTION"));
            out.add(Line.t("Mega Bracelet: " + (m.hasBracelet ? "owned" : "not owned — " + Math.round(g().cfg.huntGoal.remaining()) + " Hunt Points to go (if your goal is the bracelet)")));
            out.add(Line.t("Mega Stones owned: " + (m.ownedStones.isEmpty() ? "none recorded" : String.join(", ", m.ownedStones))));
            var rate = MegaPlanner.dropRate(g().bosses);
            out.add(Line.t("Boss Mega Stone drop rate (your log): " + (rate.known() ? DashboardText.pct(rate.value()) + " (" + DashboardText.pct(rate.low()) + "–" + DashboardText.pct(rate.high()) + ", " + rate.basis() + ")" : rate.basis())));
            out.add(Line.dim("Bosses are logged from Config → “Log boss”. The wiki does not state a drop frequency, so none is assumed."));
        }
    }

    // =================================================================== Pokédex
    static final class Dex implements Tab {
        public String name() { return "Pokédex"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            s.button(x, y, 160, "Sync from Cobblemon dex", () -> {
                DexPlanner d = g().dex;
                Set<String> owned = CobblemonCompat.dexOwnedSpecies();
                if (d.universe.isEmpty()) {
                    // Without the server's Dex Rewards list, use every species Cobblemon marks as implemented.
                    d.universe.addAll(CobblemonCompat.implementedSpecies());
                    d.source = "Cobblemon client Pokédex + implemented species (CobbleGalaxy's Dex Rewards list may differ)";
                }
                d.caught.addAll(owned);
                d.updatedAt = now();
                s.flash("§aSynced " + owned.size() + " owned species of " + d.universe.size());
            });
            return 22;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            Map<String, Double> rates = new HashMap<>();
            double hours = g.exposure.totalHours();
            if (hours > 0) for (var e : EncounterStats.speciesFrequency(g.encounters.all())) rates.put(e.getKey(), e.getValue() / hours);
            DashboardText.dex(out, g.dex, g.dex.suggest(rates::get, g.spawnRules::forSpecies, 12));
        }
    }

    // =================================================================== Spawns
    static final class Spawns implements Tab {
        private TextFieldWidget species;
        private static String query = "";

        public String name() { return "Spawns"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            species = s.field(x, y, 120, "species to look up");
            species.setText(query);
            s.button(x + 124, y, 60, "Look up", () -> { query = PokemonObservation.norm(species.getText()); s.rebuild(); });
            return 22;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            List<EncounterRecord> log = g.encounters.all();
            double hours = g.exposure.totalHours();
            out.add(Line.h("ENCOUNTERS  (" + log.size() + " logged over " + String.format(Locale.ROOT, "%.1f", hours) + " h of observation)"));
            var sum = EncounterStats.summary(log, hours);
            out.add(Line.t("All " + rate(sum[0]) + " · rare " + rate(sum[1]) + " · shiny " + rate(sum[2])));
            List<EncounterRecord> local = g.encounters.since(RadarScanner.localSince);
            out.add(Line.dim("Since the last teleport/area change: " + local.size() + " encounters"));
            out.add(Line.blank());
            if (!query.isEmpty()) {
                DashboardText.biomes(out, query, BiomeRecommender.recommend(log, g.exposure, r -> query.equals(r.species), query, 0.25), 8);
                List<SpawnRule> rules = g.spawnRules.forSpecies(query);
                out.add(Line.h("SPAWN RULES for " + query + (rules.isEmpty() ? " — none loaded" : "")));
                if (rules.isEmpty()) out.add(Line.dim("Copy Cobblemon spawn_pool_world/*.json files into config/rareradar/spawn_pool_world/ and restart."));
                for (SpawnRule r : rules) out.add(Line.t("  " + r.describe() + "  [" + r.source + "]"));
                out.add(Line.blank());
            }
            DashboardText.biomes(out, "shiny Pokémon", BiomeRecommender.recommend(log, g.exposure, r -> r.shiny, "shinies", 1.0), 6);
            out.add(Line.blank());
            DashboardText.biomes(out, "rare Pokémon", BiomeRecommender.recommend(log, g.exposure, r -> r.rarity != null, "rares", 1.0), 6);
            out.add(Line.blank());
            out.add(Line.h("BY TIME OF DAY (rare)"));
            for (var r : EncounterStats.byTime(log, g.exposure, x -> x.rarity != null)) out.add(Line.t("  " + r.key() + ": " + rate(r.perHour()) + " (" + r.perHour().basis() + ")"));
            out.add(Line.h("MOST SEEN SPECIES"));
            var freq = EncounterStats.speciesFrequency(log);
            for (int i = 0; i < Math.min(10, freq.size()); i++) out.add(Line.dim(String.format(Locale.ROOT, "  %-14s %d", freq.get(i).getKey(), freq.get(i).getValue())));
        }

        private static String rate(Estimate e) {
            return e.known() ? String.format(Locale.ROOT, "%.2f/h (%.2f–%.2f, %s)", e.value(), e.low(), e.high(), e.confidence().label) : "unknown";
        }
    }

    // =================================================================== Inventory
    static final class Inventory implements Tab {
        private TextFieldWidget value, reserve;
        private static int selected;

        public String name() { return "Inventory"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            List<ResourceBook.Resource> res = new ArrayList<>(g().cfg.resources.resources.values());
            if (res.isEmpty()) return 0;
            selected = Math.floorMod(selected, res.size());
            ResourceBook.Resource r = res.get(selected);
            s.button(x, y, 24, "◀", () -> { selected--; s.rebuild(); });
            s.button(x + 26, y, 120, r.label, () -> {});
            s.button(x + 148, y, 24, "▶", () -> { selected++; s.rebuild(); });
            value = s.field(x + 176, y, 80, "unit price $");
            reserve = s.field(x + 260, y, 60, "reserve");
            s.button(x + 324, y, 50, "Save", () -> {
                double v = Parse.money(value.getText());
                if (!value.getText().isBlank()) { if (!Double.isFinite(v)) throw new IllegalArgumentException("Invalid price"); r.unitValue = v; }
                if (!reserve.getText().isBlank()) r.reserve = Math.max(0, Parse.integer(reserve.getText(), r.reserve));
                g().saveConfig();
                s.flash("§aSaved " + r.label);
            });
            s.button(x + 378, y, 80, (r.protectedItem ? "§cProtected" : "Unprotected"), () -> { r.protectedItem = !r.protectedItem; g().saveConfig(); s.rebuild(); });
            return 22;
        }

        public void lines(List<Line> out) {
            out.add(Line.h("RESOURCES  (counts from your inventory; prices you entered)"));
            for (ResourceBook.Resource r : g().cfg.resources.resources.values()) {
                out.add(Line.t(String.format(Locale.ROOT, "%-14s ×%-4d unit %-8s total %-9s reserve %d%s", r.label, r.count, Money.fmt(r.unitValue),
                        Double.isFinite(r.unitValue) ? Money.fmt(r.unitValue * r.count) : "?", r.reserve, r.protectedItem ? "  [protected]" : "")));
            }
            Estimate total = inventoryValue();
            out.add(Line.t("Replacement value: " + Money.range(total)));
            for (String w : g().cfg.resources.warnings()) out.add(Line.warn("⚠ " + w));
            out.add(Line.blank());
            out.add(Line.h("BALL POLICY (AutoHunt)"));
            var b = g().cfg.balls;
            out.add(Line.t("Protected: " + String.join(", ", b.protectedBalls) + (g().cfg.allowProtectedBalls ? "  §c(ALLOWED by you)" : "  (never thrown)")));
            out.add(Line.t("Cheapest → best: " + String.join(" → ", b.order) + " · best ball only for score ≥ " + b.highValueScore));
        }
    }

    // =================================================================== History
    static final class History implements Tab {
        public String name() { return "History"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            Path out = g().dir.resolve("export");
            s.button(x, y, 110, "Export sessions", () -> {
                var sessions = g().sessions.allIncludingCurrent();
                writeFile(s, out.resolve("sessions.csv"), DataExport.toCsv(sessions, SessionRecord.class), "sessions CSV");
                writeFile(s, out.resolve("sessions.json"), DataExport.toJson(sessions), "sessions JSON");
            });
            s.button(x + 114, y, 120, "Export encounters", () -> {
                writeFile(s, out.resolve("encounters.csv"), DataExport.toCsv(g().encounters.all(), EncounterRecord.class), "encounters CSV");
                writeFile(s, out.resolve("encounters.json"), DataExport.toJson(g().encounters.all()), "encounters JSON");
            });
            s.button(x + 238, y, 110, "Export activities", () ->
                    writeFile(s, out.resolve("activities.csv"), DataExport.toCsv(g().activities.all(), ActivityRecord.class), "activities CSV"));
            s.button(x + 352, y, 80, "Backup", () -> {
                Path zip = g().dataDir.resolve("backups").resolve("rareradar-" + now() + ".zip");
                g().io.submit(() -> {
                    try { int n = DataExport.backup(g().dataDir, zip); MinecraftClient.getInstance().execute(() -> s.flash("§aBacked up " + n + " files → " + zip)); }
                    catch (Exception e) { MinecraftClient.getInstance().execute(() -> s.flash("§cBackup failed: " + e.getMessage())); }
                });
            });
            return 22;
        }

        public void lines(List<Line> out) {
            var sessions = g().sessions.allIncludingCurrent();
            ZoneId z = ZoneId.systemDefault();
            DashboardText.periods(out, "DAILY", Reports.daily(sessions, z), 14);
            out.add(Line.blank());
            DashboardText.periods(out, "WEEKLY", Reports.weekly(sessions, z), 8);
            out.add(Line.blank());
            out.add(Line.dim("Restore: stop the game, then unzip a backup from config/rareradar/data/backups into config/rareradar/data."));
        }
    }

    // =================================================================== Config
    static final class Config implements Tab {
        private TextFieldWidget watchSpecies, hpValue, tokenValue, bossSpecies, feePct;

        public String name() { return "Config"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            V3Config c = g().cfg;
            s.button(x, y, 140, "Classic radar settings…", () -> MinecraftClient.getInstance().setScreen(new SettingsScreen()));
            s.button(x + 144, y, 130, "Bridge: " + onOff(c.cobbleGalaxyBridge), () -> { c.cobbleGalaxyBridge = !c.cobbleGalaxyBridge; s.rebuild(); });
            s.button(x + 278, y, 130, "GTS capture: " + onOff(c.captureGtsListings), () -> { c.captureGtsListings = !c.captureGtsListings; s.rebuild(); });
            s.button(x + 412, y, 110, "HUD values: " + onOff(c.showValues), () -> { c.showValues = !c.showValues; s.rebuild(); });
            s.button(x + 526, y, 110, "GTS chat: " + onOff(c.captureGtsChat), () -> { c.captureGtsChat = !c.captureGtsChat; s.rebuild(); });
            s.button(x + 640, y, 120, "GTS menu: " + onOff(c.captureGtsChestMenu), () -> { c.captureGtsChestMenu = !c.captureGtsChestMenu; s.rebuild(); });
            int y2 = y + 22;
            s.button(x, y2, 90, "Scan every " + c.scanIntervalTicks + "t", () -> { c.scanIntervalTicks = c.scanIntervalTicks >= 20 ? 1 : c.scanIntervalTicks * 2; s.rebuild(); });
            s.button(x + 94, y2, 90, "HUD scale " + c.hudScale, () -> { c.hudScale = c.hudScale >= 2f ? 0.5f : c.hudScale + 0.25f; s.rebuild(); });
            s.button(x + 188, y2, 100, "HUD bg " + (c.hudOpacity * 100 / 255) + "%", () -> { c.hudOpacity = c.hudOpacity >= 192 ? 0 : c.hudOpacity + 64; s.rebuild(); });
            s.button(x + 292, y2, 70, "HUD ↑", () -> c.hudY = Math.max(0, c.hudY - 10));
            s.button(x + 364, y2, 70, "HUD ↓", () -> c.hudY += 10);
            s.button(x + 436, y2, 40, "←", () -> c.hudX = Math.max(0, c.hudX - 10));
            s.button(x + 478, y2, 40, "→", () -> c.hudX += 10);
            int y3 = y2 + 22;
            watchSpecies = s.field(x, y3, 100, "watch species");
            s.button(x + 104, y3, 50, "Watch", () -> { g().watchlist.add(new Watchlist.Entry(watchSpecies.getText(), null, false, 5, "")); g().saveWatchlist(); RadarScanner.clear(); });
            s.button(x + 156, y3, 70, "Shiny only", () -> { g().watchlist.add(new Watchlist.Entry(watchSpecies.getText(), null, true, 7, "")); g().saveWatchlist(); RadarScanner.clear(); });
            s.button(x + 228, y3, 60, "Remove", () -> { g().watchlist.remove(watchSpecies.getText(), null, false); g().watchlist.remove(watchSpecies.getText(), null, true); g().saveWatchlist(); RadarScanner.clear(); });
            s.button(x + 290, y3, 70, "Export list", () -> writeFile(s, g().dir.resolve("export").resolve("watchlist.json"), g().watchlist.exportJson(), "watchlist"));
            s.button(x + 362, y3, 110, "Import import/watch…", () -> g().io.submit(() -> {
                Path f = g().dir.resolve("import").resolve("watchlist.json");
                try {
                    String json = Files.readString(f);
                    MinecraftClient.getInstance().execute(() -> {
                        try { int n = g().watchlist.importJson(json, false); g().saveWatchlist(); RadarScanner.clear(); s.flash("§aImported " + n + " watchlist entries"); }
                        catch (IllegalArgumentException e) { s.flash("§c" + e.getMessage()); }
                    });
                } catch (Exception e) { MinecraftClient.getInstance().execute(() -> s.flash("§cPut a watchlist JSON at " + f)); }
            }));
            int y4 = y3 + 22;
            hpValue = s.field(x, y4, 90, "$ per Hunt Point");
            tokenValue = s.field(x + 94, y4, 90, "$ per token");
            feePct = s.field(x + 520, y4, 60, "GTS fee %");
            s.button(x + 188, y4, 60, "Set", () -> {
                if (!hpValue.getText().isBlank()) c.huntPointValue = Parse.money(hpValue.getText());
                if (!tokenValue.getText().isBlank()) c.tokenValue = Parse.money(tokenValue.getText());
                if (!feePct.getText().isBlank()) c.gtsFeePercent = Parse.money(feePct.getText().replace("%", ""));
                c.sanitize();
            });
            bossSpecies = s.field(x + 256, y4, 90, "boss species");
            s.button(x + 350, y4, 80, "Boss: stone", () -> logBoss(true));
            s.button(x + 432, y4, 80, "Boss: none", () -> logBoss(false));
            int y5 = y4 + 22;
            s.button(x, y5, 200, "Automation permissions → Automation page", () -> {});
            s.button(x + 402, y5, 120, "Master Balls: " + (c.allowProtectedBalls ? "§cALLOWED" : "protected"), () -> { c.allowProtectedBalls = !c.allowProtectedBalls; s.rebuild(); });
            int y6 = y5 + 22;
            var r = c.rarity;
            weight(s, x, y6, "Shiny", () -> r.wShiny, v -> r.wShiny = v);
            weight(s, x + 104, y6, "Legend", () -> r.wLegendary, v -> r.wLegendary = v);
            weight(s, x + 208, y6, "Myth", () -> r.wMythical, v -> r.wMythical = v);
            weight(s, x + 312, y6, "UB/Pdx", () -> r.wSpecial, v -> r.wSpecial = v);
            weight(s, x + 416, y6, "Demand", () -> r.wDemand, v -> r.wDemand = v);
            return 132;
        }

        /** Weight cycle button: +0.5 per click, wrapping from 10 back to 0. Takes effect on the next scan. */
        private static void weight(DashboardScreen s, int x, int y, String label, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set) {
            s.button(x, y, 100, String.format(Locale.ROOT, "w %s %.1f", label, get.getAsDouble()), () -> {
                double v = get.getAsDouble() + 0.5;
                set.accept(v > 10 ? 0 : v);
                RadarScanner.clear();
                s.rebuild();
            });
        }

        private void logBoss(boolean drop) {
            if (bossSpecies.getText().isBlank()) throw new IllegalArgumentException("Enter the boss species");
            BossEncounter b = new BossEncounter();
            b.ts = now();
            b.species = PokemonObservation.norm(bossSpecies.getText());
            b.outcome = BossEncounter.Outcome.WON;
            b.megaStoneDropped = drop;
            b.biome = RadarScanner.currentBiome;
            g().logBoss(b);
        }

        private static String onOff(boolean b) { return b ? "ON" : "OFF"; }

        public void lines(List<Line> out) {
            V3Config c = g().cfg;
            out.add(Line.h("WATCHLIST (" + g().watchlist.entries().size() + ")"));
            for (Watchlist.Entry e : g().watchlist.entries()) out.add(Line.t("  " + e.species + (e.form == null ? "" : " (" + e.form + ")") + (e.shinyOnly ? " — shiny only" : "") + " · priority " + e.priority));
            out.add(Line.h("RARITY WEIGHTS  R = w1·S + w2·L + w3·M + w4·P + w5·D  (priority score, not spawn odds)"));
            var r = c.rarity;
            out.add(Line.t(String.format(Locale.ROOT, "shiny %.1f · legendary %.1f · mythical %.1f · UB/paradox %.1f · demand %.1f · watchlist %.1f  (click the w buttons; more in config/rareradar/v3.json)",
                    r.wShiny, r.wLegendary, r.wMythical, r.wSpecial, r.wDemand, r.wWatchlist)));
            out.add(Line.h("ECONOMIC INPUTS"));
            out.add(Line.t("Hunt Point value: " + Money.fmt(c.huntPointValue) + " · token value: " + Money.fmt(c.tokenValue) + " · travel " + c.travelMinutes + " min · GTS fee "
                    + (Double.isFinite(c.gtsFeePercent) ? c.gtsFeePercent + "%" : "unknown (enter it if you know it; the server doesn't document one)")
                    + String.format(Locale.ROOT, " · shiny odds 1/%.0f (shinyRate in v3.json)", c.shinyRate)));
            out.add(Line.h("AUTOMATION LIMITS"));
            out.add(Line.t(String.format(Locale.ROOT, "Limits: %d min/run · %d balls/run · %d balls/target · stop below %.0f HP", c.safety.maxRunMinutes, c.safety.maxBallsPerRun, c.safety.maxBallsPerTarget, c.safety.minHealth)));
            out.add(Line.dim("Per-server permissions, enable/pause/stop and the audit log are on the Automation page."));
            if (CobbleGalaxyBridge.present()) {
                out.add(Line.h("COBBLEGALAXY BRIDGE (read-only)"));
                out.add(Line.t("hunts " + ago(CobbleGalaxyBridge.lastHuntSync) + " · GTS " + ago(CobbleGalaxyBridge.lastGtsCapture) + " · dex " + ago(CobbleGalaxyBridge.lastDexSync) + " · PokéBuilder " + ago(CobbleGalaxyBridge.lastBuilderSync)));
                if (!CobbleGalaxyBridge.disabledFeatures().isEmpty()) out.add(Line.warn("Disabled after errors: " + CobbleGalaxyBridge.disabledFeatures()));
            }
            out.add(Line.dim("Data folder: " + g().dir));
        }
    }

    // =================================================================== Automation (V4)
    static final class Automation implements Tab {
        private TextFieldWidget statement;

        public String name() { return "Automation"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            var ctl = g().controller;
            var prof = ctl.profile();
            s.button(x, y, 110, ctl.sessionEnabled() ? "§aEnabled" : "Enable session", () -> {
                var d = ctl.enable(now());
                s.flash(d.allowed() ? "§aAutomation enabled for this session (modules still need their toggle/keys)" : "§c" + d.reason());
                s.rebuild();
            });
            s.button(x + 114, y, 70, "Pause", () -> { ctl.pause(now(), "dashboard"); AutoHunt.stop(MinecraftClient.getInstance(), "paused"); s.rebuild(); });
            s.button(x + 188, y, 70, "Resume", () -> { s.flash(ctl.resume(now()).reason()); s.rebuild(); });
            s.button(x + 262, y, 120, "§cEMERGENCY STOP", () -> {
                ctl.emergencyStop(now(), "dashboard emergency stop");
                AutoHunt.stop(MinecraftClient.getInstance(), "emergency stop");
                s.rebuild();
            });
            int y2 = y + 22, bx = x;
            for (var m : List.of(dev.rareradar.core.automation.Module.HUNTING, dev.rareradar.core.automation.Module.CAPTURE,
                    dev.rareradar.core.automation.Module.NAVIGATION, dev.rareradar.core.automation.Module.TRAINING)) {
                s.button(bx, y2, 110, m.label + ": " + (ctl.moduleEnabled(m) ? "§aON" : "off"), () -> { ctl.setModuleEnabled(m, !ctl.moduleEnabled(m)); s.rebuild(); });
                bx += 114;
            }
            int y3 = y2 + 22;
            if (prof != null && !prof.singleplayer) {
                statement = s.field(x, y3, 300, "who granted permission (e.g. 'I own this server')");
                s.button(x + 304, y3, 140, "Confirm permission", () -> {
                    dev.rareradar.core.automation.ServerProfiles.authorize(prof, statement.getText(), now());
                    g().saveProfiles();
                    s.flash("§eRecorded: automation authorised on " + prof.displayName + " (" + prof.authorizationSource + ")");
                    s.rebuild();
                });
                s.button(x + 448, y3, 90, "Revoke", () -> {
                    dev.rareradar.core.automation.ServerProfiles.revoke(prof);
                    ctl.emergencyStop(now(), "permission revoked");
                    AutoHunt.stop(MinecraftClient.getInstance(), "permission revoked");
                    g().saveProfiles();
                    s.rebuild();
                });
                return 66;
            }
            return 44;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            var ctl = g.controller;
            var prof = ctl.profile();
            out.add(Line.h("SERVER PROFILE"));
            if (prof == null) out.add(Line.dim("Not connected."));
            else {
                out.add(Line.t(prof.displayName + " (" + prof.id + ") · automation " + (prof.automationAuthorized ? "§aauthorised" : "§cnot authorised")));
                if (prof.automationAuthorized) out.add(Line.dim("  authorised by: " + prof.authorizationSource));
                if (!prof.notes.isBlank()) out.add(Line.warn("  " + prof.notes));
                StringBuilder mods = new StringBuilder("  modules: ");
                for (var m : dev.rareradar.core.automation.Module.values()) mods.append(m.label).append('=').append(prof.status(m)).append("  ");
                out.add(Line.dim(mods.toString()));
            }
            out.add(Line.h("CONTROLLER"));
            out.add(Line.t("State " + ctl.state() + " · session " + (ctl.sessionEnabled() ? "enabled" : "not enabled") + " · control held by "
                    + (ctl.leaseHolder() == null ? "nobody" : ctl.leaseHolder().label) + (ctl.stopReason().isBlank() ? "" : " · last stop: " + ctl.stopReason())));
            out.add(Line.t("AutoHunt: " + AutoHunt.status()));
            out.add(Line.dim("Keys: K = hunt, L = train, Backspace = emergency stop. GTS trading automation: unsupported (no verified purchase interface)."));
            out.add(Line.blank());
            out.add(Line.h("PLANNER  (risk-adjusted Pokédollars/hour; executable = RareRadar can run it here)"));
            var plans = dev.rareradar.core.automation.TaskPlanner.plan(options(), ctl, g.strategies, now());
            if (plans.isEmpty()) out.add(Line.dim("No activity has a profit estimate yet — log sessions on the Economy page."));
            for (var p : plans) {
                String text = p.option().name() + " — " + Money.fmt(p.riskAdjustedPerHour()) + "/h risk-adjusted · " + p.why();
                out.add(p.executable() ? Line.good("▶ " + text) : Line.t("• " + text));
                for (String f : p.knownFacts()) out.add(Line.dim("    fact: " + f));
                for (String e : p.estimates()) out.add(Line.dim("    estimate: " + e));
            }
            out.add(Line.blank());
            out.add(Line.h("TASKS"));
            var tasks = g.tasks.all();
            for (int i = tasks.size() - 1, k = 0; i >= 0 && k < 8; i--, k++) {
                var t = tasks.get(i);
                out.add(Line.t(String.format(Locale.ROOT, "%s %s · %s · %s%s", t.module.label, t.status, t.objective, t.lastState,
                        t.note == null || t.note.isBlank() ? "" : " · " + t.note)));
            }
            if (tasks.isEmpty()) out.add(Line.dim("No automation tasks yet."));
            out.add(Line.h("AUDIT LOG (latest first; full log in data/automation_audit.jsonl)"));
            var recent = ctl.recent();
            for (int i = recent.size() - 1, k = 0; i >= 0 && k < 15; i--, k++) {
                var e = recent.get(i);
                out.add(Line.dim(String.format(Locale.ROOT, "%tT %s %s %s %s", e.ts(), e.module().label, e.action(), e.outcome(), e.detail())));
            }
        }
    }

    // =================================================================== Knowledge (V4)
    static final class Knowledge implements Tab {
        private TextFieldWidget query, current, target;
        private static String lookup = "";
        private static dev.rareradar.core.training.EvTrainingPlanner.Stat stat = dev.rareradar.core.training.EvTrainingPlanner.Stat.ATTACK;
        private static int cur = 0, tgt = 252;

        public String name() { return "Knowledge"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            query = s.field(x, y, 140, "species (name or dex #)");
            query.setText(lookup);
            s.button(x + 144, y, 60, "Look up", () -> { lookup = query.getText().trim(); s.rebuild(); });
            ButtonWidget[] b = new ButtonWidget[1];
            b[0] = s.button(x + 214, y, 130, "EV stat: " + stat, () -> {
                stat = dev.rareradar.core.training.EvTrainingPlanner.Stat.values()[(stat.ordinal() + 1) % 6];
                b[0].setMessage(Text.literal("EV stat: " + stat));
            });
            current = s.field(x + 348, y, 50, "have");
            current.setText(Integer.toString(cur));
            target = s.field(x + 402, y, 50, "goal");
            target.setText(Integer.toString(tgt));
            s.button(x + 456, y, 60, "Plan", () -> { cur = Parse.integer(current.getText(), 0); tgt = Parse.integer(target.getText(), 252); s.rebuild(); });
            return 22;
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            var kb = g.knowledge;
            if (!kb.isLoaded()) { out.add(Line.warn("Knowledge base not available: " + kb.errors())); return; }
            var pv = kb.provenance();
            out.add(Line.dim(String.format(Locale.ROOT, "Source: %s %s @ %s (%s) · %d species · %d spawn entries · facts are Cobblemon defaults; the server may override them",
                    pv.name(), pv.version(), pv.commit() == null ? "?" : pv.commit().substring(0, Math.min(10, pv.commit().length())), pv.license(), kb.allSpecies().size(), kb.allSpawns().size())));
            if (!lookup.isBlank()) {
                var sp = lookup.matches("\\d+") ? kb.byDex(Integer.parseInt(lookup)) : kb.species(lookup);
                if (sp == null) out.add(Line.warn("No species \"" + lookup + "\" in the 1.8.2 data."));
                else species(out, kb, sp);
                out.add(Line.blank());
            }
            out.add(Line.h("CAN SPAWN HERE (" + RadarScanner.currentBiome + ") — biome rules only; light, time, height and other conditions not checked"));
            var here = kb.speciesSpawnableInBiome(RadarScanner.currentBiome, RadarScanner.currentBiomeTags);
            out.add(Line.t(here.isEmpty() ? "(none or unknown biome)" : here.size() + " species: " + String.join(", ", here.subList(0, Math.min(40, here.size()))) + (here.size() > 40 ? " …" : "")));
            List<String> rares = new ArrayList<>();
            for (String id : here) { var sp = kb.species(id); if (sp != null && (sp.isLegendary() || sp.isMythical())) rares.add(sp.name()); }
            if (!rares.isEmpty()) out.add(Line.good("Legendary/mythical spawn entries for this biome: " + String.join(", ", rares)));
            likelyHere(out, kb);
            out.add(Line.blank());
            ev(out, g, kb);
        }

        private static void species(List<Line> out, dev.rareradar.core.knowledge.KnowledgeBase kb, dev.rareradar.core.knowledge.Species sp) {
            out.add(Line.h("#" + sp.dex() + " " + sp.name() + " (" + sp.id() + ")" + (sp.labels().isEmpty() ? "" : " · " + String.join(", ", sp.labels()))));
            var st = sp.baseStats();
            out.add(Line.t("Type " + sp.primaryType() + (sp.secondaryType() == null ? "" : "/" + sp.secondaryType())
                    + (st == null ? "" : String.format(Locale.ROOT, " · base %d/%d/%d/%d/%d/%d (total %d)", st.hp(), st.attack(), st.defence(), st.specialAttack(), st.specialDefence(), st.speed(), st.total()))));
            var ev = sp.evYield();
            out.add(Line.t("Catch rate " + (sp.catchRate() == null ? "?" : sp.catchRate()) + " · EV yield "
                    + (ev == null ? "?" : String.format(Locale.ROOT, "%d/%d/%d/%d/%d/%d", ev.hp(), ev.attack(), ev.defence(), ev.specialAttack(), ev.specialDefence(), ev.speed()))
                    + " · exp group " + sp.experienceGroup() + " · egg groups " + sp.eggGroups()));
            out.add(Line.t("Abilities: " + sp.abilities() + (sp.forms().isEmpty() ? "" : " · forms: " + sp.forms().stream().map(f -> f.name()).toList())));
            capture(out, sp, kb.spawnsFor(sp.id()));
            bestBiomes(out, kb, sp);
            for (var e : kb.evolutionsFrom(sp.id())) out.add(Line.t("Evolves → " + e.result() + " (" + e.variant() + ") " + (e.requirementsJson() == null ? "" : e.requirementsJson())));
            var spawns = kb.spawnsFor(sp.id());
            out.add(Line.h("Spawn entries (" + spawns.size() + ") — raw weights, not probabilities"));
            for (int i = 0; i < Math.min(8, spawns.size()); i++) {
                var s = spawns.get(i);
                out.add(Line.dim(String.format(Locale.ROOT, "  %s · %s · lvl %s · weight %s · %s · biomes %s%s", s.id(), s.bucket(), s.level(), s.weight(), s.positionType(),
                        s.biomes(), s.conditionJson() == null ? "" : " · " + (s.conditionJson().length() > 90 ? s.conditionJson().substring(0, 90) + "…" : s.conditionJson()))));
            }
            out.add(Line.dim("  source: " + sp.sourceFile()));
        }

        /** Model share of natural spawns at the player's current biome, time, sky, light, rain and height. */
        private static void likelyHere(List<Line> out, dev.rareradar.core.knowledge.KnowledgeBase kb) {
            var c = RadarScanner.spawnContext;
            if (c == null) return;
            for (String type : List.of("grounded", "submerged", "surface")) {
                var ctx = new dev.rareradar.core.knowledge.SpawnProbability.Context(type, c.timeTick(), c.canSeeSky(), c.skyLight(), c.raining(), c.y(), false);
                var shares = dev.rareradar.core.knowledge.SpawnProbability.inBiome(kb, RadarScanner.currentBiome, RadarScanner.currentBiomeTags, ctx);
                if (shares.isEmpty()) continue;
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < Math.min(10, shares.size()); i++) {
                    var s = shares.get(i);
                    var sp = kb.species(s.speciesId());
                    b.append(i == 0 ? "" : ", ").append(sp == null ? s.speciesId() : sp.name())
                            .append(String.format(Locale.ROOT, " %.1f%%", 100 * s.share()));
                }
                out.add(Line.t("Most likely " + type + " spawns now: " + b + (shares.size() > 10 ? " … (" + shares.size() + " species)" : "")));
            }
            out.add(Line.dim(String.format(Locale.ROOT, "  Model of Cobblemon 1.8.2 spawn buckets/weights at time %d, sky %s, sky light %d, rain %s, y %d; structure/lure/snack spawns excluded. Not measured — server settings may differ.",
                    c.timeTick(), c.canSeeSky(), c.skyLight(), c.raining(), c.y())));
        }

        /** Biome rankings take ~0.1–0.3 s, so they are computed once per species off the render thread. */
        private static final Map<String, java.util.concurrent.CompletableFuture<List<Line>>> BIOME_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        /** Best biomes for a species by modelled share of spawns (daytime/night, open sky, clear weather). */
        private static void bestBiomes(List<Line> out, dev.rareradar.core.knowledge.KnowledgeBase kb, dev.rareradar.core.knowledge.Species sp) {
            if (BIOME_CACHE.size() > 64) BIOME_CACHE.clear();
            var f = BIOME_CACHE.computeIfAbsent(sp.id(), id -> java.util.concurrent.CompletableFuture.supplyAsync(() -> rankBiomes(kb, sp)));
            out.add(Line.h("Best biomes (model share of spawns, open sky, clear weather; structure/lure spawns excluded)"));
            if (!f.isDone()) { out.add(Line.dim("  computing…")); return; }
            try { out.addAll(f.join()); }
            catch (RuntimeException e) { out.add(Line.warn("  biome ranking failed: " + e)); }
        }

        private static List<Line> rankBiomes(dev.rareradar.core.knowledge.KnowledgeBase kb, dev.rareradar.core.knowledge.Species sp) {
            List<Line> out = new ArrayList<>();
            for (int tick : new int[]{dev.rareradar.core.knowledge.SpawnProbability.NOON, dev.rareradar.core.knowledge.SpawnProbability.MIDNIGHT}) {
                String when = tick == dev.rareradar.core.knowledge.SpawnProbability.NOON ? "day  " : "night";
                for (var r : dev.rareradar.core.knowledge.SpawnProbability.bestBiomes(kb, sp.id(), dev.rareradar.core.knowledge.SpawnProbability.Context.openSky(null, tick), 5))
                    out.add(Line.t(String.format(Locale.ROOT, "  %s %-34s %-9s %.2f%% (≈1 in %.0f spawns)%s", when, r.biomeId(), r.positionType(),
                            100 * r.share().share(), 1 / r.share().share(), r.share().viaHerdOnly() ? " · herd only" : "")));
            }
            if (out.isEmpty()) out.add(Line.dim("  No ordinary natural spawn found — it may need a structure, lure, Poké Snack, fishing or a special event."));
            else out.add(Line.dim("  Includes modded biomes from Cobblemon's tags that your server may not have. Model, not measured."));
            return out;
        }

        /** Per-throw capture chance from Cobblemon 1.8.2's formula under stated scenarios (no live HP/status is read). */
        private static void capture(List<Line> out, dev.rareradar.core.knowledge.Species sp, List<dev.rareradar.core.knowledge.SpawnEntry> spawns) {
            var st = sp.baseStats();
            if (sp.catchRate() == null || st == null) return;
            int level = 0;
            for (var s : spawns) {
                var m = java.util.regex.Pattern.compile("(\\d+)").matcher(String.valueOf(s.level()));
                if (m.find()) { int l = Integer.parseInt(m.group(1)); level = level == 0 ? l : Math.min(level, l); }
            }
            if (level <= 0) level = 20;
            var rows = dev.rareradar.core.mechanics.CaptureAdvisor.advise(sp.catchRate(), st.hp(), level);
            out.add(Line.h("Capture chance per throw at level " + level + " (Cobblemon 1.8.2 formula; Pokédex criticals ignored)"));
            for (var sc : dev.rareradar.core.mechanics.CaptureAdvisor.Scenario.values()) {
                StringBuilder b = new StringBuilder("  " + sc.label + ":");
                for (var r : rows) if (r.scenario() == sc)
                    b.append(String.format(Locale.ROOT, "  %s %.1f%% (~%.1f balls)", r.ball().replace("_ball", ""), 100 * r.probability(), r.expectedBalls()));
                out.add(Line.t(b.toString()));
            }
            out.add(Line.dim("  Model, not measured: the server may change capture settings. Wild HP/status are not visible, so these are scenarios."));
        }

        private static void ev(List<Line> out, Galaxy g, dev.rareradar.core.knowledge.KnowledgeBase kb) {
            double hours = g.exposure.totalHours();
            Map<String, Double> rates = new HashMap<>();
            if (hours > 0) for (var e : EncounterStats.speciesFrequency(g.encounters.all())) rates.put(e.getKey(), e.getValue() / hours);
            var plan = dev.rareradar.core.training.EvTrainingPlanner.plan(kb, stat, cur, tgt, 0, rates);
            out.add(Line.h("EV TRAINING: " + stat + " " + plan.current() + " → " + plan.target() + " (" + plan.remaining() + " to go)"));
            if (plan.candidates().isEmpty()) out.add(Line.dim("No Pokémon you have encountered yields " + stat + " EVs yet."));
            for (int i = 0; i < Math.min(6, plan.candidates().size()); i++) {
                var c = plan.candidates().get(i);
                out.add(Line.t(String.format(Locale.ROOT, "  %s: %d EV per KO × %.1f seen/h = %.1f EV/h", c.name(), c.evPerKo(), c.encountersPerHour(), c.evsPerHour())));
            }
            if (Double.isFinite(plan.hoursForBest())) out.add(Line.good(String.format(Locale.ROOT, "Estimated time with the best target: %.1f h", plan.hoursForBest())));
            for (String a : plan.assumptions()) out.add(Line.dim("  assumes: " + a));
        }
    }
}
