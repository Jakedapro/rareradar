package dev.rareradar.ui;

import dev.rareradar.Galaxy;
import dev.rareradar.core.report.TextWrap;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * "RareRadar V3 — Galaxy Profit Engine" dashboard. Dark panel, tab strip, per-tab input widgets and a scrollable
 * text body. Keyboard: ←/→ or Tab switch pages, ↑/↓/PgUp/PgDn scroll, Esc closes. Text is rebuilt twice a second,
 * not every frame, to keep overhead low.
 */
public class DashboardScreen extends Screen {
    private static int lastTab;
    private final List<Tab> tabs = List.of(new Tabs.Overview(), new Tabs.Radar(), new Tabs.Economy(), new Tabs.Market(), new Tabs.MoneyTab(),
            new Tabs.Hunts(), new Tabs.Breeding(), new Tabs.Dex(), new Tabs.Spawns(), new Tabs.Inventory(),
            new Tabs.History(), new Tabs.Knowledge(), new Tabs.Automation(), new LabTab(), new Tabs.Config());
    private int tab = Math.min(lastTab, tabs.size() - 1);
    private int scroll;
    private int contentTop;
    private final List<dev.rareradar.core.report.Line> lines = new ArrayList<>();
    /** {@link #lines} word-wrapped to the current width; this is what is drawn and scrolled. */
    private final List<dev.rareradar.core.report.Line> view = new ArrayList<>();
    /** Widgets the current tab added through button()/field(); re-flowed when the window is too narrow for them. */
    private final List<ClickableWidget> tabWidgets = new ArrayList<>();
    private int refreshIn;
    private String flash = "";
    private int flashTicks;

    public DashboardScreen() { super(Text.literal("RareRadar V4 — Project Autonomy")); }

    @Override
    protected void init() {
        // Tab strip: each button is as wide as its label needs, wrapping to further rows on narrow windows.
        int x = 10, y = 22, right = width - 10;
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            String label = (i == tab ? "§e" : "") + tabs.get(i).name();
            int bw = Math.max(36, textRenderer.getWidth(label) + 12);
            if (x + bw > right && x > 10) { x = 10; y += 18; }
            addDrawableChild(ButtonWidget.builder(Text.literal(label), btn -> select(idx)).dimensions(x, y, bw, 16).build());
            x += bw + 2;
        }
        y += 22;
        tabWidgets.clear();
        int used = tabs.get(tab).widgets(this, 12, y, width - 24);
        used = reflow(y, used);
        contentTop = y + used + (used > 0 ? 4 : 0);
        refreshIn = 0;
    }

    /**
     * If any widget of the tab runs past the right edge, lays the tab's widgets out again row by row (keeping the tab's
     * own row grouping, wrapping inside a row when it does not fit and shrinking anything wider than the window).
     * Returns the height used. Windows wide enough for the tab's own layout are left untouched.
     */
    private int reflow(int top, int used) {
        int left = 12, right = width - 12, avail = right - left;
        boolean overflow = false;
        for (ClickableWidget w : tabWidgets) if (w.getX() + w.getWidth() > right) { overflow = true; break; }
        if (!overflow) return used;
        TreeMap<Integer, List<ClickableWidget>> rows = new TreeMap<>();
        for (ClickableWidget w : tabWidgets) rows.computeIfAbsent(w.getY(), k -> new ArrayList<>()).add(w);
        int visY = top;
        for (List<ClickableWidget> row : rows.values()) {
            row.sort((a, b) -> Integer.compare(a.getX(), b.getX()));
            int cx = left;
            for (ClickableWidget w : row) {
                int ww = Math.min(w.getWidth(), avail);
                if (cx + ww > right && cx > left) { cx = left; visY += 22; }
                w.setX(cx);
                w.setY(visY);
                w.setWidth(ww);
                cx += ww + 4;
            }
            visY += 22;
        }
        return Math.max(used, visY - top);
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
        tabWidgets.add(b);
        return addDrawableChild(b);
    }

    public TextFieldWidget field(int x, int y, int w, String placeholder) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.literal(placeholder));
        f.setMaxLength(128);
        f.setPlaceholder(Text.literal("§7" + placeholder));
        tabWidgets.add(f);
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
        ctx.drawCenteredTextWithShadow(textRenderer, "§lRARERADAR V4§r  ·  PROJECT AUTONOMY", width / 2, 8, 0x8FD3FF);
        if (--refreshIn <= 0) {
            lines.clear();
            try { tabs.get(tab).lines(lines); }
            catch (RuntimeException e) { lines.add(dev.rareradar.core.report.Line.warn("This page failed to build: " + e)); dev.rareradar.RareRadarClient.LOG.warn("Dashboard tab failed", e); }
            wrapLines();
            refreshIn = 30;
        }
        int lineH = 10, top = contentTop, bottom = height - 22, max = Math.max(1, (bottom - top) / lineH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, view.size() - max)));
        // Everything below is clipped to the content panel so partial lines never bleed over the tabs or footer.
        ctx.enableScissor(6, top, width - 6, bottom);
        int y = top;
        for (int i = scroll; i < view.size() && i < scroll + max; i++, y += lineH) {
            var l = view.get(i);
            if (!l.text().isEmpty()) ctx.drawTextWithShadow(textRenderer, l.text(), 12, y, Tab.color(l.kind()));
        }
        ctx.disableScissor();
        if (view.size() > max) {
            // Scrollbar: thumb size and position reflect the visible window.
            int trackTop = top, trackH = bottom - top, thumbH = Math.max(12, trackH * max / view.size());
            int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, view.size() - max);
            ctx.fill(width - 8, trackTop, width - 5, bottom, 0x40FFFFFF);
            ctx.fill(width - 8, thumbY, width - 5, thumbY + thumbH, 0xA08FD3FF);
            ctx.drawTextWithShadow(textRenderer, "↕ " + (scroll + 1) + "-" + Math.min(view.size(), scroll + max) + "/" + view.size(), Math.max(10, width - 86), height - 14, 0x777777);
        }
        String foot = flashTicks > 0 ? flash : "§7←/→ pages · ↑/↓ scroll · estimates show (range, confidence) · nothing here sends commands to the server";
        int footMax = width - 100;
        if (textRenderer.getWidth(foot) > footMax) foot = flashTicks > 0 ? clip(foot, footMax) : "§7←/→ pages · ↑/↓ or wheel scroll";
        ctx.drawTextWithShadow(textRenderer, foot, 10, height - 14, 0xCCCCCC);
    }

    /** Word-wraps {@link #lines} into {@link #view} (see {@link TextWrap}). */
    private void wrapLines() {
        view.clear();
        int avail = Math.max(60, width - 36);
        for (var l : lines)
            for (String part : TextWrap.wrap(l.text(), avail, textRenderer::getWidth))
                view.add(part == l.text() ? l : new dev.rareradar.core.report.Line(l.kind(), part));
    }

    private String clip(String s, int maxPx) {
        int cut = TextWrap.cutPoint(s, maxPx - textRenderer.getWidth("…"), textRenderer::getWidth);
        return s.substring(0, Math.min(cut, s.length())) + "…";
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
