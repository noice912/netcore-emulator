# netcore-emulator

A framework-independent network protocol emulator engine for Java 17. It binds TCP ("dispatch")
and UDP ("gateway") listeners, frames and routes length-prefixed packets, and lets you describe a
protocol in JSON schema files instead of code: packet layouts, nested structs, arrays, and
declarative auto-responses. Around that core sit the pieces a serious emulator needs: a fixed-rate
tick loop, disk persistence, per-session rate limiting, packet capture, a loopback HTTP admin API,
a plugin loader for drop-in jars, hot reload of config and schemas, and an operator console.

Clean-room: only Netty for the event loop, Gson for JSON, SLF4J/Logback for logging.

## Requirements

- JDK 17+
- No local Gradle needed; use the wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Build, test, run

```bash
./gradlew build        # compile and run the full test suite
./gradlew run          # start the engine (reads ./config.json, ./schemas, ./plugins)
./gradlew installDist  # build/install/netcore-emulator with start scripts, config.json and schemas/
```

On start the engine binds the listeners, loads `schemas/*.json`, starts the tick loop and the
admin API, then reads operator commands from standard input. All directories in the configuration
are relative to the working directory.

## Layout

```
src/main/java/core/
  engine/    ServerEngine (lifecycle + main), EngineConfig (Gson schema + validation),
             ConfigManager + FileWatcher (hot reload), MemoryStore + PersistentStore,
             TickScheduler, EngineMetrics, EngineStatus, ServerConsole
  network/   TcpDispatchServer, UdpGatewayServer, NetworkSession, SessionManager (thread-safe pool),
             Frame + FrameCodec (wire format), TokenBucket (rate limiting), PacketRecorder (capture)
  handlers/  BasePacket, PacketHandler, PacketRegistry, PacketDispatcher, and builtin/ packets
  schema/    JSON-defined packets: SchemaLoader, SchemaSet, SchemaPacket, SchemaManager,
             response rules and TemplateEvaluator
  admin/     AdminHttpServer (JDK HttpServer, no extra dependency)
  plugin/    EnginePlugin + PluginManager (ServiceLoader over plugins/*.jar)
schemas/     example.json - a sample protocol to copy from
config.json  engine configuration (every key optional; defaults documented below)
```

## Wire format

Every packet is a length-prefixed frame, big-endian:

```
magic   uint16  0x4E45
opcode  uint16
length  uint32  payload length (bounded by config)
payload length bytes
```

TCP concatenates frames on the stream; UDP carries exactly one frame per datagram. With
`security.encryption.enabled`, payload bytes (never the header) are XOR-ed with the configured
rolling key in both directions.

Built-in opcodes: `0x0003` PING, `0x0004` PONG, `0x0005` DISCONNECT, `0x0006` ECHO. `0x0001` and
`0x0002` are reserved for a handshake (see below). Opcodes up to `0x00FF` are reserved; schema and
plugin packets use `0x0100` and above.

## Defining packets in JSON (schemas)

Drop files into `schemas/`. They are merged, validated (every problem reported at once), and
re-registered automatically when a file changes.

```json
{
  "structs": {
    "Vec3": [ {"name":"x","type":"f32"}, {"name":"y","type":"f32"}, {"name":"z","type":"f32"} ]
  },
  "packets": [
    { "name": "MoveRequest", "opcode": "0x0110", "fields": [
        {"name": "entityId",  "type": "u32"},
        {"name": "position",  "type": "struct", "struct": "Vec3"},
        {"name": "waypoints", "type": "array",  "element": "struct", "struct": "Vec3"} ] },
    { "name": "MoveAck", "opcode": "0x0111", "fields": [
        {"name": "entityId", "type": "u32"}, {"name": "accepted", "type": "bool"} ] }
  ],
  "responses": [
    { "on": "MoveRequest", "send": "MoveAck",
      "fields": { "entityId": "${request.entityId}", "accepted": true } }
  ]
}
```

Field types: `u8 i8 u16 i16 u32 i32 i64 f32 f64 bool string bytes struct array`. Strings are a
16-bit length plus UTF-8, `bytes` a 32-bit length plus raw bytes, arrays a 16-bit count plus
elements (`element` names the element type; nested arrays go through a struct). A packet may set
`"requiresHandshake": false` to be accepted before authentication.

Response rules build the reply from templates. A field whose value is exactly one placeholder keeps
the referenced value's type; placeholders inside longer text are stringified. References:
`${request.<field.path>}`, `${session.id|remote|address|port|protocol|protocolVersion|loopback|packetsIn|openedAt}`,
`${now.millis|seconds|iso}`, `${random.int|long|uuid}`. Set `"closeAfter": true` to disconnect
after the reply. Java code can also react to schema packets through
`engine.schemas().addListener((session, packet) -> ...)` and build replies with
`engine.schemas().build("MoveAck").set("entityId", 5)`.

Schema packets never override an opcode a Java handler already owns.

## Adding a packet in Java

1. Extend `BasePacket`, implement `decode`/`encode` with the guarded `read*`/`write*` helpers, and
   give it a public no-arg constructor.
2. Implement `PacketHandler<YourPacket>`.
3. Register it: `engine.registry().register(opcode, YourPacket::new, new YourHandler());`

Handlers run on the connection's event-loop thread; offload slow work and reply via
`NetworkSession.send`. World logic that must run on a fixed cadence goes into a `Tickable`
registered with `engine.tickScheduler()`.

## Plugins

Package your packets, handlers, tickables and listeners in a jar, list the implementation of
`core.plugin.EnginePlugin` in `META-INF/services/core.plugin.EnginePlugin`, and drop the jar into
`plugins/`. Each jar gets its own class loader; `onLoad` runs before the listeners bind, `onStart`
after, `onStop` first thing at shutdown. A plugin that throws in `onLoad` is disabled, the rest
still load.

## Persistence

`engine.memoryStore()` is a namespaced, thread-safe key/value store. With `persistence.enabled`
it is restored from `data/store.json` at startup and saved atomically (temp file + rename) every
`autosaveIntervalSeconds` when something changed, on `save` from the console or API, and at
shutdown. Persisted values keep their Java type: integers come back as `Long`, floats as `Double`,
plus `Boolean`, `String`, and structured data (Gson `JsonElement`, `Map`, `Collection`) which is
restored as a `JsonElement`. Other object types are skipped with a warning.

## Operating it

Console (standard input):

```
status | sessions | mem | config | reload [schemas] | schemas | plugins | tick | save
capture on|off | broadcast <text> | kick <id> [reason] | stop
```

Admin HTTP API (`admin.enabled`, default `http://127.0.0.1:22180`, loopback only unless
`admin.allowRemote` is set; it is unauthenticated):

```
GET  /status /metrics /metrics/prometheus /sessions /config /schemas /plugins /tick /memory
POST /reload /schemas/reload /save /capture/start /capture/stop
POST /sessions/{id}/kick?reason=...   POST /broadcast?text=...
```

Packet capture (`capture on`, or `capture.enabled`) writes every inbound and outbound frame to
`captures/capture-<timestamp>.log` as timestamped hex lines: the raw material for writing schemas
of an unknown protocol.

## Handshake and authentication

The connection lifecycle for a handshake is built in: a session starts `CONNECTED`, the TCP
listener enforces `security.handshakeTimeoutSeconds`, and with `security.requireHandshake` the
dispatcher rejects any packet whose handler (or schema packet) requires a handshake until the
session is promoted. The credential check itself is deliberately not shipped, because it is specific
to the protocol being emulated. To enable it, register a `PacketHandler<HandshakeRequestPacket>` for
opcode `0x0001` that returns `false` from `requiresHandshake()`, validates the request, calls
`session.markAuthenticated(version, token)`, and replies with a `HandshakeResponsePacket`; then set
`security.requireHandshake: true`.

## Configuration reference (`config.json`)

Every key is optional; a partial file overrides only what it lists. Unknown keys are logged and
ignored. Invalid values are reported together, and a failed reload keeps the running configuration.

| Section     | Key                                | Default          | Meaning |
|-------------|------------------------------------|------------------|---------|
| network     | `bindAddress`                      | `0.0.0.0`        | Default bind address for both listeners. |
| network     | `loopbackOnly`                     | `false`          | Reject any source that is not this machine. |
| network     | `maxSessions` / `maxSessionsPerAddress` | `1024` / `16` | Admission limits (`0` disables the per-address cap). |
| dispatch    | `enabled` / `port` / `backlog`     | `true` / `22101` / `128` | TCP listener. `port: 0` picks an ephemeral port. |
| dispatch    | `idleTimeoutSeconds`               | `90`             | Close silent TCP connections; `0` disables. |
| dispatch    | `maxPayloadBytes`                  | `65536`          | Largest accepted TCP payload. |
| gateway     | `enabled` / `port`                 | `true` / `22102` | UDP listener. |
| gateway     | `sessionTimeoutSeconds`            | `60`             | Forget idle UDP endpoints; `0` disables. |
| gateway     | `maxPayloadBytes` / `socketBufferBytes` | `1400` / `1048576` | Datagram limits. |
| security    | `requireHandshake` / `handshakeTimeoutSeconds` | `false` / `10` | Handshake gating. |
| security    | `allowedProtocolVersions`          | `[1]`            | Versions a handshake may advertise. |
| security    | `maxUnknownPacketsPerSession` / `maxMalformedPacketsPerSession` | `32` / `8` | Close abusive senders; `0` disables. |
| rateLimit   | `enabled` / `packetsPerSecond` / `burst` / `maxViolations` | `true` / `200` / `400` / `10` | Per-session inbound token bucket; applies live. |
| encryption  | `enabled` / `keyHex`               | `false` / `""`   | Payload XOR obfuscation. |
| engine      | `workerThreads`                    | `0`              | Netty workers; `0` = automatic. Restart needed. |
| engine      | `configWatchEnabled` / `configWatchDebounceMillis` | `true` / `300` | Hot reload of config.json. |
| engine      | `consoleEnabled`                   | `true`           | Read operator commands from stdin. |
| engine      | `tickEnabled` / `ticksPerSecond`   | `true` / `20`    | Simulation loop; rate applies live. |
| engine      | `pluginsEnabled` / `pluginDirectory` | `true` / `plugins` | Plugin jars. Restart needed. |
| engine      | `maintenanceIntervalSeconds`       | `5`              | Idle reaping, capture flush, autosave check. |
| persistence | `enabled` / `directory`            | `true` / `data`  | Snapshot of the memory store. Restart needed. |
| persistence | `autosaveIntervalSeconds` / `namespaces` | `60` / `[]` | Autosave cadence (`0` = shutdown only); namespaces to persist (empty = all). |
| schemas     | `enabled` / `directory`            | `true` / `schemas` | JSON packet definitions. Restart needed to move. |
| schemas     | `hotReload` / `debounceMillis`     | `true` / `300`   | Re-register packets when a file changes. |
| admin       | `enabled` / `bindAddress` / `port` | `true` / `127.0.0.1` / `22180` | HTTP API. |
| admin       | `allowRemote`                      | `false`          | Required to bind the API to a non-loopback address. |
| capture     | `enabled` / `directory`            | `false` / `captures` | Record frames from startup. |
| capture     | `includePayload` / `maxBytesPerFrame` | `true` / `256` | Hex bytes written per frame; `0` = headers only. |

### Hot reload

`ConfigManager` holds an immutable snapshot and swaps it atomically when `config.json` changes
or `reload` is issued. Listener bind address/port changes rebind just that listener; toggling
`enabled` starts or stops one; tick rate, rate limits, capture, the admin API and the watchers
apply live. Settings that need a restart are logged as such.

## Mapping a protocol

1. Point a client at the engine with `capture on`; every frame lands in `captures/` as hex.
2. Describe what you see as packets in `schemas/*.json`; the file reloads as you edit.
3. Add response rules for the replies the client expects, using `${request.*}` to echo fields.
4. When a packet needs real logic, add a `SchemaListener` or a Java handler in a plugin.
