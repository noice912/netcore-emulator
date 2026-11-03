package core.engine;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentStoreTest {

    @Test
    void saveThenLoadRoundTripsTypedValues(@TempDir Path dir) throws IOException {
        MemoryStore source = new MemoryStore();
        source.put("players", "alice", 42L);
        source.put("players", "bob", "a name");
        source.put("flags", "enabled", true);
        source.put("stats", "ratio", 0.5d);
        source.put("world", "spawn", Map.of("x", 1, "y", 2));
        PersistentStore saver = new PersistentStore(source, dir, List.of());
        assertEquals(5, saver.save());
        assertTrue(Files.exists(saver.file()));
        assertFalse(Files.exists(dir.resolve(PersistentStore.FILE_NAME + ".tmp")), "temp file is renamed away");

        MemoryStore target = new MemoryStore();
        PersistentStore loader = new PersistentStore(target, dir, List.of());
        assertEquals(5, loader.load());
        assertEquals(42L, target.get("players", "alice", Long.class).orElseThrow());
        assertEquals("a name", target.get("players", "bob", String.class).orElseThrow());
        assertEquals(true, target.get("flags", "enabled", Boolean.class).orElseThrow());
        assertEquals(0.5d, target.get("stats", "ratio", Double.class).orElseThrow());
        JsonElement spawn = target.get("world", "spawn", JsonElement.class).orElseThrow();
        assertEquals(1, spawn.getAsJsonObject().get("x").getAsInt());
        assertEquals(2, spawn.getAsJsonObject().get("y").getAsInt());
    }

    @Test
    void saveIfDirtyOnlyWritesWhenSomethingChanged(@TempDir Path dir) throws IOException {
        MemoryStore store = new MemoryStore();
        PersistentStore persistent = new PersistentStore(store, dir, List.of());
        store.put("ns", "k", 1L);
        assertTrue(persistent.saveIfDirty());
        assertFalse(persistent.saveIfDirty(), "nothing changed since the last save");
        store.increment("ns", "k", 1);
        assertTrue(persistent.saveIfDirty());
        store.remove("ns", "k");
        assertTrue(persistent.saveIfDirty());
        assertTrue(persistent.lastSaveMillis() > 0);
    }

    @Test
    void unsupportedValueTypesAreSkippedNotFatal(@TempDir Path dir) throws IOException {
        MemoryStore store = new MemoryStore();
        store.put("ns", "object", new Object());
        store.put("ns", "number", 7L);
        PersistentStore persistent = new PersistentStore(store, dir, List.of());
        assertEquals(1, persistent.save());

        MemoryStore target = new MemoryStore();
        assertEquals(1, new PersistentStore(target, dir, List.of()).load());
        assertTrue(target.get("ns", "object").isEmpty());
        assertEquals(7L, target.get("ns", "number", Long.class).orElseThrow());
    }

    @Test
    void namespaceFilterLimitsWhatIsPersisted(@TempDir Path dir) throws IOException {
        MemoryStore store = new MemoryStore();
        store.put("keep", "a", 1L);
        store.put("drop", "b", 2L);
        assertEquals(1, new PersistentStore(store, dir, List.of("keep")).save());

        MemoryStore target = new MemoryStore();
        new PersistentStore(target, dir, List.of()).load();
        assertTrue(target.contains("keep", "a"));
        assertFalse(target.contains("drop", "b"));
    }

    @Test
    void loadWithoutAFileIsZeroAndNotAnError(@TempDir Path dir) throws IOException {
        PersistentStore persistent = new PersistentStore(new MemoryStore(), dir.resolve("missing"), List.of());
        assertEquals(0, persistent.load());
    }

    @Test
    void corruptFileFailsLoadWithIoException(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(PersistentStore.FILE_NAME), "{ not json", StandardCharsets.UTF_8);
        PersistentStore persistent = new PersistentStore(new MemoryStore(), dir, List.of());
        IOException e = assertThrows(IOException.class, persistent::load);
        assertTrue(e.getMessage().contains("corrupt"));
    }

    @Test
    void closeFlushesUnsavedChanges(@TempDir Path dir) throws IOException {
        MemoryStore store = new MemoryStore();
        PersistentStore persistent = new PersistentStore(store, dir, List.of());
        store.put("ns", "k", "v");
        persistent.close();
        assertTrue(Files.exists(persistent.file()));
        MemoryStore target = new MemoryStore();
        new PersistentStore(target, dir, List.of()).load();
        assertEquals("v", target.get("ns", "k", String.class).orElseThrow());
    }
}
