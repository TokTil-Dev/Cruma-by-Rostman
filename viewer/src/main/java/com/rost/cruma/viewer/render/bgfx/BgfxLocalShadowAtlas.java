package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_CLEAR_DEPTH;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_POINT;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_RT;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_clear;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;
import static org.lwjgl.bgfx.BGFX.bgfx_touch;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Light;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.api.RenderState;
import com.rost.cruma.viewer.render.bgfx.BgfxSceneMaterial.ProgramFamily;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class BgfxLocalShadowAtlas implements AutoCloseable {
    static final int MAX_LIGHTS = 2;
    static final int FACE_COUNT = 6;
    static final int MATRIX_FLOATS = MAX_LIGHTS * FACE_COUNT * 16;
    static final int PARAM_FLOATS = MAX_LIGHTS * 12;
    static final int SAMPLE_FLAGS =
            BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP | BGFX_SAMPLER_MIN_POINT | BGFX_SAMPLER_MAG_POINT;

    private static final int TILE_SIZE = 512;
    private static final int ATLAS_COLUMNS = 3;
    private static final int ATLAS_ROWS = MAX_LIGHTS * 2;
    private static final int ATLAS_WIDTH = TILE_SIZE * ATLAS_COLUMNS;
    private static final int ATLAS_HEIGHT = TILE_SIZE * ATLAS_ROWS;
    private static final float MIN_RADIUS = 384f;
    private static final float MAX_SELECTION_DISTANCE = 24_000f;
    private static final float SELECTION_RESCAN_DISTANCE = 1_024f;
    private static final float NEAR = 16f;

    private static final float[][] FACE_DIRECTIONS = {
        {1f, 0f, 0f},
        {-1f, 0f, 0f},
        {0f, 1f, 0f},
        {0f, -1f, 0f},
        {0f, 0f, 1f},
        {0f, 0f, -1f}
    };
    private static final float[][] FACE_UP = {
        {0f, 0f, 1f},
        {0f, 0f, 1f},
        {0f, 0f, 1f},
        {0f, 0f, 1f},
        {0f, 1f, 0f},
        {0f, 1f, 0f}
    };

    private final BgfxDevice device;
    private final Map<String, List<Light>> lightsByOwner = new HashMap<>();
    private final BgfxTexture atlasDepth;
    private final BgfxFramebuffer atlasFramebuffer;
    private final BgfxProgram entityDepthProgram;
    private final BgfxProgram entityMaskedProgram;
    private final BgfxProgram terrainDepthProgram;
    private final BgfxUniform shadowMaterial;
    private final BgfxUniform shadowDiffuse;
    private final BgfxUniform shadowOpacity;
    private final float[] materialData = new float[16];
    private final float[] faceView = new float[16];
    private final float[] faceProjection = new float[16];
    private final float[] matrices = new float[MATRIX_FLOATS];
    private final SelectedLight[] selected = new SelectedLight[MAX_LIGHTS];
    private long lightsRevision;
    private long renderedLightsRevision = Long.MIN_VALUE;
    private long renderedSceneRevision = Long.MIN_VALUE;
    private long selectionLightsRevision = Long.MIN_VALUE;
    private float selectionAnchorX;
    private float selectionAnchorY;
    private float selectionAnchorZ;
    private List<SelectedLight> selectionCache = List.of();
    private Snapshot snapshot = Snapshot.disabled();
    private boolean closed;

    BgfxLocalShadowAtlas(BgfxDevice device, BgfxShadowRenderer shared) {
        this.device = device;
        if (shared == null) {
            throw new UnsupportedOperationException("sun-shadow depth programs are unavailable");
        }
        if (!isSupported(device.getCapabilities())) {
            throw new UnsupportedOperationException(
                    "local shadows require a sampled depth-stencil atlas of " + ATLAS_WIDTH + "x" + ATLAS_HEIGHT);
        }
        var resources = device.getResources();
        BgfxTexture createdDepth = null;
        BgfxFramebuffer createdFramebuffer = null;
        try {
            createdDepth = BgfxTexture.create2D(
                    resources,
                    "custom-lighting/local-shadow-atlas",
                    ATLAS_WIDTH,
                    ATLAS_HEIGHT,
                    false,
                    1,
                    device.getCapabilities().depthStencilFormat(),
                    BGFX_TEXTURE_RT,
                    null);
            createdFramebuffer =
                    BgfxFramebuffer.create(resources, "custom-lighting/local-shadow-atlas-fb", List.of(createdDepth));
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(createdFramebuffer, failure);
            closeAfterFailure(createdDepth, failure);
            throw failure;
        }
        atlasDepth = createdDepth;
        atlasFramebuffer = createdFramebuffer;
        entityDepthProgram = shared.getEntityDepthProgram();
        entityMaskedProgram = shared.getEntityMaskedProgram();
        terrainDepthProgram = shared.getTerrainDepthProgram();
        shadowMaterial = shared.getShadowMaterial();
        shadowDiffuse = shared.getShadowDiffuse();
        shadowOpacity = shared.getShadowOpacity();
    }

    static boolean isSupported(BgfxCapabilities capabilities) {
        return capabilities.depthStencilSampled()
                && capabilities.maxTextureSize() >= Math.max(ATLAS_WIDTH, ATLAS_HEIGHT);
    }

    static void setLookAt(float[] target, Vec3 eye, float[] direction, float[] approximateUp) {
        var backward = normalize(-direction[0], -direction[1], -direction[2]);
        var right = normalize(cross(approximateUp, backward));
        var up = normalize(cross(backward, right));
        BgfxShadowRenderer.setView(target, right, up, backward, -dot(right, eye), -dot(up, eye), -dot(backward, eye));
    }

    static void setPerspectiveZeroToOne(float[] target, float near, float far) {
        Arrays.fill(target, 0f);
        target[0] = 1f;
        target[5] = 1f;
        target[10] = far / (near - far);
        target[11] = -1f;
        target[14] = far * near / (near - far);
    }

    void publish(String owner, List<Light> lights) {
        requireOpen();
        var stable = lights.stream()
                .filter(light -> !light.isAnimated() && light.radius() >= MIN_RADIUS)
                .toList();
        var previous = stable.isEmpty() ? lightsByOwner.remove(owner) : lightsByOwner.put(owner, stable);
        if (previous != null || !stable.isEmpty()) {
            lightsRevision++;
        }
    }

    void evict(Set<String> owners) {
        requireOpen();
        var changed = false;
        for (var owner : owners) {
            changed |= lightsByOwner.remove(owner) != null;
        }
        if (changed) {
            lightsRevision++;
        }
    }

    Snapshot render(
            List<BgfxSceneObject> objects,
            Predicate<BgfxSceneObject> hiddenObject,
            BgfxSceneUniforms sceneUniforms,
            BgfxViewAllocator views,
            Camera camera,
            RenderState rendering,
            float elapsedSeconds,
            long sceneRevision) {
        requireOpen();
        if (!rendering.isCustomLightingEffective()
                || !rendering.settings.isCustomLocalLightShadowsEnabled()
                || (camera.isOrtho() && !camera.isInteractiveOrtho())
                || lightsByOwner.isEmpty()) {
            snapshot = Snapshot.disabled();
            return snapshot;
        }
        var chosen = select(camera.getWorldFocusPosition());
        if (chosen.isEmpty()) {
            snapshot = Snapshot.disabled();
            return snapshot;
        }
        var selectionChanged = chosen.size() != activeCount();
        for (var index = 0; index < chosen.size() && !selectionChanged; index++) {
            selectionChanged = !chosen.get(index).light().equals(selected[index].light());
        }
        if (!selectionChanged
                && renderedLightsRevision == lightsRevision
                && renderedSceneRevision == sceneRevision
                && snapshot.available()) {
            return snapshot;
        }
        Arrays.fill(selected, null);
        Arrays.fill(matrices, 0f);
        for (var lightIndex = 0; lightIndex < chosen.size(); lightIndex++) {
            selected[lightIndex] = chosen.get(lightIndex);
            if (!renderLight(
                    lightIndex,
                    chosen.get(lightIndex).light(),
                    objects,
                    hiddenObject,
                    sceneUniforms,
                    views,
                    rendering,
                    elapsedSeconds)) {
                snapshot = Snapshot.disabled();
                return snapshot;
            }
        }
        var params = new float[PARAM_FLOATS];
        for (var index = 0; index < chosen.size(); index++) {
            var light = chosen.get(index).light();
            var positionOffset = index * 4;
            params[positionOffset] = light.position().getX();
            params[positionOffset + 1] = light.position().getY();
            params[positionOffset + 2] = light.position().getZ();
            params[positionOffset + 3] = light.radius();
            var colorOffset = MAX_LIGHTS * 4 + positionOffset;
            params[colorOffset] = light.color().getX();
            params[colorOffset + 1] = light.color().getY();
            params[colorOffset + 2] = light.color().getZ();
            params[colorOffset + 3] = 0.42f;
            var directionOffset = MAX_LIGHTS * 8 + positionOffset;
            params[directionOffset] = light.direction().getX();
            params[directionOffset + 1] = light.direction().getY();
            params[directionOffset + 2] = light.direction().getZ();
            params[directionOffset + 3] = isSpot(light) ? 1f - light.cone() / 256f : -1f;
        }
        snapshot = new Snapshot(
                true,
                atlasDepth,
                matrices.clone(),
                params,
                chosen.size(),
                ATLAS_WIDTH,
                ATLAS_HEIGHT,
                TILE_SIZE,
                device.getCapabilities().originBottomLeft());
        renderedLightsRevision = lightsRevision;
        renderedSceneRevision = sceneRevision;
        return snapshot;
    }

    Snapshot getSnapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        failure = closeAndMerge(atlasFramebuffer, failure);
        failure = closeAndMerge(atlasDepth, failure);
        if (failure != null) {
            throw failure;
        }
    }

    private boolean renderLight(
            int lightIndex,
            Light light,
            List<BgfxSceneObject> objects,
            Predicate<BgfxSceneObject> hiddenObject,
            BgfxSceneUniforms sceneUniforms,
            BgfxViewAllocator views,
            RenderState rendering,
            float elapsedSeconds) {
        for (var face = 0; face < FACE_COUNT; face++) {
            setLookAt(faceView, light.position(), FACE_DIRECTIONS[face], FACE_UP[face]);
            setPerspectiveZeroToOne(faceProjection, NEAR, Math.max(NEAR + 1f, light.radius()));
            BgfxShadowRenderer.multiply(faceProjection, faceView, matrices, (lightIndex * FACE_COUNT + face) * 16);
            BgfxMatrices.adaptProjection(
                    faceProjection, device.getCapabilities().homogeneousDepth());
            var view = views.allocate(
                    "custom-local-shadow-" + lightIndex + "-" + face, BgfxViewAllocator.Domain.RENDER, true);
            bgfx_set_view_name(view, "custom-local-shadow-" + lightIndex + "-" + face);
            var tileX = face % ATLAS_COLUMNS;
            var tileY = lightIndex * 2 + face / ATLAS_COLUMNS;
            bgfx_set_view_rect(view, tileX * TILE_SIZE, tileY * TILE_SIZE, TILE_SIZE, TILE_SIZE);
            bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
            bgfx_set_view_frame_buffer(view, atlasFramebuffer.getHandle());
            bgfx_set_view_clear(view, BGFX_CLEAR_DEPTH, 0, 1f, 0);
            bgfx_set_view_transform(view, faceView, faceProjection);
            bgfx_touch(view);
            if (!submitFace(view, light, objects, hiddenObject, sceneUniforms, rendering, elapsedSeconds)) {
                return false;
            }
        }
        return true;
    }

    private boolean submitFace(
            int view,
            Light light,
            List<BgfxSceneObject> objects,
            Predicate<BgfxSceneObject> hiddenObject,
            BgfxSceneUniforms sceneUniforms,
            RenderState rendering,
            float elapsedSeconds) {
        for (var object : objects) {
            if (hiddenObject.test(object)
                    || object.getStaticMeshLod() != null
                            && object.getStaticMeshLod().level() > 0
                    || !overlaps(object.getWorldBounds(), light.position(), light.radius())) {
                continue;
            }
            var geometry = object.getGeometry();
            for (var ordinal = 0; ordinal < geometry.getPrimitives().size(); ordinal++) {
                if (!overlaps(object.getPrimitiveBounds(ordinal), light.position(), light.radius())) {
                    continue;
                }
                var primitive = geometry.getPrimitives().get(ordinal);
                var material = primitive.entityMaterial();
                var surfaceType = material == null ? SurfaceType.TERRAIN.getId() : material.getSurfaceType();
                if (!BgfxSceneRenderer.isSurfaceEnabled(surfaceType, rendering.settings)
                        || material != null && !castsShadow(material)) {
                    continue;
                }
                var masked = material != null
                        && (material.getBlendMode() == BlendMode.MASKED
                                || material.getBlendMode() == BlendMode.MASKED_BLEND);
                var uniformBytes = material == null
                        ? BgfxSubmissionBudget.TERRAIN_SHADOW_DRAW_BYTES
                        : masked
                                ? BgfxSubmissionBudget.MASKED_SHADOW_DRAW_BYTES
                                : BgfxSubmissionBudget.ENTITY_SHADOW_DRAW_BYTES;
                if (!device.getSubmissionBudget().reserve(BgfxSubmissionBudget.Domain.SHADOW, uniformBytes, 1)) {
                    return false;
                }
                geometry.bind(primitive.indexOffset(), primitive.indexCount());
                if (material == null) {
                    sceneUniforms.bindTerrainModel(object.getTerrainModel());
                    bgfx_set_state(BgfxSceneState.terrainShadow(), 0);
                    bgfx_submit(view, terrainDepthProgram.getHandle(), 0, BGFX_DISCARD_ALL);
                    continue;
                }
                object.bindInstances();
                if (masked) {
                    material.bindShadow(
                            shadowMaterial,
                            shadowDiffuse,
                            shadowOpacity,
                            materialData,
                            elapsedSeconds,
                            rendering.settings.isTexturesEnabled(),
                            object.isFoliage());
                }
                bgfx_set_state(BgfxSceneState.shadow(material), 0);
                bgfx_submit(
                        view,
                        masked ? entityMaskedProgram.getHandle() : entityDepthProgram.getHandle(),
                        0,
                        BGFX_DISCARD_ALL);
            }
        }
        return true;
    }

    private List<SelectedLight> select(Vec3 camera) {
        var anchorDx = camera.getX() - selectionAnchorX;
        var anchorDy = camera.getY() - selectionAnchorY;
        var anchorDz = camera.getZ() - selectionAnchorZ;
        var rescanDistanceSquared = (double) SELECTION_RESCAN_DISTANCE * SELECTION_RESCAN_DISTANCE;
        if (selectionLightsRevision == lightsRevision
                && (double) anchorDx * anchorDx + (double) anchorDy * anchorDy + (double) anchorDz * anchorDz
                        <= rescanDistanceSquared) {
            return selectionCache;
        }
        var best = new SelectedLight[MAX_LIGHTS];
        for (var lights : lightsByOwner.values()) {
            for (var light : lights) {
                var dx = light.position().getX() - camera.getX();
                var dy = light.position().getY() - camera.getY();
                var dz = light.position().getZ() - camera.getZ();
                var distanceSquared = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                var reach = light.radius() + MAX_SELECTION_DISTANCE;
                if (distanceSquared > (double) reach * reach) {
                    continue;
                }
                var luminance = 0.2126f * light.color().getX()
                        + 0.7152f * light.color().getY()
                        + 0.0722f * light.color().getZ();
                if (luminance <= 0.01f) {
                    continue;
                }
                var score = luminance * light.radius() * light.radius() / Math.max(distanceSquared, 1.0);
                insert(best, new SelectedLight(light, score));
            }
        }
        var result = new ArrayList<SelectedLight>(MAX_LIGHTS);
        for (var value : best) {
            if (value != null) {
                result.add(value);
            }
        }
        selectionAnchorX = camera.getX();
        selectionAnchorY = camera.getY();
        selectionAnchorZ = camera.getZ();
        selectionLightsRevision = lightsRevision;
        selectionCache = List.copyOf(result);
        return selectionCache;
    }

    private int activeCount() {
        var count = 0;
        for (var value : selected) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("local shadow atlas is closed");
        }
    }

    private static void insert(SelectedLight[] best, SelectedLight candidate) {
        for (var index = 0; index < best.length; index++) {
            if (best[index] == null || isPreferred(candidate, best[index])) {
                for (var move = best.length - 1; move > index; move--) {
                    best[move] = best[move - 1];
                }
                best[index] = candidate;
                return;
            }
        }
    }

    private static boolean isPreferred(SelectedLight candidate, SelectedLight current) {
        var scoreOrder = Double.compare(candidate.score(), current.score());
        if (scoreOrder != 0) {
            return scoreOrder > 0;
        }
        return compareAuthoredLight(candidate.light(), current.light()) < 0;
    }

    private static int compareAuthoredLight(Light left, Light right) {
        var order = Float.compare(left.position().getX(), right.position().getX());
        if (order == 0) {
            order = Float.compare(left.position().getY(), right.position().getY());
        }
        if (order == 0) {
            order = Float.compare(left.position().getZ(), right.position().getZ());
        }
        if (order == 0) {
            order = Float.compare(left.radius(), right.radius());
        }
        if (order == 0) {
            order = Float.compare(left.color().getX(), right.color().getX());
        }
        if (order == 0) {
            order = Float.compare(left.color().getY(), right.color().getY());
        }
        if (order == 0) {
            order = Float.compare(left.color().getZ(), right.color().getZ());
        }
        if (order == 0) {
            order = Integer.compare(left.lightEffect(), right.lightEffect());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getX(), right.direction().getX());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getY(), right.direction().getY());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getZ(), right.direction().getZ());
        }
        if (order == 0) {
            order = Integer.compare(left.cone(), right.cone());
        }
        return order;
    }

    private static boolean castsShadow(BgfxSceneMaterial material) {
        if ((material.getSurfaceType() & SurfaceType.FAKE_BACKDROP.getId()) != 0
                || material.getProgramFamily() == ProgramFamily.WATER
                || material.getBlendMode() == BlendMode.INVISIBLE) {
            return false;
        }
        return switch (material.getBlendMode()) {
            case OPAQUE, MASKED, MASKED_BLEND, UNLIT -> true;
            default -> false;
        };
    }

    private static boolean isSpot(Light light) {
        return light.lightEffect() == 8 || light.lightEffect() == 12;
    }

    private static boolean overlaps(Bounds3f bounds, Vec3 center, float radius) {
        var dx = axisDistance(center.getX(), bounds.minX(), bounds.maxX());
        var dy = axisDistance(center.getY(), bounds.minY(), bounds.maxY());
        var dz = axisDistance(center.getZ(), bounds.minZ(), bounds.maxZ());
        return (double) dx * dx + (double) dy * dy + (double) dz * dz <= (double) radius * radius;
    }

    private static float axisDistance(float value, float minimum, float maximum) {
        return value < minimum ? minimum - value : value > maximum ? value - maximum : 0f;
    }

    private static float[] cross(float[] left, float[] right) {
        return new float[] {
            left[1] * right[2] - left[2] * right[1],
            left[2] * right[0] - left[0] * right[2],
            left[0] * right[1] - left[1] * right[0]
        };
    }

    private static float dot(float[] vector, Vec3 point) {
        return vector[0] * point.getX() + vector[1] * point.getY() + vector[2] * point.getZ();
    }

    private static float[] normalize(float[] value) {
        return normalize(value[0], value[1], value[2]);
    }

    private static float[] normalize(float x, float y, float z) {
        var length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-7f) {
            return new float[] {1f, 0f, 0f};
        }
        return new float[] {x / length, y / length, z / length};
    }

    private static RuntimeException closeAndMerge(AutoCloseable value, RuntimeException failure) {
        if (value == null) {
            return failure;
        }
        try {
            value.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        } catch (Exception exception) {
            var wrapped = new IllegalStateException("failed to close local-shadow resource", exception);
            if (failure == null) {
                return wrapped;
            }
            failure.addSuppressed(wrapped);
        }
        return failure;
    }

    private static void closeAfterFailure(AutoCloseable value, Throwable failure) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private record SelectedLight(Light light, double score) {}

    record Snapshot(
            boolean available,
            BgfxTexture atlasDepth,
            float[] matrices,
            float[] lightParams,
            int lightCount,
            int atlasWidth,
            int atlasHeight,
            int tileSize,
            boolean originBottomLeft) {
        private static final Snapshot DISABLED =
                new Snapshot(false, null, new float[0], new float[0], 0, 0, 0, 0, false);

        static Snapshot disabled() {
            return DISABLED;
        }
    }
}
