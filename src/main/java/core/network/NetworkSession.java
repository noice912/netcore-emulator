package core.network;

import core.handlers.BasePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * One connected client endpoint, independent of transport.
 *
 * <p>Created by the {@link SessionManager} when a connection (TCP) or a first datagram (UDP)
 * arrives, and removed from the pool when {@link #close} runs, whether that is triggered by a
 * handler, an idle timeout, a protocol violation, or the peer vanishing. Every method is safe to
 * call from any thread; the state transitions are atomic so a session closes exactly once.
 */
public final class NetworkSession {

    public enum State {
        /** Transport is up; the client has not completed the handshake. */
        CONNECTED,
        /** Handshake accepted; all registered packets are allowed. */
        AUTHENTICATED,
        /** Closed; no further traffic in either direction. */
        CLOSED
    }

    /** Serializable snapshot for status views and the admin API. */
    public record View(long id, String protocol, String remote, boolean loopback, String state,
                       int protocolVersion, long openedAtMillis, long idleMillis, long packetsIn, long packetsOut,
                       long bytesIn, long bytesOut, int unknownPackets, int malformedPackets,
                       int rateLimitViolations) {
    }

    private static final Logger log = LoggerFactory.getLogger(NetworkSession.class);

    private final long id;
    private final Protocol protocol;
    private final InetSocketAddress remoteAddress;
    private final boolean loopback;
    private final SessionTransport transport;
    private final SessionManager manager;
    private final long openedAtMillis;

    private final AtomicReference<State> state = new AtomicReference<>(State.CONNECTED);
    private volatile long lastActivityMillis;
    private volatile int protocolVersion = -1;
    private volatile String clientToken = "";
    private volatile String closeReason;
    private volatile TokenBucket inboundBucket;

    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final LongAdder packetsIn = new LongAdder();
    private final LongAdder packetsOut = new LongAdder();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();
    private final AtomicInteger unknownPackets = new AtomicInteger();
    private final AtomicInteger malformedPackets = new AtomicInteger();
    private final AtomicInteger rateLimitViolations = new AtomicInteger();

    NetworkSession(long id, Protocol protocol, InetSocketAddress remoteAddress, SessionTransport transport,
                   SessionManager manager) {
        this.id = id;
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.manager = Objects.requireNonNull(manager, "manager");
        InetAddress address = remoteAddress.getAddress();
        this.loopback = address != null && address.isLoopbackAddress();
        this.openedAtMillis = System.currentTimeMillis();
        this.lastActivityMillis = openedAtMillis;
    }

    // ---- identity -------------------------------------------------------------------------------

    public long id() {
        return id;
    }

    public Protocol protocol() {
        return protocol;
    }

    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /** Whether the peer is on this machine (127.0.0.0/8 or ::1). */
    public boolean isLoopback() {
        return loopback;
    }

    public long openedAtMillis() {
        return openedAtMillis;
    }

    // ---- state ----------------------------------------------------------------------------------

    public State state() {
        return state.get();
    }

    public boolean isOpen() {
        return state.get() != State.CLOSED;
    }

    public boolean isAuthenticated() {
        return state.get() == State.AUTHENTICATED;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String clientToken() {
        return clientToken;
    }

    public Optional<String> closeReason() {
        return Optional.ofNullable(closeReason);
    }

    /**
     * Promote the session from CONNECTED to AUTHENTICATED. Returns {@code false} if the session
     * was not in CONNECTED state (already authenticated, or closed).
     */
    public boolean markAuthenticated(int protocolVersion, String clientToken) {
        if (!state.compareAndSet(State.CONNECTED, State.AUTHENTICATED)) {
            return false;
        }
        this.protocolVersion = protocolVersion;
        this.clientToken = clientToken == null ? "" : clientToken;
        log.info("session {} ({} {}) authenticated with protocol v{}", id, protocol, remoteAddress, protocolVersion);
        return true;
    }

    // ---- activity, counters & flood control -----------------------------------------------------

    public void touch() {
        lastActivityMillis = System.currentTimeMillis();
    }

    public long lastActivityMillis() {
        return lastActivityMillis;
    }

    public long idleMillis() {
        return Math.max(0, System.currentTimeMillis() - lastActivityMillis);
    }

    public void recordInbound(int bytes) {
        packetsIn.increment();
        bytesIn.add(bytes);
        touch();
    }

    void recordOutbound(int bytes) {
        packetsOut.increment();
        bytesOut.add(bytes);
        manager.metrics().packetOut(bytes);
    }

    public int recordUnknownPacket() {
        return unknownPackets.incrementAndGet();
    }

    public int recordMalformedPacket() {
        return malformedPackets.incrementAndGet();
    }

    /**
     * Admit one inbound packet against this session's token bucket. The bucket is created on first
     * use and re-configured in place when the limits change, so a config reload applies live.
     */
    public boolean tryAdmitInbound(double packetsPerSecond, double burst) {
        TokenBucket bucket = inboundBucket;
        if (bucket == null) {
            synchronized (this) {
                bucket = inboundBucket;
                if (bucket == null) {
                    bucket = new TokenBucket(packetsPerSecond, burst);
                    inboundBucket = bucket;
                }
            }
        }
        return bucket.tryAcquire(packetsPerSecond, burst);
    }

    public int recordRateLimitViolation() {
        return rateLimitViolations.incrementAndGet();
    }

    public long packetsIn() {
        return packetsIn.sum();
    }

    public long packetsOut() {
        return packetsOut.sum();
    }

    public long bytesIn() {
        return bytesIn.sum();
    }

    public long bytesOut() {
        return bytesOut.sum();
    }

    public int unknownPackets() {
        return unknownPackets.get();
    }

    public int malformedPackets() {
        return malformedPackets.get();
    }

    public int rateLimitViolations() {
        return rateLimitViolations.get();
    }

    /** Point-in-time, JSON-friendly snapshot. */
    public View view() {
        return new View(id, protocol.name(), remoteAddress.toString(), loopback, state.get().name(), protocolVersion,
                openedAtMillis, idleMillis(), packetsIn.sum(), packetsOut.sum(), bytesIn.sum(), bytesOut.sum(),
                unknownPackets.get(), malformedPackets.get(), rateLimitViolations.get());
    }

    // ---- attributes -----------------------------------------------------------------------------

    /** Typed read of a per-session attribute; empty when absent or of another type. */
    public <T> Optional<T> attribute(String key, Class<T> type) {
        Object value = attributes.get(Objects.requireNonNull(key, "key"));
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    public Object setAttribute(String key, Object value) {
        return attributes.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
    }

    public Object removeAttribute(String key) {
        return attributes.remove(Objects.requireNonNull(key, "key"));
    }

    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    // ---- I/O ------------------------------------------------------------------------------------

    /** Encode and queue a packet. Returns {@code false} if the session is closed or encoding failed. */
    public boolean send(BasePacket packet) {
        Objects.requireNonNull(packet, "packet");
        if (!isOpen()) {
            log.debug("session {} is closed; not sending {}", id, packet);
            return false;
        }
        Frame frame;
        try {
            frame = packet.toFrame(transport.allocator());
        } catch (RuntimeException e) {
            log.error("session {}: failed to encode {}", id, packet, e);
            return false;
        }
        return write(frame, false, packet);
    }

    /** Send a final packet, then close. The farewell is still written if the state was CONNECTED. */
    public void sendAndClose(BasePacket farewell, String reason) {
        Objects.requireNonNull(farewell, "farewell");
        if (state.getAndSet(State.CLOSED) == State.CLOSED) {
            return;
        }
        Frame frame = null;
        try {
            frame = farewell.toFrame(transport.allocator());
        } catch (RuntimeException e) {
            log.warn("session {}: failed to encode farewell {}", id, farewell, e);
        }
        boolean queued = frame != null && write(frame, true, farewell);
        if (!queued) {
            closeTransportQuietly();
        }
        finishClose(reason);
    }

    /** Close the session and its transport. Safe to call repeatedly and from any thread. */
    public void close(String reason) {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) {
            return;
        }
        closeTransportQuietly();
        finishClose(reason);
    }

    private boolean write(Frame frame, boolean closeAfterWrite, BasePacket packet) {
        int length = frame.payloadLength();
        PacketRecorder recorder = manager.recorderOrNull();
        if (recorder != null) {
            recorder.record(PacketRecorder.Direction.OUT, this, frame.opcode(), frame.content());
        }
        try {
            transport.send(frame, closeAfterWrite);
        } catch (RuntimeException e) {
            log.error("session {}: transport rejected {}", id, packet, e);
            return false;
        }
        recordOutbound(length);
        return true;
    }

    private void closeTransportQuietly() {
        try {
            transport.close();
        } catch (RuntimeException e) {
            log.warn("session {}: error while closing transport", id, e);
        }
    }

    private void finishClose(String reason) {
        closeReason = reason == null || reason.isBlank() ? "closed" : reason;
        log.info("session {} ({} {}) closed after {} ms: {}", id, protocol, remoteAddress,
                System.currentTimeMillis() - openedAtMillis, closeReason);
        manager.onSessionClosed(this, closeReason);
    }

    @Override
    public String toString() {
        return "Session#" + id + "[" + protocol + " " + remoteAddress + (loopback ? " loopback" : "") + " "
                + state.get() + " in=" + packetsIn.sum() + " out=" + packetsOut.sum() + "]";
    }
}
