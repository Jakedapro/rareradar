package net.minecraft.client.gui.widget;
public abstract class SliderWidget extends ClickableWidget {
    protected double value;
    public SliderWidget(int x, int y, int width, int height, net.minecraft.text.Text text, double value) { super(x, y, width, height, text); }
    protected abstract void updateMessage();
    protected abstract void applyValue();
}
