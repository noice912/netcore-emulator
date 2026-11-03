package core.handlers;

import core.handlers.builtin.EchoHandler;
import core.handlers.builtin.EchoPacket;
import core.handlers.builtin.Opcodes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketRegistryTest {

    @Test
    void registerAndLookup() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());

        assertEquals(1, registry.size());
        assertTrue(registry.contains(Opcodes.ECHO));
        assertTrue(registry.lookup(Opcodes.ECHO).isPresent());
        assertTrue(registry.lookup(0x9999).isEmpty());
        assertEquals(Opcodes.ECHO, registry.opcodes().first());
    }

    @Test
    void newPacketReturnsFreshInstances() {
        PacketRegistry registry = new PacketRegistry();
        PacketRegistry.Registration<EchoPacket> reg =
                registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
        assertNotSame(reg.newPacket(), reg.newPacket());
    }

    @Test
    void duplicateRegistrationIsRejected() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
        assertThrows(IllegalStateException.class,
                () -> registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler()));
    }

    @Test
    void replaceOverridesExisting() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
        registry.replace(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
        assertEquals(1, registry.size());
    }

    @Test
    void unregisterRemoves() {
        PacketRegistry registry = new PacketRegistry();
        registry.register(Opcodes.ECHO, EchoPacket::new, new EchoHandler());
        assertTrue(registry.unregister(Opcodes.ECHO));
        assertFalse(registry.unregister(Opcodes.ECHO));
        assertEquals(0, registry.size());
    }

    @Test
    void registeringPacketUnderMismatchedOpcodeFailsAtRegistration() {
        PacketRegistry registry = new PacketRegistry();
        // EchoPacket reports opcode 0x0006, so registering it under a different opcode must be caught
        // by the registry's probe at registration time, not on the first live packet.
        assertThrows(IllegalStateException.class,
                () -> registry.register(0x1234, EchoPacket::new, new EchoHandler()));
    }

    @Test
    void opcodeRangeIsValidated() {
        PacketRegistry registry = new PacketRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(0x10000, EchoPacket::new, new EchoHandler()));
    }
}
