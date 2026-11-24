package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_READ_WRITE;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_dynamic_vertex_buffer_with_layout;

import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Kind;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class BgfxGeometryPool implements AutoCloseable {
    static final int REUSE_DELAY_FRAMES = 4;

    private static final int DEFAULT_VERTEX_BYTES = 128 * 1024 * 1024;
    private static final int DEFAULT_INDEX_BYTES = 32 * 1024 * 1024;
    private static final int TERRAIN_VERTEX_BYTES = 64 * 1024 * 1024;

    private final BgfxDevice device;
    private final EnumMap<Kind, List<Chunk>> chunks = new EnumMap<>(Kind.class);
    private int nextChunkId;
    private long frame;
    private long stagedIndexBytes;
    private long submittedIndexBytes;
    private long indexSubmissions;
    private @Nullable BgfxWireframe lineBuilder;
    private boolean closed;

    BgfxGeometryPool(BgfxDevice device) {
        if (device == null) {
            throw new IllegalArgumentException("geometry pool requires a bgfx device");
        }
        this.device = device;
        for (var kind : Kind.values()) {
            chunks.put(kind, new ArrayList<>());
        }
    }

    static boolean isReusableAtFrame(long retiredFrame, long currentFrame) {
        return currentFrame >= retiredFrame && currentFrame - retiredFrame >= REUSE_DELAY_FRAMES;
    }

    Slice allocate(
            Kind kind, String label, ByteBuffer vertexData, int vertexCount, ByteBuffer indexData, int indexCount) {
        requireOpen();
        if (kind == null || vertexData == null || indexData == null || vertexCount < 1 || indexCount < 1) {
            throw new IllegalArgumentException("geometry pool requires non-empty indexed geometry");
        }
        var layout = layout(kind);
        if (vertexData.remaining() != Math.multiplyExact(vertexCount, layout.getStride())
                || indexData.remaining() != Math.multiplyExact(indexCount, Integer.BYTES)) {
            throw new IllegalArgumentException("geometry payload size does not match its element counts");
        }
        var candidates = chunks.get(kind);
        Chunk chunk = null;
        for (var candidate : candidates) {
            if (candidate.canAllocate(vertexCount, indexCount)) {
                chunk = candidate;
                break;
            }
        }
        var created = false;
        if (chunk == null) {
            chunk = createChunk(kind, vertexCount, indexCount);
            candidates.add(chunk);
            created = true;
        }
        try {
            return chunk.allocate(label, vertexData, vertexCount, indexData, indexCount);
        } catch (RuntimeException | Error failure) {
            if (created && chunk.liveSlices == 0) {
                candidates.remove(chunk);
                closeAfterFailure(chunk, failure);
            }
            throw failure;
        }
    }

    void enableLines(BgfxWireframe builder) {
        requireOpen();
        if (lineBuilder != null) {
            return;
        }
        lineBuilder = builder;
        for (var family : chunks.values()) {
            for (var chunk : family) {
                chunk.buildLines(0, chunk.indexHighWater);
            }
        }
    }

    Snapshot snapshot() {
        var chunkCount = 0;
        var slices = 0;
        long capacityBytes = 0L;
        long liveBytes = 0L;
        for (var family : chunks.values()) {
            chunkCount += family.size();
            for (var chunk : family) {
                slices += chunk.liveSlices;
                capacityBytes = Math.addExact(capacityBytes, chunk.capacityBytes());
                liveBytes = Math.addExact(liveBytes, chunk.liveBytes);
            }
        }
        return new Snapshot(
                chunkCount,
                Math.multiplyExact(chunkCount, 2),
                slices,
                capacityBytes,
                liveBytes,
                stagedIndexBytes,
                submittedIndexBytes,
                indexSubmissions);
    }

    void advanceFrame() {
        requireOpen();
        frame = Math.incrementExact(frame);
        for (var family : chunks.values()) {
            for (var index = family.size() - 1; index >= 0; index--) {
                var chunk = family.get(index);
                chunk.reclaim(frame);
                if (chunk.liveSlices == 0 && chunk.retired.isEmpty()) {
                    family.remove(index);
                    chunk.close();
                }
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
        for (var family : chunks.values()) {
            for (var index = family.size() - 1; index >= 0; index--) {
                try {
                    family.get(index).close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            family.clear();
        }
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
            long stagedIndexBytes,
            long submittedIndexBytes,
            long indexSubmissions) {
        Snapshot since(Snapshot baseline) {
            if (baseline == null) {
                throw new IllegalArgumentException("geometry upload baseline is required");
            }
            return new Snapshot(
                    chunks,
                    nativeHandles,
                    liveSlices,
                    capacityBytes,
                    liveBytes,
                    Math.max(0L, stagedIndexBytes - baseline.stagedIndexBytes),
                    Math.max(0L, submittedIndexBytes - baseline.submittedIndexBytes),
                    Math.max(0L, indexSubmissions - baseline.indexSubmissions));
        }
    }

    final class Slice implements AutoCloseable {
        private final Chunk chunk;
        private final String label;
        private final int vertexOffset;
        private final int vertexCount;
        private final int indexOffset;
        private final int indexCount;
        private final long estimatedBytes;
        private boolean closed;

        private Slice(Chunk chunk, String label, int vertexOffset, int vertexCount, int indexOffset, int indexCount) {
            this.chunk = chunk;
            this.label = label == null || label.isBlank() ? "geometry" : label;
            this.vertexOffset = vertexOffset;
            this.vertexCount = vertexCount;
            this.indexOffset = indexOffset;
            this.indexCount = indexCount;
            this.estimatedBytes = Math.addExact(
                    Math.multiplyExact((long) vertexCount, chunk.vertices.getStride()),
                    Math.multiplyExact((long) indexCount, Integer.BYTES));
        }

        void bind(int firstIndex, int count) {
            if (closed || chunk.closed || BgfxGeometryPool.this.closed) {
                throw new IllegalStateException("geometry slice is closed: " + label);
            }
            if (firstIndex < 0 || count < 0 || (long) firstIndex + count > indexCount) {
                throw new IllegalArgumentException("draw range is outside geometry slice: " + label);
            }
            bgfx_set_dynamic_vertex_buffer(0, chunk.vertices.getHandle(), vertexOffset, vertexCount);
            bgfx_set_dynamic_index_buffer(chunk.indices.getHandle(), Math.addExact(indexOffset, firstIndex), count);
        }

        boolean bindLines(int firstIndex, int count) {
            requireLive();
            if (firstIndex < 0 || count < 0 || (long) firstIndex + count > indexCount) {
                throw new IllegalArgumentException("draw range is outside geometry slice: " + label);
            }
            var first = Math.addExact(indexOffset, firstIndex);
            if (chunk.lines == null || first % 3 != 0) {
                return false;
            }
            bgfx_set_dynamic_vertex_buffer(0, chunk.vertices.getHandle(), vertexOffset, vertexCount);
            bgfx_set_dynamic_index_buffer(
                    chunk.lines.getHandle(), Math.multiplyExact(first, 2), Math.multiplyExact(count, 2));
            return true;
        }

        void updateVertices(ByteBuffer data) {
            requireLive();
            if ((long) data.remaining() != Math.multiplyExact((long) vertexCount, chunk.vertices.getStride())) {
                throw new IllegalArgumentException("vertex update size differs from geometry slice: " + label);
            }
            chunk.vertices.update(vertexOffset, data);
        }

        void updateVertices(int first, ByteBuffer data) {
            requireLive();
            var stride = chunk.vertices.getStride();
            if (first < 0 || data.remaining() % stride != 0 || (long) first + data.remaining() / stride > vertexCount) {
                throw new IllegalArgumentException("vertex update is outside geometry slice: " + label);
            }
            chunk.vertices.update(Math.addExact(vertexOffset, first), data);
        }

        void bindVertices(int stream, BgfxVertexLayout layout) {
            requireLive();
            bgfx_set_dynamic_vertex_buffer_with_layout(
                    stream, chunk.vertices.getHandle(), vertexOffset, vertexCount, layout.getHandle());
        }

        IndirectBinding getIndirectBinding() {
            requireLive();
            return chunk.indirectBinding;
        }

        int getIndirectVertexOffset() {
            requireLive();
            return vertexOffset;
        }

        int getIndirectIndexOffset() {
            requireLive();
            return indexOffset;
        }

        long getEstimatedBytes() {
            return estimatedBytes;
        }

        @Override
        public void close() {
            release(this);
        }

        private void requireLive() {
            if (closed || chunk.closed || BgfxGeometryPool.this.closed) {
                throw new IllegalStateException("geometry slice is closed: " + label);
            }
        }
    }

    final class IndirectBinding {
        private final Chunk chunk;

        private IndirectBinding(Chunk chunk) {
            this.chunk = chunk;
        }

        Kind getKind() {
            return chunk.kind;
        }

        void bind() {
            if (closed || chunk.closed || chunk.owner != BgfxGeometryPool.this) {
                throw new IllegalStateException("indirect geometry chunk is closed");
            }
            bgfx_set_dynamic_vertex_buffer(0, chunk.vertices.getHandle(), 0, chunk.vertexCapacity);
            bgfx_set_dynamic_index_buffer(chunk.indices.getHandle(), 0, chunk.indexCapacity);
        }
    }

    private final class Chunk implements AutoCloseable {
        private final BgfxGeometryPool owner = BgfxGeometryPool.this;
        private final Kind kind;
        private final String label;
        private final BgfxBuffer vertices;
        private final BgfxBuffer indices;
        private final int vertexCapacity;
        private final int indexCapacity;
        private final IndirectBinding indirectBinding;
        private final List<Range> freeVertices = new ArrayList<>();
        private final List<Range> freeIndices = new ArrayList<>();
        private final List<RetiredRange> retired = new ArrayList<>();
        private int vertexHighWater;
        private int indexHighWater;
        private int liveSlices;
        private long liveBytes;
        private @Nullable BgfxBuffer lines;
        private boolean closed;

        private Chunk(
                Kind kind,
                String label,
                BgfxBuffer vertices,
                BgfxBuffer indices,
                int vertexCapacity,
                int indexCapacity) {
            this.kind = kind;
            this.label = label;
            this.vertices = vertices;
            this.indices = indices;
            this.vertexCapacity = vertexCapacity;
            this.indexCapacity = indexCapacity;
            this.indirectBinding = new IndirectBinding(this);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            if (lines != null) {
                try {
                    lines.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            try {
                indices.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                vertices.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            freeVertices.clear();
            freeIndices.clear();
            retired.clear();
            if (failure != null) {
                throw failure;
            }
        }

        private boolean canAllocate(int vertices, int indices) {
            return findAllocation(freeVertices, vertexHighWater, vertexCapacity, vertices) != null
                    && findAllocation(freeIndices, indexHighWater, indexCapacity, indices) != null;
        }

        private Slice allocate(
                String label, ByteBuffer vertexData, int vertexCount, ByteBuffer indexData, int indexCount) {
            var vertexPlan = findAllocation(freeVertices, vertexHighWater, vertexCapacity, vertexCount);
            var indexPlan = findAllocation(freeIndices, indexHighWater, indexCapacity, indexCount);
            if (vertexPlan == null || indexPlan == null) {
                throw new IllegalStateException("geometry chunk allocation no longer fits");
            }
            vertices.update(vertexPlan.offset, vertexData);
            var indexBytes = indexData.remaining();
            stagedIndexBytes = Math.addExact(stagedIndexBytes, indexBytes);
            indices.update(indexPlan.offset, indexData);
            submittedIndexBytes = Math.addExact(submittedIndexBytes, indexBytes);
            indexSubmissions = Math.addExact(indexSubmissions, 1L);
            apply(freeVertices, vertexPlan, vertexCount);
            apply(freeIndices, indexPlan, indexCount);
            if (!vertexPlan.fromFree) {
                vertexHighWater = Math.addExact(vertexPlan.offset, vertexCount);
            }
            if (!indexPlan.fromFree) {
                indexHighWater = Math.addExact(indexPlan.offset, indexCount);
            }
            var slice = new Slice(this, label, vertexPlan.offset, vertexCount, indexPlan.offset, indexCount);
            if (lineBuilder != null) {
                buildLines(indexPlan.offset, indexCount);
            }
            liveSlices++;
            liveBytes = Math.addExact(liveBytes, slice.estimatedBytes);
            return slice;
        }

        private long capacityBytes() {
            var lineBytes = lines == null ? 0L : lines.getEstimatedBytes();
            return Math.addExact(Math.addExact(vertices.getEstimatedBytes(), indices.getEstimatedBytes()), lineBytes);
        }

        private void buildLines(int firstIndex, int indexCount) {
            var builder = lineBuilder;
            if (builder == null) {
                return;
            }
            if (lines == null) {
                lines = BgfxBuffer.dynamicIndex(
                        device.getResources(),
                        label + "/lines",
                        Math.multiplyExact(indexCapacity, 2),
                        true,
                        BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ_WRITE));
            }
            var triangles = indexCount / 3;
            if (triangles > 0) {
                builder.build(indices, lines, firstIndex, triangles);
            }
        }

        private void reclaim(long currentFrame) {
            for (var index = retired.size() - 1; index >= 0; index--) {
                var range = retired.get(index);
                if (!isReusableAtFrame(range.frame, currentFrame)) {
                    continue;
                }
                releaseRange(freeVertices, range.vertexOffset, range.vertexCount);
                releaseRange(freeIndices, range.indexOffset, range.indexCount);
                retired.remove(index);
            }
            vertexHighWater = trimTail(freeVertices, vertexHighWater);
            indexHighWater = trimTail(freeIndices, indexHighWater);
        }
    }

    private Chunk createChunk(Kind kind, int requiredVertices, int requiredIndices) {
        var layout = layout(kind);
        var defaultVertices =
                Math.max(1, (kind == Kind.TERRAIN ? TERRAIN_VERTEX_BYTES : DEFAULT_VERTEX_BYTES) / layout.getStride());
        var defaultIndices = DEFAULT_INDEX_BYTES / Integer.BYTES;
        var vertexCapacity = computeCapacity(requiredVertices, defaultVertices);
        var indexCapacity = computeCapacity(requiredIndices, defaultIndices);
        BgfxBuffer vertices = null;
        BgfxBuffer indices = null;
        var id = nextChunkId++;
        var prefix = "geometry-pool/" + kind.name().toLowerCase() + "/chunk-" + id;
        try {
            vertices = BgfxBuffer.dynamicVertex(
                    device.getResources(),
                    prefix + "/vertices",
                    vertexCapacity,
                    layout,
                    BgfxBuffer.gpuResident(device.getCapabilities(), 0));
            indices = BgfxBuffer.dynamicIndex(
                    device.getResources(),
                    prefix + "/indices",
                    indexCapacity,
                    true,
                    BgfxBuffer.gpuResident(device.getCapabilities(), BGFX_BUFFER_COMPUTE_READ));
            var chunk = new Chunk(kind, prefix, vertices, indices, vertexCapacity, indexCapacity);
            if (lineBuilder != null) {
                chunk.buildLines(0, 0);
            }
            return chunk;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(indices, failure);
            closeAfterFailure(vertices, failure);
            throw failure;
        }
    }

    private void release(Slice slice) {
        if (slice.closed) {
            return;
        }
        var chunk = slice.chunk;
        if (chunk.owner != this || chunk.closed || chunk.liveSlices < 1) {
            throw new IllegalStateException("geometry slice belongs to a closed or different pool");
        }
        slice.closed = true;
        chunk.liveSlices--;
        chunk.liveBytes -= slice.estimatedBytes;
        chunk.retired.add(
                new RetiredRange(slice.vertexOffset, slice.vertexCount, slice.indexOffset, slice.indexCount, frame));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("geometry pool is closed");
        }
    }

    private static int computeCapacity(int required, int defaultCapacity) {
        if (required <= defaultCapacity) {
            return defaultCapacity;
        }
        var highest = Integer.highestOneBit(required - 1);
        if (highest <= 0 || highest > (Integer.MAX_VALUE >>> 1)) {
            return required;
        }
        return highest << 1;
    }

    private static BgfxVertexLayoutSpec layout(Kind kind) {
        return switch (kind) {
            case ENTITY, STATIC_MESH_TANGENT -> BgfxSceneVertexLayouts.ENTITY;
            case STATIC_MESH -> BgfxSceneVertexLayouts.STATIC_MESH;
            case STATIC_MESH_COLOR -> BgfxSceneVertexLayouts.STATIC_MESH_COLOR;
            case TERRAIN -> BgfxSceneVertexLayouts.TERRAIN;
        };
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

    private static Allocation findAllocation(List<Range> free, int highWater, int capacity, int required) {
        for (var index = 0; index < free.size(); index++) {
            var range = free.get(index);
            if (range.count >= required) {
                return new Allocation(range.offset, true, index);
            }
        }
        return (long) highWater + required <= capacity ? new Allocation(highWater, false, -1) : null;
    }

    private static void apply(List<Range> free, Allocation allocation, int used) {
        if (!allocation.fromFree) {
            return;
        }
        var range = free.get(allocation.freeIndex);
        if (range.count == used) {
            free.remove(allocation.freeIndex);
        } else {
            free.set(allocation.freeIndex, new Range(Math.addExact(range.offset, used), range.count - used));
        }
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

    private record RetiredRange(int vertexOffset, int vertexCount, int indexOffset, int indexCount, long frame) {}

    private record Allocation(int offset, boolean fromFree, int freeIndex) {}
}
