package core.plugin;

import core.engine.ServerEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PluginManagerTest {

    private static ServerEngine engine(Path dir, boolean pluginsEnabled) throws Exception {
        String json = """
                {
                  "network": { "bindAddress": "127.0.0.1", "dispatch": { "port": 0 }, "gateway": { "port": 0 } },
                  "admin": { "enabled": false },
                  "persistence": { "enabled": false },
                  "schemas": { "enabled": false },
                  "engine": { "consoleEnabled": false, "configWatchEnabled": false, "pluginsEnabled": %s,
                              "pluginDirectory": "%s", "ticksPerSecond": 100 }
                }
                """.formatted(pluginsEnabled, dir.resolve("plugins").toString().replace('\\', '/'));
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, json, StandardCharsets.UTF_8);
        return ServerEngine.create(cfg);
    }

    @Test
    void classpathPluginGoesThroughTheFullLifecycle(@TempDir Path dir) throws Exception {
        int loadsBefore = TestPlugin.LOADS.get();
        int startsBefore = TestPlugin.STARTS.get();
        int stopsBefore = TestPlugin.STOPS.get();
        int ticksBefore = TestPlugin.TICKS.get();

        ServerEngine engine = engine(dir, true);
        try {
            engine.start();
            List<PluginManager.PluginInfo> plugins = engine.plugins().plugins();
            assertEquals(1, plugins.size());
            assertEquals("test-plugin", plugins.get(0).name());
            assertEquals("9.9", plugins.get(0).version());
            assertEquals("classpath", plugins.get(0).source());
            assertEquals("started", plugins.get(0).state());
            assertEquals(loadsBefore + 1, TestPlugin.LOADS.get());
            assertEquals(startsBefore + 1, TestPlugin.STARTS.get());
            assertTrue(engine.registry().contains(TestPlugin.OPCODE), "plugin registered its packet");

            long deadline = System.currentTimeMillis() + 5_000;
            while (TestPlugin.TICKS.get() == ticksBefore && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(TestPlugin.TICKS.get() > ticksBefore, "plugin tickable ran on the tick loop");
        } finally {
            engine.stop();
        }
        assertEquals(stopsBefore + 1, TestPlugin.STOPS.get());
        assertEquals("stopped", engine.plugins().plugins().get(0).state());
    }

    @Test
    void disabledPluginsAreNotLoaded(@TempDir Path dir) throws Exception {
        int loadsBefore = TestPlugin.LOADS.get();
        ServerEngine engine = engine(dir, false);
        try {
            engine.start();
            assertEquals(0, engine.plugins().count());
            assertEquals(loadsBefore, TestPlugin.LOADS.get());
        } finally {
            engine.stop();
        }
    }

    @Test
    void missingPluginDirectoryIsNotAnError(@TempDir Path dir) throws Exception {
        PluginManager manager = new PluginManager(engine(dir, true), dir.resolve("nope"));
        assertTrue(manager.load() >= 1, "classpath plugin still loads");
        manager.close();
    }
}
