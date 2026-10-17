package net.minecraft.client.gui.widget;
public class TextFieldWidget extends ClickableWidget {
    public TextFieldWidget(net.minecraft.client.font.TextRenderer r, int x, int y, int w, int h, net.minecraft.text.Text text) { super(x, y, w, h, text); }
    public TextFieldWidget(net.minecraft.client.font.TextRenderer r, int x, int y, int w, int h, TextFieldWidget copyFrom, net.minecraft.text.Text text) { super(x, y, w, h, text); }
    public TextFieldWidget(net.minecraft.client.font.TextRenderer r, int w, int h, net.minecraft.text.Text text) { super(0, 0, w, h, text); }
    public void setText(String s) { throw new UnsupportedOperationException(); }
    public String getText() { throw new UnsupportedOperationException(); }
    public void setMaxLength(int n) { throw new UnsupportedOperationException(); }
    public void setPlaceholder(net.minecraft.text.Text t) { throw new UnsupportedOperationException(); }
}
