package net.minecraft.entity;
public abstract class Entity implements net.minecraft.world.entity.EntityLike {
    public boolean horizontalCollision;
    public int age;
    @Override public int getId() { throw new UnsupportedOperationException(); }
    @Override public java.util.UUID getUuid() { throw new UnsupportedOperationException(); }
    @Override public net.minecraft.util.math.BlockPos getBlockPos() { throw new UnsupportedOperationException(); }
    @Override public final net.minecraft.util.math.Box getBoundingBox() { throw new UnsupportedOperationException(); }
    public double squaredDistanceTo(net.minecraft.util.math.Vec3d v) { throw new UnsupportedOperationException(); }
    public double squaredDistanceTo(Entity e) { throw new UnsupportedOperationException(); }
    public double squaredDistanceTo(double x, double y, double z) { throw new UnsupportedOperationException(); }
    public float distanceTo(Entity e) { throw new UnsupportedOperationException(); }
    public final double getX() { throw new UnsupportedOperationException(); }
    public final double getY() { throw new UnsupportedOperationException(); }
    public final double getZ() { throw new UnsupportedOperationException(); }
    public final int getBlockX() { throw new UnsupportedOperationException(); }
    public final int getBlockY() { throw new UnsupportedOperationException(); }
    public final int getBlockZ() { throw new UnsupportedOperationException(); }
    public final net.minecraft.util.math.Vec3d getPos() { throw new UnsupportedOperationException(); }
    public final net.minecraft.util.math.Vec3d getEyePos() { throw new UnsupportedOperationException(); }
    public final float getHeight() { throw new UnsupportedOperationException(); }
    public boolean isRemoved() { throw new UnsupportedOperationException(); }
    public boolean isAlive() { throw new UnsupportedOperationException(); }
    public boolean isOnGround() { throw new UnsupportedOperationException(); }
    public boolean isTouchingWater() { throw new UnsupportedOperationException(); }
    public float getYaw() { throw new UnsupportedOperationException(); }
    public float getPitch() { throw new UnsupportedOperationException(); }
    public void setYaw(float y) { throw new UnsupportedOperationException(); }
    public void setPitch(float p) { throw new UnsupportedOperationException(); }
    public int getTeamColorValue() { throw new UnsupportedOperationException(); }
    public net.minecraft.world.World getWorld() { throw new UnsupportedOperationException(); }
}
