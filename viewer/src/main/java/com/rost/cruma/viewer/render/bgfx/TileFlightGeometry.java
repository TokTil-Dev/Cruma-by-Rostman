package com.rost.cruma.viewer.render.bgfx;

import static org.lwjgl.bgfx.BGFX.BGFX_TEXTURE_FORMAT_RGBA8;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.Entity.MultiTexture;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.UMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TileFlightGeometry {
    static final int STRIDE = 44;

    static final int GROUND_STRIDE = 132;

    static final int LAYERS = BgfxTerrainMaterial.PASS_LAYERS;

    static final int ALPHA_ATLASES = 3;

    public static final int BOX = 6;

    private static final int CHUNK_BYTES = 4 << 20;
    private static final int GRID = 257;
    private static final long HIDDEN = Entity.SurfaceType.BOUNDING_BOX.getId()
            | Entity.SurfaceType.IMPORTED_GEODATA.getId()
            | Entity.SurfaceType.GENERATED_GEODATA.getId()
            | Entity.SurfaceType.FAKE_BACKDROP.getId();
    private static final float FOLIAGE_CUT = .5f;
    private static final float LEFT_OUT = Float.NaN;
    private static final float WATER = -2;

    private final String tile;
    private final List<Ground> grounds;
    private final List<Chunk> chunks;
    private final List<Prepared> textures;
    private final float[] obstacles;
    private final float @Nullable [] heights;
    private final float gridLeft;
    private final float gridTop;
    private final float gridStep;
    private final long bytes;

    record Ground(
            ByteBuffer vertices,
            ByteBuffer indices,
            int layerCount,
            int[] layers,
            int[] alphas,
            int[] diffuseAlpha,
            List<Overlay> overlays) {}

    record Overlay(int layerCount, int[] layers, int[] alphas, int[] diffuseAlpha, float[] coordinates) {}

    record Chunk(ByteBuffer vertices, ByteBuffer indices, List<Draw> draws) {}

    record Draw(int texture, int firstIndex, int indexCount, float cut, boolean water, boolean bsp, int lightmap) {}

    private record GroupKey(int texture, boolean cut, boolean water, boolean bsp, int lightmap) {}

    record Prepared(int format, int width, int height, int levels, ByteBuffer data) {}

    private TileFlightGeometry(
            String tile,
            List<Ground> grounds,
            List<Chunk> chunks,
            List<Prepared> textures,
            float[] obstacles,
            float @Nullable [] heights,
            float gridLeft,
            float gridTop,
            float gridStep) {
        this.tile = tile;
        this.grounds = List.copyOf(grounds);
        this.chunks = List.copyOf(chunks);
        this.textures = List.copyOf(textures);
        this.obstacles = obstacles;
        this.heights = heights;
        this.gridLeft = gridLeft;
        this.gridTop = gridTop;
        this.gridStep = gridStep;
        long total = 0;
        for (var ground : grounds) {
            total += ground.vertices().capacity() + ground.indices().capacity();
        }
        for (var chunk : chunks) {
            total += chunk.vertices().capacity() + chunk.indices().capacity();
        }
        for (var texture : textures) {
            total += texture.data().capacity();
        }
        bytes = total;
    }

    public static TileFlightGeometry build(
            UMap map, BgfxCapabilities capabilities, int largest, float hour, BooleanSupplier cancelled) {
        var builder = new Builder(capabilities, largest, hour);
        float[] heights = null;
        float left = 0, top = 0, step = 0;
        for (var entity : map.entities()) {
            if (cancelled.getAsBoolean()) {
                return empty(map.name());
            }
            if (entity.getMesh() instanceof TerrainMesh terrain) {
                builder.addGround(terrain, hour);
                if (heights == null) {
                    var grid = heightGrid(terrain);
                    heights = grid.heights;
                    left = grid.left;
                    top = grid.top;
                    step = grid.step;
                }
            }
        }
        for (var entity : map.entities()) {
            if (cancelled.getAsBoolean()) {
                return empty(map.name());
            }
            if (entity.getMesh() instanceof EntityMesh mesh && !entity.isZoneLookCopy()) {
                builder.addMesh(entity, mesh, cancelled);
            }
        }
        if (cancelled.getAsBoolean()) {
            return empty(map.name());
        }
        return new TileFlightGeometry(
                map.name(),
                builder.grounds,
                builder.buildChunks(),
                builder.prepareTextures(cancelled),
                builder.getObstacles(),
                heights,
                left,
                top,
                step);
    }

    public static TileFlightGeometry empty(String tile) {
        return new TileFlightGeometry(tile, List.of(), List.of(), List.of(), new float[0], null, 0, 0, 0);
    }

    public String getTile() {
        return tile;
    }

    public long getBytes() {
        return bytes;
    }

    public float findGround(float x, float y) {
        if (heights == null || gridStep <= 0) {
            return Float.NaN;
        }
        var column = Math.round((x - gridLeft) / gridStep);
        var row = Math.round((y - gridTop) / gridStep);
        if (column < 0 || row < 0 || column >= GRID || row >= GRID) {
            return Float.NaN;
        }
        return heights[row * GRID + column];
    }

    public float[] getObstacles() {
        return obstacles.clone();
    }

    public boolean isEmpty() {
        return grounds.isEmpty() && chunks.isEmpty();
    }

    List<Ground> getGrounds() {
        return grounds;
    }

    List<Chunk> getChunks() {
        return chunks;
    }

    List<Prepared> getTextures() {
        return textures;
    }

    private record Grid(float[] heights, float left, float top, float step) {}

    private static Grid heightGrid(TerrainMesh terrain) {
        var vertices = terrain.vertexBuffer();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (var i = 0; i < vertices.size(); i++) {
            minX = Math.min(minX, vertices.getPositionX(i));
            maxX = Math.max(maxX, vertices.getPositionX(i));
            minY = Math.min(minY, vertices.getPositionY(i));
        }
        var step = (maxX - minX) / (GRID - 1);
        var heights = new float[GRID * GRID];
        Arrays.fill(heights, Float.NaN);
        for (var i = 0; i < vertices.size(); i++) {
            var column = Math.round((vertices.getPositionX(i) - minX) / step);
            var row = Math.round((vertices.getPositionY(i) - minY) / step);
            if (column >= 0 && row >= 0 && column < GRID && row < GRID) {
                heights[row * GRID + column] = vertices.getPositionZ(i);
            }
        }
        return new Grid(heights, minX, minY, step);
    }

    private sealed interface Source {}

    private record FromTexture(Texture texture) implements Source {}

    private record FromAtlas(int width, int height, byte[] rgba) implements Source {}

    private static final class Builder {
        private final BgfxCapabilities capabilities;
        private final int largest;
        private final IdentityHashMap<Texture, Integer> textureIndex = new IdentityHashMap<>();
        private final List<Source> sources = new ArrayList<>();
        private final List<Ground> grounds = new ArrayList<>();
        private final LinkedHashMap<GroupKey, Group> groups = new LinkedHashMap<>();
        private final int shadowVariant;
        private int[] stamp = new int[0];
        private int[] mapped = new int[0];
        private int stampValue;
        private float[] obstacles = new float[BOX * 64];
        private int obstacleCount;

        Builder(BgfxCapabilities capabilities, int largest, float hour) {
            this.capabilities = capabilities;
            this.largest = largest;
            this.shadowVariant = BgfxFrameEnvironment.shadowVariantForHour(hour);
        }

        void addMesh(Entity entity, EntityMesh mesh, BooleanSupplier cancelled) {
            if (!(mesh.vertices() instanceof MeshVertexBuffer vertices) || vertices.size() == 0) {
                return;
            }
            ensureMaps(vertices.size());
            var model = entity.getModelMatrix();
            var instances = entity.getInstanceMatrices();
            var sun = entity.getSunLightMarker() == 1f ? (byte) 255 : (byte) 0;
            var matrix = new float[16];
            var box = new float[6];
            var unused = new float[6];
            var placed = 0;
            for (var instance : instances) {
                if (++placed % 256 == 0 && cancelled.getAsBoolean()) {
                    return;
                }
                model.times(instance).to(matrix);
                Arrays.fill(box, 0, 3, Float.MAX_VALUE);
                Arrays.fill(box, 3, 6, -Float.MAX_VALUE);
                for (var surface : mesh.surfaces()) {
                    var cut = cutOf(surface);
                    if (Float.isNaN(cut)) {
                        continue;
                    }
                    var water = cut == WATER;
                    var texture = water ? null : textureOf(surface.material());
                    if (texture == null && !water) {
                        continue;
                    }
                    var indices = mesh.indices();
                    var bsp = !water && (surface.type() & Entity.SurfaceType.CSG.getId()) != 0;
                    var lightmap = bsp ? lightmapSlot(entity, vertices, indices, surface) : -1;
                    var group = group(texture, water ? -1 : cut, water, bsp, lightmap);
                    var bounds = water ? unused : box;
                    stampValue++;
                    for (var i = surface.indexOffset(); i < surface.indexOffset() + surface.indexCount(); i++) {
                        var source = indices[i];
                        if (stamp[source] != stampValue) {
                            stamp[source] = stampValue;
                            mapped[source] = group.addVertex(vertices, source, matrix, sun, bounds);
                        }
                        group.addIndex(mapped[source]);
                    }
                }
                if (box[0] <= box[3]) {
                    addObstacle(box);
                }
            }
        }

        void addGround(TerrainMesh terrain, float hour) {
            var layers = new int[LAYERS];
            var alphas = new int[ALPHA_ATLASES];
            var diffuseAlpha = new int[ALPHA_ATLASES];
            Arrays.fill(layers, -1);
            Arrays.fill(alphas, -1);
            var count = 1;
            var overlays = new ArrayList<Overlay>();
            var vertices = terrain.vertexBuffer();
            var material = terrain.surface().material();
            if (material instanceof Entity.MultiTextureEntityMaterial painted
                    && !painted.multiTexture().entries().isEmpty()) {
                var source = painted.multiTexture();
                count = Math.min(LAYERS, source.entries().size());
                for (var layer = 0; layer < count; layer++) {
                    var texture = source.entries().get(layer).texture();
                    layers[layer] = texture == null ? -1 : textureSlot(texture);
                }
                for (var atlas = 0; atlas < ALPHA_ATLASES && count > 1 && atlas * 4 < count; atlas++) {
                    diffuseAlpha[atlas] = BgfxTerrainMaterial.diffuseAlphaMask(source, atlas * 4);
                    alphas[atlas] = atlasSlot(source, atlas);
                }
                var all = Math.min(
                        BgfxTerrainMaterial.MAX_LAYERS, source.entries().size());
                if (all > LAYERS) {
                    var projection = TerrainLayerProjection.fit(vertices, LAYERS, all - LAYERS);
                    for (var first = LAYERS; first < all; first += LAYERS) {
                        overlays.add(overlay(source, projection, first, Math.min(LAYERS, all - first)));
                    }
                }
            } else if (textureOf(material) instanceof Texture only) {
                layers[0] = textureSlot(only);
            }
            var coordinates = Math.min(2 * LAYERS, vertices.getTextureCoordinateCount());
            var variants = terrain.lightVariants();
            var slot = BgfxFrameEnvironment.shadowSlotForHour(hour);
            var first = (int) Math.floor(slot) % 8;
            var next = (first + 1) % 8;
            var share = slot - (float) Math.floor(slot);
            var buffer =
                    ByteBuffer.allocateDirect(vertices.size() * GROUND_STRIDE).order(ByteOrder.nativeOrder());
            for (var i = 0; i < vertices.size(); i++) {
                buffer.putFloat(vertices.getPositionX(i))
                        .putFloat(vertices.getPositionY(i))
                        .putFloat(vertices.getPositionZ(i));
                buffer.putFloat(vertices.getNormalX(i))
                        .putFloat(vertices.getNormalY(i))
                        .putFloat(vertices.getNormalZ(i));
                var sunlight = variants.length >= 8 && variants[first].length > i && variants[next].length > i
                        ? variants[first][i] + (variants[next][i] - variants[first][i]) * share
                        : vertices.getLightValue(i);
                buffer.put((byte) vertices.getSteadyLightRed(i))
                        .put((byte) vertices.getSteadyLightGreen(i))
                        .put((byte) vertices.getSteadyLightBlue(i))
                        .put((byte) Math.round(Math.clamp(sunlight, 0f, 1f) * 255));
                buffer.putFloat(vertices.getAlphaMapU(i)).putFloat(vertices.getAlphaMapV(i));
                for (var coordinate = 0; coordinate < 2 * LAYERS; coordinate++) {
                    buffer.putFloat(coordinate < coordinates ? vertices.getTerrainTextureCoordinate(i, coordinate) : 0);
                }
            }
            var source = terrain.indices();
            var indices = ByteBuffer.allocateDirect(source.length * 4).order(ByteOrder.nativeOrder());
            for (var index : source) {
                indices.putInt(index);
            }
            grounds.add(new Ground(
                    buffer.flip(), indices.flip(), count, layers, alphas, diffuseAlpha, List.copyOf(overlays)));
        }

        float[] getObstacles() {
            return Arrays.copyOf(obstacles, obstacleCount * BOX);
        }

        List<Chunk> buildChunks() {
            var chunks = new ArrayList<Chunk>();
            var pending = new ArrayList<Group>();
            var pendingBytes = 0L;
            for (var group : groups.values()) {
                if (group.indexCount == 0) {
                    continue;
                }
                pending.add(group);
                pendingBytes += (long) group.getVertexCount() * STRIDE + group.indexCount * 4L;
                if (pendingBytes >= CHUNK_BYTES) {
                    chunks.add(chunk(pending));
                    pending.clear();
                    pendingBytes = 0;
                }
            }
            if (!pending.isEmpty()) {
                chunks.add(chunk(pending));
            }
            groups.clear();
            return chunks;
        }

        List<Prepared> prepareTextures(BooleanSupplier cancelled) {
            var prepared = new ArrayList<Prepared>(sources.size());
            for (var source : sources) {
                if (cancelled.getAsBoolean()) {
                    return List.of();
                }
                prepared.add(
                        switch (source) {
                            case FromTexture(var texture) -> prepare(texture);
                            case FromAtlas(var width, var height, var rgba) -> prepareRgba(width, height, rgba);
                        });
            }
            return prepared;
        }

        private void addObstacle(float[] box) {
            if ((obstacleCount + 1) * BOX > obstacles.length) {
                obstacles = Arrays.copyOf(obstacles, obstacles.length * 2);
            }
            var at = obstacleCount * BOX;
            obstacles[at] = box[0];
            obstacles[at + 1] = box[1];
            obstacles[at + 2] = box[3];
            obstacles[at + 3] = box[4];
            obstacles[at + 4] = box[2];
            obstacles[at + 5] = box[5];
            obstacleCount++;
        }

        private int textureSlot(Texture texture) {
            return textureIndex.computeIfAbsent(texture, key -> {
                sources.add(new FromTexture(key));
                return sources.size() - 1;
            });
        }

        private Overlay overlay(MultiTexture source, TerrainLayerProjection projection, int first, int count) {
            var layers = new int[LAYERS];
            var alphas = new int[ALPHA_ATLASES];
            var diffuseAlpha = new int[ALPHA_ATLASES];
            Arrays.fill(layers, -1);
            Arrays.fill(alphas, -1);
            for (var slot = 0; slot < count; slot++) {
                var texture = source.entries().get(first + slot).texture();
                layers[slot] = texture == null ? -1 : textureSlot(texture);
            }
            for (var atlas = 0; atlas < ALPHA_ATLASES && atlas * 4 < count; atlas++) {
                diffuseAlpha[atlas] = BgfxTerrainMaterial.diffuseAlphaMask(source, first + atlas * 4);
                alphas[atlas] = atlasSlot(source, first / 4 + atlas);
            }
            var coordinates = new float[BgfxTerrainMaterial.LAYER_UV_VECTORS * 4];
            projection.writeWorld(null, first - LAYERS, count, LAYERS, coordinates);
            return new Overlay(count, layers, alphas, diffuseAlpha, coordinates);
        }

        private int atlasSlot(MultiTexture source, int atlas) {
            if (atlas < source.packedAlphaGroups().size()) {
                var packed = source.packedAlphaGroups().get(atlas);
                sources.add(new FromAtlas(packed.width(), packed.height(), packed.rgba()));
                return sources.size() - 1;
            }
            var extent = BgfxPreparedTexturePayloads.terrainAlphaExtent(source, atlas * 4);
            var width = extent.width();
            var height = extent.height();
            var rgba = new byte[width * height * 4];
            for (var channel = 0; channel < 4; channel++) {
                var layer = atlas * 4 + channel;
                if (layer >= source.entries().size()) {
                    break;
                }
                var map = source.entries().get(layer).alphaMap();
                if (map == null || map.width() < 1 || map.height() < 1) {
                    continue;
                }
                byte[] pixels;
                try {
                    pixels = TexturePixels.decodeMipRgba(map, 0);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                for (var y = 0; y < height; y++) {
                    var sourceY = Math.min(map.height() - 1, y * map.height() / height);
                    for (var x = 0; x < width; x++) {
                        var sourceX = Math.min(map.width() - 1, x * map.width() / width);
                        rgba[(y * width + x) * 4 + channel] = pixels[(sourceY * map.width() + sourceX) * 4];
                    }
                }
            }
            sources.add(new FromAtlas(width, height, rgba));
            return sources.size() - 1;
        }

        private Chunk chunk(List<Group> parts) {
            var vertexCount = 0;
            var indexCount = 0;
            for (var group : parts) {
                vertexCount += group.getVertexCount();
                indexCount += group.indexCount;
            }
            var vertices = ByteBuffer.allocateDirect(vertexCount * STRIDE).order(ByteOrder.nativeOrder());
            var indices = ByteBuffer.allocateDirect(indexCount * 4).order(ByteOrder.nativeOrder());
            var draws = new ArrayList<Draw>(parts.size());
            var vertexBase = 0;
            for (var group : parts) {
                vertices.put(group.vertices, 0, group.getVertexCount() * STRIDE);
                var first = indices.position() / 4;
                for (var i = 0; i < group.indexCount; i++) {
                    indices.putInt(vertexBase + group.indices[i]);
                }
                draws.add(new Draw(
                        group.texture, first, group.indexCount, group.cut, group.water, group.bsp, group.lightmap));
                vertexBase += group.getVertexCount();
            }
            return new Chunk(vertices.flip(), indices.flip(), draws);
        }

        private Group group(@Nullable Texture texture, float cut, boolean water, boolean bsp, int lightmap) {
            var slot = texture == null ? -1 : textureSlot(texture);
            var key = new GroupKey(slot, cut >= 0, water, bsp, lightmap);
            return groups.computeIfAbsent(key, ignored -> new Group(slot, cut, water, bsp, lightmap));
        }

        private int lightmapSlot(Entity entity, MeshVertexBuffer vertices, int[] indices, Entity.Surface surface) {
            var vertex = -1;
            for (var i = surface.indexOffset(); i < surface.indexOffset() + surface.indexCount(); i++) {
                if (vertices.getLightmapUvX(indices[i]) >= 0) {
                    vertex = indices[i];
                    break;
                }
            }
            if (vertex < 0) {
                return -1;
            }
            Texture lightmap;
            var atlases = entity.getLightmaps();
            if (atlases != null && !atlases.isEmpty()) {
                var layers = entity.getLightmapLayers();
                var atlas = layers != null && vertex < layers.length ? layers[vertex] : 0;
                var pages = entity.getCsgLightmapVariants();
                var count = entity.getCsgLightmapVariantCount();
                var page = Math.clamp(shadowVariant, 0, count - 1) * atlases.size() + atlas;
                lightmap = pages != null && count > 1 && page < pages.size()
                        ? pages.get(page)
                        : atlas < atlases.size() ? atlases.get(atlas) : null;
            } else if (entity.getLightmapVariants() instanceof List<Texture> variants
                    && !variants.isEmpty()
                    && !entity.isBeastLightmap()) {
                var slots = entity.getLightmapVariantSlots();
                var count = slots.isEmpty() ? variants.size() : slots.size();
                var selected = Math.clamp(shadowVariant, 0, count - 1);
                var slot = slots.isEmpty() ? selected : slots.get(selected);
                lightmap = slot < 0 || slot >= variants.size() ? null : variants.get(slot);
            } else {
                lightmap = entity.getLightmap();
            }
            return lightmap == null ? -1 : textureSlot(lightmap);
        }

        private void ensureMaps(int size) {
            if (stamp.length < size) {
                stamp = new int[size];
                mapped = new int[size];
                stampValue = 0;
            }
        }

        private Prepared prepare(Texture texture) {
            var level = Math.max(0, texture.firstMip());
            while (level + 1 < texture.data().size()
                    && Math.max(texture.width() >> level, texture.height() >> level) > largest) {
                level++;
            }
            var width = Math.max(1, texture.width() >> level);
            var height = Math.max(1, texture.height() >> level);
            var format = BgfxTextureCompression.nativeFormat(capabilities, texture);
            if (format >= 0 && width >= 4 && height >= 4) {
                var levels = 0;
                var size = 0;
                for (var mip = level; mip < texture.data().size(); mip++) {
                    if ((texture.width() >> mip) < 4 || (texture.height() >> mip) < 4) {
                        break;
                    }
                    size += texture.data().get(mip).length;
                    levels++;
                }
                var data = ByteBuffer.allocateDirect(size);
                for (var mip = level; mip < level + levels; mip++) {
                    data.put(texture.data().get(mip));
                }
                return new Prepared(format, width, height, levels, data.flip());
            }
            byte[] rgba;
            try {
                rgba = TexturePixels.decodeMipRgba(texture, level);
            } catch (RuntimeException unreadable) {
                rgba = new byte[] {(byte) 128, (byte) 128, (byte) 128, (byte) 255};
                width = 1;
                height = 1;
            }
            return prepareRgba(width, height, rgba);
        }

        private static Prepared prepareRgba(int width, int height, byte[] rgba) {
            var base = ByteBuffer.allocateDirect(rgba.length).put(rgba).flip();
            var chain = BgfxUiMipmaps.createRgbaMipChain(base, width, height);
            var levels = 1 + (int) Math.floor(Math.log(Math.max(width, height)) / Math.log(2));
            return new Prepared(BGFX_TEXTURE_FORMAT_RGBA8, width, height, levels, chain);
        }

        private static @Nullable Texture textureOf(Entity.@Nullable EntityMaterial material) {
            if (material instanceof Entity.MultiTextureEntityMaterial layers) {
                for (var entry : layers.multiTexture().entries()) {
                    if (entry.texture() != null) {
                        return entry.texture();
                    }
                }
                return null;
            }
            return material == null ? null : material.getTexture();
        }

        private static float cutOf(Entity.Surface surface) {
            if ((surface.type() & HIDDEN) != 0) {
                return LEFT_OUT;
            }
            if (surface.water() || surface.material() instanceof Entity.WaterEntityMaterial) {
                return WATER;
            }
            var authored = surface.alphaRef() > 0 ? surface.alphaRef() / 255f : -1;
            return switch (surface.blendMode()) {
                case OPAQUE, UNLIT -> authored;
                case MASKED, MASKED_BLEND -> authored > 0 ? authored : FOLIAGE_CUT;
                default -> LEFT_OUT;
            };
        }
    }

    private static final class Group {
        final int texture;
        final float cut;
        final boolean water;
        final boolean bsp;
        final int lightmap;
        byte[] vertices = new byte[STRIDE * 256];
        int[] indices = new int[768];
        int vertexBytes;
        int indexCount;
        private final ByteBuffer view = ByteBuffer.allocate(STRIDE).order(ByteOrder.nativeOrder());

        Group(int texture, float cut, boolean water, boolean bsp, int lightmap) {
            this.texture = texture;
            this.cut = cut;
            this.water = water;
            this.bsp = bsp;
            this.lightmap = lightmap;
        }

        int getVertexCount() {
            return vertexBytes / STRIDE;
        }

        int addVertex(MeshVertexBuffer source, int index, float[] m, byte sun, float[] box) {
            float x = source.getPositionX(index), y = source.getPositionY(index), z = source.getPositionZ(index);
            float nx = source.getNormalX(index), ny = source.getNormalY(index), nz = source.getNormalZ(index);
            var wx = m[0] * x + m[4] * y + m[8] * z + m[12];
            var wy = m[1] * x + m[5] * y + m[9] * z + m[13];
            var wz = m[2] * x + m[6] * y + m[10] * z + m[14];
            box[0] = Math.min(box[0], wx);
            box[1] = Math.min(box[1], wy);
            box[2] = Math.min(box[2], wz);
            box[3] = Math.max(box[3], wx);
            box[4] = Math.max(box[4], wy);
            box[5] = Math.max(box[5], wz);
            var tx = m[0] * nx + m[4] * ny + m[8] * nz;
            var ty = m[1] * nx + m[5] * ny + m[9] * nz;
            var tz = m[2] * nx + m[6] * ny + m[10] * nz;
            var length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (length > 1e-6f) {
                tx /= length;
                ty /= length;
                tz /= length;
            }
            return put(
                    wx,
                    wy,
                    wz,
                    tx,
                    ty,
                    tz,
                    source.getUvX(index),
                    source.getUvY(index),
                    half(source.getBakedLightX(index)),
                    half(source.getBakedLightY(index)),
                    half(source.getBakedLightZ(index)),
                    sun,
                    lightmap < 0 ? -1 : source.getLightmapUvX(index),
                    lightmap < 0 ? -1 : source.getLightmapUvY(index));
        }

        void addIndex(int index) {
            if (indexCount == indices.length) {
                indices = Arrays.copyOf(indices, indices.length * 2);
            }
            indices[indexCount++] = index;
        }

        private int put(
                float x,
                float y,
                float z,
                float nx,
                float ny,
                float nz,
                float u,
                float v,
                byte red,
                byte green,
                byte blue,
                byte sun,
                float lightmapU,
                float lightmapV) {
            if (vertexBytes + STRIDE > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
            }
            view.clear();
            view.putFloat(x).putFloat(y).putFloat(z);
            view.putFloat(nx).putFloat(ny).putFloat(nz);
            view.putFloat(u).putFloat(v);
            view.put(red).put(green).put(blue).put(sun);
            view.putFloat(lightmapU).putFloat(lightmapV);
            System.arraycopy(view.array(), 0, vertices, vertexBytes, STRIDE);
            vertexBytes += STRIDE;
            return vertexBytes / STRIDE - 1;
        }

        private static byte half(float light) {
            return (byte) Math.round(Math.clamp(light * .5f, 0f, 1f) * 255);
        }
    }
}
