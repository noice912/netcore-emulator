package core.handlers.builtin;

import core.handlers.PacketHandler;
import core.network.NetworkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Closes the session when the client announces an orderly disconnect. */
public final class DisconnectHandler implements PacketHandler<DisconnectPacket> {

    private static final Logger log = LoggerFactory.getLogger(DisconnectHandler.class);

    @Override
    public Class<DisconnectPacket> packetType() {
        return DisconnectPacket.class;
    }

    @Override
    public boolean requiresHandshake() {
        // A client may bail out at any point, including before it has handshaken.
        return false;
    }

    @Override
    public void execute(NetworkSession session, DisconnectPacket packet) {
        log.info("session {} ({}) requested disconnect: {}", session.id(), session.remoteAddress(),
                packet.reason().isEmpty() ? "(no reason given)" : packet.reason());
        session.close("client disconnect: " + packet.reason());
    }
}
