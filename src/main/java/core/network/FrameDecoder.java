package core.network;

import core.engine.EngineConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * TCP inbound: accumulates stream bytes and emits complete {@link Frame}s. Limits and the cipher
 * key are read from the live configuration on every frame, so a reload applies immediately.
 */
public final class FrameDecoder extends ByteToMessageDecoder {

    private final Supplier<EngineConfig> config;

    public FrameDecoder(Supplier<EngineConfig> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        EngineConfig cfg = config.get();
        Frame frame = FrameCodec.decode(in, cfg.network().dispatch().maxPayloadBytes(),
                cfg.security().encryption().keyOrNull());
        if (frame != null) {
            out.add(frame);
        }
    }
}
