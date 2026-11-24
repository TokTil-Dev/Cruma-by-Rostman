package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.primitives.USphere;
import com.rost.cruma.core.world.ModelLightRule;
import com.rost.cruma.core.world.NativeActorLightCache;
import com.rost.cruma.core.world.NativeActorLightCache.Data;
import com.rost.cruma.core.world.NativeLightCollector;
import com.rost.cruma.core.world.NativeLightReceiver;
import com.rost.cruma.core.world.NativeModelLighting;
import com.rost.cruma.viewer.render.SpawnOverlayState.ModelInstance;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightSources.ReceiverView;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightState.Input;
import com.rost.cruma.viewer.render.bgfx.BgfxSpawnModelRenderer.BodyLight;
import com.rost.cruma.viewer.spawn.SpawnModelData;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class BgfxSpawnModelLighting {
    static final int SLOTS = 8;

    static final int VECTORS = SLOTS * 4 + 1;

    private static final Vec3 NO_DIRECTION = new Vec3(0f, 0f, -1f);

    private final BgfxNativeLightSources sources;
    private final BgfxNativeLightState state = new BgfxNativeLightState();
    private final NativeActorLightCache cache = new NativeActorLightCache();
    private final Map<String, float[]> bounds = new HashMap<>();
    private int[] nodeStack = new int[0];

    BgfxSpawnModelLighting(BgfxNativeLightSources sources) {
        this.sources = sources;
    }

    static float[] pack(BgfxNativeLightState state, int count, Vec3 ambient) {
        var result = new float[VECTORS * 4];
        var lit = 0;
        for (var slot = 0; slot < Math.min(count, SLOTS); slot++) {
            if (!state.isActive(slot)) {
                continue;
            }
            var type = state.recordWord(slot, 0);
            var at = slot * 16;
            var directional = type == NativeModelLighting.DIRECTIONAL;
            var from = directional ? 16 : 13;
            for (var axis = 0; axis < 3; axis++) {
                result[at + axis] = read(state, slot, from + axis);
                result[at + 4 + axis] = read(state, slot, 1 + axis);
                result[at + 12 + axis] = read(state, slot, 16 + axis);
            }
            result[at + 3] = type;
            result[at + 7] = read(state, slot, 19);
            result[at + 8] = read(state, slot, 21);
            result[at + 9] = read(state, slot, 22);
            result[at + 10] = directional ? 1f : 0f;
            lit++;
        }
        var ambientAt = SLOTS * 16;
        result[ambientAt] = ambient.getX();
        result[ambientAt + 1] = ambient.getY();
        result[ambientAt + 2] = ambient.getZ();
        result[ambientAt + 3] = lit;
        return result;
    }

    record Environment(
            ModelLightRule rule,
            @Nullable Vec3 actorAmbient,
            @Nullable Vec3 beastActorAmbient,
            float beastLightmap2) {
        static final Environment NONE = new Environment(ModelLightRule.INTERLUDE, null, null, 1f);
    }

    Frame open(BgfxNativeView camera, double seconds, Environment environment, List<BodyLight> bodies) {
        return new Frame(camera, seconds, environment, bodies);
    }

    void clear() {
        bounds.clear();
    }

    private static float read(BgfxNativeLightState state, int slot, int word) {
        return Float.intBitsToFloat(state.recordWord(slot, word));
    }

    final class Frame {
        private final BgfxNativeView camera;
        private final double seconds;
        private final Environment environment;
        private final List<Data> bodies = new ArrayList<>();
        private final Map<String, ReceiverView> views = new HashMap<>();
        private final List<Data> reaching = new ArrayList<>();

        private Frame(BgfxNativeView camera, double seconds, Environment environment, List<BodyLight> lights) {
            this.camera = camera;
            this.seconds = seconds;
            this.environment = environment;
            for (var light : lights) {
                bodies.add(new BodySource(light).data);
            }
        }

        float @Nullable [] computeUniforms(ModelInstance instance, float[] transform) {
            var box = worldBox(instance.model(), transform);
            var center = new Vec3(
                    (box.getMinX() + box.getMaxX()) * .5f,
                    (box.getMinY() + box.getMaxY()) * .5f,
                    (box.getMinZ() + box.getMaxZ()) * .5f);
            var owner = sources.findLevel(center);
            if (owner == null) {
                return null;
            }
            var scene = sources.getScene(owner);
            if (nodeStack.length < scene.getNodeCount()) {
                nodeStack = new int[scene.getNodeCount()];
            }
            var leaves = scene.collectLeaves(box, nodeStack);
            if (leaves.length == 0) {
                return null;
            }
            var sphere = litSphere(instance.model(), transform, box, center);
            var sunAffect = NativeModelLighting.isSunAffect(scene, leaves);
            var flags = new NativeLightReceiver.Flags(
                    2,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    sunAffect,
                    true,
                    false,
                    1f,
                    NativeModelLighting.MAX_LIGHTS);
            var view = views.computeIfAbsent(
                    owner, name -> sources.openReceiverView(name, camera, false, true, false, false));
            reaching.clear();
            for (var body : bodies) {
                var source = body.getSource();
                var reach = source.radius() + sphere.radius();
                var offset = source.position().minus(center);
                if (offset.dot(offset) <= reach * reach) {
                    reaching.add(body);
                }
            }
            var count = view.updateModel(flags, sphere, leaves, cache, seconds, reaching);
            var beast = scene.isBeastEnvironment();
            var rule = environment.rule();
            var ordinaryScale = 1f;
            if (rule.isBeastScaled() && beast) {
                var location = transformPoint(
                        transform, new Vec3(0f, 0f, instance.model().lowest()));
                if (scene.getZoneLight(scene.pointRegion(location).zone()).sunAffect()) {
                    ordinaryScale = environment.beastLightmap2();
                }
            }
            state.push();
            try {
                for (var slot = 0; slot < count; slot++) {
                    var data = cache.getLight(slot);
                    Input input;
                    if (data.getActor() instanceof BodySource body) {
                        input = new Input(
                                0, body.light.color(), body.light.position(), NO_DIRECTION, body.light.radius());
                    } else {
                        var source = (BgfxNativeLightSources.Source) data.getActor();
                        var cached = data.getSource();
                        input = new Input(
                                cached.effect(),
                                source.getModelColor(),
                                cached.position(),
                                source.getDirection().times(-1f),
                                cached.radius());
                    }
                    state.setLight(slot, input, false, true, !rule.isSunScaled(), 1f, ordinaryScale, sphere);
                }
                var zone = NativeModelLighting.getZoneAmbient(scene, leaves, 0, seconds);
                var actorAmbient = beast ? environment.beastActorAmbient() : environment.actorAmbient();
                return pack(state, count, NativeModelLighting.getAmbient(sunAffect, actorAmbient, zone));
            } finally {
                state.pop(false);
            }
        }

        @Nullable
        Vec3 computeAmbient(Box box) {
            var center = new Vec3(
                    (box.getMinX() + box.getMaxX()) * .5f,
                    (box.getMinY() + box.getMaxY()) * .5f,
                    (box.getMinZ() + box.getMaxZ()) * .5f);
            var owner = sources.findLevel(center);
            if (owner == null) {
                return null;
            }
            var scene = sources.getScene(owner);
            if (nodeStack.length < scene.getNodeCount()) {
                nodeStack = new int[scene.getNodeCount()];
            }
            return NativeModelLighting.getActorAmbient(
                    scene, box, nodeStack, environment.actorAmbient(), environment.beastActorAmbient(), seconds);
        }

        private Box worldBox(SpawnModelData model, float[] transform) {
            var local = bounds.computeIfAbsent(model.key(), _ -> localBounds(model));
            return new Box(
                    new Box(new Vec3(local[0], local[1], local[2]), new Vec3(local[3], local[4], local[5])), transform);
        }

        private static USphere litSphere(SpawnModelData model, float[] transform, Box box, Vec3 center) {
            var sphere = model.litSphere();
            if (sphere == null) {
                var extent = box.getMax().minus(box.getMin()).times(.5f);
                return new USphere(center, (float) Math.sqrt(extent.dot(extent)));
            }
            var scale = 0f;
            for (var column = 0; column < 3; column++) {
                var at = column * 4;
                scale = Math.max(
                        scale,
                        transform[at] * transform[at]
                                + transform[at + 1] * transform[at + 1]
                                + transform[at + 2] * transform[at + 2]);
            }
            return new USphere(
                    transformPoint(transform, sphere.location()), sphere.radius() * (float) Math.sqrt(scale));
        }
    }

    private static Vec3 transformPoint(float[] m, Vec3 p) {
        return new Vec3(
                m[0] * p.getX() + m[4] * p.getY() + m[8] * p.getZ() + m[12],
                m[1] * p.getX() + m[5] * p.getY() + m[9] * p.getZ() + m[13],
                m[2] * p.getX() + m[6] * p.getY() + m[10] * p.getZ() + m[14]);
    }

    private static float[] localBounds(SpawnModelData model) {
        var vertices = model.vertices();
        var result = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        for (var at = 0; at + 2 < vertices.length; at += SpawnModelData.FLOATS) {
            for (var axis = 0; axis < 3; axis++) {
                result[axis] = Math.min(result[axis], vertices[at + axis]);
                result[3 + axis] = Math.max(result[3 + axis], vertices[at + axis]);
            }
        }
        if (result[0] > result[3]) {
            return new float[6];
        }
        return result;
    }

    private static final class BodySource implements NativeActorLightCache.Actor {
        private final BodyLight light;
        private final Data data;

        private BodySource(BodyLight light) {
            this.light = light;
            data = new Data(
                    this,
                    new NativeLightCollector.Source(this, light.position(), light.radius(), 0, false, 0),
                    NO_DIRECTION,
                    light.brightness(),
                    0);
        }

        @Override
        public int getObjectIndex() {
            return -1;
        }

        @Override
        public Object getClassIdentity() {
            return BodySource.class;
        }

        @Override
        public boolean isDeleted() {
            return false;
        }

        @Override
        public boolean isMovableSun() {
            return false;
        }

        @Override
        public @Nullable Object getLevelIdentity() {
            return null;
        }

        @Override
        public Data getLightData() {
            return data;
        }
    }
}
