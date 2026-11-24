package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.MeshVertexBuffer;
import com.rost.cruma.core.world.NativeVertexLightmap.EnvironmentLight;
import com.rost.cruma.core.world.NativeVertexLightmap.EnvironmentSource;
import com.rost.cruma.core.world.NativeVertexLightmap.SceneLight;
import com.rost.cruma.core.world.NativeVertexLightmap.Source;
import com.rost.cruma.core.world.NativeVertexLightmapInputs.Reference;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntSupplier;
import org.jetbrains.annotations.Nullable;

public final class NativeVertexLightmapState {
    private final Map<String, Actor> actors = new HashMap<>();
    private final Map<String, Instance> instances = new HashMap<>();
    private final Map<String, IdentityHashMap<Entity, Binding>> bindings = new HashMap<>();
    private final IdentityHashMap<MeshVertexBuffer, Geometry> geometry = new IdentityHashMap<>();

    public record Cached(Source light, boolean dynamic, boolean changed) {}

    public record Stream(float[] coefficients, long revision) {}

    private record Geometry(float[] vertices, float[] tangents) {}

    private static final class Actor implements NativeLightRefresh.Actor {
        final Instance[] instances = new Instance[3];
        boolean dirty;

        public boolean isDeleted() {
            return false;
        }

        public void markStaticLightingDirty() {
            dirty = true;
        }

        public @Nullable NativeLightRefresh.Light getLight() {
            return null;
        }

        public void clearRenderData() {}
    }

    private static final class Instance {
        final String id;
        int[] sceneApplied;
        int[] environmentApplied;
        Stream base;
        Stream environment;
        boolean dirty;

        Instance(NativeVertexLightmapInputs inputs) {
            id = toKey(inputs.instanceId());
            sceneApplied = collectApplied(inputs.sceneLights());
            environmentApplied = collectApplied(inputs.environmentLights());
        }
    }

    public static final class Binding {
        private final Actor actor;
        private final Instance instance;
        private final NativeVertexLightmapInputs inputs;
        private final MeshVertexBuffer vertices;
        private final float[] localToWorld;
        private final float[] worldToLocal;
        private final boolean beast;
        private final int level;

        private Binding(Actor actor, Instance instance, Entity entity) {
            this.actor = actor;
            this.instance = instance;
            inputs = entity.getNativeVertexLightmapInputs();
            vertices = ((EntityMesh) entity.getMesh()).vertexBuffer();
            localToWorld = new float[16];
            worldToLocal = new float[16];
            var model = entity.getModelMatrix();
            model.to(localToWorld);
            model.inverse().to(worldToLocal);
            beast = entity.getNativeStaticMeshLightingInputs().beastInstance();
            level = entity.getNativeStaticMeshDraw().level();
        }

        public @Nullable Stream getStream() {
            return beast ? null : inputs.sunAffected() ? instance.environment : instance.base;
        }

        public @Nullable Stream getBaseStream() {
            return instance.base;
        }

        public @Nullable Stream getEnvironmentStream() {
            return instance.environment;
        }

        public String getInstanceId() {
            return instance.id;
        }

        public boolean isInitialized() {
            return instance.base != null;
        }

        public boolean isEnvironmentDirty() {
            return instance.dirty || actor.dirty;
        }

        public int[] getSceneApplied() {
            return instance.sceneApplied.clone();
        }

        public int[] getEnvironmentApplied() {
            return instance.environmentApplied.clone();
        }
    }

    public @Nullable Binding bind(String owner, Entity entity) {
        var input = entity.getNativeVertexLightmapInputs();
        var draw = entity.getNativeStaticMeshDraw();
        var receiver = entity.getNativeLightReceiver();
        if (input == null
                || draw == null
                || receiver == null
                || !receiver.getFlags().staticActor()) {
            return null;
        }
        var owned = bindings.computeIfAbsent(toKey(owner), _ -> new IdentityHashMap<>());
        var existing = owned.get(entity);
        if (existing != null) {
            return existing;
        }
        var actor = actors.computeIfAbsent(toKey(receiver.getActorId()), _ -> new Actor());
        var instance = instances.computeIfAbsent(toKey(input.instanceId()), _ -> new Instance(input));
        actor.instances[draw.level()] = instance;
        var binding = new Binding(actor, instance, entity);
        owned.put(entity, binding);
        return binding;
    }

    public void markActorDirty(String actorId) {
        var actor = actors.get(toKey(actorId));
        if (actor != null) {
            actor.dirty = true;
        }
    }

    public @Nullable NativeLightRefresh.Actor getActor(String actorId) {
        return actors.get(toKey(actorId));
    }

    public @Nullable Stream prepare(
            Binding binding,
            float hour,
            Vec3 managerColor,
            boolean beastWorld,
            Function<String, @Nullable Cached> source,
            IntSupplier appRand) {
        var instance = binding.instance;
        var input = binding.inputs;
        var mesh = geometry.computeIfAbsent(binding.vertices, NativeVertexLightmapState::toGeometry);
        if (instance.base == null) {
            var records = new ArrayList<SceneLight>(input.sceneLights().size());
            for (var i = 0; i < input.sceneLights().size(); i++) {
                var ref = input.sceneLights().get(i);
                records.add(new SceneLight(ref.actorReference(), null, ref.visibility(), instance.sceneApplied[i]));
            }
            var rebuilt = NativeVertexLightmap.rebuild(
                    mesh.vertices(),
                    mesh.tangents(),
                    binding.localToWorld,
                    binding.worldToLocal,
                    input.drawScale(),
                    input.intensity(),
                    records,
                    index -> {
                        var cached = resolve(input.sceneLights().get(index), source);
                        return cached == null ? null : cached.light();
                    });
            instance.base = new Stream(rebuilt.coefficients(), 1);
            instance.sceneApplied = rebuilt.applied();
        }
        if (binding.actor.dirty) {
            for (var child : binding.actor.instances) {
                if (child != null) {
                    child.dirty = true;
                }
            }
            binding.actor.dirty = false;
        }
        if (instance.dirty) {
            if (input.sunAffected() && !binding.beast && !beastWorld) {
                touchOrdinaryEnvironment(input.environmentLights(), instance.environmentApplied, hour, source);
                var records = new ArrayList<EnvironmentLight>(
                        input.environmentLights().size());
                for (var i = 0; i < input.environmentLights().size(); i++) {
                    records.add(new EnvironmentLight(
                            null, input.environmentLights().get(i).visibility(), instance.environmentApplied[i]));
                }
                var updated = NativeVertexLightmap.updateEnvironment(
                        mesh.vertices(),
                        mesh.tangents(),
                        instance.base.coefficients(),
                        managerColor,
                        records,
                        appRand,
                        index -> {
                            var cached = resolve(input.environmentLights().get(index), source);
                            return cached == null
                                    ? null
                                    : new EnvironmentSource(
                                            cached.light().direction(), cached.dynamic(), cached.changed());
                        });
                if (updated != null) {
                    instance.environment = new Stream(
                            updated.coefficients(),
                            instance.environment == null ? 1 : instance.environment.revision() + 1);
                    instance.environmentApplied = updated.applied();
                }
            }
            instance.dirty = false;
        }
        return binding.getStream();
    }

    public void remove(String owner) {
        bindings.remove(toKey(owner));
        pruneBindings();
    }

    public void unbind(String owner, Entity entity) {
        var owned = bindings.get(toKey(owner));
        if (owned == null || owned.remove(entity) == null) {
            return;
        }
        if (owned.isEmpty()) {
            bindings.remove(toKey(owner));
        }
        pruneBindings();
    }

    private void pruneBindings() {
        var retained = new IdentityHashMap<MeshVertexBuffer, Boolean>();
        var retainedInstances = new IdentityHashMap<Instance, Boolean>();
        var retainedActors = new IdentityHashMap<Actor, Boolean>();
        for (var entries : bindings.values()) {
            for (var binding : entries.values()) {
                retained.put(binding.vertices, true);
                retainedInstances.put(binding.instance, true);
                retainedActors.put(binding.actor, true);
            }
        }
        actors.values().removeIf(actor -> !retainedActors.containsKey(actor));
        for (var actor : actors.values()) {
            Arrays.fill(actor.instances, null);
        }
        for (var entries : bindings.values()) {
            for (var binding : entries.values()) {
                binding.actor.instances[binding.level] = binding.instance;
            }
        }
        instances.values().removeIf(instance -> !retainedInstances.containsKey(instance));
        geometry.keySet().removeIf(vertices -> !retained.containsKey(vertices));
    }

    private static void touchOrdinaryEnvironment(
            List<Reference> references, int[] applied, float hour, Function<String, @Nullable Cached> source) {
        var count = references.size();
        if (count == 0) {
            return;
        }
        var first = count - 1;
        for (var index = 0; index < count; index++) {
            var center = index * 24f / count + 24f / count * .5f;
            if (center > hour) {
                break;
            }
            first = index;
        }
        for (var slot = 0; slot < 2; slot++) {
            var index = (first + slot) % count;
            var cached = resolve(references.get(index), source);
            applied[index] = cached != null && !cached.dynamic() && !cached.changed() ? 1 : 0;
        }
    }

    private static @Nullable Cached resolve(Reference reference, Function<String, @Nullable Cached> source) {
        return reference.actorId() == null ? null : source.apply(reference.actorId());
    }

    private static Geometry toGeometry(MeshVertexBuffer source) {
        var vertices = new float[source.size() * 6];
        var tangents = new float[source.size() * 4];
        for (var i = 0; i < source.size(); i++) {
            var at = i * 6;
            vertices[at] = source.getPositionX(i);
            vertices[at + 1] = source.getPositionY(i);
            vertices[at + 2] = source.getPositionZ(i);
            vertices[at + 3] = source.getNormalX(i);
            vertices[at + 4] = source.getNormalY(i);
            vertices[at + 5] = source.getNormalZ(i);
            at = i * 4;
            tangents[at] = source.getStaticMeshTangentX(i);
            tangents[at + 1] = source.getStaticMeshTangentY(i);
            tangents[at + 2] = source.getStaticMeshTangentZ(i);
            tangents[at + 3] = source.getStaticMeshTangentW(i);
        }
        return new Geometry(vertices, tangents);
    }

    private static int[] collectApplied(List<Reference> references) {
        var values = new int[references.size()];
        for (var index = 0; index < values.length; index++) {
            values[index] = references.get(index).applied();
        }
        return values;
    }

    private static String toKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
