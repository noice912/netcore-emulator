package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Server to client: answer to {@link HandshakeRequestPacket}.
 *
 * <pre>
 * bool   accepted
 * int64  sessionId                 (0 when rejected)
 * int32  heartbeatIntervalSeconds  (how often the client should send PING)
 * string message                   (human-readable reason or greeting)
 * </pre>
 */
public final class HandshakeResponsePacket extends BasePacket {

    private boolean accepted;
    private long sessionId;
    private int heartbeatIntervalSeconds;
    private String message = "";

    public HandshakeResponsePacket() {
        super(Opcodes.HANDSHAKE_RESPONSE);
    }

    public HandshakeResponsePacket(boolean accepted, long sessionId, int heartbeatIntervalSeconds, String message) {
        this();
        this.accepted = accepted;
        this.sessionId = sessionId;
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        this.message = message == null ? "" : message;
    }

    public boolean accepted() {
        return accepted;
    }

    public long sessionId() {
        return sessionId;
    }

    public int heartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public String message() {
        return message;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        accepted = readBoolean(in, "accepted");
        sessionId = readLong(in, "sessionId");
        heartbeatIntervalSeconds = readInt(in, "heartbeatIntervalSeconds");
        message = readString(in, "message");
    }

    @Override
    public void encode(ByteBuf out) {
        writeBoolean(out, accepted);
        out.writeLong(sessionId);
        out.writeInt(heartbeatIntervalSeconds);
        writeString(out, message);
    }

    @Override
    public String toString() {
        return "HandshakeResponse{accepted=" + accepted + ", sessionId=" + sessionId + ", heartbeat="
                + heartbeatIntervalSeconds + "s, message='" + message + "'}";
    }
}
