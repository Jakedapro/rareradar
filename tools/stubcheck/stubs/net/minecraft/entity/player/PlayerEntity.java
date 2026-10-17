package net.minecraft.entity.player;
public abstract class PlayerEntity extends net.minecraft.entity.LivingEntity {
    public PlayerInventory getInventory() { throw new UnsupportedOperationException(); }
    public void sendMessage(net.minecraft.text.Text message, boolean overlay) { throw new UnsupportedOperationException(); }
}
