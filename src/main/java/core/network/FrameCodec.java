package core.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;

/**
 * Wire format shared by the TCP and UDP listeners.
 *
 * <pre>
 * offset  size  field
 * 0       2     magic   0x4E45
 * 2       2     opcode  unsigned 16-bit
 * 4       4     length  unsigned 32-bit payload length (bounded by config)
 * 8       n     payload
 * </pre>
 *
 * <p>All integers are big-endian. When encryption is enabled in the configuration the payload
 * bytes (never the header) are XOR-ed with the configured rolling key in both directions, which
 * keeps framing decodable while obscuring content on the wire.
 *
 * <p>Over TCP, frames are concatenated on the stream; over UDP, each datagram carries exactly one
 * frame.
 */
public final class FrameCodec {

    public static final int MAGIC = 0x4E45;
    public static final int HEADER_LENGTH = 8;
    /** Hard ceiling regardless of configuration. */
    public static final int MAX_SUPPORTED_PAYLOAD = 16 * 1024 * 1024;

    private FrameCodec() {
    }

    /**
     * Attempt to decode one frame from the readable bytes of {@code in}.
     *
     * @return the frame (a retained slice of {@code in}), or {@code null} when more bytes are needed;
     *         in that case the reader index is left untouched
     * @throws CorruptedFrameException if the magic does not match
     * @throws TooLongFrameException   if the declared length exceeds {@code maxPayloadBytes}
     */
    public static Frame decode(ByteBuf in, int maxPayloadBytes, byte[] cipherKey) {
        if (in.readableBytes() < HEADER_LENGTH) {
            return null;
        }
        int start = in.readerIndex();
        int magic = in.getUnsignedShort(start);
        if (magic != MAGIC) {
            throw new CorruptedFrameException(String.format("bad frame magic 0x%04X (expected 0x%04X)", magic, MAGIC));
        }
        int opcode = in.getUnsignedShort(start + 2);
        long length = in.getUnsignedInt(start + 4);
        if (length > maxPayloadBytes) {
            throw new TooLongFrameException(String.format(
                    "frame payload of %d bytes exceeds the %d-byte limit (opcode 0x%04X)", length, maxPayloadBytes, opcode));
        }
        if (in.readableBytes() - HEADER_LENGTH < length) {
            return null;
        }
        in.skipBytes(HEADER_LENGTH);
        ByteBuf payload = in.readRetainedSlice((int) length);
        if (hasKey(cipherKey)) {
            xor(payload, payload.readerIndex(), payload.readableBytes(), cipherKey);
        }
        return new Frame(opcode, payload);
    }

    /** Write header plus payload into {@code out}. Does not release the frame. */
    public static void encode(Frame frame, ByteBuf out, byte[] cipherKey) {
        ByteBuf payload = frame.content();
        int length = payload.readableBytes();
        out.writeShort(MAGIC);
        out.writeShort(frame.opcode());
        out.writeInt(length);
        int payloadStart = out.writerIndex();
        out.writeBytes(payload, payload.readerIndex(), length);
        if (hasKey(cipherKey)) {
            xor(out, payloadStart, length, cipherKey);
        }
    }

    public static int encodedLength(Frame frame) {
        return HEADER_LENGTH + frame.payloadLength();
    }

    public static boolean hasKey(byte[] key) {
        return key != null && key.length > 0;
    }

    /** Symmetric rolling XOR over {@code buf[index, index + length)}, applied in place. */
    public static void xor(ByteBuf buf, int index, int length, byte[] key) {
        for (int i = 0; i < length; i++) {
            int at = index + i;
            buf.setByte(at, buf.getByte(at) ^ key[i % key.length]);
        }
    }
}
