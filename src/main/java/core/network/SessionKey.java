package core.network;

import java.net.InetSocketAddress;

/** Identifies a remote endpoint per protocol; what the session pool is keyed by. */
record SessionKey(Protocol protocol, InetSocketAddress remote) {
}
