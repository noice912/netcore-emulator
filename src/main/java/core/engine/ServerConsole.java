package core.engine;

import core.network.NetworkSession;
import core.plugin.PluginManager;
import core.schema.PacketDef;
import core.schema.ResponseRule;
import core.schema.SchemaSet;
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
 * Admin console over standard input. Runs on the calling thread and returns only when the
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
                    return;
                }
            }
        } catch (IOException e) {
            log.warn("console input error: {}", e.getMessage());
        }
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
    boolean handle(String line) {
        if (line.isEmpty()) {
            return true;
        }
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].trim() : "";
        try {
            switch (command) {
                case "help", "?" -> printHelp();
                case "status", "stat" -> println(engine.status());
                case "sessions", "ls" -> printSessions();
                case "mem", "memory" -> printMemory();
                case "config" -> println(engine.config().toJson());
                case "reload" -> {
                    if (argument.equalsIgnoreCase("schemas")) {
                        printSchemaReload();
                    } else {
                        ConfigManager.ReloadResult result = engine.reloadConfig();
                        println("config reload: " + result.outcome() + " - " + result.message());
                    }
                }
                case "schemas" -> {
                    if (argument.equalsIgnoreCase("reload")) {
                        printSchemaReload();
                    } else {
                        printSchemas();
                    }
                }
                case "plugins" -> printPlugins();
                case "tick" -> println(String.valueOf(engine.tickScheduler().stats()));
                case "save" -> printSave();
                case "capture" -> printCapture(argument);
                case "broadcast" -> {
                    if (argument.isEmpty()) {
                        println("usage: broadcast <text>");
                    } else {
                        println("broadcast delivered to " + engine.broadcastText(argument) + " session(s)");
                    }
                }
                case "kick" -> printKick(argument);
                case "stop", "quit", "exit" -> {
                    println("stopping...");
                    engine.stop();
                    return false;
                }
                default -> println("unknown command '" + command + "' (type 'help')");
            }
        } catch (RuntimeException e) {
            println("command failed: " + e);
            log.warn("console command '{}' failed", line, e);
        }
        return true;
    }

    private void printSchemaReload() {
        var result = engine.schemas().reload();
        println("schema reload: " + (result.success() ? "OK" : "FAILED") + " - " + result.message());
    }

    private void printSchemas() {
        SchemaSet set = engine.schemas().current();
        if (set.isEmpty()) {
            println("no schema packets loaded (directory: " + engine.schemas().directory() + ")");
            return;
        }
        println(set.packets().size() + " schema packet(s) from " + String.join(", ", set.sources()) + ":");
        for (PacketDef def : set.packets()) {
            println(String.format("  0x%04X %-24s %d field(s)%s", def.opcode(), def.name(), def.fields().size(),
                    def.requiresHandshake() ? "" : " [no handshake]"));
        }
        if (!set.rules().isEmpty()) {
            println(set.rules().size() + " response rule(s):");
            for (ResponseRule rule : set.rules()) {
                println("  " + rule);
            }
        }
    }

    private void printPlugins() {
        List<PluginManager.PluginInfo> plugins = engine.plugins().plugins();
        if (plugins.isEmpty()) {
            println("no plugins loaded");
            return;
        }
        for (PluginManager.PluginInfo p : plugins) {
            println(String.format("  %-24s v%-8s %-8s %s", p.name(), p.version(), p.state(), p.source()));
        }
    }

    private void printSave() {
        if (engine.persistence().isEmpty()) {
            println("persistence is disabled");
            return;
        }
        try {
            int saved = engine.persistence().get().save();
            println("saved " + saved + " entr(ies) to " + engine.persistence().get().file());
        } catch (IOException e) {
            println("save failed: " + e.getMessage());
        }
    }

    private void printCapture(String argument) {
        switch (argument.toLowerCase()) {
            case "on", "start" -> {
                try {
                    boolean started = engine.recorder().start();
                    println((started ? "capture started: " : "capture already running: ")
                            + engine.recorder().currentFile().map(Object::toString).orElse("?"));
                } catch (IOException e) {
                    println("capture could not start: " + e.getMessage());
                }
            }
            case "off", "stop" -> {
                engine.recorder().stop();
                println("capture stopped (" + engine.recorder().framesRecorded() + " frames recorded in total)");
            }
            default -> println(engine.recorder().isRecording()
                    ? "capture is recording to " + engine.recorder().currentFile().map(Object::toString).orElse("?")
                    + " (" + engine.recorder().framesRecorded() + " frames)"
                    : "capture is off (use 'capture on')");
        }
    }

    private void printKick(String argument) {
        String[] parts = argument.split("\\s+", 2);
        long id;
        try {
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            println("usage: kick <sessionId> [reason]");
            return;
        }
        String reason = parts.length > 1 ? parts[1] : "kicked by operator";
        println(engine.kick(id, reason) ? "session " + id + " kicked" : "no session with id " + id);
    }

    private void printSessions() {
        List<NetworkSession> all = engine.sessions().snapshot();
        if (all.isEmpty()) {
            println("no active sessions");
            return;
        }
        println(all.size() + " active session(s):");
        for (NetworkSession s : all) {
            println(String.format("  #%-4d %-4s %-21s %-13s in=%-6d out=%-6d idle=%dms", s.id(), s.protocol(),
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
        println("commands: status | sessions | mem | config | reload [schemas] | schemas | plugins | tick | save"
                + " | capture on|off | broadcast <text> | kick <id> [reason] | stop");
    }

    private void println(String text) {
        System.out.println(text);
    }
}
