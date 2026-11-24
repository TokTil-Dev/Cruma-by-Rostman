package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_alloc;
import static org.lwjgl.bgfx.BGFX.bgfx_copy;

import java.lang.System.Logger.Level;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.system.MemoryUtil;

final class BgfxMemoryCopy {
    private static final boolean DEBUG_COPY_TIMINGS = Boolean.getBoolean("cruma.viewer.bgfx.debug_copy_timings");
    private static final boolean DEBUG_SLOW_COPIES = Boolean.getBoolean("cruma.viewer.bgfx.debug_slow_copies");
    private static final ThreadMXBean THREAD_TIMINGS = DEBUG_COPY_TIMINGS ? ManagementFactory.getThreadMXBean() : null;
    private static final LongAdder COPY_CALLS = new LongAdder();
    private static final LongAdder COPY_BYTES = new LongAdder();
    private static final LongAdder COPY_NANOS = new LongAdder();

    private BgfxMemoryCopy() {}

    static BGFXMemory copy(ByteBuffer source) {
        if (source == null || !source.hasRemaining()) {
            throw new IllegalArgumentException("bgfx upload data cannot be null or empty");
        }
        var bytes = source.remaining();
        var started = System.nanoTime();
        if (source.isDirect()) {
            return record(copyDirect(source), bytes, started);
        }
        var view = source.slice();
        var direct = MemoryUtil.memAlloc(bytes);
        try {
            direct.put(view).flip();
            return record(copyDirect(direct), bytes, started);
        } finally {
            MemoryUtil.memFree(direct);
        }
    }

    static Snapshot snapshot() {
        return new Snapshot(COPY_CALLS.sum(), COPY_BYTES.sum(), COPY_NANOS.sum());
    }

    record Snapshot(long calls, long bytes, long nanos) {
        Snapshot {
            if (calls < 0L || bytes < 0L || nanos < 0L) {
                throw new IllegalArgumentException("bgfx copy counters cannot be negative");
            }
        }

        Snapshot since(Snapshot baseline) {
            if (baseline == null) {
                throw new IllegalArgumentException("bgfx copy baseline is required");
            }
            return new Snapshot(
                    Math.max(0L, calls - baseline.calls),
                    Math.max(0L, bytes - baseline.bytes),
                    Math.max(0L, nanos - baseline.nanos));
        }
    }

    private static BGFXMemory copyDirect(ByteBuffer source) {
        if (!DEBUG_COPY_TIMINGS) {
            return bgfx_copy(source);
        }
        var bytes = source.remaining();
        var cpuEnabled = THREAD_TIMINGS.isThreadCpuTimeEnabled();
        var cpuStarted = cpuEnabled ? THREAD_TIMINGS.getCurrentThreadCpuTime() : -1L;
        var started = System.nanoTime();
        var memory = bgfx_alloc(bytes);
        var allocated = System.nanoTime();
        var cpuAllocated = cpuEnabled ? THREAD_TIMINGS.getCurrentThreadCpuTime() : -1L;
        MemoryUtil.memCopy(
                MemoryUtil.memAddress(source), MemoryUtil.memGetAddress(memory.address() + BGFXMemory.DATA), bytes);
        var copied = System.nanoTime();
        if (copied - started > 5_000_000L) {
            var cpuCopied = cpuEnabled ? THREAD_TIMINGS.getCurrentThreadCpuTime() : -1L;
            System.getLogger(BgfxMemoryCopy.class.getName())
                    .log(
                            Level.INFO,
                            "Copy timing: bytes="
                                    + bytes
                                    + " allocateMs="
                                    + (allocated - started) / 1_000_000.0
                                    + " copyMs="
                                    + (copied - allocated) / 1_000_000.0
                                    + " allocateCpuMs="
                                    + (cpuEnabled ? (cpuAllocated - cpuStarted) / 1_000_000.0 : -1.0)
                                    + " copyCpuMs="
                                    + (cpuEnabled ? (cpuCopied - cpuAllocated) / 1_000_000.0 : -1.0)
                                    + " completedNs="
                                    + copied);
        }
        return memory;
    }

    private static BGFXMemory record(BGFXMemory memory, int bytes, long started) {
        var completed = System.nanoTime();
        var elapsed = Math.max(0L, completed - started);
        COPY_CALLS.increment();
        COPY_BYTES.add(bytes);
        COPY_NANOS.add(elapsed);
        if (DEBUG_SLOW_COPIES && elapsed > 5_000_000L) {
            System.getLogger(BgfxMemoryCopy.class.getName())
                    .log(
                            Level.INFO,
                            "Slow copy: bytes="
                                    + bytes
                                    + " elapsedMs="
                                    + elapsed / 1_000_000.0
                                    + " completedNs="
                                    + completed);
        }
        return memory;
    }
}
