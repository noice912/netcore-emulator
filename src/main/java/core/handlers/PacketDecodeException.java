package core.handlers;

/**
 * Raised by {@link BasePacket#decode} when an inbound payload is shorter than, or structurally
 * different from, what the packet class expects. The dispatcher treats it as a malformed packet:
 * it is logged and counted, and the engine loop keeps running.
 */
public class PacketDecodeException extends Exception {

    private static final long serialVersionUID = 1L;

    public PacketDecodeException(String message) {
        super(message);
    }

    public PacketDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
