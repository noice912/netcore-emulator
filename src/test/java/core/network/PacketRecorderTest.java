package core.network;

import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.handlers.builtin.EchoPacket;
import core.support.CapturingTransport;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketRecorderTest {

    private static SessionManager manager() {
        EngineConfig config = EngineConfig.defaults();
        return new SessionManager(() -> config, new EngineMetrics());
    }

    @Test
    void writesOneLinePerFrameWithTruncatedHex(@TempDir Path dir) throws Exception {
        SessionManager manager = manager();
        NetworkSession session = manager.open(Protocol.TCP, new InetSocketAddress("127.0.0.1", 5000),
                new CapturingTransport()).orElseThrow();
        PacketRecorder recorder = new PacketRecorder(dir, true, 8);
        assertFalse(recorder.isRecording());
        assertTrue(recorder.start());
        assertFalse(recorder.start(), "second start is a no-op");

        recorder.record(PacketRecorder.Direction.IN, session, 0x0101,
                Unpooled.wrappedBuffer(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}));
        recorder.record(PacketRecorder.Direction.OUT, session, 0x0102, Unpooled.EMPTY_BUFFER);
        recorder.stop();
        assertFalse(recorder.isRecording());
        assertEquals(2, recorder.framesRecorded());

        List<String> lines = Files.readAllLines(recorder.currentFile().orElseThrow());
        assertEquals(3, lines.size(), "header plus two frames");
        assertTrue(lines.get(0).startsWith("# packet capture started"));
        assertTrue(lines.get(1).contains("IN  #" + session.id() + " TCP"), lines.get(1));
        assertTrue(lines.get(1).contains("op=0x0101 len=10 hex=0102030405060708..."), lines.get(1));
        assertTrue(lines.get(2).contains("OUT #" + session.id()), lines.get(2));
        assertTrue(lines.get(2).contains("op=0x0102 len=0"), lines.get(2));
        assertFalse(lines.get(2).contains("hex="), "empty payload has no hex column");
    }

    @Test
    void recordingIsANoOpWhenStopped(@TempDir Path dir) throws Exception {
        SessionManager manager = manager();
        NetworkSession session = manager.open(Protocol.UDP, new InetSocketAddress("127.0.0.1", 5001),
                new CapturingTransport()).orElseThrow();
        PacketRecorder recorder = new PacketRecorder(dir, true, 32);
        recorder.record(PacketRecorder.Direction.IN, session, 1, Unpooled.wrappedBuffer(new byte[]{1}));
        assertEquals(0, recorder.framesRecorded());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(0, files.count(), "no capture file is created until start()");
        }
    }

    @Test
    void sessionOutboundTrafficFlowsThroughTheManagerRecorder(@TempDir Path dir) throws Exception {
        SessionManager manager = manager();
        PacketRecorder recorder = new PacketRecorder(dir, false, 0);
        manager.setRecorder(recorder);
        assertTrue(manager.recorder().isPresent());
        NetworkSession session = manager.open(Protocol.TCP, new InetSocketAddress("127.0.0.1", 5002),
                new CapturingTransport()).orElseThrow();
        recorder.start();
        assertTrue(session.send(new EchoPacket("captured")));
        recorder.stop();
        assertEquals(1, recorder.framesRecorded());
        List<String> lines = Files.readAllLines(recorder.currentFile().orElseThrow());
        assertTrue(lines.get(1).contains("OUT #" + session.id()));
        assertFalse(lines.get(1).contains("hex="), "includePayload=false writes headers only");
    }
}
