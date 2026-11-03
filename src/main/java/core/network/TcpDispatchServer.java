package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.PacketDispatcher;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * TCP "dispatch" listener. Each accepted connection gets its own pipeline:
 * idle watchdog, frame decoder, frame encoder, session handler.
 */
public final class TcpDispatchServer implements NetworkListener {

    private static final Logger log = LoggerFactory.getLogger(TcpDispatchServer.class);

    /** The configuration values a bind depends on; a change in any of them forces a rebind. */
    record Binding(String bindAddress, int port, int backlog) {
        static Binding of(EngineConfig cfg) {
            return new Binding(cfg.network().dispatchBindAddress(), cfg.network().dispatch().port(),
                    cfg.network().dispatch().backlog());
        }
    }

    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Supplier<EngineConfig> config;
    private final SessionManager sessions;
    private final PacketDispatcher dispatcher;
    private final EngineMetrics metrics;
    private final ChannelGroup clients = new DefaultChannelGroup("dispatch-clients", GlobalEventExecutor.INSTANCE);

    private volatile Channel serverChannel;
    private volatile Binding binding;

    public TcpDispatchServer(EventLoopGroup bossGroup, EventLoopGroup workerGroup, Supplier<EngineConfig> config,
                             SessionManager sessions, PacketDispatcher dispatcher, EngineMetrics metrics) {
        this.bossGroup = Objects.requireNonNull(bossGroup, "bossGroup");
        this.workerGroup = Objects.requireNonNull(workerGroup, "workerGroup");
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public String name() {
        return "TCP dispatch";
    }

    @Override
    public Protocol protocol() {
        return Protocol.TCP;
    }

    @Override
    public synchronized void start() throws IOException, InterruptedException {
        if (serverChannel != null) {
            return;
        }
        Binding target = Binding.of(config.get());
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, target.backlog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        clients.add(ch);
                        EngineConfig live = config.get();
                        ChannelPipeline pipeline = ch.pipeline();
                        int idleSeconds = live.network().dispatch().idleTimeoutSeconds();
                        if (idleSeconds > 0) {
                            pipeline.addLast("idle", new IdleStateHandler(idleSeconds, 0, 0));
                        }
                        pipeline.addLast("frame-decoder", new FrameDecoder(config));
                        pipeline.addLast("frame-encoder", new FrameEncoder(config));
                        pipeline.addLast("session", new TcpSessionHandler(config, sessions, dispatcher, metrics));
                    }
                });

        InetSocketAddress address = new InetSocketAddress(target.bindAddress(), target.port());
        ChannelFuture bound = bootstrap.bind(address).await();
        if (!bound.isSuccess()) {
            throw new IOException(name() + " listener could not bind " + address + ": " + bound.cause(), bound.cause());
        }
        serverChannel = bound.channel();
        binding = target;
        log.info("{} listener bound on {}", name(), serverChannel.localAddress());
    }

    @Override
    public synchronized void stop() {
        Channel channel = serverChannel;
        if (channel == null) {
            return;
        }
        serverChannel = null;
        binding = null;
        try {
            channel.close().await(5, TimeUnit.SECONDS);
            clients.close().await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("{} listener on {} stopped", name(), channel.localAddress());
    }

    @Override
    public boolean isRunning() {
        Channel channel = serverChannel;
        return channel != null && channel.isActive();
    }

    @Override
    public int boundPort() {
        Channel channel = serverChannel;
        return channel != null && channel.localAddress() instanceof InetSocketAddress addr ? addr.getPort() : -1;
    }

    @Override
    public boolean needsRebind(EngineConfig updated) {
        Binding current = binding;
        return current != null && !current.equals(Binding.of(updated));
    }

    /** Number of accepted connections currently open on this listener. */
    public int connectionCount() {
        return clients.size();
    }
}
