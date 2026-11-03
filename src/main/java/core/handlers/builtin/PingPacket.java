package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Client to server heartbeat.
 *
 * <pre>
 * int64 clientTimeMillis   (echoed back in PONG so the client can measure round-trip time)
 * </pre>
 */
public final class PingPacket extends BasePacket {

    private long clientTimeMillis;

    public PingPacket() {
        super(Opcodes.PING);
    }

    public PingPacket(long clientTimeMillis) {
        this();
        this.clientTimeMillis = clientTimeMillis;
    }

    public long clientTimeMillis() {
        return clientTimeMillis;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        clientTimeMillis = readLong(in, "clientTimeMillis");
    }

    @Override
    public void encode(ByteBuf out) {
        out.writeLong(clientTimeMillis);
    }

    @Override
    public String toString() {
        return "Ping{clientTime=" + clientTimeMillis + "}";
    }
}
