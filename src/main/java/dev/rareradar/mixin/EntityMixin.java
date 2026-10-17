package dev.rareradar.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import dev.rareradar.Rarity;
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
        if (RareRadarClient.enabled && (Object) this instanceof PokemonEntity pe) {
            Rarity r = Rarity.of(pe);
            if (r != null) cir.setReturnValue(r.color);
        }
    }
}
