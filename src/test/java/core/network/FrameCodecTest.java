package core.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.TooLongFrameException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameCodecTest {

    private static ByteBuf payload(String text) {
        return Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encodeThenDecodeRoundTrips() {
        Frame original = new Frame(0x1234, payload("hello world"));
        ByteBuf wire = Unpooled.buffer();
        FrameCodec.encode(original, wire, null);
        original.release();

        assertEquals(FrameCodec.HEADER_LENGTH + 11, wire.readableBytes());
        Frame decoded = FrameCodec.decode(wire, 1024, null);
        assertEquals(0x1234, decoded.opcode());
        assertEquals("hello world", decoded.content().toString(StandardCharsets.UTF_8));
        decoded.release();
        wire.release();
    }

    @Test
    void decodeReturnsNullUntilHeaderAndBodyPresent() {
        Frame frame = new Frame(7, payload("abcdef"));
        ByteBuf wire = Unpooled.buffer();
        FrameCodec.encode(frame, wire, null);
        frame.release();

        // Only part of the header: not enough yet, reader index untouched.
        ByteBuf partialHeader = wire.copy(0, 4);
        assertNull(FrameCodec.decode(partialHeader, 1024, null));
        assertEquals(0, partialHeader.readerIndex());
        partialHeader.release();

        // Header present but body truncated.
        ByteBuf partialBody = wire.copy(0, FrameCodec.HEADER_LENGTH + 2);
        assertNull(FrameCodec.decode(partialBody, 1024, null));
        assertEquals(0, partialBody.readerIndex());
        partialBody.release();

        wire.release();
    }

    @Test
    void twoFramesBackToBackDecodeIndependently() {
        ByteBuf wire = Unpooled.buffer();
        Frame a = new Frame(1, payload("aaa"));
        Frame b = new Frame(2, payload("bbbb"));
        FrameCodec.encode(a, wire, null);
        FrameCodec.encode(b, wire, null);
        a.release();
        b.release();

        Frame first = FrameCodec.decode(wire, 1024, null);
        Frame second = FrameCodec.decode(wire, 1024, null);
        assertEquals(1, first.opcode());
        assertEquals("aaa", first.content().toString(StandardCharsets.UTF_8));
        assertEquals(2, second.opcode());
        assertEquals("bbbb", second.content().toString(StandardCharsets.UTF_8));
        assertNull(FrameCodec.decode(wire, 1024, null));
        first.release();
        second.release();
        wire.release();
    }

    @Test
    void badMagicIsRejected() {
        ByteBuf wire = Unpooled.buffer();
        wire.writeShort(0x0000);
        wire.writeShort(1);
        wire.writeInt(0);
        assertThrows(CorruptedFrameException.class, () -> FrameCodec.decode(wire, 1024, null));
        wire.release();
    }

    @Test
    void oversizePayloadIsRejected() {
        ByteBuf wire = Unpooled.buffer();
        wire.writeShort(FrameCodec.MAGIC);
        wire.writeShort(1);
        wire.writeInt(5000);
        assertThrows(TooLongFrameException.class, () -> FrameCodec.decode(wire, 1024, null));
        wire.release();
    }

    @Test
    void encryptionIsSymmetricAndLeavesHeaderClear() {
        byte[] key = {0x11, 0x22, 0x33};
        Frame frame = new Frame(0x00AB, payload("secret payload"));
        ByteBuf wire = Unpooled.buffer();
        FrameCodec.encode(frame, wire, key);
        frame.release();

        // Header (magic/opcode/length) must remain readable without the key.
        assertEquals(FrameCodec.MAGIC, wire.getUnsignedShort(0));
        assertEquals(0x00AB, wire.getUnsignedShort(2));

        Frame decoded = FrameCodec.decode(wire, 1024, key);
        assertEquals("secret payload", decoded.content().toString(StandardCharsets.UTF_8));
        decoded.release();
        wire.release();
    }

    @Test
    void xorIsItsOwnInverse() {
        byte[] key = {0x0F, (byte) 0xF0, 0x55};
        byte[] data = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.copiedBuffer(data);
        FrameCodec.xor(buf, 0, data.length, key);
        assertTrue(buf.readableBytes() == data.length);
        FrameCodec.xor(buf, 0, data.length, key);
        byte[] out = new byte[data.length];
        buf.getBytes(0, out);
        assertArrayEquals(data, out);
        buf.release();
    }
}
