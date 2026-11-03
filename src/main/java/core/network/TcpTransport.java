package core.network;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/** {@link SessionTransport} over a Netty socket channel. Writes go through {@link FrameEncoder}. */
final class TcpTransport implements SessionTransport {

    private static final Logger log = LoggerFactory.getLogger(TcpTransport.class);

    private final Channel channel;

    TcpTransport(Channel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    @Override
    public void send(Frame frame, boolean closeAfterWrite) {
        // writeAndFlush is thread-safe and releases the frame on failure (e.g. channel already closed).
        ChannelFuture future = channel.writeAndFlush(frame);
        future.addListener(f -> {
            if (!f.isSuccess() && log.isDebugEnabled()) {
                log.debug("write to {} failed: {}", channel.remoteAddress(), String.valueOf(f.cause()));
            }
        });
        if (closeAfterWrite) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void close() {
        if (channel.isOpen()) {
            channel.close();
        }
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
