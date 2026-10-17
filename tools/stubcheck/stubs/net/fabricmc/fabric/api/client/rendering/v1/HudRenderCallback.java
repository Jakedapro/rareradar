package net.fabricmc.fabric.api.client.rendering.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
public interface HudRenderCallback {
    Event<HudRenderCallback> EVENT = null;
    void onHudRender(DrawContext drawContext, RenderTickCounter tickCounter);
}
