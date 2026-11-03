package core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Writes every inbound and outbound frame to a timestamped capture file, one line per frame:
 * {@code <ISO time> <IN|OUT> #<session> <proto> <remote> op=0x.... len=N hex=...}.
 *
 * <p>Meant for mapping an unknown protocol: run a client against the engine, read the capture,
 * then describe what you saw as a schema. Recording is off until {@link #start()} and can be
 * toggled at runtime from the console or the admin API. When not recording, {@link #record} is a
 * single volatile read, so it costs nothing on the hot path.
 */
public final class PacketRecorder implements AutoCloseable {

    public enum Direction { IN, OUT }

    private static final Logger log = LoggerFactory.getLogger(PacketRecorder.class);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final int FLUSH_EVERY = 64;

    private final Path directory;
    private final boolean includePayload;
    private final int maxBytesPerFrame;
    private final LongAdder frames = new LongAdder();

    private volatile boolean recording;
    private BufferedWriter writer;
    private Path currentFile;
    private int unflushed;

    public PacketRecorder(Path directory, boolean includePayload, int maxBytesPerFrame) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.includePayload = includePayload;
        this.maxBytesPerFrame = Math.max(0, maxBytesPerFrame);
    }

    /** Open a new capture file. Returns {@code false} if already recording. */
    public synchronized boolean start() throws IOException {
        if (recording) {
            return false;
        }
        Files.createDirectories(directory);
        currentFile = directory.resolve("capture-" + FILE_STAMP.format(Instant.now()) + ".log");
        writer = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        writer.write("# packet capture started " + Instant.now()
                + " | columns: time direction session protocol remote opcode length hex");
        writer.newLine();
        unflushed = 0;
        recording = true;
        log.info("packet capture started: {}", currentFile);
        return true;
    }

    public synchronized void stop() {
        if (!recording) {
            return;
        }
        closeWriter();
        log.info("packet capture stopped: {} ({} frame(s) recorded in total)", currentFile, frames.sum());
    }

    private void closeWriter() {
        recording = false;
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            log.warn("error closing capture file {}", currentFile, e);
        }
        writer = null;
    }

    public boolean isRecording() {
        return recording;
    }

    public Optional<Path> currentFile() {
        return Optional.ofNullable(currentFile);
    }

    public long framesRecorded() {
        return frames.sum();
    }

    /** Append one frame. Safe to call from any thread; a no-op when not recording. */
    public void record(Direction direction, NetworkSession session, int opcode, ByteBuf payload) {
        if (!recording) {
            return;
        }
        String line = format(direction, session, opcode, payload);
        synchronized (this) {
            if (!recording || writer == null) {
                return;
            }
            try {
                writer.write(line);
                writer.newLine();
                if (++unflushed >= FLUSH_EVERY) {
                    writer.flush();
                    unflushed = 0;
                }
            } catch (IOException e) {
                log.warn("capture write failed; stopping capture", e);
                closeWriter();
                return;
            }
        }
        frames.increment();
    }

    /** Push buffered lines to disk; the engine calls this from its maintenance task. */
    public synchronized void flush() {
        if (recording && writer != null && unflushed > 0) {
            try {
                writer.flush();
                unflushed = 0;
            } catch (IOException e) {
                log.warn("capture flush failed", e);
            }
        }
    }

    private String format(Direction direction, NetworkSession session, int opcode, ByteBuf payload) {
        int length = payload == null ? 0 : payload.readableBytes();
        StringBuilder sb = new StringBuilder(96 + Math.min(length, maxBytesPerFrame) * 2);
        sb.append(Instant.now())
                .append(direction == Direction.IN ? " IN  #" : " OUT #").append(session.id())
                .append(' ').append(session.protocol())
                .append(' ').append(session.remoteAddress())
                .append(String.format(" op=0x%04X len=%d", opcode, length));
        if (includePayload && length > 0 && maxBytesPerFrame > 0) {
            int n = Math.min(length, maxBytesPerFrame);
            sb.append(" hex=").append(ByteBufUtil.hexDump(payload, payload.readerIndex(), n));
            if (length > n) {
                sb.append("...");
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        stop();
    }
}
