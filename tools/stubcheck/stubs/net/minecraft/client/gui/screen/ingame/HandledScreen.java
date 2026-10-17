package net.minecraft.client.gui.screen.ingame;
// yarn: HandledScreen<T extends ScreenHandler> extends Screen implements ScreenHandlerProvider<T>; FIELD handler
public abstract class HandledScreen<T extends net.minecraft.screen.ScreenHandler> extends net.minecraft.client.gui.screen.Screen implements ScreenHandlerProvider<T> {
    protected final T handler = null;
    protected HandledScreen(T handler, net.minecraft.entity.player.PlayerInventory inv, net.minecraft.text.Text title) { super(title); }
    @Override public T getScreenHandler() { throw new UnsupportedOperationException(); }
}
