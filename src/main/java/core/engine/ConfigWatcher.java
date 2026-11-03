package core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;

/**
 * Watches one file's directory and runs a callback after the file is created, modified or
 * replaced. Editors typically save in several steps (truncate, write, rename), so events are
 * debounced: after the first relevant event the watcher waits briefly, discards the rest of the
 * burst, then fires once.
 */
final class ConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private final Path file;
    private final Path directory;
    private final String fileName;
    private final long debounceMillis;
    private final Runnable onChange;

    private volatile boolean running;
    private WatchService watchService;
    private Thread thread;

    ConfigWatcher(Path file, long debounceMillis, Runnable onChange) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath();
        Path parent = this.file.getParent();
        this.directory = parent != null ? parent : this.file.getFileSystem().getPath(".").toAbsolutePath();
        this.fileName = this.file.getFileName().toString();
        this.debounceMillis = Math.max(0, debounceMillis);
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        watchService = file.getFileSystem().newWatchService();
        directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        running = true;
        thread = new Thread(this::loop, "config-watcher");
        thread.setDaemon(true);
        thread.start();
        log.info("watching {} for changes", file);
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            boolean relevant = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true; // events were lost; assume ours was among them
                    continue;
                }
                if (event.context() instanceof Path changed && fileName.equals(changed.getFileName().toString())) {
                    relevant = true;
                }
            }
            boolean valid = key.reset();

            if (relevant && running) {
                settle();
                try {
                    onChange.run();
                } catch (RuntimeException e) {
                    log.error("config change callback failed", e);
                }
            }
            if (!valid) {
                log.warn("{} is no longer watchable; config watcher stopped", directory);
                return;
            }
        }
    }

    /** Wait out the editor's save burst and discard the events it produced. */
    private void settle() {
        if (debounceMillis == 0) {
            return;
        }
        try {
            Thread.sleep(debounceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            WatchKey extra;
            while ((extra = watchService.poll()) != null) {
                extra.pollEvents();
                extra.reset();
            }
        } catch (ClosedWatchServiceException ignored) {
            // shutting down
        }
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        try {
            watchService.close();
        } catch (IOException e) {
            log.debug("error closing watch service", e);
        }
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("stopped watching {}", file);
    }
}
