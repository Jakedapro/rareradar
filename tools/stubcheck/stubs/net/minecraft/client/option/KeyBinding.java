package net.minecraft.client.option;
public class KeyBinding implements Comparable<KeyBinding> {
    public KeyBinding(String translationKey, int code, String category) { throw new UnsupportedOperationException(); }
    public KeyBinding(String translationKey, net.minecraft.client.util.InputUtil.Type type, int code, String category) { throw new UnsupportedOperationException(); }
    public static void onKeyPressed(net.minecraft.client.util.InputUtil.Key key) { throw new UnsupportedOperationException(); }
    public boolean wasPressed() { throw new UnsupportedOperationException(); }
    public void setPressed(boolean pressed) { throw new UnsupportedOperationException(); }
    public String getTranslationKey() { throw new UnsupportedOperationException(); }
    public String getBoundKeyTranslationKey() { throw new UnsupportedOperationException(); }
    @Override public int compareTo(KeyBinding o) { throw new UnsupportedOperationException(); }
}
