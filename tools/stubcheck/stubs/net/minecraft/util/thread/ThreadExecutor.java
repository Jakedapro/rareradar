package net.minecraft.util.thread;
public abstract class ThreadExecutor<R extends Runnable> implements java.util.concurrent.Executor {
    @Override public void execute(Runnable r) { throw new UnsupportedOperationException(); }
}
