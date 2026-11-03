package core.engine;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Process-wide, thread-safe, namespaced key/value memory.
 *
 * <p>Engine state that must outlive a single packet or session (accounts, tokens, world data,
 * counters) lives here. Each namespace is an independent {@link ConcurrentHashMap}; reads are typed
 * so a caller never gets a {@link ClassCastException} from a value another component stored.
 * Nothing is persisted: the store is empty on every start.
 */
public final class MemoryStore {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> namespaces = new ConcurrentHashMap<>();

    private static String checkName(String name, String what) {
        Objects.requireNonNull(name, what);
        if (name.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        return name;
    }

    private ConcurrentHashMap<String, Object> space(String namespace) {
        return namespaces.computeIfAbsent(checkName(namespace, "namespace"), k -> new ConcurrentHashMap<>());
    }

    /** Store a value (null is not allowed; use {@link #remove} instead). Returns the previous value. */
    public Object put(String namespace, String key, Object value) {
        return space(namespace).put(checkName(key, "key"), Objects.requireNonNull(value, "value"));
    }

    public Optional<Object> get(String namespace, String key) {
        ConcurrentHashMap<String, Object> space = namespaces.get(checkName(namespace, "namespace"));
        return space == null ? Optional.empty() : Optional.ofNullable(space.get(checkName(key, "key")));
    }

    /** Typed read; empty when absent or stored as a different type. */
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return get(namespace, key).filter(type::isInstance).map(type::cast);
    }

    public <T> T getOrDefault(String namespace, String key, Class<T> type, T fallback) {
        return get(namespace, key, type).orElse(fallback);
    }

    /** Atomically create the value if absent. Throws if an existing value has a different type. */
    public <T> T computeIfAbsent(String namespace, String key, Class<T> type, Function<String, ? extends T> factory) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(factory, "factory");
        Object value = space(namespace).computeIfAbsent(checkName(key, "key"), k ->
                Objects.requireNonNull(factory.apply(k), "factory returned null"));
        if (!type.isInstance(value)) {
            throw new IllegalStateException(namespace + "/" + key + " holds " + value.getClass().getName()
                    + ", not " + type.getName());
        }
        return type.cast(value);
    }

    public boolean contains(String namespace, String key) {
        return get(namespace, key).isPresent();
    }

    public boolean remove(String namespace, String key) {
        ConcurrentHashMap<String, Object> space = namespaces.get(checkName(namespace, "namespace"));
        return space != null && space.remove(checkName(key, "key")) != null;
    }

    /** Atomically add {@code delta} to a numeric value (created as 0 if absent) and return the result. */
    public long increment(String namespace, String key, long delta) {
        Object result = space(namespace).merge(checkName(key, "key"), delta, (existing, add) -> {
            if (!(existing instanceof Number current)) {
                throw new IllegalStateException(namespace + "/" + key + " holds "
                        + existing.getClass().getName() + ", not a number");
            }
            return current.longValue() + ((Number) add).longValue();
        });
        return ((Number) result).longValue();
    }

    public Set<String> keys(String namespace) {
        ConcurrentHashMap<String, Object> space = namespaces.get(checkName(namespace, "namespace"));
        return space == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(space.keySet()));
    }

    public int size(String namespace) {
        ConcurrentHashMap<String, Object> space = namespaces.get(checkName(namespace, "namespace"));
        return space == null ? 0 : space.size();
    }

    public void clear(String namespace) {
        namespaces.remove(checkName(namespace, "namespace"));
    }

    public void clearAll() {
        namespaces.clear();
    }

    public Set<String> namespaces() {
        return Collections.unmodifiableSet(new TreeSet<>(namespaces.keySet()));
    }

    public int totalEntries() {
        int total = 0;
        for (ConcurrentHashMap<String, Object> space : namespaces.values()) {
            total += space.size();
        }
        return total;
    }

    /** Entry count per namespace, sorted by name. */
    public Map<String, Integer> stats() {
        Map<String, Integer> stats = new TreeMap<>();
        namespaces.forEach((name, space) -> stats.put(name, space.size()));
        return Collections.unmodifiableMap(stats);
    }
}
