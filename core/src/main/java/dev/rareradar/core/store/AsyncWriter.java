package dev.rareradar.core.store;

import dev.rareradar.core.util.Log;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single background thread for disk writes so the game thread never blocks on I/O.
 * The queue is bounded; when it is full the task is rejected and counted rather than stalling the caller.
 */
public final class AsyncWriter implements AutoCloseable {
    private final ThreadPoolExecutor exec;
    private final AtomicLong dropped = new AtomicLong();
    private final boolean synchronous;

    private AsyncWriter(int capacity, boolean synchronous) {
        this.synchronous = synchronous;
        this.exec = synchronous ? null : new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(capacity), r -> {
                    Thread t = new Thread(r, "RareRadar-IO");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY);
                    return t;
                }, (r, e) -> dropped.incrementAndGet());
    }

    public static AsyncWriter background(int capacity) { return new AsyncWriter(capacity, false); }

    /** Runs tasks inline on the caller. For tests and for shutdown paths. */
    public static AsyncWriter inline() { return new AsyncWriter(0, true); }

    public void submit(Runnable task) {
        Runnable safe = () -> {
            try { task.run(); } catch (Throwable t) { Log.error("Background write failed", t); }
        };
        if (synchronous) safe.run(); else exec.execute(safe);
    }

    public long dropped() { return dropped.get(); }

    /** Waits until everything queued so far has been written. */
    public void flush(long timeoutMs) {
        if (synchronous) return;
        Future<?> f = exec.submit(() -> {});
        try { f.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (Exception e) { Log.warn("Flush timed out or failed: " + e); }
    }

    @Override
    public void close() {
        if (synchronous) return;
        exec.shutdown();
        try {
            if (!exec.awaitTermination(5, TimeUnit.SECONDS)) Log.warn("IO thread did not finish within 5s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
