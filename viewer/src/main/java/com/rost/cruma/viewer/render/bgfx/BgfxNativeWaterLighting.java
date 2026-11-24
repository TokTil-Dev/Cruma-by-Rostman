package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.NativeActorLightCache;
import com.rost.cruma.core.world.NativeLightReceiver;
import com.rost.cruma.core.world.NativeStaticMeshLightingFeatures;
import com.rost.cruma.core.world.NativeStaticMeshLightingInputs;
import com.rost.cruma.viewer.render.bgfx.BgfxNativeLightSources.ReceiverView;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class BgfxNativeWaterLighting {
    static final int VECTORS = 21;
    static final float[] DISABLED = new float[VECTORS * 4];

    private final BgfxNativeLightSources sources;
    private final BgfxNativeLightState state = new BgfxNativeLightState();
    private final Map<String, NativeActorLightCache> caches = new HashMap<>();
    private final Map<NativeLightReceiver, String> actorKeys = new IdentityHashMap<>();
    private final Map<String, String> ownerKeys = new IdentityHashMap<>();

    BgfxNativeWaterLighting(BgfxNativeLightSources sources) {
        this.sources = sources;
    }

    record Draw(float[] uniforms, NativeStaticMeshLightingFeatures features) {}

    private record Key(String actor, NativeStaticMeshLightingInputs inputs) {}

    static float[] pack(BgfxNativeLightState state, NativeStaticMeshLightingFeatures features) {
        var result = new float[VECTORS * 4];
        result[0] = 1;
        result[1] = features.vertexColor() ? 1 : 0;
        result[2] = features.dynamicLight() ? 1 : 0;
        result[3] = features.lightmap() ? 1 : 0;
        var vertex = state.getVertexConstants();
        var pixel = state.getPixelConstants();
        System.arraycopy(vertex.flags, 0, result, 4, 4);
        System.arraycopy(pixel.flags, 0, result, 8, 4);
        System.arraycopy(pixel.sun, 0, result, 12, 4);
        var direction = state.getSunDirection();
        result[16] = -direction.getX();
        result[17] = -direction.getY();
        result[18] = -direction.getZ();
        System.arraycopy(vertex.positions, 0, result, 20, 16);
        System.arraycopy(vertex.colors, 0, result, 36, 16);
        System.arraycopy(vertex.attenuation, 0, result, 52, 16);
        System.arraycopy(pixel.colors, 0, result, 68, 8);
        System.arraycopy(pixel.attenuation, 0, result, 76, 8);
        return result;
    }

    View open(BgfxNativeView camera) {
        return new View(camera);
    }

    void remove(String owner) {
        var prefix = owner.toLowerCase(Locale.ROOT) + ".";
        caches.keySet().removeIf(actor -> actor.startsWith(prefix));
        actorKeys.values().removeIf(actor -> actor.startsWith(prefix));
        ownerKeys.values().removeIf(value -> value.equals(owner.toLowerCase(Locale.ROOT)));
    }

    void clear() {
        caches.clear();
        actorKeys.clear();
        ownerKeys.clear();
    }

    final class View {
        private final BgfxNativeView camera;
        private final Map<String, ReceiverView> levels = new HashMap<>();
        private final Map<Key, Draw> draws = new HashMap<>();
        private final Map<String, NativeActorLightCache> updated = new HashMap<>();

        private View(BgfxNativeView camera) {
            this.camera = camera;
        }

        @Nullable
        Draw prepare(
                String owner,
                NativeLightReceiver receiver,
                NativeStaticMeshLightingInputs inputs,
                double seconds,
                float environmentScale) {
            return prepare(owner, receiver, inputs, seconds, environmentScale, false);
        }

        @Nullable
        Draw prepare(
                String owner,
                NativeLightReceiver receiver,
                NativeStaticMeshLightingInputs inputs,
                double seconds,
                float environmentScale,
                boolean useTangent) {
            return prepare(owner, receiver, inputs, seconds, environmentScale, useTangent, () -> {});
        }

        @Nullable
        Draw prepare(
                String owner,
                NativeLightReceiver receiver,
                NativeStaticMeshLightingInputs inputs,
                double seconds,
                float environmentScale,
                boolean useTangent,
                Runnable updateVertexStreams) {
            var levelKey = ownerKeys.computeIfAbsent(owner, value -> value.toLowerCase(Locale.ROOT));
            if (!receiver.getFlags().staticActor()
                    || receiver.getFlags().unlitCheck()
                    || !sources.hasNormalizedLevel(levelKey)
                    || !inputs.acceptedInstance()
                            && inputs.rootEnvironmentEntry()
                            && (inputs.rootEnvironmentSource() == null
                                    || sources.getSource(inputs.rootEnvironmentSource()) == null)) {
                return null;
            }
            var actor = actorKeys.computeIfAbsent(
                    receiver, value -> value.getActorId().toLowerCase(Locale.ROOT));
            var key = new Key(actor, inputs);
            var existing = draws.get(key);
            if (existing != null) {
                return existing;
            }
            var level = levels.computeIfAbsent(
                    levelKey, name -> sources.openReceiverView(name, camera, false, true, false, false));
            var cache = updated.get(actor);
            if (cache == null) {
                cache = caches.computeIfAbsent(actor, _ -> new NativeActorLightCache());
                level.update(receiver, cache, seconds, true, (light, present) -> true);
                updated.put(actor, cache);
            }
            updateVertexStreams.run();
            var modulate = !receiver.getFlags().unlit();
            var selection = level.select(receiver, cache, inputs, Integer.MAX_VALUE);
            var features = inputs.selectFeatures(modulate, useTangent, selection.size() != 0);
            state.push();
            try {
                level.submitSelectedLights(
                        cache,
                        state,
                        receiver.getWorldSphere(),
                        true,
                        modulate,
                        receiver.getLightmapScale().resolve(environmentScale),
                        inputs);
                var result = new Draw(pack(state, features), features);
                draws.put(key, result);
                return result;
            } finally {
                state.pop(true);
            }
        }
    }
}
