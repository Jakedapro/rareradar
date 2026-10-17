package net.minecraft.client.gui.screen;
public abstract class Screen extends net.minecraft.client.gui.AbstractParentElement implements net.minecraft.client.gui.Drawable {
    protected final net.minecraft.text.Text title = null;
    public int width, height;
    protected net.minecraft.client.font.TextRenderer textRenderer;
    protected Screen(net.minecraft.text.Text title) {}
    protected void init() {}
    @Override public void render(net.minecraft.client.gui.DrawContext ctx, int mouseX, int mouseY, float delta) {}
    public void renderBackground(net.minecraft.client.gui.DrawContext ctx, int mouseX, int mouseY, float delta) {}
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    public boolean shouldPause() { return true; }
    public void removed() {}
    public void tick() {}
    public void close() {}
    protected <T extends net.minecraft.client.gui.Element & net.minecraft.client.gui.Drawable & net.minecraft.client.gui.Selectable> T addDrawableChild(T child) { throw new UnsupportedOperationException(); }
    protected void clearAndInit() {}
}
