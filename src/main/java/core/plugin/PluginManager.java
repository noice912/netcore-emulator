package core.plugin;

import core.engine.ServerEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Discovers, loads and drives {@link EnginePlugin}s from the classpath and from every
 * {@code *.jar} in the plugin directory. Each jar gets its own class loader (parented to the
 * engine's), and every lifecycle call is isolated so one broken plugin cannot take the rest down.
 */
public final class PluginManager implements AutoCloseable {

    /** What the console and admin API show per plugin. */
    public record PluginInfo(String name, String version, String source, String state) {
    }

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);
    private static final int MAX_PROVIDER_FAILURES = 100;

    private static final class Loaded {
        final EnginePlugin plugin;
        final String source;
        volatile String state;

        Loaded(EnginePlugin plugin, String source, String state) {
            this.plugin = plugin;
            this.source = source;
            this.state = state;
        }
    }

    private final ServerEngine engine;
    private final Path directory;
    private final List<Loaded> loaded = new CopyOnWriteArrayList<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    public PluginManager(ServerEngine engine, Path directory) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Discover and {@code onLoad} every plugin. Returns how many loaded successfully. */
    public synchronized int load() {
        int count = loadFrom(ServiceLoader.load(EnginePlugin.class, PluginManager.class.getClassLoader()),
                "classpath");
        if (Files.isDirectory(directory)) {
            List<Path> jars;
            try (Stream<Path> files = Files.list(directory)) {
                jars = files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                        .sorted().toList();
            } catch (IOException e) {
                log.error("cannot list plugin directory {}", directory, e);
                return count;
            }
            for (Path jar : jars) {
                try {
                    URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                            PluginManager.class.getClassLoader());
                    loaders.add(loader);
                    count += loadFrom(ServiceLoader.load(EnginePlugin.class, loader), jar.getFileName().toString());
                } catch (IOException | RuntimeException e) {
                    log.error("cannot open plugin jar {}", jar, e);
                }
            }
        } else {
            log.info("plugin directory {} does not exist; only classpath plugins are loaded", directory);
        }
        return count;
    }

    private int loadFrom(ServiceLoader<EnginePlugin> services, String source) {
        int count = 0;
        int failures = 0;
        Iterator<EnginePlugin> iterator = services.iterator();
        while (failures < MAX_PROVIDER_FAILURES) {
            EnginePlugin plugin;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                plugin = iterator.next();
            } catch (ServiceConfigurationError e) {
                failures++;
                log.error("a plugin declared in {} could not be instantiated", source, e);
                continue;
            }
            String name = plugin.name() == null ? plugin.getClass().getName() : plugin.name();
            if (loaded.stream().anyMatch(l -> l.plugin.name().equals(name))) {
                log.warn("plugin '{}' from {} skipped: a plugin with that name is already loaded", name, source);
                continue;
            }
            try {
                plugin.onLoad(engine);
                loaded.add(new Loaded(plugin, source, "loaded"));
                count++;
                log.info("plugin '{}' v{} loaded from {}", name, plugin.version(), source);
            } catch (Exception | LinkageError e) {
                log.error("plugin '{}' from {} failed in onLoad and is disabled", name, source, e);
            }
        }
        return count;
    }

    /** Call {@code onStart} on every loaded plugin, after the listeners are bound. */
    public synchronized void startAll() {
        for (Loaded entry : loaded) {
            try {
                entry.plugin.onStart(engine);
                entry.state = "started";
            } catch (Exception | LinkageError e) {
                entry.state = "failed";
                log.error("plugin '{}' failed in onStart", entry.plugin.name(), e);
            }
        }
    }

    /** Call {@code onStop} in reverse load order. */
    public synchronized void stopAll() {
        List<Loaded> reversed = new ArrayList<>(loaded);
        Collections.reverse(reversed);
        for (Loaded entry : reversed) {
            if ("stopped".equals(entry.state)) {
                continue;
            }
            try {
                entry.plugin.onStop(engine);
            } catch (RuntimeException | LinkageError e) {
                log.error("plugin '{}' failed in onStop", entry.plugin.name(), e);
            }
            entry.state = "stopped";
        }
    }

    public List<PluginInfo> plugins() {
        List<PluginInfo> infos = new ArrayList<>(loaded.size());
        for (Loaded entry : loaded) {
            infos.add(new PluginInfo(entry.plugin.name(), entry.plugin.version(), entry.source, entry.state));
        }
        return Collections.unmodifiableList(infos);
    }

    public int count() {
        return loaded.size();
    }

    @Override
    public synchronized void close() {
        stopAll();
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.debug("error closing plugin class loader", e);
            }
        }
        loaders.clear();
    }
}
