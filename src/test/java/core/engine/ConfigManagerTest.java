package core.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {

    @Test
    void loadCreatesDefaultFileWhenMissing(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.json");
        ConfigManager manager = ConfigManager.load(cfg);
        assertTrue(Files.exists(cfg), "a default config file should be written");
        assertEquals(22101, manager.current().network().dispatch().port());
        manager.close();
    }

    @Test
    void reloadAppliesValidChangeAndNotifiesListeners(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.json");
        ConfigManager manager = ConfigManager.load(cfg);
        AtomicReference<EngineConfig> seen = new AtomicReference<>();
        manager.addListener((prev, now) -> seen.set(now));

        Files.writeString(cfg, "{\"network\":{\"dispatch\":{\"port\":9321}}}", StandardCharsets.UTF_8);
        ConfigManager.ReloadResult result = manager.reload();

        assertEquals(ConfigManager.Outcome.RELOADED, result.outcome());
        assertEquals(9321, manager.current().network().dispatch().port());
        assertEquals(9321, seen.get().network().dispatch().port());
        manager.close();
    }

    @Test
    void reloadKeepsCurrentConfigWhenFileIsInvalid(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.json");
        ConfigManager manager = ConfigManager.load(cfg);
        int originalPort = manager.current().network().dispatch().port();

        Files.writeString(cfg, "{ this is not valid json ", StandardCharsets.UTF_8);
        ConfigManager.ReloadResult result = manager.reload();

        assertEquals(ConfigManager.Outcome.FAILED, result.outcome());
        assertEquals(originalPort, manager.current().network().dispatch().port(), "bad reload must not change settings");
        manager.close();
    }

    @Test
    void reloadReportsNoChangeWhenContentIsEquivalent(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.json");
        ConfigManager manager = ConfigManager.load(cfg);
        ConfigManager.ReloadResult result = manager.reload();
        assertEquals(ConfigManager.Outcome.UNCHANGED, result.outcome());
        manager.close();
    }

    @Test
    void fileWatcherTriggersReloadOnChange(@TempDir Path dir) throws Exception {
        Path cfg = dir.resolve("config.json");
        ConfigManager manager = ConfigManager.load(cfg);
        manager.startWatching(50);
        assertTrue(manager.isWatching());

        try {
            Files.writeString(cfg, "{\"network\":{\"dispatch\":{\"port\":9700}}}", StandardCharsets.UTF_8);
            boolean applied = waitFor(() -> manager.current().network().dispatch().port() == 9700, 5_000);
            assertTrue(applied, "watcher should pick up the change within the timeout");
        } finally {
            manager.stopWatching();
            manager.close();
        }
        assertFalse(manager.isWatching());
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    void writeDefaultProducesParseableFile(@TempDir Path dir) throws IOException, ConfigException {
        Path cfg = dir.resolve("nested").resolve("config.json");
        EngineConfig.writeDefault(cfg);
        assertTrue(Files.exists(cfg));
        EngineConfig parsed = EngineConfig.load(cfg);
        assertEquals(EngineConfig.defaults().toJson(), parsed.toJson());
    }
}
