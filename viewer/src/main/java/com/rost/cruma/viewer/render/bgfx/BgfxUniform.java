package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_COUNT;
import static org.lwjgl.bgfx.BGFX.bgfx_create_uniform;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import org.lwjgl.bgfx.BGFX;

public final class BgfxUniform implements AutoCloseable {
    private final Lease lease;
    private final int type;
    private final int count;

    private BgfxUniform(Lease lease, int type, int count) {
        this.lease = lease;
        this.type = type;
        this.count = count;
    }

    public static BgfxUniform create(BgfxResourceRegistry registry, String name, int type, int count) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("uniform name cannot be blank");
        }
        if (type < 0 || type >= BGFX_UNIFORM_TYPE_COUNT) {
            throw new IllegalArgumentException("invalid bgfx uniform type: " + type);
        }
        if (count < 1 || count > 0xffff) {
            throw new IllegalArgumentException("uniform array count must be within 1..65535");
        }
        var handle = bgfx_create_uniform(name, type, count);
        var lease = registry.registerReference(Kind.UNIFORM, handle, name, 0L, BGFX::bgfx_destroy_uniform);
        return new BgfxUniform(lease, type, count);
    }

    public short getHandle() {
        return lease.getHandle();
    }

    public int getType() {
        return type;
    }

    public int getCount() {
        return count;
    }

    @Override
    public void close() {
        lease.close();
    }
}
