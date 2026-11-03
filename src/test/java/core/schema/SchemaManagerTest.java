package core.schema;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.BasePacket;
import core.handlers.DispatchResult;
import core.handlers.PacketDecodeException;
import core.handlers.PacketDispatcher;
import core.handlers.PacketHandler;
import core.handlers.PacketRegistry;
import core.handlers.builtin.BuiltinHandlers;
import core.network.Frame;
import core.network.NetworkSession;
import core.network.Protocol;
import core.network.SessionManager;
import core.support.CapturingTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaManagerTest {

    static final String SCHEMA = """
            { "packets": [
                { "name": "HelloReq", "opcode": "0x0100", "fields": [
                    {"name":"name","type":"string"}, {"name":"build","type":"u32"} ] },
                { "name": "HelloRsp", "opcode": "0x0101", "fields": [
                    {"name":"retcode","type":"i32"}, {"name":"greeting","type":"string"},
                    {"name":"sessionId","type":"i64"}, {"name":"build","type":"u32"}, {"name":"serverTime","type":"i64"} ] },
                { "name": "Bye", "opcode": "0x0102" } ],
              "responses": [
                { "on": "HelloReq", "send": "HelloRsp", "fields": {
                    "retcode": 0, "greeting": "welcome ${request.name}", "sessionId": "${session.id}",
                    "build": "${request.build}", "serverTime": "${now.millis}" } },
                { "on": "Bye", "send": "HelloRsp", "closeAfter": true, "fields": { "retcode": 1, "greeting": "bye" } } ] }
            """;

    @TempDir
    Path dir;

    private PacketRegistry registry;
    private SchemaManager manager;
    private PacketDispatcher dispatcher;
    private CapturingTransport transport;
    private NetworkSession session;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("hello.json"), SCHEMA, StandardCharsets.UTF_8);
        registry = new PacketRegistry();
        BuiltinHandlers.registerAll(registry);
        manager = new SchemaManager(registry, dir);
        EngineMetrics metrics = new EngineMetrics();
        EngineConfig config = EngineConfig.defaults();
        dispatcher = new PacketDispatcher(registry, () -> config, metrics);
        SessionManager sessions = new SessionManager(() -> config, metrics);
        transport = new CapturingTransport();
        session = sessions.open(Protocol.TCP, new InetSocketAddress("127.0.0.1", 40000), transport).orElseThrow();
    }

    private DispatchResult dispatch(SchemaPacket packet) {
        Frame frame = packet.toFrame(UnpooledByteBufAllocator.DEFAULT);
        try {
            return dispatcher.dispatch(session, frame);
        } finally {
            frame.release();
        }
    }

    private SchemaPacket lastResponse(String packetName) throws PacketDecodeException {
        CapturingTransport.Sent sent = transport.last().orElseThrow();
        SchemaPacket response = manager.build(packetName);
        assertEquals(response.opcode(), sent.opcode());
        response.decode(Unpooled.wrappedBuffer(sent.payload()));
        return response;
    }

    @Test
    void loadRegistersEverySchemaPacket() {
        SchemaManager.LoadResult result = manager.reload();
        assertTrue(result.success(), result.message());
        assertEquals(3, result.packets());
        assertEquals(2, result.rules());
        assertEquals(3, manager.registeredCount());
        assertTrue(registry.contains(0x0100));
        assertTrue(registry.contains(0x0101));
        assertTrue(registry.contains(0x0102));
        assertEquals(3 + 3, registry.size(), "three builtins plus three schema packets");
    }

    @Test
    void responseRuleBuildsReplyFromTemplates() throws Exception {
        manager.reload();
        SchemaPacket request = manager.build("HelloReq").set("name", "josh").set("build", 42);
        assertEquals(DispatchResult.HANDLED, dispatch(request));

        SchemaPacket response = lastResponse("HelloRsp");
        assertEquals("welcome josh", response.getString("greeting"));
        assertEquals(session.id(), response.getLong("sessionId"));
        assertEquals(42L, response.getLong("build"));
        assertEquals(0L, response.getLong("retcode"));
        assertTrue(response.getLong("serverTime") > 0);
        assertEquals(1, manager.packetsHandled());
        assertEquals(1, manager.responsesSent());
        assertTrue(session.isOpen());
    }

    @Test
    void closeAfterRuleClosesTheSessionAfterReplying() throws Exception {
        manager.reload();
        assertEquals(DispatchResult.HANDLED, dispatch(manager.build("Bye")));
        assertTrue(transport.last().orElseThrow().closedAfter());
        assertEquals("bye", lastResponse("HelloRsp").getString("greeting"));
        assertFalse(session.isOpen());
    }

    @Test
    void listenersSeePacketsBeforeRulesRun() {
        manager.reload();
        List<String> seen = new ArrayList<>();
        manager.addListener((s, p) -> seen.add(p.name() + "@" + transport.count()));
        dispatch(manager.build("HelloReq").set("name", "x"));
        assertEquals(List.of("HelloReq@0"), seen, "listener runs before the response is sent");
        assertEquals(1, transport.count());
    }

    @Test
    void failingListenerDoesNotBlockResponses() throws Exception {
        manager.reload();
        manager.addListener((s, p) -> {
            throw new IllegalStateException("listener boom");
        });
        assertEquals(DispatchResult.HANDLED, dispatch(manager.build("HelloReq").set("name", "x")));
        assertEquals("welcome x", lastResponse("HelloRsp").getString("greeting"));
    }

    /** A hand-written packet sitting on an opcode the schema also wants. */
    static final class JavaOwnedPacket extends BasePacket {
        JavaOwnedPacket() {
            super(0x0100);
        }

        @Override
        public void decode(ByteBuf in) {
        }

        @Override
        public void encode(ByteBuf out) {
        }
    }

    @Test
    void javaHandlerKeepsItsOpcode() {
        registry.register(0x0100, JavaOwnedPacket::new, new PacketHandler<JavaOwnedPacket>() {
            @Override
            public Class<JavaOwnedPacket> packetType() {
                return JavaOwnedPacket.class;
            }

            @Override
            public void execute(NetworkSession session, JavaOwnedPacket packet) {
            }
        });
        SchemaManager.LoadResult result = manager.reload();
        assertTrue(result.success());
        assertEquals(2, result.packets(), "HelloReq is skipped because 0x0100 is Java-owned");
        assertTrue(result.message().contains("skipped"));
        assertEquals(JavaOwnedPacket.class, registry.lookup(0x0100).orElseThrow().packetType());
    }

    @Test
    void reloadDropsPacketsRemovedFromTheSchema() throws Exception {
        manager.reload();
        assertTrue(registry.contains(0x0102));
        Files.writeString(dir.resolve("hello.json"),
                "{ \"packets\": [ { \"name\": \"HelloReq\", \"opcode\": \"0x0100\" } ] }", StandardCharsets.UTF_8);
        SchemaManager.LoadResult result = manager.reload();
        assertTrue(result.success());
        assertEquals(1, result.packets());
        assertTrue(registry.contains(0x0100));
        assertFalse(registry.contains(0x0101));
        assertFalse(registry.contains(0x0102));
        assertEquals(1, manager.current().packets().size());
    }

    @Test
    void brokenFileKeepsThePreviousRegistrations() throws Exception {
        manager.reload();
        Files.writeString(dir.resolve("hello.json"), "{ nope", StandardCharsets.UTF_8);
        SchemaManager.LoadResult result = manager.reload();
        assertFalse(result.success());
        assertEquals(3, result.packets());
        assertTrue(registry.contains(0x0100));
        assertEquals(3, manager.current().packets().size());
        assertEquals(DispatchResult.HANDLED, dispatch(manager.build("HelloReq").set("name", "still works")));
    }

    @Test
    void buildingAnUnknownPacketFails() {
        manager.reload();
        assertThrows(IllegalArgumentException.class, () -> manager.build("Nope"));
    }

    @Test
    void reloadReplacesSurvivingOpcodeInPlaceWithTheNewLayout() throws Exception {
        manager.reload();
        assertTrue(registry.contains(0x0100));
        // Change HelloReq's field layout but keep its opcode: it must stay registered across the
        // reload (never transiently unregistered) and pick up the new definition.
        Files.writeString(dir.resolve("hello.json"),
                "{ \"packets\": [ { \"name\": \"HelloReq\", \"opcode\": \"0x0100\", \"fields\": ["
                        + " {\"name\":\"extra\",\"type\":\"u32\"} ] } ] }",
                StandardCharsets.UTF_8);
        SchemaManager.LoadResult result = manager.reload();

        assertTrue(result.success(), result.message());
        assertTrue(registry.contains(0x0100), "surviving opcode stays registered across the reload");
        SchemaPacket packet = (SchemaPacket) registry.lookup(0x0100).orElseThrow().newPacket();
        assertTrue(packet.def().field("extra").isPresent(), "new field layout is live");
        assertTrue(packet.def().field("name").isEmpty(), "old field layout is gone");
        // HelloRsp (0x0101) and Bye (0x0102) are gone from this file now, so they are unregistered.
        assertFalse(registry.contains(0x0101));
        assertFalse(registry.contains(0x0102));
    }
}
