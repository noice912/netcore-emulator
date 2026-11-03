package core.handlers.builtin;

/**
 * Opcodes reserved by the engine's built-in control packets. Project-specific packets should use
 * opcodes above {@link #RESERVED_MAX}.
 */
public final class Opcodes {

    public static final int HANDSHAKE_REQUEST = 0x0001;
    public static final int HANDSHAKE_RESPONSE = 0x0002;
    public static final int PING = 0x0003;
    public static final int PONG = 0x0004;
    public static final int DISCONNECT = 0x0005;
    public static final int ECHO = 0x0006;

    /** Highest opcode reserved for engine control traffic. */
    public static final int RESERVED_MAX = 0x00FF;

    private Opcodes() {
    }
}
