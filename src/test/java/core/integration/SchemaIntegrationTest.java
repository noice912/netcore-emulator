package core.integration;

import core.engine.ServerEngine;
import core.schema.SchemaPacket;
import core.support.Frames;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema-defined packets answered by response rules, end to end over real sockets. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SchemaIntegrationTest {

    static final String SCHEMA = """
            { "structs": { "Vec3": [ {"name":"x","type":"f32"}, {"name":"y","type":"f32"}, {"name":"z","type":"f32"} ] },
              "packets": [
                { "name": "HelloRequest",  "opcode": "0x0100", "fields": [
                    {"name":"clientName","type":"string"}, {"name":"clientBuild","type":"u32"} ] },
                { "name": "HelloResponse", "opcode": "0x0101", "fields": [
                    {"name":"retcode","type":"i32"}, {"name":"sessionId","type":"i64"}, {"name":"message","type":"string"} ] },
                { "name": "MoveRequest",   "opcode": "0x0110", "fields": [
                    {"name":"entityId","type":"u32"}, {"name":"position","type":"struct","struct":"Vec3"} ] },
                { "name": "MoveAck",       "opcode": "0x0111", "fields": [
                    {"name":"entityId","type":"u32"}, {"name":"position","type":"struct","struct":"Vec3"}, {"name":"accepted","type":"bool"} ] } ],
              "responses": [
                { "on": "HelloRequest", "send": "HelloResponse", "fields": {
                    "retcode": 0, "sessionId": "${session.id}", "message": "Welcome, ${request.clientName} (build ${request.clientBuild})" } },
                { "on": "MoveRequest", "send": "MoveAck", "fields": {
                    "entityId": "${request.entityId}", "position": "${request.position}", "accepted": true } } ] }
            """;

    private ServerEngine engine;
    private Path schemaDir;

    @BeforeEach
    void startEngine(@TempDir Path dir) throws Exception {
        schemaDir = dir.resolve("schemas");
        Files.createDirectories(schemaDir);
        Files.writeString(schemaDir.resolve("game.json"), SCHEMA, StandardCharsets.UTF_8);
        String json = """
                {
                  "network": { "bindAddress": "127.0.0.1", "dispatch": { "port": 0, "idleTimeoutSeconds": 0 }, "gateway": { "port": 0 } },
                  "admin": { "enabled": false },
                  "persistence": { "enabled": false },
                  "schemas": { "enabled": true, "directory": "%s", "hotReload": true, "debounceMillis": 50 },
                  "engine": { "consoleEnabled": false, "configWatchEnabled": false, "pluginsEnabled": false }
                }
                """.formatted(schemaDir.toString().replace('\\', '/'));
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, json, StandardCharsets.UTF_8);
        engine = ServerEngine.create(cfg);
        engine.start();
        assertEquals(4, engine.schemas().current().packets().size());
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void helloOverTcpIsAnsweredByTheResponseRule() throws Exception {
        SchemaPacket hello = engine.schemas().build("HelloRequest").set("clientName", "tester").set("clientBuild", 1234);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(Frames.toWire(hello, null));
            socket.getOutputStream().flush();

            Frames.Wire reply = Frames.readWire(socket.getInputStream(), null);
            assertEquals(0x0101, reply.opcode());
            SchemaPacket response = engine.schemas().build("HelloResponse");
            response.decode(reply.payloadBuf());
            assertEquals(0L, response.getLong("retcode"));
            assertTrue(response.getLong("sessionId") > 0);
            assertEquals("Welcome, tester (build 1234)", response.getString("message"));
        }
        assertEquals(1, engine.schemas().packetsHandled());
        assertEquals(1, engine.schemas().responsesSent());
    }

    @Test
    void structValuesRoundTripOverUdp() throws Exception {
        SchemaPacket move = engine.schemas().build("MoveRequest").set("entityId", 77)
                .set("position", java.util.Map.of("x", 1.5, "y", -2.0, "z", 3.25));
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5_000);
            byte[] wire = Frames.toWire(move, null);
            socket.send(new DatagramPacket(wire, wire.length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), engine.gatewayPort())));
            byte[] buffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            Frames.Wire reply = Frames.fromDatagram(response.getData(), response.getLength(), null);
            assertEquals(0x0111, reply.opcode());
            SchemaPacket ack = engine.schemas().build("MoveAck");
            ack.decode(Unpooled.wrappedBuffer(reply.payload()));
            assertEquals(77L, ack.getLong("entityId"));
            assertEquals(-2.0, ack.getDouble("position.y"), 1e-6);
            assertEquals(3.25, ack.getDouble("position.z"), 1e-6);
            assertTrue(ack.getBoolean("accepted"));
        }
    }

    @Test
    void editingTheSchemaFileHotReloadsPackets() throws Exception {
        assertTrue(engine.schemas().isWatching());
        String extended = SCHEMA.substring(0, SCHEMA.lastIndexOf("],")) // cut before the responses close
                .replace("\"packets\": [", "\"packets\": [ { \"name\": \"Extra\", \"opcode\": \"0x0120\" },")
                + "] }";
        Files.writeString(schemaDir.resolve("game.json"), extended, StandardCharsets.UTF_8);

        long deadline = System.currentTimeMillis() + 10_000;
        while (!engine.registry().contains(0x0120) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(engine.registry().contains(0x0120), "new packet should be registered after the file change");
        assertEquals(5, engine.schemas().current().packets().size());
    }
}
