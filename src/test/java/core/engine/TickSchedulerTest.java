package core.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class TickSchedulerTest {

    @Test
    void ticksRoughlyAtTheConfiguredRate() throws InterruptedException {
        TickScheduler scheduler = new TickScheduler(100);
        AtomicLong count = new AtomicLong();
        AtomicLong lastDelta = new AtomicLong();
        scheduler.register((number, delta) -> {
            count.incrementAndGet();
            lastDelta.set(delta);
        });
        scheduler.start();
        assertTrue(scheduler.isRunning());
        Thread.sleep(400);
        scheduler.stop();

        long ticks = count.get();
        assertTrue(ticks >= 10 && ticks <= 80, "expected roughly 40 ticks in 400ms at 100 tps, got " + ticks);
        assertEquals(ticks, scheduler.tickCount());
        assertTrue(lastDelta.get() > 0);
        assertFalse(scheduler.isRunning());
    }

    @Test
    void failingTickableDoesNotStopOthers() throws InterruptedException {
        TickScheduler scheduler = new TickScheduler(200);
        AtomicInteger healthy = new AtomicInteger();
        scheduler.register((number, delta) -> {
            throw new RuntimeException("boom");
        });
        scheduler.register((number, delta) -> healthy.incrementAndGet());
        scheduler.start();
        Thread.sleep(200);
        scheduler.stop();
        assertTrue(healthy.get() > 0, "the healthy tickable must keep running");
    }

    @Test
    void statsAndRateChangesAreReflected() throws InterruptedException {
        TickScheduler scheduler = new TickScheduler(50);
        scheduler.register((number, delta) -> { });
        assertEquals(1, scheduler.tickableCount());
        scheduler.start();
        Thread.sleep(150);
        scheduler.setTicksPerSecond(25);
        scheduler.stop();

        TickScheduler.Stats stats = scheduler.stats();
        assertFalse(stats.running());
        assertEquals(25, stats.ticksPerSecond());
        assertTrue(stats.tickCount() > 0);
        assertEquals(1, stats.tickables());
        assertTrue(stats.maxTickMicros() >= stats.lastTickMicros());
    }

    @Test
    void unregisterRemovesTickable() {
        TickScheduler scheduler = new TickScheduler(10);
        Tickable t = (number, delta) -> { };
        scheduler.register(t);
        scheduler.register(t); // idempotent
        assertEquals(1, scheduler.tickableCount());
        assertTrue(scheduler.unregister(t));
        assertFalse(scheduler.unregister(t));
        assertEquals(0, scheduler.tickableCount());
    }

    @Test
    void rejectsImpossibleRates() {
        assertThrows(IllegalArgumentException.class, () -> new TickScheduler(0));
        assertThrows(IllegalArgumentException.class, () -> new TickScheduler(1001));
    }

    @Test
    void startAndStopAreIdempotent() {
        TickScheduler scheduler = new TickScheduler(10);
        scheduler.start();
        scheduler.start();
        assertTrue(scheduler.isRunning());
        scheduler.stop();
        scheduler.stop();
        assertFalse(scheduler.isRunning());
    }
}
