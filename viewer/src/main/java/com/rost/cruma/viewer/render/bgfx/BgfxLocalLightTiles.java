package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.rost.cruma.core.world.Light;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import glm_.vec3.Vec3;
import java.nio.ByteBuffer;
import java.util.List;

final class BgfxLocalLightTiles implements AutoCloseable {
    static final int TILE_SIZE = 32;
    static final int TILE_LIGHT_CAPACITY = 8;
    static final int OVERFLOW_CODE = 255;

    private final BgfxResourceRegistry resources;
    private final float[] topLeft = new float[3];
    private final float[] topRight = new float[3];
    private final float[] bottomRight = new float[3];
    private final float[] bottomLeft = new float[3];
    private final float[] forwardRay = new float[3];
    private final float[] centerRay = new float[3];
    private final float[] planes = new float[12];
    private final int[] selected = new int[TILE_LIGHT_CAPACITY];
    private int capacityWidth;
    private int capacityHeight;
    private BgfxTexture firstIndices;
    private BgfxTexture secondIndices;
    private ByteBuffer firstPayload;
    private ByteBuffer secondPayload;
    private boolean closed;

    BgfxLocalLightTiles(BgfxResourceRegistry resources) {
        this.resources = resources;
    }

    static int selectTile(List<Light> lights, Vec3 camera, float[] forward, float[] inwardPlanes, int[] target) {
        var count = 0;
        for (var index = 0; index < lights.size(); index++) {
            var light = lights.get(index);
            var dx = light.position().getX() - camera.getX();
            var dy = light.position().getY() - camera.getY();
            var dz = light.position().getZ() - camera.getZ();
            if (!doesSphereIntersect(dx, dy, dz, light.radius(), forward, inwardPlanes)) {
                continue;
            }
            if (count == TILE_LIGHT_CAPACITY) {
                return TILE_LIGHT_CAPACITY + 1;
            }
            target[count++] = index;
        }
        return count;
    }

    static boolean doesSphereIntersect(
            float dx, float dy, float dz, float radius, float[] forward, float[] inwardPlanes) {
        if (dot(forward, 0, dx, dy, dz) < -radius) {
            return false;
        }
        for (var offset = 0; offset < 12; offset += 3) {
            if (dot(inwardPlanes, offset, dx, dy, dz) < -radius) {
                return false;
            }
        }
        return true;
    }

    Snapshot update(Camera camera, Extent extent, BgfxLocalLightSet.Snapshot lights, boolean enabled) {
        requireOpen();
        if (!enabled || camera.isOrtho() || extent.isZero() || lights == null || !lights.available()) {
            return Snapshot.disabled();
        }
        var tileWidth = divideRoundUp(extent.width(), TILE_SIZE);
        var tileHeight = divideRoundUp(extent.height(), TILE_SIZE);
        ensure(tileWidth, tileHeight);
        var payloadBytes = Math.multiplyExact(Math.multiplyExact(tileWidth, tileHeight), 4);
        clear(firstPayload, payloadBytes);
        clear(secondPayload, payloadBytes);

        camera.writeCursorRayDirection(0f, 0f, forwardRay);
        var cameraPosition = camera.getPosition();
        var authored = lights.lights();
        for (var tileY = 0; tileY < tileHeight; tileY++) {
            var top = 1f - 2f * (tileY * TILE_SIZE) / extent.height();
            var bottom = 1f - 2f * Math.min((tileY + 1) * TILE_SIZE, extent.height()) / extent.height();
            for (var tileX = 0; tileX < tileWidth; tileX++) {
                var left = 2f * (tileX * TILE_SIZE) / extent.width() - 1f;
                var right = 2f * Math.min((tileX + 1) * TILE_SIZE, extent.width()) / extent.width() - 1f;
                camera.writeCursorRayDirection(left, top, topLeft);
                camera.writeCursorRayDirection(right, top, topRight);
                camera.writeCursorRayDirection(right, bottom, bottomRight);
                camera.writeCursorRayDirection(left, bottom, bottomLeft);
                camera.writeCursorRayDirection((left + right) * 0.5f, (top + bottom) * 0.5f, centerRay);
                setPlane(planes, 0, topLeft, topRight, centerRay);
                setPlane(planes, 3, topRight, bottomRight, centerRay);
                setPlane(planes, 6, bottomRight, bottomLeft, centerRay);
                setPlane(planes, 9, bottomLeft, topLeft, centerRay);
                var count = selectTile(authored, cameraPosition, forwardRay, planes, selected);
                var offset = (tileY * tileWidth + tileX) * 4;
                if (count > TILE_LIGHT_CAPACITY) {
                    firstPayload.put(offset, (byte) OVERFLOW_CODE);
                    continue;
                }
                for (var slot = 0; slot < count; slot++) {
                    var code = (byte) (selected[slot] + 1);
                    if (slot < 4) {
                        firstPayload.put(offset + slot, code);
                    } else {
                        secondPayload.put(offset + slot - 4, code);
                    }
                }
            }
        }
        firstIndices.update2D(0, 0, 0, 0, tileWidth, tileHeight, uploadView(firstPayload, payloadBytes), tileWidth * 4);
        secondIndices.update2D(
                0, 0, 0, 0, tileWidth, tileHeight, uploadView(secondPayload, payloadBytes), tileWidth * 4);
        return new Snapshot(firstIndices, secondIndices, 1f / capacityWidth, 1f / capacityHeight);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(secondIndices, failure);
        secondIndices = null;
        failure = closeAndMerge(firstIndices, failure);
        firstIndices = null;
        if (failure != null) {
            throw failure;
        }
    }

    private void ensure(int width, int height) {
        if (capacityWidth >= width && capacityHeight >= height) {
            ensurePayloadCapacity(Math.multiplyExact(Math.multiplyExact(width, height), 4));
            return;
        }
        var nextWidth = Math.max(capacityWidth, width);
        var nextHeight = Math.max(capacityHeight, height);
        BgfxTexture createdFirst = null;
        BgfxTexture createdSecond = null;
        try {
            createdFirst = BgfxTexture.create2D(
                    resources,
                    "custom-lighting/local-tiles-0",
                    nextWidth,
                    nextHeight,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    0L,
                    null);
            createdSecond = BgfxTexture.create2D(
                    resources,
                    "custom-lighting/local-tiles-1",
                    nextWidth,
                    nextHeight,
                    false,
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    0L,
                    null);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdSecond, failure);
            closeAfterFailure(createdFirst, failure);
            throw failure;
        }
        var oldFirst = firstIndices;
        var oldSecond = secondIndices;
        firstIndices = createdFirst;
        secondIndices = createdSecond;
        capacityWidth = nextWidth;
        capacityHeight = nextHeight;
        ensurePayloadCapacity(Math.multiplyExact(Math.multiplyExact(width, height), 4));
        RuntimeException closeFailure = null;
        closeFailure = closeAndMerge(oldSecond, closeFailure);
        closeFailure = closeAndMerge(oldFirst, closeFailure);
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void ensurePayloadCapacity(int bytes) {
        if (firstPayload != null && firstPayload.capacity() >= bytes) {
            return;
        }
        firstPayload = ByteBuffer.allocateDirect(bytes);
        secondPayload = ByteBuffer.allocateDirect(bytes);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("local-light tiles are closed");
        }
    }

    private static void setPlane(float[] target, int offset, float[] first, float[] second, float[] inside) {
        var x = first[1] * second[2] - first[2] * second[1];
        var y = first[2] * second[0] - first[0] * second[2];
        var z = first[0] * second[1] - first[1] * second[0];
        var inverseLength = 1f / Math.max((float) Math.sqrt(x * x + y * y + z * z), 0.000001f);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;
        if (x * inside[0] + y * inside[1] + z * inside[2] < 0f) {
            x = -x;
            y = -y;
            z = -z;
        }
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
    }

    private static float dot(float[] vector, int offset, float x, float y, float z) {
        return vector[offset] * x + vector[offset + 1] * y + vector[offset + 2] * z;
    }

    private static int divideRoundUp(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static void clear(ByteBuffer buffer, int bytes) {
        for (var index = 0; index < bytes; index++) {
            buffer.put(index, (byte) 0);
        }
    }

    private static ByteBuffer uploadView(ByteBuffer source, int bytes) {
        var view = source.duplicate();
        view.position(0);
        view.limit(bytes);
        return view;
    }

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("failed to close local-light tile resource", exception);
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

    record Snapshot(BgfxTexture first, BgfxTexture second, float inverseWidth, float inverseHeight) {
        private static final Snapshot DISABLED = new Snapshot(null, null, 0f, 0f);

        static Snapshot disabled() {
            return DISABLED;
        }

        boolean available() {
            return first != null && second != null;
        }
    }
}
