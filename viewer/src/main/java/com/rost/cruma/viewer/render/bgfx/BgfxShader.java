package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_create_shader;
import static org.lwjgl.bgfx.BGFX.bgfx_set_shader_name;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import java.nio.ByteBuffer;
import org.lwjgl.bgfx.BGFX;

final class BgfxShader implements AutoCloseable {
    private final Lease lease;

    private BgfxShader(Lease lease) {
        this.lease = lease;
    }

    static BgfxShader create(BgfxResourceRegistry registry, String label, ByteBuffer bytecode) {
        var bytes = bytecode == null ? 0 : bytecode.remaining();
        var handle = bgfx_create_shader(BgfxMemoryCopy.copy(bytecode));
        var lease = registry.registerReference(Kind.SHADER, handle, label, bytes, BGFX::bgfx_destroy_shader);
        bgfx_set_shader_name(handle, lease.getLabel());
        return new BgfxShader(lease);
    }

    short getHandle() {
        return lease.getHandle();
    }

    @Override
    public void close() {
        lease.close();
    }
}
