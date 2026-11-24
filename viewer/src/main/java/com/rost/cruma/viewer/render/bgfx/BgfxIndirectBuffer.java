package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_create_indirect_buffer;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import org.lwjgl.bgfx.BGFX;

public final class BgfxIndirectBuffer implements AutoCloseable {
    private static final int ESTIMATED_COMMAND_BYTES = 20;

    private final Lease lease;
    private final int commandCapacity;

    private BgfxIndirectBuffer(Lease lease, int commandCapacity) {
        this.lease = lease;
        this.commandCapacity = commandCapacity;
    }

    public static BgfxIndirectBuffer create(
            BgfxResourceRegistry registry, BgfxCapabilities capabilities, String label, int commandCapacity) {
        if (!capabilities.drawIndirect()) {
            throw new UnsupportedOperationException("active bgfx renderer does not support indirect draws");
        }
        if (commandCapacity < 1) {
            throw new IllegalArgumentException("indirect command capacity must be positive");
        }
        var handle = bgfx_create_indirect_buffer(commandCapacity);
        var bytes = Math.multiplyExact((long) commandCapacity, ESTIMATED_COMMAND_BYTES);
        var lease = registry.register(Kind.INDIRECT_BUFFER, handle, label, bytes, BGFX::bgfx_destroy_indirect_buffer);
        return new BgfxIndirectBuffer(lease, commandCapacity);
    }

    public short getHandle() {
        return lease.getHandle();
    }

    public int getCommandCapacity() {
        return commandCapacity;
    }

    @Override
    public void close() {
        lease.close();
    }
}
