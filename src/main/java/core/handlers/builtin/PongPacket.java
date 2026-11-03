package core.handlers.builtin;

import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

/**
 * Server to client heartbeat reply.
 *
 * <pre>
 * int64 clientTimeMillis   (copied from the PING)
 * int64 serverTimeMillis   (server wall clock when the PING was handled)
 * </pre>
 */
public final class PongPacket extends BasePacket {

    private long clientTimeMillis;
    private long serverTimeMillis;

    public PongPacket() {
        super(Opcodes.PONG);
    }

    public PongPacket(long clientTimeMillis, long serverTimeMillis) {
        this();
        this.clientTimeMillis = clientTimeMillis;
        this.serverTimeMillis = serverTimeMillis;
    }

    public long clientTimeMillis() {
        return clientTimeMillis;
    }

    public long serverTimeMillis() {
        return serverTimeMillis;
    }

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        clientTimeMillis = readLong(in, "clientTimeMillis");
        serverTimeMillis = readLong(in, "serverTimeMillis");
    }

    @Override
    public void encode(ByteBuf out) {
        out.writeLong(clientTimeMillis);
        out.writeLong(serverTimeMillis);
    }

    @Override
    public String toString() {
        return "Pong{clientTime=" + clientTimeMillis + ", serverTime=" + serverTimeMillis + "}";
    }
}
