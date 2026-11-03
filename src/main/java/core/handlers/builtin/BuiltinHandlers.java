package core.handlers.builtin;

import core.handlers.PacketRegistry;

/**
 * Registers the engine's built-in control packets.
 *
 * <p>Opcode {@code 0x0001} (handshake request) is intentionally left unregistered: session
 * authentication is project-specific. Register your own
 * {@code PacketHandler<HandshakeRequestPacket>} for it (returning {@code false} from
 * {@code requiresHandshake()} and calling {@code NetworkSession#markAuthenticated} on success)
 * before enabling {@code security.requireHandshake} in config.json.
 */
public final class BuiltinHandlers {

    private BuiltinHandlers() {
    }

    public static void registerAll(PacketRegistry registry) {
        registry.register(Opcodes.PING, PingPacket::new, new PingHandler());
        registry.register(Opcodes.DISCONNECT, DisconnectPacket::new, new DisconnectHandler());
        registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
    }
}
