package core.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.engine.EngineConfig;
import core.engine.EngineMetrics;
import core.engine.EngineStatus;
import core.engine.ServerEngine;
import core.engine.TickScheduler;
import core.network.NetworkSession;
import core.schema.FieldDef;
import core.schema.PacketDef;
import core.schema.ResponseRule;
import core.schema.SchemaSet;
import core.schema.StructDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small HTTP API on the JDK's built-in server (no extra dependency) for observing and operating
 * the engine. It is unauthenticated by design, so the configuration refuses a non-loopback bind
 * unless {@code admin.allowRemote} is set explicitly.
 *
 * <pre>
 * GET  /                      endpoint index
 * GET  /status                full engine status (JSON)
 * GET  /metrics               counters (JSON)         GET /metrics/prometheus  (text exposition)
 * GET  /sessions              live sessions
 * GET  /config                active configuration
 * GET  /schemas               loaded packet schemas and response rules
 * GET  /plugins | /tick | /memory
 * POST /reload                re-read config.json      POST /schemas/reload
 * POST /save                  flush persistence        POST /capture/start | /capture/stop
 * POST /sessions/{id}/kick?reason=...                  POST /broadcast?text=...
 * POST /shutdown              stop the engine (answers, then stops on another thread)
 * </pre>
 */
public final class AdminHttpServer implements AutoCloseable {

    record Binding(String bindAddress, int port) {
        static Binding of(EngineConfig cfg) {
            return new Binding(cfg.admin().bindAddress(), cfg.admin().port());
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AdminHttpServer.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Pattern KICK = Pattern.compile("/sessions/(\\d+)/kick");

    private final ServerEngine engine;
    private final Supplier<EngineConfig> config;
    private HttpServer server;
    private ExecutorService executor;
    private volatile Binding binding;

    public AdminHttpServer(ServerEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = engine.configManager().supplier();
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        Binding target = Binding.of(config.get());
        HttpServer created = HttpServer.create(new InetSocketAddress(target.bindAddress(), target.port()), 16);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "engine-admin");
            t.setDaemon(true);
            return t;
        });
        created.setExecutor(pool);
        created.createContext("/", this::handle);
        created.start();
        server = created;
        executor = pool;
        binding = target;
        log.info("admin API listening on http://{}:{}/", target.bindAddress(), created.getAddress().getPort());
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        executor.shutdownNow();
        server = null;
        executor = null;
        binding = null;
        log.info("admin API stopped");
    }

    public boolean isRunning() {
        return server != null;
    }

    public int boundPort() {
        HttpServer current = server;
        return current == null ? -1 : current.getAddress().getPort();
    }

    public boolean needsRebind(EngineConfig updated) {
        Binding current = binding;
        return current != null && !current.equals(Binding.of(updated));
    }

    @Override
    public void close() {
        stop();
    }

    // ---- routing --------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String path = normalize(exchange.getRequestURI().getPath());
        try {
            switch (method) {
                case "GET" -> handleGet(exchange, path);
                case "POST" -> handlePost(exchange, path, parseQuery(exchange.getRequestURI().getRawQuery()));
                default -> {
                    exchange.getResponseHeaders().set("Allow", "GET, POST");
                    sendJson(exchange, 405, error("method not allowed: " + method));
                }
            }
        } catch (Exception e) {
            log.error("admin request {} {} failed", method, path, e);
            sendJson(exchange, 500, error(String.valueOf(e)));
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange, String path) throws IOException {
        switch (path) {
            case "/" -> sendText(exchange, 200, index());
            case "/status" -> sendJson(exchange, 200, engine.statusSnapshot());
            case "/metrics" -> sendJson(exchange, 200, engine.metrics().snapshot());
            case "/metrics/prometheus" -> sendText(exchange, 200, prometheus());
            case "/sessions" -> {
                List<NetworkSession.View> views = new ArrayList<>();
                engine.sessions().snapshot().forEach(s -> views.add(s.view()));
                sendJson(exchange, 200, views);
            }
            case "/config" -> sendRaw(exchange, 200, "application/json; charset=utf-8", engine.config().toJson());
            case "/schemas" -> sendJson(exchange, 200, schemaSummary());
            case "/plugins" -> sendJson(exchange, 200, engine.plugins().plugins());
            case "/tick" -> sendJson(exchange, 200, engine.tickScheduler().stats());
            case "/memory" -> sendJson(exchange, 200, engine.memoryStore().stats());
            default -> sendJson(exchange, 404, error("no such endpoint: " + path));
        }
    }

    private void handlePost(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        Matcher kick = KICK.matcher(path);
        if (kick.matches()) {
            long id = Long.parseLong(kick.group(1));
            boolean kicked = engine.kick(id, query.getOrDefault("reason", "kicked via admin API"));
            sendJson(exchange, kicked ? 200 : 404, Map.of("session", id, "kicked", kicked));
            return;
        }
        switch (path) {
            case "/reload" -> sendJson(exchange, 200, engine.reloadConfig());
            case "/schemas/reload" -> sendJson(exchange, 200, engine.schemas().reload());
            case "/save" -> {
                if (engine.persistence().isEmpty()) {
                    sendJson(exchange, 409, error("persistence is disabled"));
                } else {
                    int saved = engine.persistence().get().save();
                    sendJson(exchange, 200, Map.of("saved", saved, "file", engine.persistence().get().file().toString()));
                }
            }
            case "/capture/start" -> {
                boolean started = engine.recorder().start();
                sendJson(exchange, 200, Map.of("recording", true, "started", started,
                        "file", engine.recorder().currentFile().map(Object::toString).orElse("")));
            }
            case "/capture/stop" -> {
                engine.recorder().stop();
                sendJson(exchange, 200, Map.of("recording", false, "frames", engine.recorder().framesRecorded()));
            }
            case "/shutdown" -> {
                // Answer first, then stop on another thread: stop() tears this server down too.
                sendJson(exchange, 200, Map.of("stopping", true));
                Thread stopper = new Thread(engine::stop, "admin-shutdown");
                stopper.setDaemon(false);
                stopper.start();
            }
            case "/broadcast" -> {
                String text = query.get("text");
                if (text == null || text.isBlank()) {
                    sendJson(exchange, 400, error("query parameter 'text' is required"));
                } else {
                    sendJson(exchange, 200, Map.of("sent", engine.broadcastText(text)));
                }
            }
            default -> sendJson(exchange, 404, error("no such endpoint: " + path));
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    private Map<String, Object> schemaSummary() {
        SchemaSet set = engine.schemas().current();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("directory", engine.schemas().directory().toString());
        out.put("watching", engine.schemas().isWatching());
        out.put("sources", set.sources());
        out.put("packetsHandled", engine.schemas().packetsHandled());
        out.put("responsesSent", engine.schemas().responsesSent());
        List<Map<String, Object>> packets = new ArrayList<>();
        for (PacketDef def : set.packets()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name", def.name());
            p.put("opcode", String.format("0x%04X", def.opcode()));
            p.put("requiresHandshake", def.requiresHandshake());
            if (!def.description().isEmpty()) {
                p.put("description", def.description());
            }
            p.put("fields", fieldViews(def.fields()));
            packets.add(p);
        }
        out.put("packets", packets);
        Map<String, Object> structs = new LinkedHashMap<>();
        for (StructDef struct : set.structs()) {
            structs.put(struct.name(), fieldViews(struct.fields()));
        }
        out.put("structs", structs);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (ResponseRule rule : set.rules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("on", rule.on());
            r.put("send", rule.send());
            r.put("closeAfter", rule.closeAfter());
            r.put("fields", rule.templates());
            rules.add(r);
        }
        out.put("responses", rules);
        return out;
    }

    private static List<Map<String, Object>> fieldViews(List<FieldDef> fields) {
        List<Map<String, Object>> views = new ArrayList<>();
        for (FieldDef f : fields) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", f.name());
            v.put("type", f.type().id());
            if (f.elementType() != null) {
                v.put("element", f.elementType().id());
            }
            if (f.structName() != null) {
                v.put("struct", f.structName());
            }
            views.add(v);
        }
        return views;
    }

    private String prometheus() {
        EngineStatus s = engine.statusSnapshot();
        EngineMetrics.Snapshot m = s.metrics();
        TickScheduler.Stats t = s.tick();
        StringBuilder sb = new StringBuilder(1024);
        line(sb, "netcore_up", s.running() ? 1 : 0);
        line(sb, "netcore_uptime_seconds", s.uptimeMillis() / 1000);
        line(sb, "netcore_sessions_active", s.sessions().total());
        line(sb, "netcore_sessions_tcp", s.sessions().tcp());
        line(sb, "netcore_sessions_udp", s.sessions().udp());
        line(sb, "netcore_connections_accepted_total", m.connectionsAccepted());
        line(sb, "netcore_connections_rejected_total", m.connectionsRejected());
        line(sb, "netcore_packets_in_total", m.packetsIn());
        line(sb, "netcore_bytes_in_total", m.bytesIn());
        line(sb, "netcore_packets_out_total", m.packetsOut());
        line(sb, "netcore_bytes_out_total", m.bytesOut());
        line(sb, "netcore_packets_handled_total", m.packetsHandled());
        line(sb, "netcore_packets_dropped_total", m.packetsDropped());
        line(sb, "netcore_packets_rejected_total", m.packetsRejected());
        line(sb, "netcore_packets_rate_limited_total", m.packetsRateLimited());
        line(sb, "netcore_unknown_opcodes_total", m.unknownOpcodes());
        line(sb, "netcore_malformed_packets_total", m.malformedPackets());
        line(sb, "netcore_handler_errors_total", m.handlerErrors());
        line(sb, "netcore_tick_count_total", t.tickCount());
        line(sb, "netcore_tick_rate", t.ticksPerSecond());
        line(sb, "netcore_tick_last_micros", t.lastTickMicros());
        line(sb, "netcore_tick_average_micros", t.averageTickMicros());
        line(sb, "netcore_tick_overruns_total", t.overruns());
        line(sb, "netcore_schema_packets_handled_total", s.schemas().packetsHandled());
        line(sb, "netcore_schema_responses_sent_total", s.schemas().responsesSent());
        line(sb, "netcore_memory_entries", s.memory().entries());
        line(sb, "netcore_capture_frames_total", s.capture().frames());
        line(sb, "netcore_sessions_loopback", s.sessions().loopback());
        EngineStatus.JvmStatus j = s.jvm();
        line(sb, "netcore_jvm_heap_used_bytes", j.heapUsedBytes());
        line(sb, "netcore_jvm_heap_committed_bytes", j.heapCommittedBytes());
        line(sb, "netcore_jvm_heap_max_bytes", j.heapMaxBytes());
        line(sb, "netcore_jvm_nonheap_used_bytes", j.nonHeapUsedBytes());
        line(sb, "netcore_netty_direct_memory_bytes", j.nettyDirectBytes());
        line(sb, "netcore_netty_heap_memory_bytes", j.nettyHeapBytes());
        line(sb, "netcore_jvm_threads", j.threadCount());
        line(sb, "netcore_cpu_cores", j.availableProcessors());
        line(sb, "netcore_event_loops_boss", j.bossLoops());
        line(sb, "netcore_event_loops_worker", j.workerLoops());
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, long value) {
        sb.append(name).append(' ').append(value).append('\n');
    }

    private static String index() {
        return "netcore-emulator admin API\n"
                + "GET  /status /metrics /metrics/prometheus /sessions /config /schemas /plugins /tick /memory\n"
                + "POST /reload /schemas/reload /save /capture/start /capture/stop /shutdown\n"
                + "POST /sessions/{id}/kick?reason=... /broadcast?text=...\n";
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private static Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.put(key, value);
        }
        return query;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendRaw(exchange, status, "application/json; charset=utf-8", GSON.toJson(body));
    }

    private static void sendText(HttpExchange exchange, int status, String body) throws IOException {
        sendRaw(exchange, status, "text/plain; charset=utf-8", body);
    }

    private static void sendRaw(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
