package net.minecraft.entity;
public abstract class LivingEntity extends Entity {
    public float getHealth() { throw new UnsupportedOperationException(); }
    public boolean canSee(Entity e) { throw new UnsupportedOperationException(); }
    public void swingHand(net.minecraft.util.Hand hand) { throw new UnsupportedOperationException(); }
    public void swingHand(net.minecraft.util.Hand hand, boolean fromServer) { throw new UnsupportedOperationException(); }
}
