package net.minecraft.client.gui;
public interface Element {
    default boolean keyPressed(int keyCode, int scanCode, int modifiers) { return false; }
    default boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) { return false; }
}
