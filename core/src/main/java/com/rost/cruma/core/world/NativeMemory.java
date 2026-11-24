package com.rost.cruma.core.world;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeMemory {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle MALLOC = LINKER.downcallHandle(
            LINKER.defaultLookup().find("malloc").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle FREE = LINKER.downcallHandle(
            LINKER.defaultLookup().find("free").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private NativeMemory() {}

    public static MemorySegment allocateUnzeroed(long bytes, Arena arena) {
        if (bytes < 1L) {
            throw new IllegalArgumentException("allocation size must be positive, got " + bytes);
        }
        if (arena == null) {
            throw new IllegalArgumentException("allocation requires an arena");
        }
        MemorySegment raw;
        try {
            raw = (MemorySegment) MALLOC.invokeExact(bytes);
        } catch (Throwable failure) {
            throw new IllegalStateException("malloc is unavailable", failure);
        }
        if (raw.address() == 0L) {
            throw new OutOfMemoryError("malloc of " + bytes + " bytes failed");
        }
        return raw.reinterpret(bytes, arena, NativeMemory::free);
    }

    public static ByteBuffer directBuffer(int bytes) {
        return allocateUnzeroed(bytes, Arena.ofAuto()).asByteBuffer().order(ByteOrder.nativeOrder());
    }

    private static void free(MemorySegment segment) {
        try {
            FREE.invokeExact(segment);
        } catch (Throwable failure) {
            throw new IllegalStateException("free failed", failure);
        }
    }
}
