package net.minecraft.world.entity;
public interface EntityLike {
    int getId();
    java.util.UUID getUuid();
    net.minecraft.util.math.BlockPos getBlockPos();
    net.minecraft.util.math.Box getBoundingBox();
}
