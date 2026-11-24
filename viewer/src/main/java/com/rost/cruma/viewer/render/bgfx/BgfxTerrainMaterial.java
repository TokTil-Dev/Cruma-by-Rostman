package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.bgfx_set_texture;
import static org.lwjgl.bgfx.BGFX.bgfx_set_uniform;

import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.TerrainZoneLayers;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class BgfxTerrainMaterial implements AutoCloseable {
    static final int MAX_LAYERS = 32;

    static final int PASS_LAYERS = 12;

    static final int LAYER_UV_VECTORS = 2 * PASS_LAYERS + 1;

    private static final int PASS_ATLASES = PASS_LAYERS / 4;
    private static final int MAX_PASSES = (MAX_LAYERS + PASS_LAYERS - 1) / PASS_LAYERS;

    private final long stableId;
    private final BgfxTextureCache cache;
    private final TextureRef[] diffuse = new TextureRef[MAX_PASSES * PASS_LAYERS];
    private final TextureRef[] alpha = new TextureRef[MAX_PASSES * PASS_ATLASES];
    private final List<TextureRef> ownedTextures = new ArrayList<>();
    private final float[] values = new float[BgfxSceneUniforms.TERRAIN_VECTORS * 4];
    private final float[] overlayValues = new float[BgfxSceneUniforms.TERRAIN_VECTORS * 4];
    private final float[] layerUv = new float[LAYER_UV_VECTORS * 4];
    private final float[] baseUv = new float[LAYER_UV_VECTORS * 4];
    private final int[] diffuseAlpha = new int[MAX_PASSES * PASS_ATLASES];
    private final int layerCount;
    private @Nullable TerrainLayerProjection overlayProjection;
    private final @Nullable TerrainZoneLayers zoneLayers;
    private int boundZoneState = Integer.MIN_VALUE;
    private int hiddenLayers;
    private int baseLayer;
    private boolean closed;

    private BgfxTerrainMaterial(
            long stableId,
            MultiTexture source,
            String stableName,
            BgfxTextureCache cache,
            @Nullable TerrainVertexBuffer vertices) {
        this.stableId = stableId;
        this.cache = cache;
        for (var group = 0; group < diffuseAlpha.length; group++) {
            diffuseAlpha[group] = diffuseAlphaMask(source, group * 4);
        }
        put(values, 3, diffuseAlpha[0], diffuseAlpha[1], diffuseAlpha[2], 0f);
        var zones = source.getZoneLayers();
        zoneLayers = zones.hasZoneLayers() ? zones : null;
        if (isProxySource(source)) {
            this.layerCount = 1;
            this.overlayProjection = null;
            baseUv[2 * PASS_LAYERS * 4 + 3] = 1f;
            Arrays.fill(diffuse, cache.getPagedTerrainProxy());
            Arrays.fill(alpha, cache.getPagedBlack());
            return;
        }
        this.layerCount = Math.min(MAX_LAYERS, source.entries().size());
        this.overlayProjection = layerCount > PASS_LAYERS && vertices != null
                ? TerrainLayerProjection.of(vertices, PASS_LAYERS, layerCount - PASS_LAYERS)
                : null;
        placeBaseLayers(vertices);
        try {
            for (var layer = 0; layer < diffuse.length; layer++) {
                if (layer < layerCount && source.entries().get(layer).texture() != null) {
                    diffuse[layer] = own(
                            cache.loadPaged(source.entries().get(layer).texture(), stableName + "/terrain-" + layer));
                } else {
                    diffuse[layer] = cache.getPagedBlack();
                }
            }
            IdentityHashMap<Texture, byte[]> decoded = null;
            for (var group = 0; group < alpha.length; group++) {
                var firstLayer = group * 4;
                if (layerCount == 1 || firstLayer >= layerCount) {
                    alpha[group] = cache.getPagedBlack();
                    continue;
                }
                var prepared = cache.getPreparedTerrainAlpha(source, group);
                if (prepared != null) {
                    alpha[group] = own(cache.createPreparedGeneratedPagedRgba(
                            "scene/" + stableName + "/terrain-alpha-" + group, prepared, true));
                } else if (group < source.packedAlphaGroups().size()) {
                    alpha[group] = own(cache.getTerrainAlphaAtlas(
                            "scene/" + stableName + "/terrain-alpha-" + group,
                            source.packedAlphaGroups().get(group)));
                } else {
                    if (decoded == null) {
                        decoded = new IdentityHashMap<>();
                    }
                    var extent = BgfxPreparedTexturePayloads.terrainAlphaExtent(source, firstLayer);
                    var packed = packAlphaGroup(source, firstLayer, extent.width(), extent.height(), cache, decoded);
                    alpha[group] = own(cache.createGeneratedPagedRgba(
                            "scene/" + stableName + "/terrain-alpha-" + group,
                            extent.width(),
                            extent.height(),
                            packed,
                            false,
                            true));
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure);
            throw failure;
        }
    }

    static BgfxTerrainMaterial create(
            long stableId,
            MultiTexture source,
            String stableName,
            BgfxTextureCache cache,
            @Nullable TerrainVertexBuffer vertices) {
        if (source == null || source.entries().isEmpty()) {
            throw new IllegalArgumentException("terrain requires at least one material entry");
        }
        return new BgfxTerrainMaterial(stableId, source, stableName, cache, vertices);
    }

    static boolean isProxySource(MultiTexture source) {
        if (source == null || source.entries().size() != 1) {
            return false;
        }
        var entry = source.entries().getFirst();
        return entry.texture() == null && entry.alphaMap() == null;
    }

    static int diffuseAlphaMask(MultiTexture source, int firstLayer) {
        if (source == null || firstLayer < 0) {
            throw new IllegalArgumentException("terrain diffuse-alpha mask requires a source and layer");
        }
        var mask = 0;
        var lastLayer = Math.min(Math.min(source.entries().size(), MAX_LAYERS), firstLayer + 4);
        for (var layer = firstLayer; layer < lastLayer; layer++) {
            var texture = source.entries().get(layer).texture();
            if (texture != null && texture.alphaTexture()) {
                mask |= 1 << (layer - firstLayer);
            }
        }
        return mask;
    }

    long getStableId() {
        return stableId;
    }

    int getLayerCount() {
        return layerCount;
    }

    int getPassCount() {
        return overlayProjection == null ? 1 : (layerCount + PASS_LAYERS - 1) / PASS_LAYERS;
    }

    boolean hasOverlayLayers(int pass, ZoneStateChoice zoneState) {
        if (pass < 1 || pass >= getPassCount()) {
            return false;
        }
        chooseZoneLayers(zoneState);
        var count = Math.min(PASS_LAYERS, layerCount - pass * PASS_LAYERS);
        var shown = ~(hiddenLayers >>> pass * PASS_LAYERS) & (1 << count) - 1;
        return shown != 0 || baseLayer / PASS_LAYERS == pass;
    }

    void placeLayers(TerrainVertexBuffer vertices) {
        if (overlayProjection != null) {
            overlayProjection = TerrainLayerProjection.of(vertices, PASS_LAYERS, layerCount - PASS_LAYERS);
        }
        if (baseUv[2 * PASS_LAYERS * 4 + 3] == 0f) {
            placeBaseLayers(vertices);
        }
    }

    void collectTextures(Set<TextureRef> target) {
        target.addAll(ownedTextures);
    }

    void bind(
            BgfxSceneUniforms uniforms,
            boolean texturesEnabled,
            Vec3 ambient,
            Vec3 sun,
            boolean skipZeroCoverage,
            ZoneStateChoice zoneState) {
        bindTextures(uniforms, 0, texturesEnabled);
        chooseZoneLayers(zoneState);
        var base = baseLayer < PASS_LAYERS ? baseLayer : 0;
        put(values, 0, Math.min(PASS_LAYERS, layerCount), texturesEnabled ? 1f : 0f, skipZeroCoverage ? 1f : 0f, base);
        values[3 * 4 + 3] = hiddenLayers & (1 << PASS_LAYERS) - 1;
        put(values, 1, ambient.getX(), ambient.getY(), ambient.getZ(), 0f);
        put(values, 2, sun.getX(), sun.getY(), sun.getZ(), 0f);
        for (var layer = 0; layer < PASS_LAYERS; layer++) {
            values[(4 + layer / 4) * 4 + layer % 4] = diffuse[layer].getBaseLayer();
        }
        put(values, 7, alpha[0].getBaseLayer(), alpha[1].getBaseLayer(), alpha[2].getBaseLayer(), 0f);
        bgfx_set_uniform(uniforms.getTerrainMaterial().getHandle(), values, BgfxSceneUniforms.TERRAIN_VECTORS);
        bgfx_set_uniform(uniforms.getTerrainBaseUv().getHandle(), baseUv, LAYER_UV_VECTORS);
    }

    void bindOverlay(
            BgfxSceneUniforms uniforms,
            int pass,
            boolean texturesEnabled,
            Vec3 ambient,
            Vec3 sun,
            boolean skipZeroCoverage,
            ZoneStateChoice zoneState,
            float[] model,
            float coverage) {
        if (pass < 1 || pass >= getPassCount() || overlayProjection == null) {
            throw new IllegalArgumentException("terrain has no overlay " + pass);
        }
        bindTextures(uniforms, pass, texturesEnabled);
        chooseZoneLayers(zoneState);
        var first = pass * PASS_LAYERS;
        var base = baseLayer / PASS_LAYERS == pass ? baseLayer - first : -1;
        put(
                overlayValues,
                0,
                Math.min(PASS_LAYERS, layerCount - first),
                texturesEnabled ? 1f : 0f,
                skipZeroCoverage ? 1f : 0f,
                base);
        put(
                overlayValues,
                3,
                diffuseAlpha[pass * PASS_ATLASES],
                diffuseAlpha[pass * PASS_ATLASES + 1],
                diffuseAlpha[pass * PASS_ATLASES + 2],
                hiddenLayers >>> first & (1 << PASS_LAYERS) - 1);
        put(overlayValues, 1, ambient.getX(), ambient.getY(), ambient.getZ(), 0f);
        put(overlayValues, 2, sun.getX(), sun.getY(), sun.getZ(), 0f);
        for (var layer = 0; layer < PASS_LAYERS; layer++) {
            overlayValues[(4 + layer / 4) * 4 + layer % 4] = diffuse[first + layer].getBaseLayer();
        }
        put(
                overlayValues,
                7,
                alpha[pass * PASS_ATLASES].getBaseLayer(),
                alpha[pass * PASS_ATLASES + 1].getBaseLayer(),
                alpha[pass * PASS_ATLASES + 2].getBaseLayer(),
                coverage);
        bgfx_set_uniform(uniforms.getTerrainMaterial().getHandle(), overlayValues, BgfxSceneUniforms.TERRAIN_VECTORS);
        overlayProjection.writeWorld(model, first - PASS_LAYERS, PASS_LAYERS, PASS_LAYERS, layerUv);
        bgfx_set_uniform(uniforms.getTerrainLayerUv().getHandle(), layerUv, LAYER_UV_VECTORS);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = ownedTextures.size() - 1; index >= 0; index--) {
            try {
                ownedTextures.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void placeBaseLayers(@Nullable TerrainVertexBuffer vertices) {
        if (vertices == null) {
            return;
        }
        var base = Math.min(PASS_LAYERS, layerCount);
        TerrainLayerProjection.of(vertices, 0, base).writeWorld(null, 0, base, PASS_LAYERS, baseUv);
    }

    private void bindTextures(BgfxSceneUniforms uniforms, int pass, boolean texturesEnabled) {
        for (var slot = 0; slot < PASS_LAYERS; slot++) {
            var texture = texturesEnabled ? diffuse[pass * PASS_LAYERS + slot] : cache.getPagedBlack();
            bgfx_set_texture(
                    slot,
                    uniforms.getTerrainSampler(slot).getHandle(),
                    texture.getTexture().getHandle(),
                    texture.getSamplerFlags());
        }
        for (var group = 0; group < PASS_ATLASES; group++) {
            var texture = alpha[pass * PASS_ATLASES + group];
            bgfx_set_texture(
                    PASS_LAYERS + group,
                    uniforms.getTerrainSampler(PASS_LAYERS + group).getHandle(),
                    texture.getTexture().getHandle(),
                    texture.getSamplerFlags());
        }
    }

    private void chooseZoneLayers(ZoneStateChoice zoneState) {
        if (zoneLayers == null) {
            return;
        }
        var choice = zoneLayers.choiceIn(zoneState);
        if (choice == boundZoneState) {
            return;
        }
        boundZoneState = choice;
        hiddenLayers = zoneLayers.findHiddenLayers(choice) & (int) ((1L << layerCount) - 1);
        var base = zoneLayers.findBaseLayer(hiddenLayers);
        baseLayer = base < layerCount ? base : 0;
    }

    private TextureRef own(TextureRef ref) {
        if (!ref.isBaseline()) {
            ownedTextures.add(ref);
        }
        return ref;
    }

    private void closeAfterFailure(Throwable failure) {
        for (var index = ownedTextures.size() - 1; index >= 0; index--) {
            try {
                ownedTextures.get(index).close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        ownedTextures.clear();
    }

    private static void put(float[] target, int vector, float x, float y, float z, float w) {
        var offset = vector * 4;
        target[offset] = x;
        target[offset + 1] = y;
        target[offset + 2] = z;
        target[offset + 3] = w;
    }

    private static byte[] packAlphaGroup(
            MultiTexture source,
            int firstLayer,
            int atlasWidth,
            int atlasHeight,
            BgfxTextureCache cache,
            IdentityHashMap<Texture, byte[]> decoded) {
        var output = new byte[Math.multiplyExact(Math.multiplyExact(atlasWidth, atlasHeight), 4)];
        for (var channel = 0; channel < 4; channel++) {
            var layer = firstLayer + channel;
            if (layer >= source.entries().size()) {
                continue;
            }
            var texture = source.entries().get(layer).alphaMap();
            if (texture == null || texture.width() < 1 || texture.height() < 1) {
                continue;
            }
            var rgba = decoded.computeIfAbsent(texture, cache::decodeBaseRgba);
            for (var y = 0; y < atlasHeight; y++) {
                var sourceY = Math.min(texture.height() - 1, y * texture.height() / atlasHeight);
                for (var x = 0; x < atlasWidth; x++) {
                    var sourceX = Math.min(texture.width() - 1, x * texture.width() / atlasWidth);
                    output[(y * atlasWidth + x) * 4 + channel] = rgba[(sourceY * texture.width() + sourceX) * 4];
                }
            }
        }
        return output;
    }
}
