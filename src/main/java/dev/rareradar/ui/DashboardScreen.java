package dev.rareradar.ui;

import dev.rareradar.Galaxy;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * "RareRadar V3 — Galaxy Profit Engine" dashboard. Dark panel, tab strip, per-tab input widgets and a scrollable
 * text body. Keyboard: ←/→ or Tab switch pages, ↑/↓/PgUp/PgDn scroll, Esc closes. Text is rebuilt twice a second,
 * not every frame, to keep overhead low.
 */
public class DashboardScreen extends Screen {
    private static int lastTab;
    private final List<Tab> tabs = List.of(new Tabs.Overview(), new Tabs.Radar(), new Tabs.Economy(), new Tabs.Market(),
            new Tabs.Hunts(), new Tabs.Breeding(), new Tabs.Dex(), new Tabs.Spawns(), new Tabs.Inventory(),
            new Tabs.History(), new Tabs.Automation(), new Tabs.Config());
    private int tab = Math.min(lastTab, 11);
    private int scroll;
    private int contentTop;
    private final List<dev.rareradar.core.report.Line> lines = new ArrayList<>();
    private int refreshIn;
    private String flash = "";
    private int flashTicks;

    public DashboardScreen() { super(Text.literal("RareRadar V3 — Galaxy Profit Engine")); }

    @Override
    protected void init() {
        int x = 10, y = 22, bw = Math.max(48, (width - 20) / tabs.size() - 2);
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            ButtonWidget b = ButtonWidget.builder(Text.literal((i == tab ? "§e" : "") + tabs.get(i).name()), btn -> select(idx))
                    .dimensions(x, y, bw, 16).build();
            addDrawableChild(b);
            x += bw + 2;
            if (x + bw > width - 10) { x = 10; y += 18; }
        }
        y += 22;
        int used = tabs.get(tab).widgets(this, 12, y, width - 24);
        contentTop = y + used + (used > 0 ? 4 : 0);
        refreshIn = 0;
    }

    private void select(int i) {
        tab = i;
        lastTab = i;
        scroll = 0;
        clearAndInit();
    }

    // ----- helpers used by tabs
    public ButtonWidget button(int x, int y, int w, String label, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), btn -> {
            try { action.run(); }
            catch (IllegalArgumentException e) { flash("§c" + e.getMessage()); }
            catch (RuntimeException e) { flash("§cError: " + e); dev.rareradar.RareRadarClient.LOG.warn("Dashboard action failed", e); }
            refreshIn = 0;
        }).dimensions(x, y, w, 18).build();
        return addDrawableChild(b);
    }

    public TextFieldWidget field(int x, int y, int w, String placeholder) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(placeholder));
        f.setMaxLength(128);
        f.setPlaceholder(Text.literal("§7" + placeholder));
        return addDrawableChild(f);
    }

    public void flash(String msg) { flash = msg; flashTicks = 160; }
    public void rebuild() { clearAndInit(); }
    public void rebuildLater() { refreshIn = 0; }
    public Galaxy g() { return Galaxy.get(); }

    @Override
    public void tick() {
        if (flashTicks > 0) flashTicks--;
        Galaxy.get().adoptModel();
    }

    /** Plain dark panel instead of the 1.21 blur (cheaper, and keeps text crisp). Screen.render calls this first. */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xE0101018);
        ctx.fill(6, contentTop - 4, width - 6, height - 18, 0x60202638);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "§lRARERADAR V3§r  ·  GALAXY PROFIT ENGINE", width / 2, 8, 0x8FD3FF);
        if (--refreshIn <= 0) {
            lines.clear();
            try { tabs.get(tab).lines(lines); }
            catch (RuntimeException e) { lines.add(dev.rareradar.core.report.Line.warn("This page failed to build: " + e)); dev.rareradar.RareRadarClient.LOG.warn("Dashboard tab failed", e); }
            refreshIn = 30;
        }
        int lineH = 10, y = contentTop, max = (height - 22 - contentTop) / lineH;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - max)));
        for (int i = scroll; i < lines.size() && i < scroll + max; i++, y += lineH) {
            var l = lines.get(i);
            if (!l.text().isEmpty()) ctx.drawTextWithShadow(textRenderer, l.text(), 12, y, Tab.color(l.kind()));
        }
        if (lines.size() > max) ctx.drawTextWithShadow(textRenderer, "↕ " + (scroll + 1) + "-" + Math.min(lines.size(), scroll + max) + "/" + lines.size(), width - 70, height - 14, 0x777777);
        String foot = flashTicks > 0 ? flash : "§7←/→ pages · ↑/↓ scroll · estimates show (range, confidence) · nothing here sends commands to the server";
        ctx.drawTextWithShadow(textRenderer, foot, 10, height - 14, 0xCCCCCC);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean typing = getFocused() instanceof TextFieldWidget;
        if (!typing) {
            if (key == GLFW.GLFW_KEY_RIGHT || (key == GLFW.GLFW_KEY_TAB && (mods & GLFW.GLFW_MOD_SHIFT) == 0)) { select((tab + 1) % tabs.size()); return true; }
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_TAB) { select((tab + tabs.size() - 1) % tabs.size()); return true; }
            if (key == GLFW.GLFW_KEY_DOWN) { scroll++; return true; }
            if (key == GLFW.GLFW_KEY_UP) { scroll = Math.max(0, scroll - 1); return true; }
            if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll += 10; return true; }
            if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll = Math.max(0, scroll - 10); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        scroll = Math.max(0, scroll - (int) Math.signum(v) * 3);
        return true;
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void removed() { Galaxy.get().saveConfig(); }
}
