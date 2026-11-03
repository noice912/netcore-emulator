package core.network;

import io.netty.buffer.ByteBufAllocator;

/**
 * The connection underneath a {@link NetworkSession}. Implemented once per protocol so the session
 * and the packet handlers never need to know whether they are talking over TCP or UDP.
 */
public interface SessionTransport {

    /**
     * Queue a frame for delivery. The transport takes ownership of the frame and releases it once
     * written or failed. With {@code closeAfterWrite} the connection is torn down after the write
     * completes (a no-op for connectionless transports).
     */
    void send(Frame frame, boolean closeAfterWrite);

    /** Tear down the connection. Idempotent. */
    void close();

    boolean isActive();

    /** Allocator for outbound payloads, so sends use the channel's pooled buffers. */
    ByteBufAllocator allocator();
}
