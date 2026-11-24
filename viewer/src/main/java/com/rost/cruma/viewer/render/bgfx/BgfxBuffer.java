package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_COMPUTE_WRITE;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_DRAW_INDIRECT;
import static org.lwjgl.bgfx.BGFX.BGFX_BUFFER_INDEX32;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_dynamic_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_index_buffer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_vertex_buffer_name;
import static org.lwjgl.bgfx.BGFX.bgfx_update_dynamic_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_update_dynamic_vertex_buffer;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import java.nio.ByteBuffer;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;

public final class BgfxBuffer implements AutoCloseable {
    private final Type type;
    private final Lease lease;
    private final int capacityElements;
    private final int stride;
    private final boolean index32;

    private BgfxBuffer(Type type, Lease lease, int capacityElements, int stride, boolean index32) {
        this.type = type;
        this.lease = lease;
        this.capacityElements = capacityElements;
        this.stride = stride;
        this.index32 = index32;
    }

    public static BgfxBuffer staticVertex(
            BgfxResourceRegistry registry, String label, ByteBuffer data, BgfxVertexLayoutSpec layout, int flags) {
        requireDataMultiple(data, layout.getStride(), "vertex");
        validateFlags(flags, false, false);
        short handle;
        try (var stack = MemoryStack.stackPush()) {
            handle = bgfx_create_vertex_buffer(BgfxMemoryCopy.copy(data), layout.createNative(stack), flags);
        }
        var bytes = data.remaining();
        var lease = registry.register(Kind.VERTEX_BUFFER, handle, label, bytes, BGFX::bgfx_destroy_vertex_buffer);
        bgfx_set_vertex_buffer_name(handle, lease.getLabel());
        return new BgfxBuffer(Type.STATIC_VERTEX, lease, bytes / layout.getStride(), layout.getStride(), false);
    }

    public static BgfxBuffer staticIndex(
            BgfxResourceRegistry registry, String label, ByteBuffer data, boolean index32, int flags) {
        var stride = index32 ? 4 : 2;
        requireDataMultiple(data, stride, "index");
        flags = index32 ? flags | BGFX_BUFFER_INDEX32 : flags & ~BGFX_BUFFER_INDEX32;
        validateFlags(flags, true, false);
        var handle = bgfx_create_index_buffer(BgfxMemoryCopy.copy(data), flags);
        var bytes = data.remaining();
        var lease = registry.register(Kind.INDEX_BUFFER, handle, label, bytes, BGFX::bgfx_destroy_index_buffer);
        bgfx_set_index_buffer_name(handle, lease.getLabel());
        return new BgfxBuffer(Type.STATIC_INDEX, lease, bytes / stride, stride, index32);
    }

    public static int gpuResident(BgfxCapabilities capabilities, int flags) {
        return capabilities.renderer().equals("Direct3D 11") ? flags | BGFX_BUFFER_COMPUTE_WRITE : flags;
    }

    public static BgfxBuffer dynamicVertex(
            BgfxResourceRegistry registry, String label, int capacityVertices, BgfxVertexLayoutSpec layout, int flags) {
        requirePositive(capacityVertices, "dynamic vertex capacity");
        validateFlags(flags, false, true);
        short handle;
        try (var stack = MemoryStack.stackPush()) {
            handle = bgfx_create_dynamic_vertex_buffer(capacityVertices, layout.createNative(stack), flags);
        }
        var bytes = Math.multiplyExact((long) capacityVertices, layout.getStride());
        var lease = registry.register(
                Kind.DYNAMIC_VERTEX_BUFFER, handle, label, bytes, BGFX::bgfx_destroy_dynamic_vertex_buffer);
        return new BgfxBuffer(Type.DYNAMIC_VERTEX, lease, capacityVertices, layout.getStride(), false);
    }

    public static BgfxBuffer dynamicIndex(
            BgfxResourceRegistry registry, String label, int capacityIndices, boolean index32, int flags) {
        requirePositive(capacityIndices, "dynamic index capacity");
        var stride = index32 ? 4 : 2;
        flags = index32 ? flags | BGFX_BUFFER_INDEX32 : flags & ~BGFX_BUFFER_INDEX32;
        validateFlags(flags, true, true);
        var handle = bgfx_create_dynamic_index_buffer(capacityIndices, flags);
        var bytes = Math.multiplyExact((long) capacityIndices, stride);
        var lease = registry.register(
                Kind.DYNAMIC_INDEX_BUFFER, handle, label, bytes, BGFX::bgfx_destroy_dynamic_index_buffer);
        return new BgfxBuffer(Type.DYNAMIC_INDEX, lease, capacityIndices, stride, index32);
    }

    static void validateFlags(int flags, boolean index, boolean dynamic) {
        if (!index && (flags & BGFX_BUFFER_INDEX32) != 0) {
            throw new IllegalArgumentException("BGFX_BUFFER_INDEX32 is only valid for index buffers");
        }
        if ((flags & BGFX_BUFFER_DRAW_INDIRECT) != 0) {
            throw new IllegalArgumentException("draw-indirect arguments require BgfxIndirectBuffer");
        }
        if (!dynamic && (flags & BGFX.BGFX_BUFFER_ALLOW_RESIZE) != 0) {
            throw new IllegalArgumentException("BGFX_BUFFER_ALLOW_RESIZE is only valid for dynamic buffers");
        }
    }

    public void update(int firstElement, ByteBuffer data) {
        if (firstElement < 0) {
            throw new IllegalArgumentException("first buffer element cannot be negative");
        }
        requireDataMultiple(data, stride, "dynamic buffer");
        var elements = data.remaining() / stride;
        if ((long) firstElement + elements > capacityElements) {
            throw new IllegalArgumentException("dynamic buffer update exceeds capacity");
        }
        switch (type) {
            case DYNAMIC_VERTEX ->
                bgfx_update_dynamic_vertex_buffer(lease.getHandle(), firstElement, BgfxMemoryCopy.copy(data));
            case DYNAMIC_INDEX ->
                bgfx_update_dynamic_index_buffer(lease.getHandle(), firstElement, BgfxMemoryCopy.copy(data));
            default -> throw new IllegalStateException("static bgfx buffers cannot be updated");
        }
    }

    public short getHandle() {
        return lease.getHandle();
    }

    public Type getType() {
        return type;
    }

    public int getCapacityElements() {
        return capacityElements;
    }

    public int getStride() {
        return stride;
    }

    public boolean isIndex32() {
        return index32;
    }

    public long getEstimatedBytes() {
        return lease.getEstimatedBytes();
    }

    @Override
    public void close() {
        lease.close();
    }

    private static void requireDataMultiple(ByteBuffer data, int stride, String noun) {
        if (data == null || !data.hasRemaining()) {
            throw new IllegalArgumentException(noun + " data cannot be null or empty");
        }
        if (data.remaining() % stride != 0) {
            throw new IllegalArgumentException(noun + " data size is not a multiple of stride " + stride);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public enum Type {
        STATIC_VERTEX,
        STATIC_INDEX,
        DYNAMIC_VERTEX,
        DYNAMIC_INDEX
    }
}
