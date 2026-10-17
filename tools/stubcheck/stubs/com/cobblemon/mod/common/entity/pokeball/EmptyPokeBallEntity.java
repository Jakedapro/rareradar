package com.cobblemon.mod.common.entity.pokeball;
public final class EmptyPokeBallEntity extends net.minecraft.entity.projectile.thrown.ThrownItemEntity {
    public enum CaptureState { NOT, HIT, FALL, CRITICAL, SHAKE, CAPTURED, BROKEN_FREE }
    public final CaptureState getCaptureState() { throw new UnsupportedOperationException(); }
}
