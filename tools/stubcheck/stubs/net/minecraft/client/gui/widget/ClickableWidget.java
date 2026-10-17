package net.minecraft.client.gui.widget;
public abstract class ClickableWidget implements net.minecraft.client.gui.Drawable, net.minecraft.client.gui.Element, net.minecraft.client.gui.Selectable {
    public ClickableWidget(int x, int y, int w, int h, net.minecraft.text.Text message) {}
    public void setMessage(net.minecraft.text.Text message) { throw new UnsupportedOperationException(); }
    @Override public void render(net.minecraft.client.gui.DrawContext ctx, int mouseX, int mouseY, float delta) {}
}
