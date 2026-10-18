package dev.rareradar;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import dev.rareradar.core.economy.BalanceTracker;
import dev.rareradar.core.economy.Transaction;
import dev.rareradar.core.economy.TransactionKind;
import dev.rareradar.core.economy.TransactionRule;
import dev.rareradar.core.market.GtsTextParser;
import dev.rareradar.core.market.PriceObservation;
import dev.rareradar.core.util.Log;
import net.minecraft.client.MinecraftClient;

/**
 * Automatic transaction tracking from what the client actually receives:
 * <ul>
 *   <li>server (system) messages matching a format the player labelled on the Money page, learned from a real
 *       message — unlabelled money messages are only collected as candidates, never counted;</li>
 *   <li>the balance shown on the server's scoreboard sidebar, whose changes confirm labelled transactions and give
 *       an observed net for the session.</li>
 * </ul>
 * Read-only: no commands are sent and nothing is clicked.
 */
public final class MoneyCapture {
    /** A balance change within this window of a labelled message confirms it. */
    static final long CONFIRM_WINDOW_MS = 5_000;
    private static int ticks;

    private MoneyCapture() {}

    static boolean isSpecies(String id) {
        try { return PokemonSpecies.getByName(id) != null; }
        catch (LinkageError | RuntimeException e) { CobblemonCompat.warnOnce("speciesLookup", e); return false; }
    }

    /**
     * Handles one system message. Returns true when it matched a labelled format (the GTS listing parser then skips
     * it, so a labelled sale is not also stored as a separate listing/sale row).
     */
    static boolean onSystemMessage(String raw) {
        Galaxy g = Galaxy.get();
        if (g == null || !g.cfg.trackMoney) return false;
        String msg = GtsTextParser.clean(raw);
        long now = System.currentTimeMillis();
        for (TransactionRule r : g.cfg.transactionRules) {
            TransactionRule.Match m = r.match(msg);
            if (m == null) continue;
            Transaction t = new Transaction();
            t.ts = now; t.kind = r.kind; t.amount = m.amount(); t.subject = m.subject(); t.message = msg; t.ruleId = r.id;
            var cur = g.sessions.current();
            t.session = cur == null ? null : cur.id;
            if (!g.transactions.add(t)) return true;
            if (t.kind.sign > 0) g.sessions.reward(t.amount); else g.sessions.spend(t.amount);
            recordMarketEvidence(g, t);
            reconcile(g);
            Log.info("[Money] " + t.kind.label + " " + Math.round(t.amount) + (t.subject == null ? "" : " (" + t.subject + ")") + " from: " + msg);
            return true;
        }
        // Not labelled: keep as a candidate unless it is a GTS listing broadcast the GTS parser already handles.
        g.moneyMessages.offer(msg, now, MoneyCapture::isGtsListing);
        return false;
    }

    private static boolean isGtsListing(String s) {
        GtsTextParser.Result r = GtsTextParser.parseAnnouncement(s, MoneyCapture::isSpecies);
        return r.status() == GtsTextParser.Status.PARSED && r.kind() == PriceObservation.Kind.LISTING;
    }

    /** A labelled STS payout or own GTS sale naming a species is real price evidence for the market database. */
    private static void recordMarketEvidence(Galaxy g, Transaction t) {
        if (t.subject == null || !isSpecies(t.subject.replace(' ', '-'))) return;
        PriceObservation.Kind kind = switch (t.kind) {
            case STS_PAYOUT -> PriceObservation.Kind.STS;
            case GTS_SALE -> PriceObservation.Kind.SALE;
            case GTS_PURCHASE -> PriceObservation.Kind.PURCHASE;
            default -> null;
        };
        if (kind == null) return;
        PriceObservation p = PriceObservation.of(t.subject.replace(' ', '-'), false, kind, t.amount, t.ts);
        p.source = PriceObservation.Source.OWN_TRADE;
        p.note = "money:" + t.ruleId + ":" + t.ts + " (labelled server message; shiny/IVs not stated)";
        if (p.validate() == null) g.market.add(p);
    }

    /** Called every client tick; reads the sidebar once a second. */
    public static void tick(MinecraftClient client) {
        if (++ticks % 20 != 0) return;
        Galaxy g = Galaxy.get();
        if (g == null || !g.cfg.trackMoney || client.world == null) return;
        BalanceTracker.Change c = g.balance.observe(SidebarReader.lines(client), System.currentTimeMillis());
        if (c != null) reconcile(g);
        var cur = g.sessions.current();
        if (cur != null && Double.isFinite(g.balance.sessionNet())) cur.balanceNet = g.balance.sessionNet();
    }

    private static void reconcile(Galaxy g) {
        long since = System.currentTimeMillis() - 10 * CONFIRM_WINDOW_MS;
        if (BalanceTracker.reconcile(g.transactions.since(since), g.balance.changes(), CONFIRM_WINDOW_MS) > 0) g.io.submit(g.transactions::save);
    }

    /** Learns a rule from a candidate message and stores it in the config. */
    public static TransactionRule label(String message, TransactionKind kind) {
        Galaxy g = Galaxy.get();
        TransactionRule r = TransactionRule.learn(message, kind, MoneyCapture::isSpecies, System.currentTimeMillis());
        if (r == null) throw new IllegalArgumentException("That message doesn't contain exactly one money amount");
        g.cfg.transactionRules.add(r);
        g.saveConfig();
        return r;
    }

    public static void onDisconnect() { Galaxy g = Galaxy.get(); if (g != null) g.balance.resetSession(); }
}
