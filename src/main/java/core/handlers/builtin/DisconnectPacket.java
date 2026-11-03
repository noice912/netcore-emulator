package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Either direction: announces an orderly disconnect. The sender closes right after sending it.
 *
 * <pre>
 * string reason
 * </pre>
 */
public final class DisconnectPacket extends BasePacket {

    private String reason = "";

    public DisconnectPacket() {
        super(Opcodes.DISCONNECT);
    }

    public DisconnectPacket(String reason) {
        this();
        this.reason = reason == null ? "" : reason;
    }

    public String reason() {
        return reason;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        reason = readString(in, "reason");
    }

    @Override
    public void encode(ByteBuf out) {
        writeString(out, reason);
    }

    @Override
    public String toString() {
        return "Disconnect{reason='" + reason + "'}";
    }
}
