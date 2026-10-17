package net.minecraft.text;
public interface StringVisitable {
    default String getString() { throw new UnsupportedOperationException(); }
}
