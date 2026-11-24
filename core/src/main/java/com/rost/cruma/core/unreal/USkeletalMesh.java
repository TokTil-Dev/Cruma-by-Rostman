package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UPrimitive;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.quat.Quat;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.function.IntFunction;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.jetbrains.annotations.Nullable;

public final class USkeletalMesh extends UPrimitive {
    private static final double HEAD_REACH = 24;

    private int version;
    private final List<UObjectRef<UMaterial, Optional>> textures = new ArrayList<>();
    private Vec3 meshScale = new Vec3(1f);
    private Vec3 meshOrigin = new Vec3();
    private URotator rotationOrigin = new URotator(0, 0, 0);
    private Face[] faces = new Face[0];
    private Wedge[] wedges = new Wedge[0];
    private Material[] materials = new Material[0];
    private Vec3[] points = new Vec3[0];
    private Bone[] bones = new Bone[0];
    private int animationIndex;
    private Influence[] influences = new Influence[0];
    private int[] lodSections = new int[0];
    private int[] sectionMaterials = new int[0];
    private int[] faceSections = new int[0];

    private int lazyEnd;
    private List<Socket> sockets = List.of();
    private boolean skinningStream;

    public USkeletalMesh(UArchive archive) {
        super(archive);
    }

    public record Face(int wedge0, int wedge1, int wedge2, int materialIndex) {}

    public record Wedge(int vertexIndex, Vec2 uv) {}

    public record Material(long polygonFlags, int textureIndex) {}

    public record Bone(String name, float[] orientation, Vec3 position, int childCount, int parentIndex) {}

    public record Socket(String alias, String bone, Vec3 origin, Vec3 x, Vec3 y, Vec3 z) {}

    public record Influence(int point, int bone, float weight) {}

    static @Nullable Restored restoreLineage(
            LineageWedge[] lineage, List<int[]> sections, List<int[]> boneMaps, int[] indices) {
        var wedgeSection = new int[lineage.length];
        Arrays.fill(wedgeSection, -1);
        var faceList = new ArrayList<Face>();
        for (var s = 0; s < sections.size(); s++) {
            var section = sections.get(s);
            for (var face = section[7]; face < section[7] + section[8]; face++) {
                var at = face * 3;
                if (at + 2 >= indices.length) {
                    return null;
                }
                for (var k = 0; k < 3; k++) {
                    if (indices[at + k] >= lineage.length) {
                        return null;
                    }
                    wedgeSection[indices[at + k]] = s;
                }
                faceList.add(new Face(indices[at], indices[at + 1], indices[at + 2], section[0]));
            }
        }
        var pointIndex = new HashMap<List<Float>, Integer>();
        var pointList = new ArrayList<Vec3>();
        var influenceList = new ArrayList<Influence>();
        var wedgeList = new Wedge[lineage.length];
        for (var i = 0; i < lineage.length; i++) {
            var wedge = lineage[i];
            var key = List.of(
                    wedge.point().getX(),
                    wedge.point().getY(),
                    wedge.point().getZ(),
                    wedge.normal().getX(),
                    wedge.normal().getY(),
                    wedge.normal().getZ());
            var point = pointIndex.get(key);
            if (point == null) {
                point = pointList.size();
                pointIndex.put(key, point);
                pointList.add(wedge.point());
                var section = wedgeSection[i];
                var boneMap = section >= 0 && section < boneMaps.size() ? boneMaps.get(section) : new int[0];
                for (var k = 0; k < 4; k++) {
                    if (wedge.bones()[k] == 255 || wedge.weights()[k] < 1e-6f || wedge.bones()[k] >= boneMap.length) {
                        continue;
                    }
                    influenceList.add(new Influence(point, boneMap[wedge.bones()[k]], wedge.weights()[k]));
                }
            }
            wedgeList[i] = new Wedge(point, wedge.uv());
        }
        return new Restored(influenceList, wedgeList, faceList.toArray(Face[]::new), pointList.toArray(Vec3[]::new));
    }

    static Skinning decodeSkinning(int[] data, int pointCount) {
        var influences = new ArrayList<Influence>();
        var wedges = new ArrayList<Wedge>();
        var nextPoint = 0;
        var i = 0;
        while (i < data.length) {
            var head = data[i];
            if (head == 0xFFFFFFFF) {
                break;
            }
            if (head >>> 28 == 0xF) {
                if (i + 2 >= data.length) {
                    break;
                }
                var point = Math.max(0, nextPoint - 1);
                wedges.add(new Wedge(
                        Math.min(point, pointCount - 1),
                        new Vec2(Float.intBitsToFloat(data[i + 1]), Float.intBitsToFloat(data[i + 2]))));
                i += 3;
                continue;
            }
            var count = ((head >>> 28) & 7) + 1;
            if (i + count + 1 >= data.length || nextPoint >= pointCount) {
                break;
            }
            for (var k = 0; k < count; k++) {
                var value = data[i + k];
                influences.add(new Influence(nextPoint, (value & 0xFFF) / 6, ((value >>> 16) & 0xFFF) / 4095f));
            }
            wedges.add(new Wedge(
                    nextPoint,
                    new Vec2(Float.intBitsToFloat(data[i + count]), Float.intBitsToFloat(data[i + count + 1]))));
            nextPoint++;
            i += count + 2;
        }
        return new Skinning(influences, wedges.toArray(Wedge[]::new));
    }

    static Rigid rigidGeometry(
            List<int[]> sections,
            int[] indices,
            float[] vertices,
            int boneCount,
            int head,
            double @Nullable [] headAt) {
        var count = vertices.length / 8;
        var pointList = new Vec3[count];
        var wedgeList = new Wedge[count];
        for (var i = 0; i < count; i++) {
            pointList[i] = new Vec3(vertices[i * 8], vertices[i * 8 + 1], vertices[i * 8 + 2]);
            wedgeList[i] = new Wedge(i, new Vec2(vertices[i * 8 + 6], vertices[i * 8 + 7]));
        }
        var influenceList = new ArrayList<Influence>(count);
        for (var section : sections) {
            var bone = section[5] < boneCount ? section[5] : 0;
            var first = section[2];
            var last = Math.min(count - 1, section[3]);
            if (bone == 0 && headAt != null && last >= first) {
                double x = 0;
                double y = 0;
                double z = 0;
                for (var i = first; i <= last; i++) {
                    x += pointList[i].getX();
                    y += pointList[i].getY();
                    z += pointList[i].getZ();
                }
                var n = last - first + 1;
                if (Math.hypot(Math.hypot(x / n - headAt[4], y / n - headAt[5]), z / n - headAt[6]) <= HEAD_REACH) {
                    bone = head;
                }
            }
            for (var i = first; i <= last; i++) {
                influenceList.add(new Influence(i, bone, 1f));
            }
        }
        return new Rigid(
                pointList, wedgeList, facesFromSections(sections, indices), influenceList.toArray(Influence[]::new));
    }

    static Face[] facesFromSections(List<int[]> sections, int[] indices) {
        var faces = new ArrayList<Face>();
        for (var section : sections) {
            var material = section[0];
            var firstFace = section[7];
            var count = section[8];
            for (var face = firstFace; face < firstFace + count; face++) {
                var at = face * 3;
                if (at + 2 >= indices.length) {
                    break;
                }
                faces.add(new Face(indices[at], indices[at + 1], indices[at + 2], material));
            }
        }
        return faces.toArray(Face[]::new);
    }

    static Sections sectionsOf(Face[] faces, int[] lodSections) {
        var materials = new IntArrayList(lodSections.length);
        var bySection = new IntIntHashMap();
        for (var materialIndex : lodSections) {
            if (!bySection.containsKey(materialIndex)) {
                bySection.put(materialIndex, materials.size());
            }
            materials.add(materialIndex);
        }
        var extra = new TreeSet<Integer>();
        for (var face : faces) {
            if (!bySection.containsKey(face.materialIndex())) {
                extra.add(face.materialIndex());
            }
        }
        for (var materialIndex : extra) {
            bySection.put(materialIndex, materials.size());
            materials.add(materialIndex);
        }
        var faceSections = new int[faces.length];
        for (var i = 0; i < faces.length; i++) {
            faceSections[i] = bySection.get(faces[i].materialIndex());
        }
        return new Sections(materials.toArray(), faceSections);
    }

    public static <T> List<@Nullable T> pickSectionMaterials(
            int[] sectionMaterials, List<? extends @Nullable T> listed, IntFunction<? extends @Nullable T> textures) {
        var result = new ArrayList<@Nullable T>(sectionMaterials.length);
        for (var section = 0; section < sectionMaterials.length; section++) {
            T material = section < listed.size() ? listed.get(section) : null;
            result.add(material != null ? material : textures.apply(sectionMaterials[section]));
        }
        return result;
    }

    public static <T> List<@Nullable T> pickClassicSectionMaterials(
            int[] sectionMaterials,
            int materialCount,
            List<? extends @Nullable T> listed,
            IntFunction<? extends @Nullable T> materialTextures) {
        var result = new ArrayList<@Nullable T>(sectionMaterials.length);
        for (var section = 0; section < sectionMaterials.length; section++) {
            var material = Math.clamp(sectionMaterials[section], 0, Math.max(materialCount, 1) - 1);
            T picked = material < listed.size() ? listed.get(material) : null;
            result.add(picked != null || material >= materialCount ? picked : materialTextures.apply(material));
        }
        return result;
    }

    @Override
    public void deserialize() {
        super.deserialize();
        version = archive.readInt();
        archive.readInt();
        var fileVersion = archive.getHeader().getFileVersion();
        skipArray("vertices", fileVersion >= 133 ? 12 : 4);
        if (version <= 1) {
            throw malformedException("legacy version " + version + " is not supported");
        }
        var textureCount = archive.readIndex();
        requireCount("textures", textureCount);
        for (var i = 0; i < textureCount; i++) {
            var ref = UObjectRef.<UMaterial>optional();
            ref.fromArchive(archive);
            textures.add(ref);
        }
        meshScale = archive.readVector();
        meshOrigin = archive.readVector();
        rotationOrigin = URotator.of(archive);
        archive.readUInt16Array();
        faces = archive.readArray(
                Face.class, in -> new Face(in.readUInt16(), in.readUInt16(), in.readUInt16(), in.readUInt16()));
        archive.readUInt16Array();
        wedges = archive.readArray(
                Wedge.class, in -> new Wedge(in.readUInt16(), new Vec2(in.readFloat(), in.readFloat())));
        materials = archive.readArray(Material.class, in -> new Material(in.readUInt32(), in.readInt()));
        archive.skipBytes(24);
        if (version >= 3) {
            archive.readInt();
            archive.readIndex();
            archive.skipBytes(12 + 12 + 12 + 4 + 12);
        }
        if (version >= 4) {
            archive.readFloat();
        }
        if (version >= 5) {
            archive.readInt();
        }
        if (version >= 7) {
            archive.skipBytes(2);
        }

        points = archive.readArray(Vec3.class, UArchive::readVector);
        bones = archive.readArray(Bone.class, USkeletalMesh::readBone);
        animationIndex = archive.readIndex();
        archive.readInt();
        var groupCount = archive.readIndex();
        requireCount("weight groups", groupCount);
        var groups = new int[groupCount][];
        var starts = new int[groupCount];
        for (var group = 0; group < groupCount; group++) {
            groups[group] = archive.readUInt16Array();
            starts[group] = archive.readInt();
        }
        var boneInfluenceCount = archive.readIndex();
        requireCount("bone influences", boneInfluenceCount);
        var weights = new int[boneInfluenceCount];
        var boneIndices = new int[boneInfluenceCount];
        for (var i = 0; i < boneInfluenceCount; i++) {
            weights[i] = archive.readUInt16();
            boneIndices[i] = archive.readUInt16();
        }
        var resolved = new ArrayList<Influence>();
        for (var group = 0; group < groupCount; group++) {
            var perPoint = group + 1;
            var cursor = starts[group];
            for (var point : groups[group]) {
                for (var k = 0; k < perPoint && cursor < boneInfluenceCount; k++, cursor++) {
                    resolved.add(new Influence(point, boneIndices[cursor], weights[cursor] / 65535f));
                }
            }
        }
        influences = resolved.toArray(Influence[]::new);
        var aliases = archive.readList(UArchive::readName);
        var boneNames = archive.readList(UArchive::readName);
        var coordinateCount = archive.readIndex();
        requireCount("attachment coordinates", coordinateCount);
        var socketList = new ArrayList<Socket>();
        for (var i = 0; i < coordinateCount; i++) {
            var origin = archive.readVector();
            var x = archive.readVector();
            var y = archive.readVector();
            var z = archive.readVector();
            if (i < aliases.size() && i < boneNames.size()) {
                socketList.add(
                        new Socket(String.valueOf(aliases.get(i)), String.valueOf(boneNames.get(i)), origin, x, y, z));
            }
        }
        sockets = List.copyOf(socketList);
        if (version >= 2) {
            readFirstLodModel();
        }
        var sections = sectionsOf(faces, lodSections);
        sectionMaterials = sections.materials();
        faceSections = sections.faces();
        validate();
    }

    public boolean usesSkinningStream() {
        return skinningStream;
    }

    public List<Socket> getSockets() {
        return sockets;
    }

    public int getVersion() {
        return version;
    }

    public Face[] getFaces() {
        return faces;
    }

    public Wedge[] getWedges() {
        return wedges;
    }

    public Material[] getMaterials() {
        return materials;
    }

    public Vec3[] getPoints() {
        return points;
    }

    public Bone[] getBones() {
        return bones;
    }

    public Influence[] getInfluences() {
        return influences;
    }

    public Vec3 getMeshScale() {
        return meshScale;
    }

    public Vec3 getMeshOrigin() {
        return meshOrigin;
    }

    public URotator getRotationOrigin() {
        return rotationOrigin;
    }

    public int getAnimationIndex() {
        return animationIndex;
    }

    public @Nullable UMeshAnimation loadOwnAnimation() {
        var index = getAnimationIndex();
        return index != 0 && archive.getObjectLoader().loadObject(index) instanceof UMeshAnimation animation
                ? animation
                : null;
    }

    public Vec3 convertMeshToLocal(Vec3 point) {
        return UVertMesh.meshSpacePosition(point, meshScale, meshOrigin, rotationOrigin);
    }

    public Vec3 convertAttachedToLocal(Vec3 point) {
        var scaled = new Vec3(
                point.getX() * meshScale.getX(), point.getY() * meshScale.getY(), point.getZ() * meshScale.getZ());
        return new Quat(rotationOrigin.vector()).times(scaled).plus(meshOrigin);
    }

    public int[] getSectionMaterials() {
        return sectionMaterials;
    }

    public int[] getFaceSections() {
        return faceSections;
    }

    public @Nullable UMaterial getTexture(int index) {
        return index >= 0 && index < textures.size() ? textures.get(index).get() : null;
    }

    public @Nullable UMaterial getMaterialTexture(int index) {
        return index >= 0 && index < materials.length ? getTexture(materials[index].textureIndex()) : null;
    }

    public @Nullable UMaterial getSectionTexture(int section) {
        return section >= 0 && section < sectionMaterials.length ? getTexture(sectionMaterials[section]) : null;
    }

    public long getSectionPolygonFlags(int section) {
        if (materials.length == 0 || section < 0 || section >= sectionMaterials.length) {
            return 0;
        }
        return materials[Math.clamp(sectionMaterials[section], 0, materials.length - 1)].polygonFlags();
    }

    public int getTextureCount() {
        return textures.size();
    }

    private void readFirstLodModel() {
        var licensee = archive.getHeader().getLicenseVersion();
        var lodCount = archive.readIndex();
        requireCount("LOD models", lodCount);
        if (lodCount == 0) {
            return;
        }
        var skinningCount = archive.readIndex();
        requireCount("skinning data", skinningCount);
        var skinningData = new int[skinningCount];
        for (var i = 0; i < skinningCount; i++) {
            skinningData[i] = archive.readInt();
        }
        skipArray("skin points", 16);
        archive.readInt();
        var smoothSections = new ArrayList<int[]>();
        var rigidSections = new ArrayList<int[]>();
        var smoothBoneMaps = new ArrayList<int[]>();
        var rigidBoneMaps = new ArrayList<int[]>();
        for (var list = 0; list < 2; list++) {
            var sections = archive.readIndex();
            requireCount("sections", sections);
            for (var section = 0; section < sections; section++) {
                var words = new int[9];
                for (var word = 0; word < 9; word++) {
                    words[word] = archive.readUInt16();
                }
                if (list == 0) {
                    smoothSections.add(words);
                } else {
                    rigidSections.add(words);
                }
                if (licensee > 27) {
                    var boneCount = archive.readIndex();
                    requireCount("section bones", boneCount);
                    var boneMap = new int[boneCount];
                    for (var bone = 0; bone < boneCount; bone++) {
                        boneMap[bone] = archive.readInt();
                    }
                    if (list == 0) {
                        smoothBoneMaps.add(boneMap);
                    } else {
                        rigidBoneMaps.add(boneMap);
                    }
                }
            }
        }
        var drawn = smoothSections.isEmpty() ? rigidSections : smoothSections;
        lodSections = new int[drawn.size()];
        for (var i = 0; i < lodSections.length; i++) {
            lodSections[i] = drawn.get(i)[0];
        }
        var smoothIndices = new int[0];
        var rigidIndices = new int[0];
        for (var buffer = 0; buffer < 2; buffer++) {
            var count = archive.readIndex();
            requireCount("indices", count);
            var read = new int[count];
            for (var i = 0; i < count; i++) {
                read[i] = archive.readUInt16();
            }
            if (buffer == 0) {
                smoothIndices = read;
            } else {
                rigidIndices = read;
            }
            archive.readInt();
        }
        archive.skipBytes(12);
        var rigidCount = archive.readIndex();
        requireCount("skin vertex stream", rigidCount);
        var rigidVertices = new float[Math.multiplyExact(rigidCount, 8)];
        for (var i = 0; i < rigidVertices.length; i++) {
            rigidVertices[i] = archive.readFloat();
        }
        var lodInfluences = lazyArray("LOD influences", 8);
        var influenceList = new ArrayList<Influence>(lodInfluences.length);
        for (var offset : lodInfluences) {
            archive.setPosition(offset);
            var weight = archive.readFloat();
            var point = archive.readUInt16();
            var bone = archive.readUInt16();
            influenceList.add(new Influence(point, bone, weight));
        }
        archive.setPosition(lazyEnd);
        var lodWedges = lazyArray("LOD wedges", 10);
        var wedgeList = new Wedge[lodWedges.length];
        for (var i = 0; i < lodWedges.length; i++) {
            archive.setPosition(lodWedges[i]);
            wedgeList[i] = new Wedge(archive.readUInt16(), new Vec2(archive.readFloat(), archive.readFloat()));
        }
        archive.setPosition(lazyEnd);
        var lodFaces = lazyArray("LOD faces", 8);
        var faceList = new Face[lodFaces.length];
        for (var i = 0; i < lodFaces.length; i++) {
            archive.setPosition(lodFaces[i]);
            faceList[i] =
                    new Face(archive.readUInt16(), archive.readUInt16(), archive.readUInt16(), archive.readUInt16());
        }
        archive.setPosition(lazyEnd);
        var lodPoints = lazyArray("LOD points", 12);
        var pointList = new Vec3[lodPoints.length];
        for (var i = 0; i < lodPoints.length; i++) {
            archive.setPosition(lodPoints[i]);
            pointList[i] = archive.readVector();
        }
        archive.setPosition(lazyEnd);
        var lineageWedges = licensee > 27 ? readLineageWedges() : new LineageWedge[0];
        if (faceList.length == 0 && wedgeList.length == 0 && lineageWedges.length > 0) {
            var restored = restoreLineage(lineageWedges, smoothSections, smoothBoneMaps, smoothIndices);
            if (restored != null) {
                influences = restored.influences().toArray(Influence[]::new);
                wedges = restored.wedges();
                faces = restored.faces();
                points = restored.points();
                return;
            }
        }
        if (faceList.length == 0
                && wedgeList.length == 0
                && lineageWedges.length == 0
                && skinningCount == 0
                && !rigidSections.isEmpty()
                && rigidCount > 0) {
            addRigidGeometry(rigidSections, rigidIndices, rigidVertices);
            return;
        }
        if (faceList.length == 0 && wedgeList.length == 0 && skinningCount > 0 && pointList.length > 0) {
            skinningStream = true;
            var skinned = decodeSkinning(skinningData, pointList.length);
            influenceList = skinned.influences();
            wedgeList = skinned.wedges();
            faceList = facesFromSections(smoothSections, smoothIndices);
        }
        if (faceList.length == 0 || wedgeList.length == 0 || pointList.length == 0) {
            return;
        }
        influences = influenceList.toArray(Influence[]::new);
        wedges = wedgeList;
        faces = faceList;
        points = pointList;
    }

    record Skinning(ArrayList<Influence> influences, Wedge[] wedges) {}

    record LineageWedge(Vec3 point, Vec3 normal, Vec2 uv, int[] bones, float[] weights) {}

    record Restored(List<Influence> influences, Wedge[] wedges, Face[] faces, Vec3[] points) {}

    private LineageWedge[] readLineageWedges() {
        var start = archive.getPosition();
        try {
            archive.skipBytes(4 * 6);
            archive.readInt();
            var count = archive.readIndex();
            requireCount("Lineage wedges", count);
            var result = new LineageWedge[count];
            for (var i = 0; i < count; i++) {
                var point = archive.readVector();
                var normal = archive.readVector();
                var uv = new Vec2(archive.readFloat(), archive.readFloat());
                var bones = new int[4];
                for (var k = 0; k < 4; k++) {
                    bones[k] = archive.readByte() & 0xff;
                }
                var weights = new float[4];
                for (var k = 0; k < 4; k++) {
                    weights[k] = archive.readFloat();
                }
                result[i] = new LineageWedge(point, normal, uv, bones, weights);
            }
            return result;
        } catch (RuntimeException unreadable) {
            archive.setPosition(start);
            return new LineageWedge[0];
        }
    }

    private void addRigidGeometry(List<int[]> sections, int[] indices, float[] vertices) {
        var head = -1;
        for (var i = 0; i < bones.length; i++) {
            if (bones[i].name().replace('_', ' ').equalsIgnoreCase("Bip01 Head")) {
                head = i;
            }
        }
        var headAt = head < 0 ? null : SkeletalPose.findPosedBone(this, null, null, 0, bones[head].name());
        var geometry = rigidGeometry(sections, indices, vertices, bones.length, head, headAt);
        points = geometry.points();
        wedges = geometry.wedges();
        faces = geometry.faces();
        influences = geometry.influences();
    }

    record Rigid(Vec3[] points, Wedge[] wedges, Face[] faces, Influence[] influences) {}

    record Sections(int[] materials, int[] faces) {}

    private int[] lazyArray(String label, int elementSize) {
        var end = archive.readInt();
        var count = archive.readIndex();
        requireCount(label, count);
        var start = archive.getOffset();
        if (end != start + count * elementSize) {
            throw malformedException(label
                    + " end "
                    + end
                    + " does not match "
                    + count
                    + " elements of "
                    + elementSize
                    + " bytes from "
                    + start);
        }
        var offsets = new int[count];
        for (var i = 0; i < count; i++) {
            offsets[i] = start + i * elementSize;
        }
        lazyEnd = end;
        return offsets;
    }

    private void validate() {
        for (var face : faces) {
            if (face.wedge0() >= wedges.length || face.wedge1() >= wedges.length || face.wedge2() >= wedges.length) {
                throw malformedException("face refers past " + wedges.length + " wedges");
            }
        }
        for (var wedge : wedges) {
            if (wedge.vertexIndex() >= points.length) {
                throw malformedException("wedge refers past " + points.length + " points");
            }
        }
        for (var influence : influences) {
            if (influence.point() >= points.length || influence.bone() >= bones.length) {
                throw malformedException("influence " + influence + " is out of range");
            }
        }
    }

    private void skipArray(String label, int elementSize) {
        var count = archive.readIndex();
        requireCount(label, count);
        archive.skipBytes(Math.multiplyExact(count, elementSize));
    }

    private void requireCount(String label, int count) {
        if (count < 0 || count > 4_000_000) {
            throw malformedException("invalid " + label + " count " + count);
        }
    }

    private IllegalStateException malformedException(String detail) {
        return new IllegalStateException("Malformed SkeletalMesh " + getName() + ": " + detail);
    }

    private static Bone readBone(UArchive archive) {
        var name = archive.readName();
        archive.readInt();
        var orientation =
                new float[] {archive.readFloat(), archive.readFloat(), archive.readFloat(), archive.readFloat()};
        var position = archive.readVector();
        archive.skipBytes(16);
        var children = archive.readInt();
        var parent = archive.readInt();
        return new Bone(name, orientation, position, children, parent);
    }
}
