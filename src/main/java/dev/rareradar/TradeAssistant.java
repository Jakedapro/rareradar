package dev.rareradar;

import dev.rareradar.core.util.Money;
import dev.rareradar.core.market.PriceObservation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.HashSet;
import java.util.Set;

/**
 * Trading modes. A = observe only (default). B = assisted: newly captured listings that look underpriced against
 * confirmed-sale evidence are announced in chat with the evidence; the player decides. RareRadar never buys or sells
 * (Mode C is unsupported: there is no verified purchase interface and purchases are irreversible).
 */
public final class TradeAssistant {
    public enum Mode { OBSERVE, ASSISTED }

    private static final Set<String> alerted = new HashSet<>();
    private static int alertsThisSession;

    private TradeAssistant() {}

    public static void onNewListing(PriceObservation p) {
        Galaxy g = Galaxy.get();
        if (g == null || g.cfg.tradingMode != Mode.ASSISTED || p.kind != PriceObservation.Kind.LISTING) return;
        if (alerted.contains(p.id) || alertsThisSession >= 30) return;
        var a = g.opportunities.assess(p, g.cfg.gtsFeePercent, System.currentTimeMillis());
        boolean feeKnown = Double.isFinite(g.cfg.gtsFeePercent);
        // Alert only when even a below-typical resale profits; with an unknown fee, the same test before fee.
        boolean alert = a.verdict() == dev.rareradar.core.market.OpportunityFinder.Verdict.BUY_CANDIDATE
                || (!feeKnown && a.verdict() == dev.rareradar.core.market.OpportunityFinder.Verdict.MARGINAL && a.conservativeNet() > 0);
        if (!alert) return;
        alerted.add(p.id);
        alertsThisSession++;
        String text = String.format(java.util.Locale.ROOT,
                "%s%s asking %s · typical sale %s · theoretical margin %s (below-typical resale: %s)%s · ~%s per sale. ",
                p.species, Boolean.TRUE.equals(p.shiny) ? " (shiny)" : "", Money.fmt(p.price), Money.range(a.resale()),
                Money.fmt(a.expectedNet()), Money.fmt(a.conservativeNet()),
                feeKnown ? String.format(java.util.Locale.ROOT, " after your %.1f%% fee", g.cfg.gtsFeePercent) : " BEFORE fee (fee unknown)",
                Double.isFinite(a.daysToSell()) ? String.format(java.util.Locale.ROOT, "%.1f days", a.daysToSell()) : "? days");
        RareRadarClient.LOG.info("[Trade assistant] {}", text);
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("§6[RareRadar] Possible underpriced listing: " + text
                + "§7Based on " + a.resale().basis() + ". Not a guarantee; check IVs/nature before buying."), false);
    }
}
