package core.schema;

import core.network.NetworkSession;

/**
 * Java-side hook for schema packets: called for every decoded {@link SchemaPacket} before the
 * declarative response rules run. Use it when a packet needs logic a template cannot express.
 */
@FunctionalInterface
public interface SchemaListener {

    void onPacket(NetworkSession session, SchemaPacket packet) throws Exception;
}
