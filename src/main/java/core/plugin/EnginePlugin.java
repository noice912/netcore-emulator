package core.plugin;

import core.engine.ServerEngine;

/**
 * Extension point for code that ships separately from the engine.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}: list the implementing
 * class in {@code META-INF/services/core.plugin.EnginePlugin} inside a jar dropped into the
 * plugin directory (or on the engine's classpath). A plugin gets the running {@link ServerEngine}
 * and typically registers packets and handlers, tickables, session listeners or schema listeners
 * in {@link #onLoad}.
 *
 * <p>Lifecycle: {@code onLoad} runs before the listeners bind, {@code onStart} after they are up,
 * {@code onStop} first thing during shutdown. A plugin that throws in {@code onLoad} is disabled
 * without affecting the others.
 */
public interface EnginePlugin {

    String name();

    default String version() {
        return "1.0";
    }

    void onLoad(ServerEngine engine) throws Exception;

    default void onStart(ServerEngine engine) throws Exception {
    }

    default void onStop(ServerEngine engine) {
    }
}
