package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.Bsp.UPrimitive;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.unreal.primitives.UColor;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import java.util.Arrays;
import org.eclipse.collections.api.factory.primitive.ObjectIntMaps;
import org.jetbrains.annotations.Nullable;

public class UStaticMesh extends UPrimitive {
    public static final float LOD_RANGE_UNIT = 2048f;

    private Surface[] surfaces;
    private VertexStream vertexStream;
    private ColorStream colorStream, alphaStream;
    private UvStream[] uvStream;
    private @Nullable UvStream beastLightmapUvStream;
    private int beastLightmapSize;
    private int beastLightmapExtra;
    private IndexStream indexStream, wireframeIndexStream;
    private final UObjectRef<UModel, Optional> collisionModel = UObjectRef.optional();

    private Material[] materials;
    private boolean useSimpleLineCollision;
    private boolean useSimpleBoxCollision;
    private boolean useSimpleKarmaCollision;
    private boolean useVertexColor;
    private boolean hasStaticMeshLod;
    private final UObjectRef<UStaticMesh, Optional> staticMeshLod1 = UObjectRef.optional();
    private final UObjectRef<UStaticMesh, Optional> staticMeshLod2 = UObjectRef.optional();
    private float lodRange1;
    private float lodRange2;

    private boolean swayObject;
    private float frequency;
    private float maxSwayAngle = 1f;
    private boolean staticMeshLodBlend;
    private boolean makeTwoSideMesh;
    private int nativeUnknown;
    private boolean useBillBoard;
    private boolean useNormalMap;
    private boolean[] mirrorDoubledSurfaces;

    private int collisionOffset = -1;

    public UStaticMesh(UArchive archive) {
        super(archive);
        useSimpleLineCollision = false;
        useSimpleBoxCollision = true;
        useSimpleKarmaCollision = true;
    }

    public static class Surface {
        private final long unknown;
        private final int firstIndex;
        private final int minVertexIndex;
        private final int maxVertexIndex;
        private final int triangleCount;
        private final int triangleMax;

        private Surface(UArchive archive) {
            unknown = archive.readUInt32();
            firstIndex = archive.readUInt16();
            minVertexIndex = archive.readUInt16();
            maxVertexIndex = archive.readUInt16();
            triangleCount = archive.readUInt16();
            triangleMax = archive.readUInt16();
        }

        public long getUnknown() {
            return this.unknown;
        }

        public int getFirstIndex() {
            return this.firstIndex;
        }

        public int getMinVertexIndex() {
            return this.minVertexIndex;
        }

        public int getMaxVertexIndex() {
            return this.maxVertexIndex;
        }

        public int getTriangleCount() {
            return this.triangleCount;
        }

        public int getTriangleMax() {
            return this.triangleMax;
        }
    }

    public static class Vertex {
        private final Vec3 location;
        private final Vec3 normal;

        private Vertex(UArchive archive) {
            location = archive.readVector();
            normal = archive.readVector();
        }

        public Vec3 getLocation() {
            return this.location;
        }

        public Vec3 getNormal() {
            return this.normal;
        }
    }

    public static class VertexStream {
        private Vertex[] vertices;
        private final long revision;

        private VertexStream(UArchive archive) {
            vertices = archive.readArray(Vertex.class, Vertex::new);
            revision = archive.readUInt32();
        }

        public Vertex[] getVertices() {
            return this.vertices;
        }

        public long getRevision() {
            return this.revision;
        }
    }

    public static class ColorStream {
        private UColor[] colors;
        private final long revision;

        private ColorStream(UArchive archive) {
            colors = archive.readArray(UColor.class, UColor::of);
            revision = archive.readUInt32();
        }

        public UColor[] getColors() {
            return this.colors;
        }

        public long getRevision() {
            return this.revision;
        }
    }

    public static class UvStream {
        private Vec2[] uvs;
        private final long coordinateIndex;
        private final long revision;

        private UvStream(UArchive archive) {
            uvs = archive.readArray(Vec2.class, a -> new Vec2(a.readFloat(), a.readFloat()));
            coordinateIndex = archive.readUInt32();
            revision = archive.readUInt32();
        }

        public Vec2[] getUvs() {
            return this.uvs;
        }

        public long getCoordinateIndex() {
            return this.coordinateIndex;
        }

        public long getRevision() {
            return this.revision;
        }
    }

    public static class IndexStream {
        private final int[] indices;
        private final long revision;

        private IndexStream(UArchive archive) {
            indices = archive.readUInt16Array();
            revision = archive.readUInt32();
        }

        public int[] getIndices() {
            return this.indices;
        }

        public long getRevision() {
            return this.revision;
        }
    }

    public static class Material {
        private final boolean enableCollision;
        private final UObjectRef<UMaterial, Optional> material = UObjectRef.optional();

        public Material(boolean enableCollision) {
            this.enableCollision = enableCollision;
        }

        public boolean isEnableCollision() {
            return this.enableCollision;
        }

        public UObjectRef<UMaterial, Optional> getMaterial() {
            return this.material;
        }
    }

    public float getLodDistance1() {
        return lodRange1 * LOD_RANGE_UNIT;
    }

    public float getLodDistance2() {
        return lodRange2 * LOD_RANGE_UNIT;
    }

    public record Collision(float[] planes, int[] materials, int[] nodes, float[] boxes) {
        public int faceCount() {
            return materials.length;
        }

        public int nodeCount() {
            return nodes.length / 4;
        }
    }

    @Override
    public void deserialize() {
        super.deserialize();
        surfaces = archive.readArray(Surface.class, Surface::new);
        boundingBox = UBox.of(archive);
        vertexStream = new VertexStream(archive);
        colorStream = new ColorStream(archive);
        alphaStream = new ColorStream(archive);
        uvStream = archive.readArray(UvStream.class, UvStream::new);
        indexStream = new IndexStream(archive);
        wireframeIndexStream = new IndexStream(archive);
        collisionModel.fromArchive(archive);

        var fileVersion = archive.getHeader().getFileVersion();
        var licenseVersion = archive.getHeader().getLicenseVersion();
        if (fileVersion < 114) {
            return;
        }
        if (licenseVersion < 17) {
            return;
        }
        collisionOffset = archive.getOffset();
        skipLazyArray("collision faces");
        skipLazyArray("collision nodes");
        if (licenseVersion > 5) {
            hasStaticMeshLod = archive.readInt() != 0;
            staticMeshLod1.fromArchive(archive);
            staticMeshLod2.fromArchive(archive);
            lodRange1 = archive.readFloat();
            lodRange2 = archive.readFloat();
        }
        if (licenseVersion > 6) {
            swayObject = archive.readInt() != 0;
            frequency = archive.readFloat();
        }
        if (licenseVersion > 11) {
            maxSwayAngle = archive.readFloat();
        }
        if (licenseVersion > 12) {
            staticMeshLodBlend = archive.readInt() != 0;
        }
        if (licenseVersion > 13) {
            makeTwoSideMesh = archive.readInt() != 0;
            nativeUnknown = archive.readInt();
        }
        if (licenseVersion > 14) {
            useBillBoard = archive.readInt() != 0;
        }
        if (licenseVersion > 36) {
            useNormalMap = archive.readInt() != 0;
        }
        skipLazyArray("raw triangles");
        archive.readInt();
        if (fileVersion >= 100) {
            archive.readIndex();
        }
        if (fileVersion >= 120) {
            archive.readInt();
        }
        if (fileVersion >= 124) {
            beastLightmapUvStream = new UvStream(archive);
            beastLightmapSize = archive.readInt();
            beastLightmapExtra = archive.readInt();
        }
    }

    public @Nullable Collision readCollision() {
        if (collisionOffset < 0) {
            return null;
        }
        var saved = archive.getOffset();
        try {
            archive.setPosition(collisionOffset);
            var facesEnd = archive.readInt();
            var faceCount = archive.readIndex();
            if (faceCount < 0 || faceCount > 1_000_000) {
                throw new IllegalStateException("Invalid StaticMesh collision face count: " + faceCount);
            }
            var planes = new float[faceCount * 16];
            var materials = new int[faceCount];
            for (var face = 0; face < faceCount; face++) {
                for (var value = 0; value < 16; value++) {
                    planes[face * 16 + value] = archive.readFloat();
                }
                archive.readIndex();
                archive.readIndex();
                archive.readIndex();
                materials[face] = archive.readIndex();
            }
            if (archive.getOffset() != facesEnd) {
                throw new IllegalStateException("StaticMesh collision faces did not end at their lazy offset");
            }
            var nodesEnd = archive.readInt();
            var nodeCount = archive.readIndex();
            if (nodeCount < 0 || nodeCount > 1_000_000) {
                throw new IllegalStateException("Invalid StaticMesh collision node count: " + nodeCount);
            }
            var nodes = new int[nodeCount * 4];
            var boxes = new float[nodeCount * 6];
            for (var node = 0; node < nodeCount; node++) {
                for (var value = 0; value < 4; value++) {
                    nodes[node * 4 + value] = archive.readIndex();
                }
                for (var value = 0; value < 6; value++) {
                    boxes[node * 6 + value] = archive.readFloat();
                }
                archive.readByte();
            }
            if (archive.getOffset() != nodesEnd) {
                throw new IllegalStateException("StaticMesh collision nodes did not end at their lazy offset");
            }
            return new Collision(planes, materials, nodes, boxes);
        } finally {
            archive.setPosition(saved);
        }
    }

    public Vec2 @Nullable [] getBeastLightmapUvs() {
        return beastLightmapUvStream == null ? null : beastLightmapUvStream.getUvs();
    }

    public @Nullable UvStream getBeastLightmapUvStream() {
        return beastLightmapUvStream;
    }

    public int getBeastLightmapSize() {
        return beastLightmapSize;
    }

    public int getBeastLightmapExtra() {
        return beastLightmapExtra;
    }

    public boolean isSurfaceMirrorDoubled(int surfaceIndex) {
        if (surfaces == null || surfaceIndex < 0 || surfaceIndex >= surfaces.length) {
            return false;
        }
        if (mirrorDoubledSurfaces == null) {
            mirrorDoubledSurfaces = measureMirrorDoubling();
        }
        return mirrorDoubledSurfaces[surfaceIndex];
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "Materials" -> {
                materials = new Material[property.getArraySize()];
                for (int i = 0; i < materials.length; i++) {
                    var material = new Material(
                            property.subProperty("EnableCollision", i).isTrue());
                    material.material.fromProperty(archive, property.subProperty("Material", i));
                    materials[i] = material;
                }
                yield true;
            }
            case "UseSimpleLineCollision" -> {
                useSimpleLineCollision = property.isTrue();
                yield true;
            }
            case "UseSimpleBoxCollision" -> {
                useSimpleBoxCollision = property.isTrue();
                yield true;
            }
            case "UseSimpleKarmaCollision" -> {
                useSimpleKarmaCollision = property.isTrue();
                yield true;
            }
            case "UseVertexColor" -> {
                useVertexColor = property.isTrue();
                yield true;
            }
            case "bSwayObject" -> {
                swayObject = property.isTrue();
                yield true;
            }
            case "Frequency" -> {
                frequency = property.getFloatValue();
                yield true;
            }
            case "MaxSwayAngle" -> {
                maxSwayAngle = property.getFloatValue();
                yield true;
            }
            case "bUseBillBoard" -> {
                useBillBoard = property.isTrue();
                yield true;
            }
            case "bUseNormalmap" -> {
                useNormalMap = property.isTrue();
                yield true;
            }
            default -> false;
        };
    }

    private record PositionKey(int x, int y, int z) {}

    public Surface[] getSurfaces() {
        return this.surfaces;
    }

    public VertexStream getVertexStream() {
        return this.vertexStream;
    }

    public ColorStream getColorStream() {
        return this.colorStream;
    }

    public ColorStream getAlphaStream() {
        return this.alphaStream;
    }

    public UvStream[] getUvStream() {
        return this.uvStream;
    }

    public IndexStream getIndexStream() {
        return this.indexStream;
    }

    public IndexStream getWireframeIndexStream() {
        return this.wireframeIndexStream;
    }

    public UObjectRef<UModel, Optional> getCollisionModel() {
        return this.collisionModel;
    }

    public Material[] getMaterials() {
        return this.materials;
    }

    public boolean isUseSimpleLineCollision() {
        return this.useSimpleLineCollision;
    }

    public boolean isUseSimpleBoxCollision() {
        return this.useSimpleBoxCollision;
    }

    public boolean isUseSimpleKarmaCollision() {
        return this.useSimpleKarmaCollision;
    }

    public boolean isUseVertexColor() {
        return this.useVertexColor;
    }

    public boolean isHasStaticMeshLod() {
        return this.hasStaticMeshLod;
    }

    public UObjectRef<UStaticMesh, Optional> getStaticMeshLod1() {
        return this.staticMeshLod1;
    }

    public UObjectRef<UStaticMesh, Optional> getStaticMeshLod2() {
        return this.staticMeshLod2;
    }

    public float getLodRange1() {
        return this.lodRange1;
    }

    public float getLodRange2() {
        return this.lodRange2;
    }

    public boolean isSwayObject() {
        return this.swayObject;
    }

    public float getFrequency() {
        return this.frequency;
    }

    public float getMaxSwayAngle() {
        return this.maxSwayAngle;
    }

    public boolean isStaticMeshLodBlend() {
        return this.staticMeshLodBlend;
    }

    public boolean isMakeTwoSideMesh() {
        return this.makeTwoSideMesh;
    }

    public int getNativeUnknown() {
        return this.nativeUnknown;
    }

    public boolean isUseBillBoard() {
        return this.useBillBoard;
    }

    public boolean isUseNormalMap() {
        return this.useNormalMap;
    }

    private void skipLazyArray(String name) {
        var endOffset = archive.readInt();
        if (endOffset < archive.getOffset()) {
            throw new IllegalStateException("Invalid StaticMesh " + name + " lazy-array end: " + endOffset);
        }
        archive.setPosition(endOffset);
    }

    private boolean[] measureMirrorDoubling() {
        var doubled = new boolean[surfaces.length];
        var vertices = vertexStream == null ? null : vertexStream.getVertices();
        var indices = indexStream == null ? null : indexStream.getIndices();
        if (vertices == null || indices == null || vertices.length > 0xFFFF) {
            return doubled;
        }
        var canonical = canonicalVertexIds(vertices);
        for (int i = 0; i < surfaces.length; i++) {
            doubled[i] = isMirrorDoubled(surfaces[i], indices, canonical);
        }
        return doubled;
    }

    private static int[] canonicalVertexIds(Vertex[] vertices) {
        var byPosition = ObjectIntMaps.mutable.<PositionKey>empty();
        var canonical = new int[vertices.length];
        for (int i = 0; i < vertices.length; i++) {
            var location = vertices[i].getLocation();
            var key = new PositionKey(
                    Float.floatToIntBits(location.getX()),
                    Float.floatToIntBits(location.getY()),
                    Float.floatToIntBits(location.getZ()));
            canonical[i] = byPosition.getIfAbsentPut(key, i);
        }
        return canonical;
    }

    private static boolean isMirrorDoubled(Surface surface, int[] indices, int[] canonical) {
        var triangles = surface.getTriangleMax();
        if (triangles < 2 || (triangles & 1) != 0) {
            return false;
        }
        var keys = new long[triangles];
        for (int t = 0; t < triangles; t++) {
            var start = surface.getFirstIndex() + t * 3;
            if (start + 2 >= indices.length) {
                return false;
            }
            var key = orientedKey(
                    canonical[indices[start]], canonical[indices[start + 1]], canonical[indices[start + 2]]);
            if (key < 0) {
                return false;
            }
            keys[t] = key;
        }
        Arrays.sort(keys);
        var runStart = 0;
        while (runStart < keys.length) {
            var runEnd = runStart;
            while (runEnd < keys.length && keys[runEnd] == keys[runStart]) {
                runEnd++;
            }
            if (runLength(keys, reversedKey(keys[runStart])) != runEnd - runStart) {
                return false;
            }
            runStart = runEnd;
        }
        return true;
    }

    private static long orientedKey(int a, int b, int c) {
        if (a == b || b == c || a == c) {
            return -1;
        }
        if (b < a && b < c) {
            return pack(b, c, a);
        }
        if (c < a && c < b) {
            return pack(c, a, b);
        }
        return pack(a, b, c);
    }

    private static long pack(int a, int b, int c) {
        return ((long) a << 32) | ((long) b << 16) | c;
    }

    private static long reversedKey(long key) {
        var a = (int) (key >>> 32);
        var b = (int) ((key >>> 16) & 0xFFFF);
        var c = (int) (key & 0xFFFF);
        return orientedKey(a, c, b);
    }

    private static int runLength(long[] sortedKeys, long key) {
        var at = Arrays.binarySearch(sortedKeys, key);
        if (at < 0) {
            return 0;
        }
        var first = at;
        while (first > 0 && sortedKeys[first - 1] == key) {
            first--;
        }
        var last = at;
        while (last + 1 < sortedKeys.length && sortedKeys[last + 1] == key) {
            last++;
        }
        return last - first + 1;
    }
}
