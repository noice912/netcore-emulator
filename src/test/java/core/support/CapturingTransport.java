package core.support;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import core.network.Frame;
import core.network.SessionTransport;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory {@link SessionTransport} that records everything a session sends, for unit tests. */
public final class CapturingTransport implements SessionTransport {

    /** One captured outbound frame, decoded eagerly into opcode + payload bytes. */
    public record Sent(int opcode, byte[] payload, boolean closedAfter) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean active = new AtomicBoolean(true);

    @Override
    public void send(Frame frame, boolean closeAfterWrite) {
        try {
            ByteBuf content = frame.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            sent.add(new Sent(frame.opcode(), bytes, closeAfterWrite));
        } finally {
            frame.release();
        }
    }

    @Override
    public void close() {
        active.set(false);
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public ByteBufAllocator allocator() {
        return UnpooledByteBufAllocator.DEFAULT;
    }

    public List<Sent> sent() {
        return List.copyOf(sent);
    }

    public int count() {
        return sent.size();
    }

    public Optional<Sent> last() {
        return sent.isEmpty() ? Optional.empty() : Optional.of(sent.get(sent.size() - 1));
    }

    /** Decode the most recently sent frame into the given packet type. */
    public <T extends BasePacket> T lastAs(T target) throws PacketDecodeException {
        Sent s = last().orElseThrow(() -> new AssertionError("nothing was sent"));
        if (s.opcode() != target.opcode()) {
            throw new AssertionError("last opcode was 0x" + Integer.toHexString(s.opcode())
                    + " but expected 0x" + Integer.toHexString(target.opcode()));
        }
        ByteBuf buf = Unpooled.wrappedBuffer(s.payload());
        target.decode(buf);
        return target;
    }
}
