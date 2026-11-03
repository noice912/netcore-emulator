package core.handlers.builtin;

import core.handlers.PacketHandler;
import core.network.NetworkSession;

/** Returns the received message to the sender unchanged. */
public final class EchoHandler implements PacketHandler<EchoPacket> {

    @Override
    public Class<EchoPacket> packetType() {
        return EchoPacket.class;
    }

    @Override
    public void execute(NetworkSession session, EchoPacket packet) {
        session.send(new EchoPacket(packet.message()));
    }
}
