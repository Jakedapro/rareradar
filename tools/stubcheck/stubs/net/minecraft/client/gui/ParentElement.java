package net.minecraft.client.gui;
public interface ParentElement extends Element {
    Element getFocused();
    @Override default boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    @Override default boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { return false; }
}
