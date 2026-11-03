package core.network;

/** Observer for session lifecycle events. Callbacks run on the network thread; keep them short. */
public interface SessionListener {

    default void onOpen(NetworkSession session) {
    }

    default void onClose(NetworkSession session, String reason) {
    }
}
