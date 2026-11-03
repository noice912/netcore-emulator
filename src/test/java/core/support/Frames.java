package core.support;

import core.handlers.BasePacket;
import core.network.Frame;
import core.network.FrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Helpers for building and reading wire frames in tests, using the real {@link FrameCodec}. */
public final class Frames {

    /** One decoded wire frame. */
    public record Wire(int opcode, byte[] payload) {
        public ByteBuf payloadBuf() {
            return Unpooled.wrappedBuffer(payload);
        }
    }

    private Frames() {
    }

    /** Serialize a packet to its on-the-wire bytes (header + payload), optionally encrypted. */
    public static byte[] toWire(BasePacket packet, byte[] key) {
        Frame frame = packet.toFrame(UnpooledByteBufAllocator.DEFAULT);
        ByteBuf out = Unpooled.buffer(FrameCodec.encodedLength(frame));
        try {
            FrameCodec.encode(frame, out, key);
            return ByteBufUtil.getBytes(out);
        } finally {
            frame.release();
            out.release();
        }
    }

    /** Serialize a raw opcode + payload to wire bytes (for crafting malformed or unknown frames). */
    public static byte[] toWire(int opcode, byte[] payload, byte[] key) {
        Frame frame = new Frame(opcode, Unpooled.wrappedBuffer(payload));
        ByteBuf out = Unpooled.buffer();
        try {
            FrameCodec.encode(frame, out, key);
            return ByteBufUtil.getBytes(out);
        } finally {
            frame.release();
            out.release();
        }
    }

    /** Read exactly one frame from a stream (blocking), as a TCP client would. */
    public static Wire readWire(InputStream in, byte[] key) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int magic = data.readUnsignedShort();
        if (magic != FrameCodec.MAGIC) {
            throw new IOException("bad magic 0x" + Integer.toHexString(magic));
        }
        int opcode = data.readUnsignedShort();
        long length = data.readInt() & 0xFFFFFFFFL;
        byte[] payload = new byte[(int) length];
        data.readFully(payload);
        if (FrameCodec.hasKey(key)) {
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            FrameCodec.xor(buf, 0, payload.length, key);
        }
        return new Wire(opcode, payload);
    }

    /** Parse one frame out of a complete datagram payload. */
    public static Wire fromDatagram(byte[] datagram, int length, byte[] key) throws IOException {
        ByteBuf buf = Unpooled.wrappedBuffer(datagram, 0, length);
        Frame frame = FrameCodec.decode(buf, FrameCodec.MAX_SUPPORTED_PAYLOAD, key);
        if (frame == null) {
            throw new IOException("incomplete datagram (" + length + " bytes)");
        }
        try {
            return new Wire(frame.opcode(), ByteBufUtil.getBytes(frame.content()));
        } finally {
            frame.release();
        }
    }
}
