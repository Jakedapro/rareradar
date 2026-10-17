package net.minecraft.component;
public interface ComponentHolder {
    default <T> T get(ComponentType<? extends T> type) { throw new UnsupportedOperationException(); }
}
