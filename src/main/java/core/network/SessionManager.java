package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Thread-safe pool of live sessions.
 *
 * <p>Admission enforces the global and per-address limits and the optional loopback-only policy
 * from the configuration, atomically, so concurrent connects from one address cannot exceed the
 * limit. Sessions are indexed by id and by (protocol, remote endpoint); UDP uses the latter to map
 * datagrams back to their session.
 */
public final class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Supplier<EngineConfig> config;
    private final EngineMetrics metrics;
    private final ConcurrentHashMap<Long, NetworkSession> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SessionKey, NetworkSession> byEndpoint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetAddress, Integer> perAddress = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();

    public SessionManager(Supplier<EngineConfig> config, EngineMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Admit a new endpoint. Returns an empty optional (and logs why) when policy rejects it; the
     * caller then closes the underlying connection.
     */
    public Optional<NetworkSession> open(Protocol protocol, InetSocketAddress remote, SessionTransport transport) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(transport, "transport");

        EngineConfig.Network net = config.get().network();
        InetAddress address = remote.getAddress();
        if (address == null) {
            return reject(protocol, remote, "unresolved remote address");
        }
        if (net.loopbackOnly() && !address.isLoopbackAddress()) {
            return reject(protocol, remote, "non-loopback source while network.loopbackOnly=true");
        }
        if (byId.size() >= net.maxSessions()) {
            return reject(protocol, remote, "session limit reached (" + net.maxSessions() + ")");
        }
        SessionKey key = new SessionKey(protocol, remote);
        if (byEndpoint.containsKey(key)) {
            return reject(protocol, remote, "endpoint already has a session");
        }

        int perAddressLimit = net.maxSessionsPerAddress();
        boolean[] admitted = new boolean[1];
        perAddress.compute(address, (a, count) -> {
            int current = count == null ? 0 : count;
            if (perAddressLimit > 0 && current >= perAddressLimit) {
                admitted[0] = false;
                return count;
            }
            admitted[0] = true;
            return current + 1;
        });
        if (!admitted[0]) {
            return reject(protocol, remote, "per-address limit reached (" + perAddressLimit + ")");
        }

        long id = sequence.incrementAndGet();
        NetworkSession session = new NetworkSession(id, protocol, remote, transport, this);
        if (byEndpoint.putIfAbsent(key, session) != null) {
            releaseAddressSlot(address);
            return reject(protocol, remote, "endpoint already has a session");
        }
        byId.put(id, session);
        metrics.connectionAccepted();
        log.info("session {} opened: {} {}{} ({} active)", id, protocol, remote,
                session.isLoopback() ? " [loopback]" : "", byId.size());
        for (SessionListener listener : listeners) {
            try {
                listener.onOpen(session);
            } catch (RuntimeException e) {
                log.error("session listener {} failed in onOpen", listener, e);
            }
        }
        return Optional.of(session);
    }

    private Optional<NetworkSession> reject(Protocol protocol, InetSocketAddress remote, String reason) {
        metrics.connectionRejected();
        log.warn("rejected {} connection from {}: {}", protocol, remote, reason);
        return Optional.empty();
    }

    /** Called by {@link NetworkSession#close}; removes the session from every index exactly once. */
    void onSessionClosed(NetworkSession session, String reason) {
        if (byId.remove(session.id()) == null) {
            return;
        }
        byEndpoint.remove(new SessionKey(session.protocol(), session.remoteAddress()), session);
        InetAddress address = session.remoteAddress().getAddress();
        if (address != null) {
            releaseAddressSlot(address);
        }
        for (SessionListener listener : listeners) {
            try {
                listener.onClose(session, reason);
            } catch (RuntimeException e) {
                log.error("session listener {} failed in onClose", listener, e);
            }
        }
    }

    private void releaseAddressSlot(InetAddress address) {
        perAddress.computeIfPresent(address, (a, count) -> count <= 1 ? null : count - 1);
    }

    // ---- queries --------------------------------------------------------------------------------

    public Optional<NetworkSession> find(long id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<NetworkSession> find(Protocol protocol, InetSocketAddress remote) {
        return Optional.ofNullable(byEndpoint.get(new SessionKey(protocol, remote)));
    }

    /** Point-in-time copy of all sessions, ordered by id. */
    public List<NetworkSession> snapshot() {
        List<NetworkSession> all = new ArrayList<>(byId.values());
        all.sort(Comparator.comparingLong(NetworkSession::id));
        return all;
    }

    public int activeCount() {
        return byId.size();
    }

    public int count(Protocol protocol) {
        int n = 0;
        for (NetworkSession s : byId.values()) {
            if (s.protocol() == protocol) {
                n++;
            }
        }
        return n;
    }

    public int loopbackCount() {
        int n = 0;
        for (NetworkSession s : byId.values()) {
            if (s.isLoopback()) {
                n++;
            }
        }
        return n;
    }

    public int countFor(InetAddress address) {
        return perAddress.getOrDefault(address, 0);
    }

    // ---- bulk operations ------------------------------------------------------------------------

    /** Close sessions on the given protocol that have been silent longer than {@code maxIdleMillis}. */
    public int closeIdle(Protocol protocol, long maxIdleMillis, String reason) {
        int closed = 0;
        for (NetworkSession s : byId.values()) {
            if (s.protocol() == protocol && s.isOpen() && s.idleMillis() > maxIdleMillis) {
                s.close(reason);
                closed++;
            }
        }
        return closed;
    }

    public int closeAll(Protocol protocol, String reason) {
        int closed = 0;
        for (NetworkSession s : new ArrayList<>(byId.values())) {
            if (s.protocol() == protocol && s.isOpen()) {
                s.close(reason);
                closed++;
            }
        }
        return closed;
    }

    public int closeAll(String reason) {
        int closed = 0;
        for (NetworkSession s : new ArrayList<>(byId.values())) {
            if (s.isOpen()) {
                s.close(reason);
                closed++;
            }
        }
        return closed;
    }

    public void addListener(SessionListener listener) {
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }
}
