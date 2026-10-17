package net.minecraft.entity.player;
public class PlayerInventory implements net.minecraft.inventory.Inventory {
    public final net.minecraft.util.collection.DefaultedList<net.minecraft.item.ItemStack> main = null;
    public final net.minecraft.util.collection.DefaultedList<net.minecraft.item.ItemStack> offHand = null;
    public int selectedSlot;
    @Override public net.minecraft.item.ItemStack getStack(int slot) { throw new UnsupportedOperationException(); }
}
