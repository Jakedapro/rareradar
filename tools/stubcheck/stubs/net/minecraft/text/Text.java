package net.minecraft.text;
public interface Text extends StringVisitable {
    static MutableText literal(String s) { throw new UnsupportedOperationException(); }
    static MutableText empty() { throw new UnsupportedOperationException(); }
}
