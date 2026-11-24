package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;

import com.rost.cruma.core.world.NativeWaterReflection;
import com.rost.cruma.core.world.WaterPlane;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneGeometry.Primitive;
import glm_.vec3.Vec3;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.BufferUtils;

final class BgfxReflectionCache implements AutoCloseable {
    private static final Extent ZERO_EXTENT = new Extent(0, 0);
    private final BgfxResourceRegistry resources;

    private final int depthFormat;

    private final Map<Key, Entry> entries = new LinkedHashMap<>();

    BgfxReflectionCache(BgfxResourceRegistry resources, int depthFormat) {
        this.resources = resources;
        this.depthFormat = depthFormat;
    }

    static Key keyFor(Primitive primitive) {
        return keyFor(primitive, 0L);
    }

    static Key keyFor(Primitive primitive, long sharingScope) {
        var plane = primitive.reflectionPlane();
        if (plane == null || primitive.waterSheetSource() == null) {
            return null;
        }
        var fog = sharingScope == 0L ? null : primitive.distanceFog();
        var source = primitive.waterSheetSource();
        return new Key(
                plane,
                fog == null
                        ? null
                        : new FogKey(
                                fog.color().getX(),
                                fog.color().getY(),
                                fog.color().getZ(),
                                fog.start(),
                                fog.end()),
                sharingScope,
                source.frontZone(plane),
                source.authoredPlaneX(),
                source.authoredPlaneY(),
                source.authoredPlaneZ(),
                source.authoredPlaneW());
    }

    record Key(
            WaterPlane plane,
            FogKey fog,
            long sharingScope,
            int viewZone,
            float authoredPlaneX,
            float authoredPlaneY,
            float authoredPlaneZ,
            float authoredPlaneW) {
        boolean usesNativeSharedTarget() {
            return authoredPlaneW > -3870f && authoredPlaneW < -3670f;
        }
    }

    record FogKey(float red, float green, float blue, float start, float end) {}

    final class Entry implements AutoCloseable {
        private final Key key;
        private final FloatBuffer reflectedView = BufferUtils.createFloatBuffer(16);
        private final float[] sourceView = new float[16];
        private final NativeWaterReflection reflection;
        private final float[] product = new float[16];
        private final float[] samplingViewProjection = new float[16];

        private Extent extent = ZERO_EXTENT;
        private Extent capacity = ZERO_EXTENT;
        private BgfxTexture color;
        private BgfxTexture depth;
        private BgfxFramebuffer framebuffer;
        private boolean valid;
        private boolean initialized;

        private Entry(Key key) {
            this.key = key;
            reflection = new NativeWaterReflection(
                    key.authoredPlaneX(), key.authoredPlaneY(), key.authoredPlaneZ(), key.authoredPlaneW());
        }

        void markRendered(FloatBuffer renderedView, FloatBuffer renderedProjection) {
            for (var index = 0; index < 16; index++) {
                sourceView[index] = renderedView.get(index);
                product[index] = renderedProjection.get(index);
            }
            multiply(product, sourceView, samplingViewProjection);
            valid = true;
            initialized = true;
        }

        void markCleared() {
            initialized = true;
        }

        boolean hasContent() {
            return initialized;
        }

        float[] getSamplingViewProjection() {
            return valid ? samplingViewProjection : null;
        }

        FloatBuffer getReflectedView(Camera camera) {
            var view = camera.getViewMatrix();
            for (var index = 0; index < 16; index++) {
                sourceView[index] = view.get(index);
            }
            reflection.composeViewMatrix(sourceView, product);
            reflectedView.clear();
            reflectedView.put(product).flip();
            return reflectedView;
        }

        Vec3 getReflectedCamera(Camera camera) {
            return reflection.reflect(camera.getPosition());
        }

        Key getKey() {
            return key;
        }

        Extent getExtent() {
            return extent;
        }

        BgfxTexture getColor() {
            return color;
        }

        BgfxFramebuffer getFramebuffer() {
            return framebuffer;
        }

        @Override
        public void close() {
            closeTargets();
        }

        private void ensure(Extent requested) {
            if (requested.equals(extent)) {
                return;
            }
            if (color != null && capacity.width() >= requested.width() && capacity.height() >= requested.height()) {
                extent = requested;
                valid = false;
                return;
            }
            var requestedCapacity = new Extent(
                    Math.max(capacity.width(), requested.width()), Math.max(capacity.height(), requested.height()));
            BgfxTexture nextColor = null;
            BgfxTexture nextDepth = null;
            BgfxFramebuffer nextFramebuffer = null;
            try {
                nextColor = BgfxTexture.create2D(
                        resources,
                        label("color"),
                        requestedCapacity.width(),
                        requestedCapacity.height(),
                        false,
                        1,
                        BGFX_TEXTURE_FORMAT_RGBA8,
                        BGFX_TEXTURE_RT,
                        null);
                nextDepth = BgfxTexture.create2D(
                        resources,
                        label("depth"),
                        requestedCapacity.width(),
                        requestedCapacity.height(),
                        false,
                        1,
                        depthFormat,
                        BGFX_TEXTURE_RT,
                        null);
                nextFramebuffer =
                        BgfxFramebuffer.create(resources, label("framebuffer"), List.of(nextColor, nextDepth));
            } catch (RuntimeException | Error e) {
                closeAfterFailure(nextFramebuffer, e);
                closeAfterFailure(nextDepth, e);
                closeAfterFailure(nextColor, e);
                throw e;
            }
            var oldColor = color;
            var oldDepth = depth;
            var oldFramebuffer = framebuffer;
            color = nextColor;
            depth = nextDepth;
            framebuffer = nextFramebuffer;
            extent = requested;
            capacity = requestedCapacity;
            valid = false;
            initialized = false;
            RuntimeException failure = null;
            failure = closeAndMerge(oldFramebuffer, failure);
            failure = closeAndMerge(oldDepth, failure);
            failure = closeAndMerge(oldColor, failure);
            if (failure != null) {
                throw failure;
            }
        }

        private void closeTargets() {
            RuntimeException failure = null;
            failure = closeAndMerge(framebuffer, failure);
            framebuffer = null;
            failure = closeAndMerge(depth, failure);
            depth = null;
            failure = closeAndMerge(color, failure);
            color = null;
            extent = ZERO_EXTENT;
            capacity = ZERO_EXTENT;
            valid = false;
            initialized = false;
            if (failure != null) {
                throw failure;
            }
        }

        private String label(String suffix) {
            var plane = key.plane;
            return "reflection/"
                    + Integer.toUnsignedString(key.hashCode(), 16)
                    + '/'
                    + plane.x()
                    + '-'
                    + plane.y()
                    + '-'
                    + plane.z()
                    + '/'
                    + suffix;
        }
    }

    static void multiply(float[] left, float[] right, float[] target) {
        for (var column = 0; column < 4; column++) {
            for (var row = 0; row < 4; row++) {
                var value = 0f;
                for (var index = 0; index < 4; index++) {
                    value += left[index * 4 + row] * right[column * 4 + index];
                }
                target[column * 4 + row] = value;
            }
        }
    }

    Entry acquire(Key key, Extent extent) {
        var entry = entries.computeIfAbsent(key, ignored -> new Entry(key));
        entry.ensure(extent);
        return entry;
    }

    int size() {
        return entries.size();
    }

    void retain(Set<Key> retained) {
        if (retained == null) {
            throw new IllegalArgumentException("retained reflection keys are required");
        }
        RuntimeException failure = null;
        for (var iterator = entries.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (retained.contains(entry.getKey())) {
                continue;
            }
            iterator.remove();
            failure = closeAndMerge(entry.getValue(), failure);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var entry : entries.values()) {
            try {
                entry.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        entries.clear();
        if (failure != null) {
            throw failure;
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
            var wrapped = new IllegalStateException("failed to close reflection target", e);
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
