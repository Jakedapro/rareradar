package net.minecraft.client.gui.widget;
public abstract class ClickableWidget implements net.minecraft.client.gui.Drawable, net.minecraft.client.gui.Element, net.minecraft.client.gui.Selectable {
    public ClickableWidget(int x, int y, int w, int h, net.minecraft.text.Text message) {}
    public boolean active = true;
    public int getX() { throw new UnsupportedOperationException(); }
    public int getY() { throw new UnsupportedOperationException(); }
    public void setX(int x) { throw new UnsupportedOperationException(); }
    public void setY(int y) { throw new UnsupportedOperationException(); }
    public int getWidth() { throw new UnsupportedOperationException(); }
    public void setWidth(int w) { throw new UnsupportedOperationException(); }
    public void setMessage(net.minecraft.text.Text message) { throw new UnsupportedOperationException(); }
    @Override public void render(net.minecraft.client.gui.DrawContext ctx, int mouseX, int mouseY, float delta) {}
}
