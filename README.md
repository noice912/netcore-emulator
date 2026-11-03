# CustomEmulator

A lightweight, framework-independent network protocol engine for Java 17. It binds TCP ("dispatch")
and UDP ("gateway") listeners, frames and routes length-prefixed packets to pluggable handlers, and
keeps everything configurable from an external `config.json` that can be reloaded while running.

It is a clean-room foundation: no third-party game-server framework, only Netty for the event loop,
Gson for config, and SLF4J/Logback for logging.

## Requirements

- JDK 17+
- No local Gradle needed; use the bundled wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Build and test

```bash
./gradlew build        # compile and run the full test suite
./gradlew test         # tests only
./gradlew run          # start the engine using ./config.json
./gradlew installDist  # produce build/install/CustomEmulator with start scripts + config.json
```

On start the engine binds the listeners, then reads admin commands from standard input:
`status`, `sessions`, `mem`, `reload`, `stop`.

## Layout

```
src/main/java/core/
  engine/    ServerEngine (lifecycle + main), ConfigManager + ConfigWatcher (hot reload),
             EngineConfig (Gson schema + validation), MemoryStore, EngineMetrics, ServerConsole
  network/   Netty NIO listeners (TcpDispatchServer, UdpGatewayServer), NetworkSession,
             SessionManager (thread-safe pool), Frame + FrameCodec (wire format), transports
  handlers/  BasePacket, PacketHandler, PacketRegistry, PacketDispatcher, and builtin/ packets
```

## Wire format

Every packet is a length-prefixed frame, big-endian:

```
magic   uint16  0x4E45
opcode  uint16
length  uint32  payload length (bounded by config)
payload length bytes
```

TCP concatenates frames on the stream; UDP carries exactly one frame per datagram. When
`security.encryption.enabled` is set, the payload bytes (not the header) are XOR-ed with the
configured rolling key in both directions.

Built-in opcodes: `0x0003` PING, `0x0004` PONG, `0x0005` DISCONNECT, `0x0006` ECHO. `0x0001`/`0x0002`
are reserved for a handshake (see below). Use opcodes above `0x00FF` for your own packets.

## Adding a packet

1. Extend `BasePacket`, implement `decode`/`encode` with the guarded `read*`/`write*` helpers, and
   give it a public no-arg constructor.
2. Implement `PacketHandler<YourPacket>`.
3. Register it: `engine.registry().register(opcode, YourPacket::new, new YourHandler());`

The dispatcher decodes on the connection's event-loop thread, so handlers must not block; offload
slow work to an executor and reply via `NetworkSession.send`.

## Handshake and authentication

The connection lifecycle for a handshake is built in: a session starts `CONNECTED`, the TCP listener
enforces `security.handshakeTimeoutSeconds`, and when `security.requireHandshake` is true the
dispatcher rejects any packet whose handler sets `requiresHandshake() == true` until the session is
promoted. The credential check itself is deliberately **not** shipped, because it is specific to the
protocol you are emulating. To enable it:

1. Register a `PacketHandler<HandshakeRequestPacket>` for opcode `0x0001` that returns
   `false` from `requiresHandshake()`.
2. In its `execute`, validate the request and call
   `session.markAuthenticated(version, token)`, then reply with a `HandshakeResponsePacket`.
3. Set `security.requireHandshake: true` in `config.json`.

`HandshakeRequestPacket` and `HandshakeResponsePacket` are provided as the on-the-wire messages.

## Configuration (`config.json`)

Parsed with Gson; every key has a default, so a partial file only overrides what it lists. Unknown
keys are logged and ignored. Invalid values are reported with a single message listing each problem,
and a failed reload keeps the running configuration untouched.

| Section    | Key                              | Default   | Meaning |
|------------|----------------------------------|-----------|---------|
| network    | `bindAddress`                    | `0.0.0.0` | Default bind address for both listeners. |
| network    | `loopbackOnly`                   | `false`   | Reject any source that is not on this machine. |
| network    | `maxSessions`                    | `1024`    | Global live-session cap. |
| network    | `maxSessionsPerAddress`          | `16`      | Per remote IP; `0` disables. |
| dispatch   | `enabled` / `port`               | `true` / `22101` | TCP listener. `port: 0` picks an ephemeral port. |
| dispatch   | `idleTimeoutSeconds`             | `90`      | Close silent TCP connections; `0` disables. |
| dispatch   | `maxPayloadBytes`                | `65536`   | Largest accepted TCP payload. |
| gateway    | `enabled` / `port`               | `true` / `22102` | UDP listener. |
| gateway    | `sessionTimeoutSeconds`          | `60`      | Forget idle UDP endpoints; `0` disables. |
| gateway    | `maxPayloadBytes`                | `1400`    | Largest accepted datagram payload. |
| security   | `requireHandshake`               | `false`   | Gate non-handshake packets until authenticated. |
| security   | `handshakeTimeoutSeconds`        | `10`      | Drop TCP connections that never handshake; `0` disables. |
| security   | `allowedProtocolVersions`        | `[1]`     | Versions a handshake may advertise. |
| security   | `maxUnknownPacketsPerSession`    | `32`      | Close abusive senders; `0` disables. |
| security   | `maxMalformedPacketsPerSession`  | `8`       | Close abusive senders; `0` disables. |
| security   | `encryption.enabled` / `keyHex`  | `false` / `""` | Payload XOR obfuscation. |
| engine     | `workerThreads`                  | `0`       | Netty workers; `0` = automatic. Needs a restart. |
| engine     | `configWatchEnabled`             | `true`    | Reload automatically when the file changes. |
| engine     | `consoleEnabled`                 | `true`    | Read admin commands from stdin. |
| engine     | `heartbeatIntervalSeconds`       | `30`      | PING cadence advertised in the handshake response. |

### Hot reload

`ConfigManager` holds an immutable snapshot and swaps it atomically. Editing `config.json` (or
sending `reload` on the console) reparses the file; a change to a listener's bind address/port
rebinds just that listener, toggling `enabled` starts or stops one, and a broken file is rejected
with the current settings left running.

### Binding to `0.0.0.0`

The default accepts connections on every interface. For a local-only setup, set `loopbackOnly: true`
(or `bindAddress: "127.0.0.1"`).

## Mapping your own protocol

Once the engine runs, feed it your packet definitions by creating `BasePacket` subclasses per opcode
(from your unencrypted packet maps or JSON schemas) and registering a handler for each. Start from
the built-in `core.handlers.builtin` packets as templates.
