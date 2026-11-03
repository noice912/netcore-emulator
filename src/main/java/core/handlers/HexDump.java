package core.handlers;

import io.netty.buffer.ByteBuf;

/**
 * Renders a payload as an offset / hex / ASCII table for diagnostics, marking the byte where
 * decoding failed. Kept out of {@link PacketDecodeException} on purpose: the exception carries only
 * the cheap {@link PacketDecodeException#errorOffset() offset}, and this table is built lazily by
 * the dispatcher at log time, so nothing is formatted on the happy path.
 *
 * <p>Columns are fixed width, so a caret under the failing byte lines up exactly:
 * a 9-character offset gutter ({@code %08x} plus a space), then each byte as {@code "%02x "}.
 *
 * <pre>
 * payload=15 byte(s), failed at offset 13 (0xD)
 * offset   00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f  ascii
 * 00000000 4e 45 00 06 00 00 00 05 68 65 6c 6c 6f 00 00     NE......hello..
 *                                                  ^^ here
 * </pre>
 */
public final class HexDump {

    private static final int BYTES_PER_ROW = 16;
    /** Width of the "%08x " offset gutter that precedes the hex columns. */
    private static final int GUTTER = 9;

    private HexDump() {
    }

    /**
     * @param payload     the buffer to render; read by absolute index, so its reader index is ignored
     * @param errorOffset byte offset to mark with a caret, or {@code -1} for none
     * @param maxBytes    the most bytes to render; the full size is always reported
     */
    public static String annotated(ByteBuf payload, int errorOffset, int maxBytes) {
        int total = payload.writerIndex();
        int shown = Math.min(Math.max(0, maxBytes), total);
        StringBuilder sb = new StringBuilder(shown * 5 + 160);
        sb.append("payload=").append(total).append(" byte(s)");
        if (errorOffset >= 0) {
            sb.append(String.format(", failed at offset %d (0x%X)", errorOffset, errorOffset));
        }
        sb.append('\n');
        sb.append("offset   00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e 0f  ascii\n");
        for (int row = 0; row < shown; row += BYTES_PER_ROW) {
            sb.append(String.format("%08x ", row));
            StringBuilder ascii = new StringBuilder(BYTES_PER_ROW);
            for (int col = 0; col < BYTES_PER_ROW; col++) {
                int i = row + col;
                if (i < shown) {
                    int b = payload.getByte(i) & 0xFF;
                    sb.append(String.format("%02x ", b));
                    ascii.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
            if (errorOffset >= row && errorOffset < row + BYTES_PER_ROW && errorOffset < shown) {
                sb.append(" ".repeat(GUTTER + (errorOffset - row) * 3)).append("^^ here\n");
            }
        }
        if (total > shown) {
            sb.append("... ").append(total - shown).append(" more byte(s) not shown\n");
        }
        return sb.toString();
    }
}
