package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UPrimitive;
import com.rost.cruma.core.unreal.UMeshAnimSequences.Checks;
import com.rost.cruma.core.unreal.UObjectRef.Optional;
import com.rost.cruma.core.unreal.primitives.URotator;
import glm_.quat.Quat;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class UVertMesh extends UPrimitive {
    private int version;
    private int vertexCount;
    private Vec3[] positions = new Vec3[0];
    private final List<UObjectRef<UMaterial, Optional>> textures = new ArrayList<>();
    private Vec3 meshScale = new Vec3(1f);
    private Vec3 meshOrigin = new Vec3();
    private URotator rotationOrigin = new URotator(0, 0, 0);
    private int[] faceLevels = new int[0];
    private Face[] faces = new Face[0];
    private int[] collapseWedges = new int[0];
    private Wedge[] wedges = new Wedge[0];
    private Material[] materials = new Material[0];
    private int frameCount;
    private AnimationSequence[] animationSequences = new AnimationSequence[0];

    public UVertMesh(UArchive archive) {
        super(archive);
    }

    public record PackedVertex(int x, int y, int z) {
        private static PackedVertex read(UArchive archive) {
            var packed = (int) archive.readUInt32();
            return new PackedVertex((packed << 21) >> 21, (packed << 10) >> 21, packed >> 22);
        }
    }

    public record Face(int wedge0, int wedge1, int wedge2, int materialIndex) {
        private static Face read(UArchive archive) {
            return new Face(archive.readUInt16(), archive.readUInt16(), archive.readUInt16(), archive.readUInt16());
        }
    }

    public record Wedge(int vertexIndex, Vec2 uv) {
        private static Wedge read(UArchive archive) {
            return new Wedge(archive.readUInt16(), new Vec2(archive.readFloat(), archive.readFloat()));
        }
    }

    public record Material(long polygonFlags, int textureIndex) {
        private static Material read(UArchive archive) {
            return new Material(archive.readUInt32(), archive.readInt());
        }
    }

    public record AnimationSequence(String name, int startFrame, int frameCount, float rate) {}

    static Vec3 meshSpacePosition(Vec3 raw, Vec3 scale, Vec3 origin, URotator rotationOrigin) {
        var centeredAndScaled = new Vec3(
                (raw.getX() - origin.getX()) * scale.getX(),
                (raw.getY() - origin.getY()) * scale.getY(),
                (raw.getZ() - origin.getZ()) * scale.getZ());
        return new Quat(rotationOrigin.vector()).times(centeredAndScaled);
    }

    @Override
    public void deserialize() {
        super.deserialize();
        version = archive.readInt();
        vertexCount = archive.readInt();
        if (version < 1 || vertexCount <= 0) {
            throw malformedException("invalid version/vertex count " + version + "/" + vertexCount);
        }

        if (archive.getHeader().getFileVersion() >= 133) {
            positions = archive.readArray(Vec3.class, UArchive::readVector);
        } else {
            var packed = archive.readArray(PackedVertex.class, PackedVertex::read);
            positions = new Vec3[packed.length];
            for (var i = 0; i < packed.length; i++) {
                positions[i] = new Vec3(packed[i].x(), packed[i].y(), packed[i].z());
            }
        }
        if (positions.length == 0 || positions.length % vertexCount != 0) {
            throw malformedException(
                    "vertex count " + positions.length + " is not a positive multiple of " + vertexCount);
        }
        frameCount = positions.length / vertexCount;

        if (version <= 1) {
            throw malformedException("legacy version " + version + " is not supported");
        }
        if (version == 6 || version > 7) {
            throw malformedException("version " + version + " is not supported");
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
        faceLevels = readUInt16Array("face levels");
        faces = archive.readArray(Face.class, Face::read);
        collapseWedges = readUInt16Array("collapse wedges");
        wedges = archive.readArray(Wedge.class, Wedge::read);
        materials = archive.readArray(Material.class, Material::read);
        validateTopology();

        readNativeTail();
        if (archive.getObjectEnd() >= 0 && archive.getRemainingInObject() != 0) {
            throw malformedException(archive.getRemainingInObject() + " bytes remain after the bounding spheres");
        }
    }

    public Vec3 getPosition(int frame, int vertex) {
        if (frame < 0 || frame >= frameCount || vertex < 0 || vertex >= vertexCount) {
            throw new IndexOutOfBoundsException("frame=" + frame + ", vertex=" + vertex);
        }
        return meshSpacePosition(positions[frame * vertexCount + vertex], meshScale, meshOrigin, rotationOrigin);
    }

    public UMaterial getTexture(int materialIndex) {
        if (materialIndex < 0 || materialIndex >= materials.length) {
            return null;
        }
        var textureIndex = materials[materialIndex].textureIndex();
        return textureIndex >= 0 && textureIndex < textures.size()
                ? textures.get(textureIndex).get()
                : null;
    }

    public AnimationSequence findAnimationSequence(String name) {
        if (name == null) {
            return null;
        }
        for (var sequence : animationSequences) {
            if (sequence.name().equalsIgnoreCase(name)) {
                return sequence;
            }
        }
        return null;
    }

    public int getVersion() {
        return this.version;
    }

    public int getVertexCount() {
        return this.vertexCount;
    }

    public Vec3[] getPositions() {
        return this.positions;
    }

    public List<UObjectRef<UMaterial, Optional>> getTextures() {
        return this.textures;
    }

    public Vec3 getMeshScale() {
        return this.meshScale;
    }

    public Vec3 getMeshOrigin() {
        return this.meshOrigin;
    }

    public URotator getRotationOrigin() {
        return this.rotationOrigin;
    }

    public int[] getFaceLevels() {
        return this.faceLevels;
    }

    public Face[] getFaces() {
        return this.faces;
    }

    public int[] getCollapseWedges() {
        return this.collapseWedges;
    }

    public Wedge[] getWedges() {
        return this.wedges;
    }

    public Material[] getMaterials() {
        return this.materials;
    }

    public int getFrameCount() {
        return this.frameCount;
    }

    public AnimationSequence[] getAnimationSequences() {
        return this.animationSequences;
    }

    private void readNativeTail() {
        var fileVersion = archive.getHeader().getFileVersion();
        requireRemaining("LOD controls", 24);
        archive.readFloat();
        archive.readFloat();
        archive.readFloat();
        archive.readInt();
        archive.readFloat();
        archive.readFloat();

        if (version >= 3) {
            requireRemaining("impostor presence", 4);
            archive.readInt();
            archive.readIndex();
            requireRemaining("impostor properties", 12 + 12 + 12 + 4 + 12);
            archive.readVector();
            archive.readInt();
            archive.readInt();
            archive.readInt();
            archive.readVector();
            archive.skipBytes(4);
            archive.readInt();
            archive.readInt();
            archive.readInt();
        }
        if (version >= 4) {
            requireRemaining("skin tessellation", 4);
            archive.readFloat();
        }
        if (version >= 5) {
            requireRemaining("Lineage LOD v5 field", 4);
            archive.readInt();
        }
        if (version >= 7) {
            requireRemaining("version 7 LOD fields", 2);
            archive.skipBytes(2);
        }

        skipArray("animation vertex stream", 32);
        requireRemaining("animation vertex revision", 4);
        archive.readInt();

        skipArray("animation vertices", fileVersion >= 133 ? 12 : 4);
        skipArray("frame keys", 4);
        readAnimationSequences();

        skipArray("normals", 4);
        requireRemaining("vertex and frame counts", 8);
        var storedVertexCount = archive.readInt();
        var storedFrameCount = archive.readInt();
        if (storedVertexCount != vertexCount || storedFrameCount != frameCount) {
            throw malformedException("stored vertex/frame counts "
                    + storedVertexCount
                    + "/"
                    + storedFrameCount
                    + " differ from "
                    + vertexCount
                    + "/"
                    + frameCount);
        }
        skipArray("bounding boxes", 25);
        skipArray("bounding spheres", fileVersion < 62 ? 12 : 16);
    }

    private void readAnimationSequences() {
        var sequenceOffset = archive.getOffset();
        var sequenceCount = archive.readIndex();
        requireCount(
                "animation sequences at 0x" + Integer.toHexString(sequenceOffset) + ", internalVersion=" + version,
                sequenceCount);
        animationSequences = new AnimationSequence[sequenceCount];
        var checks = new Checks() {
            @Override
            public void remaining(String label, int bytes) {
                requireRemaining(label, bytes);
            }

            @Override
            public void count(String label, int count) {
                requireCount(label, count);
            }
        };
        for (var i = 0; i < sequenceCount; i++) {
            var sequence = UMeshAnimSequences.read(archive, checks);
            var name = sequence.name();
            var startFrame = sequence.startFrame();
            var sequenceFrames = sequence.frameCount();
            var rate = sequence.rate();
            if (startFrame < 0 || sequenceFrames < 0 || startFrame > frameCount || !Float.isFinite(rate)) {
                throw malformedException("invalid animation sequence "
                        + name
                        + " ["
                        + startFrame
                        + ", "
                        + sequenceFrames
                        + ", "
                        + rate
                        + "] for "
                        + frameCount
                        + " frames");
            }
            var storedFrames = Math.min(sequenceFrames, frameCount - startFrame);
            animationSequences[i] = new AnimationSequence(name, startFrame, storedFrames, rate);
        }
    }

    private int skipArray(String label, int elementBytes) {
        var offset = archive.getOffset();
        var count = archive.readIndex();
        requireCount(label + " at 0x" + Integer.toHexString(offset), count);
        var bytes = Math.multiplyExact(count, elementBytes);
        requireRemaining(label + " at 0x" + Integer.toHexString(offset) + " count=" + count, bytes);
        archive.skipBytes(bytes);
        return count;
    }

    private void requireRemaining(String label, int bytes) {
        if (bytes < 0 || archive.getRemainingInObject() < bytes) {
            throw malformedException(
                    "truncated " + label + ": need " + bytes + ", have " + archive.getRemainingInObject());
        }
    }

    private int[] readUInt16Array(String label) {
        var count = archive.readIndex();
        requireCount(label, count);
        var values = new int[count];
        for (var i = 0; i < count; i++) {
            values[i] = archive.readUInt16();
        }
        return values;
    }

    private void validateTopology() {
        for (var wedge : wedges) {
            if (wedge.vertexIndex() < 0 || wedge.vertexIndex() >= vertexCount) {
                throw malformedException("wedge vertex " + wedge.vertexIndex() + " outside 0.." + (vertexCount - 1));
            }
        }
        for (var face : faces) {
            if (face.wedge0() >= wedges.length || face.wedge1() >= wedges.length || face.wedge2() >= wedges.length) {
                throw malformedException("face references a wedge outside 0.." + (wedges.length - 1));
            }
            if (face.materialIndex() >= materials.length) {
                throw malformedException(
                        "face material " + face.materialIndex() + " outside 0.." + (materials.length - 1));
            }
        }
    }

    private void requireCount(String label, int count) {
        if (count < 0 || count > 10_000_000) {
            throw malformedException("invalid " + label + " count " + count);
        }
    }

    private IllegalStateException malformedException(String detail) {
        return new IllegalStateException("Malformed VertMesh '" + getFullName() + "': " + detail);
    }
}
