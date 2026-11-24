package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.geodata.GeoDataChunk;
import com.rost.cruma.viewer.geodata.GeoDataChunk.Place;
import com.rost.cruma.viewer.geodata.GeoDataDisplay;
import com.rost.cruma.viewer.geodata.GeoDataMesh;
import com.rost.cruma.viewer.geodata.GeoDataOverlayLoader;
import com.rost.cruma.viewer.geodata.GeoDataViewFocus;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.render.LoadedTiles;
import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import com.rost.cruma.viewer.render.bgfx.BgfxViewAllocator.Domain;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.bgfx.BGFX;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

final class BgfxGeodataRenderer implements AutoCloseable {
    static final float DEPTH_TOLERANCE = 40;

    static final float HIDDEN_OPACITY = .2f;

    static final int UPLOAD_CORNERS_PER_FRAME = 120_000;

    private static final BgfxVertexLayoutSpec LAYOUT = BgfxVertexLayoutSpec.builder()
            .add(BGFX.BGFX_ATTRIB_POSITION, 3, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX.BGFX_ATTRIB_COLOR0, 4, BGFX.BGFX_ATTRIB_TYPE_UINT8, true, false)
            .add(BGFX.BGFX_ATTRIB_TEXCOORD1, 4, BGFX.BGFX_ATTRIB_TYPE_FLOAT)
            .build();
    private static final int CLAMP_POINT = BGFX.BGFX_SAMPLER_U_CLAMP
            | BGFX.BGFX_SAMPLER_V_CLAMP
            | BGFX.BGFX_SAMPLER_MIN_POINT
            | BGFX.BGFX_SAMPLER_MAG_POINT;

    private static final int[] CORNERS = {0, 1, 2, 5};

    private final BgfxDevice device;
    private final GeoDataOverlayLoader loader = new GeoDataOverlayLoader();
    private final GeoDataOverlayLoader comparisonLoader = new GeoDataOverlayLoader();
    private GeoDataOverlayLoader visibleLoader = loader;
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final float[] projectionTerms = new float[16];
    private BgfxProgram program;
    private BgfxUniform viewUniform;
    private BgfxUniform heightUniform;
    private BgfxUniform projectionUniform;
    private BgfxUniform styleUniform;
    private BgfxUniform focusUniform;
    private BgfxUniform hoverUniform;
    private BgfxUniform selectUniform;
    private BgfxUniform depthSampler;
    private BgfxUniform modelDepthSampler;
    private final Map<Place, Resident> residents = new HashMap<>();
    private final List<Resident> drawn = new ArrayList<>();
    private List<GeoDataChunk> residentChunks = List.of();
    private BgfxBuffer forward;
    private BgfxBuffer backward;
    private int patternQuads;
    private BgfxFramebuffer framebuffer;
    private BgfxTexture color;
    private BgfxTexture depth;
    private boolean active;
    private boolean lastFromAbove = true;
    private long reloadRevision;
    private long missingRevision;
    private long nextFocusUpdate;
    private GeoDataAccess focusData;
    private GeoDataDisplay focusDisplay;
    private final BgfxOverlayLinePass linePass;

    private final float[] meshProjection = new float[4];
    private final float[] meshFocus = new float[4];
    private final float[] meshView = new float[4];
    private final float[] meshHeight = new float[4];
    private float[] meshStyle = new float[4];
    private float[] meshHover = new float[4];
    private float[] meshSelect = new float[4];

    BgfxGeodataRenderer(BgfxDevice device) {
        this.device = device;
        this.linePass = new BgfxOverlayLinePass(device);
    }

    static float[] styleFor(GeoDataDisplay display) {
        var grid = display.localGrid() ? 1f : 0f;
        var radius = display.localGrid() ? display.localGridRadius() : 0f;
        return switch (display.mode()) {
            case EXACT_GRID -> new float[] {.2f, 1, 0, .26f};
            case SURFACE -> new float[] {.3f, grid, radius, .34f};
            case CONTOURS -> new float[] {0, grid, radius, .28f};
        };
    }

    void update(GeodataState state, Camera camera) {
        state.guardSourceChange();
        if (missingRevision != state.getMissingRevision()) {
            missingRevision = state.getMissingRevision();
            loader.refreshMissing();
        }
        if (reloadRevision != state.getReloadRevision()) {
            reloadRevision = state.getReloadRevision();
            loader.reset();
            comparisonLoader.reset();
            releaseGeometry();
            releaseFramebuffer();
        }
        if (!state.enabled && !state.toolsOpen) {
            if (active) {
                loader.reset();
                comparisonLoader.reset();
                releaseGeometry();
                releaseFramebuffer();
            }
            active = false;
            state.data = null;
            state.loading = false;
            state.viewFocus = null;
            state.viewFocusOnLayer = false;
            focusData = null;
            focusDisplay = null;
            state.status = "Off";
            state.displayLimitReached = false;
            return;
        }
        active = true;
        var comparison = state.getComparisonDisplay();
        updateFocus(state, camera, comparison == null ? state.queryData() : comparison);
        var position = state.viewFocus;
        if (state.surfaceFilter != null && !state.surfaceFilter.belongsTo(state.queryData())) {
            state.surfaceFilter = null;
        }
        var displaySurface = state.surfaceFilter;
        if (displaySurface != null && state.editing == null) {
            displaySurface = displaySurface.forDisplay(state.data, state.getSourceSnapshot());
        }
        visibleLoader = comparison == null ? loader : comparisonLoader;
        visibleLoader.update(
                comparison == null ? state.path : comparison.getSourcePath(),
                (float) position.x(),
                (float) position.y(),
                state.radius,
                state.display.geometryOptions().withSurface(comparison == null ? displaySurface : null),
                comparison != null ? comparison : state.editing == null ? null : state.getSourceSnapshot(),
                comparison != null ? comparison : state.editing == null ? null : state.queryData(),
                state.vertexLimit);
        var snapshot = visibleLoader.getSnapshot();
        state.status = (comparison == null ? "" : "Viewing source B | ") + snapshot.status();
        state.loading = snapshot.loading();
        state.displayLimitReached = snapshot.truncated();
        state.displayLayers = snapshot.layerCount();
        if (comparison == null) {
            state.data = snapshot.data();
        }
        updateFocus(state, camera, comparison == null ? state.queryData() : comparison);
        state.inspection.update(state.queryData());
    }

    void cameraRestored() {
        nextFocusUpdate = 0;
    }

    void render(
            GeodataState state,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture targetDepth,
            Extent extent,
            BgfxViewAllocator views) {
        render(state, camera, targetColor, targetDepth, null, extent, views);
    }

    void render(
            GeodataState state,
            Camera camera,
            BgfxTexture targetColor,
            BgfxTexture targetDepth,
            @Nullable BgfxTexture modelDepth,
            Extent extent,
            BgfxViewAllocator views) {
        if (!state.enabled || !active) {
            return;
        }
        var snapshot = visibleLoader.getSnapshot();
        state.status = (state.getComparisonDisplay() == null ? "" : "Viewing source B | ") + snapshot.status();
        var softDepth = targetColor != null && targetDepth != null;
        var lines = state.lines.getShownCount() > 0 && state.getComparisonDisplay() == null;
        if (snapshot.chunks().isEmpty()) {
            releaseGeometry();
            if (!lines) {
                releaseFramebuffer();
                return;
            }
        }
        createProgram();
        var direction = camera.getCursorRayDirection(0, 0);
        var fromAbove = direction.getZ() < -.02f || direction.getZ() <= .02f && lastFromAbove;
        lastFromAbove = fromAbove;
        if (!snapshot.chunks().isEmpty()) {
            upload(snapshot.chunks());
            collect(camera, state.tiles);
        }
        ensureFramebuffer(targetColor, softDepth ? null : targetDepth);
        var view = views.allocate("geodata", Domain.RENDER, true);
        BGFX.bgfx_set_view_mode(view, BGFX.BGFX_VIEW_MODE_SEQUENTIAL);
        BGFX.bgfx_set_view_frame_buffer(view, framebuffer == null ? BGFX.BGFX_INVALID_HANDLE : framebuffer.getHandle());
        BGFX.bgfx_set_view_rect(view, 0, 0, extent.width(), extent.height());
        BGFX.bgfx_set_view_transform(
                view,
                BgfxMatrices.firstSixteenFloats(camera.getViewMatrix()),
                BgfxMatrices.toBackendProjection(
                        camera.getProjectionMatrix(), device.getCapabilities().homogeneousDepth(), projection));
        var flags = BGFX.BGFX_STATE_WRITE_RGB
                | BGFX.BGFX_STATE_WRITE_A
                | BGFX.BGFX_STATE_BLEND_ALPHA
                | BGFX.BGFX_STATE_MSAA;
        var showSelection = state.toolsOpen && state.getComparisonDisplay() == null;
        var models = softDepth ? modelDepth : null;
        try (var stack = MemoryStack.stackPush()) {
            if (!drawn.isEmpty()) {
                submitMesh(
                        view,
                        state,
                        camera,
                        extent,
                        targetDepth,
                        models,
                        softDepth,
                        fromAbove,
                        showSelection,
                        flags,
                        stack);
            }
            if (lines) {
                linePass.submit(view, camera, extent, softDepth ? targetDepth : null, models, state.lines, 1, stack);
            }
        }
    }

    @Override
    public void close() {
        loader.close();
        comparisonLoader.close();
        releaseGeometry();
        releaseFramebuffer();
        if (program != null) {
            program.close();
            program = null;
        }
        linePass.close();
        for (var uniform : new BgfxUniform[] {
            viewUniform,
            heightUniform,
            projectionUniform,
            styleUniform,
            focusUniform,
            hoverUniform,
            selectUniform,
            depthSampler,
            modelDepthSampler
        }) {
            if (uniform != null) {
                uniform.close();
            }
        }
    }

    private void updateFocus(GeodataState state, Camera camera, GeoDataAccess data) {
        var display = state.display
                .geometryOptions()
                .withSurface(state.getComparisonDisplay() == null ? state.surfaceFilter : null);
        var now = System.nanoTime();
        if (state.viewFocus != null
                && data == focusData
                && Objects.equals(display, focusDisplay)
                && now < nextFocusUpdate) {
            return;
        }
        var origin = camera.getCursorRayOrigin(state.focusNdcX, state.focusNdcY);
        var ray = camera.getCursorRayDirection(state.focusNdcX, state.focusNdcY);
        var resolved = GeoDataViewFocus.resolve(
                data,
                new GeoPoint(origin.getX(), origin.getY(), origin.getZ()),
                new GeoPoint(ray.getX(), ray.getY(), ray.getZ()),
                state.radius,
                display);
        state.viewFocus = resolved.point();
        state.viewFocusOnLayer = resolved.onLayer();
        focusData = data;
        focusDisplay = display;
        nextFocusUpdate = now + 80_000_000L;
    }

    private void submitMesh(
            int view,
            GeodataState state,
            Camera camera,
            Extent extent,
            BgfxTexture targetDepth,
            @Nullable BgfxTexture modelDepth,
            boolean softDepth,
            boolean fromAbove,
            boolean showSelection,
            long flags,
            MemoryStack stack) {
        var display = state.display;
        var focus = state.viewFocus;
        var source = camera.getProjectionMatrix().duplicate();
        source.position(0).limit(16);
        source.get(projectionTerms, 0, 16);
        meshProjection[0] = projectionTerms[10];
        meshProjection[1] = projectionTerms[11];
        meshProjection[2] = projectionTerms[14];
        meshProjection[3] = projectionTerms[15];
        meshStyle = styleFor(display);
        meshFocus[0] = (float) focus.x();
        meshFocus[1] = (float) focus.y();
        meshFocus[2] = (float) focus.z();
        meshHover = toCellUniform(showSelection ? state.hovered : null);
        meshSelect = toCellUniform(showSelection ? state.inspection.selected : null);
        meshView[0] = 1f / extent.width();
        meshView[1] = 1f / extent.height();
        meshView[2] = display.lineWidthPixels();
        meshView[3] = display.opacity();
        if (softDepth) {
            meshHeight[0] = modelDepth != null ? 1 : 0;
            meshHeight[1] = BgfxOverlayLinePass.MODEL_TOLERANCE;
            meshHeight[2] = state.throughGeometry ? HIDDEN_OPACITY : 0;
            meshHeight[3] = DEPTH_TOLERANCE;
            draw(view, fromAbove, flags, targetDepth, modelDepth, stack);
            return;
        }
        meshHeight[0] = 0;
        meshHeight[2] = 0;
        meshHeight[3] = 0;
        if (state.throughGeometry) {
            meshView[3] = display.opacity() * HIDDEN_OPACITY;
            draw(view, fromAbove, flags | BGFX.BGFX_STATE_DEPTH_TEST_GREATER, null, null, stack);
            meshView[3] = display.opacity();
        }
        draw(view, fromAbove, flags | BGFX.BGFX_STATE_DEPTH_TEST_LEQUAL, null, null, stack);
    }

    private void draw(
            int view,
            boolean fromAbove,
            long state,
            BgfxTexture sceneDepth,
            @Nullable BgfxTexture modelDepth,
            MemoryStack stack) {
        for (var resident : drawn) {
            var frame = stack.getPointer();
            BGFX.bgfx_set_uniform(projectionUniform.getHandle(), stack.floats(meshProjection), 1);
            BGFX.bgfx_set_uniform(styleUniform.getHandle(), stack.floats(meshStyle), 1);
            BGFX.bgfx_set_uniform(focusUniform.getHandle(), stack.floats(meshFocus), 1);
            BGFX.bgfx_set_uniform(hoverUniform.getHandle(), stack.floats(meshHover), 1);
            BGFX.bgfx_set_uniform(selectUniform.getHandle(), stack.floats(meshSelect), 1);
            BGFX.bgfx_set_uniform(heightUniform.getHandle(), stack.floats(meshHeight), 1);
            BGFX.bgfx_set_uniform(viewUniform.getHandle(), stack.floats(meshView), 1);
            stack.setPointer(frame);
            BGFX.bgfx_set_vertex_buffer(0, resident.vertices.getHandle(), 0, resident.quads * 4);
            BGFX.bgfx_set_index_buffer(
                    fromAbove ? forward.getHandle() : backward.getHandle(),
                    fromAbove ? 0 : (patternQuads - resident.quads) * 6,
                    resident.quads * 6);
            if (sceneDepth != null) {
                BGFX.bgfx_set_texture(0, depthSampler.getHandle(), sceneDepth.getHandle(), CLAMP_POINT);
            }
            if (modelDepth != null) {
                BGFX.bgfx_set_texture(1, modelDepthSampler.getHandle(), modelDepth.getHandle(), CLAMP_POINT);
            }
            BGFX.bgfx_set_state(state, 0);
            BGFX.bgfx_submit(view, program.getHandle(), 0, BGFX.BGFX_DISCARD_ALL);
        }
    }

    private void createProgram() {
        if (program != null) {
            return;
        }
        var resources = device.getResources();
        program = BgfxProgram.graphics(
                resources,
                "geodata/surface",
                BgfxShaderResources.load(device.getCapabilities(), "vs_geodata"),
                BgfxShaderResources.load(device.getCapabilities(), "fs_geodata"));
        viewUniform = BgfxUniform.create(resources, "u_geodataView", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        heightUniform = BgfxUniform.create(resources, "u_geodataHeight", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        projectionUniform = BgfxUniform.create(resources, "u_geodataProjection", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        styleUniform = BgfxUniform.create(resources, "u_geodataStyle", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        focusUniform = BgfxUniform.create(resources, "u_geodataFocus", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        hoverUniform = BgfxUniform.create(resources, "u_geodataHover", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        selectUniform = BgfxUniform.create(resources, "u_geodataSelect", BGFX.BGFX_UNIFORM_TYPE_VEC4, 1);
        depthSampler = BgfxUniform.create(resources, "s_geodataDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
        modelDepthSampler = BgfxUniform.create(resources, "s_geodataModelDepth", BGFX.BGFX_UNIFORM_TYPE_SAMPLER, 1);
    }

    private static final class Resident {
        final GeoDataChunk chunk;
        final BgfxBuffer vertices;
        final int quads;
        float distance;

        Resident(GeoDataChunk chunk, BgfxBuffer vertices, int quads) {
            this.chunk = chunk;
            this.vertices = vertices;
            this.quads = quads;
        }

        void close() {
            if (vertices != null) {
                vertices.close();
            }
        }
    }

    private void upload(List<GeoDataChunk> chunks) {
        if (chunks != residentChunks) {
            var places = new HashSet<Place>();
            for (var chunk : chunks) {
                places.add(chunk.place());
            }
            var iterator = residents.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!places.contains(entry.getKey())) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
            residentChunks = chunks;
        }
        var corners = 0;
        for (var chunk : chunks) {
            var resident = residents.get(chunk.place());
            if (resident != null && resident.chunk == chunk) {
                continue;
            }
            if (corners > 0 && corners + chunk.mesh().quadCount() * 4 > UPLOAD_CORNERS_PER_FRAME) {
                break;
            }
            corners += chunk.mesh().quadCount() * 4;
            var replacement = upload(chunk);
            if (resident != null) {
                resident.close();
            }
            residents.put(chunk.place(), replacement);
        }
    }

    private Resident upload(GeoDataChunk chunk) {
        var mesh = chunk.mesh();
        var quadFloats = 6 * GeoDataMesh.FLOATS_PER_VERTEX;
        var quads = mesh.vertices().length / quadFloats;
        if (quads == 0) {
            return new Resident(chunk, null, 0);
        }
        ensurePatterns(quads);
        var bytes = MemoryUtil.memAlloc(quads * 4 * 32);
        try {
            var floats = bytes.asFloatBuffer();
            var source = mesh.vertices();
            for (var quad = 0; quad < quads; quad++) {
                var offset = quad * quadFloats;
                for (var corner : CORNERS) {
                    floats.put(source, offset + corner * GeoDataMesh.FLOATS_PER_VERTEX, GeoDataMesh.FLOATS_PER_VERTEX);
                }
            }
            return new Resident(
                    chunk, BgfxBuffer.staticVertex(device.getResources(), "geodata/piece", bytes, LAYOUT, 0), quads);
        } finally {
            MemoryUtil.memFree(bytes);
        }
    }

    private void ensurePatterns(int quads) {
        if (forward != null && patternQuads >= quads) {
            return;
        }
        var capacity = Math.max(4096, Integer.highestOneBit(quads - 1) << 1);
        var ahead = MemoryUtil.memAlloc(capacity * 6 * Integer.BYTES);
        var behind = MemoryUtil.memAlloc(capacity * 6 * Integer.BYTES);
        try {
            for (var quad = 0; quad < capacity; quad++) {
                putQuadIndices(ahead, quad * 4);
                putQuadIndices(behind, (capacity - 1 - quad) * 4);
            }
            ahead.flip();
            behind.flip();
            var forwardReplacement = BgfxBuffer.staticIndex(device.getResources(), "geodata/indices", ahead, true, 0);
            var backwardReplacement =
                    BgfxBuffer.staticIndex(device.getResources(), "geodata/indices-reversed", behind, true, 0);
            releasePatterns();
            forward = forwardReplacement;
            backward = backwardReplacement;
            patternQuads = capacity;
        } finally {
            MemoryUtil.memFree(ahead);
            MemoryUtil.memFree(behind);
        }
    }

    private void collect(Camera camera, LoadedTiles tiles) {
        drawn.clear();
        var clip = tiles != null && !tiles.isEmpty();
        var frustum = camera.getFrustum();
        var eye = camera.getPosition();
        for (var resident : residents.values()) {
            var chunk = resident.chunk;
            if (clip
                    && !tiles.intersects(
                            chunk.x(), chunk.y(), chunk.x() + chunk.size() - 1, chunk.y() + chunk.size() - 1)) {
                continue;
            }
            if (resident.quads == 0
                    || !frustum.intersects(
                            chunk.x(),
                            chunk.y(),
                            chunk.minZ(),
                            chunk.x() + chunk.size(),
                            chunk.y() + chunk.size(),
                            chunk.maxZ())) {
                continue;
            }
            var dx = chunk.x() + chunk.size() * .5f - eye.getX();
            var dy = chunk.y() + chunk.size() * .5f - eye.getY();
            resident.distance = dx * dx + dy * dy;
            drawn.add(resident);
        }
        drawn.sort((a, b) -> Float.compare(b.distance, a.distance));
    }

    private void ensureFramebuffer(BgfxTexture targetColor, BgfxTexture targetDepth) {
        if (color == targetColor && depth == targetDepth && (targetColor == null || framebuffer != null)) {
            return;
        }
        releaseFramebuffer();
        if (targetColor != null) {
            framebuffer = BgfxFramebuffer.create(
                    device.getResources(),
                    "geodata/framebuffer",
                    targetDepth == null ? List.of(targetColor) : List.of(targetColor, targetDepth));
        }
        color = targetColor;
        depth = targetDepth;
    }

    private void releaseGeometry() {
        for (var resident : residents.values()) {
            resident.close();
        }
        residents.clear();
        drawn.clear();
        residentChunks = List.of();
        releasePatterns();
    }

    private void releasePatterns() {
        if (forward != null) {
            forward.close();
            forward = null;
        }
        if (backward != null) {
            backward.close();
            backward = null;
        }
        patternQuads = 0;
    }

    private void releaseFramebuffer() {
        if (framebuffer != null) {
            framebuffer.close();
            framebuffer = null;
        }
        color = null;
        depth = null;
    }

    private static float[] toCellUniform(Selection selection) {
        return selection == null
                ? new float[4]
                : new float[] {selection.ref().x(), selection.ref().y(), selection.height(), 1};
    }

    private static void putQuadIndices(ByteBuffer out, int first) {
        out.putInt(first)
                .putInt(first + 1)
                .putInt(first + 2)
                .putInt(first)
                .putInt(first + 2)
                .putInt(first + 3);
    }
}
