package core.handlers;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.builtin.BuiltinHandlers;
import core.handlers.builtin.DisconnectPacket;
import core.handlers.builtin.EchoPacket;
import core.handlers.builtin.Opcodes;
import core.handlers.builtin.PingPacket;
import core.handlers.builtin.PongPacket;
import core.network.Frame;
import core.network.NetworkSession;
import core.network.Protocol;
import core.network.SessionManager;
import core.support.CapturingTransport;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketDispatcherTest {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 50000);

    private record Fixture(PacketDispatcher dispatcher, SessionManager sessions, EngineMetrics metrics,
                           CapturingTransport transport, NetworkSession session) {
    }

    private static Fixture newFixture(EngineConfig config) {
        AtomicReference<EngineConfig> ref = new AtomicReference<>(config);
        EngineMetrics metrics = new EngineMetrics();
        SessionManager sessions = new SessionManager(ref::get, metrics);
        PacketRegistry registry = new PacketRegistry();
        BuiltinHandlers.registerAll(registry);
        PacketDispatcher dispatcher = new PacketDispatcher(registry, ref::get, metrics);
        CapturingTransport transport = new CapturingTransport();
        NetworkSession session = sessions.open(Protocol.TCP, LOOPBACK, transport).orElseThrow();
        return new Fixture(dispatcher, sessions, metrics, transport, session);
    }

    private static Frame frameOf(core.handlers.BasePacket packet) {
        return packet.toFrame(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT);
    }

    @Test
    void knownPacketIsHandled() throws Exception {
        Fixture f = newFixture(EngineConfig.defaults());
        Frame frame = frameOf(new PingPacket(123L));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();

        assertEquals(DispatchResult.HANDLED, result);
        PongPacket pong = f.transport().lastAs(new PongPacket());
        assertEquals(123L, pong.clientTimeMillis());
        assertEquals(1, f.metrics().snapshot().packetsHandled());
    }

    @Test
    void echoRoundTripsThroughHandler() throws Exception {
        Fixture f = newFixture(EngineConfig.defaults());
        Frame frame = frameOf(new EchoPacket("ping-pong"));
        f.dispatcher().dispatch(f.session(), frame);
        frame.release();
        assertEquals("ping-pong", f.transport().lastAs(new EchoPacket()).message());
    }

    @Test
    void unknownOpcodeIsCountedNotFatal() {
        Fixture f = newFixture(EngineConfig.defaults());
        Frame frame = new Frame(0xABCD, Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();

        assertEquals(DispatchResult.UNKNOWN_OPCODE, result);
        assertEquals(1, f.metrics().snapshot().unknownOpcodes());
        assertTrue(f.session().isOpen());
    }

    @Test
    void malformedPayloadIsReportedNotFatal() {
        Fixture f = newFixture(EngineConfig.defaults());
        // PING needs 8 bytes; give it 3.
        Frame frame = new Frame(Opcodes.PING, Unpooled.wrappedBuffer(new byte[]{0, 0, 0}));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();

        assertEquals(DispatchResult.MALFORMED, result);
        assertEquals(1, f.metrics().snapshot().malformedPackets());
        assertTrue(f.session().isOpen());
    }

    @Test
    void nullInputsAreDropped() {
        Fixture f = newFixture(EngineConfig.defaults());
        assertEquals(DispatchResult.DROPPED_NULL, f.dispatcher().dispatch(f.session(), null));
        assertEquals(DispatchResult.DROPPED_NULL, f.dispatcher().dispatch(null, frameOf(new PingPacket(1))));
    }

    @Test
    void closedSessionRejectsTraffic() {
        Fixture f = newFixture(EngineConfig.defaults());
        f.session().close("test");
        Frame frame = frameOf(new PingPacket(1L));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();
        assertEquals(DispatchResult.REJECTED_SESSION_CLOSED, result);
    }

    @Test
    void handshakeGateBlocksPreAuthTrafficAndClosesSession() throws Exception {
        EngineConfig config = EngineConfig.parse("{\"security\":{\"requireHandshake\":true}}", "test");
        Fixture f = newFixture(config);

        // ECHO requires a handshake; the fresh session has not authenticated.
        Frame frame = frameOf(new EchoPacket("too early"));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();

        assertEquals(DispatchResult.REJECTED_HANDSHAKE_REQUIRED, result);
        assertFalse(f.session().isOpen());
        assertEquals(1, f.metrics().snapshot().packetsRejected());
    }

    @Test
    void authenticatedSessionPassesTheGate() throws Exception {
        EngineConfig config = EngineConfig.parse("{\"security\":{\"requireHandshake\":true}}", "test");
        Fixture f = newFixture(config);
        assertTrue(f.session().markAuthenticated(1, "token"));

        Frame frame = frameOf(new EchoPacket("now allowed"));
        DispatchResult result = f.dispatcher().dispatch(f.session(), frame);
        frame.release();

        assertEquals(DispatchResult.HANDLED, result);
        assertEquals("now allowed", f.transport().lastAs(new EchoPacket()).message());
    }

    @Test
    void repeatedUnknownOpcodesEventuallyCloseSession() throws Exception {
        EngineConfig config = EngineConfig.parse(
                "{\"security\":{\"maxUnknownPacketsPerSession\":3}}", "test");
        Fixture f = newFixture(config);
        for (int i = 0; i < 4; i++) {
            Frame frame = new Frame(0x7777, Unpooled.EMPTY_BUFFER.copy());
            f.dispatcher().dispatch(f.session(), frame);
            frame.release();
        }
        assertFalse(f.session().isOpen(), "session should close after exceeding the unknown-opcode limit");
    }

    @Test
    void canOpenSessionOnlyForHandshakeFreeOpcodes() {
        Fixture f = newFixture(EngineConfig.defaults());
        // DISCONNECT does not require a handshake; ECHO does.
        assertTrue(f.dispatcher().canOpenSession(Opcodes.DISCONNECT));
        assertFalse(f.dispatcher().canOpenSession(Opcodes.ECHO));
        assertFalse(f.dispatcher().canOpenSession(0x1234));
    }

    @Test
    void disconnectPacketClosesSession() {
        Fixture f = newFixture(EngineConfig.defaults());
        assertSame(NetworkSession.State.CONNECTED, f.session().state());
        Frame frame = frameOf(new DisconnectPacket("bye"));
        f.dispatcher().dispatch(f.session(), frame);
        frame.release();
        assertFalse(f.session().isOpen());
    }

    @Test
    void floodIsRateLimitedThenSessionIsClosed() throws Exception {
        EngineConfig config = EngineConfig.parse(
                "{\"security\":{\"rateLimit\":{\"packetsPerSecond\":1,\"burst\":2,\"maxViolations\":1}}}", "test");
        Fixture f = newFixture(config);

        for (int i = 0; i < 2; i++) {
            Frame frame = frameOf(new PingPacket(i));
            assertEquals(DispatchResult.HANDLED, f.dispatcher().dispatch(f.session(), frame), "burst packet " + i);
            frame.release();
        }
        Frame third = frameOf(new PingPacket(3));
        assertEquals(DispatchResult.RATE_LIMITED, f.dispatcher().dispatch(f.session(), third));
        third.release();
        assertTrue(f.session().isOpen(), "first violation is within maxViolations");

        Frame fourth = frameOf(new PingPacket(4));
        assertEquals(DispatchResult.RATE_LIMITED, f.dispatcher().dispatch(f.session(), fourth));
        fourth.release();
        assertFalse(f.session().isOpen(), "second violation exceeds maxViolations=1");
        assertEquals(2, f.metrics().snapshot().packetsRateLimited());
        assertEquals(2, f.session().rateLimitViolations());
    }

    @Test
    void rateLimitCanBeDisabled() throws Exception {
        EngineConfig config = EngineConfig.parse("{\"security\":{\"rateLimit\":{\"enabled\":false}}}", "test");
        Fixture f = newFixture(config);
        for (int i = 0; i < 50; i++) {
            Frame frame = frameOf(new PingPacket(i));
            assertEquals(DispatchResult.HANDLED, f.dispatcher().dispatch(f.session(), frame));
            frame.release();
        }
        assertEquals(0, f.metrics().snapshot().packetsRateLimited());
    }
}
