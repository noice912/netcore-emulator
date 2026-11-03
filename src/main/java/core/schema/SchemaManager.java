package core.schema;

import core.engine.FileWatcher;
import core.handlers.PacketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * Owns the live {@link SchemaSet}, registers a {@link SchemaPacket} factory plus
 * {@link SchemaHandler} for every schema packet in the {@link PacketRegistry}, and re-registers
 * them when the schema directory changes. A failed reload keeps the previous set registered.
 * Opcodes already owned by a Java handler are never overridden.
 */
public final class SchemaManager implements AutoCloseable {

    /** Outcome of a (re)load. */
    public record LoadResult(boolean success, int packets, int structs, int rules, String message) {
    }

    private static final Logger log = LoggerFactory.getLogger(SchemaManager.class);

    private final PacketRegistry registry;
    private final Path directory;
    private final Set<Integer> registered = new HashSet<>();
    private final CopyOnWriteArrayList<SchemaListener> listeners = new CopyOnWriteArrayList<>();
    private final LongAdder handled = new LongAdder();
    private final LongAdder responses = new LongAdder();
    private volatile SchemaSet current = SchemaSet.empty();
    private FileWatcher watcher;

    public SchemaManager(PacketRegistry registry, Path directory) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public Path directory() {
        return directory;
    }

    /** Load (or reload) every schema file and swap the registrations. Never throws. */
    public synchronized LoadResult reload() {
        SchemaSet loaded;
        try {
            loaded = SchemaLoader.loadDirectory(directory);
        } catch (IOException | SchemaException e) {
            log.error("schema load from {} failed; keeping the {} packet(s) from the previous load: {}",
                    directory, registered.size(), e.getMessage());
            return new LoadResult(false, registered.size(), current.structs().size(), current.rules().size(),
                    e.getMessage());
        }

        for (int opcode : registered) {
            registry.unregister(opcode);
        }
        registered.clear();

        int skipped = 0;
        for (PacketDef def : loaded.packets()) {
            if (registry.contains(def.opcode())) {
                log.warn("schema packet '{}' not registered: opcode 0x{} belongs to a Java handler ({})", def.name(),
                        String.format("%04X", def.opcode()),
                        registry.lookup(def.opcode()).map(r -> r.handler().name()).orElse("?"));
                skipped++;
                continue;
            }
            final SchemaSet set = loaded;
            registry.register(def.opcode(), () -> new SchemaPacket(def, set), new SchemaHandler(def, this));
            registered.add(def.opcode());
        }
        current = loaded;

        String message = String.format("%d packet(s), %d struct(s), %d response rule(s) from %d file(s)%s",
                registered.size(), loaded.structs().size(), loaded.rules().size(), loaded.sources().size(),
                skipped > 0 ? " (" + skipped + " skipped: opcode owned by a Java handler)" : "");
        log.info("schemas loaded from {}: {}", directory, message);
        return new LoadResult(true, registered.size(), loaded.structs().size(), loaded.rules().size(), message);
    }

    public SchemaSet current() {
        return current;
    }

    /** New packet instance with default field values, ready for {@link SchemaPacket#set}. */
    public SchemaPacket build(String packetName) {
        SchemaSet set = current;
        PacketDef def = set.packet(packetName).orElseThrow(() ->
                new IllegalArgumentException("no schema packet named '" + packetName + "'"));
        return new SchemaPacket(def, set);
    }

    public synchronized int registeredCount() {
        return registered.size();
    }

    public void addListener(SchemaListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(SchemaListener listener) {
        listeners.remove(listener);
    }

    List<SchemaListener> listeners() {
        return listeners;
    }

    void recordHandled() {
        handled.increment();
    }

    void recordResponse() {
        responses.increment();
    }

    public long packetsHandled() {
        return handled.sum();
    }

    public long responsesSent() {
        return responses.sum();
    }

    public synchronized void startWatching(long debounceMillis) throws IOException {
        if (watcher != null) {
            return;
        }
        FileWatcher created = new FileWatcher("schema-watcher", directory,
                name -> name.toLowerCase().endsWith(".json"), debounceMillis, this::reload);
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
