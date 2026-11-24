package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_BGRA4;
import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.AnimatedTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorEntityMaterial;
import com.rost.cruma.core.world.Entity.ColorModifierEntityMaterial;
import com.rost.cruma.core.world.Entity.CombinerEntityMaterial;
import com.rost.cruma.core.world.Entity.ConstantOpacityEntityMaterial;
import com.rost.cruma.core.world.Entity.Cubemap;
import com.rost.cruma.core.world.Entity.EntityMaterial;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.EnvMapEntityMaterial;
import com.rost.cruma.core.world.Entity.Mesh;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.ReflectiveEntityMaterial;
import com.rost.cruma.core.world.Entity.SpecularEntityMaterial;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TextureEntityMaterialWithOpacity;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.UMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class BgfxPreparedTexturePayloads {
    private static final int TERRAIN_ALPHA_ATLAS_MAX_EXTENT = 1_024;

    private final BgfxCapabilities capabilities;
    private final BgfxPayloadAllocator memory;
    private final Set<BgfxTextureContent> resident;
    private final IdentityHashMap<Texture, Payload> textures = new IdentityHashMap<>();
    private final IdentityHashMap<Texture, BgfxTextureContent> contents = new IdentityHashMap<>();
    private final IdentityHashMap<Texture, byte[]> decodedBases = new IdentityHashMap<>();
    private final IdentityHashMap<Cubemap, Payload> cubes = new IdentityHashMap<>();
    private final IdentityHashMap<MultiTexture, List<Payload>> terrainAlpha = new IdentityHashMap<>();
    private final List<ArrayPayload> arrays = new ArrayList<>();
    private final List<Payload> payloads = new ArrayList<>();
    private long retainedBytes;

    private BgfxPreparedTexturePayloads(
            BgfxCapabilities capabilities, BgfxPayloadAllocator memory, Set<BgfxTextureContent> resident) {
        if (capabilities == null) {
            throw new IllegalArgumentException("texture preparation requires renderer capabilities");
        }
        this.capabilities = capabilities;
        this.memory = memory;
        this.resident = resident;
    }

    static BgfxPreparedTexturePayloads prepare(UMap map, BgfxCapabilities capabilities) {
        return prepare(map, capabilities, BgfxPayloadAllocator.AUTOMATIC, Set.of());
    }

    static BgfxPreparedTexturePayloads prepare(
            UMap map, BgfxCapabilities capabilities, BgfxPayloadAllocator memory, Set<BgfxTextureContent> resident) {
        if (map == null) {
            throw new IllegalArgumentException("texture preparation requires a decoded map");
        }
        var result = new BgfxPreparedTexturePayloads(capabilities, memory, resident);
        for (var entity : map.entities()) {
            checkInterrupted();
            result.visitEntity(entity);
        }
        for (var descriptor : map.spriteEmitters()) {
            result.prepareTexture(descriptor.sprite());
        }
        for (var descriptor : map.beamEmitters()) {
            result.prepareTexture(descriptor.texture());
        }
        for (var descriptor : map.meshEmitters()) {
            result.visitMesh(descriptor.mesh());
        }
        for (var descriptor : map.projectors()) {
            result.visitMaterial(descriptor.material());
        }
        result.decodedBases.clear();
        return result;
    }

    static TerrainAlphaExtent terrainAlphaExtent(MultiTexture source, int firstLayer) {
        if (source == null || firstLayer < 0) {
            throw new IllegalArgumentException("terrain alpha extent requires a source and layer");
        }
        var width = 1;
        var height = 1;
        var lastLayer = Math.min(Math.min(source.entries().size(), BgfxTerrainMaterial.MAX_LAYERS), firstLayer + 4);
        for (var layer = firstLayer; layer < lastLayer; layer++) {
            var texture = source.entries().get(layer).alphaMap();
            if (texture == null || texture.width() < 1 || texture.height() < 1) {
                continue;
            }
            width = Math.max(width, Math.min(TERRAIN_ALPHA_ATLAS_MAX_EXTENT, texture.width()));
            height = Math.max(height, Math.min(TERRAIN_ALPHA_ATLAS_MAX_EXTENT, texture.height()));
        }
        return new TerrainAlphaExtent(width, height);
    }

    static boolean doesTextureCarryAlpha(Texture source) {
        return source != null
                && (source.alphaTexture()
                        || source.format() == TextureFormat.TEXTURE_RGBA
                        || source.format() == TextureFormat.TEXTURE_DXT3
                        || source.format() == TextureFormat.TEXTURE_DXT5);
    }

    static byte[] decodeBaseRgba(Texture source) {
        return TexturePixels.decodeBaseRgba(source);
    }

    Payload getTexture(Texture source) {
        return textures.get(source);
    }

    BgfxTextureContent getContent(Texture source) {
        return contents.get(source);
    }

    Payload getCube(Cubemap source) {
        return cubes.get(source);
    }

    Payload findArray(List<Texture> sources) {
        if (sources == null) {
            return null;
        }
        for (var candidate : arrays) {
            if (isSameIdentitySequence(candidate.sources, sources)) {
                return candidate.payload;
            }
        }
        return null;
    }

    Payload getTerrainAlpha(MultiTexture source, int group) {
        var groups = terrainAlpha.get(source);
        return groups == null || group < 0 || group >= groups.size() ? null : groups.get(group);
    }

    long getRetainedBytes() {
        return retainedBytes;
    }

    List<Payload> getPayloads() {
        return List.copyOf(payloads);
    }

    private void visitEntity(Entity entity) {
        if (entity == null || entity.getMesh() == null) {
            return;
        }
        visitMesh(entity.getMesh());
        if (entity.getCsgLightmapVariants() == null
                || entity.getCsgLightmapVariants().isEmpty()) {
            prepareArray(entity.getLightmaps());
        } else {
            prepareArray(entity.getCsgLightmapVariants());
        }
        prepareArray(entity.getLightmapVariants());
        if ((entity.getLightmapVariants() == null
                        || entity.getLightmapVariants().isEmpty())
                && entity.getLightmap() != null) {
            prepareArray(List.of(entity.getLightmap()));
        }
    }

    private void visitMesh(Mesh mesh) {
        switch (mesh) {
            case EntityMesh entity -> {
                for (var surface : entity.surfaces()) {
                    if (!(surface.material() instanceof MultiTextureEntityMaterial)) {
                        visitMaterial(surface.material());
                    }
                }
            }
            case TerrainMesh terrain -> {
                if (!(terrain.surface().material() instanceof MultiTextureEntityMaterial material)) {
                    throw new IllegalArgumentException("terrain surface requires a multi-texture material");
                }
                prepareTerrain(material.multiTexture());
            }
        }
    }

    private void visitMaterial(EntityMaterial material) {
        if (material == null) {
            return;
        }
        switch (material) {
            case ColorEntityMaterial _ -> {}
            case TextureEntityMaterial value -> prepareTexture(value.texture());
            case TextureEntityMaterialWithOpacity value -> {
                prepareTexture(value.texture());
                prepareTexture(value.opacityTexture());
            }
            case AnimatedTextureEntityMaterial value -> value.frames().forEach(this::prepareTexture);
            case MultiTextureEntityMaterial value -> prepareTerrain(value.multiTexture());
            case ReflectiveEntityMaterial value -> {
                prepareTexture(value.base());
                prepareCube(value.cubemap());
            }
            case EnvMapEntityMaterial value -> prepareTexture(value.map());
            case CombinerEntityMaterial value -> {
                prepareTexture(value.material1());
                prepareTexture(value.material2());
                prepareTexture(value.mask());
            }
            case WaterEntityMaterial value -> {
                prepareTexture(value.texture());
                prepareTexture(value.material2());
                if (value.authoredPasses() != null) {
                    var maps = value.authoredPasses().shaderMaps();
                    if (maps.normal() != null) {
                        maps.normal().frames().forEach(this::prepareTexture);
                    }
                    if (maps.specular() != null) {
                        maps.specular().frames().forEach(this::prepareTexture);
                    }
                }
            }
            case ColorModifierEntityMaterial value -> visitMaterial(value.base());
            case ConstantOpacityEntityMaterial value -> visitMaterial(value.base());
            case SpecularEntityMaterial value -> {
                visitMaterial(value.base());
                prepareTexture(value.overlay().texture());
                prepareTexture(value.overlay().mask());
                if (value.overlay().cube() != null) {
                    prepareCube(value.overlay().cube().cubemap());
                }
            }
        }
    }

    private void prepareTerrain(MultiTexture source) {
        if (source == null || source.entries().isEmpty()) {
            throw new IllegalArgumentException("terrain requires at least one material entry");
        }
        if (terrainAlpha.containsKey(source)) {
            return;
        }
        if (BgfxTerrainMaterial.isProxySource(source)) {
            terrainAlpha.put(source, List.of());
            return;
        }
        var layerCount =
                Math.min(BgfxTerrainMaterial.MAX_LAYERS, source.entries().size());
        for (var layer = 0; layer < layerCount; layer++) {
            prepareTexture(source.entries().get(layer).texture());
        }
        if (layerCount == 1) {
            terrainAlpha.put(source, List.of());
            return;
        }
        var groups = new ArrayList<Payload>((layerCount + 3) / 4);
        for (var firstLayer = 0; firstLayer < layerCount; firstLayer += 4) {
            checkInterrupted();
            var group = firstLayer / 4;
            var parserPacked = group < source.packedAlphaGroups().size()
                    ? source.packedAlphaGroups().get(group)
                    : null;
            var extent = parserPacked != null
                    ? new TerrainAlphaExtent(parserPacked.width(), parserPacked.height())
                    : terrainAlphaExtent(source, firstLayer);
            var packed = parserPacked != null
                    ? parserPacked.rgba()
                    : packTerrainAlpha(source, firstLayer, extent.width(), extent.height());
            var sixteenBits =
                    BgfxTextureCompression.canPackTerrainWeights(capabilities) && TerrainWeightBits.fits(packed);
            groups.add(addPayload(
                    extent.width(),
                    extent.height(),
                    1,
                    false,
                    false,
                    sixteenBits ? BGFX_TEXTURE_FORMAT_BGRA4 : BGFX_TEXTURE_FORMAT_RGBA8,
                    List.of(sixteenBits ? TerrainWeightBits.pack(packed) : packed),
                    true));
        }
        terrainAlpha.put(source, List.copyOf(groups));
    }

    private byte[] packTerrainAlpha(MultiTexture source, int firstLayer, int atlasWidth, int atlasHeight) {
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
            var rgba = decodedBases.computeIfAbsent(texture, BgfxPreparedTexturePayloads::decodeBaseRgba);
            for (var y = 0; y < atlasHeight; y++) {
                if ((y & 63) == 0) {
                    checkInterrupted();
                }
                var sourceY = Math.min(texture.height() - 1, y * texture.height() / atlasHeight);
                for (var x = 0; x < atlasWidth; x++) {
                    var sourceX = Math.min(texture.width() - 1, x * texture.width() / atlasWidth);
                    output[(y * atlasWidth + x) * 4 + channel] = rgba[(sourceY * texture.width() + sourceX) * 4];
                }
            }
        }
        return output;
    }

    record TerrainAlphaExtent(int width, int height) {
        TerrainAlphaExtent {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("terrain alpha extent must be positive");
            }
        }

        long rgbaBytes() {
            return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        }
    }

    private void prepareTexture(Texture source) {
        if (source == null
                || source.data().isEmpty()
                || source.data().getFirst().length == 0
                || textures.containsKey(source)
                || contents.containsKey(source)) {
            return;
        }
        var original = source;
        source = source.uploadView();
        validateDimensions(source.width(), source.height());
        var content = BgfxTextureContent.of(source, BgfxTextureCache.pagedSampler(original));
        contents.put(original, content);
        if (resident.contains(content)) {
            return;
        }
        var nativeFormat = BgfxTextureCompression.nativeFormat(capabilities, source);
        var compressed = nativeFormat >= 0;
        List<byte[]> levels;
        int format;
        if (compressed) {
            levels = BgfxTextureMipmaps.splitCompressedLevels(source);
            format = nativeFormat;
        } else {
            var base = decodedBases.computeIfAbsent(source, BgfxPreparedTexturePayloads::decodeBaseRgba);
            levels = BgfxTextureMipmaps.decodeRgbaLevels(source, base);
            format = BGFX_TEXTURE_FORMAT_RGBA8;
        }
        textures.put(
                original,
                addPayload(
                        source.width(),
                        source.height(),
                        1,
                        levels.size(),
                        false,
                        format,
                        levels,
                        doesTextureCarryAlpha(source)));
    }

    private void prepareCube(Cubemap source) {
        if (source == null || cubes.containsKey(source)) {
            return;
        }
        if (source.faces().size() != 6) {
            return;
        }
        var first = source.faces().getFirst();
        if (first == null || first.width() != first.height()) {
            return;
        }
        validateDimensions(first.width(), first.height());
        var firstUpload = first.uploadView();
        var levels = new ArrayList<byte[]>();
        for (var face : source.faces()) {
            if (face == null
                    || face.width() != first.width()
                    || face.height() != first.height()
                    || face.data().size() != first.data().size()) {
                throw new IllegalArgumentException("cubemap faces must have one square extent and mip count");
            }
            var upload = face.uploadView(first.firstMip());
            var base = decodedBases.computeIfAbsent(upload, BgfxPreparedTexturePayloads::decodeBaseRgba);
            levels.addAll(BgfxTextureMipmaps.decodeRgbaLevels(upload, base));
        }
        cubes.put(
                source,
                addPayload(
                        firstUpload.width(),
                        firstUpload.height(),
                        1,
                        firstUpload.data().size(),
                        true,
                        BGFX_TEXTURE_FORMAT_RGBA8,
                        levels,
                        true));
    }

    private void prepareArray(List<Texture> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        if (findArray(sources) != null) {
            return;
        }
        if (sources.size() > 0xffff || sources.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("texture array requires 1..65535 non-null layers");
        }
        var first = sources.getFirst();
        validateArraySource(first, first.width(), first.height());
        var nativeFormat = BgfxTextureCompression.nativeFormat(capabilities, first.format());
        var compressed = nativeFormat >= 0;
        for (var source : sources) {
            validateArraySource(source, first.width(), first.height());
            compressed &= source.format() == first.format();
        }
        var levels = new ArrayList<byte[]>();
        for (var source : sources) {
            if (compressed) {
                levels.addAll(completeCompressedChain(source));
            } else {
                var base = decodedBases.computeIfAbsent(source, BgfxPreparedTexturePayloads::decodeBaseRgba);
                levels.addAll(BgfxTextureCache.rgbaMipChain(base, source.width(), source.height()));
            }
        }
        var prepared = addPayload(
                first.width(),
                first.height(),
                sources.size(),
                true,
                false,
                compressed ? nativeFormat : BGFX_TEXTURE_FORMAT_RGBA8,
                levels,
                false);
        arrays.add(new ArrayPayload(List.copyOf(sources), prepared));
    }

    private Payload addPayload(
            int width,
            int height,
            int layers,
            boolean mipmapped,
            boolean cube,
            int format,
            List<byte[]> levels,
            boolean hasAlpha) {
        return addPayload(
                width, height, layers, mipmapped ? mipCount(width, height) : 1, cube, format, levels, hasAlpha);
    }

    private Payload addPayload(
            int width,
            int height,
            int layers,
            int mipLevels,
            boolean cube,
            int format,
            List<byte[]> levels,
            boolean hasAlpha) {
        var data = concatenate(levels);
        var payload = new Payload(width, height, layers, mipLevels, cube, format, data, hasAlpha, levels);
        retainedBytes = Math.addExact(retainedBytes, payload.getRetainedBytes());
        payloads.add(payload);
        return payload;
    }

    private void validateArraySource(Texture source, int width, int height) {
        if (source.width() != width
                || source.height() != height
                || source.data().isEmpty()
                || source.data().getFirst().length == 0) {
            throw new IllegalArgumentException(
                    "texture-array layers must have one non-empty common extent " + width + 'x' + height);
        }
        validateDimensions(width, height);
    }

    private void validateDimensions(int width, int height) {
        if (width < 1
                || height < 1
                || width > capabilities.maxTextureSize()
                || height > capabilities.maxTextureSize()) {
            throw new IllegalArgumentException("scene texture extent is unsupported: " + width + "x" + height);
        }
    }

    private ByteBuffer concatenate(List<byte[]> levels) {
        var bytes = 0;
        for (var level : levels) {
            bytes = Math.addExact(bytes, level.length);
        }
        var result = memory.allocate(bytes);
        for (var level : levels) {
            checkInterrupted();
            result.put(level);
        }
        return result.flip().asReadOnlyBuffer();
    }

    private static List<byte[]> completeCompressedChain(Texture source) {
        return DxtMipCodec.completeAuthoredChain(source.format(), source.width(), source.height(), source.data())
                .levels();
    }

    private static boolean isSameIdentitySequence(List<Texture> left, List<Texture> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (var index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) {
                return false;
            }
        }
        return true;
    }

    private static int mipCount(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new PreparationCancelledException();
        }
    }

    static final class Payload {
        private final int width;
        private final int height;
        private final int layers;
        private final boolean mipmapped;
        private final boolean cube;
        private final int format;
        private final ByteBuffer data;
        private final boolean hasAlpha;
        private final int mipLevels;
        private final int[] offsets;
        private final int[] sizes;

        private Payload(
                int width,
                int height,
                int layers,
                int mipLevels,
                boolean cube,
                int format,
                ByteBuffer data,
                boolean hasAlpha,
                List<byte[]> subresources) {
            if (width < 1
                    || height < 1
                    || layers < 1
                    || data == null
                    || !data.hasRemaining()
                    || subresources == null
                    || subresources.isEmpty()) {
                throw new IllegalArgumentException("invalid prepared texture payload");
            }
            this.width = width;
            this.height = height;
            this.layers = layers;
            this.mipmapped = mipLevels > 1;
            this.cube = cube;
            this.format = format;
            this.data = data.asReadOnlyBuffer();
            this.data.position(0);
            this.hasAlpha = hasAlpha;
            this.mipLevels = mipLevels;
            if (mipLevels < 1 || mipLevels > mipCount(width, height)) {
                throw new IllegalArgumentException("invalid prepared mip count");
            }
            var surfaces = Math.multiplyExact(layers, cube ? 6 : 1);
            if (subresources.size() != Math.multiplyExact(surfaces, mipLevels)) {
                throw new IllegalArgumentException(
                        "prepared texture subresource count does not match layers/faces/mips");
            }
            offsets = new int[subresources.size()];
            sizes = new int[subresources.size()];
            var offset = 0;
            for (var index = 0; index < subresources.size(); index++) {
                var bytes = subresources.get(index);
                if (bytes == null || bytes.length < 1) {
                    throw new IllegalArgumentException("prepared texture has an empty subresource");
                }
                offsets[index] = offset;
                sizes[index] = bytes.length;
                offset = Math.addExact(offset, bytes.length);
            }
            if (offset != this.data.remaining()) {
                throw new IllegalArgumentException("prepared texture subresource byte layout is corrupt");
            }
        }

        int getWidth() {
            return width;
        }

        int getHeight() {
            return height;
        }

        int getLayers() {
            return layers;
        }

        boolean isMipmapped() {
            return mipmapped;
        }

        boolean isCube() {
            return cube;
        }

        int getFormat() {
            return format;
        }

        boolean hasAlpha() {
            return hasAlpha;
        }

        int getMipLevels() {
            return mipLevels;
        }

        ByteBuffer getBytes() {
            var result = data.duplicate();
            result.position(0);
            return result;
        }

        ByteBuffer getSubresource(int layer, int mip) {
            if (cube || layer < 0 || layer >= layers || mip < 0 || mip >= mipLevels) {
                throw new IllegalArgumentException("prepared 2D-array subresource is out of range");
            }
            var index = layer * mipLevels + mip;
            var result = data.duplicate();
            result.position(offsets[index]);
            result.limit(offsets[index] + sizes[index]);
            return result.slice().asReadOnlyBuffer();
        }

        long getRetainedBytes() {
            return data.remaining();
        }
    }

    private record ArrayPayload(List<Texture> sources, Payload payload) {}

    static final class PreparationCancelledException extends RuntimeException {
        PreparationCancelledException() {
            super("texture payload preparation was cancelled");
        }
    }
}
