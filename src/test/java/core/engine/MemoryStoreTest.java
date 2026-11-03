package core.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryStoreTest {

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
    }

    @Test
    void putGetAndTypedGet() {
        store.put("accounts", "alice", 42);
        assertEquals(42, store.get("accounts", "alice", Integer.class).orElseThrow());
        assertTrue(store.get("accounts", "bob").isEmpty());
        assertTrue(store.get("accounts", "alice", String.class).isEmpty());
        assertEquals(99, store.getOrDefault("accounts", "missing", Integer.class, 99));
    }

    @Test
    void computeIfAbsentCreatesOnce() {
        AtomicInteger calls = new AtomicInteger();
        StringBuilder a = store.computeIfAbsent("ns", "k", StringBuilder.class, key -> {
            calls.incrementAndGet();
            return new StringBuilder("v");
        });
        StringBuilder b = store.computeIfAbsent("ns", "k", StringBuilder.class, key -> {
            calls.incrementAndGet();
            return new StringBuilder("other");
        });
        assertSame(a, b);
        assertEquals(1, calls.get());
    }

    @Test
    void computeIfAbsentTypeMismatchThrows() {
        store.put("ns", "k", "a string");
        assertThrows(IllegalStateException.class,
                () -> store.computeIfAbsent("ns", "k", Integer.class, key -> 1));
    }

    @Test
    void incrementAccumulates() {
        assertEquals(5, store.increment("counters", "hits", 5));
        assertEquals(8, store.increment("counters", "hits", 3));
        assertEquals(8L, store.get("counters", "hits", Long.class).orElseThrow());
    }

    @Test
    void incrementOnNonNumberThrows() {
        store.put("counters", "x", "not a number");
        assertThrows(IllegalStateException.class, () -> store.increment("counters", "x", 1));
    }

    @Test
    void removeKeysSizeAndNamespaces() {
        store.put("a", "1", "x");
        store.put("a", "2", "y");
        store.put("b", "1", "z");
        assertEquals(2, store.size("a"));
        assertEquals(3, store.totalEntries());
        assertTrue(store.remove("a", "1"));
        assertFalse(store.remove("a", "1"));
        assertEquals(1, store.size("a"));
        assertEquals(2, store.namespaces().size());
        assertEquals(java.util.Set.of("2"), store.keys("a"));
        assertEquals(2, store.stats().get("a") == null ? -1 : store.stats().size());
    }

    @Test
    void clearNamespaceAndAll() {
        store.put("a", "1", "x");
        store.put("b", "1", "y");
        store.clear("a");
        assertEquals(0, store.size("a"));
        assertEquals(1, store.totalEntries());
        store.clearAll();
        assertEquals(0, store.totalEntries());
    }

    @Test
    void rejectsNullAndBlankNames() {
        assertThrows(NullPointerException.class, () -> store.put("ns", "k", null));
        assertThrows(IllegalArgumentException.class, () -> store.put("", "k", "v"));
        assertThrows(IllegalArgumentException.class, () -> store.put("ns", "  ", "v"));
    }

    @Test
    void concurrentIncrementsAreConsistent() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int threads = 8;
        int perThread = 10_000;
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    store.increment("counters", "total", 1);
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals((long) threads * perThread, store.get("counters", "total", Long.class).orElseThrow());
    }
}
