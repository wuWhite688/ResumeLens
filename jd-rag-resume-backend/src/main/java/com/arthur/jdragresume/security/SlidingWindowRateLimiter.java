package com.arthur.jdragresume.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class SlidingWindowRateLimiter {
    private final ConcurrentHashMap<String, KeyWindow> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public SlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean tryAcquire(String key, int limit, long windowMs) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        long normalizedWindow = Math.max(1L, windowMs);
        evictExpired(now);
        while (true) {
            KeyWindow window = windows.computeIfAbsent(key, ignored -> new KeyWindow());
            synchronized (window) {
                if (windows.get(key) != window) {
                    continue;
                }
                window.windowMs = normalizedWindow;
                prune(window.timestamps, now - window.windowMs);
                if (window.timestamps.size() >= limit) {
                    return false;
                }
                window.timestamps.addLast(now);
                return true;
            }
        }
    }

    int trackedKeyCount() {
        return windows.size();
    }

    private void evictExpired(long now) {
        for (String key : List.copyOf(windows.keySet())) {
            KeyWindow window = windows.get(key);
            if (window == null) {
                continue;
            }
            synchronized (window) {
                if (window.windowMs <= 0L) {
                    continue;
                }
                prune(window.timestamps, now - window.windowMs);
                if (window.timestamps.isEmpty()) {
                    windows.remove(key, window);
                }
            }
        }
    }

    private static void prune(Deque<Long> timestamps, long cutoff) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.removeFirst();
        }
    }

    private static final class KeyWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long windowMs;
    }
}
