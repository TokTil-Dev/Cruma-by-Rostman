package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT_WRITE_ONLY;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import java.util.List;

public final class BgfxFrameTargets implements AutoCloseable {
    private final BgfxResourceRegistry resources;

    private final int depthFormat;

    private Extent extent = new Extent(0, 0);
    private Extent capacity = new Extent(0, 0);
    private BgfxTexture color;
    private BgfxTexture packedDepth;
    private BgfxTexture normal;

    private BgfxTexture waterMask;

    private BgfxTexture materialAux;

    private BgfxTexture depth;
    private BgfxFramebuffer framebuffer;

    private BgfxTexture ownDepth;
    private BgfxFramebuffer colorOverOwnDepth;

    public BgfxFrameTargets(BgfxResourceRegistry resources, int depthFormat) {
        this.resources = resources;
        this.depthFormat = depthFormat;
    }

    public boolean ensure(Extent requested) {
        if (requested == null) {
            throw new IllegalArgumentException("frame-target extent cannot be null");
        }
        if (requested.isZero()) {
            extent = requested;
            return false;
        }
        reserve(requested);
        extent = requested;
        return true;
    }

    public void reserve(Extent requestedCapacity) {
        if (requestedCapacity == null) {
            throw new IllegalArgumentException("frame-target capacity cannot be null");
        }
        if (requestedCapacity.isZero()
                || capacity.width() >= requestedCapacity.width() && capacity.height() >= requestedCapacity.height()) {
            return;
        }
        var nextCapacity = new Extent(
                Math.max(capacity.width(), requestedCapacity.width()),
                Math.max(capacity.height(), requestedCapacity.height()));
        BgfxTexture nextColor = null;
        BgfxTexture nextPackedDepth = null;
        BgfxTexture nextNormal = null;
        BgfxTexture nextWaterMask = null;
        BgfxTexture nextMaterialAux = null;
        BgfxTexture nextDepth = null;
        BgfxFramebuffer nextFramebuffer = null;
        try {
            nextColor = BgfxTexture.create2D(
                    resources,
                    "frame/world-color",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT | BGFX_TEXTURE_BLIT_DST,
                    null);
            nextPackedDepth = BgfxTexture.create2D(
                    resources,
                    "frame/world-packed-depth",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextNormal = BgfxTexture.create2D(
                    resources,
                    "frame/world-view-normal",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextWaterMask = BgfxTexture.create2D(
                    resources,
                    "frame/world-water-mask",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextMaterialAux = BgfxTexture.create2D(
                    resources,
                    "frame/world-material-aux",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextDepth = BgfxTexture.create2D(
                    resources,
                    "frame/world-depth",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    depthFormat,
                    BGFX_TEXTURE_RT,
                    null);
            nextFramebuffer = BgfxFramebuffer.create(
                    resources,
                    "frame/world-framebuffer",
                    List.of(nextColor, nextPackedDepth, nextNormal, nextWaterMask, nextMaterialAux, nextDepth));
        } catch (RuntimeException | Error e) {
            closeAfterFailure(nextFramebuffer, e);
            closeAfterFailure(nextDepth, e);
            closeAfterFailure(nextMaterialAux, e);
            closeAfterFailure(nextWaterMask, e);
            closeAfterFailure(nextNormal, e);
            closeAfterFailure(nextPackedDepth, e);
            closeAfterFailure(nextColor, e);
            throw e;
        }
        closeColorOverOwnDepth();
        var oldColor = color;
        var oldPackedDepth = packedDepth;
        var oldNormal = normal;
        var oldWaterMask = waterMask;
        var oldMaterialAux = materialAux;
        var oldDepth = depth;
        var oldFramebuffer = framebuffer;
        color = nextColor;
        packedDepth = nextPackedDepth;
        normal = nextNormal;
        waterMask = nextWaterMask;
        materialAux = nextMaterialAux;
        depth = nextDepth;
        framebuffer = nextFramebuffer;
        capacity = nextCapacity;
        RuntimeException failure = null;
        failure = closeAndMerge(oldFramebuffer, failure);
        failure = closeAndMerge(oldDepth, failure);
        failure = closeAndMerge(oldMaterialAux, failure);
        failure = closeAndMerge(oldWaterMask, failure);
        failure = closeAndMerge(oldPackedDepth, failure);
        failure = closeAndMerge(oldNormal, failure);
        failure = closeAndMerge(oldColor, failure);
        if (failure != null) {
            throw failure;
        }
    }

    public Extent getExtent() {
        return extent;
    }

    public Extent getCapacity() {
        return capacity;
    }

    public BgfxTexture getColor() {
        requireAllocated();
        return color;
    }

    public BgfxTexture getDepth() {
        requireAllocated();
        return depth;
    }

    public BgfxTexture getPackedDepth() {
        requireAllocated();
        return packedDepth;
    }

    public BgfxTexture getNormal() {
        requireAllocated();
        return normal;
    }

    public BgfxTexture getWaterMask() {
        requireAllocated();
        return waterMask;
    }

    public BgfxTexture getMaterialAux() {
        requireAllocated();
        return materialAux;
    }

    public BgfxFramebuffer getFramebuffer() {
        requireAllocated();
        return framebuffer;
    }

    public BgfxFramebuffer getColorOverOwnDepth() {
        requireAllocated();
        if (colorOverOwnDepth == null) {
            ownDepth = BgfxTexture.create2D(
                    resources,
                    "frame/own-depth",
                    capacity.width(),
                    capacity.height(),
                    false,
                    1,
                    depthFormat,
                    BGFX_TEXTURE_RT_WRITE_ONLY,
                    null);
            colorOverOwnDepth =
                    BgfxFramebuffer.create(resources, "frame/color-over-own-depth", List.of(color, ownDepth));
        }
        return colorOverOwnDepth;
    }

    @Override
    public void close() {
        destroyTargets();
    }

    private void closeColorOverOwnDepth() {
        RuntimeException failure = null;
        failure = closeAndMerge(colorOverOwnDepth, failure);
        colorOverOwnDepth = null;
        failure = closeAndMerge(ownDepth, failure);
        ownDepth = null;
        if (failure != null) {
            throw failure;
        }
    }

    private void destroyTargets() {
        closeColorOverOwnDepth();
        RuntimeException failure = null;
        failure = closeAndMerge(framebuffer, failure);
        framebuffer = null;
        failure = closeAndMerge(depth, failure);
        depth = null;
        failure = closeAndMerge(waterMask, failure);
        waterMask = null;
        failure = closeAndMerge(materialAux, failure);
        materialAux = null;
        failure = closeAndMerge(packedDepth, failure);
        packedDepth = null;
        failure = closeAndMerge(normal, failure);
        normal = null;
        failure = closeAndMerge(color, failure);
        color = null;
        extent = new Extent(0, 0);
        capacity = new Extent(0, 0);
        if (failure != null) {
            throw failure;
        }
    }

    private void requireAllocated() {
        if (extent.isZero() || framebuffer == null) {
            throw new IllegalStateException("frame targets are not allocated for a zero-sized display");
        }
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
        } catch (Exception e) {
            var wrapped = new IllegalStateException("failed to close bgfx frame target", e);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
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
}
