package core.network;

import core.engine.EngineConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link SessionTransport} over the shared UDP gateway channel: every send is one datagram to the
 * session's remote endpoint. There is no connection to close; the session manager drops the
 * endpoint mapping instead.
 */
final class UdpTransport implements SessionTransport {

    private final Channel channel;
    private final InetSocketAddress remote;
    private final Supplier<EngineConfig> config;

    UdpTransport(Channel channel, InetSocketAddress remote, Supplier<EngineConfig> config) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.remote = Objects.requireNonNull(remote, "remote");
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public void send(Frame frame, boolean closeAfterWrite) {
        ByteBuf out = channel.alloc().ioBuffer(FrameCodec.encodedLength(frame));
        try {
            FrameCodec.encode(frame, out, config.get().security().encryption().keyOrNull());
        } catch (RuntimeException e) {
            out.release();
            throw e;
        } finally {
            frame.release();
        }
        channel.writeAndFlush(new DatagramPacket(out, remote));
    }

    @Override
    public void close() {
        // Connectionless: nothing to tear down on the wire.
    }

    @Override
    public boolean isActive() {
        return channel.isActive();
    }

    @Override
    public ByteBufAllocator allocator() {
        return channel.alloc();
    }
}
