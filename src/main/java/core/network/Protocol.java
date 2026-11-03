package core.network;

/** Transport a session rides on. */
public enum Protocol {
    /** Stream listener ("dispatch"): one connection per client, ordered delivery. */
    TCP("dispatch"),
    /** Datagram listener ("gateway"): sessions are keyed by the sender's address and port. */
    UDP("gateway");

    private final String role;

    Protocol(String role) {
        this.role = role;
    }

    /** The listener role this protocol serves in the engine. */
    public String role() {
        return role;
    }
}
