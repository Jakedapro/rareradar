package dev.rareradar;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class SettingsScreen extends Screen {
    private static final int COL_W = 150, ROW_H = 24;

    public SettingsScreen() {
        super(Text.literal("Rare Radar"));
    }

    private static class IntSlider extends SliderWidget {
        private final String label; private final int min, max, step; private final IntConsumer set;
        IntSlider(int x, int y, int w, String label, int min, int max, int step, int cur, IntConsumer set) {
            super(x, y, w, 20, Text.empty(), (double) (cur - min) / (max - min));
            this.label = label; this.min = min; this.max = max; this.step = step; this.set = set;
            updateMessage();
        }
        private int value() {
            int v = min + (int) Math.round(this.value * (max - min));
            return Math.max(min, Math.min(max, Math.round((float) v / step) * step));
        }
        @Override protected void updateMessage() { setMessage(Text.literal(label + ": " + value())); }
        @Override protected void applyValue() { set.accept(value()); }
    }

    private ButtonWidget toggle(int x, int y, String label, BooleanSupplier get, Consumer<Boolean> set) {
        return ButtonWidget.builder(txt(label, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(txt(label, get.getAsBoolean()));
            Settings.save();
        }).dimensions(x, y, COL_W, 20).build();
    }

    private static Text txt(String label, boolean on) {
        return Text.literal(label + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    protected void init() {
        Settings s = Settings.S;
        int left = width / 2 - COL_W - 5, right = width / 2 + 5, y = 40;

        addDrawableChild(toggle(left, y, "Radar", () -> RareRadarClient.enabled, v -> { RareRadarClient.enabled = v; s.radarEnabled = v; }));
        addDrawableChild(toggle(right, y, "Glow outlines", () -> s.outlines, v -> s.outlines = v));
        y += ROW_H;
        addDrawableChild(toggle(left, y, "HUD list", () -> s.hudList, v -> s.hudList = v));
        addDrawableChild(new IntSlider(right, y, COL_W, "HUD rows", 1, 20, 1, s.hudRows, v -> s.hudRows = v));
        y += ROW_H;
        addDrawableChild(new IntSlider(left, y, COL_W * 2 + 10, "Search range (blocks)", 16, 1000, 8, s.scanRange, v -> s.scanRange = v));
        y += ROW_H + 6;

        // Tier toggles now drive the V3 rarity engine (config/rareradar/v3.json); the cache is cleared so they apply at once.
        var r = Galaxy.get().cfg.rarity;
        addDrawableChild(toggle(left, y, "Shiny", () -> r.shiny, v -> { r.shiny = s.shiny = v; RadarScanner.clear(); }));
        addDrawableChild(toggle(right, y, "Legendary", () -> r.legendary, v -> { r.legendary = s.legendary = v; RadarScanner.clear(); }));
        y += ROW_H;
        addDrawableChild(toggle(left, y, "Mythical", () -> r.mythical, v -> { r.mythical = s.mythical = v; RadarScanner.clear(); }));
        addDrawableChild(toggle(right, y, "Ultra Beast", () -> r.ultraBeast, v -> { r.ultraBeast = s.ultraBeast = v; RadarScanner.clear(); }));
        y += ROW_H;
        addDrawableChild(toggle(left, y, "Paradox", () -> r.paradox, v -> { r.paradox = s.paradox = v; RadarScanner.clear(); }));
        addDrawableChild(toggle(right, y, "Watchlist", () -> r.watchlist, v -> { r.watchlist = v; RadarScanner.clear(); }));
        y += ROW_H + 6;

        addDrawableChild(new IntSlider(left, y, COL_W, "Throw range", 3, 20, 1, s.throwRange, v -> s.throwRange = v));
        addDrawableChild(new IntSlider(right, y, COL_W, "Train range", 3, 15, 1, s.trainRange, v -> s.trainRange = v));
        y += ROW_H;
        addDrawableChild(new IntSlider(left, y, COL_W, "Level window", 0, 30, 1, s.levelWindow, v -> s.levelWindow = v));
        addDrawableChild(new IntSlider(right, y, COL_W, "Min lead HP %", 10, 100, 5, s.minLeadHpPercent, v -> s.minLeadHpPercent = v));
        y += ROW_H + 10;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close()).dimensions(width / 2 - 50, y, 100, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("The game only knows about Pokemon the server has sent you, so far-away ones won't show."),
                width / 2, height - 14, 0x999999);
    }

    @Override
    public void removed() {
        Settings.save();
        Galaxy.get().saveConfig();
    }
}
