package com.rost.cruma.viewer.render.bgfx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.bgfx.BGFX;

public final class BgfxResourceRegistry implements AutoCloseable {
    private static final AtomicLong NEXT_GENERATION = new AtomicLong(1L);

    private final Thread apiThread;
    private final long generation;
    private final LinkedHashMap<ResourceKey, ArrayList<Lease>> live = new LinkedHashMap<>();

    private volatile boolean closed;
    private long liveBytes;
    private final long[] handlesByKind = new long[Kind.values().length];
    private final long[] bytesByKind = new long[Kind.values().length];
    private Snapshot cachedSnapshot;

    public BgfxResourceRegistry() {
        this(Thread.currentThread());
    }

    BgfxResourceRegistry(Thread apiThread) {
        this.apiThread = apiThread;
        this.generation = NEXT_GENERATION.getAndIncrement();
    }

    public static boolean isInvalid(short handle) {
        return handle == BGFX.BGFX_INVALID_HANDLE;
    }

    public enum Kind {
        VERTEX_BUFFER,
        INDEX_BUFFER,
        DYNAMIC_VERTEX_BUFFER,
        DYNAMIC_INDEX_BUFFER,
        INDIRECT_BUFFER,
        TEXTURE,
        FRAMEBUFFER,
        UNIFORM,
        SHADER,
        PROGRAM,
        VERTEX_LAYOUT
    }

    @FunctionalInterface
    public interface HandleDestroyer {
        void destroy(short handle);
    }

    public static final class Lease implements AutoCloseable {
        private final BgfxResourceRegistry owner;
        private final long generation;
        private final Kind kind;
        private final short rawHandle;
        private final String label;
        private final long estimatedBytes;
        private final HandleDestroyer destroyer;

        private volatile boolean closed;

        private Lease(
                BgfxResourceRegistry owner,
                long generation,
                Kind kind,
                short rawHandle,
                String label,
                long estimatedBytes,
                HandleDestroyer destroyer) {
            this.owner = owner;
            this.generation = generation;
            this.kind = kind;
            this.rawHandle = rawHandle;
            this.label = label;
            this.estimatedBytes = estimatedBytes;
            this.destroyer = destroyer;
        }

        public short getHandle() {
            if (closed || owner.closed || generation != owner.generation) {
                throw new IllegalStateException("stale or closed bgfx " + kind + " handle: " + label);
            }
            return rawHandle;
        }

        public long getGeneration() {
            return generation;
        }

        public Kind getKind() {
            return kind;
        }

        public String getLabel() {
            return label;
        }

        public long getEstimatedBytes() {
            return estimatedBytes;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            owner.release(this);
        }

        @Override
        public String toString() {
            return kind
                    + "["
                    + label
                    + ", handle="
                    + Short.toUnsignedInt(rawHandle)
                    + ", generation="
                    + generation
                    + "]";
        }
    }

    public record Snapshot(
            long generation,
            int liveHandles,
            long liveBytes,
            long[] handlesByKind,
            long[] bytesByKind,
            boolean closed) {
        public Snapshot {
            handlesByKind = handlesByKind.clone();
            bytesByKind = bytesByKind.clone();
        }

        @Override
        public long[] handlesByKind() {
            return handlesByKind.clone();
        }

        @Override
        public long[] bytesByKind() {
            return bytesByKind.clone();
        }

        public long count(Kind kind) {
            return handlesByKind[kind.ordinal()];
        }

        public long bytes(Kind kind) {
            return bytesByKind[kind.ordinal()];
        }
    }

    private record ResourceKey(Kind kind, int handle) {}

    public long getGeneration() {
        return generation;
    }

    public synchronized Lease register(
            Kind kind, short handle, String label, long estimatedBytes, HandleDestroyer destroyer) {
        return register(kind, handle, label, estimatedBytes, destroyer, false);
    }

    synchronized Lease registerReference(
            Kind kind, short handle, String label, long estimatedBytes, HandleDestroyer destroyer) {
        if (kind != Kind.SHADER && kind != Kind.PROGRAM && kind != Kind.UNIFORM) {
            throw new IllegalArgumentException("resource kind is not natively reference counted: " + kind);
        }
        return register(kind, handle, label, estimatedBytes, destroyer, true);
    }

    public synchronized Snapshot snapshot() {
        if (cachedSnapshot == null) {
            cachedSnapshot = new Snapshot(generation, live.size(), liveBytes, handlesByKind, bytesByKind, closed);
        }
        return cachedSnapshot;
    }

    public synchronized long getLiveBytes() {
        return liveBytes;
    }

    synchronized String describeLargestFamilies(Kind kind, int limit) {
        var counts = new LinkedHashMap<String, Integer>();
        for (var entry : live.entrySet()) {
            if (entry.getKey().kind() == kind) {
                counts.merge(entry.getValue().getFirst().label.replaceAll("[0-9]+", "#"), 1, Integer::sum);
            }
        }
        var largest = new ArrayList<>(counts.entrySet());
        largest.sort(Entry.<String, Integer>comparingByValue().reversed());
        return largest.subList(0, Math.min(limit, largest.size())).toString();
    }

    synchronized Map<String, Long> getBufferBytesByFamily() {
        var result = new TreeMap<String, Long>();
        for (var entry : live.entrySet()) {
            if (entry.getKey().kind().ordinal() > Kind.INDIRECT_BUFFER.ordinal()) {
                continue;
            }
            var lease = entry.getValue().getFirst();
            var separator = lease.label.indexOf('/');
            var family = separator < 0 ? lease.label : lease.label.substring(0, separator);
            result.merge(family, lease.estimatedBytes, Math::addExact);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        checkApiThread();
        if (closed) {
            return;
        }
        closed = true;
        cachedSnapshot = null;
        var reverse = new ArrayList<Lease>();
        for (var leases : live.values()) {
            reverse.addAll(leases);
        }
        RuntimeException firstFailure = null;
        for (var i = reverse.size() - 1; i >= 0; i--) {
            var lease = reverse.get(i);
            if (lease.closed) {
                continue;
            }
            try {
                release(lease);
            } catch (RuntimeException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        live.clear();
        liveBytes = 0L;
        cachedSnapshot = null;
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private Lease register(
            Kind kind, short handle, String label, long estimatedBytes, HandleDestroyer destroyer, boolean reference) {
        checkApiThread();
        checkOpen();
        if (kind == null || destroyer == null) {
            throw new IllegalArgumentException("resource kind and destroyer are required");
        }
        if (isInvalid(handle)) {
            var current = snapshot();
            throw new IllegalStateException("bgfx failed to create "
                    + kind
                    + " '"
                    + label
                    + "'. LiveHandles="
                    + current.liveHandles()
                    + ", liveMiB="
                    + current.liveBytes() / (1024L * 1024L)
                    + ", kindHandles="
                    + current.count(kind)
                    + ", kindMiB="
                    + current.bytes(kind) / (1024L * 1024L)
                    + ", largestFamilies="
                    + describeLargestFamilies(kind, 6));
        }
        if (estimatedBytes < 0L) {
            throw new IllegalArgumentException("estimated resource bytes cannot be negative");
        }
        var normalizedLabel = label == null || label.isBlank() ? kind.name().toLowerCase() : label;
        var key = new ResourceKey(kind, Short.toUnsignedInt(handle));
        var leases = live.get(key);
        if (leases != null && !reference) {
            throw new IllegalStateException("duplicate live bgfx handle " + key);
        }
        var lease = new Lease(this, generation, kind, handle, normalizedLabel, estimatedBytes, destroyer);
        if (leases == null) {
            leases = new ArrayList<>();
            live.put(key, leases);
            liveBytes = Math.addExact(liveBytes, estimatedBytes);
            handlesByKind[kind.ordinal()]++;
            bytesByKind[kind.ordinal()] = Math.addExact(bytesByKind[kind.ordinal()], estimatedBytes);
        } else if (leases.getFirst().estimatedBytes != estimatedBytes) {
            throw new IllegalArgumentException("shared resource size mismatch: " + key);
        }
        leases.add(lease);
        cachedSnapshot = null;
        return lease;
    }

    private synchronized void release(Lease lease) {
        checkApiThread();
        if (lease.closed) {
            return;
        }
        if (lease.owner != this || lease.generation != generation) {
            throw new IllegalStateException("resource belongs to a different bgfx device generation");
        }
        var key = new ResourceKey(lease.kind, Short.toUnsignedInt(lease.rawHandle));
        var leases = live.get(key);
        if (leases == null || !leases.remove(lease)) {
            lease.closed = true;
            throw new IllegalStateException("resource is not registered in its bgfx device generation: " + lease);
        }
        lease.closed = true;
        if (leases.isEmpty()) {
            live.remove(key);
            liveBytes -= lease.estimatedBytes;
            handlesByKind[lease.kind.ordinal()]--;
            bytesByKind[lease.kind.ordinal()] -= lease.estimatedBytes;
        }
        cachedSnapshot = null;
        lease.destroyer.destroy(lease.rawHandle);
    }

    private void checkApiThread() {
        if (Thread.currentThread() != apiThread) {
            throw new IllegalStateException("bgfx resource lifetime must be changed on the device API thread");
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx resource registry is closed");
        }
    }
}
