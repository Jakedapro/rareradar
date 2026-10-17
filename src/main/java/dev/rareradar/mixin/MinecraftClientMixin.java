package dev.rareradar.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.rareradar.RareRadarClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void rareradar$outline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (RareRadarClient.enabled && dev.rareradar.Settings.S.outlines && entity instanceof PokemonEntity pe && dev.rareradar.Rarity.of(pe) != null) {
            cir.setReturnValue(true);
        }
    }
}
