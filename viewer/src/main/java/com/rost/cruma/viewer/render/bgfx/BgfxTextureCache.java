package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MAG_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_MIN_ANISOTROPIC;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_U_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_SAMPLER_V_CLAMP;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.AuthoredMaterialRuntime;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.Cubemap;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.NativeRandom;
import com.rost.cruma.core.world.TextureSequence.Clocks;
import com.rost.cruma.viewer.render.api.ViewerGraphicsConfig.Tuning;
import com.rost.cruma.viewer.render.bgfx.BgfxPreparedTexturePayloads.Payload;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

final class BgfxTextureCache implements AutoCloseable {
    private static final int LIGHTMAP_PAGE_TARGET_LAYERS = 256;
    private static final long LIGHTMAP_PAGE_TARGET_BYTES = 64L * 1024L * 1024L;
    private static final long MATERIAL_PAGE_TARGET_BYTES = 16L * 1024L * 1024L;
    private static final boolean ADAPTIVE_TEXTURE_PAGES =
            !"false".equals(System.getProperty("cruma.viewer.bgfx.adaptive_texture_pages"));
    private long renderFrame;
    private double bitmapSeconds;
    private final Clocks bitmapClocks = new Clocks();
    private final AuthoredMaterialRuntime authoredMaterials;

    private final BgfxDevice device;
    private final int materialPageTargetLayers;
    private final int ordinaryDiffusePageGroupSize;
    private final WeakIdentityMap<Texture, TextureEntry> byIdentity = new WeakIdentityMap<>();
    private final WeakIdentityMap<Texture, ArrayAllocation> pagedByIdentity = new WeakIdentityMap<>();
    private final WeakIdentityMap<TerrainAlphaAtlas, ArrayAllocation> terrainAlphaByIdentity = new WeakIdentityMap<>();
    private final WeakIdentityMap<Cubemap, TextureEntry> cubesByIdentity = new WeakIdentityMap<>();
    private final WeakIdentityMap<Texture, ArrayAllocation> singleLayerArraysByIdentity = new WeakIdentityMap<>();
    private final WeakIdentityMap<List<Texture>, ArrayAllocation> arraysByIdentity = new WeakIdentityMap<>();
    private final Map<String, Map<Integer, List<LightmapLayer>>> lightmapLayers = new HashMap<>();
    private final Map<String, ArrayAllocation> arraysByStableName = new HashMap<>();
    private final Map<String, ArrayAllocation> pagedByStableName = new HashMap<>();
    private final Map<BgfxTextureContent, ArrayAllocation> pagedByContent = new HashMap<>();
    private final Set<BgfxTextureContent> residentContents = ConcurrentHashMap.newKeySet();
    private final Map<ArrayPageKey, List<ArrayPage>> arrayPages = new HashMap<>();
    private final Map<String, TextureEntry> byStableName = new HashMap<>();
    private final List<BgfxTexture> owned = new ArrayList<>();

    private final TextureRef white;
    private final TextureRef black;
    private final TextureRef transparent;
    private final TextureRef whiteCube;
    private final TextureRef pagedWhite;
    private final TextureRef pagedBlack;
    private final TextureRef pagedTransparent;
    private final TextureRef pagedTerrainProxy;
    private BgfxPreparedTexturePayloads prepared;
    private boolean closed;

    BgfxTextureCache(BgfxDevice device, Tuning tuning) {
        this(device, tuning, new NativeRandom());
    }

    BgfxTextureCache(BgfxDevice device, Tuning tuning, DoubleSupplier random) {
        this.device = device;
        authoredMaterials = new AuthoredMaterialRuntime(random);
        this.materialPageTargetLayers = tuning.materialPageTargetLayers();
        this.ordinaryDiffusePageGroupSize = tuning.ordinaryDiffusePageGroupSize();
        white = createSolidTexture("scene/white", 0xffffffff);
        black = createSolidTexture("scene/black", 0x000000ff);
        transparent = createSolidTexture("scene/transparent", 0x00000000);
        whiteCube = solidCube("scene/white-cube", 0xffffffff);
        pagedWhite = pagedSolid("scene/paged-white", 0xffffffff);
        pagedBlack = pagedSolid("scene/paged-black", 0x000000ff);
        pagedTransparent = pagedSolid("scene/paged-transparent", 0x00000000);
        pagedTerrainProxy = pagedSolid("scene/paged-terrain-proxy", 0x687552ff);
    }

    static boolean isAdaptiveTexturePagesEnabled() {
        return ADAPTIVE_TEXTURE_PAGES;
    }

    static int pagedSampler(Texture source) {
        var sampler = BGFX_SAMPLER_MIN_ANISOTROPIC
                | BGFX_SAMPLER_MAG_ANISOTROPIC
                | (source.clampU() ? BGFX_SAMPLER_U_CLAMP : 0)
                | (source.clampV() ? BGFX_SAMPLER_V_CLAMP : 0);
        return BgfxBitmapSampler.apply(sampler, source.filters());
    }

    static int pageCapacity(
            int layers, int targetLayers, int byMemory, int maximumLayers, int existingPages, boolean adaptive) {
        if (layers < 1 || layers > maximumLayers || targetLayers < 1 || byMemory < 1 || existingPages < 0) {
            throw new IllegalArgumentException("invalid texture page capacity request");
        }
        var target = adaptive
                ? Math.min(targetLayers, existingPages == 0 ? 8 : existingPages == 1 ? 32 : targetLayers)
                : targetLayers;
        return Math.max(layers, Math.min(maximumLayers, Math.min(target, byMemory)));
    }

    static List<byte[]> rgbaMipChain(byte[] base, int width, int height) {
        var expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (base.length < expected) {
            throw new IllegalArgumentException("short RGBA base level: " + base.length + " < " + expected);
        }
        var levels = new ArrayList<byte[]>();
        var current = base.length == expected ? base : Arrays.copyOf(base, expected);
        var currentWidth = width;
        var currentHeight = height;
        levels.add(current);
        while (currentWidth > 1 || currentHeight > 1) {
            var nextWidth = Math.max(1, currentWidth >> 1);
            var nextHeight = Math.max(1, currentHeight >> 1);
            current = downsample(current, currentWidth, currentHeight, nextWidth, nextHeight);
            levels.add(current);
            currentWidth = nextWidth;
            currentHeight = nextHeight;
        }
        return List.copyOf(levels);
    }

    void advanceFrame() {
        advanceFrame(System.nanoTime() * 1e-9);
    }

    void advanceFrame(double seconds) {
        renderFrame++;
        bitmapSeconds = seconds;
    }

    double getBitmapSeconds() {
        return bitmapSeconds;
    }

    Clocks getBitmapClocks() {
        return bitmapClocks;
    }

    AuthoredMaterialRuntime getAuthoredMaterials() {
        return authoredMaterials;
    }

    long getRenderFrame() {
        return renderFrame;
    }

    ArrayPageMemory getArrayPageMemory() {
        long allocated = 0;
        long occupied = 0;
        long layers = 0;
        long occupiedLayers = 0;
        var pageCount = 0;
        for (var family : arrayPages.values()) {
            for (var page : family) {
                var bytes = page.entry.texture.getEstimatedBytes();
                var used = page.occupied.cardinality();
                allocated = Math.addExact(allocated, bytes);
                occupied = Math.addExact(occupied, bytes / page.capacity * used);
                layers += page.capacity;
                occupiedLayers += used;
                pageCount++;
            }
        }
        return new ArrayPageMemory(arrayPages.size(), pageCount, allocated, occupied, layers, occupiedLayers);
    }

    record ArrayPageMemory(
            int families,
            int pages,
            long allocatedBytes,
            long occupiedBytes,
            long capacityLayers,
            long occupiedLayers) {}

    TextureRef getWhite() {
        return white;
    }

    TextureRef getBlack() {
        return black;
    }

    TextureRef getTransparent() {
        return transparent;
    }

    TextureRef getWhiteCube() {
        return whiteCube;
    }

    TextureRef getPagedWhite() {
        return pagedWhite;
    }

    TextureRef getPagedBlack() {
        return pagedBlack;
    }

    TextureRef getPagedTransparent() {
        return pagedTransparent;
    }

    TextureRef getPagedTerrainProxy() {
        return pagedTerrainProxy;
    }

    int getResidentTextureCount() {
        return owned.size();
    }

    int getIdentityCacheSize() {
        return byIdentity.size()
                + pagedByIdentity.size()
                + cubesByIdentity.size()
                + singleLayerArraysByIdentity.size()
                + arraysByIdentity.size();
    }

    int getNamedCacheSize() {
        return byStableName.size();
    }

    long getResidentBytes() {
        var bytes = 0L;
        for (var texture : owned) {
            bytes = Math.addExact(bytes, texture.getEstimatedBytes());
        }
        return bytes;
    }

    int getOrdinaryDiffusePageGroupSize() {
        return ordinaryDiffusePageGroupSize;
    }

    <T> T withPrepared(BgfxPreparedTexturePayloads payload, Supplier<T> action) {
        requireOpen();
        if (payload == null || action == null) {
            throw new IllegalArgumentException("prepared texture scope requires payload and action");
        }
        if (prepared != null) {
            throw new IllegalStateException("prepared texture scopes cannot be nested");
        }
        prepared = payload;
        try {
            return action.get();
        } finally {
            prepared = null;
        }
    }

    void withPrepared(BgfxPreparedTexturePayloads payload, Runnable action) {
        withPrepared(payload, () -> {
            action.run();
            return null;
        });
    }

    TextureRef createGeneratedRgba(String label, int width, int height, byte[] rgba, boolean mipmapped, boolean clamp) {
        requireOpen();
        validateDimensions(width, height);
        var levels = mipmapped ? rgbaMipChain(rgba, width, height) : List.of(checkedRgba(rgba, width, height));
        var texture = BgfxTexture.create2D(
                device.getResources(),
                label,
                width,
                height,
                levels.size() > 1,
                1,
                BGFX_TEXTURE_FORMAT_RGBA8,
                0L,
                concatenate(levels));
        owned.add(texture);
        var sampler = clamp ? BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP : 0;
        return lease(new TextureEntry(texture, sampler, true, false));
    }

    TextureRef createPreparedGeneratedRgba(String label, Payload payload, boolean clamp) {
        requireOpen();
        if (payload == null
                || payload.isCube()
                || payload.getLayers() != 1
                || payload.getFormat() != BGFX_TEXTURE_FORMAT_RGBA8) {
            throw new IllegalArgumentException("generated RGBA texture requires a prepared 2D RGBA8 payload");
        }
        var texture = createPreparedTexture(label, payload);
        var sampler = clamp ? BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP : 0;
        return lease(new TextureEntry(texture, sampler, payload.hasAlpha(), false));
    }

    TextureRef createGeneratedPagedRgba(
            String label, int width, int height, byte[] rgba, boolean mipmapped, boolean clamp) {
        requireOpen();
        validateDimensions(width, height);
        var levels = mipmapped ? rgbaMipChain(rgba, width, height) : List.of(checkedRgba(rgba, width, height));
        return createPagedRgba(
                label,
                width,
                height,
                levels.size(),
                byteCount(levels),
                clamp,
                BGFX_TEXTURE_FORMAT_RGBA8,
                null,
                List.of(levels));
    }

    TextureRef createPreparedGeneratedPagedRgba(String label, Payload payload, boolean clamp) {
        requireOpen();
        if (payload == null
                || payload.isCube()
                || payload.getLayers() != 1
                || payload.getFormat() != BGFX_TEXTURE_FORMAT_RGBA8
                        && payload.getFormat() != BGFX_TEXTURE_FORMAT_BGRA4) {
            throw new IllegalArgumentException(
                    "generated paged RGBA texture requires a prepared 2D RGBA8 or BGRA4 payload");
        }
        return createPagedRgba(
                label,
                payload.getWidth(),
                payload.getHeight(),
                payload.getMipLevels(),
                payload.getRetainedBytes(),
                clamp,
                payload.getFormat(),
                payload,
                null);
    }

    TextureRef getTerrainAlphaAtlas(String label, TerrainAlphaAtlas atlas) {
        requireOpen();
        var hit = terrainAlphaByIdentity.get(atlas);
        if (hit != null) {
            return lease(hit);
        }
        validateDimensions(atlas.width(), atlas.height());
        var levels = List.of(checkedRgba(atlas.rgba(), atlas.width(), atlas.height()));
        var sampler = BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP;
        var pageKey = new ArrayPageKey(atlas.width(), atlas.height(), 1, BGFX_TEXTURE_FORMAT_RGBA8, sampler);
        var allocation = allocateArray(
                pageKey, 1, byteCount(levels), materialPageTargetLayers, MATERIAL_PAGE_TARGET_BYTES, label);
        try {
            uploadArray(allocation, null, List.of(levels), 1);
            terrainAlphaByIdentity.put(atlas, allocation);
            allocation.terrainAlphaKeys.put(atlas, Boolean.TRUE);
            return lease(allocation);
        } catch (RuntimeException | Error failure) {
            discardUnleased(allocation);
            throw failure;
        }
    }

    boolean updateTerrainAlphaAtlas(TerrainAlphaAtlas atlas, int x, int y, int width, int height) {
        requireOpen();
        var allocation = terrainAlphaByIdentity.get(atlas);
        if (allocation == null) {
            return false;
        }
        if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > atlas.width() || y + height > atlas.height()) {
            throw new IllegalArgumentException("terrain weight update lies outside its atlas");
        }
        var region = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
        for (var row = 0; row < height; row++) {
            System.arraycopy(atlas.rgba(), ((y + row) * atlas.width() + x) * 4, region, row * width * 4, width * 4);
        }
        allocation.page.entry.texture.update2D(
                allocation.baseLayer, 0, x, y, width, height, toDirectBuffer(region), BgfxTexture.AUTO_PITCH);
        return true;
    }

    Payload getPreparedTerrainAlpha(MultiTexture source, int group) {
        return prepared == null ? null : prepared.getTerrainAlpha(source, group);
    }

    TextureRef load(Texture source, String stableName) {
        requireOpen();
        if (source == null || source.data().isEmpty() || source.data().getFirst().length == 0) {
            return white;
        }
        var payload = prepared == null ? null : prepared.getTexture(source);
        var identityHit = byIdentity.get(source);
        if (identityHit != null) {
            return lease(identityHit);
        }
        var key = stableName == null || stableName.isBlank() ? null : stableName.toLowerCase();
        if (key != null) {
            var namedHit = byStableName.get(key);
            if (namedHit != null) {
                rememberTextureIdentity(source, namedHit);
                return lease(namedHit);
            }
        }
        validateDimensions(source.width(), source.height());
        var ref = payload == null
                ? create2D(source, key == null ? "scene/texture-" + owned.size() : "scene/" + key)
                : create2D(source, key == null ? "scene/texture-" + owned.size() : "scene/" + key, payload);
        rememberTextureIdentity(source, ref.entry);
        if (key != null) {
            rememberTextureName(key, ref.entry);
        }
        return ref;
    }

    TextureRef loadPaged(Texture source, String stableName) {
        requireOpen();
        if (source == null || source.data().isEmpty() || source.data().getFirst().length == 0) {
            return pagedWhite;
        }
        var payload = prepared == null ? null : prepared.getTexture(source);
        var identityHit = pagedByIdentity.get(source);
        if (identityHit != null) {
            return lease(identityHit);
        }
        var key = stableName == null || stableName.isBlank() ? null : stableName.toLowerCase();
        if (key != null) {
            var namedHit = pagedByStableName.get(key);
            if (namedHit != null) {
                rememberPagedIdentity(source, namedHit);
                return lease(namedHit);
            }
        }

        var upload = source.uploadView();
        validateDimensions(upload.width(), upload.height());
        var sampler = pagedSampler(source);
        var known = prepared == null ? null : prepared.getContent(source);
        var content = known != null ? known : BgfxTextureContent.of(upload, sampler);
        var sameContent = pagedByContent.get(content);
        if (sameContent != null) {
            rememberPagedIdentity(source, sameContent);
            if (key != null) {
                rememberPagedName(key, sameContent);
            }
            return lease(sameContent);
        }
        int format;
        int mipLevels;
        long bytesPerLayer;
        List<List<byte[]>> unpackedLayers = null;
        if (payload != null) {
            if (payload.isCube()
                    || payload.getLayers() != 1
                    || payload.getWidth() != upload.width()
                    || payload.getHeight() != upload.height()) {
                throw new IllegalArgumentException("prepared paged texture does not match its source");
            }
            format = payload.getFormat();
            mipLevels = payload.getMipLevels();
            bytesPerLayer = payload.getRetainedBytes();
        } else {
            var nativeFormat = BgfxTextureCompression.nativeFormat(device.getCapabilities(), upload);
            var compressed = nativeFormat >= 0;
            var levels = compressed
                    ? BgfxTextureMipmaps.splitCompressedLevels(upload)
                    : BgfxTextureMipmaps.decodeRgbaLevels(upload, decodeBaseRgba(upload));
            format = compressed ? nativeFormat : BGFX_TEXTURE_FORMAT_RGBA8;
            mipLevels = levels.size();
            bytesPerLayer = byteCount(levels);
            unpackedLayers = List.of(levels);
        }
        var pageKey = new ArrayPageKey(upload.width(), upload.height(), mipLevels, format, sampler);
        var label = key == null ? "scene/paged-" + owned.size() : "scene/" + key;
        var allocation =
                allocateArray(pageKey, 1, bytesPerLayer, materialPageTargetLayers, MATERIAL_PAGE_TARGET_BYTES, label);
        try {
            uploadArray(allocation, payload, unpackedLayers, mipLevels);
            rememberPagedIdentity(source, allocation);
            if (key != null) {
                rememberPagedName(key, allocation);
            }
            allocation.content = content;
            pagedByContent.put(content, allocation);
            residentContents.add(content);
            return lease(allocation);
        } catch (RuntimeException | Error failure) {
            discardUnleased(allocation);
            throw failure;
        }
    }

    Set<BgfxTextureContent> getResidentContents() {
        return residentContents;
    }

    TextureRef loadCube(Cubemap source, String stableName) {
        requireOpen();
        if (source == null || source.faces().size() != 6) {
            return white;
        }
        var payload = prepared == null ? null : prepared.getCube(source);
        var hit = cubesByIdentity.get(source);
        if (hit != null) {
            return lease(hit);
        }
        var first = source.faces().getFirst();
        if (first == null || first.width() != first.height()) {
            return white;
        }
        validateDimensions(first.width(), first.height());
        var firstUpload = first.uploadView();
        var size = firstUpload.width();
        var label = stableName == null || stableName.isBlank()
                ? "scene/cubemap-" + owned.size()
                : "scene/" + stableName.toLowerCase();
        BgfxTexture texture;
        if (payload != null) {
            texture = createPreparedTexture(label, payload);
        } else {
            var allLevels = new ArrayList<byte[]>();
            for (var face : source.faces()) {
                if (face == null
                        || face.width() != first.width()
                        || face.height() != first.height()
                        || face.data().size() != first.data().size()) {
                    throw new IllegalArgumentException("cubemap faces must have one square extent and mip count");
                }
                var upload = face.uploadView(first.firstMip());
                allLevels.addAll(BgfxTextureMipmaps.decodeRgbaLevels(upload, decodeBaseRgba(upload)));
            }
            texture = BgfxTexture.createCube(
                    device.getResources(),
                    label,
                    size,
                    firstUpload.data().size(),
                    1,
                    BGFX_TEXTURE_FORMAT_RGBA8,
                    0L,
                    concatenate(allLevels));
            owned.add(texture);
        }
        var entry = new TextureEntry(texture, BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP, true, false);
        var ref = lease(entry);
        rememberCubeIdentity(source, ref.entry);
        return ref;
    }

    TextureRef loadArray(List<Texture> sources, String stableName) {
        requireOpen();
        if (sources == null
                || sources.isEmpty()
                || sources.size() > 0xffff
                || sources.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("texture array requires 1..65535 non-null layers");
        }
        var preparedArray = prepared == null ? null : prepared.findArray(sources);
        var identityHit = sources.size() == 1
                ? singleLayerArraysByIdentity.get(sources.getFirst())
                : arraysByIdentity.get(sources);
        if (identityHit != null) {
            return lease(identityHit);
        }
        var key = stableName == null || stableName.isBlank() ? null : stableName.toLowerCase();
        if (key != null) {
            var namedHit = arraysByStableName.get(key);
            if (namedHit != null) {
                rememberArrayIdentity(sources, namedHit);
                return lease(namedHit);
            }
        }

        var first = sources.getFirst();
        validateArraySource(first, first.width(), first.height());
        for (var source : sources) {
            validateArraySource(source, first.width(), first.height());
        }
        var label = key == null ? "scene/array-" + owned.size() : "scene/" + key;
        int format;
        int mipLevels;
        long bytesPerLayer;
        List<List<byte[]>> unpackedLayers = null;
        if (preparedArray != null) {
            if (preparedArray.isCube()
                    || preparedArray.getLayers() != sources.size()
                    || preparedArray.getWidth() != first.width()
                    || preparedArray.getHeight() != first.height()) {
                throw new IllegalArgumentException("prepared lightmap array does not match its source");
            }
            format = preparedArray.getFormat();
            mipLevels = preparedArray.getMipLevels();
            bytesPerLayer = preparedArray.getRetainedBytes() / preparedArray.getLayers();
        } else {
            var nativeFormat = BgfxTextureCompression.nativeFormat(device.getCapabilities(), first.format());
            var compressed = nativeFormat >= 0;
            for (var source : sources) {
                compressed &= source.format() == first.format();
            }
            format = compressed ? nativeFormat : BGFX_TEXTURE_FORMAT_RGBA8;
            unpackedLayers = new ArrayList<>(sources.size());
            for (var source : sources) {
                unpackedLayers.add(
                        compressed
                                ? completeCompressedChain(source)
                                : rgbaMipChain(decodeBaseRgba(source), source.width(), source.height()));
            }
            mipLevels = unpackedLayers.getFirst().size();
            bytesPerLayer = byteCount(unpackedLayers.getFirst());
        }
        var sampler = (first.clampU() ? BGFX_SAMPLER_U_CLAMP : 0) | (first.clampV() ? BGFX_SAMPLER_V_CLAMP : 0);
        sampler = BgfxBitmapSampler.apply(sampler, first.filters());
        var pageKey = new ArrayPageKey(first.width(), first.height(), mipLevels, format, sampler);
        var allocation = allocateArray(
                pageKey, sources.size(), bytesPerLayer, LIGHTMAP_PAGE_TARGET_LAYERS, LIGHTMAP_PAGE_TARGET_BYTES, label);
        try {
            uploadArray(allocation, preparedArray, unpackedLayers, mipLevels);
            rememberArrayIdentity(sources, allocation);
            if (key != null) {
                rememberArrayName(key, allocation);
            }
            return lease(allocation);
        } catch (RuntimeException | Error failure) {
            discardUnleased(allocation);
            throw failure;
        }
    }

    void registerLightmapLayers(String map, TextureRef array, List<Texture> layers, Entity source) {
        for (var layer = 0; layer < layers.size(); layer++) {
            var key = source.getBspLightmapSource(layers.get(layer));
            if (key >= 0 && array.allocation != null) {
                var known = lightmapLayers
                        .computeIfAbsent(map, ignored -> new HashMap<>())
                        .computeIfAbsent(key, ignored -> new ArrayList<>());
                known.removeIf(old -> old.array().released);
                known.add(new LightmapLayer(array, layer));
            }
        }
    }

    long replaceLightmapLayers(String map, int key, Texture replacement) {
        requireOpen();
        var byKey = lightmapLayers.get(map);
        var layers = byKey == null ? null : byKey.get(key);
        if (layers == null) {
            return 0;
        }
        layers.removeIf(layer -> layer.array().released);
        if (layers.isEmpty()) {
            byKey.remove(key);
            return 0;
        }
        long bytes = 0;
        for (var layer : layers) {
            var allocation = layer.array().allocation;
            var page = allocation.page.key;
            if (replacement.width() != page.width || replacement.height() != page.height) {
                continue;
            }
            var compressed = page.format != BGFX_TEXTURE_FORMAT_RGBA8;
            if (compressed
                    && BgfxTextureCompression.nativeFormat(device.getCapabilities(), replacement.format())
                            != page.format) {
                continue;
            }
            var levels = compressed
                    ? completeCompressedChain(replacement)
                    : rgbaMipChain(decodeBaseRgba(replacement), replacement.width(), replacement.height());
            if (levels.size() < page.mipLevels) {
                continue;
            }
            for (var mip = 0; mip < page.mipLevels; mip++) {
                allocation.page.entry.texture.update2D(
                        allocation.baseLayer + layer.layer(),
                        mip,
                        0,
                        0,
                        Math.max(1, page.width >> mip),
                        Math.max(1, page.height >> mip),
                        toDirectBuffer(levels.get(mip)),
                        BgfxTexture.AUTO_PITCH);
                bytes += levels.get(mip).length;
            }
        }
        return bytes;
    }

    private record LightmapLayer(TextureRef array, int layer) {}

    byte[] decodeBaseRgba(Texture source) {
        return TexturePixels.decodeBaseRgba(source);
    }

    void clearNamed() {
        requireOpen();
        byStableName.forEach((key, entry) -> entry.stableNames.remove(key));
        arraysByStableName.forEach((key, allocation) -> allocation.arrayNames.remove(key));
        pagedByStableName.forEach((key, allocation) -> allocation.pagedNames.remove(key));
        byStableName.clear();
        arraysByStableName.clear();
        pagedByStableName.clear();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = owned.size() - 1; index >= 0; index--) {
            try {
                owned.get(index).close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        owned.clear();
        byStableName.clear();
        arraysByStableName.clear();
        pagedByStableName.clear();
        pagedByContent.clear();
        residentContents.clear();
        byIdentity.clear();
        pagedByIdentity.clear();
        terrainAlphaByIdentity.clear();
        cubesByIdentity.clear();
        singleLayerArraysByIdentity.clear();
        arraysByIdentity.clear();
        arrayPages.clear();
        bitmapClocks.clear();
        authoredMaterials.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private TextureRef createPagedRgba(
            String label,
            int width,
            int height,
            int mipLevels,
            long bytesPerLayer,
            boolean clamp,
            int format,
            Payload payload,
            List<List<byte[]>> unpackedLayers) {
        var sampler = clamp ? BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP : 0;
        var pageKey = new ArrayPageKey(width, height, mipLevels, format, sampler);
        var allocation =
                allocateArray(pageKey, 1, bytesPerLayer, materialPageTargetLayers, MATERIAL_PAGE_TARGET_BYTES, label);
        try {
            uploadArray(allocation, payload, unpackedLayers, mipLevels);
            return lease(allocation);
        } catch (RuntimeException | Error failure) {
            discardUnleased(allocation);
            throw failure;
        }
    }

    private void validateArraySource(Texture source, int width, int height) {
        if (source.width() != width
                || source.height() != height
                || source.data().isEmpty()
                || source.data().getFirst().length == 0) {
            throw new IllegalArgumentException(
                    "texture-array layers must have one non-empty common extent " + width + 'x' + height);
        }
        validateDimensions(source.width(), source.height());
    }

    private void rememberArrayIdentity(List<Texture> sources, ArrayAllocation allocation) {
        if (sources.size() == 1) {
            var source = sources.getFirst();
            var previous = singleLayerArraysByIdentity.put(source, allocation);
            if (previous != null && previous != allocation) {
                previous.singleLayerKeys.remove(source);
            }
            allocation.singleLayerKeys.put(source, Boolean.TRUE);
        } else {
            var previous = arraysByIdentity.put(sources, allocation);
            if (previous != null && previous != allocation) {
                previous.arrayKeys.remove(sources);
            }
            allocation.arrayKeys.put(sources, Boolean.TRUE);
        }
    }

    private void rememberArrayName(String key, ArrayAllocation allocation) {
        var previous = arraysByStableName.put(key, allocation);
        if (previous != null && previous != allocation) {
            previous.arrayNames.remove(key);
        }
        allocation.arrayNames.add(key);
    }

    private void rememberPagedIdentity(Texture source, ArrayAllocation allocation) {
        var previous = pagedByIdentity.put(source, allocation);
        if (previous != null && previous != allocation) {
            previous.pagedKeys.remove(source);
        }
        allocation.pagedKeys.put(source, Boolean.TRUE);
    }

    private void rememberPagedName(String key, ArrayAllocation allocation) {
        var previous = pagedByStableName.put(key, allocation);
        if (previous != null && previous != allocation) {
            previous.pagedNames.remove(key);
        }
        allocation.pagedNames.add(key);
    }

    private void rememberTextureIdentity(Texture source, TextureEntry entry) {
        var previous = byIdentity.put(source, entry);
        if (previous != null && previous != entry) {
            previous.textureKeys.remove(source);
        }
        entry.textureKeys.put(source, Boolean.TRUE);
    }

    private void rememberCubeIdentity(Cubemap source, TextureEntry entry) {
        var previous = cubesByIdentity.put(source, entry);
        if (previous != null && previous != entry) {
            previous.cubeKeys.remove(source);
        }
        entry.cubeKeys.put(source, Boolean.TRUE);
    }

    private void rememberTextureName(String key, TextureEntry entry) {
        var previous = byStableName.put(key, entry);
        if (previous != null && previous != entry) {
            previous.stableNames.remove(key);
        }
        entry.stableNames.add(key);
    }

    private ArrayAllocation allocateArray(
            ArrayPageKey key, int layers, long bytesPerLayer, int targetLayers, long targetBytes, String label) {
        if (layers > device.getCapabilities().maxTextureLayers()) {
            throw new IllegalArgumentException("lightmap array exceeds renderer layer limit: " + layers + " > "
                    + device.getCapabilities().maxTextureLayers());
        }
        var pages = arrayPages.computeIfAbsent(key, ignored -> new ArrayList<>());
        for (var page : pages) {
            var base = page.allocate(layers);
            if (base >= 0) {
                return new ArrayAllocation(page, base, layers, label);
            }
        }
        var byMemory = bytesPerLayer <= 0L ? targetLayers : (int) Math.max(1L, targetBytes / bytesPerLayer);
        var capacity = pageCapacity(
                layers,
                targetLayers,
                byMemory,
                device.getCapabilities().maxTextureLayers(),
                pages.size(),
                ADAPTIVE_TEXTURE_PAGES);
        var texture = BgfxTexture.create2D(
                device.getResources(),
                "scene/lightmap-page-" + key.width + 'x' + key.height + '-' + pages.size() + '-' + owned.size(),
                key.width,
                key.height,
                key.mipLevels,
                capacity,
                key.format,
                0L,
                null);
        owned.add(texture);
        var entry = new TextureEntry(texture, key.samplerFlags, false, false);
        var page = new ArrayPage(key, entry, capacity);
        for (var index = pages.size() - 1; index >= 0; index--) {
            var primary = pages.get(index);
            if (primary.gpuPairPrimary == primary && primary.gpuPairSecondary == null) {
                page.gpuPairPrimary = primary;
                primary.gpuPairSecondary = page;
                break;
            }
        }
        for (var index = pages.size() - 1; index >= 0; index--) {
            var primary = pages.get(index);
            if (primary.gpuQuadPrimary != primary) {
                continue;
            }
            var slot = primary.firstFreeGpuQuadSlot();
            if (slot >= 0) {
                page.gpuQuadPrimary = primary;
                page.gpuQuadOrdinal = slot;
                primary.gpuQuadPages[slot] = page;
                break;
            }
        }
        entry.arrayPage = page;
        pages.add(page);
        var base = page.allocate(layers);
        if (base < 0) {
            throw new IllegalStateException("new lightmap page cannot satisfy its first allocation");
        }
        return new ArrayAllocation(page, base, layers, label);
    }

    private void uploadArray(
            ArrayAllocation allocation, Payload preparedArray, List<List<byte[]>> unpackedLayers, int mipLevels) {
        for (var layer = 0; layer < allocation.layers; layer++) {
            for (var mip = 0; mip < mipLevels; mip++) {
                var width = Math.max(1, allocation.page.key.width >> mip);
                var height = Math.max(1, allocation.page.key.height >> mip);
                var data = preparedArray != null
                        ? preparedArray.getSubresource(layer, mip)
                        : toDirectBuffer(unpackedLayers.get(layer).get(mip));
                allocation.page.entry.texture.update2D(
                        allocation.baseLayer + layer, mip, 0, 0, width, height, data, BgfxTexture.AUTO_PITCH);
            }
        }
    }

    private void discardUnleased(ArrayAllocation allocation) {
        allocation.page.release(allocation.baseLayer, allocation.layers);
        if (allocation.page.isEmpty()) {
            destroyPage(allocation.page);
        }
    }

    private void destroyPage(ArrayPage page) {
        if (page.gpuPairPrimary == page) {
            var secondary = page.gpuPairSecondary;
            if (secondary != null) {
                secondary.gpuPairPrimary = secondary;
                secondary.gpuPairSecondary = null;
            }
        } else {
            page.gpuPairPrimary.gpuPairSecondary = null;
        }
        page.gpuPairPrimary = page;
        page.gpuPairSecondary = null;
        if (page.gpuQuadPrimary == page) {
            var remaining = new ArrayList<ArrayPage>(3);
            for (var slot = 1; slot < page.gpuQuadPages.length; slot++) {
                if (page.gpuQuadPages[slot] != null) {
                    remaining.add(page.gpuQuadPages[slot]);
                }
            }
            if (!remaining.isEmpty()) {
                var primary = remaining.getFirst();
                Arrays.fill(primary.gpuQuadPages, null);
                primary.gpuQuadPrimary = primary;
                primary.gpuQuadOrdinal = 0;
                primary.gpuQuadPages[0] = primary;
                for (var index = 1; index < remaining.size(); index++) {
                    var member = remaining.get(index);
                    member.gpuQuadPrimary = primary;
                    member.gpuQuadOrdinal = index;
                    primary.gpuQuadPages[index] = member;
                }
            }
        } else {
            page.gpuQuadPrimary.gpuQuadPages[page.gpuQuadOrdinal] = null;
        }
        page.gpuQuadPrimary = page;
        page.gpuQuadOrdinal = 0;
        Arrays.fill(page.gpuQuadPages, null);
        page.gpuQuadPages[0] = page;
        var pages = arrayPages.get(page.key);
        if (pages != null) {
            pages.remove(page);
            if (pages.isEmpty()) {
                arrayPages.remove(page.key);
            }
        }
        owned.remove(page.entry.texture);
        page.entry.texture.close();
    }

    private TextureRef create2D(Texture source, String label) {
        source = source.uploadView();
        var nativeFormat = BgfxTextureCompression.nativeFormat(device.getCapabilities(), source);
        var compressed = nativeFormat >= 0;
        List<byte[]> levels;
        int format;
        if (compressed) {
            levels = BgfxTextureMipmaps.splitCompressedLevels(source);
            format = nativeFormat;
        } else {
            levels = BgfxTextureMipmaps.decodeRgbaLevels(source, decodeBaseRgba(source));
            format = BGFX_TEXTURE_FORMAT_RGBA8;
        }
        var texture = BgfxTexture.create2D(
                device.getResources(),
                label,
                source.width(),
                source.height(),
                levels.size(),
                1,
                format,
                0L,
                concatenate(levels));
        owned.add(texture);
        var sampler = (source.clampU() ? BGFX_SAMPLER_U_CLAMP : 0) | (source.clampV() ? BGFX_SAMPLER_V_CLAMP : 0);
        sampler = BgfxBitmapSampler.apply(sampler, source.filters());
        var hasAlpha = BgfxPreparedTexturePayloads.doesTextureCarryAlpha(source);
        return lease(new TextureEntry(texture, sampler, hasAlpha, false));
    }

    private TextureRef create2D(Texture source, String label, Payload payload) {
        source = source.uploadView();
        if (payload.isCube()
                || payload.getLayers() != 1
                || payload.getWidth() != source.width()
                || payload.getHeight() != source.height()) {
            throw new IllegalArgumentException("prepared 2D texture payload does not match its source");
        }
        var texture = createPreparedTexture(label, payload);
        var sampler = (source.clampU() ? BGFX_SAMPLER_U_CLAMP : 0) | (source.clampV() ? BGFX_SAMPLER_V_CLAMP : 0);
        sampler = BgfxBitmapSampler.apply(sampler, source.filters());
        return lease(new TextureEntry(texture, sampler, payload.hasAlpha(), false));
    }

    private BgfxTexture createPreparedTexture(String label, Payload payload) {
        var texture = payload.isCube()
                ? BgfxTexture.createCube(
                        device.getResources(),
                        label,
                        payload.getWidth(),
                        payload.getMipLevels(),
                        payload.getLayers(),
                        payload.getFormat(),
                        0L,
                        payload.getBytes())
                : BgfxTexture.create2D(
                        device.getResources(),
                        label,
                        payload.getWidth(),
                        payload.getHeight(),
                        payload.getMipLevels(),
                        payload.getLayers(),
                        payload.getFormat(),
                        0L,
                        payload.getBytes());
        owned.add(texture);
        return texture;
    }

    private List<byte[]> completeCompressedChain(Texture source) {
        return DxtMipCodec.completeAuthoredChain(source.format(), source.width(), source.height(), source.data())
                .levels();
    }

    private TextureRef createSolidTexture(String label, int rgba) {
        var data = ByteBuffer.allocateDirect(4);
        data.put((byte) (rgba >>> 24));
        data.put((byte) (rgba >>> 16));
        data.put((byte) (rgba >>> 8));
        data.put((byte) rgba);
        data.flip();
        var texture =
                BgfxTexture.create2D(device.getResources(), label, 1, 1, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, 0L, data);
        owned.add(texture);
        return newBaselineRef(
                new TextureEntry(texture, BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP, rgba != 0xffffffff, true));
    }

    private TextureRef pagedSolid(String label, int rgba) {
        var key = new ArrayPageKey(1, 1, 1, BGFX_TEXTURE_FORMAT_RGBA8, BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP);
        var allocation = allocateArray(key, 1, 4L, materialPageTargetLayers, MATERIAL_PAGE_TARGET_BYTES, label);
        var data = ByteBuffer.allocateDirect(4);
        data.put((byte) (rgba >>> 24));
        data.put((byte) (rgba >>> 16));
        data.put((byte) (rgba >>> 8));
        data.put((byte) rgba);
        data.flip();
        allocation.page.entry.texture.update2D(allocation.baseLayer, 0, 0, 0, 1, 1, data, BgfxTexture.AUTO_PITCH);
        return new TextureRef(this, allocation.page.entry, allocation, true);
    }

    private TextureRef solidCube(String label, int rgba) {
        var data = ByteBuffer.allocateDirect(6 * 4);
        for (var face = 0; face < 6; face++) {
            data.put((byte) (rgba >>> 24));
            data.put((byte) (rgba >>> 16));
            data.put((byte) (rgba >>> 8));
            data.put((byte) rgba);
        }
        data.flip();
        var texture =
                BgfxTexture.createCube(device.getResources(), label, 1, false, 1, BGFX_TEXTURE_FORMAT_RGBA8, 0L, data);
        owned.add(texture);
        return newBaselineRef(new TextureEntry(texture, BGFX_SAMPLER_U_CLAMP | BGFX_SAMPLER_V_CLAMP, false, true));
    }

    private TextureRef newBaselineRef(TextureEntry entry) {
        return new TextureRef(this, entry, null, true);
    }

    private TextureRef lease(TextureEntry entry) {
        if (entry.baseline) {
            return new TextureRef(this, entry, null, true);
        }
        entry.references = Math.addExact(entry.references, 1);
        return new TextureRef(this, entry, null, false);
    }

    private TextureRef lease(ArrayAllocation allocation) {
        allocation.references = Math.addExact(allocation.references, 1);
        allocation.page.entry.references = Math.addExact(allocation.page.entry.references, 1);
        return new TextureRef(this, allocation.page.entry, allocation, false);
    }

    private void release(TextureRef ref) {
        if (ref.baselineLease || ref.released) {
            return;
        }
        ref.released = true;
        var entry = ref.activeEntry();
        if (entry.references <= 0) {
            throw new IllegalStateException(
                    "texture reference count underflow for handle " + Short.toUnsignedInt(entry.texture.getHandle()));
        }
        if (ref.allocation != null) {
            var allocation = ref.allocation;
            if (allocation.references <= 0) {
                throw new IllegalStateException(
                        "lightmap page reference count underflow at layer " + allocation.baseLayer);
            }
            allocation.references--;
            if (allocation.references == 0 && !closed) {
                removeAllocationAliases(allocation);
                allocation.page.release(allocation.baseLayer, allocation.layers);
            }
        }
        entry.references--;
        if (entry.references != 0 || closed) {
            return;
        }
        if (entry.arrayPage != null) {
            if (!entry.arrayPage.isEmpty()) {
                return;
            }
            destroyPage(entry.arrayPage);
            return;
        }
        removeTextureAliases(entry);
        owned.remove(entry.texture);
        entry.texture.close();
    }

    private void removeAllocationAliases(ArrayAllocation allocation) {
        for (var source : allocation.singleLayerKeys.getKeySet()) {
            if (singleLayerArraysByIdentity.get(source) == allocation) {
                singleLayerArraysByIdentity.remove(source);
            }
        }
        allocation.singleLayerKeys.clear();
        for (var sources : allocation.arrayKeys.getKeySet()) {
            if (arraysByIdentity.get(sources) == allocation) {
                arraysByIdentity.remove(sources);
            }
        }
        allocation.arrayKeys.clear();
        for (var key : allocation.arrayNames) {
            if (arraysByStableName.get(key) == allocation) {
                arraysByStableName.remove(key);
            }
        }
        allocation.arrayNames.clear();
        for (var source : allocation.pagedKeys.getKeySet()) {
            if (pagedByIdentity.get(source) == allocation) {
                pagedByIdentity.remove(source);
            }
        }
        allocation.pagedKeys.clear();
        for (var atlas : allocation.terrainAlphaKeys.getKeySet()) {
            if (terrainAlphaByIdentity.get(atlas) == allocation) {
                terrainAlphaByIdentity.remove(atlas);
            }
        }
        allocation.terrainAlphaKeys.clear();
        for (var key : allocation.pagedNames) {
            if (pagedByStableName.get(key) == allocation) {
                pagedByStableName.remove(key);
            }
        }
        allocation.pagedNames.clear();
        if (allocation.content != null) {
            pagedByContent.remove(allocation.content);
            residentContents.remove(allocation.content);
            allocation.content = null;
        }
    }

    private void removeTextureAliases(TextureEntry entry) {
        for (var source : entry.textureKeys.getKeySet()) {
            if (byIdentity.get(source) == entry) {
                byIdentity.remove(source);
            }
        }
        entry.textureKeys.clear();
        for (var source : entry.cubeKeys.getKeySet()) {
            if (cubesByIdentity.get(source) == entry) {
                cubesByIdentity.remove(source);
            }
        }
        entry.cubeKeys.clear();
        for (var key : entry.stableNames) {
            if (byStableName.get(key) == entry) {
                byStableName.remove(key);
            }
        }
        entry.stableNames.clear();
    }

    private void validateDimensions(int width, int height) {
        if (width < 1
                || height < 1
                || width > device.getCapabilities().maxTextureSize()
                || height > device.getCapabilities().maxTextureSize()) {
            throw new IllegalArgumentException("scene texture extent is unsupported: " + width + "x" + height);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("bgfx texture cache is closed");
        }
    }

    private static final class TextureEntry {
        private BgfxTexture texture;
        private final int samplerFlags;
        private final boolean hasAlpha;
        private final boolean baseline;
        private final WeakIdentityMap<Texture, Boolean> textureKeys = new WeakIdentityMap<>();
        private final WeakIdentityMap<Cubemap, Boolean> cubeKeys = new WeakIdentityMap<>();
        private final Set<String> stableNames = new HashSet<>();
        private ArrayPage arrayPage;
        private int references;

        private TextureEntry(BgfxTexture texture, int samplerFlags, boolean hasAlpha, boolean baseline) {
            if (texture == null) {
                throw new IllegalArgumentException("texture entry requires a texture");
            }
            this.texture = texture;
            this.samplerFlags = samplerFlags;
            this.hasAlpha = hasAlpha;
            this.baseline = baseline;
        }
    }

    private record ArrayPageKey(int width, int height, int mipLevels, int format, int samplerFlags) {
        private ArrayPageKey {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("invalid lightmap page extent");
            }
        }
    }

    private static final class ArrayPage {
        private final ArrayPageKey key;
        private final TextureEntry entry;
        private final int capacity;
        private final BitSet occupied;
        private ArrayPage gpuPairPrimary;
        private ArrayPage gpuPairSecondary;
        private ArrayPage gpuQuadPrimary;
        private int gpuQuadOrdinal;
        private final ArrayPage[] gpuQuadPages = new ArrayPage[4];

        private ArrayPage(ArrayPageKey key, TextureEntry entry, int capacity) {
            if (key == null || entry == null || capacity < 1) {
                throw new IllegalArgumentException("invalid lightmap page");
            }
            this.key = key;
            this.entry = entry;
            this.capacity = capacity;
            this.occupied = new BitSet(capacity);
            this.gpuPairPrimary = this;
            this.gpuQuadPrimary = this;
            this.gpuQuadPages[0] = this;
        }

        private int firstFreeGpuQuadSlot() {
            for (var slot = 1; slot < gpuQuadPages.length; slot++) {
                if (gpuQuadPages[slot] == null) {
                    return slot;
                }
            }
            return -1;
        }

        private int allocate(int layers) {
            if (layers < 1 || layers > capacity) {
                return -1;
            }
            var base = 0;
            while (base + layers <= capacity) {
                var occupiedLayer = occupied.nextSetBit(base);
                if (occupiedLayer < 0 || occupiedLayer >= base + layers) {
                    occupied.set(base, base + layers);
                    return base;
                }
                base = occupied.nextClearBit(occupiedLayer);
            }
            return -1;
        }

        private void release(int base, int layers) {
            if (base < 0 || layers < 1 || base + layers > capacity || occupied.nextClearBit(base) < base + layers) {
                throw new IllegalStateException("invalid or duplicate lightmap page release");
            }
            occupied.clear(base, base + layers);
        }

        private boolean isEmpty() {
            return occupied.isEmpty();
        }
    }

    private static final class ArrayAllocation {
        private ArrayPage page;
        private int baseLayer;
        private final int layers;
        private final String label;
        private final WeakIdentityMap<Texture, Boolean> singleLayerKeys = new WeakIdentityMap<>();
        private final WeakIdentityMap<List<Texture>, Boolean> arrayKeys = new WeakIdentityMap<>();
        private final WeakIdentityMap<Texture, Boolean> pagedKeys = new WeakIdentityMap<>();
        private final WeakIdentityMap<TerrainAlphaAtlas, Boolean> terrainAlphaKeys = new WeakIdentityMap<>();
        private final Set<String> arrayNames = new HashSet<>();
        private final Set<String> pagedNames = new HashSet<>();
        private BgfxTextureContent content;
        private int references;

        private ArrayAllocation(ArrayPage page, int baseLayer, int layers, String label) {
            if (page == null
                    || baseLayer < 0
                    || layers < 1
                    || baseLayer + layers > page.capacity
                    || label == null
                    || label.isBlank()) {
                throw new IllegalArgumentException("invalid lightmap page allocation");
            }
            this.page = page;
            this.baseLayer = baseLayer;
            this.layers = layers;
            this.label = label;
        }
    }

    static final class TextureRef implements AutoCloseable {
        private final BgfxTextureCache owner;
        private final TextureEntry entry;
        private final ArrayAllocation allocation;
        private final boolean baselineLease;
        private boolean released;

        private TextureRef(
                BgfxTextureCache owner, TextureEntry entry, ArrayAllocation allocation, boolean baselineLease) {
            this.owner = owner;
            this.entry = entry;
            this.allocation = allocation;
            this.baselineLease = baselineLease;
        }

        BgfxTexture getTexture() {
            if (released) {
                throw new IllegalStateException("texture lease is released");
            }
            return activeEntry().texture;
        }

        int getSamplerFlags() {
            return activeEntry().samplerFlags;
        }

        boolean hasAlpha() {
            return activeEntry().hasAlpha;
        }

        long getEstimatedBytes() {
            if (allocation == null) {
                return activeEntry().texture.getEstimatedBytes();
            }
            return Math.max(
                    1L, activeEntry().texture.getEstimatedBytes() * allocation.layers / allocation.page.capacity);
        }

        int getBaseLayer() {
            if (released) {
                throw new IllegalStateException("texture lease is released");
            }
            return allocation == null ? 0 : allocation.baseLayer;
        }

        boolean isSameGpuResource(TextureRef other) {
            return other != null
                    && !released
                    && !other.released
                    && activeEntry().texture.getHandle()
                            == other.activeEntry().texture.getHandle()
                    && activeEntry().samplerFlags == other.activeEntry().samplerFlags;
        }

        int getGpuResourceHash() {
            if (released) {
                throw new IllegalStateException("texture lease is released");
            }
            var active = activeEntry();
            return 31 * Short.toUnsignedInt(active.texture.getHandle()) + active.samplerFlags;
        }

        boolean isSameGpuPagePair(TextureRef other) {
            if (other == null || released || other.released) {
                return false;
            }
            var left = gpuPairPrimaryEntry();
            var right = other.gpuPairPrimaryEntry();
            return left.texture.getHandle() == right.texture.getHandle() && left.samplerFlags == right.samplerFlags;
        }

        int getGpuPagePairHash() {
            if (released) {
                throw new IllegalStateException("texture lease is released");
            }
            var primary = gpuPairPrimaryEntry();
            return 31 * Short.toUnsignedInt(primary.texture.getHandle()) + primary.samplerFlags;
        }

        boolean isGpuPagePairSecondary() {
            return allocation != null && allocation.page.gpuPairPrimary != allocation.page;
        }

        BgfxTexture getGpuPagePairPrimaryTexture() {
            return gpuPairPrimaryEntry().texture;
        }

        BgfxTexture getGpuPagePairSecondaryTexture() {
            if (allocation == null) {
                return activeEntry().texture;
            }
            var primary = allocation.page.gpuPairPrimary;
            return (primary.gpuPairSecondary == null ? primary : primary.gpuPairSecondary).entry.texture;
        }

        int getGpuPagePairSamplerFlags() {
            return gpuPairPrimaryEntry().samplerFlags;
        }

        boolean isSameGpuPageQuad(TextureRef other) {
            if (other == null || released || other.released) {
                return false;
            }
            var left = gpuQuadPrimaryEntry();
            var right = other.gpuQuadPrimaryEntry();
            return left.texture.getHandle() == right.texture.getHandle() && left.samplerFlags == right.samplerFlags;
        }

        int getGpuPageQuadHash() {
            if (released) {
                throw new IllegalStateException("texture lease is released");
            }
            var primary = gpuQuadPrimaryEntry();
            return 31 * Short.toUnsignedInt(primary.texture.getHandle()) + primary.samplerFlags;
        }

        int getGpuPageGroupOrdinal(int groupSize) {
            if (allocation == null || groupSize <= 1) {
                return 0;
            }
            return groupSize >= 4
                    ? allocation.page.gpuQuadOrdinal
                    : (allocation.page.gpuPairPrimary == allocation.page ? 0 : 1);
        }

        BgfxTexture getGpuPageGroupTexture(int groupSize, int slot) {
            if (allocation == null || groupSize <= 1) {
                return activeEntry().texture;
            }
            if (groupSize < 4) {
                return slot == 1 ? getGpuPagePairSecondaryTexture() : getGpuPagePairPrimaryTexture();
            }
            var primary = allocation.page.gpuQuadPrimary;
            var member = slot >= 0 && slot < primary.gpuQuadPages.length ? primary.gpuQuadPages[slot] : null;
            return (member == null ? primary : member).entry.texture;
        }

        int getGpuPageGroupSamplerFlags(int groupSize) {
            return groupSize >= 4 ? gpuQuadPrimaryEntry().samplerFlags : getGpuPagePairSamplerFlags();
        }

        boolean isBaseline() {
            return baselineLease || entry.baseline;
        }

        TextureRef retain() {
            if (released) {
                throw new IllegalStateException("cannot retain a released texture lease");
            }
            return allocation == null ? owner.lease(entry) : owner.lease(allocation);
        }

        @Override
        public void close() {
            owner.release(this);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TextureRef ref)) {
                return false;
            }
            return allocation == null ? ref.allocation == null && entry == ref.entry : allocation == ref.allocation;
        }

        @Override
        public int hashCode() {
            return allocation == null ? System.identityHashCode(entry) : System.identityHashCode(allocation);
        }

        private TextureEntry activeEntry() {
            return allocation == null ? entry : allocation.page.entry;
        }

        private TextureEntry gpuPairPrimaryEntry() {
            return allocation == null ? entry : allocation.page.gpuPairPrimary.entry;
        }

        private TextureEntry gpuQuadPrimaryEntry() {
            return allocation == null ? entry : allocation.page.gpuQuadPrimary.entry;
        }
    }

    private static byte[] downsample(byte[] source, int sourceWidth, int sourceHeight, int width, int height) {
        var target = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            var y0 = Math.min(y * 2, sourceHeight - 1);
            var y1 = Math.min(y * 2 + 1, sourceHeight - 1);
            for (var x = 0; x < width; x++) {
                var x0 = Math.min(x * 2, sourceWidth - 1);
                var x1 = Math.min(x * 2 + 1, sourceWidth - 1);
                var offset00 = (y0 * sourceWidth + x0) * 4;
                var offset01 = (y0 * sourceWidth + x1) * 4;
                var offset10 = (y1 * sourceWidth + x0) * 4;
                var offset11 = (y1 * sourceWidth + x1) * 4;
                var destination = (y * width + x) * 4;
                for (var channel = 0; channel < 4; channel++) {
                    var sum = (source[offset00 + channel] & 0xff)
                            + (source[offset01 + channel] & 0xff)
                            + (source[offset10 + channel] & 0xff)
                            + (source[offset11 + channel] & 0xff);
                    target[destination + channel] = (byte) ((sum + 2) >> 2);
                }
            }
        }
        return target;
    }

    private static byte[] checkedRgba(byte[] source, int width, int height) {
        var expected = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (source.length < expected) {
            throw new IllegalArgumentException("short RGBA texture: " + source.length + " < " + expected);
        }
        return source.length == expected ? source.clone() : Arrays.copyOf(source, expected);
    }

    private static ByteBuffer concatenate(List<byte[]> levels) {
        var bytes = 0;
        for (var level : levels) {
            bytes = Math.addExact(bytes, level.length);
        }
        var result = ByteBuffer.allocateDirect(bytes);
        for (var level : levels) {
            result.put(level);
        }
        return result.flip();
    }

    private static long byteCount(List<byte[]> levels) {
        var result = 0L;
        for (var level : levels) {
            result = Math.addExact(result, level.length);
        }
        return result;
    }

    private static ByteBuffer toDirectBuffer(byte[] bytes) {
        var result = ByteBuffer.allocateDirect(bytes.length);
        return result.put(bytes).flip();
    }

    private static int mipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }
}
