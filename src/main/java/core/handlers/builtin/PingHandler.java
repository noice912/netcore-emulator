package core.handlers.builtin;

import core.handlers.PacketHandler;
import core.network.NetworkSession;

/** Answers every PING with a PONG carrying the client's timestamp and the server's clock. */
public final class PingHandler implements PacketHandler<PingPacket> {

    @Override
    public Class<PingPacket> packetType() {
        return PingPacket.class;
    }

    @Override
    public void execute(NetworkSession session, PingPacket packet) {
        session.send(new PongPacket(packet.clientTimeMillis(), System.currentTimeMillis()));
    }
}
