package dev.rareradar.mixin;

import dev.rareradar.RadarScanner;
import dev.rareradar.RareRadarClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    @Inject(method = "getTeamColorValue", at = @At("HEAD"), cancellable = true)
    private void rareradar$color(CallbackInfoReturnable<Integer> cir) {
        if (!RareRadarClient.enabled || !dev.rareradar.Settings.S.outlines) return;
        int c = RadarScanner.color(((Entity) (Object) this).getId());
        if (c != -1) cir.setReturnValue(c);
    }
}
