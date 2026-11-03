package core.engine;

import core.network.NetworkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal admin console over standard input. Runs on the calling thread and returns only when the
 * operator quits or standard input reaches end-of-file (in which case the engine keeps running
 * until it is signalled).
 */
public final class ServerConsole {

    private static final Logger log = LoggerFactory.getLogger(ServerConsole.class);

    private final ServerEngine engine;

    public ServerConsole(ServerEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public void run() {
        printHelp();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while (engine.isRunning() && (line = reader.readLine()) != null) {
                if (!handle(line.trim())) {
                    return; // quit
                }
            }
        } catch (IOException e) {
            log.warn("console input error: {}", e.getMessage());
        }
        // End-of-input but the engine is still up (e.g. launched without an attached terminal):
        // wait for an external signal instead of tearing the server down.
        if (engine.isRunning()) {
            log.info("console input closed; engine continues running (send SIGINT/SIGTERM to stop)");
            try {
                engine.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return {@code false} when the operator asked to quit. */
    private boolean handle(String command) {
        switch (command.toLowerCase()) {
            case "" -> { /* ignore blank lines */ }
            case "help", "?" -> printHelp();
            case "status", "stat" -> println(engine.status());
            case "sessions", "ls" -> printSessions();
            case "mem", "memory" -> printMemory();
            case "reload" -> printReload();
            case "stop", "quit", "exit" -> {
                println("stopping...");
                engine.stop();
                return false;
            }
            default -> println("unknown command '" + command + "' (type 'help')");
        }
        return true;
    }

    private void printReload() {
        ConfigManager.ReloadResult result = engine.configManager().reload();
        println("reload: " + result.outcome() + " - " + result.message());
    }

    private void printSessions() {
        List<NetworkSession> all = engine.sessions().snapshot();
        if (all.isEmpty()) {
            println("no active sessions");
            return;
        }
        println(all.size() + " active session(s):");
        for (NetworkSession s : all) {
            println(String.format("  #%d %-5s %-21s %-13s in=%d out=%d idle=%dms", s.id(), s.protocol(),
                    s.remoteAddress(), s.state(), s.packetsIn(), s.packetsOut(), s.idleMillis()));
        }
    }

    private void printMemory() {
        Map<String, Integer> stats = engine.memoryStore().stats();
        if (stats.isEmpty()) {
            println("memory store is empty");
            return;
        }
        println("memory store (" + engine.memoryStore().totalEntries() + " entries):");
        stats.forEach((ns, count) -> println(String.format("  %-24s %d", ns, count)));
    }

    private void printHelp() {
        println("commands: status | sessions | mem | reload | stop");
    }

    private void println(String text) {
        System.out.println(text);
    }
}
