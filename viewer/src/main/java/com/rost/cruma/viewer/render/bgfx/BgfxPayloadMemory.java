package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.NativeMemory;
import java.lang.foreign.Arena;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class BgfxPayloadMemory implements AutoCloseable, BgfxPayloadAllocator {
    private static final Cleaner CLEANER = Cleaner.create();
    private static final int SLAB_BYTES = 8 << 20;
    private static final int LARGEST_IN_SLAB = SLAB_BYTES / 8;
    private static final int ALIGNMENT = 16;

    private final Arena arena = Arena.ofShared();
    private final Cleanable cleanable;
    private ByteBuffer slab;
    private int used;
    private long allocated;

    BgfxPayloadMemory() {
        cleanable = CLEANER.register(this, arena::close);
    }

    @Override
    public ByteBuffer allocate(int bytes) {
        if (bytes < 1) {
            throw new IllegalArgumentException("payload allocation must be positive, got " + bytes);
        }
        allocated = Math.addExact(allocated, bytes);
        if (bytes > LARGEST_IN_SLAB) {
            return NativeMemory.allocateUnzeroed(bytes, arena).asByteBuffer().order(ByteOrder.nativeOrder());
        }
        if (slab == null || used + bytes > SLAB_BYTES) {
            slab = NativeMemory.allocateUnzeroed(SLAB_BYTES, arena).asByteBuffer();
            used = 0;
        }
        var result = slab.slice(used, bytes).order(ByteOrder.nativeOrder());
        used = used + bytes + ALIGNMENT - 1 & -ALIGNMENT;
        return result;
    }

    long getAllocatedBytes() {
        return allocated;
    }

    @Override
    public void close() {
        cleanable.clean();
    }
}
