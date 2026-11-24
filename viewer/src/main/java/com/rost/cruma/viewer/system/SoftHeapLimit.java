package com.rost.cruma.viewer.system;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SoftHeapLimit {
    private static final String OPTION = "SoftMaxHeapSize";
    private static final long SMALLEST_LIMIT = 2L << 30;
    private static final long SMALLEST_MARGIN = 1L << 30;
    private static final long STEP_MILLIS = 500L;
    private static final int WINDOW_STEPS = 16;
    private static final long CHANGE_BYTES = 128L << 20;
    private static final Logger log = LogManager.getLogger(SoftHeapLimit.class);
    private static final AtomicBoolean started = new AtomicBoolean();
    private static final Object lock = new Object();
    private static boolean collectionWanted;

    private SoftHeapLimit() {}

    public static void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        var pools = collectedPools();
        if (pools.length == 0) {
            return;
        }
        HotSpotDiagnosticMXBean options;
        try {
            options = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        } catch (RuntimeException | LinkageError unavailable) {
            log.info("The runtime cannot change the heap limit: {}", unavailable.toString());
            return;
        }
        Thread.ofPlatform().name("cruma-heap-limit").daemon().start(() -> follow(options, pools));
    }

    public static void collectSoon() {
        synchronized (lock) {
            collectionWanted = true;
            lock.notifyAll();
        }
    }

    static long limitFor(long live, long maximum) {
        var wanted = live + Math.max(SMALLEST_MARGIN, live / 4);
        return Math.min(maximum, Math.max(SMALLEST_LIMIT, wanted));
    }

    static long smallest(long[] values, int count) {
        var result = Long.MAX_VALUE;
        for (var index = 0; index < count; index++) {
            result = Math.min(result, values[index]);
        }
        return result;
    }

    private static void follow(HotSpotDiagnosticMXBean options, MemoryPoolMXBean[] pools) {
        var maximum = Runtime.getRuntime().maxMemory();
        var recent = new long[WINDOW_STEPS];
        var step = 0;
        var current = -1L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                recent[step++ % recent.length] = liveBytes(pools);
                var limit = limitFor(smallest(recent, Math.min(step, recent.length)), maximum);
                if (Math.abs(limit - current) >= CHANGE_BYTES) {
                    options.setVMOption(OPTION, Long.toString(limit));
                    current = limit;
                }
                if (waitForCollection()) {
                    java.lang.System.gc();
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException refused) {
            log.info("The heap limit stays as it is: {}", refused.toString());
        }
    }

    private static boolean waitForCollection() throws InterruptedException {
        synchronized (lock) {
            if (!collectionWanted) {
                lock.wait(STEP_MILLIS);
            }
            var wanted = collectionWanted;
            collectionWanted = false;
            return wanted;
        }
    }

    private static long liveBytes(MemoryPoolMXBean[] pools) {
        var live = 0L;
        for (var pool : pools) {
            var usage = pool.getCollectionUsage();
            if (usage != null) {
                live += usage.getUsed();
            }
        }
        return live;
    }

    private static MemoryPoolMXBean[] collectedPools() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().startsWith("ZGC ") && pool.getCollectionUsage() != null)
                .toArray(MemoryPoolMXBean[]::new);
    }
}
