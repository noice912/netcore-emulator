package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.PacketDispatcher;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * UDP "gateway" listener. One datagram is one frame. Sessions are keyed by sender address and
 * port; when the handshake requirement is on, only an opcode whose handler does not require a
 * handshake may create a session from an unknown endpoint, so stray datagrams never allocate
 * state. Idle UDP sessions are reaped by the engine's maintenance task.
 */
public final class UdpGatewayServer implements NetworkListener {

    private static final Logger log = LoggerFactory.getLogger(UdpGatewayServer.class);

    record Binding(String bindAddress, int port) {
        static Binding of(EngineConfig cfg) {
            return new Binding(cfg.network().gatewayBindAddress(), cfg.network().gateway().port());
        }
    }

    private final EventLoopGroup group;
    private final Supplier<EngineConfig> config;
    private final SessionManager sessions;
    private final PacketDispatcher dispatcher;
    private final EngineMetrics metrics;

    private volatile Channel channel;
    private volatile Binding binding;

    public UdpGatewayServer(EventLoopGroup group, Supplier<EngineConfig> config, SessionManager sessions,
                            PacketDispatcher dispatcher, EngineMetrics metrics) {
        this.group = Objects.requireNonNull(group, "group");
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String name() {
        return "UDP gateway";
    }

    @Override
    public Protocol protocol() {
        return Protocol.UDP;
    }

    @Override
    public synchronized void start() throws IOException, InterruptedException {
        if (channel != null) {
            return;
        }
        EngineConfig cfg = config.get();
        Binding target = Binding.of(cfg);
        int bufferBytes = cfg.network().gateway().socketBufferBytes();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, bufferBytes)
                .option(ChannelOption.SO_SNDBUF, bufferBytes)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .handler(new GatewayHandler());

        InetSocketAddress address = new InetSocketAddress(target.bindAddress(), target.port());
        ChannelFuture bound = bootstrap.bind(address).await();
        if (!bound.isSuccess()) {
            throw new IOException(name() + " listener could not bind " + address + ": " + bound.cause(), bound.cause());
        }
        channel = bound.channel();
        binding = target;
        log.info("{} listener bound on {}", name(), channel.localAddress());
    }

    @Override
    public synchronized void stop() {
        Channel ch = channel;
        if (ch == null) {
            return;
        }
        channel = null;
        binding = null;
        sessions.closeAll(Protocol.UDP, "gateway stopped");
        try {
            ch.close().await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("{} listener on {} stopped", name(), ch.localAddress());
    }

    @Override
    public boolean isRunning() {
        Channel ch = channel;
        return ch != null && ch.isActive();
    }

    @Override
    public int boundPort() {
        Channel ch = channel;
        return ch != null && ch.localAddress() instanceof InetSocketAddress addr ? addr.getPort() : -1;
    }

    @Override
    public boolean needsRebind(EngineConfig updated) {
        Binding current = binding;
        return current != null && !current.equals(Binding.of(updated));
    }

    /** Pipeline handler for the single datagram channel. */
    private final class GatewayHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagram) {
            InetSocketAddress sender = datagram.sender();
            ByteBuf content = datagram.content();
            EngineConfig cfg = config.get();

            Frame frame;
            try {
                frame = FrameCodec.decode(content, cfg.network().gateway().maxPayloadBytes(),
                        cfg.security().encryption().keyOrNull());
            } catch (CorruptedFrameException | TooLongFrameException e) {
                metrics.malformedPacket();
                log.debug("dropping datagram from {}: {}", sender, e.getMessage());
                return;
            }
            if (frame == null) {
                metrics.malformedPacket();
                log.debug("dropping truncated datagram from {} ({} bytes)", sender, content.readableBytes());
                return;
            }

            try {
                NetworkSession session = sessions.find(Protocol.UDP, sender).orElse(null);
                if (session == null) {
                    if (cfg.security().requireHandshake() && !dispatcher.canOpenSession(frame.opcode())) {
                        metrics.packetRejected();
                        log.debug("dropping opcode 0x{} from unknown endpoint {}: handshake required first",
                                String.format("%04X", frame.opcode()), sender);
                        return;
                    }
                    session = sessions.open(Protocol.UDP, sender, new UdpTransport(ctx.channel(), sender, config))
                            .orElse(null);
                    if (session == null) {
                        return;
                    }
                }
                dispatcher.dispatch(session, frame);
            } finally {
                frame.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (cause instanceof PortUnreachableException) {
                // ICMP reply for a datagram we sent to a peer that went away; the channel stays usable.
                log.debug("peer port unreachable; ignoring");
                return;
            }
            log.warn("{} pipeline error (listener stays up)", name(), cause);
        }
    }
}
