package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Client to server: first packet on every connection.
 *
 * <pre>
 * int32  protocolVersion
 * string clientToken     (u16 length + UTF-8; any client-chosen identifier, must be non-blank)
 * </pre>
 */
public final class HandshakeRequestPacket extends BasePacket {

    private int protocolVersion;
    private String clientToken = "";

    public HandshakeRequestPacket() {
        super(Opcodes.HANDSHAKE_REQUEST);
    }

    public HandshakeRequestPacket(int protocolVersion, String clientToken) {
        this();
        this.protocolVersion = protocolVersion;
        this.clientToken = clientToken == null ? "" : clientToken;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    public String clientToken() {
        return clientToken;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        protocolVersion = readInt(in, "protocolVersion");
        clientToken = readString(in, "clientToken");
    }

    @Override
    public void encode(ByteBuf out) {
        out.writeInt(protocolVersion);
        writeString(out, clientToken);
    }

    @Override
    public String toString() {
        return "HandshakeRequest{version=" + protocolVersion + ", token='" + clientToken + "'}";
    }
}
