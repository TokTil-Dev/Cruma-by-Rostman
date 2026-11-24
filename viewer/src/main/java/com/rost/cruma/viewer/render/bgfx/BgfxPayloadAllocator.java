package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.NativeMemory;
import java.nio.ByteBuffer;

@FunctionalInterface
interface BgfxPayloadAllocator {
    BgfxPayloadAllocator AUTOMATIC = NativeMemory::directBuffer;

    ByteBuffer allocate(int bytes);
}
