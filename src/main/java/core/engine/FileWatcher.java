package core.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Watches one directory and runs a callback after a matching file is created, modified or
 * deleted. Editors typically save in several steps (truncate, write, rename), so events are
 * debounced: after the first relevant event the watcher waits briefly, discards the rest of the
 * burst, then fires once. Used for {@code config.json} and the schema directory.
 */
public final class FileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileWatcher.class);

    private final String threadName;
    private final Path directory;
    private final Predicate<String> fileNameFilter;
    private final long debounceMillis;
    private final Runnable onChange;

    private volatile boolean running;
    private WatchService watchService;
    private Thread thread;

    public FileWatcher(String threadName, Path directory, Predicate<String> fileNameFilter, long debounceMillis,
                       Runnable onChange) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
        this.fileNameFilter = Objects.requireNonNull(fileNameFilter, "fileNameFilter");
        this.debounceMillis = Math.max(0, debounceMillis);
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Files.createDirectories(directory);
        watchService = directory.getFileSystem().newWatchService();
        directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        running = true;
        thread = new Thread(this::loop, threadName);
        thread.setDaemon(true);
        thread.start();
        log.info("watching {} for changes", directory);
    }

    public boolean isRunning() {
        return running;
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
                if (event.context() instanceof Path changed
                        && fileNameFilter.test(changed.getFileName().toString())) {
                    relevant = true;
                }
            }
            boolean valid = key.reset();

            if (relevant && running) {
                settle();
                try {
                    onChange.run();
                } catch (RuntimeException e) {
                    log.error("change callback for {} failed", directory, e);
                }
            }
            if (!valid) {
                log.warn("{} is no longer watchable; watcher stopped", directory);
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
        log.info("stopped watching {}", directory);
    }
}
