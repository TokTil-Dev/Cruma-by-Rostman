package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.UObjectRef.Required;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.unreal.primitives.USphere;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class Bsp {
    public abstract static class UPrimitive extends UObject {
        protected UBox boundingBox;
        protected USphere boundingSphere;

        public UPrimitive(UArchive archive) {
            super(archive);
        }

        public void deserialize() {
            super.deserialize();
            boundingBox = UBox.of(archive);
            boundingSphere = USphere.of(archive);
        }

        public UBox getBoundingBox() {
            return this.boundingBox;
        }

        public USphere getBoundingSphere() {
            return this.boundingSphere;
        }
    }

    public static class BspNodeFlags {
        public static final int NF_NotCsg = 0x01;
        public static final int NF_ShootThrough = 0x02;
        public static final int NF_NotVisBlocking = 0x04;
        public static final int NF_PolyOccluded = 0x08;
        public static final int NF_BoxOccluded = 0x10;
        public static final int NF_BrightCorners = 0x10;
        public static final int NF_IsNew = 0x20;
        public static final int NF_IsFront = 0x40;
        public static final int NF_IsBack = 0x80;

        public static final int NF_NeverMove = 0x00;
        public static final int NF_Passable = NF_NotCsg;

        private BspNodeFlags() {}
    }

    public static class UBspNode {
        private final Vec4 plane;
        private final long zoneMask;
        private final short flags;
        private final int vertexPoolIndex;
        private final int surfaceIndex;
        private final int backIndex;
        private final int frontIndex;
        private final int planeIndex;
        private final int collisionBound;
        private final int renderBound;
        private final Vec4 exclusiveSphereBound;
        private final Vec4 inclusiveSphereBound;
        private final int[] zone = new int[2];
        private final short vertexCount;
        private final int[] leaf = new int[2];
        private int iSection = -1;
        private int iFirstVertex = -1;
        private int iLightmapIndex = -1;

        public UBspNode(UArchive archive) {
            plane = archive.readVector4();
            zoneMask = archive.readUInt64();
            flags = archive.readUInt8();
            vertexPoolIndex = archive.readIndex();
            surfaceIndex = archive.readIndex();
            backIndex = archive.readIndex();
            frontIndex = archive.readIndex();
            planeIndex = archive.readIndex();
            collisionBound = archive.readIndex();
            renderBound = archive.readIndex();
            exclusiveSphereBound = archive.readVector4();
            inclusiveSphereBound = archive.readVector4();
            zone[0] = archive.readIndex();
            zone[1] = archive.readIndex();
            vertexCount = archive.readUInt8();
            leaf[0] = archive.readInt();
            leaf[1] = archive.readInt();

            iSection = archive.readInt();
            iFirstVertex = archive.readInt();
            iLightmapIndex = archive.readInt();
        }

        public Vec4 getPlane() {
            return this.plane;
        }

        public long getZoneMask() {
            return this.zoneMask;
        }

        public short getFlags() {
            return this.flags;
        }

        public int getVertexPoolIndex() {
            return this.vertexPoolIndex;
        }

        public int getSurfaceIndex() {
            return this.surfaceIndex;
        }

        public int getBackIndex() {
            return this.backIndex;
        }

        public int getFrontIndex() {
            return this.frontIndex;
        }

        public int getPlaneIndex() {
            return this.planeIndex;
        }

        public int getCollisionBound() {
            return this.collisionBound;
        }

        public int getRenderBound() {
            return this.renderBound;
        }

        public Vec4 getExclusiveSphereBound() {
            return this.exclusiveSphereBound;
        }

        public Vec4 getInclusiveSphereBound() {
            return this.inclusiveSphereBound;
        }

        public int[] getZone() {
            return this.zone;
        }

        public short getVertexCount() {
            return this.vertexCount;
        }

        public int[] getLeaf() {
            return this.leaf;
        }

        public int getISection() {
            return this.iSection;
        }

        public int getIFirstVertex() {
            return this.iFirstVertex;
        }

        public int getILightmapIndex() {
            return this.iLightmapIndex;
        }
    }

    public static final class BspPolygonFlags {
        public static final int PF_Invisible = 0x00000001;
        public static final int PF_Masked = 0x00000002;
        public static final int PF_Translucent = 0x00000004;
        public static final int PF_NotSolid = 0x00000008;
        public static final int PF_Environment = 0x00000010;
        public static final int PF_ForceViewZone = 0x00000010;
        public static final int PF_Semisolid = 0x00000020;
        public static final int PF_Modulated = 0x00000040;
        public static final int PF_FakeBackdrop = 0x00000080;
        public static final int PF_TwoSided = 0x00000100;
        public static final int PF_AutoUPan = 0x00000200;
        public static final int PF_AutoVPan = 0x00000400;
        public static final int PF_NoSmooth = 0x00000800;
        public static final int PF_BigWavy = 0x00001000;
        public static final int PF_SpecialPoly = 0x00001000;
        public static final int PF_Unused2 = 0x00002000;
        public static final int PF_Flat = 0x00004000;
        public static final int PF_LowShadowDetail = 0x00008000;
        public static final int PF_NoMerge = 0x00010000;
        public static final int PF_CloudWavy = 0x00020000;
        public static final int PF_DirtyShadows = 0x00040000;
        public static final int PF_BrightCorners = 0x00080000;
        public static final int PF_L2_Water = 0x00080000;
        public static final int PF_SpecialLit = 0x00100000;
        public static final int PF_Gouraud = 0x00200000;
        public static final int PF_NoBoundRejection = 0x00200000;
        public static final int PF_Unlit = 0x00400000;
        public static final int PF_HighShadowDetail = 0x00800000;
        public static final int PF_Portal = 0x04000000;
        public static final int PF_AntiPortal = 0x08000000;
        public static final int PF_Mirrored = 0x20000000;

        public static final int PF_Memorized = 0x01000000;
        public static final int PF_Selected = 0x02000000;
        public static final int PF_Highlighted = 0x10000000;
        public static final int PF_FlatShaded = 0x40000000;

        public static final int PF_EdProcessed = 0x40000000;
        public static final int PF_EdCut = 0x80000000;
        public static final int PF_RenderFog = 0x40000000;
        public static final int PF_Occlude = 0x80000000;
        public static final int PF_RenderHint = 0x01000000;

        public static final int PF_NoOcclude = PF_Masked | PF_Translucent | PF_Invisible | PF_Modulated;
        public static final int PF_NoEdit = PF_Memorized | PF_Selected | PF_EdProcessed | PF_NoMerge | PF_EdCut;
        public static final int PF_NoImport =
                PF_NoEdit | PF_NoMerge | PF_Memorized | PF_Selected | PF_EdProcessed | PF_EdCut;
        public static final int PF_AddLast = PF_Semisolid | PF_NotSolid;
        public static final int PF_NoAddToBSP = PF_EdCut | PF_EdProcessed | PF_Selected | PF_Memorized;
        public static final int PF_NoShadows = PF_Unlit | PF_Invisible | PF_Environment | PF_FakeBackdrop;
        public static final int PF_Transient = PF_Highlighted;

        public static final int PF_Passable =
                PF_NoOcclude | PF_NotSolid | PF_Invisible | PF_Environment | PF_FakeBackdrop | PF_Portal;
    }

    public static class UBspSurface {
        private final UObjectRef<UMaterial, Optional> material = UObjectRef.optional();
        private final long polygonFlags;
        private final int baseIndex;
        private final int normalIndex;
        private final int uIndex;
        private final int vIndex;
        private final int brushPolygonIndex;
        private final UObjectRef<UObject, Required> actor = UObjectRef.required();
        private final Vec4 plane;
        private final float lightMapScale;
        private final int licenseTailIndex;

        public UBspSurface(UArchive archive) {
            material.fromArchive(archive);
            polygonFlags = archive.readUInt32();
            baseIndex = archive.readIndex();
            normalIndex = archive.readIndex();
            uIndex = archive.readIndex();
            vIndex = archive.readIndex();
            var version = archive.getHeader().getFileVersion();
            if (version < 101) {
                archive.readIndex();
            }
            brushPolygonIndex = archive.readIndex();
            if (version < 78) {
                archive.readInt16();
                archive.readInt16();
            }
            actor.fromArchive(archive);
            plane = version > 86 ? archive.readVector4() : new Vec4(0);
            lightMapScale = version >= 106 ? archive.readFloat() : 32f;
            licenseTailIndex = archive.getHeader().getLicenseVersion() > 20 ? archive.readInt() : -1;
        }

        public UObjectRef<UMaterial, Optional> getMaterial() {
            return this.material;
        }

        public long getPolygonFlags() {
            return this.polygonFlags;
        }

        public int getBaseIndex() {
            return this.baseIndex;
        }

        public int getNormalIndex() {
            return this.normalIndex;
        }

        public int getUIndex() {
            return this.uIndex;
        }

        public int getVIndex() {
            return this.vIndex;
        }

        public int getBrushPolygonIndex() {
            return this.brushPolygonIndex;
        }

        public UObjectRef<UObject, Required> getActor() {
            return this.actor;
        }

        public Vec4 getPlane() {
            return this.plane;
        }

        public float getLightMapScale() {
            return lightMapScale;
        }

        public int getLicenseTailIndex() {
            return licenseTailIndex;
        }
    }

    public static class UVertex {
        private final int vertexIndex;
        private final int sideIndex;

        public UVertex(UArchive archive) {
            vertexIndex = archive.readIndex();
            sideIndex = archive.readIndex();
        }

        public int getVertexIndex() {
            return this.vertexIndex;
        }

        public int getSideIndex() {
            return this.sideIndex;
        }
    }

    public record UBspLeaf(int zone, int permeating, int volumetric, long visibleZones) {}

    public static class UModel extends UPrimitive {
        private static final int MIN_ZONE_BYTES = 21;
        private static final int MAX_LIGHTMAP_EXTENT = 16_384;
        private static final int MULTI_LIGHTMAP_LEVEL_REF_VERSION = 0x7d;

        private static final Logger log = LogManager.getLogger(UModel.class);

        private Vec3[] vectors;
        private Vec3[] points;
        private UBspNode[] nodes;
        private UBspSurface[] surfaces;
        private UVertex[] vertices;
        private final UObjectRef<UPolys, Optional> polys = UObjectRef.optional();
        private int numSharedSides;
        private FZoneProperties[] zones;
        private UBox[] bounds;
        private int[] leafHulls;
        private UBspLeaf[] leaves;
        private @Nullable List<UObjectRef<LightActor, Optional>> lightActors;
        private boolean rootOutside;
        private boolean rootOutsideRead;
        private boolean linked;
        private FBspSection[] bspSections;
        private FLightmapIndex[] lightmaps;
        private FStaticLightmapTexture[] lightmapAtlases;
        private FStaticLightmapTexture[][] lightmapAtlasVariants;
        private FLightmapExtent[] lightmapExtents;
        private @Nullable Integer beastLightingFlag;
        private @Nullable String lightingMetadataError;
        private String lightmapTailStep = "";
        private String lightmapTailError;

        public UModel(UArchive archive) {
            super(archive);
        }

        public void deserialize() {
            super.deserialize();
            vectors = archive.readArray(Vec3.class, UArchive::readVector);
            points = archive.readArray(Vec3.class, UArchive::readVector);
            nodes = archive.readArray(UBspNode.class, UBspNode::new);
            surfaces = archive.readArray(UBspSurface.class, UBspSurface::new);
            vertices = archive.readArray(UVertex.class, UVertex::new);
            try {
                numSharedSides = archive.readInt();
                var zoneCount = archive.readInt();
                if (zoneCount < 0 || (long) zoneCount * MIN_ZONE_BYTES > archive.getRemainingInObject()) {
                    throw new IllegalStateException("Implausible BSP zone count: " + zoneCount);
                }
                zones = new FZoneProperties[zoneCount];
                for (int i = 0; i < zoneCount; i++) {
                    zones[i] = new FZoneProperties(archive);
                }
            } catch (Exception e) {
                zones = null;
                return;
            }
            try {
                readLightmapTail();
            } catch (Exception e) {
                lightmapTailError = lightmapTailStep + ": " + e;
                log.debug(
                        "Ignoring malformed BSP lightmap tail for {} (file ver {}, license ver {}): {}",
                        getFullName(),
                        archive.getHeader().getFileVersion(),
                        archive.getHeader().getLicenseVersion(),
                        lightmapTailError);
                bspSections = null;
                lightmaps = null;
                lightmapAtlases = null;
                lightmapAtlasVariants = null;
                return;
            }
            try {
                readLightingMetadata();
            } catch (Exception e) {
                lightingMetadataError = e.toString();
            }
        }

        public void retainLightmapAtlasVariants(Set<Integer> retainedAtlasIndices) {
            if (lightmapAtlasVariants == null || lightmapAtlases == null) {
                return;
            }
            for (var i = 0; i < lightmapAtlasVariants.length; i++) {
                if (!retainedAtlasIndices.contains(i)) {
                    var first = lightmapAtlases[i];
                    lightmapAtlasVariants[i] =
                            first == null ? new FStaticLightmapTexture[0] : new FStaticLightmapTexture[] {first};
                }
            }
        }

        public UMaterial resolveBspSectionMaterial(int sectionIndex) {
            if (bspSections == null || sectionIndex < 0 || sectionIndex >= bspSections.length) {
                return null;
            }
            var materialRef = bspSections[sectionIndex].materialRef();
            if (materialRef == 0) {
                return null;
            }
            var material = archive.getObjectLoader().loadObject(materialRef);
            return material instanceof UMaterial value ? value : null;
        }

        public String getLightmapTailError() {
            return lightmapTailError;
        }

        public FLightmapExtent @Nullable [] getLightmapExtents() {
            return lightmapExtents;
        }

        public @Nullable Integer getBeastLightingFlag() {
            return beastLightingFlag;
        }

        public @Nullable String getLightingMetadataError() {
            return lightingMetadataError;
        }

        public Vec3[] getVectors() {
            return this.vectors;
        }

        public Vec3[] getPoints() {
            return this.points;
        }

        public UBspNode[] getNodes() {
            return this.nodes;
        }

        public UBspSurface[] getSurfaces() {
            return this.surfaces;
        }

        public UVertex[] getVertices() {
            return this.vertices;
        }

        public UObjectRef<UPolys, Optional> getPolys() {
            return polys;
        }

        public int getNumSharedSides() {
            return this.numSharedSides;
        }

        public FZoneProperties[] getZones() {
            return this.zones;
        }

        public UBox[] getBounds() {
            return this.bounds;
        }

        public int @Nullable [] getLeafHulls() {
            return this.leafHulls;
        }

        public UBspLeaf[] getLeaves() {
            return this.leaves;
        }

        public @Nullable List<UObjectRef<LightActor, Optional>> getLightActors() {
            return this.lightActors;
        }

        public boolean isRootOutside() {
            return this.rootOutside;
        }

        public boolean isRootOutsideRead() {
            return this.rootOutsideRead;
        }

        public boolean isLinked() {
            return this.linked;
        }

        public FBspSection[] getBspSections() {
            return this.bspSections;
        }

        public FLightmapIndex[] getLightmaps() {
            return this.lightmaps;
        }

        public FStaticLightmapTexture[] getLightmapAtlases() {
            return this.lightmapAtlases;
        }

        public FStaticLightmapTexture[][] getLightmapAtlasVariants() {
            return this.lightmapAtlasVariants;
        }

        private void readLightingMetadata() {
            var version = archive.getHeader().getFileVersion();
            var count = 0;
            if (version >= 125) {
                if (archive.getRemainingInObject() < 1) {
                    throw new IllegalStateException("Missing BSP lightmap extents");
                }
                count = archive.readIndex();
                if (count < 0 || (long) count * 8 > archive.getRemainingInObject()) {
                    throw new IllegalStateException("Implausible BSP lightmap extent count: " + count);
                }
            }
            var extents = new FLightmapExtent[Math.max(count, lightmapAtlases.length)];
            for (var i = 0; i < count; i++) {
                extents[i] = new FLightmapExtent(archive.readInt(), archive.readInt());
            }
            for (var i = count; i < extents.length; i++) {
                extents[i] = new FLightmapExtent(512, 512);
            }
            var flag = (Integer) null;
            if (version >= 127) {
                if (archive.getRemainingInObject() < 4) {
                    throw new IllegalStateException("Missing BSP Beast lighting flag");
                }
                flag = archive.readInt();
            }
            lightmapExtents = extents;
            beastLightingFlag = flag;
        }

        private void readLightmapTail() {
            lightmapTailStep = "polys";
            polys.fromArchive(archive);
            lightmapTailStep = "bounds";
            var boundsCount = archive.readIndex();
            bounds = new UBox[boundsCount];
            for (int i = 0; i < boundsCount; i++) {
                bounds[i] = UBox.of(archive);
            }
            lightmapTailStep = "leafHulls after " + boundsCount + " bounds";
            var leafHullsCount = archive.readIndex();
            if (leafHullsCount < 0 || (long) leafHullsCount * 4 > archive.getRemainingInObject()) {
                throw new IllegalStateException("Implausible BSP leaf hull count: " + leafHullsCount);
            }
            leafHulls = new int[leafHullsCount];
            for (var i = 0; i < leafHullsCount; i++) {
                leafHulls[i] = archive.readInt();
            }
            lightmapTailStep = "leaves after " + leafHullsCount + " leaf hulls";
            var leavesCount = archive.readIndex();
            leaves = new UBspLeaf[leavesCount];
            for (int i = 0; i < leavesCount; i++) {
                leaves[i] = new UBspLeaf(
                        archive.readIndex(), archive.readIndex(), archive.readIndex(), archive.readUInt64());
            }
            lightmapTailStep = "lights after " + leavesCount + " leaves";
            var lightsCount = archive.readIndex();
            if (lightsCount < 0 || lightsCount > archive.getRemainingInObject()) {
                throw new IllegalStateException("Implausible BSP light count: " + lightsCount);
            }
            var references = new ArrayList<UObjectRef<LightActor, Optional>>(lightsCount);
            for (var i = 0; i < lightsCount; i++) {
                var reference = UObjectRef.<LightActor>optional();
                reference.fromArchive(archive);
                references.add(reference);
            }
            lightActors = List.copyOf(references);
            lightmapTailStep = "rootOutside/linked after " + lightsCount + " lights";
            rootOutside = archive.readInt() != 0;
            rootOutsideRead = true;
            linked = archive.readInt() != 0;
            lightmapTailStep = "sections";
            var bspSectionCount = archive.readIndex();
            bspSections = new FBspSection[bspSectionCount];
            for (int i = 0; i < bspSectionCount; i++) {
                lightmapTailStep = "section " + i + " of " + bspSectionCount;
                bspSections[i] = readBspSection();
            }
            lightmapTailStep = "lightmaps after " + bspSectionCount + " sections";
            var lightmapCount = archive.readIndex();
            lightmaps = new FLightmapIndex[lightmapCount];
            for (int i = 0; i < lightmapCount; i++) {
                lightmapTailStep = "lightmap " + i + " of " + lightmapCount;
                lightmaps[i] = readLightmapIndex();
            }
            lightmapTailStep = "atlases after " + lightmapCount + " lightmaps";
            var multiCount = archive.readIndex();
            lightmapAtlases = new FStaticLightmapTexture[multiCount];
            lightmapAtlasVariants = new FStaticLightmapTexture[multiCount][];
            for (int i = 0; i < multiCount; i++) {
                lightmapTailStep = "atlas " + i + " of " + multiCount;
                var variants = readMultiLightmapTexture();
                lightmapAtlasVariants[i] = variants;
                lightmapAtlases[i] = variants.length == 0 ? null : variants[0];
            }
        }

        private FBspSection readBspSection() {
            var vertexCount = archive.readIndex();
            var vertices = new FBspVertex[vertexCount];
            for (int i = 0; i < vertexCount; i++) {
                var position = archive.readVector();
                var u = archive.readFloat();
                var v = archive.readFloat();
                var u2 = archive.readFloat();
                var v2 = archive.readFloat();
                var normal = archive.getHeader().getFileVersion() > 0x6c ? archive.readVector() : new Vec3();
                vertices[i] = new FBspVertex(position, normal, u, v, u2, v2);
            }
            var revision = archive.readInt();
            var materialRef = archive.readIndex();
            var numNodes = archive.readInt();
            var polygonFlags = archive.readInt();
            var iLightmapTexture = archive.readInt();
            return new FBspSection(vertices, revision, materialRef, numNodes, polygonFlags, iLightmapTexture);
        }

        private FLightmapIndex readLightmapIndex() {
            var iLightmapTexture = archive.readIndex();
            archive.readIndex();
            archive.readIndex();
            var offsetX = archive.readIndex();
            var offsetY = archive.readIndex();
            var sizeX = archive.readIndex();
            var sizeY = archive.readIndex();
            var uvMatrix = new float[16];
            for (int i = 0; i < 16; i++) {
                uvMatrix[i] = archive.readFloat();
            }
            archive.skipBytes(36);
            var subCount = archive.readIndex();
            for (int i = 0; i < subCount; i++) {
                readSubStructure();
            }
            archive.readIndex();
            archive.readInt();
            return new FLightmapIndex(iLightmapTexture, offsetX, offsetY, sizeX, sizeY, uvMatrix);
        }

        private void readSubStructure() {
            archive.readIndex();
            var bitmapCount = archive.readIndex();
            archive.skipBytes(bitmapCount);
            archive.skipBytes(28);
        }

        private FStaticLightmapTexture[] readMultiLightmapTexture() {
            var texCount = archive.readIndex();
            var variants = new FStaticLightmapTexture[texCount];
            for (int i = 0; i < texCount; i++) {
                variants[i] = readLightmapTexture();
            }
            var iLightmapsCount = archive.readIndex();
            archive.skipBytes(iLightmapsCount * 4);
            if (archive.getHeader().getFileVersion() > MULTI_LIGHTMAP_LEVEL_REF_VERSION) {
                archive.readIndex();
            }
            return variants;
        }

        private FStaticLightmapTexture readLightmapTexture() {
            archive.readIndex();
            var iLightmapsCount = archive.readIndex();
            archive.skipBytes(iLightmapsCount * 4);
            archive.skipBytes(8);
            archive.skipBytes(4);
            return readStaticLightmap();
        }

        private FStaticLightmapTexture readStaticLightmap() {
            var start = archive.getOffset();
            archive.readInt();
            var data = archive.readByteArray();
            archive.readInt();
            var halfCount = archive.readIndex();
            if (halfCount < 0 || halfCount > archive.getRemainingInObject()) {
                throw new InvalidLightmapException("offset="
                        + start
                        + ", invalid half-res byte count="
                        + halfCount
                        + ", remaining="
                        + archive.getRemainingInObject());
            }
            archive.skipBytes(halfCount);
            var format = archive.readUInt8();
            var width = archive.readInt();
            var height = archive.readInt();
            archive.readInt();
            var empty = width == 0 && height == 0 && data.length == 0 && halfCount == 0;
            if (!empty && (width <= 0 || height <= 0 || width > MAX_LIGHTMAP_EXTENT || height > MAX_LIGHTMAP_EXTENT)) {
                throw new InvalidLightmapException("offset="
                        + start
                        + ", format="
                        + format
                        + ", dimensions="
                        + width
                        + "x"
                        + height
                        + ", dataBytes="
                        + data.length
                        + ", halfBytes="
                        + halfCount);
            }
            return new FStaticLightmapTexture(format, width, height, data);
        }

        private static final class InvalidLightmapException extends IllegalStateException {
            private InvalidLightmapException(String message) {
                super(message);
            }
        }
    }

    public record FBspVertex(Vec3 position, Vec3 normal, float u, float v, float u2, float v2) {}

    public record FBspSection(
            FBspVertex[] vertices,
            int revision,
            int materialRef,
            int numNodes,
            int polygonFlags,
            int iLightmapTexture) {
        public int vertexCount() {
            return vertices.length;
        }
    }

    public record FLightmapIndex(
            int iLightmapTexture, int offsetX, int offsetY, int sizeX, int sizeY, float[] uvMatrix) {}

    public record FStaticLightmapTexture(int format, int width, int height, byte[] data) {
        public boolean isEmpty() {
            return width == 0 && height == 0 && data.length == 0;
        }
    }

    public record FLightmapExtent(int width, int height) {}

    public static class FZoneProperties {
        private final UObjectRef<ZoneInfoActor, Optional> zoneActor = UObjectRef.optional();
        private final long connectivity;
        private final long visibility;
        private final float lastRenderTime;

        public FZoneProperties(UArchive archive) {
            zoneActor.fromArchive(archive);
            connectivity = archive.readUInt64();
            visibility = archive.readUInt64();
            lastRenderTime = archive.readFloat();
        }

        public UObjectRef<ZoneInfoActor, Optional> getZoneActor() {
            return this.zoneActor;
        }

        public long getConnectivity() {
            return this.connectivity;
        }

        public long getVisibility() {
            return this.visibility;
        }

        public float getLastRenderTime() {
            return this.lastRenderTime;
        }
    }
}
