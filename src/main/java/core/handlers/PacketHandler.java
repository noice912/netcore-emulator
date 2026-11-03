package core.handlers;

import core.network.NetworkSession;

/**
 * Processes one decoded packet for one session.
 *
 * <p>Handlers run on the network event-loop thread that owns the session's connection, so a handler
 * must return quickly. Long-running work (disk, database, blocking calls) should be submitted to an
 * executor and reply to the session asynchronously through {@link NetworkSession#send}.
 *
 * <p>A handler may throw; the dispatcher logs the failure and keeps the engine running.
 *
 * @param <T> the packet class this handler accepts
 */
public interface PacketHandler<T extends BasePacket> {

    /** The exact packet class this handler accepts; used by the dispatcher for a runtime type check. */
    Class<T> packetType();

    /**
     * Handle one packet.
     *
     * @param session the session the packet arrived on; never null and open at the time of the call
     * @param packet  the fully decoded packet; never null
     */
    void execute(NetworkSession session, T packet) throws Exception;

    /**
     * Whether the session must have completed the handshake before this packet is accepted.
     * Only the handshake itself (and other pre-authentication traffic) should return {@code false}.
     * For UDP, an opcode whose handler returns {@code false} is also the only kind of datagram that
     * may open a new session from an unknown endpoint.
     */
    default boolean requiresHandshake() {
        return true;
    }

    /** Short name for log lines. */
    default String name() {
        return getClass().getSimpleName();
    }
}
