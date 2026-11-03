package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.PacketDispatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Last handler in each TCP connection's pipeline. Opens the session when the connection becomes
 * active, feeds decoded frames to the dispatcher, enforces the handshake deadline, and closes the
 * session on idle timeout, decoder failure, I/O error or the peer disconnecting.
 *
 * <p>One instance per connection; its fields are only touched on that connection's event loop.
 */
final class TcpSessionHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger log = LoggerFactory.getLogger(TcpSessionHandler.class);

    private final Supplier<EngineConfig> config;
    private final SessionManager sessions;
    private final PacketDispatcher dispatcher;
    private final EngineMetrics metrics;

    private NetworkSession session;
    private ScheduledFuture<?> handshakeTimer;

    TcpSessionHandler(Supplier<EngineConfig> config, SessionManager sessions, PacketDispatcher dispatcher,
                      EngineMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        SocketAddress remote = ctx.channel().remoteAddress();
        if (!(remote instanceof InetSocketAddress inet)) {
            log.warn("closing TCP channel with unsupported remote address {}", remote);
            ctx.close();
            return;
        }
        Optional<NetworkSession> opened = sessions.open(Protocol.TCP, inet, new TcpTransport(ctx.channel()));
        if (opened.isEmpty()) {
            ctx.close();
            return;
        }
        session = opened.get();

        EngineConfig.Security security = config.get().security();
        int timeout = security.handshakeTimeoutSeconds();
        if (security.requireHandshake() && timeout > 0) {
            NetworkSession s = session;
            handshakeTimer = ctx.executor().schedule(() -> {
                if (s.isOpen() && !s.isAuthenticated()) {
                    s.close("handshake timeout (" + timeout + "s)");
                }
            }, timeout, TimeUnit.SECONDS);
        }
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (session == null) {
            return; // admission was refused; the channel is already closing
        }
        dispatcher.dispatch(session, frame);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelHandshakeTimer();
        if (session != null) {
            session.close("connection closed by peer");
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            if (session != null) {
                session.close("idle timeout");
            } else {
                ctx.close();
            }
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String who = session != null ? "session " + session.id() : "channel " + ctx.channel().remoteAddress();
        if (cause instanceof DecoderException) {
            Throwable root = cause.getCause() != null ? cause.getCause() : cause;
            metrics.malformedPacket();
            log.warn("{}: malformed stream ({}); closing", who, root.getMessage());
            closeSession(ctx, "malformed stream: " + root.getMessage());
        } else if (cause instanceof IOException) {
            // Connection reset, broken pipe and friends: the peer vanished without a DISCONNECT.
            log.info("{}: connection error ({}); closing", who, cause.getMessage());
            closeSession(ctx, "connection error: " + cause.getMessage());
        } else {
            log.error("{}: unexpected pipeline error; closing", who, cause);
            closeSession(ctx, "internal error: " + cause);
        }
    }

    private void closeSession(ChannelHandlerContext ctx, String reason) {
        cancelHandshakeTimer();
        if (session != null) {
            session.close(reason);
        } else {
            ctx.close();
        }
    }

    private void cancelHandshakeTimer() {
        if (handshakeTimer != null) {
            handshakeTimer.cancel(false);
            handshakeTimer = null;
        }
    }
}
