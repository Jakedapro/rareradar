package org.spongepowered.asm.mixin.injection.callback;
public class CallbackInfoReturnable<R> extends CallbackInfo {
    public void setReturnValue(R returnValue) { throw new UnsupportedOperationException(); }
    public R getReturnValue() { throw new UnsupportedOperationException(); }
}
