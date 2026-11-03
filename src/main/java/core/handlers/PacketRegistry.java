package core.handlers;

import core.network.NetworkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Central routing table: opcode to packet factory plus handler.
 *
 * <p>Backed by a {@link ConcurrentHashMap}, so handlers can be registered, replaced and removed
 * while the network layer is live. Each registration is probed once at registration time so a
 * factory/handler mismatch fails at startup rather than on the first packet from a client.
 */
public final class PacketRegistry {

    private static final Logger log = LoggerFactory.getLogger(PacketRegistry.class);

    /** One opcode's routing entry. Immutable once created. */
    public static final class Registration<T extends BasePacket> {

        private final int opcode;
        private final Class<T> packetType;
        private final Supplier<? extends T> factory;
        private final PacketHandler<T> handler;

        private Registration(int opcode, Supplier<? extends T> factory, PacketHandler<T> handler) {
            this.opcode = opcode;
            this.factory = factory;
            this.handler = handler;
            this.packetType = handler.packetType();
        }

        public int opcode() {
            return opcode;
        }

        public Class<T> packetType() {
            return packetType;
        }

        public PacketHandler<T> handler() {
            return handler;
        }

        /** Creates a fresh packet instance and verifies the factory honoured its contract. */
        public T newPacket() {
            T packet = factory.get();
            if (packet == null) {
                throw new IllegalStateException(String.format(
                        "packet factory for opcode 0x%04X returned null", opcode));
            }
            if (!packetType.isInstance(packet)) {
                throw new IllegalStateException(String.format(
                        "packet factory for opcode 0x%04X produced %s but handler %s expects %s",
                        opcode, packet.getClass().getName(), handler.name(), packetType.getName()));
            }
            if (packet.opcode() != opcode) {
                throw new IllegalStateException(String.format(
                        "packet %s reports opcode 0x%04X but is registered under 0x%04X",
                        packet.getClass().getSimpleName(), packet.opcode(), opcode));
            }
            return packet;
        }

        /** Invokes the handler after a runtime type check; a foreign packet type never gets through. */
        public void invoke(NetworkSession session, BasePacket packet) throws Exception {
            if (!packetType.isInstance(packet)) {
                throw new ClassCastException(String.format(
                        "opcode 0x%04X handler %s expects %s but received %s", opcode, handler.name(),
                        packetType.getName(), packet == null ? "null" : packet.getClass().getName()));
            }
            handler.execute(session, packetType.cast(packet));
        }

        @Override
        public String toString() {
            return String.format("0x%04X -> %s (%s)", opcode, handler.name(), packetType.getSimpleName());
        }
    }

    private final ConcurrentHashMap<Integer, Registration<?>> registrations = new ConcurrentHashMap<>();

    /**
     * Register a handler for an opcode. Fails if the opcode is already taken; use {@link #replace}
     * to override a built-in deliberately.
     */
    public <T extends BasePacket> Registration<T> register(int opcode, Supplier<? extends T> factory,
                                                           PacketHandler<T> handler) {
        Registration<T> registration = build(opcode, factory, handler);
        Registration<?> existing = registrations.putIfAbsent(opcode, registration);
        if (existing != null) {
            throw new IllegalStateException(String.format(
                    "opcode 0x%04X is already registered to %s; call replace() to override it",
                    opcode, existing.handler().name()));
        }
        log.debug("registered {}", registration);
        return registration;
    }

    /** Register a handler, replacing any existing registration for the opcode. */
    public <T extends BasePacket> Registration<T> replace(int opcode, Supplier<? extends T> factory,
                                                          PacketHandler<T> handler) {
        Registration<T> registration = build(opcode, factory, handler);
        Registration<?> previous = registrations.put(opcode, registration);
        if (previous != null) {
            // Debug, not info: a schema hot-reload calls replace() for every surviving opcode, so at
            // info this would emit one line per packet on each reload of a large schema set.
            log.debug("replaced handler for opcode 0x{}: {} -> {}", String.format("%04X", opcode),
                    previous.handler().name(), handler.name());
        } else {
            log.debug("registered {}", registration);
        }
        return registration;
    }

    private static <T extends BasePacket> Registration<T> build(int opcode, Supplier<? extends T> factory,
                                                                PacketHandler<T> handler) {
        checkOpcode(opcode);
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(handler.packetType(), "handler.packetType() must not be null");
        Registration<T> registration = new Registration<>(opcode, factory, handler);
        registration.newPacket(); // probe once so a broken factory fails here, not on live traffic
        return registration;
    }

    /** Remove an opcode's registration. Returns {@code true} if one was present. */
    public boolean unregister(int opcode) {
        Registration<?> removed = registrations.remove(opcode);
        if (removed != null) {
            log.debug("unregistered {}", removed);
        }
        return removed != null;
    }

    public Optional<Registration<?>> lookup(int opcode) {
        return Optional.ofNullable(registrations.get(opcode));
    }

    public boolean contains(int opcode) {
        return registrations.containsKey(opcode);
    }

    public int size() {
        return registrations.size();
    }

    /** Sorted snapshot of the registered opcodes. */
    public NavigableSet<Integer> opcodes() {
        return Collections.unmodifiableNavigableSet(new TreeSet<>(registrations.keySet()));
    }

    public void clear() {
        registrations.clear();
    }

    static void checkOpcode(int opcode) {
        if (opcode < 0 || opcode > 0xFFFF) {
            throw new IllegalArgumentException("opcode out of range (0..65535): " + opcode);
        }
    }
}
