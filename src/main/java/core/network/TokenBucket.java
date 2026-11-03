package core.network;

/**
 * Thread-safe token bucket: tokens refill continuously at {@code ratePerSecond} up to
 * {@code capacity}; each admitted event costs one token. Cheap enough to sit on the packet path.
 */
public final class TokenBucket {

    private double tokens;
    private long lastRefillNanos;
    private double ratePerSecond;
    private double capacity;

    public TokenBucket(double ratePerSecond, double capacity) {
        check(ratePerSecond, capacity);
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    private static void check(double ratePerSecond, double capacity) {
        if (ratePerSecond <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("rate and capacity must be positive (rate=" + ratePerSecond
                    + ", capacity=" + capacity + ")");
        }
    }

    /** Change the limits in place; already-accumulated tokens are clamped to the new capacity. */
    public synchronized void reconfigure(double ratePerSecond, double capacity) {
        check(ratePerSecond, capacity);
        refill();
        this.ratePerSecond = ratePerSecond;
        this.capacity = capacity;
        if (tokens > capacity) {
            tokens = capacity;
        }
    }

    /** Take one token if available. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    public synchronized double available() {
        refill();
        return tokens;
    }

    public synchronized double ratePerSecond() {
        return ratePerSecond;
    }

    public synchronized double capacity() {
        return capacity;
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastRefillNanos;
        if (elapsedNanos > 0) {
            tokens = Math.min(capacity, tokens + elapsedNanos / 1_000_000_000.0 * ratePerSecond);
            lastRefillNanos = now;
        }
    }
}
