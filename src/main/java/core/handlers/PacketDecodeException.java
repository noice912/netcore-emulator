package core.handlers;

/**
 * Raised by {@link BasePacket#decode} when an inbound payload is shorter than, or structurally
 * different from, what the packet class expects. The dispatcher treats it as a malformed packet:
 * it is logged (with a hex dump that marks {@link #errorOffset()}) and counted, and the engine
 * loop keeps running.
 */
public class PacketDecodeException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Byte offset into the payload where decoding failed, or {@code -1} when it is not known. */
    private final int errorOffset;

    public PacketDecodeException(String message) {
        this(message, -1);
    }

    public PacketDecodeException(String message, int errorOffset) {
        super(message);
        this.errorOffset = errorOffset;
    }

    public PacketDecodeException(String message, Throwable cause) {
        super(message, cause);
        this.errorOffset = -1;
    }

    /** The payload offset where decoding failed, or {@code -1} if unknown. */
    public int errorOffset() {
        return errorOffset;
    }
}
