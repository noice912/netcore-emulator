package core.network;

import core.engine.EngineConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.Objects;
import java.util.function.Supplier;

/** TCP outbound: serializes {@link Frame}s. The frame is released by Netty after encoding. */
public final class FrameEncoder extends MessageToByteEncoder<Frame> {

    private final Supplier<EngineConfig> config;

    public FrameEncoder(Supplier<EngineConfig> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, Frame msg, boolean preferDirect) {
        int size = FrameCodec.encodedLength(msg);
        return preferDirect ? ctx.alloc().ioBuffer(size) : ctx.alloc().heapBuffer(size);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Frame msg, ByteBuf out) {
        FrameCodec.encode(msg, out, config.get().security().encryption().keyOrNull());
    }
}
