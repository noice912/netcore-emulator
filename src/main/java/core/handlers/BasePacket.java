package core.handlers;

import core.network.Frame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Base class for every packet that crosses the wire.
 *
 * <p>A packet is identified by a 16-bit opcode and carries a payload that the subclass reads in
 * {@link #decode} and writes in {@link #encode}. All multi-byte integers are big-endian (network
 * order). Strings are a 16-bit unsigned length followed by UTF-8 bytes; blobs are a 32-bit length
 * followed by raw bytes.
 *
 * <p>Subclasses must have a public no-argument constructor (or be registered with an explicit
 * factory) so the registry can instantiate them for inbound traffic.
 */
public abstract class BasePacket {

    /** Upper bound for a single string field, in bytes. */
    public static final int MAX_STRING_BYTES = 8 * 1024;

    /** Upper bound for a single byte-array field, in bytes. */
    public static final int MAX_BLOB_BYTES = 1024 * 1024;

    private final int opcode;

    protected BasePacket(int opcode) {
        if (opcode < 0 || opcode > 0xFFFF) {
            throw new IllegalArgumentException("opcode out of range (0..65535): " + opcode);
        }
        this.opcode = opcode;
    }

    /** The 16-bit opcode this packet is routed by. */
    public final int opcode() {
        return opcode;
    }

    /**
     * Populate this packet's fields from the payload. Implementations should read exactly the bytes
     * they wrote in {@link #encode} and use the {@code read*} helpers, which convert short payloads
     * into a {@link PacketDecodeException} instead of an unchecked buffer error.
     */
    public abstract void decode(ByteBuf in) throws PacketDecodeException;

    /** Serialize this packet's fields into the payload buffer. */
    public abstract void encode(ByteBuf out);

    /**
     * Encode this packet into a wire {@link Frame}. The returned frame owns a freshly allocated,
     * reference-counted payload; whoever receives it must release it (the transports do).
     */
    public Frame toFrame(ByteBufAllocator allocator) {
        Objects.requireNonNull(allocator, "allocator");
        ByteBuf payload = allocator.buffer();
        try {
            encode(payload);
        } catch (RuntimeException e) {
            payload.release();
            throw e;
        }
        return new Frame(opcode, payload);
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding helpers: each one guards the buffer so a short payload becomes a PacketDecodeException.
    // ---------------------------------------------------------------------------------------------

    protected static void require(ByteBuf in, int bytes, String field) throws PacketDecodeException {
        if (in == null) {
            throw new PacketDecodeException("payload is null while reading '" + field + "'");
        }
        if (in.readableBytes() < bytes) {
            throw new PacketDecodeException("truncated payload reading '" + field + "': need " + bytes
                    + " byte(s), have " + in.readableBytes());
        }
    }

    protected static int readU8(ByteBuf in, String field) throws PacketDecodeException {
        require(in, 1, field);
        return in.readUnsignedByte();
    }

    protected static int readU16(ByteBuf in, String field) throws PacketDecodeException {
        require(in, 2, field);
        return in.readUnsignedShort();
    }

    protected static int readInt(ByteBuf in, String field) throws PacketDecodeException {
        require(in, 4, field);
        return in.readInt();
    }

    protected static long readLong(ByteBuf in, String field) throws PacketDecodeException {
        require(in, 8, field);
        return in.readLong();
    }

    protected static boolean readBoolean(ByteBuf in, String field) throws PacketDecodeException {
        require(in, 1, field);
        return in.readByte() != 0;
    }

    protected static String readString(ByteBuf in, String field) throws PacketDecodeException {
        int length = readU16(in, field + ".length");
        if (length > MAX_STRING_BYTES) {
            throw new PacketDecodeException("string '" + field + "' is " + length + " bytes; limit is "
                    + MAX_STRING_BYTES);
        }
        require(in, length, field);
        String value = in.toString(in.readerIndex(), length, StandardCharsets.UTF_8);
        in.skipBytes(length);
        return value;
    }

    protected static byte[] readBytes(ByteBuf in, String field) throws PacketDecodeException {
        int length = readInt(in, field + ".length");
        if (length < 0 || length > MAX_BLOB_BYTES) {
            throw new PacketDecodeException("blob '" + field + "' length " + length + " is outside 0.."
                    + MAX_BLOB_BYTES);
        }
        require(in, length, field);
        byte[] value = new byte[length];
        in.readBytes(value);
        return value;
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding helpers.
    // ---------------------------------------------------------------------------------------------

    protected static void writeString(ByteBuf out, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string is " + bytes.length + " bytes; limit is "
                    + MAX_STRING_BYTES);
        }
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }

    protected static void writeBytes(ByteBuf out, byte[] value) {
        byte[] bytes = value == null ? new byte[0] : value;
        if (bytes.length > MAX_BLOB_BYTES) {
            throw new IllegalArgumentException("blob is " + bytes.length + " bytes; limit is "
                    + MAX_BLOB_BYTES);
        }
        out.writeInt(bytes.length);
        out.writeBytes(bytes);
    }

    protected static void writeBoolean(ByteBuf out, boolean value) {
        out.writeByte(value ? 1 : 0);
    }

    @Override
    public String toString() {
        return String.format("%s{opcode=0x%04X}", getClass().getSimpleName(), opcode);
    }
}
