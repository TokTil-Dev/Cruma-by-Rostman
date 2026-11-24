package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ActorDomain.Actor;
import com.rost.cruma.core.editing.P152ActorDomain.Captured;
import com.rost.cruma.core.editing.P152ActorDomain.Disposition;
import com.rost.cruma.core.editing.P152ActorDomain.Identity;
import com.rost.cruma.core.editing.P152ActorDomain.Inputs;
import com.rost.cruma.core.editing.P152ActorDomainMesh.RenderGeometry;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import com.rost.cruma.core.editing.P152ShadowMeshDrawInputs.Binding;
import com.rost.cruma.core.editing.P152ShadowMeshDrawInputs.Mesh;
import com.rost.cruma.core.editing.P152ShadowMeshMaterials.ActorMaterials;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.unreal.primitives.UBox;
import com.rost.cruma.core.world.NativeActorBounds;
import com.rost.cruma.core.world.NativeBspShadowProjection.Bounds;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import com.rost.cruma.core.world.NativeBspShadowProjection.Volume;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class P152ShadowMeshGeometry {
    private final List<Triangle> triangles;
    private final List<Placement> placements;
    private final List<Actor> actors;
    private final List<P152ActorDomain.Source> sources;
    private final List<P152EffectiveInputs.Source> identities;
    private final List<Input> runtimeInputs;
    private final Inputs inputs;
    private final int otherActors;

    private P152ShadowMeshGeometry(
            List<Triangle> triangles,
            List<Placement> placements,
            List<Actor> actors,
            List<Captured> captured,
            Inputs inputs,
            int otherActors) {
        this.triangles = List.copyOf(triangles);
        this.placements = List.copyOf(placements);
        this.actors = List.copyOf(actors);
        sources = captured.stream().map(Captured::receipt).toList();
        identities = captured.stream().map(Captured::identity).toList();
        this.inputs = inputs;
        runtimeInputs = List.copyOf(inputs.getReceipt());
        this.otherActors = otherActors;
    }

    record Placement(Identity actor, Identity mesh, int firstTriangle, int triangleCount, Bounds bounds) {}

    static P152ShadowMeshGeometry capture(Path root, MapInputs inputs, BooleanSupplier cancelled) throws IOException {
        return capture(
                root,
                inputs.getMapIdentity().logicalPath(),
                inputs.getSourceSha256(),
                P152ActorDomain.toInputs(inputs),
                cancelled);
    }

    static P152ShadowMeshGeometry capture(
            Path clientRoot, Path map, String expectedHash, Inputs inputs, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(inputs);
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toAbsolutePath().normalize().toRealPath();
        var capturedMap = P152ActorDomain.read(root, map, cancelled);
        if (!capturedMap.identity().sha256().equals(expectedHash)) {
            throw new IOException("Shadow geometry map differs from its input capture");
        }
        var source = capturedMap.bytes();
        if (source.fileVersion != 128 || source.licenseVersion != 39) {
            throw new IOException("Shadow mesh map requires P152 128/39");
        }
        var level = NativeLevelEditor.read(source);
        P152ActorDomain.requireEngineClass(source, level.entry, "Level");
        if (level.actors.isEmpty() || level.actors.size() > 65536 || level.actors.getFirst() <= 0) {
            throw new IOException("Shadow geometry requires a bounded local Level registry");
        }
        P152ActorDomain.requireEngineClass(source, source.exports.get(level.actors.getFirst() - 1), "LevelInfo");
        var packages = new LinkedHashMap<String, Captured>();
        packages.put(source.packageName.toLowerCase(Locale.ROOT), capturedMap);
        var files = P152ActorDomain.assetFiles(root, cancelled);
        var meshes = new HashMap<Identity, RenderGeometry>();
        var triangles = new ArrayList<Triangle>();
        var placements = new ArrayList<Placement>();
        var actors = new ArrayList<Actor>();
        var seen = new HashSet<Integer>();
        int otherActors = 0;
        for (int ref : level.actors) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (ref == 0) {
                continue;
            }
            if (!seen.add(ref)) {
                throw new IOException("Duplicate actor in shadow geometry registry");
            }
            if (ref < 0) {
                throw new IOException("Imported Level actor has no bounded local mesh classification");
            }
            var entry = source.exports.get(ref - 1);
            var cls = source.getObjectPath(entry.classIndex());
            if (!cls.equals("Engine.StaticMeshActor")
                    && !cls.equals("Engine.L2MovableStaticMeshActor")
                    && !cls.equals("Engine.Mover")) {
                otherActors++;
                continue;
            }
            var actor = new Identity(expectedHash, ref, source.getObjectPath(ref));
            try {
                P152ActorDomain.requireEngineClass(source, entry, cls.substring("Engine.".length()));
                var facts = switch (cls) {
                    case "Engine.Mover" -> inputs.getMover(actor.objectPath());
                    case "Engine.L2MovableStaticMeshActor" -> inputs.getMovableMesh(actor.objectPath());
                    default -> inputs.getMesh(actor.objectPath());
                };
                if (!facts.collision().shadowCast().value()) {
                    actors.add(new Actor(actor, cls, Disposition.EXCLUDED, "Effective bShadowCast is false"));
                    continue;
                }
                var reference = facts.staticMesh().value();
                if (reference.isNull() || !reference.declaredClass().equals("Engine.StaticMesh")) {
                    throw new IOException("Shadow actor has no exact StaticMesh reference");
                }
                if (!facts.mesh().value().isNull()
                        || !facts.meshContainer().value().isNull()) {
                    throw new IOException(
                            "Shadow mesh bounds require no higher-priority Mesh or MeshContainer primitive");
                }
                var meshPackage =
                        P152ActorDomain.resolvePackage(root, source, reference.index(), files, packages, cancelled);
                var meshEntry = P152ActorDomain.assetExport(meshPackage.bytes(), reference.objectPath(), cancelled);
                var identity = new Identity(meshPackage.identity().sha256(), meshEntry.index(), reference.objectPath());
                var local = meshes.get(identity);
                if (local == null) {
                    var gaps = P152ActorDomain.editorAssetLoadGaps(meshPackage.bytes(), meshEntry);
                    if (!gaps.isEmpty()) {
                        throw new IOException(String.join("; ", gaps));
                    }
                    local = P152ActorDomainMesh.readRenderGeometry(meshPackage.bytes(), meshEntry, cancelled);
                    meshes.put(identity, local);
                }
                if ((long) triangles.size() + local.triangles().size() > 1_000_000) {
                    throw new IOException("Placed shadow geometry exceeds editor triangle bounds");
                }
                var matrix = P152ActorDomain.toTransformPair(facts).getLocalToWorld();
                var bounds = renderBounds(local.primitiveBounds(), matrix);
                var placed = new ArrayList<Triangle>(local.triangles().size());
                for (var triangle : local.triangles()) {
                    P152FreshHashQuery.checkCancelled(cancelled);
                    placed.add(new Triangle(
                            ref,
                            triangle.part(),
                            triangle.face(),
                            P152ActorDomain.transformPoint(triangle.a(), matrix),
                            P152ActorDomain.transformPoint(triangle.b(), matrix),
                            P152ActorDomain.transformPoint(triangle.c(), matrix)));
                }
                placements.add(new Placement(actor, identity, triangles.size(), placed.size(), bounds));
                triangles.addAll(placed);
                actors.add(new Actor(
                        actor,
                        cls,
                        Disposition.CAPTURED_MESH,
                        "Base geometry captured independently of collision flags; draw admission unresolved"));
            } catch (IOException | IllegalArgumentException failure) {
                actors.add(new Actor(
                        actor,
                        cls,
                        Disposition.UNSUPPORTED,
                        failure instanceof FileSystemException
                                ? "Referenced mesh I/O failed: "
                                        + failure.getClass().getSimpleName()
                                : failure.getMessage()));
            }
        }
        inputs.seal();
        var result = new P152ShadowMeshGeometry(
                triangles, placements, actors, List.copyOf(packages.values()), inputs, otherActors);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    List<Triangle> getTriangles() {
        return triangles;
    }

    List<Binding> captureDrawInputs(BooleanSupplier cancelled) throws IOException {
        verifySourcesUnchanged(cancelled);
        var assets = new HashMap<Identity, Mesh>();
        long retained = 0;
        for (var identity : identities) {
            var required = new LinkedHashSet<Identity>();
            for (var placement : placements) {
                P152FreshHashQuery.checkCancelled(cancelled);
                if (placement.mesh().packageSha256().equals(identity.sha256())) {
                    required.add(placement.mesh());
                }
            }
            if (required.isEmpty()) {
                continue;
            }
            var bytes = P152CaptureIo.read(identity.logicalPath(), 512 * 1024 * 1024, cancelled);
            if (!P152CaptureIo.hash(bytes, cancelled).equals(identity.sha256())) {
                throw new IOException("Shadow draw source changed after geometry capture");
            }
            var source = new PackageBytes(identity.logicalPath(), bytes);
            for (var mesh : required) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var entry = source.exports.get(mesh.reference() - 1);
                if (!source.getObjectPath(mesh.reference()).equals(mesh.objectPath())) {
                    throw new IOException("Shadow draw asset identity differs from geometry capture");
                }
                var draw = P152ShadowMeshDrawInputs.read(source, entry, cancelled);
                retained += draw.retainedValues();
                P152ShadowMeshDrawInputs.requireBudget(retained);
                assets.put(mesh, draw);
            }
        }
        var result = new ArrayList<Binding>(placements.size());
        for (var placement : placements) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var asset = assets.get(placement.mesh());
            if (asset == null) {
                throw new IOException("Shadow draw asset has no captured source");
            }
            result.add(new Binding(placement, asset));
        }
        verifySourcesUnchanged(cancelled);
        return List.copyOf(result);
    }

    List<Placement> getPlacements() {
        return placements;
    }

    List<ActorMaterials> captureMaterialRoots(BooleanSupplier cancelled) throws IOException {
        var map = inputs.getMapInputs();
        if (map == null) {
            throw new IOException("Shadow material capture requires authenticated actor defaults");
        }
        var result = P152ShadowMeshMaterials.capture(captureDrawInputs(cancelled), map, cancelled);
        verifySourcesUnchanged(cancelled);
        return result;
    }

    List<Actor> getActors() {
        return actors;
    }

    List<P152ActorDomain.Source> getSources() {
        return sources;
    }

    int getOtherActors() {
        return otherActors;
    }

    List<Input> getRuntimeInputs() {
        return runtimeInputs;
    }

    Set<Integer> collectExcludedBy(Volume volume, BooleanSupplier cancelled) {
        Objects.requireNonNull(volume);
        Objects.requireNonNull(cancelled);
        var excluded = new HashSet<Integer>();
        for (var placement : placements) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (volume.checkBox(placement.bounds()) == 2) {
                excluded.add(placement.actor().reference());
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return Set.copyOf(excluded);
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        inputs.verify(cancelled);
        for (var identity : identities) {
            identity.verifyUnchanged(cancelled);
        }
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    private static Bounds renderBounds(Box box, float[] matrix) {
        var local = new UBox(
                new Vec3(box.min().x(), box.min().y(), box.min().z()),
                new Vec3(box.max().x(), box.max().y(), box.max().z()),
                box.valid() != 0);
        var world = NativeActorBounds.transformBox(local, new Mat4(matrix));
        return new Bounds(
                new Vector(world.min().getX(), world.min().getY(), world.min().getZ()),
                new Vector(world.max().getX(), world.max().getY(), world.max().getZ()));
    }
}
