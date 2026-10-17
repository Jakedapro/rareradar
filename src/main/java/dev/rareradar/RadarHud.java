package dev.rareradar;

import dev.rareradar.core.market.Valuation;
import dev.rareradar.core.util.Confidence;
import dev.rareradar.core.util.Money;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Radar HUD list plus the automation indicator. Reads precomputed scanner results; no world scans while rendering. */
public final class RadarHud {
    private RadarHud() {}

    static String arrow(MinecraftClient client, Vec3d target) {
        Vec3d d = target.subtract(client.player.getPos());
        double rel = MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-d.x, d.z)) - client.player.getYaw());
        if (rel > -22.5 && rel <= 22.5) return "↑";
        if (rel > 22.5 && rel <= 67.5) return "↗";
        if (rel > 67.5 && rel <= 112.5) return "→";
        if (rel > 112.5 && rel <= 157.5) return "↘";
        if (rel > -67.5 && rel <= -22.5) return "↖";
        if (rel > -112.5 && rel <= -67.5) return "←";
        if (rel > -157.5 && rel <= -112.5) return "↙";
        return "↓";
    }

    /** Short "~$12.0k?" label; the trailing marks show uncertainty (? low, ?? very low/no data). */
    static String valueTag(Valuation v) {
        if (v == null || !v.total().known()) return "";
        Confidence c = v.total().confidence();
        String q = c == Confidence.HIGH ? "" : c == Confidence.MEDIUM ? "~" : "?";
        return " " + (q.equals("~") ? "~" : "") + Money.fmt(v.total().value()) + (q.equals("?") ? "?" : "");
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden) return;
        try {
            draw(ctx, client);
        } catch (RuntimeException e) {
            // Never let a HUD bug take down rendering; report once per session.
            CobblemonCompat.warnOnce("hud", e);
        }
    }

    private static void draw(DrawContext ctx, MinecraftClient client) {
        Galaxy g = Galaxy.get();
        V3Config cfg = g.cfg;
        var tr = client.textRenderer;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cfg.hudX, cfg.hudY, 0);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale, 1);
        int y = 0;
        if (AutoHunt.active) {
            String s = "● AUTO " + (AutoHunt.train ? "TRAIN" : "HUNT") + " ACTIVE — " + AutoHunt.status();
            ctx.fill(-2, -2, tr.getWidth(s) + 2, 10, 0xC0800000);
            ctx.drawTextWithShadow(tr, s, 0, 0, 0xFFFFFF);
            y += 13;
        }
        List<RadarScanner.Hit> hits = RadarScanner.hits();
        if (RareRadarClient.enabled && Settings.S.hudList && !hits.isEmpty()) {
            int rows = Math.min(Settings.S.hudRows, hits.size());
            String[] lines = new String[rows];
            int[] colors = new int[rows];
            int w = tr.getWidth("Rare Radar");
            for (int i = 0; i < rows; i++) {
                RadarScanner.Hit h = hits.get(i);
                String label = h.rarity.qualifies() ? h.rarity.primary().label : "Hunt";
                String name = h.entity.getPokemon().getSpecies().getName();
                String val = cfg.showValues && h.rarity.qualifies() ? valueTag(RadarScanner.value(h)) : "";
                lines[i] = String.format("%s %s%s [%s] %dm (%d, %d, %d)%s", arrow(client, h.entity.getPos()), h.huntTarget ? "★" : "",
                        name, label, (int) h.distance, h.entity.getBlockX(), h.entity.getBlockY(), h.entity.getBlockZ(), val);
                colors[i] = h.rarity.qualifies() ? h.rarity.primary().color : 0x7CFC00;
                w = Math.max(w, tr.getWidth(lines[i]));
            }
            int height = 11 + rows * 10 + (hits.size() > rows ? 10 : 0);
            if (cfg.hudOpacity > 0) ctx.fill(-3, y - 3, w + 3, y + height + 1, cfg.hudOpacity << 24);
            ctx.drawTextWithShadow(tr, "Rare Radar", 0, y, 0xFFFFFF);
            y += 11;
            for (int i = 0; i < rows; i++) { ctx.drawTextWithShadow(tr, lines[i], 0, y, colors[i]); y += 10; }
            if (hits.size() > rows) ctx.drawTextWithShadow(tr, "+" + (hits.size() - rows) + " more", 0, y, 0xAAAAAA);
        }
        ctx.getMatrices().pop();
    }
}
