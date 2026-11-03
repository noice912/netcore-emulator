package core.engine;

import core.plugin.PluginManager;

import java.util.List;

/** JSON-friendly snapshot of everything the engine is doing, for the console and the admin API. */
public record EngineStatus(String name, String version, boolean running, long uptimeMillis, String configPath,
                           boolean configWatching, ListenerStatus dispatch, ListenerStatus gateway,
                           ListenerStatus admin, SessionCounts sessions, EngineMetrics.Snapshot metrics,
                           TickScheduler.Stats tick, int registeredOpcodes, SchemaStatus schemas,
                           CaptureStatus capture, MemoryStatus memory, PersistenceStatus persistence,
                           List<PluginManager.PluginInfo> plugins) {

    public record ListenerStatus(boolean enabled, boolean running, int port) {
    }

    public record SessionCounts(int total, int tcp, int udp, int loopback) {
    }

    public record SchemaStatus(boolean enabled, boolean watching, String directory, int packets, int structs,
                               int rules, long packetsHandled, long responsesSent) {
    }

    public record CaptureStatus(boolean recording, long frames, String file) {
    }

    public record MemoryStatus(int entries, int namespaces) {
    }

    public record PersistenceStatus(boolean enabled, String file, long lastSaveMillis) {
    }
}
