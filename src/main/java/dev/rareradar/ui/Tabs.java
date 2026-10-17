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
        in.rate = h -> {
            if (h.species == null || hours <= 0) return null;
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
        List<HuntOptimizer.Ranked> ranked = HuntOptimizer.rank(g.hunts.active(now()), huntInputs());
        if (!ranked.isEmpty() && ranked.get(0).valuePerHour().known()) {
            HuntOptimizer.Ranked r = ranked.get(0);
            opts.add(new ProfitEngine.Option("Hunt: " + (r.hunt().title.isBlank() ? r.hunt().species : r.hunt().title), Activity.HUNTS,
                    r.valuePerHour(), "Estimated from encounter rates and your entered reward: " + r.explanation(), false));
        }
        return opts;
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
            DashboardText.session(out, g.sessions.current(), inventoryValue());
            out.add(Line.blank());
            DashboardText.recommendation(out, ProfitEngine.recommend(options()));
            for (String w : g.cfg.resources.warnings()) out.add(Line.warn("⚠ " + w));
        }
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
            DashboardText.opportunities(out, g.opportunities.marketWide(now), 8);
            out.add(Line.blank());
            DashboardText.marketSummary(out, g.market, g.valuator, now, 30);
        }
    }

    // =================================================================== Hunts
    static final class Hunts implements Tab {
        private TextFieldWidget title, species, count, points, money, current;
        private static int selected;

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
            s.button(x, y2, 24, "◀", () -> selected = Math.max(0, selected - 1));
            s.button(x + 26, y2, 24, "▶", () -> selected++);
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

        private static void act(java.util.function.Consumer<HuntObjective> f) {
            List<HuntOptimizer.Ranked> r = HuntOptimizer.rank(g().hunts.active(now()), huntInputs());
            if (r.isEmpty()) throw new IllegalArgumentException("No active hunt selected");
            selected = Math.min(selected, r.size() - 1);
            f.accept(r.get(selected).hunt());
        }

        public void lines(List<Line> out) {
            Galaxy g = g();
            long now = now();
            List<HuntOptimizer.Ranked> ranked = HuntOptimizer.rank(g.hunts.active(now), huntInputs());
            if (!ranked.isEmpty()) {
                selected = Math.min(selected, ranked.size() - 1);
                out.add(Line.good("Selected for ◀ ▶ / +1 / Complete: #" + (selected + 1)));
            }
            if (CobbleGalaxyBridge.present()) out.add(Line.dim("Pinned CobbleGalaxy hunts sync automatically (last sync " + ago(CobbleGalaxyBridge.lastHuntSync) + ")."));
            DashboardText.hunts(out, ranked, now);
            out.add(Line.blank());
            HuntPointGoal goal = g.cfg.huntGoal;
            DashboardText.huntGoal(out, goal, goal.project(g.hunts.completed()));
            out.add(Line.blank());
            out.add(Line.h("COMPLETION HISTORY (" + g.hunts.completed().size() + ")"));
            List<HuntObjective> done = g.hunts.completed();
            for (int i = done.size() - 1, k = 0; i >= 0 && k < 8; i--, k++) {
                HuntObjective h = done.get(i);
                out.add(Line.dim("  " + (h.title.isBlank() ? h.species : h.title) + " · " + (h.rewardHuntPoints == null ? "?" : Math.round(h.rewardHuntPoints)) + " HP"));
            }
        }
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
            s.button(x, y, 90, "Target " + minPerfect + "×31", () -> { minPerfect = minPerfect >= 6 ? 1 : minPerfect + 1; s.rebuild(); });
            s.button(x + 94, y, 90, "Knot: " + (knot ? "yes" : "no"), () -> { knot = !knot; s.rebuild(); });
            s.button(x + 188, y, 100, "Everstone: " + (everstone ? "yes" : "no"), () -> { everstone = !everstone; s.rebuild(); });
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
        private TextFieldWidget watchSpecies, allow, hpValue, tokenValue, bossSpecies;

        public String name() { return "Config"; }

        public int widgets(DashboardScreen s, int x, int y, int w) {
            V3Config c = g().cfg;
            s.button(x, y, 140, "Classic radar settings…", () -> MinecraftClient.getInstance().setScreen(new SettingsScreen()));
            s.button(x + 144, y, 130, "Bridge: " + onOff(c.cobbleGalaxyBridge), () -> { c.cobbleGalaxyBridge = !c.cobbleGalaxyBridge; s.rebuild(); });
            s.button(x + 278, y, 130, "GTS capture: " + onOff(c.captureGtsListings), () -> { c.captureGtsListings = !c.captureGtsListings; s.rebuild(); });
            s.button(x + 412, y, 110, "HUD values: " + onOff(c.showValues), () -> { c.showValues = !c.showValues; s.rebuild(); });
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
            s.button(x + 188, y4, 60, "Set", () -> {
                if (!hpValue.getText().isBlank()) c.huntPointValue = Parse.money(hpValue.getText());
                if (!tokenValue.getText().isBlank()) c.tokenValue = Parse.money(tokenValue.getText());
                c.sanitize();
            });
            bossSpecies = s.field(x + 256, y4, 90, "boss species");
            s.button(x + 350, y4, 80, "Boss: stone", () -> logBoss(true));
            s.button(x + 432, y4, 80, "Boss: none", () -> logBoss(false));
            int y5 = y4 + 22;
            allow = s.field(x, y5, 150, "private server address");
            s.button(x + 154, y5, 150, "Allow automation there", () -> {
                String a = allow.getText().trim();
                if (a.isEmpty()) throw new IllegalArgumentException("Enter your private server's address");
                if (a.toLowerCase(Locale.ROOT).contains("cobblegalaxy")) throw new IllegalArgumentException("CobbleGalaxy's rules ban automation — not allowed");
                c.privateServerAllowlist.add(a);
                s.flash("§eAllowlisted " + a + " — only do this for a server you own or where automation is explicitly permitted.");
            });
            s.button(x + 308, y5, 90, "Clear allowlist", () -> c.privateServerAllowlist.clear());
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
            out.add(Line.t("Hunt Point value: " + Money.fmt(c.huntPointValue) + " · token value: " + Money.fmt(c.tokenValue) + " · travel " + c.travelMinutes + " min"));
            out.add(Line.h("AUTOMATION (private worlds only)"));
            out.add(Line.t("Allowlist: " + (c.privateServerAllowlist.isEmpty() ? "empty (single-player only)" : String.join(", ", c.privateServerAllowlist))));
            out.add(Line.t(String.format(Locale.ROOT, "Limits: %d min/run · %d balls/run · %d balls/target · stop below %.0f HP", c.safety.maxRunMinutes, c.safety.maxBallsPerRun, c.safety.maxBallsPerTarget, c.safety.minHealth)));
            out.add(Line.warn("CobbleGalaxy's rules prohibit macros and automation: AutoHunt/AutoTrain are always refused there."));
            if (CobbleGalaxyBridge.present()) {
                out.add(Line.h("COBBLEGALAXY BRIDGE (read-only)"));
                out.add(Line.t("hunts " + ago(CobbleGalaxyBridge.lastHuntSync) + " · GTS " + ago(CobbleGalaxyBridge.lastGtsCapture) + " · dex " + ago(CobbleGalaxyBridge.lastDexSync) + " · PokéBuilder " + ago(CobbleGalaxyBridge.lastBuilderSync)));
                if (!CobbleGalaxyBridge.disabledFeatures().isEmpty()) out.add(Line.warn("Disabled after errors: " + CobbleGalaxyBridge.disabledFeatures()));
            }
            out.add(Line.dim("Data folder: " + g().dir));
        }
    }
}
