package core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed-rate simulation loop on a single dedicated thread.
 *
 * <p>Every registered {@link Tickable} runs once per tick, in registration order, so world state
 * touched only from tickables needs no locking. A failing tickable is logged and the others still
 * run. If the loop falls more than one period behind (a long GC pause, a slow tickable) it
 * re-synchronises instead of firing a burst of catch-up ticks.
 */
public final class TickScheduler implements AutoCloseable {

    /** Point-in-time loop statistics. Times are in microseconds. */
    public record Stats(boolean running, int ticksPerSecond, long tickCount, long lastTickMicros,
                        long averageTickMicros, long maxTickMicros, long overruns, int tickables) {
    }

    private static final Logger log = LoggerFactory.getLogger(TickScheduler.class);

    private final CopyOnWriteArrayList<Tickable> tickables = new CopyOnWriteArrayList<>();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();

    private volatile int ticksPerSecond;
    private volatile boolean running;
    private volatile long lastTickNanos;
    private volatile long maxTickNanos;
    private volatile double averageTickNanos;
    private Thread thread;

    public TickScheduler(int ticksPerSecond) {
        setTicksPerSecond(ticksPerSecond);
    }

    /** Change the target rate; takes effect on the next tick. */
    public void setTicksPerSecond(int ticksPerSecond) {
        if (ticksPerSecond < 1 || ticksPerSecond > 1000) {
            throw new IllegalArgumentException("ticksPerSecond must be between 1 and 1000: " + ticksPerSecond);
        }
        this.ticksPerSecond = ticksPerSecond;
    }

    public int ticksPerSecond() {
        return ticksPerSecond;
    }

    public void register(Tickable tickable) {
        tickables.addIfAbsent(Objects.requireNonNull(tickable, "tickable"));
    }

    public boolean unregister(Tickable tickable) {
        return tickables.remove(tickable);
    }

    public int tickableCount() {
        return tickables.size();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "engine-tick");
        thread.setDaemon(true);
        thread.start();
        log.info("tick loop started at {} ticks/s with {} tickable(s)", ticksPerSecond, tickables.size());
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        log.info("tick loop stopped after {} tick(s)", tickCount.get());
    }

    public boolean isRunning() {
        return running;
    }

    public long tickCount() {
        return tickCount.get();
    }

    public Stats stats() {
        return new Stats(running, ticksPerSecond, tickCount.get(), lastTickNanos / 1_000,
                (long) (averageTickNanos / 1_000), maxTickNanos / 1_000, overruns.get(), tickables.size());
    }

    private void loop() {
        long previous = System.nanoTime();
        long next = previous + periodNanos();
        while (running) {
            long now = System.nanoTime();
            if (now < next) {
                LockSupport.parkNanos(next - now);
                continue;
            }
            long period = periodNanos();
            long delta = now - previous;
            previous = now;
            long number = tickCount.incrementAndGet();

            for (Tickable tickable : tickables) {
                try {
                    tickable.tick(number, delta);
                } catch (VirtualMachineError fatal) {
                    throw fatal;
                } catch (Throwable t) {
                    log.error("tickable {} failed on tick {}", tickable.name(), number, t);
                }
            }

            long took = System.nanoTime() - now;
            record(took, period);
            next += period;
            if (System.nanoTime() > next + period) {
                // More than a full period behind: resynchronise rather than burst-fire catch-up ticks.
                next = System.nanoTime() + period;
            }
        }
    }

    private long periodNanos() {
        return 1_000_000_000L / ticksPerSecond;
    }

    private void record(long tookNanos, long periodNanos) {
        lastTickNanos = tookNanos;
        if (tookNanos > maxTickNanos) {
            maxTickNanos = tookNanos;
        }
        averageTickNanos = averageTickNanos == 0 ? tookNanos : averageTickNanos * 0.9 + tookNanos * 0.1;
        if (tookNanos > periodNanos) {
            overruns.incrementAndGet();
        }
    }

    @Override
    public void close() {
        stop();
    }
}
