package core.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshots the {@link MemoryStore} to a JSON file and restores it at startup.
 *
 * <p>Values are written with a type tag so they come back as the same Java type: integers as
 * {@code Long}, floating point as {@code Double}, plus {@code Boolean}, {@code String}, and
 * structured data (Gson {@code JsonElement}, {@code Map}, {@code Collection}) which is restored
 * as a {@code JsonElement}. Anything else is skipped with a warning; keep persisted values to those
 * types. Saves are atomic (write to a temp file, then rename) so a crash mid-save never corrupts
 * the previous snapshot, and {@link #saveIfDirty()} skips the write when nothing changed.
 */
public final class PersistentStore implements AutoCloseable {

    public static final String FILE_NAME = "store.json";

    private static final Logger log = LoggerFactory.getLogger(PersistentStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final MemoryStore store;
    private final Path directory;
    private final Set<String> namespaces;
    private final Object ioLock = new Object();
    private volatile long savedModificationCount = -1;
    private volatile long lastSaveMillis;

    /**
     * @param namespaces the namespaces to persist; empty means all of them
     */
    public PersistentStore(MemoryStore store, Path directory, Collection<String> namespaces) {
        this.store = Objects.requireNonNull(store, "store");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.namespaces = new HashSet<>(Objects.requireNonNull(namespaces, "namespaces"));
    }

    public Path file() {
        return directory.resolve(FILE_NAME);
    }

    public long lastSaveMillis() {
        return lastSaveMillis;
    }

    /** Read the snapshot into the store. Returns the number of entries restored (0 if no file). */
    public int load() throws IOException {
        Path file = file();
        if (!Files.exists(file)) {
            savedModificationCount = store.modificationCount();
            return 0;
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("store file " + file + " does not contain a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("store file " + file + " is corrupt: " + e.getMessage(), e);
        }

        int loaded = 0;
        JsonElement spaces = root.get("namespaces");
        if (spaces != null && spaces.isJsonObject()) {
            for (Map.Entry<String, JsonElement> ns : spaces.getAsJsonObject().entrySet()) {
                if (!selected(ns.getKey()) || !ns.getValue().isJsonObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : ns.getValue().getAsJsonObject().entrySet()) {
                    Object value = fromJson(entry.getValue());
                    if (value == null) {
                        log.warn("skipping {}/{}: unreadable persisted value", ns.getKey(), entry.getKey());
                        continue;
                    }
                    store.put(ns.getKey(), entry.getKey(), value);
                    loaded++;
                }
            }
        }
        savedModificationCount = store.modificationCount();
        log.info("restored {} entr(ies) from {}", loaded, file);
        return loaded;
    }

    /** Save only if the store changed since the last load or save. */
    public boolean saveIfDirty() throws IOException {
        if (store.modificationCount() == savedModificationCount) {
            return false;
        }
        save();
        return true;
    }

    /** Write the snapshot unconditionally. Returns the number of entries written. */
    public int save() throws IOException {
        synchronized (ioLock) {
            long modificationCount = store.modificationCount();
            JsonObject root = new JsonObject();
            root.addProperty("format", 1);
            root.addProperty("savedAt", Instant.now().toString());
            JsonObject spaces = new JsonObject();
            int written = 0;
            int skipped = 0;
            for (String ns : store.namespaces()) {
                if (!selected(ns)) {
                    continue;
                }
                JsonObject entries = new JsonObject();
                for (String key : store.keys(ns)) {
                    Object value = store.get(ns, key).orElse(null);
                    if (value == null) {
                        continue;
                    }
                    JsonElement json = toJson(value);
                    if (json == null) {
                        skipped++;
                        log.debug("not persisting {}/{}: unsupported type {}", ns, key, value.getClass().getName());
                        continue;
                    }
                    entries.add(key, json);
                    written++;
                }
                spaces.add(ns, entries);
            }
            root.add("namespaces", spaces);

            Files.createDirectories(directory);
            Path temp = directory.resolve(FILE_NAME + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file(), StandardCopyOption.REPLACE_EXISTING);
            }
            savedModificationCount = modificationCount;
            lastSaveMillis = System.currentTimeMillis();
            if (skipped > 0) {
                log.warn("{} value(s) were not persisted because their type is unsupported", skipped);
            }
            log.info("saved {} entr(ies) to {}", written, file());
            return written;
        }
    }

    private boolean selected(String namespace) {
        return namespaces.isEmpty() || namespaces.contains(namespace);
    }

    /** Tag a value with its type so it round-trips; {@code null} for unsupported types. */
    static JsonElement toJson(Object value) {
        if (value instanceof Boolean b) {
            return tagged("bool", new JsonPrimitive(b));
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return tagged("long", new JsonPrimitive(((Number) value).longValue()));
        }
        if (value instanceof Float || value instanceof Double) {
            return tagged("double", new JsonPrimitive(((Number) value).doubleValue()));
        }
        if (value instanceof String s) {
            return tagged("string", new JsonPrimitive(s));
        }
        if (value instanceof JsonElement element) {
            return tagged("json", element.deepCopy());
        }
        if (value instanceof Map || value instanceof Collection) {
            try {
                return tagged("json", GSON.toJsonTree(value));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    static Object fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject tagged = element.getAsJsonObject();
        JsonElement type = tagged.get("t");
        JsonElement value = tagged.get("v");
        if (type == null || value == null) {
            return null;
        }
        try {
            return switch (type.getAsString()) {
                case "bool" -> value.getAsBoolean();
                case "long" -> value.getAsLong();
                case "double" -> value.getAsDouble();
                case "string" -> value.getAsString();
                case "json" -> value.deepCopy();
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonElement tagged(String type, JsonElement value) {
        JsonObject object = new JsonObject();
        object.addProperty("t", type);
        object.add("v", value);
        return object;
    }

    /** Flush unsaved changes. */
    @Override
    public void close() {
        try {
            saveIfDirty();
        } catch (IOException e) {
            log.error("final save to {} failed", file(), e);
        }
    }
}
