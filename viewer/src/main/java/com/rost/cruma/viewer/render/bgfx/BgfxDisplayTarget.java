package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import java.util.List;

final class BgfxDisplayTarget implements AutoCloseable {
    private final BgfxResourceRegistry resources;
    private Extent capacity = new Extent(0, 0);
    private BgfxTexture color;
    private BgfxFramebuffer framebuffer;

    BgfxDisplayTarget(BgfxResourceRegistry resources) {
        if (resources == null) {
            throw new IllegalArgumentException("display target requires a resource registry");
        }
        this.resources = resources;
    }

    void ensure(Extent requested) {
        if (requested == null || requested.isZero()) {
            throw new IllegalArgumentException("display target extent must be non-zero");
        }
        if (capacity.width() >= requested.width() && capacity.height() >= requested.height()) {
            return;
        }
        var nextCapacity = new Extent(
                Math.max(capacity.width(), requested.width()), Math.max(capacity.height(), requested.height()));
        BgfxTexture nextColor = null;
        BgfxFramebuffer nextFramebuffer = null;
        try {
            nextColor = BgfxTexture.create2D(
                    resources,
                    "frame/visual-display-color",
                    nextCapacity.width(),
                    nextCapacity.height(),
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_RT,
                    null);
            nextFramebuffer = BgfxFramebuffer.create(resources, "frame/visual-display-framebuffer", List.of(nextColor));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(nextFramebuffer, failure);
            closeAfterFailure(nextColor, failure);
            throw failure;
        }
        var previousFramebuffer = framebuffer;
        var previousColor = color;
        framebuffer = nextFramebuffer;
        color = nextColor;
        capacity = nextCapacity;
        RuntimeException failure = null;
        failure = closeAndMerge(previousFramebuffer, failure);
        failure = closeAndMerge(previousColor, failure);
        if (failure != null) {
            throw failure;
        }
    }

    BgfxTexture getColor() {
        requireAllocated();
        return color;
    }

    BgfxFramebuffer getFramebuffer() {
        requireAllocated();
        return framebuffer;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        failure = closeAndMerge(framebuffer, failure);
        framebuffer = null;
        failure = closeAndMerge(color, failure);
        color = null;
        capacity = new Extent(0, 0);
        if (failure != null) {
            throw failure;
        }
    }

    private void requireAllocated() {
        if (framebuffer == null || color == null) {
            throw new IllegalStateException("display target is not allocated");
        }
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        } catch (Exception closeFailure) {
            var wrapped = new IllegalStateException("failed to close display target", closeFailure);
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
