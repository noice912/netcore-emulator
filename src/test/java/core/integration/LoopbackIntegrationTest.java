package core.integration;

import core.engine.ServerEngine;
import core.handlers.builtin.DisconnectPacket;
import core.handlers.builtin.EchoPacket;
import core.handlers.builtin.PingPacket;
import core.handlers.builtin.PongPacket;
import core.network.Protocol;
import core.support.Frames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
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

/** End-to-end: bind the real listeners on loopback ephemeral ports and drive them with raw sockets. */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LoopbackIntegrationTest {

    /** Minimal engine: listeners only, every optional subsystem off. */
    static final String BASE = """
            "admin": { "enabled": false },
            "persistence": { "enabled": false },
            "schemas": { "enabled": false },
            "engine": { "consoleEnabled": false, "configWatchEnabled": false, "pluginsEnabled": false,
                        "maintenanceIntervalSeconds": 1 }
            """;

    private ServerEngine engine;

    @BeforeEach
    void startEngine(@TempDir Path dir) throws Exception {
        String json = """
                {
                  "network": {
                    "bindAddress": "127.0.0.1",
                    "dispatch": { "port": 0, "idleTimeoutSeconds": 0 },
                    "gateway":  { "port": 0 }
                  },
                  %s
                }
                """.formatted(BASE);
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, json, StandardCharsets.UTF_8);
        engine = ServerEngine.create(cfg);
        engine.start();
        assertTrue(engine.dispatchPort() > 0, "TCP listener should be bound");
        assertTrue(engine.gatewayPort() > 0, "UDP listener should be bound");
    }

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop();
        }
    }

    @Test
    void tcpEchoRoundTrip() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(Frames.toWire(new EchoPacket("hello engine"), null));
            out.flush();

            Frames.Wire reply = Frames.readWire(socket.getInputStream(), null);
            EchoPacket echo = new EchoPacket();
            echo.decode(reply.payloadBuf());
            assertEquals("hello engine", echo.message());
        }
    }

    @Test
    void tcpPingReceivesPong() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            socket.setSoTimeout(5_000);
            long clientTime = 1234567L;
            socket.getOutputStream().write(Frames.toWire(new PingPacket(clientTime), null));
            socket.getOutputStream().flush();

            Frames.Wire reply = Frames.readWire(socket.getInputStream(), null);
            PongPacket pong = new PongPacket();
            pong.decode(reply.payloadBuf());
            assertEquals(clientTime, pong.clientTimeMillis());
            assertTrue(pong.serverTimeMillis() > 0);
        }
    }

    @Test
    void tcpDisconnectClosesConnection() throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(Frames.toWire(new DisconnectPacket("leaving"), null));
            socket.getOutputStream().flush();
            int eof = socket.getInputStream().read();
            assertEquals(-1, eof, "server should close the connection");
        }
    }

    @Test
    void udpEchoRoundTrip() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5_000);
            InetSocketAddress gateway = new InetSocketAddress(InetAddress.getLoopbackAddress(), engine.gatewayPort());
            byte[] wire = Frames.toWire(new EchoPacket("udp hello"), null);
            socket.send(new DatagramPacket(wire, wire.length, gateway));

            byte[] buffer = new byte[2048];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            Frames.Wire reply = Frames.fromDatagram(response.getData(), response.getLength(), null);
            EchoPacket echo = new EchoPacket();
            echo.decode(reply.payloadBuf());
            assertEquals("udp hello", echo.message());
            assertTrue(engine.sessions().count(Protocol.UDP) >= 1);
        }
    }

    @Test
    void encryptedTcpRoundTripWhenConfigured(@TempDir Path dir) throws Exception {
        engine.stop();
        byte[] key = {0x10, 0x20, 0x30, 0x40};
        String json = """
                {
                  "network": { "bindAddress": "127.0.0.1",
                    "dispatch": { "port": 0, "idleTimeoutSeconds": 0 }, "gateway": { "enabled": false } },
                  "security": { "encryption": { "enabled": true, "keyHex": "10203040" } },
                  %s
                }
                """.formatted(BASE);
        Path cfg = dir.resolve("enc.json");
        Files.writeString(cfg, json, StandardCharsets.UTF_8);
        engine = ServerEngine.create(cfg);
        engine.start();

        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), engine.dispatchPort())) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(Frames.toWire(new EchoPacket("ciphered"), key));
            socket.getOutputStream().flush();

            Frames.Wire reply = Frames.readWire(socket.getInputStream(), key);
            EchoPacket echo = new EchoPacket();
            echo.decode(reply.payloadBuf());
            assertEquals("ciphered", echo.message());
        }
    }

    @Test
    void statusReportsBoundListeners() {
        String status = engine.status();
        assertTrue(status.contains("listening on port " + engine.dispatchPort()), status);
        assertTrue(status.contains("listening on port " + engine.gatewayPort()), status);
        assertTrue(engine.statusSnapshot().running());
        assertEquals("netcore-emulator", engine.statusSnapshot().name());
    }
}
