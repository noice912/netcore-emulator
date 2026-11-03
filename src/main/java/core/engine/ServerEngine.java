package core.engine;

import core.handlers.PacketDispatcher;
import core.handlers.PacketRegistry;
import core.handlers.builtin.BuiltinHandlers;
import core.network.NetworkListener;
import core.network.NetworkSession;
import core.network.Protocol;
import core.network.SessionManager;
import core.network.TcpDispatchServer;
import core.network.UdpGatewayServer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level lifecycle owner. Wires the configuration, in-memory store, packet registry and network
 * listeners together and starts and stops them as one unit.
 *
 * <p>Startup order: load configuration, build the shared services (metrics, memory store, registry,
 * dispatcher, session pool), create the Netty event-loop groups, bind the enabled listeners, start
 * the maintenance scheduler, then begin watching the configuration file. Shutdown reverses it.
 *
 * <p>The engine subscribes to configuration changes: a reload that moves a listener's bind address
 * or port rebinds just that listener, and toggling {@code enabled} starts or stops one. Changes
 * that require a full restart (worker-thread count) are logged rather than applied silently.
 */
public final class ServerEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ServerEngine.class);
    private static final String BANNER = "CustomEmulator protocol engine v1.0.0";

    private final ConfigManager configManager;
    private final EngineMetrics metrics = new EngineMetrics();
    private final MemoryStore memoryStore = new MemoryStore();
    private final PacketRegistry registry = new PacketRegistry();
    private final PacketDispatcher dispatcher;
    private final SessionManager sessions;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final CountDownLatch terminated = new CountDownLatch(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private TcpDispatchServer tcpServer;
    private UdpGatewayServer udpServer;
    private ScheduledExecutorService maintenance;
    private long startedAtMillis;

    private ServerEngine(ConfigManager configManager) {
        this.configManager = Objects.requireNonNull(configManager, "configManager");
        this.sessions = new SessionManager(configManager.supplier(), metrics);
        this.dispatcher = new PacketDispatcher(registry, configManager.supplier(), metrics);
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

    /** Bind listeners and start background tasks. Throws if a listener that should run cannot bind. */
    public synchronized void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        startedAtMillis = System.currentTimeMillis();
        EngineConfig cfg = configManager.current();
        log.info("{} starting", BANNER);

        int workers = cfg.engine().workerThreads();
        bossGroup = new NioEventLoopGroup(1, threadFactory("engine-boss"));
        workerGroup = workers > 0
                ? new NioEventLoopGroup(workers, threadFactory("engine-worker"))
                : new NioEventLoopGroup(0, threadFactory("engine-worker"));

        tcpServer = new TcpDispatchServer(bossGroup, workerGroup, configManager.supplier(), sessions, dispatcher, metrics);
        udpServer = new UdpGatewayServer(workerGroup, configManager.supplier(), sessions, dispatcher, metrics);

        try {
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
        } catch (Exception e) {
            // Roll back a partial start so we never leave a half-bound engine behind.
            log.error("listener startup failed; rolling back", e);
            stop();
            throw e;
        }

        startMaintenance(cfg.engine().maintenanceIntervalSeconds());
        configManager.addListener(this::onConfigChanged);
        if (cfg.engine().configWatchEnabled()) {
            try {
                configManager.startWatching(cfg.engine().configWatchDebounceMillis());
            } catch (Exception e) {
                log.warn("could not start config file watcher; reloads must be triggered manually: {}", e.getMessage());
            }
        }

        log.info("{} ready: {}", BANNER, listenerSummary());
    }

    /** Stop everything. Idempotent and safe from any thread, including a shutdown hook. */
    @Override
    public void close() {
        stop();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("{} stopping", BANNER);
        configManager.removeListener(this::onConfigChanged);
        configManager.stopWatching();

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
        shutdownGroup(workerGroup);
        shutdownGroup(bossGroup);
        workerGroup = null;
        bossGroup = null;

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
        if (!running.get()) {
            return;
        }
        if (previous.engine().workerThreads() != updated.engine().workerThreads()) {
            log.warn("engine.workerThreads changed from {} to {}; a restart is required for this to take effect",
                    previous.engine().workerThreads(), updated.engine().workerThreads());
        }
        reconcile(tcpServer, updated.network().dispatch().enabled(), updated);
        reconcile(udpServer, updated.network().gateway().enabled(), updated);

        long maintenanceWas = previous.engine().maintenanceIntervalSeconds();
        long maintenanceNow = updated.engine().maintenanceIntervalSeconds();
        if (maintenanceWas != maintenanceNow && maintenance != null) {
            maintenance.shutdownNow();
            startMaintenance((int) maintenanceNow);
        }
        log.info("configuration change applied: {}", listenerSummary());
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
        } catch (RuntimeException e) {
            log.error("maintenance task failed (will run again next interval)", e);
        }
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

    /** The bound TCP port, or -1 when the dispatch listener is not running. */
    public int dispatchPort() {
        return tcpServer != null ? tcpServer.boundPort() : -1;
    }

    /** The bound UDP port, or -1 when the gateway listener is not running. */
    public int gatewayPort() {
        return udpServer != null ? udpServer.boundPort() : -1;
    }

    public Duration uptime() {
        return running.get() ? Duration.ofMillis(System.currentTimeMillis() - startedAtMillis) : Duration.ZERO;
    }

    /** Human-readable multi-line status for the admin console. */
    public String status() {
        EngineConfig cfg = configManager.current();
        EngineMetrics.Snapshot m = metrics.snapshot();
        StringBuilder sb = new StringBuilder(512);
        sb.append(BANNER).append(System.lineSeparator());
        sb.append("  uptime        : ").append(formatUptime(uptime())).append(System.lineSeparator());
        sb.append("  config        : ").append(configManager.path().toAbsolutePath())
                .append(configManager.isWatching() ? " (watching)" : "").append(System.lineSeparator());
        sb.append("  TCP dispatch  : ").append(listenerState(tcpServer, cfg.network().dispatch().enabled()))
                .append(System.lineSeparator());
        sb.append("  UDP gateway   : ").append(listenerState(udpServer, cfg.network().gateway().enabled()))
                .append(System.lineSeparator());
        sb.append("  sessions      : ").append(sessions.activeCount())
                .append(" (tcp=").append(sessions.count(Protocol.TCP))
                .append(", udp=").append(sessions.count(Protocol.UDP))
                .append(", loopback=").append(sessions.loopbackCount()).append(")").append(System.lineSeparator());
        sb.append("  connections   : accepted=").append(m.connectionsAccepted())
                .append(", rejected=").append(m.connectionsRejected()).append(System.lineSeparator());
        sb.append("  packets in    : ").append(m.packetsIn()).append(" (").append(m.bytesIn()).append(" bytes)")
                .append(System.lineSeparator());
        sb.append("  packets out   : ").append(m.packetsOut()).append(" (").append(m.bytesOut()).append(" bytes)")
                .append(System.lineSeparator());
        sb.append("  handled       : ").append(m.packetsHandled())
                .append(", unknown=").append(m.unknownOpcodes())
                .append(", malformed=").append(m.malformedPackets())
                .append(", rejected=").append(m.packetsRejected())
                .append(", errors=").append(m.handlerErrors()).append(System.lineSeparator());
        sb.append("  registry      : ").append(registry.size()).append(" opcode(s)").append(System.lineSeparator());
        sb.append("  memory store  : ").append(memoryStore.totalEntries()).append(" entr(ies) across ")
                .append(memoryStore.namespaces().size()).append(" namespace(s)");
        return sb.toString();
    }

    private String listenerSummary() {
        return "TCP " + (tcpServer != null && tcpServer.isRunning() ? "on :" + tcpServer.boundPort() : "down")
                + ", UDP " + (udpServer != null && udpServer.isRunning() ? "on :" + udpServer.boundPort() : "down");
    }

    private static String listenerState(NetworkListener listener, boolean enabled) {
        if (!enabled) {
            return "disabled";
        }
        if (listener == null || !listener.isRunning()) {
            return "enabled, not bound";
        }
        return "listening on port " + listener.boundPort();
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
