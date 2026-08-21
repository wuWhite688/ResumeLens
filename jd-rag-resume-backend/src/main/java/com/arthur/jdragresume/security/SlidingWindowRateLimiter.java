package com.arthur.jdragresume.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

@Component
public class SlidingWindowRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    /**
     * 全量清理的最小间隔。清理只为回收内存，晚一秒回收没有可观察的影响，
     * 而限流窗口本身是分钟级的 —— 同一秒内反复全量扫描纯属浪费。
     */
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    /**
     * 跟踪键的数量上限。登录限流键包含调用方可控的 username，
     * 单个 IP 换用户名即可在一个窗口内造出任意多的键，必须有硬上限兜底。
     */
    private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

    private static final long CAPACITY_WARN_INTERVAL_MS = 60_000L;

    private final ConcurrentHashMap<String, KeyWindow> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final int maxTrackedKeys;
    private final AtomicLong lastSweepAt = new AtomicLong(0L);
    private final AtomicLong lastCapacityWarnAt = new AtomicLong(0L);
    private final AtomicLong sweepCount = new AtomicLong(0L);

    public SlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(LongSupplier clock) {
        this(clock, DEFAULT_MAX_TRACKED_KEYS);
    }

    SlidingWindowRateLimiter(LongSupplier clock, int maxTrackedKeys) {
        this.clock = clock;
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
    }

    public boolean tryAcquire(String key, int limit, long windowMs) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        long normalizedWindow = Math.max(1L, windowMs);
        sweepIfDue(now);
        if (!hasCapacityFor(key, now)) {
            return false;
        }
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

    long sweepCount() {
        return sweepCount.get();
    }

    /**
     * 全量清理原本挂在每一次 tryAcquire 上，键数一多就是每请求 O(n)：
     * 实测 22 / 2002 / 20002 个键对应 1.4µs / 62µs / 712µs，随键数线性劣化。
     * 改为按时间摊销后，单次调用退化为 O(1)，全量扫描每秒至多一次。
     * CAS 抢占清理权，避免并发请求重复扫描。
     */
    private void sweepIfDue(long now) {
        long last = lastSweepAt.get();
        if (now - last >= SWEEP_INTERVAL_MS && lastSweepAt.compareAndSet(last, now)) {
            sweep(now);
        }
    }

    /**
     * 键数触顶时先强制清理一次；若清理后仍然触顶，说明这些键都还在窗口内，
     * 此时只拒绝“新建键”，已在跟踪中的键不受影响 —— 让攻击者刷不出新配额，
     * 同时不至于把正在正常重试的调用方一起拒掉。
     */
    private boolean hasCapacityFor(String key, long now) {
        if (windows.containsKey(key) || windows.size() < maxTrackedKeys) {
            return true;
        }
        lastSweepAt.set(now);
        sweep(now);
        if (windows.containsKey(key) || windows.size() < maxTrackedKeys) {
            return true;
        }
        warnCapacityReached(now);
        return false;
    }

    private void warnCapacityReached(long now) {
        long lastWarn = lastCapacityWarnAt.get();
        if (now - lastWarn >= CAPACITY_WARN_INTERVAL_MS && lastCapacityWarnAt.compareAndSet(lastWarn, now)) {
            log.warn(
                    "Rate limiter is tracking {} keys (cap {}); rejecting new keys until windows expire",
                    windows.size(),
                    maxTrackedKeys
            );
        }
    }

    private void sweep(long now) {
        sweepCount.incrementAndGet();
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
