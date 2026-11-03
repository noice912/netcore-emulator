package core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Owns the live {@link EngineConfig} snapshot and swaps it atomically on reload.
 *
 * <p>Readers never block: they call {@link #current()} (or the {@link #supplier()}) and get an
 * immutable snapshot. A reload parses the file into a new snapshot first; if that fails, the
 * running configuration is kept and the failure is logged, so a half-saved or broken file can
 * never take the engine down. Listeners are told only when the new snapshot differs.
 */
public final class ConfigManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigManager.class);

    public enum Outcome { RELOADED, UNCHANGED, FAILED }

    public record ReloadResult(Outcome outcome, String message) {
    }

    private final Path path;
    private final CopyOnWriteArrayList<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Object reloadLock = new Object();
    private volatile EngineConfig current;
    private FileWatcher watcher;

    private ConfigManager(Path path, EngineConfig initial) {
        this.path = path;
        this.current = initial;
    }

    /** Load (creating the file with defaults if absent) and return a manager for it. */
    public static ConfigManager load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "path");
        return new ConfigManager(path, EngineConfig.load(path));
    }

    public Path path() {
        return path;
    }

    public EngineConfig current() {
        return current;
    }

    /** A live view: each call returns the snapshot current at that moment. */
    public Supplier<EngineConfig> supplier() {
        return this::current;
    }

    /** Re-read the file. Never throws; a failure keeps the current snapshot. */
    public ReloadResult reload() {
        synchronized (reloadLock) {
            if (!Files.exists(path)) {
                log.warn("config reload skipped: {} is missing; keeping current settings", path.toAbsolutePath());
                return new ReloadResult(Outcome.FAILED, "config file is missing");
            }
            EngineConfig candidate;
            try {
                candidate = EngineConfig.load(path);
            } catch (ConfigException e) {
                log.error("config reload failed; keeping current settings: {}", e.getMessage());
                return new ReloadResult(Outcome.FAILED, e.getMessage());
            }
            EngineConfig previous = current;
            if (previous.toJson().equals(candidate.toJson())) {
                log.debug("config reload: no effective change");
                return new ReloadResult(Outcome.UNCHANGED, "no effective change");
            }
            current = candidate;
            log.info("configuration reloaded from {}", path.toAbsolutePath());
            for (ConfigChangeListener listener : listeners) {
                try {
                    listener.onConfigChanged(previous, candidate);
                } catch (RuntimeException e) {
                    log.error("config change listener {} failed", listener, e);
                }
            }
            return new ReloadResult(Outcome.RELOADED, "reloaded");
        }
    }

    public void addListener(ConfigChangeListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(ConfigChangeListener listener) {
        listeners.remove(listener);
    }

    /** Start watching the file's directory; changes trigger {@link #reload()} on a background thread. */
    public synchronized void startWatching(long debounceMillis) throws IOException {
        if (watcher != null) {
            return;
        }
        Path absolute = path.toAbsolutePath();
        Path directory = absolute.getParent() != null ? absolute.getParent() : absolute.getFileSystem().getPath(".");
        String fileName = absolute.getFileName().toString();
        FileWatcher created = new FileWatcher("config-watcher", directory, fileName::equals, debounceMillis,
                this::reload);
        created.start();
        watcher = created;
    }

    public synchronized void stopWatching() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    public synchronized boolean isWatching() {
        return watcher != null;
    }

    @Override
    public void close() {
        stopWatching();
    }
}
