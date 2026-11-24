package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_COLOR0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_POSITION;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TEXCOORD0;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_FLOAT;
import static org.lwjgl.bgfx.BGFX.BGFX_ATTRIB_TYPE_UINT8;
import static org.lwjgl.bgfx.BGFX.BGFX_DISCARD_ALL;
import static org.lwjgl.bgfx.BGFX.BGFX_INVALID_HANDLE;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_FUNC;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_INV_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_BLEND_SRC_ALPHA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_MSAA;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_A;
import static org.lwjgl.bgfx.BGFX.BGFX_STATE_WRITE_RGB;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;
import static org.lwjgl.bgfx.BGFX.BGFX_UNIFORM_TYPE_SAMPLER;
import static org.lwjgl.bgfx.BGFX.BGFX_VIEW_MODE_SEQUENTIAL;
import static org.lwjgl.bgfx.BGFX.bgfx_alloc_transient_buffers;
import static org.lwjgl.bgfx.BGFX.bgfx_set_scissor;
import static org.lwjgl.bgfx.BGFX.bgfx_set_state;
import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_transient_index_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_transient_vertex_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_frame_buffer;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_mode;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_name;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_rect;
import static org.lwjgl.bgfx.BGFX.bgfx_set_view_transform;
import static org.lwjgl.bgfx.BGFX.bgfx_submit;

import com.rost.cruma.core.world.BrandCrystal;
import com.rost.cruma.core.world.LoadingScene.Figure;
import com.rost.cruma.core.world.LoadingScene.Landmarks;
import com.rost.cruma.viewer.render.api.UiFade;
import com.rost.cruma.viewer.render.api.ViewerBrandCrystal;
import com.rost.cruma.viewer.render.api.ViewerLoadingScene;
import com.rost.cruma.viewer.render.api.ViewerUiRenderer;
import imgui.ImDrawData;
import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiBackendFlags;
import imgui.type.ImInt;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.bgfx.BGFXTransientIndexBuffer;
import org.lwjgl.bgfx.BGFXTransientVertexBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class BgfxViewerUiRenderer implements ViewerUiRenderer {
    private static final long UI_STATE = BGFX_STATE_WRITE_RGB
            | BGFX_STATE_WRITE_A
            | BGFX_STATE_MSAA
            | BGFX_STATE_BLEND_FUNC(BGFX_STATE_BLEND_SRC_ALPHA, BGFX_STATE_BLEND_INV_SRC_ALPHA);
    private static final int UI_SAMPLER_FLAGS = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;

    private static final Logger log = LogManager.getLogger(BgfxViewerUiRenderer.class);

    private final BgfxDevice device;
    private final int viewId;
    private final List<BgfxLoadingScene> loadingScenes = new ArrayList<>();
    private final List<BgfxBrandCrystal> brandCrystals = new ArrayList<>();
    private final BgfxVertexLayoutSpec layout = BgfxVertexLayoutSpec.builder()
            .add(BGFX_ATTRIB_POSITION, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_TEXCOORD0, 2, BGFX_ATTRIB_TYPE_FLOAT)
            .add(BGFX_ATTRIB_COLOR0, 4, BGFX_ATTRIB_TYPE_UINT8, true, false)
            .build();
    private final Map<Long, BgfxTexture> textures = new HashMap<>();
    private final Map<Long, BgfxTexture> ownedTextures = new HashMap<>();
    private final ImVec4 clipRect = new ImVec4();
    private final float[] projection = new float[16];
    private final UiFade fade = new UiFade();

    private BgfxProgram program;
    private BgfxUniform sampler;
    private BgfxTexture fontTexture;
    private long fontTextureId;
    private long nextTextureId = 1L;
    private boolean initialized;
    private boolean transientExhaustionWarned;
    private boolean unknownTextureWarned;

    public BgfxViewerUiRenderer(BgfxDevice device) {
        this.device = device;
        this.viewId = device.getUiViewId();
    }

    @Override
    public void initialize() {
        if (initialized) {
            return;
        }
        try {
            var resources = device.getResources();
            program = BgfxProgram.graphics(
                    resources,
                    "ui/imgui",
                    BgfxShaderResources.load(device.getCapabilities(), "vs_imgui"),
                    BgfxShaderResources.load(device.getCapabilities(), "fs_imgui"));
            sampler = BgfxUniform.create(resources, "s_texture", BGFX_UNIFORM_TYPE_SAMPLER, 1);

            uploadFontAtlas();
            ImGui.getIO().addBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
            initialized = true;
        } catch (RuntimeException | Error e) {
            destroyResources();
            throw e;
        }
    }

    @Override
    public void newFrame() {
        requireInitialized();
    }

    @Override
    public void refreshFontAtlas() {
        requireInitialized();
        uploadFontAtlas();
    }

    @Override
    public long createTexture(String label, int width, int height, ByteBuffer rgba) {
        requireInitialized();
        if (width <= 0 || height <= 0 || rgba.remaining() != (long) width * height * 4) {
            throw new IllegalArgumentException("UI texture must contain width * height RGBA8 pixels");
        }
        var texture = BgfxTexture.create2D(
                device.getResources(),
                label,
                width,
                height,
                true,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                UI_SAMPLER_FLAGS,
                BgfxUiMipmaps.createRgbaMipChain(rgba, width, height));
        var id = registerTexture(texture);
        ownedTextures.put(id, texture);
        return id;
    }

    @Override
    public @Nullable ViewerLoadingScene createLoadingScene(Landmarks landmarks, List<@Nullable Figure> figures) {
        requireInitialized();
        try {
            var scene = new BgfxLoadingScene(device, this, landmarks, figures);
            loadingScenes.add(scene);
            return scene;
        } catch (RuntimeException failure) {
            log.warn("The loading screen shows its picture: {}", failure.toString());
            return null;
        }
    }

    @Override
    public void destroyLoadingScene(ViewerLoadingScene scene) {
        if (loadingScenes.remove(scene)) {
            ((BgfxLoadingScene) scene).close();
        }
    }

    @Override
    public @Nullable ViewerBrandCrystal createBrandCrystal(BrandCrystal crystal) {
        if (!initialized) {
            return null;
        }
        try {
            var made = new BgfxBrandCrystal(device, this, crystal);
            brandCrystals.add(made);
            return made;
        } catch (RuntimeException failure) {
            log.warn("The mark stays the drawn one: {}", failure.toString());
            return null;
        }
    }

    @Override
    public void destroyBrandCrystal(ViewerBrandCrystal crystal) {
        if (brandCrystals.remove(crystal)) {
            ((BgfxBrandCrystal) crystal).close();
        }
    }

    @Override
    public void destroyTexture(long textureId) {
        requireInitialized();
        var texture = ownedTextures.remove(textureId);
        if (texture == null) {
            throw new IllegalArgumentException("UI image texture is not owned by this renderer");
        }
        unregisterTexture(textureId);
        texture.close();
    }

    @Override
    public void render(ImDrawData drawData, int framebufferWidth, int framebufferHeight) {
        render(drawData, framebufferWidth, framebufferHeight, null);
    }

    void render(ImDrawData drawData, int framebufferWidth, int framebufferHeight, BgfxFramebuffer target) {
        render(drawData, framebufferWidth, framebufferHeight, target, viewId);
    }

    void render(ImDrawData drawData, int framebufferWidth, int framebufferHeight, BgfxFramebuffer target, int view) {
        requireInitialized();
        if (drawData == null || !drawData.getValid()) {
            return;
        }
        var scaleX = drawData.getFramebufferScaleX();
        var scaleY = drawData.getFramebufferScaleY();
        var width = Math.min(framebufferWidth, Math.round(drawData.getDisplaySizeX() * scaleX));
        var height = Math.min(framebufferHeight, Math.round(drawData.getDisplaySizeY() * scaleY));
        if (width <= 0 || height <= 0 || drawData.getTotalVtxCount() == 0) {
            return;
        }

        updateProjection(
                drawData.getDisplayPosX(),
                drawData.getDisplayPosY(),
                drawData.getDisplaySizeX(),
                drawData.getDisplaySizeY());
        bgfx_set_view_name(view, "display-ui");
        bgfx_set_view_rect(view, 0, 0, width, height);
        bgfx_set_view_frame_buffer(view, target == null ? BGFX_INVALID_HANDLE : target.getHandle());
        bgfx_set_view_mode(view, BGFX_VIEW_MODE_SEQUENTIAL);
        bgfx_set_view_transform(view, (float[]) null, projection);

        var fading = UiFade.takeMarked();
        fade.reset();
        try (var stack = MemoryStack.stackPush()) {
            var nativeLayout = layout.createNative(stack);
            for (var list = 0; list < drawData.getCmdListsCount(); list++) {
                var vertexCount = drawData.getCmdListVtxBufferSize(list);
                var indexCount = drawData.getCmdListIdxBufferSize(list);
                var indexSize = ImDrawData.sizeOfImDrawIdx();
                if (indexSize != Short.BYTES && indexSize != Integer.BYTES) {
                    throw new IllegalStateException("unsupported ImDrawIdx size " + indexSize);
                }
                var index32 = indexSize == Integer.BYTES;
                var vertices = BGFXTransientVertexBuffer.calloc(stack);
                var indices = BGFXTransientIndexBuffer.calloc(stack);
                if (!bgfx_alloc_transient_buffers(vertices, nativeLayout, vertexCount, indices, indexCount, index32)) {
                    if (!transientExhaustionWarned) {
                        transientExhaustionWarned = true;
                        log.warn(
                                "Interface transient buffer exhausted; skipped a draw list "
                                        + "with {} vertices/{} indices (further occurrences suppressed)",
                                vertexCount,
                                indexCount);
                    }
                    continue;
                }
                copy(drawData.getCmdListVtxBufferData(list), vertices.data());
                copy(drawData.getCmdListIdxBufferData(list), indices.data());
                if (fading) {
                    fade.apply(vertices.data(), vertexCount, isInsideFade(drawData, list));
                }
                submitCommands(drawData, list, vertices, indices, vertexCount, width, height, scaleX, scaleY, view);
            }
        }
    }

    public long registerTexture(BgfxTexture texture) {
        if (texture == null) {
            throw new IllegalArgumentException("UI texture cannot be null");
        }
        var id = nextTextureId++;
        if (id == 0L) {
            throw new IllegalStateException("ImGui texture ID space exhausted");
        }
        textures.put(id, texture);
        return id;
    }

    public void unregisterTexture(long textureId) {
        if (textureId == fontTextureId) {
            throw new IllegalArgumentException("the ImGui font texture is owned by the UI renderer");
        }
        textures.remove(textureId);
    }

    @Override
    public void close() {
        for (var scene : loadingScenes) {
            scene.close();
        }
        loadingScenes.clear();
        for (var crystal : brandCrystals) {
            crystal.close();
        }
        brandCrystals.clear();
        if (initialized) {
            ImGui.getIO().removeBackendFlags(ImGuiBackendFlags.RendererHasVtxOffset);
            ImGui.getIO().getFonts().setTexID(0L);
        }
        destroyResources();
        initialized = false;
    }

    private void uploadFontAtlas() {
        var width = new ImInt();
        var height = new ImInt();
        var atlas = ImGui.getIO().getFonts();
        var pixels = atlas.getTexDataAsRGBA32(width, height);
        var replacement = BgfxTexture.create2D(
                device.getResources(),
                "ui/font-atlas",
                width.get(),
                height.get(),
                false,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                0L,
                pixels);
        var previous = fontTexture;
        if (fontTextureId == 0L) {
            fontTextureId = registerTexture(replacement);
        } else {
            textures.put(fontTextureId, replacement);
        }
        fontTexture = replacement;
        atlas.setTexID(fontTextureId);
        close(previous);
    }

    private void submitCommands(
            ImDrawData drawData,
            int list,
            BGFXTransientVertexBuffer vertices,
            BGFXTransientIndexBuffer indices,
            int vertexCount,
            int framebufferWidth,
            int framebufferHeight,
            float scaleX,
            float scaleY,
            int view) {
        var clipOffsetX = drawData.getDisplayPosX();
        var clipOffsetY = drawData.getDisplayPosY();
        for (var command = 0; command < drawData.getCmdListCmdBufferSize(list); command++) {
            drawData.getCmdListCmdBufferClipRect(clipRect, list, command);
            if (fade.isActive()) {
                clipRect.set(
                        fade.clipX(clipRect.x), fade.clipY(clipRect.y), fade.clipX(clipRect.z), fade.clipY(clipRect.w));
            }
            var minX = Math.max(0, (int) ((clipRect.x - clipOffsetX) * scaleX));
            var minY = Math.max(0, (int) ((clipRect.y - clipOffsetY) * scaleY));
            var maxX = Math.min(framebufferWidth, (int) Math.ceil((clipRect.z - clipOffsetX) * scaleX));
            var maxY = Math.min(framebufferHeight, (int) Math.ceil((clipRect.w - clipOffsetY) * scaleY));
            if (maxX <= minX || maxY <= minY) {
                continue;
            }

            var textureId = drawData.getCmdListCmdBufferTextureId(list, command);
            var texture = textures.get(textureId);
            if (texture == null) {
                if (!unknownTextureWarned) {
                    unknownTextureWarned = true;
                    log.warn(
                            "Unknown interface TextureId {}; using the font atlas "
                                    + "(further occurrences suppressed)",
                            textureId);
                }
                texture = fontTexture;
            }
            var elementCount = drawData.getCmdListCmdBufferElemCount(list, command);
            var indexOffset = drawData.getCmdListCmdBufferIdxOffset(list, command);
            var vertexOffset = drawData.getCmdListCmdBufferVtxOffset(list, command);
            if (vertexOffset < 0 || vertexOffset >= vertexCount || elementCount <= 0) {
                continue;
            }

            bgfx_set_scissor(minX, minY, maxX - minX, maxY - minY);
            bgfx_set_texture(0, sampler.getHandle(), texture.getHandle(), UI_SAMPLER_FLAGS);
            bgfx_set_state(UI_STATE, 0);
            bgfx_set_transient_vertex_buffer(0, vertices, vertexOffset, vertexCount - vertexOffset);
            bgfx_set_transient_index_buffer(indices, indexOffset, elementCount);
            bgfx_submit(view, program.getHandle(), 0, BGFX_DISCARD_ALL);
        }
    }

    private void updateProjection(float x, float y, float width, float height) {
        var left = x;
        var right = x + width;
        var top = y;
        var bottom = y + height;
        projection[0] = 2.0f / (right - left);
        projection[1] = 0.0f;
        projection[2] = 0.0f;
        projection[3] = 0.0f;
        projection[4] = 0.0f;
        projection[5] = 2.0f / (top - bottom);
        projection[6] = 0.0f;
        projection[7] = 0.0f;
        projection[8] = 0.0f;
        projection[9] = 0.0f;
        projection[10] = 0.5f;
        projection[11] = 0.0f;
        projection[12] = (right + left) / (left - right);
        projection[13] = (top + bottom) / (bottom - top);
        projection[14] = 0.5f;
        projection[15] = 1.0f;
    }

    private boolean isInsideFade(ImDrawData drawData, int list) {
        if (!fade.isActive()) {
            return false;
        }
        for (var command = 0; command < drawData.getCmdListCmdBufferSize(list); command++) {
            drawData.getCmdListCmdBufferClipRect(clipRect, list, command);
            if (!fade.contains(clipRect.x, clipRect.y, clipRect.z, clipRect.w)) {
                return false;
            }
        }
        return true;
    }

    private void destroyResources() {
        ownedTextures.values().forEach(BgfxViewerUiRenderer::close);
        ownedTextures.clear();
        textures.clear();
        fontTextureId = 0L;
        close(fontTexture);
        fontTexture = null;
        close(sampler);
        sampler = null;
        close(program);
        program = null;
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("bgfx ImGui renderer is not initialized");
        }
    }

    private static void copy(ByteBuffer source, ByteBuffer destination) {
        if (source.remaining() > destination.remaining()) {
            throw new IllegalStateException("bgfx transient allocation is smaller than ImGui draw data");
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(source), MemoryUtil.memAddress(destination), source.remaining());
    }

    private static void close(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to close bgfx UI resource", e);
        }
    }
}
