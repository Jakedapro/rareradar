package dev.rareradar;

import dev.rareradar.core.market.Opportunity;
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
        Opportunity o = g.opportunities.checkListing(p, System.currentTimeMillis());
        if (o == null || !alerted.add(p.id) || alertsThisSession >= 30) return;
        alertsThisSession++;
        var typical = g.valuator.bucketValue(p.key(), System.currentTimeMillis());
        String fee;
        if (Double.isFinite(g.cfg.gtsFeePercent) && typical.known()) {
            // Gross opportunity = typical sale × (1 − fee) − asking price. Theoretical: resale is not guaranteed.
            double net = typical.value() * (1 - g.cfg.gtsFeePercent / 100) - p.price;
            if (net <= 0) return; // not an opportunity once the fee is counted
            fee = String.format(java.util.Locale.ROOT, " Theoretical margin after your %.1f%% fee estimate: %s.", g.cfg.gtsFeePercent, dev.rareradar.core.util.Money.fmt(net));
        } else fee = " (GTS fee unknown — not deducted)";
        RareRadarClient.LOG.info("[Trade assistant] {} — {}", o.subject(), o.explanation());
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("§6[RareRadar] Possible underpriced listing: " + o.subject()
                + " — " + o.explanation() + fee + " §7Not a guarantee; check IVs/nature before buying."), false);
    }
}
