package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.geometry.WindSway;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.DebugLabel;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer.Builder;
import com.rost.cruma.core.world.Entity.SourcePart;
import com.rost.cruma.core.world.Entity.Surface;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.Entity.WaterEntityMaterial;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

public final class OneOffMerger {
    private static final BlendClass[] BLEND_CLASSES = BlendClass.values();

    private final EnumMap<BlendClass, Group[]> groups = new EnumMap<>(BlendClass.class);
    private final List<PendingProp> pendingProps = new ArrayList<>();
    private final float[] normalMatrix = new float[9];
    private final int[] vertexBaseScratch = new int[BLEND_CLASSES.length];
    private final Group[] touchedGroupScratch = new Group[BLEND_CLASSES.length];
    private int propCount;

    private record BucketKey(
            long type,
            Object materialKey,
            BlendMode blend,
            UvAnimation uv,
            boolean modulateStaticLighting2x,
            boolean depthWrite,
            int alphaRef,
            boolean usesVertexColor) {}

    private record Bucket(Surface source, IntArrayList tris) {}

    private record SwaySegment(int floatOffset, float[] values) {}

    private record PendingProp(
            EntityMesh mesh,
            String meshName,
            String actor,
            float[] modelMatrix,
            float instanceLightX,
            float instanceLightY,
            float instanceLightZ,
            boolean sunAffected) {}

    private record PendingPart(
            String actor,
            String asset,
            int materialSlot,
            Bucket bucket,
            int firstIndex,
            int indexCount,
            Bounds3f bounds) {}

    private enum BlendClass {
        OPAQUE_PASS,
        ADDITIVE,
        MODULATE
    }

    private static final class Group {
        final Builder vertices;
        final LinkedHashMap<BucketKey, Bucket> buckets = new LinkedHashMap<>();
        final List<DebugLabel> labels = new ArrayList<>();
        final List<PendingPart> sourceParts = new ArrayList<>();
        final List<SwaySegment> swaySegments = new ArrayList<>();
        final Box worldBox = new Box();
        boolean hasSway;
        boolean hasStaticMeshInstanceLighting;
        int propCount;
        final boolean sunAffected;

        private Group(boolean sunAffected, int expectedVertices) {
            this.sunAffected = sunAffected;
            vertices = MeshVertexBuffer.builder(expectedVertices);
        }
    }

    public static boolean canMerge(EntityMesh mesh, boolean commutativeBlends) {
        for (var vertex = 0; vertex < mesh.vertexBuffer().size(); vertex++) {
            if (mesh.vertexBuffer().hasStaticMeshTangent(vertex)) {
                return false;
            }
        }
        if (mesh.billboard()) {
            return false;
        }
        if (mesh.swayFrequency() > 0f && !commutativeBlends) {
            return false;
        }
        var drawable = false;
        for (var surface : mesh.surfaces()) {
            var blend = surface.blendMode();
            if (blend == BlendMode.TRANSLUCENT
                    || blend == BlendMode.EMISSIVE
                    || blend == BlendMode.SCREEN
                    || blend == BlendMode.DARKEN
                    || blend == BlendMode.BRIGHTEN) {
                return false;
            }
            if ((blend == BlendMode.ADDITIVE || blend == BlendMode.MODULATE) && !commutativeBlends) {
                return false;
            }
            if (surface.material() instanceof WaterEntityMaterial || surface.water()) {
                return false;
            }
            if (blend != BlendMode.INVISIBLE) {
                drawable = true;
            }
        }
        return drawable;
    }

    static float[] inverseTranspose3x3(float[] m) {
        var result = new float[9];
        inverseTranspose3x3(m, result);
        return result;
    }

    static void inverseTranspose3x3(float[] m, float[] result) {
        float a00 = m[0];
        float a10 = m[1];
        float a20 = m[2];
        float a01 = m[4];
        float a11 = m[5];
        float a21 = m[6];
        float a02 = m[8];
        float a12 = m[9];
        float a22 = m[10];
        var c00 = a11 * a22 - a12 * a21;
        var c01 = a12 * a20 - a10 * a22;
        var c02 = a10 * a21 - a11 * a20;
        var c10 = a02 * a21 - a01 * a22;
        var c11 = a00 * a22 - a02 * a20;
        var c12 = a01 * a20 - a00 * a21;
        var c20 = a01 * a12 - a02 * a11;
        var c21 = a02 * a10 - a00 * a12;
        var c22 = a00 * a11 - a01 * a10;
        var det = a00 * c00 + a01 * c01 + a02 * c02;
        if (Math.abs(det) < 1e-8f) {
            result[0] = a00;
            result[1] = a10;
            result[2] = a20;
            result[3] = a01;
            result[4] = a11;
            result[5] = a21;
            result[6] = a02;
            result[7] = a12;
            result[8] = a22;
            return;
        }
        var inv = 1f / det;
        result[0] = c00 * inv;
        result[1] = c10 * inv;
        result[2] = c20 * inv;
        result[3] = c01 * inv;
        result[4] = c11 * inv;
        result[5] = c21 * inv;
        result[6] = c02 * inv;
        result[7] = c12 * inv;
        result[8] = c22 * inv;
    }

    public int getPropCount() {
        return propCount;
    }

    public void add(EntityMesh mesh, String meshName, Mat4 modelMatrix, Vec3 instanceLight) {
        add(mesh, meshName, modelMatrix, instanceLight, false);
    }

    public void add(EntityMesh mesh, String meshName, Mat4 modelMatrix, Vec3 instanceLight, boolean sunAffected) {
        add(mesh, meshName, modelMatrix, instanceLight, sunAffected, null);
    }

    public void add(
            EntityMesh mesh, String meshName, Mat4 modelMatrix, Vec3 instanceLight, boolean sunAffected, String actor) {
        var packedModelMatrix = new float[16];
        modelMatrix.to(packedModelMatrix);
        pendingProps.add(new PendingProp(
                mesh,
                meshName,
                actor,
                packedModelMatrix,
                instanceLight.getX(),
                instanceLight.getY(),
                instanceLight.getZ(),
                sunAffected));
        propCount++;
    }

    public List<Entity> flush() {
        prepareExactGroups();
        for (var pending : pendingProps) {
            addPrepared(
                    pending.mesh(),
                    pending.meshName(),
                    pending.actor(),
                    pending.modelMatrix(),
                    pending.instanceLightX(),
                    pending.instanceLightY(),
                    pending.instanceLightZ(),
                    pending.sunAffected());
        }
        pendingProps.clear();
        var entities = new ArrayList<Entity>(groups.size() * 2);
        for (var blendClass : BLEND_CLASSES) {
            var variants = groups.get(blendClass);
            if (variants == null) {
                continue;
            }
            for (var group : variants) {
                if (group != null) {
                    entities.add(flushGroup(blendClass, group));
                }
            }
        }
        return entities;
    }

    private void addPrepared(
            EntityMesh mesh,
            String meshName,
            String actor,
            float[] matrix,
            float instanceLightX,
            float instanceLightY,
            float instanceLightZ,
            boolean sunAffected) {
        inverseTranspose3x3(matrix, normalMatrix);
        var nmat = normalMatrix;

        var vertexBase = vertexBaseScratch;
        Arrays.fill(vertexBase, -1);
        var touchedGroups = touchedGroupScratch;
        Arrays.fill(touchedGroups, null);
        for (var surface : mesh.surfaces()) {
            if (surface.blendMode() == BlendMode.INVISIBLE) {
                continue;
            }
            var blendClass = classOf(surface.blendMode());
            var ordinal = blendClass.ordinal();
            if (touchedGroups[ordinal] == null) {
                var target = groupFor(blendClass, sunAffected);
                touchedGroups[ordinal] = target;
                vertexBase[ordinal] = target.vertices.size();
            }
        }
        var sourceVertices = mesh.vertexBuffer();
        var vertexCount = sourceVertices.size();
        for (var ordinal = 0; ordinal < touchedGroups.length; ordinal++) {
            var target = touchedGroups[ordinal];
            if (target != null) {
                target.vertices.ensureCapacity(target.vertices.size() + vertexCount);
            }
        }

        for (var sourceIndex = 0; sourceIndex < sourceVertices.size(); sourceIndex++) {
            var px = sourceVertices.getPositionX(sourceIndex);
            var py = sourceVertices.getPositionY(sourceIndex);
            var pz = sourceVertices.getPositionZ(sourceIndex);
            var normalX = sourceVertices.getNormalX(sourceIndex);
            var normalY = sourceVertices.getNormalY(sourceIndex);
            var normalZ = sourceVertices.getNormalZ(sourceIndex);
            var wx = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
            var wy = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
            var wz = matrix[2] * px + matrix[6] * py + matrix[10] * pz + matrix[14];
            var nx = nmat[0] * normalX + nmat[3] * normalY + nmat[6] * normalZ;
            var ny = nmat[1] * normalX + nmat[4] * normalY + nmat[7] * normalZ;
            var nz = nmat[2] * normalX + nmat[5] * normalY + nmat[8] * normalZ;
            var bakedX = (sunAffected ? 0f : instanceLightX) + sourceVertices.getBakedLightX(sourceIndex);
            var bakedY = (sunAffected ? 0f : instanceLightY) + sourceVertices.getBakedLightY(sourceIndex);
            var bakedZ = (sunAffected ? 0f : instanceLightZ) + sourceVertices.getBakedLightZ(sourceIndex);
            for (var target : touchedGroups) {
                if (target != null) {
                    var targetIndex = target.vertices.add(
                            wx,
                            wy,
                            wz,
                            nx,
                            ny,
                            nz,
                            sourceVertices.getUvX(sourceIndex),
                            sourceVertices.getUvY(sourceIndex));
                    target.vertices.setBakedLight(targetIndex, bakedX, bakedY, bakedZ);
                    target.vertices.setLightmapUv(
                            targetIndex,
                            sourceVertices.getLightmapUvX(sourceIndex),
                            sourceVertices.getLightmapUvY(sourceIndex));
                    if (sourceVertices.hasColor(sourceIndex)) {
                        target.vertices.setColor(
                                targetIndex,
                                sourceVertices.getColorR(sourceIndex),
                                sourceVertices.getColorG(sourceIndex),
                                sourceVertices.getColorB(sourceIndex),
                                sourceVertices.getColorA(sourceIndex));
                    }
                    var sunVisibilityMask = sourceVertices.getSunVisibilityMask(sourceIndex);
                    if (sunVisibilityMask != -1) {
                        target.vertices.setSunVisibilityMask(targetIndex, sunVisibilityMask);
                    }
                    target.vertices.setSecondaryUv(
                            targetIndex,
                            sourceVertices.getSecondaryUvX(sourceIndex),
                            sourceVertices.getSecondaryUvY(sourceIndex));
                }
            }
        }
        var sway = mesh.swayFrequency() > 0f ? bakeSwayParams(mesh, matrix) : null;
        for (var target : touchedGroups) {
            if (target != null) {
                appendSwayParams(target, sway, vertexCount);
                target.hasStaticMeshInstanceLighting |= mesh.staticMeshInstanceLighting();
            }
        }

        var propBox = new Box(mesh.boundingBox(), matrix);
        var sourceBounds = new Bounds3f(
                propBox.getMin().getX(),
                propBox.getMin().getY(),
                propBox.getMin().getZ(),
                propBox.getMax().getX(),
                propBox.getMax().getY(),
                propBox.getMax().getZ());
        for (var slot = 0; slot < mesh.surfaces().size(); slot++) {
            var surface = mesh.surfaces().get(slot);
            if (surface.blendMode() == BlendMode.INVISIBLE || surface.indexCount() == 0) {
                continue;
            }
            var blendClass = classOf(surface.blendMode());
            var group = groupFor(blendClass, sunAffected);
            var ordinal = blendClass.ordinal();
            var key = new BucketKey(
                    surface.type(),
                    surface.materialName() != null ? surface.materialName() : surface.material(),
                    surface.blendMode(),
                    surface.uvAnimation(),
                    surface.modulateStaticLighting2x(),
                    surface.depthWrite(),
                    surface.alphaRef(),
                    surface.usesVertexColor());
            var bucket = group.buckets.computeIfAbsent(
                    key, _ -> new Bucket(surface, new IntArrayList(surface.indexCount())));
            var end = surface.indexOffset() + surface.indexCount();
            if (actor != null && !actor.isBlank()) {
                group.sourceParts.add(new PendingPart(
                        actor, meshName, slot, bucket, bucket.tris().size(), surface.indexCount(), sourceBounds));
            }
            bucket.tris().ensureCapacity(bucket.tris().size() + surface.indexCount());
            for (int k = surface.indexOffset(); k < end; k++) {
                bucket.tris().add(vertexBase[ordinal] + mesh.indices()[k]);
            }
        }

        var center = propBox.getMin().plus(propBox.getMax()).times(0.5f);
        var labeled = false;
        for (var blendClass : BLEND_CLASSES) {
            var group = touchedGroups[blendClass.ordinal()];
            if (group == null) {
                continue;
            }
            group.worldBox.add(propBox.getMin());
            group.worldBox.add(propBox.getMax());
            group.propCount++;
            if (!labeled) {
                group.labels.add(
                        new DebugLabel(shortName(meshName), center, propBox.getMin(), propBox.getMax(), new Vec3[0]));
                labeled = true;
            }
        }
    }

    private void prepareExactGroups() {
        var expectedVertices = new int[BLEND_CLASSES.length][2];
        for (var pending : pendingProps) {
            var classMask = 0;
            for (var surface : pending.mesh().surfaces()) {
                if (surface.blendMode() != BlendMode.INVISIBLE) {
                    classMask |= 1 << classOf(surface.blendMode()).ordinal();
                }
            }
            var variant = pending.sunAffected() ? 1 : 0;
            var vertexCount = pending.mesh().vertices().size();
            for (var ordinal = 0; ordinal < BLEND_CLASSES.length; ordinal++) {
                if ((classMask & 1 << ordinal) != 0) {
                    expectedVertices[ordinal][variant] = Math.addExact(expectedVertices[ordinal][variant], vertexCount);
                }
            }
        }
        for (var ordinal = 0; ordinal < BLEND_CLASSES.length; ordinal++) {
            for (var variant = 0; variant < 2; variant++) {
                var expected = expectedVertices[ordinal][variant];
                if (expected == 0) {
                    continue;
                }
                var groupsForClass = groups.computeIfAbsent(BLEND_CLASSES[ordinal], _ -> new Group[2]);
                groupsForClass[variant] = new Group(variant == 1, expected);
            }
        }
    }

    private Group groupFor(BlendClass blendClass, boolean sunAffected) {
        var variants = groups.computeIfAbsent(blendClass, _ -> new Group[2]);
        var index = sunAffected ? 1 : 0;
        if (variants[index] == null) {
            variants[index] = new Group(sunAffected, 256);
        }
        return variants[index];
    }

    private static Entity flushGroup(BlendClass blendClass, Group group) {
        var bucketBases = new int[group.buckets.size()];
        var sourceBases = new IdentityHashMap<Bucket, Integer>();
        var total = 0;
        var b = 0;
        for (var bucket : group.buckets.values()) {
            sourceBases.put(bucket, total);
            bucketBases[b++] = total;
            total += bucket.tris().size();
        }
        var indices = new int[total];
        var surfaces = new ArrayList<Surface>(group.buckets.size());
        b = 0;
        for (var bucket : group.buckets.values()) {
            var base = bucketBases[b++];
            for (int k = 0; k < bucket.tris().size(); k++) {
                indices[base + k] = bucket.tris().get(k);
            }
            var source = bucket.source();
            surfaces.add(new Surface(
                    source.type(),
                    base,
                    bucket.tris().size(),
                    source.material(),
                    source.blendMode(),
                    source.uvAnimation(),
                    false,
                    source.materialName(),
                    source.alphaRef(),
                    source.usesVertexColor(),
                    source.depthWrite(),
                    source.modulateStaticLighting2x()));
        }
        var entity = new Entity(new EntityMesh(
                group.vertices.build(),
                indices,
                surfaces,
                List.of(),
                group.worldBox,
                0f,
                false,
                group.hasStaticMeshInstanceLighting));
        entity.setMergedRegion(true);
        entity.setSunAffected(group.sunAffected);
        entity.setDebugName("merged one-offs "
                + (blendClass == BlendClass.OPAQUE_PASS ? "" : blendClass.name().toLowerCase(Locale.ROOT) + " ")
                + "("
                + group.propCount
                + ")");
        entity.setDebugLabels(group.labels);
        entity.setSourceParts(group.sourceParts.stream()
                .map(part -> new SourcePart(
                        part.actor(),
                        part.asset(),
                        part.materialSlot(),
                        sourceBases.get(part.bucket()) + part.firstIndex(),
                        part.indexCount(),
                        part.bounds()))
                .toList());
        if (group.hasSway) {
            var swayParams = new float[group.vertices.size() * 3];
            for (var segment : group.swaySegments) {
                System.arraycopy(segment.values(), 0, swayParams, segment.floatOffset(), segment.values().length);
            }
            entity.setSwayVertexParams(swayParams);
        }
        return entity;
    }

    private static BlendClass classOf(BlendMode blend) {
        return switch (blend) {
            case ADDITIVE -> BlendClass.ADDITIVE;
            case MODULATE -> BlendClass.MODULATE;
            default -> BlendClass.OPAQUE_PASS;
        };
    }

    private static void appendSwayParams(Group group, float[] sway, int vertexCount) {
        if (sway != null) {
            group.swaySegments.add(new SwaySegment((group.vertices.size() - vertexCount) * 3, sway));
            group.hasSway = true;
        }
    }

    private static float[] bakeSwayParams(EntityMesh mesh, float[] matrix) {
        var angle = (float) Math.toRadians(mesh.swayMaxAngle());
        var frequency = mesh.swayFrequency();
        var pivotX = matrix[12];
        var pivotY = matrix[13];
        var pivotZ = matrix[14];
        var axisX = WindSway.AXIS_X;
        var axisY = WindSway.AXIS_Y;
        var vertices = mesh.vertexBuffer();
        var params = new float[vertices.size() * 3];
        var i = 0;
        for (var vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            var px = vertices.getPositionX(vertexIndex);
            var py = vertices.getPositionY(vertexIndex);
            var pz = vertices.getPositionZ(vertexIndex);
            var worldX = matrix[0] * px + matrix[4] * py + matrix[8] * pz + matrix[12];
            var worldY = matrix[1] * px + matrix[5] * py + matrix[9] * pz + matrix[13];
            var worldZ = matrix[2] * px + matrix[6] * py + matrix[10] * pz + matrix[14];
            var localX = worldX - pivotX;
            var localY = worldY - pivotY;
            var localZ = worldZ - pivotZ;
            var leverY = -axisX * localZ;
            var leverZ = axisX * localY - axisY * localX;
            params[i++] = angle * leverY;
            params[i++] = angle * leverZ;
            params[i++] = frequency;
        }
        return params;
    }

    private static String shortName(String fullName) {
        var dot = fullName.lastIndexOf('.');
        return dot >= 0 ? fullName.substring(dot + 1) : fullName;
    }
}
