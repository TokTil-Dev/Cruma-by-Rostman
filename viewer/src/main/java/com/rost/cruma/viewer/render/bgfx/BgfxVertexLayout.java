package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_create_vertex_layout;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;

final class BgfxVertexLayout implements AutoCloseable {
    private final Lease lease;
    private final BgfxVertexLayoutSpec spec;

    private BgfxVertexLayout(Lease lease, BgfxVertexLayoutSpec spec) {
        this.lease = lease;
        this.spec = spec;
    }

    static BgfxVertexLayout create(BgfxResourceRegistry registry, String label, BgfxVertexLayoutSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("vertex layout spec is required");
        }
        short handle;
        try (var stack = MemoryStack.stackPush()) {
            handle = bgfx_create_vertex_layout(spec.createNative(stack));
        }
        return new BgfxVertexLayout(
                registry.register(Kind.VERTEX_LAYOUT, handle, label, 0L, BGFX::bgfx_destroy_vertex_layout), spec);
    }

    short getHandle() {
        return lease.getHandle();
    }

    BgfxVertexLayoutSpec getSpec() {
        return spec;
    }

    @Override
    public void close() {
        lease.close();
    }
}
