package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_BLIT_DST;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_READ_BACK;
import static org.lwjgl.bgfx.BGFX.bgfx_blit;
import static org.lwjgl.bgfx.BGFX.bgfx_read_texture;

import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import org.apache.logging.log4j.LogManager;

final class BgfxObjectPickerReadback implements AutoCloseable {
    private static final int SLOT_COUNT = 4;
    private static final float NDC_MATCH_EPSILON = 0.004f;
    private static final float ORIGIN_MATCH_EPSILON_SQUARED = 0.25f;
    private static final float DIRECTION_MATCH_DOT = 0.99999f;
    private static final double CLEARED_DEPTH = 0.999999;

    private final BgfxDevice device;
    private final BgfxResourceRegistry resources;
    private final boolean originBottomLeft;
    private final boolean available;
    private final Slot[] slots = new Slot[SLOT_COUNT];
    private long nextSerial;
    private long latestSerial = Long.MIN_VALUE;
    private Request pending;
    private Sample latest;
    private BgfxOrthoPickDepth orthoDepth;
    private boolean orthoDepthFailed;
    private boolean closed;

    BgfxObjectPickerReadback(BgfxDevice device) {
        this.device = device;
        this.resources = device.getResources();
        this.originBottomLeft = device.getCapabilities().originBottomLeft();
        this.available =
                device.getCapabilities().blit() && device.getCapabilities().readback();
        for (var index = 0; index < slots.length; index++) {
            slots[index] = new Slot(index);
        }
    }

    Request request(
            float ndcX,
            float ndcY,
            Vec3 origin,
            Vec3 direction,
            float viewDepthScale,
            float maxDistance,
            Set<Category> categories) {
        return request(ndcX, ndcY, origin, direction, viewDepthScale, maxDistance, categories, Float.NaN, Float.NaN);
    }

    Request request(
            float ndcX,
            float ndcY,
            Vec3 origin,
            Vec3 direction,
            float viewDepthScale,
            float maxDistance,
            Set<Category> categories,
            float depthNear,
            float depthFar) {
        requireOpen();
        if (!available
                || origin == null
                || direction == null
                || categories == null
                || categories.isEmpty()
                || !Float.isFinite(ndcX)
                || !Float.isFinite(ndcY)
                || !Float.isFinite(viewDepthScale)
                || viewDepthScale <= 0f
                || !Float.isFinite(maxDistance)
                || maxDistance <= 0f) {
            pending = null;
            return null;
        }
        var request = new Request(
                nextSerial++,
                ndcX,
                ndcY,
                origin.getX(),
                origin.getY(),
                origin.getZ(),
                direction.getX(),
                direction.getY(),
                direction.getZ(),
                viewDepthScale,
                maxDistance,
                Set.copyOf(categories),
                depthNear,
                depthFar);
        pending = request;
        return request;
    }

    Sample findLatestMatching(Request request) {
        if (request == null || latest == null || !matches(latest.request(), request)) {
            return null;
        }
        return latest;
    }

    void submit(BgfxTexture packedDepth, BgfxTexture depth, Extent extent, BgfxViewAllocator views) {
        requireOpen();
        var request = pending;
        pending = null;
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
        slot.ensure();
        var sourceX = toPixel(request.ndcX() * 0.5f + 0.5f, extent.width());
        var topDownY = toPixel(0.5f - request.ndcY() * 0.5f, extent.height());
        var sourceY = originBottomLeft ? extent.height() - 1 - topDownY : topDownY;
        var source = packedDepth;
        if (request.ortho()) {
            var ortho = orthoDepth(depth);
            if (ortho == null) {
                return;
            }
            ortho.draw(depth, sourceX, sourceY, views);
            source = ortho.getTarget();
            sourceX = 0;
            sourceY = 0;
        }
        var view = views.allocate("object-picker-depth", Domain.RENDER, false);
        bgfx_blit(view, slot.staging.getHandle(), 0, 0, 0, 0, source.getHandle(), 0, sourceX, sourceY, 0, 1, 1, 1);
        slot.pixels.clear();
        slot.request = request;
        slot.readyFrame = Integer.toUnsignedLong(bgfx_read_texture(slot.staging.getHandle(), slot.pixels, 0, 0));
    }

    void poll(long frameNumber) {
        requireOpen();
        for (var slot : slots) {
            if (slot.request == null || !hasReached(frameNumber, slot.readyFrame)) {
                continue;
            }
            var request = slot.request;
            slot.request = null;
            if (request.serial() < latestSerial) {
                continue;
            }
            latestSerial = request.serial();
            var high = slot.pixels.get(0) & 0xff;
            var low = slot.pixels.get(1) & 0xff;
            if (request.ortho()) {
                var depth = BgfxOrthoPickDepth.decode(high, low, slot.pixels.get(2) & 0xff);
                latest = new Sample(
                        request,
                        depth >= CLEARED_DEPTH
                                ? Float.NaN
                                : (float) (request.depthNear() + depth * (request.depthFar() - request.depthNear())));
                continue;
            }
            var fraction = (slot.pixels.get(2) & 0xff) / 255f;
            var background = high == 0xff && low == 0xff && (slot.pixels.get(2) & 0xff) == 0xff;
            latest = new Sample(request, background ? Float.NaN : high * 256f + low + fraction);
        }
    }

    boolean hasPendingWork() {
        if (pending != null) {
            return true;
        }
        for (var slot : slots) {
            if (slot.request != null) {
                return true;
            }
        }
        return false;
    }

    void invalidate() {
        pending = null;
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
        if (orthoDepth != null) {
            orthoDepth.close();
        }
        latest = null;
    }

    private BgfxOrthoPickDepth orthoDepth(BgfxTexture depth) {
        if (depth == null || orthoDepthFailed) {
            return null;
        }
        if (orthoDepth == null) {
            try {
                orthoDepth = new BgfxOrthoPickDepth(device);
            } catch (RuntimeException failure) {
                orthoDepthFailed = true;
                LogManager.getLogger(BgfxObjectPickerReadback.class)
                        .warn("The Top, Front and Side views cannot pick objects: {}", failure.toString());
                return null;
            }
        }
        return orthoDepth;
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
            throw new IllegalStateException("object picker readback is closed");
        }
    }

    private static int toPixel(float normalized, int extent) {
        return Math.max(0, Math.min(extent - 1, (int) Math.floor(normalized * extent)));
    }

    private static boolean matches(Request completed, Request current) {
        if (!completed.categories().equals(current.categories())
                || Math.abs(completed.ndcX() - current.ndcX()) > NDC_MATCH_EPSILON
                || Math.abs(completed.ndcY() - current.ndcY()) > NDC_MATCH_EPSILON
                || Float.floatToIntBits(completed.maxDistance()) != Float.floatToIntBits(current.maxDistance())
                || Float.floatToIntBits(completed.depthNear()) != Float.floatToIntBits(current.depthNear())
                || Float.floatToIntBits(completed.depthFar()) != Float.floatToIntBits(current.depthFar())) {
            return false;
        }
        var ox = completed.originX() - current.originX();
        var oy = completed.originY() - current.originY();
        var oz = completed.originZ() - current.originZ();
        if (ox * ox + oy * oy + oz * oz > ORIGIN_MATCH_EPSILON_SQUARED) {
            return false;
        }
        return completed.directionX() * current.directionX()
                        + completed.directionY() * current.directionY()
                        + completed.directionZ() * current.directionZ()
                >= DIRECTION_MATCH_DOT;
    }

    private static boolean hasReached(long frameNumber, long readyFrame) {
        return Long.compareUnsigned(frameNumber, readyFrame) >= 0;
    }

    record Request(
            long serial,
            float ndcX,
            float ndcY,
            float originX,
            float originY,
            float originZ,
            float directionX,
            float directionY,
            float directionZ,
            float viewDepthScale,
            float maxDistance,
            Set<Category> categories,
            float depthNear,
            float depthFar) {
        boolean ortho() {
            return !Float.isNaN(depthNear);
        }

        Vec3 origin() {
            return new Vec3(originX, originY, originZ);
        }

        Vec3 direction() {
            return new Vec3(directionX, directionY, directionZ);
        }
    }

    record Sample(Request request, float viewDepth) {
        boolean hasGeometry() {
            return Float.isFinite(viewDepth) && viewDepth >= 0f;
        }

        float rayDistance() {
            return viewDepth / request.viewDepthScale();
        }
    }

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
                    "object-picker/depth-staging-" + index,
                    1,
                    1,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    BGFX_TEXTURE_READ_BACK | BGFX_TEXTURE_BLIT_DST,
                    null);
            pixels = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        }
    }
}
