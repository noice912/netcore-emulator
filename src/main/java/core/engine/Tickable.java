package core.engine;

/**
 * Work that runs once per simulation tick on the engine's tick thread. Implementations must
 * return quickly; a tick that takes longer than the tick period is counted as an overrun.
 */
public interface Tickable {

    /**
     * @param tickNumber monotonically increasing tick counter, starting at 1
     * @param deltaNanos wall-clock nanoseconds since the previous tick started
     */
    void tick(long tickNumber, long deltaNanos) throws Exception;

    default String name() {
        return getClass().getSimpleName();
    }
}
