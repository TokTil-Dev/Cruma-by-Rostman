package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class BgfxScenePointsReadback implements AutoCloseable {
    private static final int SLOT_COUNT = 2;
    private static final float ORIGIN_MATCH_EPSILON_SQUARED = 0.25f;
    private static final float DIRECTION_MATCH_DOT = 0.99999f;

    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final boolean originBottomLeft;
    private final boolean available;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private long nextSerial;
    private long latestSerial = Long.MIN_VALUE;
    private Request pending;
    private Request lastRequested;
    private Sample latest;
    private boolean closed;

    BgfxScenePointsReadback(BgfxDevice device) {
        this.device = device;
        this.resources = device.getResources();
        this.originBottomLeft = device.getCapabilities().originBottomLeft();
        this.available =
                device.getCapabilities().blit() && device.getCapabilities().readback();
        for (var index = 0; index < slots.length; index++) {
            slots[index] = new Slot(index);
        }
    }

    Request request(float[] ndc, Vec3 origin, Vec3 forward, float maxDistance) {
        requireOpen();
        if (!available
                || ndc == null
                || ndc.length < 2
                || ndc.length % 2 != 0
                || ndc.length > ViewerRenderBackend.RAY_POINTS * 2
                || !Float.isFinite(maxDistance)
                || maxDistance <= 0f) {
            return null;
        }
        var request = new Request(
                nextSerial,
                ndc.clone(),
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                forward.getX(),
                forward.getY(),
                forward.getZ(),
                maxDistance);
        if (lastRequested != null && matches(lastRequested, request)) {
            return lastRequested;
        }
        nextSerial++;
        pending = request;
        lastRequested = request;
        return request;
    }

    float[] findLatestMatching(Request request) {
        if (request == null || latest == null || !matches(latest.request(), request)) {
            return null;
        }
        return latest.viewDepths();
    }

    void submit(BgfxTexture packedDepth, Extent extent, BgfxViewAllocator views) {
        requireOpen();
        var request = pending;
        if (!available
                || request == null
                || packedDepth == null
                || extent == null
                || extent.isZero()
                || views == null) {
            return;
        }
        var slot = freeSlot();
        if (slot == null) {
            return;
        }
        pending = null;
        slot.ensure();
        var view = views.allocate("scene-points-depth", Domain.RENDER, false);
        var points = request.ndc().length / 2;
        for (var index = 0; index < points; index++) {
            var sourceX = toPixel(request.ndc()[index * 2] * 0.5f + 0.5f, extent.width());
            var topDownY = toPixel(0.5f - request.ndc()[index * 2 + 1] * 0.5f, extent.height());
            var sourceY = originBottomLeft ? extent.height() - 1 - topDownY : topDownY;
            bgfx_blit(
                    view,
                    slot.staging.getHandle(),
                    0,
                    index,
                    0,
                    0,
                    packedDepth.getHandle(),
                    0,
                    sourceX,
                    sourceY,
                    0,
                    1,
                    1,
                    1);
        }
        slot.pixels.clear();
        slot.request = request;
        slot.readyFrame = Integer.toUnsignedLong(bgfx_read_texture(slot.staging.getHandle(), slot.pixels, 0, 0));
    }

    void poll(long frameNumber) {
        requireOpen();
        for (var slot : slots) {
            if (slot.request == null || Long.compareUnsigned(frameNumber, slot.readyFrame) < 0) {
                continue;
            }
            var request = slot.request;
            slot.request = null;
            if (request.serial() < latestSerial) {
                continue;
            }
            latestSerial = request.serial();
            var points = request.ndc().length / 2;
            var depths = new float[points];
            for (var index = 0; index < points; index++) {
                var high = slot.pixels.get(index * 4) & 0xff;
                var low = slot.pixels.get(index * 4 + 1) & 0xff;
                var third = slot.pixels.get(index * 4 + 2) & 0xff;
                var background = high == 0xff && low == 0xff && third == 0xff;
                depths[index] = background ? Float.NaN : high * 256f + low + third / 255f;
            }
            latest = new Sample(request, depths);
        }
    }

    void invalidate() {
        pending = null;
        lastRequested = null;
        latest = null;
        latestSerial = nextSerial;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pending = null;
        for (var slot : slots) {
            slot.close();
        }
        latest = null;
    }

    private Slot freeSlot() {
        for (var slot : slots) {
            if (slot.request == null) {
                return slot;
            }
        }
        return null;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("scene points readback is closed");
        }
    }

    private static int toPixel(float normalized, int extent) {
        return Math.max(0, Math.min(extent - 1, (int) Math.floor(normalized * extent)));
    }

    private static boolean matches(Request completed, Request current) {
        if (!Arrays.equals(completed.ndc(), current.ndc())
                || Float.floatToIntBits(completed.maxDistance()) != Float.floatToIntBits(current.maxDistance())) {
            return false;
        }
        var ox = completed.originX() - current.originX();
        var oy = completed.originY() - current.originY();
        var oz = completed.originZ() - current.originZ();
        if (ox * ox + oy * oy + oz * oz > ORIGIN_MATCH_EPSILON_SQUARED) {
            return false;
        }
        return completed.forwardX() * current.forwardX()
                        + completed.forwardY() * current.forwardY()
                        + completed.forwardZ() * current.forwardZ()
                >= DIRECTION_MATCH_DOT;
    }

    record Request(
            long serial,
            float[] ndc,
            float originX,
            float originY,
            float originZ,
            float forwardX,
            float forwardY,
            float forwardZ,
            float maxDistance) {}

    record Sample(Request request, float[] viewDepths) {}

    private final class Slot implements AutoCloseable {
        private final int index;
        private BgfxTexture staging;
        private ByteBuffer pixels;
        private Request request;
        private long readyFrame;

        private Slot(int index) {
            this.index = index;
        }

        @Override
        public void close() {
            if (request != null) {
                device.retainUntilShutdown(pixels);
                request = null;
            }
            if (staging != null) {
                staging.close();
                staging = null;
            }
            pixels = null;
        }

        private void ensure() {
            if (staging != null) {
                return;
            }
            staging = BgfxTexture.create2D(
                    resources,
                    "scene-points/depth-staging-" + index,
                    ViewerRenderBackend.RAY_POINTS,
                    1,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_READ_BACK | BGFX_TEXTURE_BLIT_DST,
                    null);
            pixels = ByteBuffer.allocateDirect(ViewerRenderBackend.RAY_POINTS * 4)
                    .order(ByteOrder.nativeOrder());
        }
    }
}
