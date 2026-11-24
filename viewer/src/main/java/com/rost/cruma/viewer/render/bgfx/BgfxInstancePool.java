package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_set_instance_data_from_dynamic_vertex_buffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BgfxInstancePool implements AutoCloseable {
    static final int REUSE_DELAY_FRAMES = BgfxGeometryPool.REUSE_DELAY_FRAMES;

    private static final int DEFAULT_BYTES = 32 * 1024 * 1024;

    private final BgfxDevice device;
    private final List<Chunk> chunks = new ArrayList<>();
    private int nextChunkId;
    private long frame;
    private long uploadSubmissions;
    private long uploadedBytes;
    private boolean closed;

    BgfxInstancePool(BgfxDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("instance pool requires a bgfx device");
        }
        this.device = device;
    }

    Slice allocate(String label, ByteBuffer data) {
        requireOpen();
        var stride = BgfxSceneVertexLayouts.INSTANCE.getStride();
        if (data == null || !data.hasRemaining() || data.remaining() % stride != 0) {
            throw new IllegalArgumentException("invalid packed scene instance payload");
        }
        var count = data.remaining() / stride;
        Chunk chunk = null;
        for (var candidate : chunks) {
            if (candidate.findAllocation(count) != null) {
                chunk = candidate;
                break;
            }
        }
        var created = false;
        if (chunk == null) {
            chunk = createChunk(count);
            chunks.add(chunk);
            created = true;
        }
        try {
            return chunk.allocate(label, data, count);
        } catch (RuntimeException | Error failure) {
            if (created && chunk.liveSlices == 0) {
                chunks.remove(chunk);
                closeAfterFailure(chunk, failure);
            }
            throw failure;
        }
    }

    Snapshot snapshot() {
        var slices = 0;
        long capacityBytes = 0L;
        long liveBytes = 0L;
        for (var chunk : chunks) {
            slices += chunk.liveSlices;
            capacityBytes = Math.addExact(capacityBytes, chunk.buffer.getEstimatedBytes());
            liveBytes = Math.addExact(liveBytes, chunk.liveBytes);
        }
        return new Snapshot(
                chunks.size(), chunks.size(), slices, capacityBytes, liveBytes, uploadSubmissions, uploadedBytes);
    }

    void advanceFrame() {
        requireOpen();
        frame = Math.incrementExact(frame);
        for (var index = chunks.size() - 1; index >= 0; index--) {
            var chunk = chunks.get(index);
            chunk.reclaim(frame);
            if (chunk.liveSlices == 0 && chunk.retired.isEmpty()) {
                chunks.remove(index);
                chunk.close();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = chunks.size() - 1; index >= 0; index--) {
            try {
                chunks.get(index).close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        chunks.clear();
        if (failure != null) {
            throw failure;
        }
    }

    record Snapshot(
            int chunks,
            int nativeHandles,
            int liveSlices,
            long capacityBytes,
            long liveBytes,
            long uploadSubmissions,
            long uploadedBytes) {
        Snapshot since(Snapshot baseline) {
            if (baseline == null) {
                throw new IllegalArgumentException("instance upload baseline is required");
            }
            return new Snapshot(
                    chunks,
                    nativeHandles,
                    liveSlices,
                    capacityBytes,
                    liveBytes,
                    Math.max(0L, uploadSubmissions - baseline.uploadSubmissions),
                    Math.max(0L, uploadedBytes - baseline.uploadedBytes));
        }
    }

    final class Slice implements AutoCloseable {
        private final Chunk chunk;
        private final String label;
        private final int offset;
        private final int count;
        private final long estimatedBytes;
        private boolean closed;

        private Slice(Chunk chunk, String label, int offset, int count) {
            this.chunk = chunk;
            this.label = label == null || label.isBlank() ? "instances" : label;
            this.offset = offset;
            this.count = count;
            this.estimatedBytes = Math.multiplyExact((long) count, BgfxSceneVertexLayouts.INSTANCE.getStride());
        }

        void bind() {
            bind(0, count);
        }

        void bind(int first, int instances) {
            Objects.checkFromIndexSize(first, instances, count);
            if (closed || chunk.closed || BgfxInstancePool.this.closed) {
                throw new IllegalStateException("instance slice is closed: " + label);
            }
            bgfx_set_instance_data_from_dynamic_vertex_buffer(chunk.buffer.getHandle(), offset + first, instances);
        }

        void update(ByteBuffer data) {
            if (closed || chunk.closed || BgfxInstancePool.this.closed) {
                throw new IllegalStateException("instance slice is closed: " + label);
            }
            if (data.remaining() != estimatedBytes) {
                throw new IllegalArgumentException("instance update must preserve slice size");
            }
            chunk.buffer.update(offset, data);
            uploadSubmissions = Math.addExact(uploadSubmissions, 1L);
            uploadedBytes = Math.addExact(uploadedBytes, data.remaining());
        }

        void update(int firstInstance, ByteBuffer data) {
            if (closed || chunk.closed || BgfxInstancePool.this.closed) {
                throw new IllegalStateException("instance slice is closed: " + label);
            }
            var stride = BgfxSceneVertexLayouts.INSTANCE.getStride();
            if (data.remaining() % stride != 0) {
                throw new IllegalArgumentException("instance update must hold whole instances");
            }
            Objects.checkFromIndexSize(firstInstance, data.remaining() / stride, count);
            chunk.buffer.update(offset + firstInstance, data);
            uploadSubmissions = Math.addExact(uploadSubmissions, 1L);
            uploadedBytes = Math.addExact(uploadedBytes, data.remaining());
        }

        long getEstimatedBytes() {
            return estimatedBytes;
        }

        @Override
        public void close() {
            release(this);
        }
    }

    private final class Chunk implements AutoCloseable {
        private final BgfxBuffer buffer;
        private final int capacity;
        private final List<Range> free = new ArrayList<>();
        private final List<RetiredRange> retired = new ArrayList<>();
        private int highWater;
        private int liveSlices;
        private long liveBytes;
        private boolean closed;

        private Chunk(BgfxBuffer buffer, int capacity) {
            this.buffer = buffer;
            this.capacity = capacity;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            buffer.close();
            free.clear();
            retired.clear();
        }

        private Allocation findAllocation(int required) {
            for (var index = 0; index < free.size(); index++) {
                var range = free.get(index);
                if (range.count >= required) {
                    return new Allocation(range.offset, true, index);
                }
            }
            return (long) highWater + required <= capacity ? new Allocation(highWater, false, -1) : null;
        }

        private Slice allocate(String label, ByteBuffer data, int count) {
            var allocation = findAllocation(count);
            if (allocation == null) {
                throw new IllegalStateException("instance chunk allocation no longer fits");
            }
            var bytes = data.remaining();
            buffer.update(allocation.offset, data);
            uploadSubmissions = Math.addExact(uploadSubmissions, 1L);
            uploadedBytes = Math.addExact(uploadedBytes, bytes);
            if (allocation.fromFree) {
                var range = free.get(allocation.freeIndex);
                if (range.count == count) {
                    free.remove(allocation.freeIndex);
                } else {
                    free.set(allocation.freeIndex, new Range(Math.addExact(range.offset, count), range.count - count));
                }
            } else {
                highWater = Math.addExact(allocation.offset, count);
            }
            var slice = new Slice(this, label, allocation.offset, count);
            liveSlices++;
            liveBytes = Math.addExact(liveBytes, slice.estimatedBytes);
            return slice;
        }

        private void reclaim(long currentFrame) {
            for (var index = retired.size() - 1; index >= 0; index--) {
                var range = retired.get(index);
                if (!BgfxGeometryPool.isReusableAtFrame(range.frame, currentFrame)) {
                    continue;
                }
                releaseRange(free, range.offset, range.count);
                retired.remove(index);
            }
            highWater = trimTail(free, highWater);
        }
    }

    private Chunk createChunk(int required) {
        var defaultCapacity = Math.max(1, DEFAULT_BYTES / BgfxSceneVertexLayouts.INSTANCE.getStride());
        var capacity = computeCapacity(required, defaultCapacity);
        var id = nextChunkId++;
        return new Chunk(
                BgfxBuffer.dynamicVertex(
                        device.getResources(),
                        "instance-pool/chunk-" + id,
                        capacity,
                        BgfxSceneVertexLayouts.INSTANCE,
                        BgfxBuffer.gpuResident(device.getCapabilities(), 0)),
                capacity);
    }

    private void release(Slice slice) {
        if (slice.closed) {
            return;
        }
        var chunk = slice.chunk;
        if (chunk.closed || chunk.liveSlices < 1) {
            throw new IllegalStateException("instance slice belongs to a closed pool chunk");
        }
        slice.closed = true;
        chunk.liveSlices--;
        chunk.liveBytes -= slice.estimatedBytes;
        chunk.retired.add(new RetiredRange(slice.offset, slice.count, frame));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("instance pool is closed");
        }
    }

    private static int computeCapacity(int required, int defaultCapacity) {
        if (required <= defaultCapacity) {
            return defaultCapacity;
        }
        var highest = Integer.highestOneBit(required - 1);
        return highest <= 0 || highest > (Integer.MAX_VALUE >>> 1) ? required : highest << 1;
    }

    private static void releaseRange(List<Range> free, int offset, int count) {
        var insert = 0;
        while (insert < free.size() && free.get(insert).offset < offset) {
            insert++;
        }
        free.add(insert, new Range(offset, count));
        for (var index = Math.max(0, insert - 1); index + 1 < free.size(); ) {
            var left = free.get(index);
            var right = free.get(index + 1);
            if ((long) left.offset + left.count < right.offset) {
                index++;
                continue;
            }
            var end = Math.max(Math.addExact(left.offset, left.count), Math.addExact(right.offset, right.count));
            free.set(index, new Range(left.offset, end - left.offset));
            free.remove(index + 1);
        }
    }

    private static int trimTail(List<Range> free, int highWater) {
        while (!free.isEmpty()) {
            var last = free.getLast();
            if ((long) last.offset + last.count != highWater) {
                break;
            }
            highWater = last.offset;
            free.removeLast();
        }
        return highWater;
    }

    private static void closeAfterFailure(AutoCloseable resource, Throwable failure) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private record Range(int offset, int count) {}

    private record RetiredRange(int offset, int count, long frame) {}

    private record Allocation(int offset, boolean fromFree, int freeIndex) {}
}
