package core.network;

import core.engine.EngineConfig;

import java.io.IOException;

/** A bound socket the engine starts, stops and rebinds as configuration changes. */
public interface NetworkListener extends AutoCloseable {

    String name();

    Protocol protocol();

    /** Bind. A failure (port in use, bad address) is reported as an {@link IOException}. */
    void start() throws IOException, InterruptedException;

    /** Unbind and close every connection owned by this listener. Idempotent. */
    void stop();

    boolean isRunning();

    /** The port actually bound (useful when configured as 0), or -1 when not running. */
    int boundPort();

    /** Whether honouring {@code updated} requires a stop/start cycle (bind address or port changed). */
    boolean needsRebind(EngineConfig updated);

    @Override
    default void close() {
        stop();
    }
}
