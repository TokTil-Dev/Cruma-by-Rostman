package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_MASK;
import static org.lwjgl.bgfx.BGFX.bgfx_calc_texture_size;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_create_texture_cube;
import static org.lwjgl.bgfx.BGFX.bgfx_is_texture_valid;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture_name;
import static org.lwjgl.bgfx.BGFX.bgfx_update_texture_2d;
import static org.lwjgl.bgfx.BGFX.bgfx_update_texture_cube;

import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Kind;
import com.rost.cruma.viewer.render.bgfx.BgfxResourceRegistry.Lease;
import java.nio.ByteBuffer;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.bgfx.BGFXMemory;
import org.lwjgl.bgfx.BGFXTextureInfo;
import org.lwjgl.system.MemoryStack;

public final class BgfxTexture implements AutoCloseable {
    public static final int AUTO_PITCH = 0xffff;

    private final Lease lease;
    private final Descriptor descriptor;
    private final boolean external;

    private BgfxTexture(Lease lease, Descriptor descriptor, boolean external) {
        this.lease = lease;
        this.descriptor = descriptor;
        this.external = external;
    }

    public static BgfxTexture create2D(
            BgfxResourceRegistry registry,
            String label,
            int width,
            int height,
            boolean mipmapped,
            int layers,
            int format,
            long flags,
            ByteBuffer initialData) {
        return create2D(
                registry,
                label,
                width,
                height,
                mipmapped ? mipCount(width, height) : 1,
                layers,
                format,
                flags,
                initialData);
    }

    public static BgfxTexture create2D(
            BgfxResourceRegistry registry,
            String label,
            int width,
            int height,
            int mipLevels,
            int layers,
            int format,
            long flags,
            ByteBuffer initialData) {
        var descriptor = new Descriptor(width, height, layers, mipLevels, false, format, flags);
        descriptor.validateNative();
        var handle = descriptor.partialMipChain()
                ? bgfx_create_texture(copyOrNull(BgfxTextureContainer.encode(descriptor, initialData)), flags, 0, null)
                : bgfx_create_texture_2d(
                        width, height, descriptor.isMipmapped(), layers, format, flags, copyOrNull(initialData), 0L);
        var lease =
                registry.register(Kind.TEXTURE, handle, label, descriptor.storageBytes(), BGFX::bgfx_destroy_texture);
        bgfx_set_texture_name(handle, lease.getLabel());
        return new BgfxTexture(lease, descriptor, false);
    }

    public static BgfxTexture createExternal2D(
            BgfxResourceRegistry registry,
            String label,
            int width,
            int height,
            int format,
            long flags,
            long nativeTexture) {
        if (nativeTexture == 0L) {
            throw new IllegalArgumentException("external texture pointer cannot be null");
        }
        var descriptor = new Descriptor(width, height, 1, false, false, format, flags);
        descriptor.validateNative();
        var handle = bgfx_create_texture_2d(width, height, false, 1, format, flags, null, nativeTexture);
        var lease = registry.register(Kind.TEXTURE, handle, label, 0L, BGFX::bgfx_destroy_texture);
        bgfx_set_texture_name(handle, lease.getLabel());
        return new BgfxTexture(lease, descriptor, true);
    }

    public static BgfxTexture createCube(
            BgfxResourceRegistry registry,
            String label,
            int size,
            boolean mipmapped,
            int layers,
            int format,
            long flags,
            ByteBuffer initialData) {
        return createCube(
                registry, label, size, mipmapped ? mipCount(size, size) : 1, layers, format, flags, initialData);
    }

    public static BgfxTexture createCube(
            BgfxResourceRegistry registry,
            String label,
            int size,
            int mipLevels,
            int layers,
            int format,
            long flags,
            ByteBuffer initialData) {
        var descriptor = new Descriptor(size, size, layers, mipLevels, true, format, flags);
        descriptor.validateNative();
        var handle = descriptor.partialMipChain()
                ? bgfx_create_texture(copyOrNull(BgfxTextureContainer.encode(descriptor, initialData)), flags, 0, null)
                : bgfx_create_texture_cube(
                        size, descriptor.isMipmapped(), layers, format, flags, copyOrNull(initialData), 0L);
        var lease =
                registry.register(Kind.TEXTURE, handle, label, descriptor.storageBytes(), BGFX::bgfx_destroy_texture);
        bgfx_set_texture_name(handle, lease.getLabel());
        return new BgfxTexture(lease, descriptor, false);
    }

    public void update2D(int layer, int mip, int x, int y, int width, int height, ByteBuffer data, int pitch) {
        if (external) {
            throw new IllegalStateException("external textures cannot be updated through bgfx");
        }
        if (descriptor.cube()) {
            throw new IllegalStateException("use updateCube for cubemap textures");
        }
        validateRegion(layer, mip, x, y, width, height);
        if (pitch != AUTO_PITCH && pitch < 1) {
            throw new IllegalArgumentException("texture pitch must be positive or AUTO_PITCH");
        }
        bgfx_update_texture_2d(lease.getHandle(), layer, mip, x, y, width, height, BgfxMemoryCopy.copy(data), pitch);
    }

    public void updateCube(
            int layer, byte face, int mip, int x, int y, int width, int height, ByteBuffer data, int pitch) {
        if (external) {
            throw new IllegalStateException("external textures cannot be updated through bgfx");
        }
        if (!descriptor.cube()) {
            throw new IllegalStateException("use update2D for non-cubemap textures");
        }
        if (face < 0 || face > 5) {
            throw new IllegalArgumentException("cubemap face must be within 0..5");
        }
        validateRegion(layer, mip, x, y, width, height);
        if (pitch != AUTO_PITCH && pitch < 1) {
            throw new IllegalArgumentException("texture pitch must be positive or AUTO_PITCH");
        }
        bgfx_update_texture_cube(
                lease.getHandle(), layer, face, mip, x, y, width, height, BgfxMemoryCopy.copy(data), pitch);
    }

    public short getHandle() {
        return lease.getHandle();
    }

    public Descriptor getDescriptor() {
        return descriptor;
    }

    public long getEstimatedBytes() {
        return lease.getEstimatedBytes();
    }

    @Override
    public void close() {
        lease.close();
    }

    private void validateRegion(int layer, int mip, int x, int y, int width, int height) {
        if (layer < 0 || layer >= descriptor.layers()) {
            throw new IllegalArgumentException("texture layer is out of range");
        }
        var mipCount = descriptor.mipLevels();
        if (mip < 0 || mip >= mipCount) {
            throw new IllegalArgumentException("texture mip is out of range");
        }
        var mipWidth = Math.max(1, descriptor.width() >> mip);
        var mipHeight = Math.max(1, descriptor.height() >> mip);
        if (x < 0 || y < 0 || width < 1 || height < 1 || (long) x + width > mipWidth || (long) y + height > mipHeight) {
            throw new IllegalArgumentException("texture update region is out of bounds");
        }
    }

    private static BGFXMemory copyOrNull(ByteBuffer data) {
        return data == null ? null : BgfxMemoryCopy.copy(data);
    }

    private static int mipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    public record Descriptor(int width, int height, int layers, int mipLevels, boolean cube, int format, long flags) {
        public Descriptor(int width, int height, int layers, boolean mipmapped, boolean cube, int format, long flags) {
            this(width, height, layers, mipmapped ? mipCount(width, height) : 1, cube, format, flags);
        }

        public Descriptor {
            if (width < 1 || height < 1 || width > 0xffff || height > 0xffff) {
                throw new IllegalArgumentException("texture dimensions must be within 1..65535");
            }
            if (layers < 1 || layers > 0xffff) {
                throw new IllegalArgumentException("texture layer count must be within 1..65535");
            }
            if (mipLevels < 1 || mipLevels > mipCount(width, height)) {
                throw new IllegalArgumentException("texture mip count exceeds its dimensions");
            }
            if (cube && width != height) {
                throw new IllegalArgumentException("cubemap faces must be square");
            }
            if ((flags & BGFX_TEXTURE_READ_BACK) != 0 && (flags & BGFX_TEXTURE_RT_MASK) != 0) {
                throw new IllegalArgumentException(
                        "readback staging and render-target usage must use different textures");
            }
        }

        public boolean isMipmapped() {
            return mipLevels > 1;
        }

        boolean partialMipChain() {
            return mipLevels > 1 && mipLevels < mipCount(width, height);
        }

        void validateNative() {
            if (!bgfx_is_texture_valid(0, cube, layers, format, flags)) {
                throw new IllegalArgumentException(
                        "texture contract is unsupported by the active bgfx renderer: " + this);
            }
        }

        long storageBytes() {
            try (var stack = MemoryStack.stackPush()) {
                var info = BGFXTextureInfo.calloc(stack);
                var bytes = 0L;
                for (var mip = 0; mip < mipLevels; mip++) {
                    bgfx_calc_texture_size(
                            info,
                            Math.max(1, width >> mip),
                            Math.max(1, height >> mip),
                            1,
                            cube,
                            false,
                            layers,
                            format);
                    bytes = Math.addExact(bytes, Integer.toUnsignedLong(info.storageSize()));
                }
                return bytes;
            }
        }
    }
}
