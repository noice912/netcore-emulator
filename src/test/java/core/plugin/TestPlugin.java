package core.plugin;

import core.engine.ServerEngine;
import core.handlers.BasePacket;
import core.handlers.PacketHandler;
import core.network.NetworkSession;
import io.netty.buffer.ByteBuf;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Plugin discovered through {@code META-INF/services/core.plugin.EnginePlugin} on the test
 * classpath. Registers a packet and a tickable so tests can prove the lifecycle ran.
 */
public final class TestPlugin implements EnginePlugin {

    public static final int OPCODE = 0x0200;
    public static final AtomicInteger LOADS = new AtomicInteger();
    public static final AtomicInteger STARTS = new AtomicInteger();
    public static final AtomicInteger STOPS = new AtomicInteger();
    public static final AtomicInteger TICKS = new AtomicInteger();

    public static final class MarkerPacket extends BasePacket {
        public MarkerPacket() {
            super(OPCODE);
        }

        @Override
        public void decode(ByteBuf in) {
        }

        @Override
        public void encode(ByteBuf out) {
        }
    }

    @Override
    public String name() {
        return "test-plugin";
    }

    @Override
    public String version() {
        return "9.9";
    }

    @Override
    public void onLoad(ServerEngine engine) {
        LOADS.incrementAndGet();
        if (!engine.registry().contains(OPCODE)) {
            engine.registry().register(OPCODE, MarkerPacket::new, new PacketHandler<MarkerPacket>() {
                @Override
                public Class<MarkerPacket> packetType() {
                    return MarkerPacket.class;
                }

                @Override
                public void execute(NetworkSession session, MarkerPacket packet) {
                }
            });
        }
        engine.tickScheduler().register((number, delta) -> TICKS.incrementAndGet());
    }

    @Override
    public void onStart(ServerEngine engine) {
        STARTS.incrementAndGet();
    }

    @Override
    public void onStop(ServerEngine engine) {
        STOPS.incrementAndGet();
    }
}
