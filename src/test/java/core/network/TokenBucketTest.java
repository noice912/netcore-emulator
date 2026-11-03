package core.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketTest {

    @Test
    void burstIsAdmittedThenThrottled() {
        TokenBucket bucket = new TokenBucket(1, 3);
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire(), "fourth packet exceeds the burst");
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(1000, 1);
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
        Thread.sleep(25);
        assertTrue(bucket.tryAcquire(), "25ms at 1000/s refills at least one token");
    }

    @Test
    void reconfigureClampsAccumulatedTokens() {
        TokenBucket bucket = new TokenBucket(10, 100);
        bucket.reconfigure(10, 2);
        assertEquals(2.0, bucket.capacity());
        assertTrue(bucket.available() <= 2.0);
        assertEquals(10.0, bucket.ratePerSecond());
    }

    @Test
    void unifiedTryAcquireReconfiguresInline() {
        TokenBucket bucket = new TokenBucket(1, 2);
        assertTrue(bucket.tryAcquire(1, 2));
        assertTrue(bucket.tryAcquire(1, 2));
        assertFalse(bucket.tryAcquire(1, 2), "burst of 2 is exhausted");
        // shrink the capacity on the same call: the new limits take effect and it stays throttled
        assertFalse(bucket.tryAcquire(1, 1));
        assertEquals(1.0, bucket.capacity());
        assertEquals(1.0, bucket.ratePerSecond());
    }

    @Test
    void unifiedTryAcquireRejectsNonPositiveLimits() {
        TokenBucket bucket = new TokenBucket(1, 1);
        assertThrows(IllegalArgumentException.class, () -> bucket.tryAcquire(0, 1));
        assertThrows(IllegalArgumentException.class, () -> bucket.tryAcquire(1, 0));
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(1, 0));
        TokenBucket bucket = new TokenBucket(1, 1);
        assertThrows(IllegalArgumentException.class, () -> bucket.reconfigure(-1, 1));
    }
}
