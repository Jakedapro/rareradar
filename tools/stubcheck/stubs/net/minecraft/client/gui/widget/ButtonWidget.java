package net.minecraft.client.gui.widget;
public class ButtonWidget extends PressableWidget {
    protected ButtonWidget(int x, int y, int w, int h, net.minecraft.text.Text message, PressAction onPress, Object narration) { super(x, y, w, h, message); }
    public static Builder builder(net.minecraft.text.Text message, PressAction onPress) { throw new UnsupportedOperationException(); }
    public interface PressAction { void onPress(ButtonWidget button); }
    public static class Builder {
        public Builder dimensions(int x, int y, int width, int height) { throw new UnsupportedOperationException(); }
        public ButtonWidget build() { throw new UnsupportedOperationException(); }
    }
}
