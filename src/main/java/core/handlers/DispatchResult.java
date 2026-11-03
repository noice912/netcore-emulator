package core.handlers;

/** Outcome of routing one inbound frame through the {@link PacketDispatcher}. */
public enum DispatchResult {
    /** The packet was decoded and its handler completed normally. */
    HANDLED,
    /** No handler is registered for the opcode. */
    UNKNOWN_OPCODE,
    /** The payload could not be decoded into the registered packet class. */
    MALFORMED,
    /** The session was already closed when the frame arrived. */
    REJECTED_SESSION_CLOSED,
    /** The handler requires a completed handshake and the session has none. */
    REJECTED_HANDSHAKE_REQUIRED,
    /** The handler (or the packet factory) threw; the error was logged and swallowed. */
    HANDLER_ERROR,
    /** A null session or frame reached the dispatcher. */
    DROPPED_NULL
}
