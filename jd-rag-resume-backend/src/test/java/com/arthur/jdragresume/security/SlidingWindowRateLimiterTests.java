package com.arthur.jdragresume.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTests {
    @Test
    void allowsUpToLimitThenRejectsUntilWindowExpires() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get);

        assertTrue(limiter.tryAcquire("login:1:arthur", 2, 1_000L));
        assertTrue(limiter.tryAcquire("login:1:arthur", 2, 1_000L));
        assertFalse(limiter.tryAcquire("login:1:arthur", 2, 1_000L));

        now.addAndGet(1_001L);
        assertTrue(limiter.tryAcquire("login:1:arthur", 2, 1_000L));
    }

    @Test
    void isolatesKeys() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire("a", 1, 60_000L));
        assertTrue(limiter.tryAcquire("b", 1, 60_000L));
        assertFalse(limiter.tryAcquire("a", 1, 60_000L));
    }

    @Test
    void expiredKeysAreRemovedWithoutBreakingActiveWindows() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get);

        for (int index = 0; index < 50; index++) {
            assertTrue(limiter.tryAcquire("stale-" + index, 1, 1_000L));
        }
        assertTrue(limiter.tryAcquire("active", 2, 5_000L));
        assertEquals(51, limiter.trackedKeyCount());

        now.addAndGet(1_001L);
        assertTrue(limiter.tryAcquire("fresh", 1, 1_000L));
        assertEquals(2, limiter.trackedKeyCount());
        assertTrue(limiter.tryAcquire("active", 2, 5_000L));
        assertFalse(limiter.tryAcquire("active", 2, 5_000L));
        assertFalse(limiter.tryAcquire("fresh", 1, 1_000L));
    }
}
