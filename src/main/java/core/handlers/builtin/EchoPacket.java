package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Either direction: the server returns the same message to the sender. Useful for smoke-testing a
 * client's framing and encryption without any game logic.
 *
 * <pre>
 * string message
 * </pre>
 */
public final class EchoPacket extends BasePacket {

    private String message = "";

    public EchoPacket() {
        super(Opcodes.ECHO);
    }

    public EchoPacket(String message) {
        this();
        this.message = message == null ? "" : message;
    }

    public String message() {
        return message;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        message = readString(in, "message");
    }

    @Override
    public void encode(ByteBuf out) {
        writeString(out, message);
    }

    @Override
    public String toString() {
        return "Echo{message='" + message + "'}";
    }
}
