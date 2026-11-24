package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.DistanceFog;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.SurfaceType;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.Entity.TerrainVertexBuffer;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import com.rost.cruma.core.world.Entity.WaterSheetSource;
import com.rost.cruma.core.world.WaterPlane;
import com.rost.cruma.viewer.render.api.StableRenderId;
import com.rost.cruma.viewer.render.bgfx.BgfxGeometryPool.IndirectBinding;
import com.rost.cruma.viewer.render.bgfx.BgfxGeometryPool.Slice;
import com.rost.cruma.viewer.render.bgfx.BgfxRayOpacity.AlphaCache;
import com.rost.cruma.viewer.render.bgfx.BgfxRayOpacity.Coverage;
import com.rost.cruma.viewer.render.bgfx.BgfxTextureCache.TextureRef;
import glm_.vec2.Vec2;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class BgfxSceneGeometry implements AutoCloseable {
    private static final float GRID_TOLERANCE = 0.01f;
    private final long stableId;
    private final String label;
    private final Kind kind;
    private final Slice storage;
    private final int vertexCount;
    private final int indexCount;
    private final Bounds3f localBounds;
    private final List<Primitive> primitives;
    private final @Nullable TerrainRows terrainRows;
    private final float @Nullable [] clearanceBoxes;
    private boolean closed;

    private BgfxSceneGeometry(
            long stableId,
            String label,
            Kind kind,
            Slice storage,
            int vertexCount,
            int indexCount,
            Bounds3f localBounds,
            List<Primitive> primitives,
            @Nullable TerrainRows terrainRows,
            float @Nullable [] clearanceBoxes) {
        this.clearanceBoxes = clearanceBoxes;
        this.stableId = stableId;
        this.label = label;
        this.kind = kind;
        this.storage = storage;
        this.vertexCount = vertexCount;
        this.indexCount = indexCount;
        this.localBounds = localBounds;
        this.primitives = List.copyOf(primitives);
        this.terrainRows = terrainRows;
    }

    static BgfxSceneGeometry upload(
            BgfxGeometryPool geometryPool,
            BgfxTextureCache textures,
            Entity entity,
            String mapName,
            int entityOrdinal) {
        return install(geometryPool, textures, entity, prepare(entity, mapName, entityOrdinal));
    }

    static Prepared prepare(Entity entity, String mapName, int entityOrdinal) {
        return prepare(entity, mapName, entityOrdinal, null);
    }

    static Prepared prepare(Entity entity, String mapName, int entityOrdinal, @Nullable AlphaCache rayOpacity) {
        return prepare(entity, mapName, entityOrdinal, rayOpacity, BgfxPayloadAllocator.AUTOMATIC);
    }

    static Prepared prepare(
            Entity entity,
            String mapName,
            int entityOrdinal,
            @Nullable AlphaCache rayOpacity,
            BgfxPayloadAllocator memory) {
        if (entity == null || mapName == null || mapName.isBlank() || entityOrdinal < 0) {
            throw new IllegalArgumentException("invalid scene geometry preparation request");
        }
        var stableName = entity.getMeshName();
        var label = stableName == null || stableName.isBlank()
                ? mapName + "/entity-" + entityOrdinal
                : "shared/" + sharedCacheKey(entity);
        return prepareNamed(entity, label, rayOpacity, memory);
    }

    static Prepared prepareEditor(Entity entity, String actor, long revision, int ordinal) {
        return prepareNamed(
                entity, "editor/" + actor + '/' + revision + '/' + ordinal, null, BgfxPayloadAllocator.AUTOMATIC);
    }

    static String sharedCacheKey(Entity entity) {
        var meshName = entity.getMeshName();
        if (meshName == null || meshName.isBlank()) {
            throw new IllegalArgumentException("unnamed geometry does not have a shared cache key");
        }
        return meshName.toLowerCase(Locale.ROOT);
    }

    static BgfxSceneGeometry uploadParticle(
            BgfxGeometryPool geometryPool,
            BgfxTextureCache textures,
            EntityMesh source,
            String label,
            long discriminator) {
        return installParticle(geometryPool, textures, source, prepareParticle(source, label, discriminator));
    }

    static Prepared prepareParticle(EntityMesh source, String label, long discriminator) {
        return prepareParticle(source, label, discriminator, BgfxPayloadAllocator.AUTOMATIC);
    }

    static Prepared prepareParticle(EntityMesh source, String label, long discriminator, BgfxPayloadAllocator memory) {
        if (source == null || label == null || label.isBlank()) {
            throw new IllegalArgumentException("particle geometry requires source and label");
        }
        var stableId = StableRenderId.builder("particle-geometry")
                .put(label)
                .put(discriminator)
                .build();
        return prepareEntity(stableId, "particles/" + label, source, null, null, memory);
    }

    static BgfxSceneGeometry install(
            BgfxGeometryPool geometryPool, BgfxTextureCache textures, Entity entity, Prepared prepared) {
        if (entity == null || prepared == null || entity.getMesh() == null) {
            throw new IllegalArgumentException("geometry install requires source and prepared payload");
        }
        return switch (entity.getMesh()) {
            case EntityMesh mesh -> installEntity(geometryPool, textures, mesh, prepared);
            case TerrainMesh mesh -> installTerrain(geometryPool, textures, mesh, prepared);
        };
    }

    static BgfxSceneGeometry installParticle(
            BgfxGeometryPool geometryPool, BgfxTextureCache textures, EntityMesh source, Prepared prepared) {
        return installEntity(geometryPool, textures, source, prepared);
    }

    static BgfxVertexLayoutSpec entityLayout(Kind kind) {
        return switch (kind) {
            case ENTITY, STATIC_MESH_TANGENT -> BgfxSceneVertexLayouts.ENTITY;
            case STATIC_MESH -> BgfxSceneVertexLayouts.STATIC_MESH;
            case STATIC_MESH_COLOR -> BgfxSceneVertexLayouts.STATIC_MESH_COLOR;
            case TERRAIN -> throw new IllegalStateException("entity geometry cannot use terrain layout");
        };
    }

    static float[] buildHfWaterTangents(EntityMesh source) {
        var vertices = source.vertexBuffer();
        var tangents = new float[Math.multiplyExact(vertices.size(), 3)];
        var touched = new boolean[vertices.size()];
        var indices = source.indices();
        for (var surface : source.surfaces()) {
            if (!surface.water() && !(surface.material() instanceof WaterEntityMaterial)) {
                continue;
            }
            validateRange(surface.indexOffset(), surface.indexCount(), indices.length);
            var end = surface.indexOffset() + surface.indexCount();
            for (var offset = surface.indexOffset(); offset + 2 < end; offset += 3) {
                var i0 = indices[offset];
                var i1 = indices[offset + 1];
                var i2 = indices[offset + 2];
                if (i0 < 0
                        || i0 >= vertices.size()
                        || i1 < 0
                        || i1 >= vertices.size()
                        || i2 < 0
                        || i2 >= vertices.size()) {
                    throw new IllegalArgumentException("water triangle references a missing vertex");
                }
                if (vertices.hasBspTangent(i0) && vertices.hasBspTangent(i1) && vertices.hasBspTangent(i2)) {
                    continue;
                }

                var e1x = vertices.getPositionX(i1) - vertices.getPositionX(i0);
                var e1y = vertices.getPositionY(i1) - vertices.getPositionY(i0);
                var e1z = vertices.getPositionZ(i1) - vertices.getPositionZ(i0);
                var e2x = vertices.getPositionX(i2) - vertices.getPositionX(i0);
                var e2y = vertices.getPositionY(i2) - vertices.getPositionY(i0);
                var e2z = vertices.getPositionZ(i2) - vertices.getPositionZ(i0);
                var du1 = vertices.getUvX(i1) - vertices.getUvX(i0);
                var dv1 = vertices.getUvY(i1) - vertices.getUvY(i0);
                var du2 = vertices.getUvX(i2) - vertices.getUvX(i0);
                var dv2 = vertices.getUvY(i2) - vertices.getUvY(i0);
                var denominator = dv1 * du2 - dv2 * du1;
                if (!Float.isFinite(denominator) || Math.abs(denominator) <= 1.0e-12f) {
                    continue;
                }
                var reciprocal = 1f / denominator;
                var tx = (-dv2 * e1x + dv1 * e2x) * reciprocal;
                var ty = (-dv2 * e1y + dv1 * e2y) * reciprocal;
                var tz = (-dv2 * e1z + dv1 * e2z) * reciprocal;
                putRawTangent(tangents, touched, i0, tx, ty, tz);
                putRawTangent(tangents, touched, i1, tx, ty, tz);
                putRawTangent(tangents, touched, i2, tx, ty, tz);
            }
        }

        for (var vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            if (!touched[vertexIndex]) {
                continue;
            }
            var tangentOffset = vertexIndex * 3;
            var tx = tangents[tangentOffset];
            var ty = tangents[tangentOffset + 1];
            var tz = tangents[tangentOffset + 2];
            var nx = vertices.getNormalX(vertexIndex);
            var ny = vertices.getNormalY(vertexIndex);
            var nz = vertices.getNormalZ(vertexIndex);
            var projection = nx * tx + ny * ty + nz * tz;
            tx -= nx * projection;
            ty -= ny * projection;
            tz -= nz * projection;
            var lengthSquared = tx * tx + ty * ty + tz * tz;
            if (!Float.isFinite(lengthSquared) || lengthSquared <= 1.0e-12f) {
                tangents[tangentOffset] = 0f;
                tangents[tangentOffset + 1] = 0f;
                tangents[tangentOffset + 2] = 0f;
                continue;
            }
            var inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
            tangents[tangentOffset] = tx * inverseLength;
            tangents[tangentOffset + 1] = ty * inverseLength;
            tangents[tangentOffset + 2] = tz * inverseLength;
        }
        for (var i = 0; i < vertices.size(); i++) {
            if (vertices.hasBspTangent(i)) {
                var offset = i * 3;
                tangents[offset] = vertices.getBspTangentX(i);
                tangents[offset + 1] = vertices.getBspTangentY(i);
                tangents[offset + 2] = vertices.getBspTangentZ(i);
            }
        }
        return tangents;
    }

    static @Nullable TerrainRows terrainRows(TerrainVertexBuffer vertices, int[] indices) {
        var size = vertices.size();
        if (size < 4 || indices.length < 3) {
            return null;
        }
        var firstY = vertices.getPositionY(0);
        var width = 1;
        while (width < size && Math.abs(vertices.getPositionY(width) - firstY) <= GRID_TOLERANCE) {
            width++;
        }
        if (width < 2 || width > 4_096) {
            return null;
        }
        var rows = size / width;
        if (rows < 2) {
            return null;
        }
        var rowFirstIndex = new int[rows + 1];
        var previousRow = -1;
        for (var index = 0; index + 2 < indices.length; index += 3) {
            var lowest = Math.min(indices[index], Math.min(indices[index + 1], indices[index + 2]));
            if (lowest < 0 || lowest >= size) {
                return null;
            }
            var row = Math.min(rows - 1, lowest / width);
            if (row < previousRow) {
                return null;
            }
            for (var next = previousRow + 1; next <= row; next++) {
                rowFirstIndex[next] = index;
            }
            previousRow = row;
        }
        for (var next = previousRow + 1; next <= rows; next++) {
            rowFirstIndex[next] = indices.length;
        }
        var rowMinY = new float[rows];
        var rowMaxY = new float[rows];
        for (var row = 0; row < rows; row++) {
            var y0 = vertices.getPositionY(row * width);
            var y1 = vertices.getPositionY(Math.min(size - 1, (row + 1) * width));
            rowMinY[row] = Math.min(y0, y1) - GRID_TOLERANCE;
            rowMaxY[row] = Math.max(y0, y1) + GRID_TOLERANCE;
        }
        for (var row = 1; row < rows; row++) {
            if (rowMinY[row] < rowMinY[row - 1]) {
                return null;
            }
        }
        return new TerrainRows(rows, rowMinY, rowMaxY, rowFirstIndex);
    }

    record Prepared(
            long stableId,
            String label,
            Kind kind,
            ByteBuffer vertexData,
            ByteBuffer indexData,
            int vertexCount,
            int indexCount,
            Bounds3f localBounds,
            List<PreparedPrimitive> primitives,
            @Nullable TerrainRows terrainRows,
            float @Nullable [] clearanceBoxes) {
        Prepared {
            if (stableId == 0L
                    || label == null
                    || label.isBlank()
                    || kind == null
                    || vertexCount < 1
                    || indexCount < 3
                    || localBounds == null
                    || primitives == null
                    || primitives.isEmpty()) {
                throw new IllegalArgumentException("invalid prepared geometry payload");
            }
            vertexData = readOnly(vertexData);
            indexData = readOnly(indexData);
            primitives = List.copyOf(primitives);
        }

        long retainedBytes() {
            var bytes = Math.addExact((long) vertexData.remaining(), indexData.remaining());
            for (var primitive : primitives) {
                bytes = Math.addExact(bytes, primitive.rayCoverage().retainedBytes());
            }
            if (clearanceBoxes != null) {
                bytes = Math.addExact(bytes, (long) clearanceBoxes.length * Float.BYTES);
            }
            return bytes;
        }

        private void requireKind(Kind expected) {
            if (kind != expected) {
                throw new IllegalArgumentException(
                        "prepared geometry kind mismatch: expected " + expected + ", got " + kind);
            }
        }
    }

    record PreparedPrimitive(
            long stableId,
            long materialId,
            int sourceSurfaceOrdinal,
            int indexOffset,
            int indexCount,
            Bounds3f localBounds,
            Coverage rayCoverage) {
        PreparedPrimitive {
            if (stableId == 0L
                    || materialId == 0L
                    || sourceSurfaceOrdinal < 0
                    || indexOffset < 0
                    || indexCount < 1
                    || localBounds == null
                    || rayCoverage == null) {
                throw new IllegalArgumentException("invalid prepared primitive payload");
            }
        }
    }

    record Primitive(
            long stableId,
            int sourceSurfaceOrdinal,
            int indexOffset,
            int indexCount,
            Bounds3f localBounds,
            BgfxSceneMaterial entityMaterial,
            BgfxTerrainMaterial terrainMaterial,
            WaterPlane reflectionPlane,
            DistanceFog distanceFog,
            WaterSheetSource waterSheetSource,
            long zones) {
        Primitive {
            if (stableId == 0L
                    || sourceSurfaceOrdinal < 0
                    || indexOffset < 0
                    || indexCount < 1
                    || localBounds == null
                    || (entityMaterial == null) == (terrainMaterial == null)) {
                throw new IllegalArgumentException("invalid scene primitive descriptor");
            }
        }
    }

    @Nullable
    TerrainRows getTerrainRows() {
        return terrainRows;
    }

    boolean updateEntityVertices(EntityMesh source, int @Nullable [] lightmapLayers, int[] ranges, int[] collapsed) {
        if (closed) {
            throw new IllegalStateException("geometry is closed: " + label);
        }
        if (kind != Kind.ENTITY
                || isStaticMeshOnly(source)
                || source.vertexBuffer().size() != vertexCount) {
            return false;
        }
        var stride = entityLayout(kind).getStride();
        var packed = packEntityVertices(
                kind, source, lightmapLayers, false, buildHfWaterTangents(source), BgfxPayloadAllocator.AUTOMATIC);
        for (var range = 0; range + 1 < collapsed.length; range += 2) {
            var first = collapsed[range];
            var count = collapsed[range + 1];
            if (first < 0 || count < 1 || first + count > vertexCount) {
                throw new IllegalArgumentException("vertex range is outside geometry: " + label);
            }
            var x = packed.getFloat(first * stride);
            var y = packed.getFloat(first * stride + 4);
            var z = packed.getFloat(first * stride + 8);
            for (var k = 1; k < count; k++) {
                packed.putFloat((first + k) * stride, x);
                packed.putFloat((first + k) * stride + 4, y);
                packed.putFloat((first + k) * stride + 8, z);
            }
        }
        for (var range = 0; range + 1 < ranges.length; range += 2) {
            var first = ranges[range];
            var count = ranges[range + 1];
            if (first < 0 || count < 1 || first + count > vertexCount) {
                throw new IllegalArgumentException("vertex range is outside geometry: " + label);
            }
            storage.updateVertices(first, packed.slice(first * stride, count * stride));
        }
        return true;
    }

    boolean updateTerrainVertices(TerrainMesh source) {
        if (closed) {
            throw new IllegalStateException("geometry is closed: " + label);
        }
        if (kind != Kind.TERRAIN || source.vertices().size() != vertexCount || source.indices().length != indexCount) {
            return false;
        }
        storage.updateVertices(packTerrainVertices(source, BgfxPayloadAllocator.AUTOMATIC));
        for (var primitive : primitives) {
            if (primitive.terrainMaterial() != null) {
                primitive.terrainMaterial().placeLayers(source.vertexBuffer());
            }
        }
        return true;
    }

    long getStableId() {
        return stableId;
    }

    String getLabel() {
        return label;
    }

    Kind getKind() {
        return kind;
    }

    int getVertexCount() {
        return vertexCount;
    }

    void bind(int firstIndex, int count) {
        storage.bind(firstIndex, count);
    }

    boolean bindLines(int firstIndex, int count) {
        return storage.bindLines(firstIndex, count);
    }

    void bindVertices(int stream, BgfxVertexLayout layout) {
        storage.bindVertices(stream, layout);
    }

    BgfxVertexLayoutSpec getVertexLayout() {
        return entityLayout(kind);
    }

    IndirectBinding getIndirectBinding() {
        return storage.getIndirectBinding();
    }

    int getIndirectVertexOffset() {
        return storage.getIndirectVertexOffset();
    }

    int getIndirectIndexOffset() {
        return storage.getIndirectIndexOffset();
    }

    long getRetainedCpuBytes() {
        return 0L;
    }

    long getEstimatedGpuBytes() {
        return storage.getEstimatedBytes();
    }

    int getIndexCount() {
        return indexCount;
    }

    Bounds3f getLocalBounds() {
        return localBounds;
    }

    float @Nullable [] getClearanceBoxes() {
        return clearanceBoxes;
    }

    List<Primitive> getPrimitives() {
        return primitives;
    }

    void collectTextures(Set<TextureRef> target) {
        for (var primitive : primitives) {
            if (primitive.entityMaterial() != null) {
                primitive.entityMaterial().collectTextures(target);
            }
            if (primitive.terrainMaterial() != null) {
                primitive.terrainMaterial().collectTextures(target);
            }
        }
    }

    boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        for (var index = primitives.size() - 1; index >= 0; index--) {
            var primitive = primitives.get(index);
            failure = closeAndMerge(primitive.entityMaterial(), failure);
            failure = closeAndMerge(primitive.terrainMaterial(), failure);
        }
        try {
            storage.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Prepared prepareNamed(
            Entity entity, String label, @Nullable AlphaCache rayOpacity, BgfxPayloadAllocator memory) {
        var stableId = StableRenderId.builder("geometry").put(label).build();
        return switch (entity.getMesh()) {
            case EntityMesh mesh ->
                prepareEntity(stableId, label, mesh, entity.getLightmapLayers(), rayOpacity, memory);
            case TerrainMesh mesh -> prepareTerrain(stableId, label, mesh, rayOpacity, memory);
        };
    }

    private static Prepared prepareEntity(
            long stableId,
            String label,
            EntityMesh source,
            int[] lightmapLayers,
            @Nullable AlphaCache rayOpacity,
            BgfxPayloadAllocator memory) {
        var vertices = source.vertexBuffer();
        if (vertices.isEmpty() || source.indices().length < 3) {
            throw new IllegalArgumentException("entity geometry must contain indexed triangles");
        }
        var staticMesh = isStaticMeshOnly(source);
        var nativeTangents = staticMesh && vertices.hasStaticMeshTangent(0);
        for (var vertex = 1; staticMesh && vertex < vertices.size(); vertex++) {
            if (vertices.hasStaticMeshTangent(vertex) != nativeTangents) {
                throw new IllegalArgumentException("static-mesh tangent stream must cover every vertex");
            }
        }
        var kind = !staticMesh ? Kind.ENTITY : nativeTangents ? Kind.STATIC_MESH_TANGENT : Kind.STATIC_MESH_COLOR;
        var waterTangents = kind == Kind.ENTITY ? buildHfWaterTangents(source) : null;
        var layout = entityLayout(kind);
        var packed = packEntityVertices(kind, source, lightmapLayers, nativeTangents, waterTangents, memory);
        var packedIndices = packIndices(source.indices(), memory);
        var primitives = new ArrayList<PreparedPrimitive>(source.surfaces().size());
        for (var ordinal = 0; ordinal < source.surfaces().size(); ordinal++) {
            var surface = source.surfaces().get(ordinal);
            validateRange(surface.indexOffset(), surface.indexCount(), source.indices().length);
            if (surface.indexCount() == 0) {
                continue;
            }
            var primitiveId = StableRenderId.builder("primitive")
                    .put(stableId)
                    .put(ordinal)
                    .put(surface.type())
                    .put(surface.blendMode().ordinal())
                    .build();
            var materialId = StableRenderId.builder("material")
                    .put(primitiveId)
                    .put(surface.materialName())
                    .put(surface.material().getClass().getName())
                    .build();
            primitives.add(new PreparedPrimitive(
                    primitiveId,
                    materialId,
                    ordinal,
                    surface.indexOffset(),
                    surface.indexCount(),
                    boundsOfRange(vertices, source.indices(), surface.indexOffset(), surface.indexCount()),
                    BgfxRayOpacity.prepare(surface, rayOpacity)));
        }
        return new Prepared(
                stableId,
                label,
                kind,
                packed,
                packedIndices,
                vertices.size(),
                source.indices().length,
                toBounds(source.boundingBox()),
                primitives,
                null,
                !ClearanceBoxes.isWide(toBounds(source.boundingBox()))
                        ? null
                        : ClearanceBoxes.of(
                                new ClearanceBoxes.Positions() {
                                    @Override
                                    public float getX(int vertex) {
                                        return vertices.getPositionX(vertex);
                                    }

                                    @Override
                                    public float getY(int vertex) {
                                        return vertices.getPositionY(vertex);
                                    }

                                    @Override
                                    public float getZ(int vertex) {
                                        return vertices.getPositionZ(vertex);
                                    }
                                },
                                source.indices()));
    }

    private static BgfxSceneGeometry installEntity(
            BgfxGeometryPool geometryPool, BgfxTextureCache textures, EntityMesh source, Prepared prepared) {
        if (!prepared.kind().isInstanced()) {
            throw new IllegalArgumentException(
                    "prepared geometry kind mismatch: expected instanced entity but was " + prepared.kind());
        }
        Slice storage = null;
        var primitives = new ArrayList<Primitive>(prepared.primitives().size());
        try {
            storage = geometryPool.allocate(
                    prepared.kind(),
                    prepared.label(),
                    prepared.vertexData(),
                    prepared.vertexCount(),
                    prepared.indexData(),
                    prepared.indexCount());
            for (var descriptor : prepared.primitives()) {
                var surface = source.surfaces().get(descriptor.sourceSurfaceOrdinal());
                primitives.add(new Primitive(
                        descriptor.stableId(),
                        descriptor.sourceSurfaceOrdinal(),
                        descriptor.indexOffset(),
                        descriptor.indexCount(),
                        descriptor.localBounds(),
                        BgfxSceneMaterial.create(descriptor.materialId(), surface, textures),
                        null,
                        surface.reflectionPlane(),
                        surface.distanceFog(),
                        surface.waterSheetSource(),
                        surface.bspDrawSource() == null
                                ? -1L
                                : surface.bspDrawSource().zoneMask()));
            }
            return new BgfxSceneGeometry(
                    prepared.stableId(),
                    prepared.label(),
                    prepared.kind(),
                    storage,
                    prepared.vertexCount(),
                    prepared.indexCount(),
                    prepared.localBounds(),
                    primitives,
                    prepared.terrainRows(),
                    prepared.clearanceBoxes());
        } catch (RuntimeException | Error e) {
            for (var primitive : primitives) {
                closeAfterFailure(primitive.entityMaterial(), e);
            }
            closeAfterFailure(storage, e);
            throw e;
        }
    }

    private static Prepared prepareTerrain(
            long stableId,
            String label,
            TerrainMesh source,
            @Nullable AlphaCache rayOpacity,
            BgfxPayloadAllocator memory) {
        if (source.vertices().isEmpty() || source.indices().length < 3) {
            throw new IllegalArgumentException("terrain geometry must contain indexed triangles");
        }
        var vertices = source.vertexBuffer();
        var packed = packTerrainVertices(source, memory);
        var packedIndices = packIndices(source.indices(), memory);
        var surface = source.surface();
        validateRange(surface.indexOffset(), surface.indexCount(), source.indices().length);
        if (!(surface.material() instanceof MultiTextureEntityMaterial terrain)) {
            throw new IllegalArgumentException("terrain surface requires a multi-texture material");
        }
        var primitiveId = StableRenderId.builder("primitive")
                .put(stableId)
                .put(0)
                .put(surface.type())
                .build();
        var materialId = StableRenderId.builder("material")
                .put(primitiveId)
                .put("terrain")
                .build();
        return new Prepared(
                stableId,
                label,
                Kind.TERRAIN,
                packed,
                packedIndices,
                vertices.size(),
                source.indices().length,
                toBounds(source.boundingBox()),
                List.of(new PreparedPrimitive(
                        primitiveId,
                        materialId,
                        0,
                        surface.indexOffset(),
                        surface.indexCount(),
                        boundsOfTerrainRange(vertices, source.indices(), surface.indexOffset(), surface.indexCount()),
                        BgfxRayOpacity.prepare(surface, rayOpacity))),
                terrainRows(vertices, source.indices()),
                ClearanceBoxes.of(
                        new ClearanceBoxes.Positions() {
                            @Override
                            public float getX(int vertex) {
                                return vertices.getPositionX(vertex);
                            }

                            @Override
                            public float getY(int vertex) {
                                return vertices.getPositionY(vertex);
                            }

                            @Override
                            public float getZ(int vertex) {
                                return vertices.getPositionZ(vertex);
                            }
                        },
                        source.indices()));
    }

    private static ByteBuffer packEntityVertices(
            Kind kind,
            EntityMesh source,
            int @Nullable [] lightmapLayers,
            boolean nativeTangents,
            float @Nullable [] waterTangents,
            BgfxPayloadAllocator memory) {
        var vertices = source.vertexBuffer();
        var layout = entityLayout(kind);
        var packed = memory.allocate(Math.multiplyExact(vertices.size(), layout.getStride()))
                .order(ByteOrder.nativeOrder());
        if (lightmapLayers != null && lightmapLayers.length != vertices.size()) {
            throw new IllegalArgumentException("lightmap layer count does not match entity vertex count: "
                    + lightmapLayers.length + " != " + vertices.size());
        }
        for (var vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            put3(
                    packed,
                    vertices.getPositionX(vertexIndex),
                    vertices.getPositionY(vertexIndex),
                    vertices.getPositionZ(vertexIndex));
            if (kind == Kind.ENTITY || kind == Kind.STATIC_MESH_TANGENT) {
                put3(
                        packed,
                        vertices.getNormalX(vertexIndex),
                        vertices.getNormalY(vertexIndex),
                        vertices.getNormalZ(vertexIndex));
                packed.putFloat(vertices.getSunVisibilityMask(vertexIndex));
                if (nativeTangents) {
                    put3(
                            packed,
                            vertices.getStaticMeshTangentX(vertexIndex),
                            vertices.getStaticMeshTangentY(vertexIndex),
                            vertices.getStaticMeshTangentZ(vertexIndex));
                    packed.putFloat(vertices.getStaticMeshTangentW(vertexIndex));
                } else {
                    var tangentOffset = vertexIndex * 3;
                    put3(
                            packed,
                            waterTangents[tangentOffset],
                            waterTangents[tangentOffset + 1],
                            waterTangents[tangentOffset + 2]);
                    packed.putFloat(vertices.hasBspTangent(vertexIndex) ? 2f : 0f);
                }
            } else {
                putHalf4(
                        packed,
                        vertices.getNormalX(vertexIndex),
                        vertices.getNormalY(vertexIndex),
                        vertices.getNormalZ(vertexIndex),
                        vertices.getSunVisibilityMask(vertexIndex));
            }
            put2(packed, vertices.getUvX(vertexIndex), vertices.getUvY(vertexIndex));
            if (kind == Kind.STATIC_MESH || kind == Kind.STATIC_MESH_COLOR) {
                put2(packed, vertices.getSecondaryUvX(vertexIndex), vertices.getSecondaryUvY(vertexIndex));
                putHalf4(
                        packed,
                        vertices.getBakedLightX(vertexIndex),
                        vertices.getBakedLightY(vertexIndex),
                        vertices.getBakedLightZ(vertexIndex),
                        BgfxSceneVertexLayouts.COMPACT_STATIC_MESH_MARKER);
                if (kind == Kind.STATIC_MESH_COLOR) {
                    putColor(packed, vertices, vertexIndex);
                }
                continue;
            }
            put2(packed, vertices.getLightmapUvX(vertexIndex), vertices.getLightmapUvY(vertexIndex));
            packed.putFloat(lightmapLayers == null ? 0f : lightmapLayers[vertexIndex]);
            var hasBspPlane = vertices.hasBspPlane(vertexIndex);
            packed.putFloat(hasBspPlane ? vertices.getBspPlaneW(vertexIndex) : 0f);
            put2(packed, vertices.getSecondaryUvX(vertexIndex), vertices.getSecondaryUvY(vertexIndex));
            put2(
                    packed,
                    hasBspPlane ? vertices.getBspPlaneX(vertexIndex) : 0f,
                    hasBspPlane ? vertices.getBspPlaneY(vertexIndex) : 0f);
            putHalf4(
                    packed,
                    vertices.getBakedLightX(vertexIndex),
                    vertices.getBakedLightY(vertexIndex),
                    vertices.getBakedLightZ(vertexIndex),
                    hasBspPlane ? vertices.getBspPlaneZ(vertexIndex) : 0f);
            putColor(packed, vertices, vertexIndex);
        }
        packed.flip();
        return packed;
    }

    private static ByteBuffer packTerrainVertices(TerrainMesh source, BgfxPayloadAllocator memory) {
        var vertices = source.vertexBuffer();
        var packed = memory.allocate(Math.multiplyExact(vertices.size(), BgfxSceneVertexLayouts.TERRAIN.getStride()))
                .order(ByteOrder.nativeOrder());
        for (var vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            put3(
                    packed,
                    vertices.getPositionX(vertexIndex),
                    vertices.getPositionY(vertexIndex),
                    vertices.getPositionZ(vertexIndex));
            put3(
                    packed,
                    vertices.getNormalX(vertexIndex),
                    vertices.getNormalY(vertexIndex),
                    vertices.getNormalZ(vertexIndex));
            put2(packed, vertices.getAlphaMapU(vertexIndex), vertices.getAlphaMapV(vertexIndex));
            for (var variant = 0; variant < 8; variant++) {
                packed.putShort(Float.floatToFloat16(
                        terrainLight(source, vertexIndex, variant, vertices.getLightValue(vertexIndex))));
            }
            packed.put((byte) vertices.getSteadyLightRed(vertexIndex));
            packed.put((byte) vertices.getSteadyLightGreen(vertexIndex));
            packed.put((byte) vertices.getSteadyLightBlue(vertexIndex));
            packed.put((byte) 0xff);
        }
        return packed.flip();
    }

    private static BgfxSceneGeometry installTerrain(
            BgfxGeometryPool geometryPool, BgfxTextureCache textures, TerrainMesh source, Prepared prepared) {
        prepared.requireKind(Kind.TERRAIN);
        var surface = source.surface();
        if (!(surface.material() instanceof MultiTextureEntityMaterial terrain)) {
            throw new IllegalArgumentException("terrain surface requires a multi-texture material");
        }
        var descriptor = prepared.primitives().getFirst();
        Slice storage = null;
        BgfxTerrainMaterial material = null;
        try {
            storage = geometryPool.allocate(
                    prepared.kind(),
                    prepared.label(),
                    prepared.vertexData(),
                    prepared.vertexCount(),
                    prepared.indexData(),
                    prepared.indexCount());
            material = BgfxTerrainMaterial.create(
                    descriptor.materialId(), terrain.multiTexture(), prepared.label(), textures, source.vertexBuffer());
            return new BgfxSceneGeometry(
                    prepared.stableId(),
                    prepared.label(),
                    Kind.TERRAIN,
                    storage,
                    prepared.vertexCount(),
                    prepared.indexCount(),
                    prepared.localBounds(),
                    List.of(new Primitive(
                            descriptor.stableId(),
                            descriptor.sourceSurfaceOrdinal(),
                            descriptor.indexOffset(),
                            descriptor.indexCount(),
                            descriptor.localBounds(),
                            null,
                            material,
                            null,
                            null,
                            null,
                            -1L)),
                    prepared.terrainRows(),
                    prepared.clearanceBoxes());
        } catch (RuntimeException | Error e) {
            closeAfterFailure(material, e);
            closeAfterFailure(storage, e);
            throw e;
        }
    }

    private static float terrainLight(TerrainMesh source, int vertexIndex, int variant, float fallback) {
        var variants = source.lightVariants();
        if (variants == null || variants.length == 0) {
            return fallback;
        }
        var values = variants[Math.min(variant, variants.length - 1)];
        return values == null || vertexIndex >= values.length ? fallback : values[vertexIndex];
    }

    private static ByteBuffer packIndices(int[] indices, BgfxPayloadAllocator memory) {
        var result = memory.allocate(Math.multiplyExact(indices.length, Integer.BYTES))
                .order(ByteOrder.nativeOrder());
        result.asIntBuffer().put(indices);
        return result;
    }

    private static ByteBuffer readOnly(ByteBuffer source) {
        if (source == null || !source.hasRemaining()) {
            throw new IllegalArgumentException("retained geometry payload cannot be empty");
        }
        var duplicate = source.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        duplicate.position(0);
        return duplicate;
    }

    private static void putTextureCoordinate(ByteBuffer target, float[] coordinates, int index) {
        var offset = index * 2;
        if (offset + 1 < coordinates.length) {
            put2(target, coordinates[offset], coordinates[offset + 1]);
        } else {
            put2(target, 0f, 0f);
        }
    }

    private static void put2(ByteBuffer target, Vec2 value) {
        put2(target, value.getX(), value.getY());
    }

    private static void put2(ByteBuffer target, float x, float y) {
        target.putFloat(x).putFloat(y);
    }

    private static void put3(ByteBuffer target, float x, float y, float z) {
        target.putFloat(x).putFloat(y).putFloat(z);
    }

    private static void putHalf4(ByteBuffer target, float x, float y, float z, float w) {
        target.putShort(Float.floatToFloat16(x));
        target.putShort(Float.floatToFloat16(y));
        target.putShort(Float.floatToFloat16(z));
        target.putShort(Float.floatToFloat16(w));
    }

    private static void putColor(ByteBuffer target, MeshVertexBuffer vertices, int vertexIndex) {
        var hasColor = vertices.hasColor(vertexIndex);
        target.put(toUnorm(hasColor ? vertices.getColorR(vertexIndex) : 1f));
        target.put(toUnorm(hasColor ? vertices.getColorG(vertexIndex) : 1f));
        target.put(toUnorm(hasColor ? vertices.getColorB(vertexIndex) : 1f));
        target.put(toUnorm(hasColor ? vertices.getColorA(vertexIndex) : 1f));
    }

    private static boolean isStaticMeshOnly(EntityMesh source) {
        var staticMesh = SurfaceType.STATIC_MESH.getId();
        return source.surfaces().stream().allMatch(surface -> (surface.type() & staticMesh) != 0);
    }

    private static void putRawTangent(float[] tangents, boolean[] touched, int vertexIndex, float x, float y, float z) {
        var offset = vertexIndex * 3;
        tangents[offset] = x;
        tangents[offset + 1] = y;
        tangents[offset + 2] = z;
        touched[vertexIndex] = true;
    }

    private static byte toUnorm(float value) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
    }

    private static void validateRange(int offset, int count, int indexCount) {
        if (offset < 0 || count < 0 || (long) offset + count > indexCount) {
            throw new IllegalArgumentException("surface index range is outside its geometry");
        }
    }

    private static Bounds3f toBounds(Box box) {
        return new Bounds3f(
                box.getMin().getX(),
                box.getMin().getY(),
                box.getMin().getZ(),
                box.getMax().getX(),
                box.getMax().getY(),
                box.getMax().getZ());
    }

    private static Bounds3f boundsOfRange(MeshVertexBuffer vertices, int[] indices, int offset, int count) {
        var firstIndex = indices[offset];
        var minX = vertices.getPositionX(firstIndex);
        var minY = vertices.getPositionY(firstIndex);
        var minZ = vertices.getPositionZ(firstIndex);
        var maxX = minX;
        var maxY = minY;
        var maxZ = minZ;
        for (var cursor = offset + 1; cursor < offset + count; cursor++) {
            var vertexIndex = indices[cursor];
            var x = vertices.getPositionX(vertexIndex);
            var y = vertices.getPositionY(vertexIndex);
            var z = vertices.getPositionZ(vertexIndex);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Bounds3f boundsOfTerrainRange(TerrainVertexBuffer vertices, int[] indices, int offset, int count) {
        var firstIndex = indices[offset];
        var minX = vertices.getPositionX(firstIndex);
        var minY = vertices.getPositionY(firstIndex);
        var minZ = vertices.getPositionZ(firstIndex);
        var maxX = minX;
        var maxY = minY;
        var maxZ = minZ;
        for (var cursor = offset + 1; cursor < offset + count; cursor++) {
            var vertexIndex = indices[cursor];
            var x = vertices.getPositionX(vertexIndex);
            var y = vertices.getPositionY(vertexIndex);
            var z = vertices.getPositionZ(vertexIndex);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new Bounds3f(minX, minY, minZ, maxX, maxY, maxZ);
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

    private static RuntimeException closeAndMerge(AutoCloseable resource, RuntimeException failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Exception e) {
            var runtime = e instanceof RuntimeException r ? r : new IllegalStateException(e);
            if (failure == null) {
                return runtime;
            }
            failure.addSuppressed(runtime);
        }
        return failure;
    }

    enum Kind {
        ENTITY,
        STATIC_MESH,
        STATIC_MESH_COLOR,
        STATIC_MESH_TANGENT,
        TERRAIN;

        boolean isInstanced() {
            return this != TERRAIN;
        }
    }

    record TerrainRows(int rows, float[] rowMinY, float[] rowMaxY, int[] rowFirstIndex) {
        int firstIndex(float minY) {
            var row = 0;
            while (row < rows && rowMaxY[row] < minY) {
                row++;
            }
            return rowFirstIndex[row];
        }

        int endIndex(float maxY) {
            var row = rows;
            while (row > 0 && rowMinY[row - 1] > maxY) {
                row--;
            }
            return rowFirstIndex[row];
        }
    }
}
