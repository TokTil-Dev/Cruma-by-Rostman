package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainHeightField.SectorCoordinate;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.UTerrain.TerrainCoords;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.TerrainAlphaAtlas;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainMeshVertex;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TerrainEditorGeometry {
    private static final int SEAM_STRIDE = 512;
    private static final String SECTOR_MARK = "$terrain$";

    private final String owner;
    private final String asset;
    private final TerrainMesh original;
    private final Info info;
    private final BitSet edgeTurns;
    private final boolean inverted;
    private final float[] textureProjectors;
    private final List<LayerSource> layers;
    private final Map<SectorCoordinate, Sector> sectors;
    private final @Nullable TerrainCoords toWorld;
    private final @Nullable TerrainCoords toHeightMap;
    private final int @Nullable [] steadyLight;
    private final int[] seamSamples;
    private final Map<SectorCoordinate, int[]> seamTriangles;

    public TerrainEditorGeometry(
            Entity entity, Info info, BitSet edgeTurns, boolean inverted, float[] textureProjectors) {
        this(entity, info, edgeTurns, inverted, textureProjectors, List.of());
    }

    public TerrainEditorGeometry(
            Entity entity,
            Info info,
            BitSet edgeTurns,
            boolean inverted,
            float[] textureProjectors,
            List<LayerSource> layers) {
        this(entity, info, edgeTurns, inverted, textureProjectors, layers, null, null);
    }

    public TerrainEditorGeometry(
            Entity entity,
            Info info,
            BitSet edgeTurns,
            boolean inverted,
            float[] textureProjectors,
            List<LayerSource> layers,
            @Nullable TerrainCoords toWorld,
            @Nullable TerrainCoords toHeightMap) {
        this.toWorld = toWorld;
        this.toHeightMap = toHeightMap;
        steadyLight = null;
        if (!(entity.getMesh() instanceof TerrainMesh terrain)
                || info.width() != 256
                || info.height() != 256
                || terrain.vertices().size() < 65536
                || textureProjectors.length % 8 != 0
                || textureProjectors.length / 4 != terrain.vertexBuffer().getTextureCoordinateCount()) {
            throw new IllegalArgumentException(
                    "Terrain template requires the native256grid and exact layer projection channels");
        }
        owner = Objects.requireNonNull(entity.getSourceActor());
        asset = entity.getSourceAsset();
        original = terrain;
        this.info = info;
        this.edgeTurns = (BitSet) edgeTurns.clone();
        this.inverted = inverted;
        this.textureProjectors = textureProjectors.clone();
        this.layers = List.copyOf(layers);
        var grouped = new LinkedHashMap<SectorCoordinate, List<Integer>>();
        var vertices = terrain.vertexBuffer();
        var grid = info.width() * info.height();
        seamSamples = new int[vertices.size() - grid];
        for (var v = grid; v < vertices.size(); v++) {
            var sample = info.toHeightmap()
                    .transform(vertices.getPositionX(v), vertices.getPositionY(v), vertices.getPositionZ(v));
            var x = Math.round(sample.x());
            var y = Math.round(sample.y());
            var onSeam = x == info.width() && y >= 0 && y <= info.height()
                    || y == info.height() && x >= 0 && x <= info.width();
            seamSamples[v - grid] = onSeam ? x * SEAM_STRIDE + y : -1;
        }
        var seams = new LinkedHashMap<SectorCoordinate, List<Integer>>();
        for (var i = 0; i < terrain.indices().length; i += 3) {
            int minX = 256, minY = 256;
            for (var corner = 0; corner < 3; corner++) {
                var v = terrain.indices()[i + corner];
                var sample = info.toHeightmap()
                        .transform(vertices.getPositionX(v), vertices.getPositionY(v), vertices.getPositionZ(v));
                minX = Math.min(minX, Math.round(sample.x()));
                minY = Math.min(minY, Math.round(sample.y()));
            }
            if (minX < 0 || minY < 0 || minX > 255 || minY > 255) {
                throw new IllegalArgumentException("Terrain triangle is outside its native grid");
            }
            var coordinate = new SectorCoordinate(minX / 16, minY / 16);
            var target = grouped.computeIfAbsent(coordinate, ignored -> new ArrayList<>());
            var a = terrain.indices()[i];
            var b = terrain.indices()[i + 1];
            var c = terrain.indices()[i + 2];
            target.add(a);
            target.add(b);
            target.add(c);
            if (a >= grid || b >= grid || c >= grid) {
                var rule = seamRule(new int[] {a, b, c});
                seams.computeIfAbsent(coordinate, ignored -> new ArrayList<>())
                        .addAll(List.of(a, b, c, rule[0], rule[1]));
            }
        }
        var loadedSeams = new LinkedHashMap<SectorCoordinate, int[]>();
        for (var entry : seams.entrySet()) {
            loadedSeams.put(
                    entry.getKey(),
                    entry.getValue().stream().mapToInt(Integer::intValue).toArray());
        }
        seamTriangles = Map.copyOf(loadedSeams);
        var parts = new LinkedHashMap<SectorCoordinate, Sector>();
        for (var entry : grouped.entrySet()) {
            var remap = new LinkedHashMap<Integer, Integer>();
            var indices = new int[entry.getValue().size()];
            for (var i = 0; i < indices.length; i++) {
                indices[i] = remap.computeIfAbsent(entry.getValue().get(i), ignored -> remap.size());
            }
            var originals = remap.keySet().stream().mapToInt(Integer::intValue).toArray();
            parts.put(entry.getKey(), new Sector(originals, indices));
        }
        sectors = Map.copyOf(parts);
    }

    private TerrainEditorGeometry(
            TerrainEditorGeometry base,
            float[] textureProjectors,
            BitSet edgeTurns,
            Map<SectorCoordinate, Sector> sectors) {
        this(base, textureProjectors, edgeTurns, sectors, base.steadyLight);
    }

    private TerrainEditorGeometry(
            TerrainEditorGeometry base,
            float[] textureProjectors,
            BitSet edgeTurns,
            Map<SectorCoordinate, Sector> sectors,
            int @Nullable [] steadyLight) {
        this.steadyLight = steadyLight;
        seamSamples = base.seamSamples;
        seamTriangles = base.seamTriangles;
        owner = base.owner;
        asset = base.asset;
        original = base.original;
        info = base.info;
        this.edgeTurns = edgeTurns;
        inverted = base.inverted;
        this.textureProjectors = textureProjectors;
        layers = base.layers;
        this.sectors = sectors;
        toWorld = base.toWorld;
        toHeightMap = base.toHeightMap;
    }

    public static String sectorActor(String owner, int x, int y) {
        return owner + SECTOR_MARK + x + '_' + y;
    }

    public static @Nullable String sectorOwner(@Nullable String actor) {
        var mark = actor == null ? -1 : actor.indexOf(SECTOR_MARK);
        return mark < 1 ? null : actor.substring(0, mark);
    }

    @FunctionalInterface
    public interface SeamHeights {
        SeamHeights NONE = (x, y) -> Float.NaN;

        static SeamHeights firstKnown(List<SeamHeights> sources) {
            var copy = List.copyOf(sources);
            if (copy.isEmpty()) {
                return NONE;
            }
            return (x, y) -> {
                for (var source : copy) {
                    var value = source.getWorldZ(x, y);
                    if (!Float.isNaN(value)) {
                        return value;
                    }
                }
                return Float.NaN;
            };
        }

        float getWorldZ(int x, int y);
    }

    public static final class SeamSectors {
        private final List<Set<SectorCoordinate>> east;
        private final List<Set<SectorCoordinate>> south;
        private final Set<SectorCoordinate> all;

        private SeamSectors(
                List<Set<SectorCoordinate>> east, List<Set<SectorCoordinate>> south, Set<SectorCoordinate> all) {
            this.east = east;
            this.south = south;
            this.all = all;
        }

        public Set<SectorCoordinate> at(int x, int y) {
            if (x == 256 && y >= 0 && y <= 256) {
                return east.get(y);
            }
            if (y == 256 && x >= 0 && x <= 256) {
                return south.get(x);
            }
            return Set.of();
        }

        public Set<SectorCoordinate> getAll() {
            return all;
        }
    }

    public TerrainEditorGeometry withQuads(BitSet visible, BitSet turns, Set<SectorCoordinate> changed) {
        var width = info.width();
        var parts = new LinkedHashMap<>(sectors);
        for (var coordinate : changed) {
            var corners = new ArrayList<Integer>();
            var seams = seamTriangles.get(coordinate);
            if (seams != null) {
                for (var i = 0; i < seams.length; i += 5) {
                    var quad = seams[i + 3];
                    var bit = seams[i + 4];
                    if (quad < 0 || visible.get(quad) && visible.get(bit)) {
                        corners.add(seams[i]);
                        corners.add(seams[i + 1]);
                        corners.add(seams[i + 2]);
                    }
                }
            }
            for (var y = coordinate.y() * 16; y < Math.min(coordinate.y() * 16 + 16, info.height() - 1); y++) {
                for (var x = coordinate.x() * 16; x < Math.min(coordinate.x() * 16 + 16, width - 1); x++) {
                    var quad = y * width + x;
                    if (!visible.get(quad)) {
                        continue;
                    }
                    var a = quad;
                    var b = quad + 1;
                    var c = quad + width + 1;
                    var d = quad + width;
                    var triangles = turns.get(quad) ? new int[] {d, a, b, d, b, c} : new int[] {a, b, c, a, c, d};
                    for (var corner : triangles) {
                        corners.add(corner);
                    }
                }
            }
            if (corners.isEmpty()) {
                parts.remove(coordinate);
                continue;
            }
            var remap = new LinkedHashMap<Integer, Integer>();
            var indices = new int[corners.size()];
            for (var i = 0; i < indices.length; i++) {
                indices[i] = remap.computeIfAbsent(corners.get(i), ignored -> remap.size());
            }
            parts.put(
                    coordinate,
                    new Sector(
                            remap.keySet().stream().mapToInt(Integer::intValue).toArray(), indices));
        }
        return new TerrainEditorGeometry(this, textureProjectors, (BitSet) turns.clone(), Map.copyOf(parts));
    }

    public TerrainEditorGeometry withSteadyLight(int @Nullable [] rgb) {
        var samples = info.width() * info.height();
        if (rgb != null && rgb.length != samples) {
            throw new IllegalArgumentException("Steady light needs one colour per height sample");
        }
        return new TerrainEditorGeometry(this, textureProjectors, edgeTurns, sectors, rgb == null ? null : rgb.clone());
    }

    public Set<SectorCoordinate> getSteadyLightSectors(TerrainEditorGeometry other) {
        var width = info.width();
        var height = info.height();
        var result = new LinkedHashSet<SectorCoordinate>();
        for (var index = 0; index < width * height; index++) {
            if (steadyLightAt(index) == other.steadyLightAt(index)) {
                continue;
            }
            var x = index % width;
            var y = index / width;
            for (var sx : new int[] {x / 16, Math.max(0, x - 1) / 16}) {
                for (var sy : new int[] {y / 16, Math.max(0, y - 1) / 16}) {
                    var coordinate = new SectorCoordinate(Math.min(sx, width / 16 - 1), Math.min(sy, height / 16 - 1));
                    if (sectors.containsKey(coordinate)) {
                        result.add(coordinate);
                    }
                }
            }
        }
        return result;
    }

    public Set<SectorCoordinate> getAllSectorCoordinates() {
        var result = new LinkedHashSet<SectorCoordinate>();
        for (var y = 0; y < (info.height() + 14) / 16; y++) {
            for (var x = 0; x < (info.width() + 14) / 16; x++) {
                result.add(new SectorCoordinate(x, y));
            }
        }
        return result;
    }

    private record Sector(int[] originalVertices, int[] indices) {}

    public TerrainEditorGeometry withLayerMapping(int layer, TerrainLayerMapping mapping) {
        if (toWorld == null || toHeightMap == null) {
            throw new IllegalStateException("This Terrain template has no native transforms for a new layer mapping");
        }
        if (layer < 0 || layer * 8 >= textureProjectors.length) {
            throw new IllegalArgumentException("Terrain layer " + layer + " has no texture coordinates");
        }
        var projectors = textureProjectors.clone();
        System.arraycopy(mapping.projector(toWorld, toHeightMap), 0, projectors, layer * 8, 8);
        return new TerrainEditorGeometry(this, projectors, edgeTurns, sectors);
    }

    public record LayerSource(int index, String texture, String weightTexture, boolean drawn) {}

    public List<LayerSource> getLayers() {
        return layers;
    }

    public List<TerrainAlphaAtlas> getAlphaAtlases() {
        if (original.surface().material() instanceof MultiTextureEntityMaterial material) {
            return material.multiTexture().packedAlphaGroups();
        }
        return List.of();
    }

    public String getOwner() {
        return owner;
    }

    public Set<SectorCoordinate> getSectorCoordinates() {
        return sectors.keySet();
    }

    public SeamSectors getSeamSectorsBySample() {
        var grid = info.width() * info.height();
        var east = new ArrayList<Set<SectorCoordinate>>();
        var south = new ArrayList<Set<SectorCoordinate>>();
        for (var i = 0; i <= 256; i++) {
            east.add(new LinkedHashSet<>());
            south.add(new LinkedHashSet<>());
        }
        var all = new LinkedHashSet<SectorCoordinate>();
        for (var entry : seamTriangles.entrySet()) {
            var triangles = entry.getValue();
            for (var i = 0; i < triangles.length; i += 5) {
                for (var corner = 0; corner < 3; corner++) {
                    var vertex = triangles[i + corner];
                    var packed = vertex < grid ? -1 : seamSamples[vertex - grid];
                    if (packed < 0) {
                        continue;
                    }
                    var x = packed / SEAM_STRIDE;
                    var y = packed % SEAM_STRIDE;
                    if (x == 256) {
                        east.get(y).add(entry.getKey());
                    } else {
                        south.get(x).add(entry.getKey());
                    }
                    all.add(entry.getKey());
                }
            }
        }
        var eastSets = new ArrayList<Set<SectorCoordinate>>();
        var southSets = new ArrayList<Set<SectorCoordinate>>();
        for (var i = 0; i <= 256; i++) {
            eastSets.add(Set.copyOf(east.get(i)));
            southSets.add(Set.copyOf(south.get(i)));
        }
        return new SeamSectors(List.copyOf(eastSets), List.copyOf(southSets), Set.copyOf(all));
    }

    public Set<SectorCoordinate> getSeamSectors(int minX, int minY, int maxX, int maxY) {
        var grid = info.width() * info.height();
        var result = new LinkedHashSet<SectorCoordinate>();
        for (var entry : sectors.entrySet()) {
            for (var index : entry.getValue().originalVertices()) {
                var packed = index < grid ? -1 : seamSamples[index - grid];
                if (packed < 0) {
                    continue;
                }
                var x = packed / SEAM_STRIDE;
                var y = packed % SEAM_STRIDE;
                if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                    result.add(entry.getKey());
                    break;
                }
            }
        }
        return result;
    }

    public Map<String, List<Entity>> build(short[] samples, Set<SectorCoordinate> requested) {
        return build(samples, requested, SeamHeights.NONE);
    }

    public Map<String, List<Entity>> build(short[] samples, Set<SectorCoordinate> requested, SeamHeights seams) {
        Objects.requireNonNull(seams);
        var field = new TerrainHeightField(256, 256, samples, info.toWorld());
        var result = new LinkedHashMap<String, List<Entity>>();
        var source = original.vertexBuffer();
        var layerRows = layerRows();
        var layerOrigin = new float[] {
            info.toHeightmap().origin().x(),
            info.toHeightmap().origin().y(),
            info.toHeightmap().origin().z()
        };
        for (var coordinate : requested) {
            var sector = sectors.get(coordinate);
            if (sector == null) {
                continue;
            }
            var vertices =
                    TerrainVertexBuffer.builder(sector.originalVertices().length, source.getTextureCoordinateCount());
            vertices.setLayerRows(layerOrigin, layerRows);
            var variants = original.lightVariants() == null
                    ? null
                    : new float[original.lightVariants().length][sector.originalVertices().length];
            var bounds = new Box();
            for (var i = 0; i < sector.originalVertices().length; i++) {
                var index = sector.originalVertices()[i];
                var originalVertex = source.get(index);
                var position = originalVertex.position();
                var normal = originalVertex.normal();
                var uv = originalVertex.terrainTextureCoords();
                if (index < 65536) {
                    var x = index % 256;
                    var y = index / 256;
                    var height = field.getHeightAt(x, y);
                    var point = field.getWorldAt(x, y);
                    var direction = field.getNormalAt(x, y, edgeTurns, inverted);
                    position = new Vec3(point.x(), point.y(), point.z());
                    normal = new Vec3(direction.x(), direction.y(), direction.z());
                    for (var channel = 0; channel < uv.length; channel++) {
                        var p = channel * 4;
                        uv[channel] = textureProjectors[p] * x
                                + textureProjectors[p + 1] * y
                                + textureProjectors[p + 2] * height
                                + textureProjectors[p + 3];
                    }
                } else if (seamSamples[index - 65536] >= 0) {
                    var packed = seamSamples[index - 65536];
                    var z = seams.getWorldZ(packed / SEAM_STRIDE, packed % SEAM_STRIDE);
                    if (Float.isFinite(z) && z != position.getZ()) {
                        var before = info.toHeightmap()
                                .transform(position.getX(), position.getY(), position.getZ())
                                .z();
                        var after = info.toHeightmap()
                                .transform(position.getX(), position.getY(), z)
                                .z();
                        for (var channel = 0; channel < uv.length; channel++) {
                            uv[channel] += textureProjectors[channel * 4 + 2] * (after - before);
                        }
                        position = new Vec3(position.getX(), position.getY(), z);
                    }
                }
                var steady = steadyLight != null && index < steadyLight.length
                        ? steadyLight[index]
                        : originalVertex.steadyLightRgb();
                vertices.add(new TerrainMeshVertex(
                        position, originalVertex.alphaMapCoord(), originalVertex.lightValue(), uv, steady, normal));
                bounds.add(position);
                if (variants != null) {
                    for (var v = 0; v < variants.length; v++) {
                        variants[v][i] = original.lightVariants()[v][index];
                    }
                }
            }
            var surface = new Surface(
                    original.surface().type(),
                    0,
                    sector.indices().length,
                    original.surface().material());
            var entity = new Entity(new TerrainMesh(vertices.build(), sector.indices(), surface, bounds, variants));
            var actor = sectorActor(owner, coordinate.x(), coordinate.y());
            entity.setSourceActor(actor);
            entity.setSourceAsset(asset);
            entity.setDebugName("terrain sector " + coordinate.x() + ',' + coordinate.y());
            result.put(actor, List.of(entity));
        }
        return Map.copyOf(result);
    }

    private float[] layerRows() {
        var map = info.toHeightmap();
        var rows = new float[textureProjectors.length];
        for (var row = 0; row < rows.length; row += 4) {
            var x = textureProjectors[row];
            var y = textureProjectors[row + 1];
            var z = textureProjectors[row + 2];
            rows[row] =
                    x * map.xAxis().x() + y * map.yAxis().x() + z * map.zAxis().x();
            rows[row + 1] =
                    x * map.xAxis().y() + y * map.yAxis().y() + z * map.zAxis().y();
            rows[row + 2] =
                    x * map.xAxis().z() + y * map.yAxis().z() + z * map.zAxis().z();
            rows[row + 3] = textureProjectors[row + 3];
        }
        for (var layer = 0; layer + 8 <= rows.length; layer += 8) {
            for (var term = layer; term < layer + 8; term++) {
                if (!Float.isFinite(rows[term])) {
                    Arrays.fill(rows, layer, layer + 8, 0f);
                    break;
                }
            }
        }
        return rows;
    }

    private int steadyLightAt(int index) {
        return steadyLight != null
                ? steadyLight[index]
                : original.vertexBuffer().get(index).steadyLightRgb();
    }

    private int[] seamRule(int[] corners) {
        var width = info.width();
        var height = info.height();
        var grid = width * height;
        boolean corner = false, south = false, east = false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (var vertex : corners) {
            int x, y;
            if (vertex < grid) {
                x = vertex % width;
                y = vertex / width;
            } else {
                var packed = seamSamples[vertex - grid];
                if (packed < 0) {
                    return new int[] {-1, -1};
                }
                x = packed / SEAM_STRIDE;
                y = packed % SEAM_STRIDE;
            }
            corner |= x == width && y == height;
            south |= y == height;
            east |= x == width;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
        }
        if (corner) {
            return new int[] {(height - 2) * width + width - 2, (height - 1) * width + width - 1};
        }
        if (south) {
            return new int[] {(height - 2) * width + minX, (height - 1) * width + minX};
        }
        if (east) {
            return new int[] {minY * width + width - 2, minY * width + width - 1};
        }
        return new int[] {-1, -1};
    }
}
