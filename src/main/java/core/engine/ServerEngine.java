package core.engine;

import core.admin.AdminHttpServer;
import core.handlers.PacketDispatcher;
import core.handlers.PacketRegistry;
import core.handlers.builtin.BuiltinHandlers;
import core.handlers.builtin.DisconnectPacket;
import core.handlers.builtin.EchoPacket;
import core.network.NetworkListener;
import core.network.PacketRecorder;
import core.network.Protocol;
import core.network.SessionManager;
import core.network.TcpDispatchServer;
import core.network.UdpGatewayServer;
import core.plugin.PluginManager;
import core.schema.SchemaManager;
import core.schema.SchemaSet;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level lifecycle owner. Wires configuration, persistence, the packet registry, schemas,
 * plugins, the tick loop, the network listeners and the admin API together and starts and stops
 * them as one unit.
 *
 * <p>Startup order: restore persisted data, open capture (if enabled), load plugins ({@code onLoad}),
 * load schemas, bind listeners, start the tick loop and maintenance, start the admin API, begin
 * watching config and schema files, then {@code onStart} the plugins. Shutdown reverses it and
 * flushes persistence last so handlers can still write during the drain.
 *
 * <p>Configuration changes are applied live where possible (listener rebinds, tick rate, rate
 * limits, capture toggle, admin API, watchers). Changes that need a restart (worker threads,
 * plugin, schema and persistence directories) are logged instead of applied silently.
 */
public final class ServerEngine implements AutoCloseable {

    public static final String NAME = "netcore-emulator";
    public static final String VERSION = "2.0.2";

    private static final Logger log = LoggerFactory.getLogger(ServerEngine.class);
    private static final String BANNER = NAME + " engine v" + VERSION;

    private final ConfigManager configManager;
    private final EngineMetrics metrics = new EngineMetrics();
    private final MemoryStore memoryStore = new MemoryStore();
    private final PacketRegistry registry = new PacketRegistry();
    private final PacketDispatcher dispatcher;
    private final SessionManager sessions;
    private final TickScheduler tickScheduler;
    private final SchemaManager schemaManager;
    private final PacketRecorder recorder;
    private final PluginManager pluginManager;
    private final PersistentStore persistentStore;
    private final ConfigChangeListener configListener = this::onConfigChanged;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch terminated = new CountDownLatch(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private TcpDispatchServer tcpServer;
    private UdpGatewayServer udpServer;
    private AdminHttpServer adminServer;
    private ScheduledExecutorService maintenance;
    private long startedAtMillis;

    private ServerEngine(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        EngineConfig cfg = configManager.current();
        this.sessions = new SessionManager(configManager.supplier(), metrics);
        this.dispatcher = new PacketDispatcher(registry, configManager.supplier(), metrics);
        this.tickScheduler = new TickScheduler(cfg.engine().ticksPerSecond());
        this.schemaManager = new SchemaManager(registry, Path.of(cfg.schemas().directory()));
        this.recorder = new PacketRecorder(Path.of(cfg.capture().directory()), cfg.capture().includePayload(),
                cfg.capture().maxBytesPerFrame());
        this.pluginManager = new PluginManager(this, Path.of(cfg.engine().pluginDirectory()));
        this.persistentStore = cfg.persistence().enabled()
                ? new PersistentStore(memoryStore, Path.of(cfg.persistence().directory()), cfg.persistence().namespaces())
                : null;
        sessions.setRecorder(recorder);
        dispatcher.setRecorder(recorder);
        BuiltinHandlers.registerAll(registry);
    }

    /** Build an engine from a configuration file, creating it with defaults if it does not exist. */
    public static ServerEngine create(Path configPath) throws ConfigException {
        return new ServerEngine(ConfigManager.load(configPath));
    }

    /** Build an engine around an already-loaded configuration manager. */
    public static ServerEngine create(ConfigManager configManager) {
        return new ServerEngine(configManager);
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    /** Start every subsystem. Throws (after rolling back) if a required listener cannot bind. */
    public synchronized void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        startedAtMillis = System.currentTimeMillis();
        EngineConfig cfg = configManager.current();
        log.info("{} starting", BANNER);
        try {
            if (persistentStore != null) {
                try {
                    persistentStore.load();
                } catch (IOException e) {
                    log.error("could not restore persisted data from {}; starting with an empty store",
                            persistentStore.file(), e);
                }
            }
            if (cfg.capture().enabled()) {
                startCaptureQuietly();
            }
            if (cfg.engine().pluginsEnabled()) {
                pluginManager.load();
            } else {
                log.info("plugins disabled by configuration");
            }
            if (cfg.schemas().enabled()) {
                schemaManager.reload();
            } else {
                log.info("schemas disabled by configuration");
            }

            int workers = cfg.engine().workerThreads();
            bossGroup = new NioEventLoopGroup(1, threadFactory("engine-boss"));
            workerGroup = new NioEventLoopGroup(workers, threadFactory("engine-worker"));
            log.info("event loops allocated: boss={}, workers={} (engine.workerThreads={}, {} cores)",
                    loopCount(bossGroup), loopCount(workerGroup), workers == 0 ? "0=auto" : String.valueOf(workers),
                    Runtime.getRuntime().availableProcessors());
            tcpServer = new TcpDispatchServer(bossGroup, workerGroup, configManager.supplier(), sessions, dispatcher, metrics);
            udpServer = new UdpGatewayServer(workerGroup, configManager.supplier(), sessions, dispatcher, metrics);
            if (cfg.network().dispatch().enabled()) {
                tcpServer.start();
            } else {
                log.info("TCP dispatch listener disabled by configuration");
            }
            if (cfg.network().gateway().enabled()) {
                udpServer.start();
            } else {
                log.info("UDP gateway listener disabled by configuration");
            }

            if (cfg.engine().tickEnabled()) {
                tickScheduler.start();
            }
            startMaintenance(cfg.engine().maintenanceIntervalSeconds());

            if (cfg.admin().enabled()) {
                startAdminQuietly();
            }

            configManager.addListener(configListener);
            if (cfg.engine().configWatchEnabled()) {
                try {
                    configManager.startWatching(cfg.engine().configWatchDebounceMillis());
                } catch (IOException e) {
                    log.warn("config watcher could not start; reload manually: {}", e.getMessage());
                }
            }
            if (cfg.schemas().enabled() && cfg.schemas().hotReload()) {
                try {
                    schemaManager.startWatching(cfg.schemas().debounceMillis());
                } catch (IOException e) {
                    log.warn("schema watcher could not start; reload manually: {}", e.getMessage());
                }
            }
            pluginManager.startAll();
        } catch (Exception e) {
            log.error("startup failed; rolling back", e);
            stop();
            throw e;
        }
        log.info("{} ready: {}", BANNER, listenerSummary());
    }

    @Override
    public void close() {
        stop();
    }

    /** Stop everything. Idempotent and safe from any thread, including a shutdown hook. */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("{} stopping", BANNER);
        pluginManager.stopAll();
        if (adminServer != null) {
            adminServer.stop();
            adminServer = null;
        }
        configManager.removeListener(configListener);
        configManager.stopWatching();
        schemaManager.stopWatching();
        if (maintenance != null) {
            maintenance.shutdownNow();
            maintenance = null;
        }
        if (tcpServer != null) {
            tcpServer.stop();
        }
        if (udpServer != null) {
            udpServer.stop();
        }
        int closed = sessions.closeAll("engine shutdown");
        if (closed > 0) {
            log.info("closed {} remaining session(s)", closed);
        }
        tickScheduler.stop();
        if (persistentStore != null) {
            persistentStore.close();
        }
        recorder.stop();
        shutdownGroup(workerGroup);
        shutdownGroup(bossGroup);
        workerGroup = null;
        bossGroup = null;
        pluginManager.close();
        log.info("{} stopped", BANNER);
        terminated.countDown();
    }

    /** Block until the engine stops (via {@link #stop()} or a signal). */
    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---- configuration reaction -----------------------------------------------------------------

    private void onConfigChanged(EngineConfig previous, EngineConfig updated) {
        ScheduledExecutorService executor = maintenance;
        if (executor == null) {
            return;
        }
        try {
            executor.execute(() -> applyConfigChange(previous, updated));
        } catch (RejectedExecutionException e) {
            log.debug("config change ignored: engine is stopping");
        }
    }

    private synchronized void applyConfigChange(EngineConfig previous, EngineConfig updated) {
        if (!running.get()) {
            return;
        }
        warnIfRestartNeeded(previous, updated);
        reconcile(tcpServer, updated.network().dispatch().enabled(), updated);
        reconcile(udpServer, updated.network().gateway().enabled(), updated);

        if (updated.admin().enabled()) {
            if (adminServer == null) {
                startAdminQuietly();
            } else if (adminServer.needsRebind(updated)) {
                log.info("admin API bind settings changed; rebinding");
                adminServer.stop();
                try {
                    adminServer.start();
                } catch (IOException e) {
                    log.error("admin API could not rebind: {}", e.getMessage());
                }
            }
        } else if (adminServer != null) {
            adminServer.stop();
            adminServer = null;
        }

        if (updated.capture().enabled() != previous.capture().enabled()) {
            if (updated.capture().enabled()) {
                startCaptureQuietly();
            } else {
                recorder.stop();
            }
        }

        if (updated.engine().ticksPerSecond() != tickScheduler.ticksPerSecond()) {
            tickScheduler.setTicksPerSecond(updated.engine().ticksPerSecond());
            log.info("tick rate changed to {} ticks/s", updated.engine().ticksPerSecond());
        }
        if (updated.engine().tickEnabled() != tickScheduler.isRunning()) {
            if (updated.engine().tickEnabled()) {
                tickScheduler.start();
            } else {
                tickScheduler.stop();
            }
        }

        boolean wantSchemaWatch = updated.schemas().enabled() && updated.schemas().hotReload();
        if (wantSchemaWatch != schemaManager.isWatching()) {
            if (wantSchemaWatch) {
                try {
                    schemaManager.startWatching(updated.schemas().debounceMillis());
                } catch (IOException e) {
                    log.warn("schema watcher could not start: {}", e.getMessage());
                }
            } else {
                schemaManager.stopWatching();
            }
        }

        if (previous.engine().maintenanceIntervalSeconds() != updated.engine().maintenanceIntervalSeconds()) {
            ScheduledExecutorService old = maintenance;
            startMaintenance(updated.engine().maintenanceIntervalSeconds());
            if (old != null) {
                old.shutdown();
            }
        }
        log.info("configuration change applied: {}", listenerSummary());
    }

    private static void warnIfRestartNeeded(EngineConfig previous, EngineConfig updated) {
        if (previous.engine().workerThreads() != updated.engine().workerThreads()) {
            log.warn("engine.workerThreads changed; a restart is required for this to take effect");
        }
        if (previous.engine().pluginsEnabled() != updated.engine().pluginsEnabled()
                || !previous.engine().pluginDirectory().equals(updated.engine().pluginDirectory())) {
            log.warn("plugin settings changed; a restart is required to load or unload plugins");
        }
        if (previous.persistence().enabled() != updated.persistence().enabled()
                || !previous.persistence().directory().equals(updated.persistence().directory())) {
            log.warn("persistence location/enablement changed; a restart is required for this to take effect");
        }
        if (previous.schemas().enabled() != updated.schemas().enabled()
                || !previous.schemas().directory().equals(updated.schemas().directory())) {
            log.warn("schema directory/enablement changed; a restart is required for this to take effect");
        }
        if (!previous.capture().directory().equals(updated.capture().directory())) {
            log.warn("capture.directory changed; a restart is required for this to take effect");
        }
    }

    private void reconcile(NetworkListener listener, boolean shouldRun, EngineConfig updated) {
        if (listener == null) {
            return;
        }
        try {
            if (shouldRun) {
                if (!listener.isRunning()) {
                    log.info("{} listener enabled; starting", listener.name());
                    listener.start();
                } else if (listener.needsRebind(updated)) {
                    log.info("{} listener bind settings changed; rebinding", listener.name());
                    listener.stop();
                    listener.start();
                }
            } else if (listener.isRunning()) {
                log.info("{} listener disabled; stopping", listener.name());
                listener.stop();
            }
        } catch (Exception e) {
            log.error("failed to apply configuration change to {} listener", listener.name(), e);
        }
    }

    private void startAdminQuietly() {
        try {
            if (adminServer == null) {
                adminServer = new AdminHttpServer(this);
            }
            adminServer.start();
        } catch (IOException e) {
            log.error("admin API could not bind {}:{}: {}", config().admin().bindAddress(), config().admin().port(),
                    e.getMessage());
        }
    }

    private void startCaptureQuietly() {
        try {
            recorder.start();
        } catch (IOException e) {
            log.warn("packet capture could not start: {}", e.getMessage());
        }
    }

    // ---- maintenance ----------------------------------------------------------------------------

    private void startMaintenance(int intervalSeconds) {
        int interval = Math.max(1, intervalSeconds);
        maintenance = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "engine-maintenance");
            t.setDaemon(true);
            return t;
        });
        maintenance.scheduleWithFixedDelay(this::runMaintenance, interval, interval, TimeUnit.SECONDS);
    }

    private void runMaintenance() {
        try {
            EngineConfig cfg = configManager.current();
            long udpTimeout = cfg.network().gateway().sessionTimeoutSeconds();
            if (udpTimeout > 0) {
                int reaped = sessions.closeIdle(Protocol.UDP, udpTimeout * 1000L, "udp session idle timeout");
                if (reaped > 0) {
                    log.debug("reaped {} idle UDP session(s)", reaped);
                }
            }
            recorder.flush();
            if (persistentStore != null) {
                int autosave = cfg.persistence().autosaveIntervalSeconds();
                long since = System.currentTimeMillis() - Math.max(persistentStore.lastSaveMillis(), startedAtMillis);
                if (autosave > 0 && since >= autosave * 1000L) {
                    try {
                        persistentStore.saveIfDirty();
                    } catch (IOException e) {
                        log.error("autosave failed", e);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("maintenance task failed (will run again next interval)", e);
        }
    }

    // ---- operations -----------------------------------------------------------------------------

    public ConfigManager.ReloadResult reloadConfig() {
        return configManager.reload();
    }

    /** Send a DISCONNECT to the session and close it. Returns {@code false} if no such session. */
    public boolean kick(long sessionId, String reason) {
        String why = reason == null || reason.isBlank() ? "kicked by operator" : reason;
        return sessions.find(sessionId).map(s -> {
            s.sendAndClose(new DisconnectPacket(why), "kicked: " + why);
            return true;
        }).orElse(false);
    }

    /** Send a text message to every open session as an ECHO packet. Returns how many received it. */
    public int broadcastText(String text) {
        return sessions.broadcast(new EchoPacket(text));
    }

    // ---- accessors ------------------------------------------------------------------------------

    public EngineConfig config() {
        return configManager.current();
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public PacketRegistry registry() {
        return registry;
    }

    public PacketDispatcher dispatcher() {
        return dispatcher;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public MemoryStore memoryStore() {
        return memoryStore;
    }

    public EngineMetrics metrics() {
        return metrics;
    }

    public TickScheduler tickScheduler() {
        return tickScheduler;
    }

    public SchemaManager schemas() {
        return schemaManager;
    }

    public PacketRecorder recorder() {
        return recorder;
    }

    public PluginManager plugins() {
        return pluginManager;
    }

    /** Present only when {@code persistence.enabled} was true at startup. */
    public Optional<PersistentStore> persistence() {
        return Optional.ofNullable(persistentStore);
    }

    /** The bound TCP port, or -1 when the dispatch listener is not running. */
    public int dispatchPort() {
        return tcpServer != null ? tcpServer.boundPort() : -1;
    }

    /** The bound UDP port, or -1 when the gateway listener is not running. */
    public int gatewayPort() {
        return udpServer != null ? udpServer.boundPort() : -1;
    }

    /** The bound admin API port, or -1 when it is not running. */
    public int adminPort() {
        AdminHttpServer admin = adminServer;
        return admin != null ? admin.boundPort() : -1;
    }

    public Duration uptime() {
        return running.get() ? Duration.ofMillis(System.currentTimeMillis() - startedAtMillis) : Duration.ZERO;
    }

    /** Structured status for the admin API. */
    public EngineStatus statusSnapshot() {
        EngineConfig cfg = configManager.current();
        SchemaSet set = schemaManager.current();
        AdminHttpServer admin = adminServer;
        return new EngineStatus(NAME, VERSION, running.get(), uptime().toMillis(),
                configManager.path().toAbsolutePath().toString(), configManager.isWatching(),
                new EngineStatus.ListenerStatus(cfg.network().dispatch().enabled(),
                        tcpServer != null && tcpServer.isRunning(), dispatchPort()),
                new EngineStatus.ListenerStatus(cfg.network().gateway().enabled(),
                        udpServer != null && udpServer.isRunning(), gatewayPort()),
                new EngineStatus.ListenerStatus(cfg.admin().enabled(), admin != null && admin.isRunning(), adminPort()),
                new EngineStatus.SessionCounts(sessions.activeCount(), sessions.count(Protocol.TCP),
                        sessions.count(Protocol.UDP), sessions.loopbackCount()),
                metrics.snapshot(), tickScheduler.stats(), jvmStatus(), registry.size(),
                new EngineStatus.SchemaStatus(cfg.schemas().enabled(), schemaManager.isWatching(),
                        schemaManager.directory().toString(), set.packets().size(), set.structs().size(),
                        set.rules().size(), schemaManager.packetsHandled(), schemaManager.responsesSent()),
                new EngineStatus.CaptureStatus(recorder.isRecording(), recorder.framesRecorded(),
                        recorder.currentFile().map(Path::toString).orElse(null)),
                new EngineStatus.MemoryStatus(memoryStore.totalEntries(), memoryStore.namespaces().size()),
                new EngineStatus.PersistenceStatus(persistentStore != null,
                        persistentStore != null ? persistentStore.file().toString() : null,
                        persistentStore != null ? persistentStore.lastSaveMillis() : 0L),
                pluginManager.plugins());
    }

    /** Human-readable multi-line status for the console. */
    public String status() {
        EngineStatus s = statusSnapshot();
        EngineMetrics.Snapshot m = s.metrics();
        TickScheduler.Stats t = s.tick();
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(1024);
        sb.append(BANNER).append(nl);
        sb.append("  uptime        : ").append(formatUptime(uptime())).append(nl);
        sb.append("  config        : ").append(s.configPath()).append(s.configWatching() ? " (watching)" : "").append(nl);
        sb.append("  TCP dispatch  : ").append(listenerText(s.dispatch())).append(nl);
        sb.append("  UDP gateway   : ").append(listenerText(s.gateway())).append(nl);
        sb.append("  admin API     : ").append(listenerText(s.admin())).append(nl);
        sb.append("  sessions      : ").append(s.sessions().total())
                .append(" (tcp=").append(s.sessions().tcp()).append(", udp=").append(s.sessions().udp())
                .append(", loopback=").append(s.sessions().loopback()).append(")").append(nl);
        sb.append("  connections   : accepted=").append(m.connectionsAccepted())
                .append(", rejected=").append(m.connectionsRejected()).append(nl);
        sb.append("  packets in    : ").append(m.packetsIn()).append(" (").append(m.bytesIn()).append(" bytes)").append(nl);
        sb.append("  packets out   : ").append(m.packetsOut()).append(" (").append(m.bytesOut()).append(" bytes)").append(nl);
        sb.append("  handled       : ").append(m.packetsHandled())
                .append(", unknown=").append(m.unknownOpcodes())
                .append(", malformed=").append(m.malformedPackets())
                .append(", rejected=").append(m.packetsRejected())
                .append(", rate-limited=").append(m.packetsRateLimited())
                .append(", errors=").append(m.handlerErrors()).append(nl);
        sb.append("  tick loop     : ").append(t.running() ? "running" : "stopped")
                .append(" @ ").append(t.ticksPerSecond()).append(" tps, ticks=").append(t.tickCount())
                .append(", last=").append(t.lastTickMicros()).append("us, avg=").append(t.averageTickMicros())
                .append("us, max=").append(t.maxTickMicros()).append("us, overruns=").append(t.overruns())
                .append(", tickables=").append(t.tickables()).append(nl);
        EngineStatus.JvmStatus j = s.jvm();
        sb.append("  jvm memory    : heap ").append(mib(j.heapUsedBytes())).append(" used / ")
                .append(mib(j.heapCommittedBytes())).append(" committed / ").append(mib(j.heapMaxBytes()))
                .append(" max, non-heap ").append(mib(j.nonHeapUsedBytes()))
                .append(", netty direct ").append(mib(j.nettyDirectBytes()))
                .append(", netty heap ").append(mib(j.nettyHeapBytes())).append(nl);
        sb.append("  threads       : ").append(j.threadCount()).append(" total; event loops boss=")
                .append(j.bossLoops()).append(", workers=").append(j.workerLoops())
                .append(" (").append(j.availableProcessors()).append(" cores)").append(nl);
        sb.append("  registry      : ").append(s.registeredOpcodes()).append(" opcode(s)").append(nl);
        sb.append("  schemas       : ").append(s.schemas().enabled() ? "" : "disabled; ")
                .append(s.schemas().packets()).append(" packet(s), ").append(s.schemas().structs())
                .append(" struct(s), ").append(s.schemas().rules()).append(" rule(s), handled=")
                .append(s.schemas().packetsHandled()).append(", responses=").append(s.schemas().responsesSent())
                .append(s.schemas().watching() ? " (watching)" : "").append(nl);
        sb.append("  capture       : ").append(s.capture().recording()
                ? "recording to " + s.capture().file() + " (" + s.capture().frames() + " frames)" : "off").append(nl);
        sb.append("  memory store  : ").append(s.memory().entries()).append(" entr(ies) across ")
                .append(s.memory().namespaces()).append(" namespace(s)").append(nl);
        sb.append("  persistence   : ").append(s.persistence().enabled()
                ? s.persistence().file() + (s.persistence().lastSaveMillis() > 0
                        ? " (last save " + (System.currentTimeMillis() - s.persistence().lastSaveMillis()) / 1000 + "s ago)"
                        : " (not saved yet)")
                : "disabled").append(nl);
        sb.append("  plugins       : ").append(s.plugins().size());
        for (PluginManager.PluginInfo p : s.plugins()) {
            sb.append(nl).append("    - ").append(p.name()).append(" v").append(p.version())
                    .append(" [").append(p.state()).append("] from ").append(p.source());
        }
        return sb.toString();
    }

    private EngineStatus.JvmStatus jvmStatus() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        PooledByteBufAllocatorMetric netty = PooledByteBufAllocator.DEFAULT.metric();
        return new EngineStatus.JvmStatus(heap.getUsed(), heap.getCommitted(), heap.getMax(), nonHeap.getUsed(),
                netty.usedDirectMemory(), netty.usedHeapMemory(),
                ManagementFactory.getThreadMXBean().getThreadCount(), Runtime.getRuntime().availableProcessors(),
                loopCount(bossGroup), loopCount(workerGroup));
    }

    private static int loopCount(EventLoopGroup group) {
        return group instanceof MultithreadEventLoopGroup multi ? multi.executorCount() : 0;
    }

    private static String mib(long bytes) {
        return bytes < 0 ? "n/a" : String.format("%.1f MiB", bytes / 1048576.0);
    }

    private String listenerSummary() {
        return "TCP " + (tcpServer != null && tcpServer.isRunning() ? "on :" + tcpServer.boundPort() : "down")
                + ", UDP " + (udpServer != null && udpServer.isRunning() ? "on :" + udpServer.boundPort() : "down")
                + ", admin " + (adminServer != null && adminServer.isRunning() ? "on :" + adminServer.boundPort() : "off");
    }

    private static String listenerText(EngineStatus.ListenerStatus status) {
        if (!status.enabled()) {
            return "disabled";
        }
        return status.running() ? "listening on port " + status.port() : "enabled, not bound";
    }

    private static String formatUptime(Duration d) {
        long s = d.toSeconds();
        return String.format("%dd %02dh %02dm %02ds", s / 86400, (s % 86400) / 3600, (s % 3600) / 60, s % 60);
    }

    private static ThreadFactory threadFactory(String prefix) {
        return new DefaultThreadFactory(prefix, true);
    }

    private static void shutdownGroup(EventLoopGroup group) {
        if (group == null) {
            return;
        }
        try {
            group.shutdownGracefully(100, 2_000, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- entry point ----------------------------------------------------------------------------

    public static void main(String[] args) {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config.json");
        ServerEngine engine;
        try {
            engine = ServerEngine.create(configPath);
            engine.start();
        } catch (Exception e) {
            log.error("engine failed to start", e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "shutdown-hook"));

        if (engine.config().engine().consoleEnabled()) {
            new ServerConsole(engine).run();
        } else {
            try {
                engine.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                engine.stop();
            }
        }
    }
}
