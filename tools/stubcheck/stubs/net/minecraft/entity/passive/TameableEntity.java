package net.minecraft.entity.passive;
public abstract class TameableEntity extends AnimalEntity implements net.minecraft.entity.Tameable {
    @Override public java.util.UUID getOwnerUuid() { throw new UnsupportedOperationException(); }
    @Override public net.minecraft.entity.LivingEntity getOwner() { throw new UnsupportedOperationException(); }
}
