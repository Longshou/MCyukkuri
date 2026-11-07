package io.github.longshou.mcyukkuri.status;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Daemon stall observer. It reads a volatile tick timestamp and the server thread, never gameplay state. */
public final class TraceWatchdog implements Runnable {
    private static final long POLL_NANOS = 1_000_000_000L;
    private static final long STALL_NANOS = 5_000_000_000L;

    public record Stall(long elapsedNanos, StackTraceElement[] stack) {}

    private final Thread serverThread;
    private final LongSupplier tickEndNanos;
    private final LongSupplier clock;
    private final Consumer<Stall> listener;
    private final StallTracker tracker = new StallTracker(STALL_NANOS);
    private volatile boolean running = true;
    private final Thread thread;

    public TraceWatchdog(Thread serverThread, LongSupplier tickEndNanos, Consumer<Stall> listener) {
        this(serverThread, tickEndNanos, System::nanoTime, listener);
    }

    TraceWatchdog(Thread serverThread, LongSupplier tickEndNanos, LongSupplier clock, Consumer<Stall> listener) {
        this.serverThread = serverThread;
        this.tickEndNanos = tickEndNanos;
        this.clock = clock;
        this.listener = listener;
        thread = new Thread(this, "mcyukkuri-trace-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public void close() {
        running = false;
        thread.interrupt();
    }

    @Override public void run() {
        while (running) {
            long elapsed = tracker.poll(clock.getAsLong(), tickEndNanos.getAsLong());
            if (elapsed > 0) listener.accept(new Stall(elapsed, serverThread.getStackTrace()));
            try {
                Thread.sleep(POLL_NANOS / 1_000_000L);
            } catch (InterruptedException interrupted) {
                if (!running) return;
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Pure timing decision, separately testable without Minecraft or a sleeping test thread. */
    static final class StallTracker {
        private final long threshold;
        private long observedTickEnd;
        private boolean reported;

        StallTracker(long threshold) { this.threshold = threshold; }

        /** Returns elapsed stall nanos once per continuous stall, or zero while healthy/already reported. */
        long poll(long now, long lastTickEnd) {
            if (lastTickEnd != observedTickEnd) {
                observedTickEnd = lastTickEnd;
                reported = false;
            }
            if (lastTickEnd == 0 || reported || now - lastTickEnd < threshold) return 0;
            reported = true;
            return now - lastTickEnd;
        }
    }
}
