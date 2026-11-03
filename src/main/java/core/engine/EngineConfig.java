package core.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable snapshot of {@code config.json}, parsed with Gson.
 *
 * <p>Every field has a default, so a partial file is valid: only the keys present override the
 * defaults. Unknown keys are reported as warnings (typo protection) and ignored. {@code //} and
 * {@code /* *&#47;} comments are allowed in the file. After parsing, {@link #normalizeAndValidate}
 * replaces null sections with defaults and rejects impossible values with one message listing every
 * problem found.
 */
public final class EngineConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineConfig.class);
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .setStrictness(Strictness.LENIENT)
            .create();

    /** Largest TCP payload the codec will ever accept, whatever the file says. */
    public static final int MAX_TCP_PAYLOAD = 16 * 1024 * 1024;
    /** Largest UDP payload that fits in one IPv4 datagram. */
    public static final int MAX_UDP_PAYLOAD = 65_507;

    private Network network = new Network();
    private Security security = new Security();
    private Engine engine = new Engine();

    public Network network() {
        return network;
    }

    public Security security() {
        return security;
    }

    public Engine engine() {
        return engine;
    }

    // ---- loading --------------------------------------------------------------------------------

    /** The built-in defaults, validated. */
    public static EngineConfig defaults() {
        try {
            return new EngineConfig().normalizeAndValidate();
        } catch (ConfigException e) {
            throw new IllegalStateException("built-in defaults are invalid", e);
        }
    }

    /** Read and validate a file. A missing file is created with the defaults first. */
    public static EngineConfig load(Path path) throws ConfigException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            log.info("{} not found; writing defaults", path.toAbsolutePath());
            writeDefault(path);
        }
        String json;
        try {
            json = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("cannot read " + path.toAbsolutePath() + ": " + e.getMessage(), e);
        }
        return parse(json, path.toString());
    }

    /** Parse and validate JSON text. {@code sourceName} is only used in messages. */
    public static EngineConfig parse(String json, String sourceName) throws ConfigException {
        if (json == null || json.isBlank()) {
            throw new ConfigException(sourceName + " is empty");
        }
        EngineConfig parsed;
        try {
            JsonReader reader = new JsonReader(new StringReader(json));
            reader.setStrictness(Strictness.LENIENT);
            JsonElement root = JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                throw new ConfigException(sourceName + " must contain a JSON object at the top level");
            }
            warnUnknownKeys(root.getAsJsonObject(), EngineConfig.class, "", sourceName);
            parsed = GSON.fromJson(root, EngineConfig.class);
        } catch (JsonParseException | IllegalStateException e) {
            throw new ConfigException(sourceName + " is not valid JSON: " + e.getMessage(), e);
        }
        if (parsed == null) {
            throw new ConfigException(sourceName + " is empty");
        }
        try {
            return parsed.normalizeAndValidate();
        } catch (ConfigException e) {
            throw new ConfigException(sourceName + ": " + e.getMessage(), e);
        }
    }

    /** Write the defaults as pretty JSON, creating parent directories as needed. */
    public static void writeDefault(Path path) throws ConfigException {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, defaults().toJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException("cannot write " + path.toAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Fill null sections with defaults, then validate; throws with every problem listed. */
    EngineConfig normalizeAndValidate() throws ConfigException {
        if (network == null) {
            network = new Network();
        }
        if (security == null) {
            security = new Security();
        }
        if (engine == null) {
            engine = new Engine();
        }
        List<String> problems = new ArrayList<>();
        network.validate(problems);
        security.validate(problems);
        engine.validate(problems);
        if (!network.dispatch.enabled && !network.gateway.enabled) {
            problems.add("network: at least one of dispatch.enabled or gateway.enabled must be true");
        }
        if (!problems.isEmpty()) {
            throw new ConfigException("invalid configuration:" + System.lineSeparator() + "  - "
                    + String.join(System.lineSeparator() + "  - ", problems));
        }
        return this;
    }

    private static void warnUnknownKeys(JsonObject object, Class<?> type, String prefix, String sourceName) {
        Set<String> known = new HashSet<>();
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (!Modifier.isStatic(mods) && !Modifier.isTransient(mods)) {
                known.add(field.getName());
            }
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (!known.contains(entry.getKey())) {
                log.warn("{}: unknown setting '{}' is ignored", sourceName, path);
                continue;
            }
            if (entry.getValue().isJsonObject()) {
                try {
                    Field field = type.getDeclaredField(entry.getKey());
                    warnUnknownKeys(entry.getValue().getAsJsonObject(), field.getType(), path, sourceName);
                } catch (NoSuchFieldException ignored) {
                    // known contains it, so this cannot happen
                }
            }
        }
    }

    // ---- sections -------------------------------------------------------------------------------

    /** Listener addresses, ports and admission limits. */
    public static final class Network {
        private String bindAddress = "0.0.0.0";
        private boolean loopbackOnly = false;
        private int maxSessions = 1024;
        private int maxSessionsPerAddress = 16;
        private Dispatch dispatch = new Dispatch();
        private Gateway gateway = new Gateway();

        /** Default bind address for both listeners. */
        public String bindAddress() {
            return bindAddress;
        }

        /** Refuse sessions from any source other than this machine. */
        public boolean loopbackOnly() {
            return loopbackOnly;
        }

        public int maxSessions() {
            return maxSessions;
        }

        /** Per remote IP address; 0 disables the limit. */
        public int maxSessionsPerAddress() {
            return maxSessionsPerAddress;
        }

        public Dispatch dispatch() {
            return dispatch;
        }

        public Gateway gateway() {
            return gateway;
        }

        public String dispatchBindAddress() {
            return isBlank(dispatch.bindAddress) ? bindAddress : dispatch.bindAddress;
        }

        public String gatewayBindAddress() {
            return isBlank(gateway.bindAddress) ? bindAddress : gateway.bindAddress;
        }

        void validate(List<String> problems) {
            if (dispatch == null) {
                dispatch = new Dispatch();
            }
            if (gateway == null) {
                gateway = new Gateway();
            }
            checkAddress(problems, "network.bindAddress", bindAddress, true);
            if (maxSessions < 1) {
                problems.add("network.maxSessions must be at least 1");
            }
            if (maxSessionsPerAddress < 0) {
                problems.add("network.maxSessionsPerAddress must be 0 (unlimited) or positive");
            }
            dispatch.validate(problems);
            gateway.validate(problems);
        }
    }

    /** TCP listener. */
    public static final class Dispatch {
        private boolean enabled = true;
        private String bindAddress = "";
        private int port = 22101;
        private int backlog = 128;
        private int idleTimeoutSeconds = 90;
        private int maxPayloadBytes = 65_536;

        public boolean enabled() {
            return enabled;
        }

        /** Overrides {@code network.bindAddress} when non-blank. */
        public String bindAddress() {
            return bindAddress == null ? "" : bindAddress;
        }

        /** 0 picks an ephemeral port (handy for tests). */
        public int port() {
            return port;
        }

        public int backlog() {
            return backlog;
        }

        /** Close a connection that has sent nothing for this long; 0 disables. Applies to new connections. */
        public int idleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public int maxPayloadBytes() {
            return maxPayloadBytes;
        }

        void validate(List<String> problems) {
            checkAddress(problems, "network.dispatch.bindAddress", bindAddress, false);
            checkPort(problems, "network.dispatch.port", port);
            if (backlog < 1) {
                problems.add("network.dispatch.backlog must be at least 1");
            }
            if (idleTimeoutSeconds < 0) {
                problems.add("network.dispatch.idleTimeoutSeconds must be 0 (disabled) or positive");
            }
            if (maxPayloadBytes < 16 || maxPayloadBytes > MAX_TCP_PAYLOAD) {
                problems.add("network.dispatch.maxPayloadBytes must be between 16 and " + MAX_TCP_PAYLOAD);
            }
        }
    }

    /** UDP listener. */
    public static final class Gateway {
        private boolean enabled = true;
        private String bindAddress = "";
        private int port = 22102;
        private int sessionTimeoutSeconds = 60;
        private int maxPayloadBytes = 1400;
        private int socketBufferBytes = 1024 * 1024;

        public boolean enabled() {
            return enabled;
        }

        public String bindAddress() {
            return bindAddress == null ? "" : bindAddress;
        }

        public int port() {
            return port;
        }

        /** Forget a UDP endpoint that has been silent for this long; 0 disables reaping. */
        public int sessionTimeoutSeconds() {
            return sessionTimeoutSeconds;
        }

        public int maxPayloadBytes() {
            return maxPayloadBytes;
        }

        /** Kernel send/receive buffer size for the datagram socket. */
        public int socketBufferBytes() {
            return socketBufferBytes;
        }

        void validate(List<String> problems) {
            checkAddress(problems, "network.gateway.bindAddress", bindAddress, false);
            checkPort(problems, "network.gateway.port", port);
            if (sessionTimeoutSeconds < 0) {
                problems.add("network.gateway.sessionTimeoutSeconds must be 0 (disabled) or positive");
            }
            if (maxPayloadBytes < 16 || maxPayloadBytes > MAX_UDP_PAYLOAD) {
                problems.add("network.gateway.maxPayloadBytes must be between 16 and " + MAX_UDP_PAYLOAD);
            }
            if (socketBufferBytes < 4096) {
                problems.add("network.gateway.socketBufferBytes must be at least 4096");
            }
        }
    }

    /** Handshake policy, abuse limits and payload encryption. */
    public static final class Security {
        private boolean requireHandshake = false;
        private int handshakeTimeoutSeconds = 10;
        private int[] allowedProtocolVersions = {1};
        private int maxUnknownPacketsPerSession = 32;
        private int maxMalformedPacketsPerSession = 8;
        private Encryption encryption = new Encryption();

        /**
         * When true, a session must be promoted with {@code NetworkSession#markAuthenticated} by a
         * handler whose {@code requiresHandshake()} is false before any other packet is accepted.
         * Needs a handshake handler registered for the handshake opcode.
         */
        public boolean requireHandshake() {
            return requireHandshake;
        }

        /** TCP connections that have not handshaken within this time are closed; 0 disables. */
        public int handshakeTimeoutSeconds() {
            return handshakeTimeoutSeconds;
        }

        public int[] allowedProtocolVersions() {
            return allowedProtocolVersions.clone();
        }

        public boolean isProtocolVersionAllowed(int version) {
            for (int allowed : allowedProtocolVersions) {
                if (allowed == version) {
                    return true;
                }
            }
            return false;
        }

        /** Close a session after this many unregistered opcodes; 0 disables. */
        public int maxUnknownPacketsPerSession() {
            return maxUnknownPacketsPerSession;
        }

        /** Close a session after this many undecodable payloads; 0 disables. */
        public int maxMalformedPacketsPerSession() {
            return maxMalformedPacketsPerSession;
        }

        public Encryption encryption() {
            return encryption;
        }

        void validate(List<String> problems) {
            if (encryption == null) {
                encryption = new Encryption();
            }
            if (handshakeTimeoutSeconds < 0) {
                problems.add("security.handshakeTimeoutSeconds must be 0 (disabled) or positive");
            }
            if (allowedProtocolVersions == null || allowedProtocolVersions.length == 0) {
                problems.add("security.allowedProtocolVersions must list at least one version");
            }
            if (maxUnknownPacketsPerSession < 0) {
                problems.add("security.maxUnknownPacketsPerSession must be 0 (disabled) or positive");
            }
            if (maxMalformedPacketsPerSession < 0) {
                problems.add("security.maxMalformedPacketsPerSession must be 0 (disabled) or positive");
            }
            encryption.validate(problems);
        }
    }

    /** Payload obfuscation toggle: rolling XOR with a hex key, applied to payload bytes only. */
    public static final class Encryption {
        private boolean enabled = false;
        private String keyHex = "";
        private transient volatile byte[] keyBytes;

        public boolean enabled() {
            return enabled;
        }

        public String keyHex() {
            return keyHex == null ? "" : keyHex;
        }

        /** The decoded key when encryption is enabled, otherwise {@code null}. */
        public byte[] keyOrNull() {
            if (!enabled) {
                return null;
            }
            byte[] key = keyBytes;
            if (key == null) {
                key = decodeHex(keyHex());
                keyBytes = key;
            }
            return key.length == 0 ? null : key;
        }

        void validate(List<String> problems) {
            if (!enabled) {
                return;
            }
            String hex = keyHex();
            if (hex.isBlank()) {
                problems.add("security.encryption.keyHex is required when encryption is enabled");
                return;
            }
            try {
                byte[] key = decodeHex(hex);
                if (key.length > 1024) {
                    problems.add("security.encryption.keyHex must be at most 1024 bytes (2048 hex digits)");
                }
                keyBytes = key;
            } catch (IllegalArgumentException e) {
                problems.add("security.encryption.keyHex: " + e.getMessage());
            }
        }
    }

    /** Process-level behaviour. */
    public static final class Engine {
        private int workerThreads = 0;
        private boolean configWatchEnabled = true;
        private int configWatchDebounceMillis = 300;
        private boolean logUnknownOpcodes = true;
        private boolean consoleEnabled = true;
        private int heartbeatIntervalSeconds = 30;
        private int maintenanceIntervalSeconds = 5;

        /** Netty worker threads; 0 lets Netty pick (2 x CPU cores). Requires a restart to change. */
        public int workerThreads() {
            return workerThreads;
        }

        /** Re-read config.json automatically when it changes on disk. */
        public boolean configWatchEnabled() {
            return configWatchEnabled;
        }

        public int configWatchDebounceMillis() {
            return configWatchDebounceMillis;
        }

        public boolean logUnknownOpcodes() {
            return logUnknownOpcodes;
        }

        /** Read admin commands (status, reload, stop) from standard input. */
        public boolean consoleEnabled() {
            return consoleEnabled;
        }

        /** Advertised to clients in the handshake response as the expected PING cadence. */
        public int heartbeatIntervalSeconds() {
            return heartbeatIntervalSeconds;
        }

        /** How often idle UDP sessions are reaped. */
        public int maintenanceIntervalSeconds() {
            return maintenanceIntervalSeconds;
        }

        void validate(List<String> problems) {
            if (workerThreads < 0) {
                problems.add("engine.workerThreads must be 0 (automatic) or positive");
            }
            if (configWatchDebounceMillis < 0 || configWatchDebounceMillis > 60_000) {
                problems.add("engine.configWatchDebounceMillis must be between 0 and 60000");
            }
            if (heartbeatIntervalSeconds < 1) {
                problems.add("engine.heartbeatIntervalSeconds must be at least 1");
            }
            if (maintenanceIntervalSeconds < 1) {
                problems.add("engine.maintenanceIntervalSeconds must be at least 1");
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void checkAddress(List<String> problems, String name, String value, boolean required) {
        if (isBlank(value)) {
            if (required) {
                problems.add(name + " must not be blank");
            }
            return;
        }
        try {
            InetAddress.getByName(value.trim());
        } catch (UnknownHostException e) {
            problems.add(name + " is not a usable address: " + value);
        }
    }

    private static void checkPort(List<String> problems, String name, int port) {
        if (port < 0 || port > 65_535) {
            problems.add(name + " must be between 0 and 65535");
        }
    }

    static byte[] decodeHex(String hex) {
        String clean = hex == null ? "" : hex.trim();
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            clean = clean.substring(2);
        }
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string has an odd number of digits");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(2 * i), 16);
            int lo = Character.digit(clean.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("hex string contains a non-hex character at position " + (2 * i));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
