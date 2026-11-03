package core.schema;

import core.handlers.PacketHandler;
import core.network.NetworkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Handler registered for every schema packet. Notifies the {@link SchemaListener}s, then applies
 * each {@link ResponseRule} declared for the packet.
 */
public final class SchemaHandler implements PacketHandler<SchemaPacket> {

    private static final Logger log = LoggerFactory.getLogger(SchemaHandler.class);

    private final PacketDef def;
    private final SchemaManager manager;

    SchemaHandler(PacketDef def, SchemaManager manager) {
        this.def = Objects.requireNonNull(def, "def");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public Class<SchemaPacket> packetType() {
        return SchemaPacket.class;
    }

    @Override
    public boolean requiresHandshake() {
        return def.requiresHandshake();
    }

    @Override
    public String name() {
        return "schema:" + def.name();
    }

    @Override
    public void execute(NetworkSession session, SchemaPacket packet) {
        manager.recordHandled();
        if (log.isDebugEnabled()) {
            log.debug("session {} <- {}", session.id(), packet);
        }
        for (SchemaListener listener : manager.listeners()) {
            try {
                listener.onPacket(session, packet);
            } catch (Exception e) {
                log.error("schema listener {} failed on {}", listener, packet.name(), e);
            }
        }
        for (ResponseRule rule : manager.current().rulesFor(def.name())) {
            SchemaPacket response;
            try {
                response = manager.build(rule.send());
                for (Map.Entry<String, Object> template : rule.templates().entrySet()) {
                    response.set(template.getKey(), TemplateEvaluator.evaluate(template.getValue(), packet, session));
                }
            } catch (RuntimeException e) {
                log.error("response rule {} could not be built", rule, e);
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug("session {} -> {} (rule {})", session.id(), response, rule);
            }
            if (rule.closeAfter()) {
                session.sendAndClose(response, "schema rule " + rule.on() + " -> " + rule.send() + " (close)");
            } else {
                session.send(response);
            }
            manager.recordResponse();
        }
    }
}
