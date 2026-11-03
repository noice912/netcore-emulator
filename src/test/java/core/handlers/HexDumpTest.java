package core.handlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexDumpTest {

    @Test
    void rendersOffsetHexAsciiAndAlignedCaret() {
        ByteBuf buf = Unpooled.wrappedBuffer("NE\u0000\u0006hello".getBytes(StandardCharsets.US_ASCII));
        String dump = HexDump.annotated(buf, 4, 64);

        assertTrue(dump.contains("payload=9 byte(s), failed at offset 4 (0x4)"), dump);
        assertTrue(dump.contains("4e 45 00 06 68 65 6c 6c 6f"), dump);
        assertTrue(dump.contains("NE..hello"), dump);
        // caret sits under byte 4: 9-char gutter + 4 * 3 = 21 spaces, then the marker.
        assertTrue(dump.contains("\n" + " ".repeat(21) + "^^ here"), dump);
        buf.release();
    }

    @Test
    void noCaretOrOffsetWhenUnknown() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        String dump = HexDump.annotated(buf, -1, 64);
        assertFalse(dump.contains("^^"), dump);
        assertFalse(dump.contains("failed at offset"), dump);
        assertTrue(dump.contains("payload=3 byte(s)"), dump);
        buf.release();
    }

    @Test
    void truncatesLongPayloadButReportsFullSize() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[40]);
        String dump = HexDump.annotated(buf, -1, 16);
        assertTrue(dump.contains("payload=40 byte(s)"), dump);
        assertTrue(dump.contains("24 more byte(s) not shown"), dump);
        buf.release();
    }

    @Test
    void offsetBeyondTheShownWindowIsReportedWithoutACaret() {
        ByteBuf buf = Unpooled.wrappedBuffer(new byte[40]);
        String dump = HexDump.annotated(buf, 30, 16);
        assertTrue(dump.contains("failed at offset 30 (0x1E)"), dump);
        assertFalse(dump.contains("^^"), dump);
        buf.release();
    }
}
