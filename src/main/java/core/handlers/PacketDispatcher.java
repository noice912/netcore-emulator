package core.handlers;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.network.Frame;
import core.network.NetworkSession;
import core.network.PacketRecorder;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Routes inbound frames to their registered handler.
 *
 * <p>This is the boundary between untrusted bytes and engine code, so every step is guarded: null
 * inputs are dropped, flooding sessions are throttled by a per-session token bucket, unknown
 * opcodes are counted and logged, payloads that fail to decode are reported as malformed, the
 * packet instance is type-checked against the handler, and any exception a handler throws is
 * logged instead of propagating into the network event loop. Only JVM-fatal errors
 * ({@link VirtualMachineError}) are rethrown.
 *
 * <p>A session that keeps sending unknown, malformed or excessive traffic is closed once it
 * crosses the per-session limits in the security section of the configuration.
 */
public final class PacketDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PacketDispatcher.class);
    private static final int DUMP_BYTES = 32;

    private final PacketRegistry registry;
    private final Supplier<EngineConfig> config;
    private final EngineMetrics metrics;
    private volatile PacketRecorder recorder;

    public PacketDispatcher(PacketRegistry registry, Supplier<EngineConfig> config, EngineMetrics metrics) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public PacketRegistry registry() {
        return registry;
    }

    /** Attach (or detach with {@code null}) a recorder that sees every inbound frame. */
    public void setRecorder(PacketRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Whether a datagram carrying this opcode may open a brand-new session: true only for opcodes
     * whose handler does not require a handshake (i.e. the handshake itself).
     */
    public boolean canOpenSession(int opcode) {
        return registry.lookup(opcode).map(r -> !r.handler().requiresHandshake()).orElse(false);
    }

    /**
     * Decode and execute one frame on behalf of a session. Never throws for bad input or a failing
     * handler. The caller keeps ownership of the frame and must release it afterwards.
     */
    public DispatchResult dispatch(NetworkSession session, Frame frame) {
        if (session == null || frame == null || frame.content() == null) {
            metrics.packetDropped();
            log.warn("dispatcher received a null {}; dropping", session == null ? "session" : "frame");
            return DispatchResult.DROPPED_NULL;
        }

        ByteBuf payload = frame.content();
        int opcode = frame.opcode();
        int length = payload.readableBytes();
        session.recordInbound(length);
        metrics.packetIn(length);

        PacketRecorder rec = recorder;
        if (rec != null) {
            rec.record(PacketRecorder.Direction.IN, session, opcode, payload);
        }

        if (!session.isOpen()) {
            log.debug("session {} is closed; dropping opcode 0x{}", session.id(), hex(opcode));
            metrics.packetDropped();
            return DispatchResult.REJECTED_SESSION_CLOSED;
        }

        EngineConfig cfg = config.get();
        EngineConfig.RateLimit limit = cfg.security().rateLimit();
        if (limit.enabled() && !session.tryAdmitInbound(limit.packetsPerSecond(), limit.burst())) {
            return onRateLimited(session, opcode, limit);
        }

        PacketRegistry.Registration<?> registration = registry.lookup(opcode).orElse(null);
        if (registration == null) {
            return onUnknownOpcode(session, opcode, payload, cfg);
        }

        if (cfg.security().requireHandshake() && registration.handler().requiresHandshake()
                && !session.isAuthenticated()) {
            metrics.packetRejected();
            log.warn("session {} ({}) sent {} (0x{}) before completing the handshake; closing",
                    session.id(), session.remoteAddress(), registration.packetType().getSimpleName(), hex(opcode));
            session.close("protocol violation: packet before handshake");
            return DispatchResult.REJECTED_HANDSHAKE_REQUIRED;
        }

        BasePacket packet;
        try {
            packet = registration.newPacket();
        } catch (RuntimeException e) {
            metrics.handlerError();
            log.error("packet factory for opcode 0x{} failed", hex(opcode), e);
            return DispatchResult.HANDLER_ERROR;
        }

        try {
            packet.decode(payload);
        } catch (PacketDecodeException | IndexOutOfBoundsException | IllegalArgumentException
                 | IllegalStateException e) {
            return onMalformed(session, registration, payload, e, cfg);
        }
        if (payload.isReadable() && log.isDebugEnabled()) {
            log.debug("session {}: {} left {} trailing byte(s) unread\n{}", session.id(), packet,
                    payload.readableBytes(), HexDump.annotated(payload, payload.readerIndex(), DUMP_BYTES));
        }

        try {
            registration.invoke(session, packet);
            metrics.packetHandled();
            return DispatchResult.HANDLED;
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            metrics.handlerError();
            log.error("handler {} failed on {} from session {} ({})", registration.handler().name(), packet,
                    session.id(), session.remoteAddress(), t);
            return DispatchResult.HANDLER_ERROR;
        }
    }

    private DispatchResult onRateLimited(NetworkSession session, int opcode, EngineConfig.RateLimit limit) {
        int count = session.recordRateLimitViolation();
        metrics.packetRateLimited();
        if (count == 1 || count % 100 == 0) {
            log.warn("session {} ({}) exceeded the inbound rate limit ({} pkt/s, burst {}); dropped opcode 0x{} "
                    + "(violation #{})", session.id(), session.remoteAddress(), limit.packetsPerSecond(),
                    limit.burst(), hex(opcode), count);
        }
        if (limit.maxViolations() > 0 && count > limit.maxViolations()) {
            session.close("flood: inbound rate limit exceeded " + count + " times");
        }
        return DispatchResult.RATE_LIMITED;
    }

    private DispatchResult onUnknownOpcode(NetworkSession session, int opcode, ByteBuf payload, EngineConfig cfg) {
        int count = session.recordUnknownPacket();
        metrics.unknownOpcode();
        if (cfg.engine().logUnknownOpcodes()) {
            log.warn("session {} ({}) sent unknown opcode 0x{} with {} byte(s)\n{}", session.id(),
                    session.remoteAddress(), hex(opcode), payload.readableBytes(),
                    HexDump.annotated(payload, -1, DUMP_BYTES));
        }
        int limit = cfg.security().maxUnknownPacketsPerSession();
        if (limit > 0 && count > limit) {
            session.close("too many unknown opcodes (" + count + ")");
        }
        return DispatchResult.UNKNOWN_OPCODE;
    }

    private DispatchResult onMalformed(NetworkSession session, PacketRegistry.Registration<?> registration,
                                       ByteBuf payload, Exception cause, EngineConfig cfg) {
        int count = session.recordMalformedPacket();
        metrics.malformedPacket();
        int offset = cause instanceof PacketDecodeException pde ? pde.errorOffset() : -1;
        log.warn("session {} ({}) sent malformed {} (0x{}): {}\n{}", session.id(), session.remoteAddress(),
                registration.packetType().getSimpleName(), hex(registration.opcode()), cause.getMessage(),
                HexDump.annotated(payload, offset, DUMP_BYTES));
        int limit = cfg.security().maxMalformedPacketsPerSession();
        if (limit > 0 && count > limit) {
            session.close("too many malformed packets (" + count + ")");
        }
        return DispatchResult.MALFORMED;
    }

    private static String hex(int opcode) {
        return String.format("%04X", opcode);
    }
}
