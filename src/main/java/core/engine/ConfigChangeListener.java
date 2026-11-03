package core.engine;

/** Notified after a successful reload swapped in a configuration that differs from the previous one. */
@FunctionalInterface
public interface ConfigChangeListener {

    void onConfigChanged(EngineConfig previous, EngineConfig updated);
}
