package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.Light;
import com.rost.cruma.core.world.MaterialClient;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class BgfxLocalLightSet {
    static final int MAX_LIGHTS = 16;
    static final int PARAM_FLOATS = MAX_LIGHTS * 12;
    private static final float REALISTIC_DIRECT_STRENGTH = 0.42f;
    private static final float MIN_RADIUS = 1f;
    private static final float MAX_SELECTION_DISTANCE = 24_000f;
    private static final float SELECTION_RESCAN_DISTANCE = 1_024f;

    private final Map<String, List<Light>> lightsByOwner = new HashMap<>();
    private final Map<String, List<Light>> animatedLightsByOwner = new TreeMap<>();
    private final AnimatedLightIntensity animatedIntensity = new AnimatedLightIntensity();
    private long lightsRevision;
    private long selectionLightsRevision = Long.MIN_VALUE;
    private float selectionAnchorX;
    private float selectionAnchorY;
    private float selectionAnchorZ;
    private Snapshot selectionCache = Snapshot.disabled();

    void publish(String owner, List<Light> lights) {
        publish(owner, lights, List.of());
    }

    void publish(String owner, List<Light> lights, List<Light> animatedLights) {
        var stable = lights.stream()
                .filter(light -> !light.isAnimated() && light.radius() >= MIN_RADIUS)
                .toList();
        var stablePrevious = stable.isEmpty() ? lightsByOwner.remove(owner) : lightsByOwner.put(owner, stable);
        var animated = animatedLights.stream()
                .filter(light -> light.isAnimated() && light.radius() >= MIN_RADIUS)
                .toList();
        var animatedPrevious =
                animated.isEmpty() ? animatedLightsByOwner.remove(owner) : animatedLightsByOwner.put(owner, animated);
        if (stablePrevious != null || !stable.isEmpty() || animatedPrevious != null || !animated.isEmpty()) {
            lightsRevision++;
        }
    }

    void evict(Set<String> owners) {
        var changed = false;
        for (var owner : owners) {
            changed |= lightsByOwner.remove(owner) != null;
            changed |= animatedLightsByOwner.remove(owner) != null;
        }
        if (changed) {
            lightsRevision++;
        }
    }

    Snapshot resolve(Vec3 camera, boolean enabled) {
        return resolve(camera, enabled, 0f);
    }

    Snapshot resolve(Vec3 camera, boolean enabled, float levelTimeSeconds) {
        return resolve(camera, enabled, levelTimeSeconds, MaterialClient.UNKNOWN);
    }

    Snapshot resolve(Vec3 camera, boolean enabled, float levelTimeSeconds, MaterialClient client) {
        if (!enabled || (lightsByOwner.isEmpty() && animatedLightsByOwner.isEmpty())) {
            return Snapshot.disabled();
        }
        var steady = resolveSteady(camera);
        if (animatedLightsByOwner.isEmpty()) {
            return steady;
        }

        var best = new SelectedLight[MAX_LIGHTS];
        for (var light : steady.lights()) {
            insert(best, new SelectedLight(light, score(light, camera, 1f), 1f));
        }
        for (var animated : animatedLightsByOwner.values()) {
            for (var light : animated) {
                var intensity = animatedIntensity.sample(light, levelTimeSeconds, client);
                if (intensity <= 0f || !isWithinSelectionReach(light, camera)) {
                    continue;
                }
                insert(best, new SelectedLight(light, score(light, camera, intensity), intensity));
            }
        }
        return createSnapshot(best);
    }

    void clear() {
        if (!lightsByOwner.isEmpty() || !animatedLightsByOwner.isEmpty()) {
            lightsByOwner.clear();
            animatedLightsByOwner.clear();
            lightsRevision++;
        }
        animatedIntensity.reset();
        selectionCache = Snapshot.disabled();
    }

    private Snapshot resolveSteady(Vec3 camera) {
        if (lightsByOwner.isEmpty()) {
            return Snapshot.disabled();
        }
        var anchorDx = camera.getX() - selectionAnchorX;
        var anchorDy = camera.getY() - selectionAnchorY;
        var anchorDz = camera.getZ() - selectionAnchorZ;
        var rescanDistanceSquared = (double) SELECTION_RESCAN_DISTANCE * SELECTION_RESCAN_DISTANCE;
        if (selectionLightsRevision == lightsRevision
                && (double) anchorDx * anchorDx + (double) anchorDy * anchorDy + (double) anchorDz * anchorDz
                        <= rescanDistanceSquared) {
            return selectionCache;
        }

        var best = new SelectedLight[MAX_LIGHTS];
        for (var lights : lightsByOwner.values()) {
            for (var light : lights) {
                if (!isWithinSelectionReach(light, camera)) {
                    continue;
                }
                var luminance = luminance(light);
                if (luminance <= 0.01f) {
                    continue;
                }
                insert(best, new SelectedLight(light, score(light, camera, 1f), 1f));
            }
        }
        selectionAnchorX = camera.getX();
        selectionAnchorY = camera.getY();
        selectionAnchorZ = camera.getZ();
        selectionLightsRevision = lightsRevision;
        selectionCache = createSnapshot(best);
        return selectionCache;
    }

    private static Snapshot createSnapshot(SelectedLight[] selected) {
        var lights = new ArrayList<Light>(MAX_LIGHTS);
        var params = new float[PARAM_FLOATS];
        for (var candidate : selected) {
            if (candidate == null) {
                continue;
            }
            var index = lights.size();
            lights.add(candidate.light());
            put(params, index, candidate.light(), candidate.intensity());
        }
        return new Snapshot(List.copyOf(lights), params);
    }

    private static void put(float[] params, int index, Light light, float intensity) {
        var positionOffset = index * 4;
        params[positionOffset] = light.position().getX();
        params[positionOffset + 1] = light.position().getY();
        params[positionOffset + 2] = light.position().getZ();
        params[positionOffset + 3] = light.radius();
        var colorOffset = MAX_LIGHTS * 4 + positionOffset;
        params[colorOffset] = light.color().getX();
        params[colorOffset + 1] = light.color().getY();
        params[colorOffset + 2] = light.color().getZ();
        params[colorOffset + 3] = REALISTIC_DIRECT_STRENGTH * intensity;
        var directionOffset = MAX_LIGHTS * 8 + positionOffset;
        params[directionOffset] = light.direction().getX();
        params[directionOffset + 1] = light.direction().getY();
        params[directionOffset + 2] = light.direction().getZ();
        params[directionOffset + 3] = isSpot(light) ? 1f - light.cone() / 256f : -1f;
    }

    private static float luminance(Light light) {
        return 0.2126f * light.color().getX()
                + 0.7152f * light.color().getY()
                + 0.0722f * light.color().getZ();
    }

    private static boolean isWithinSelectionReach(Light light, Vec3 camera) {
        var distanceSquared = distanceSquared(light, camera);
        var reach = light.radius() + MAX_SELECTION_DISTANCE;
        return distanceSquared <= (double) reach * reach;
    }

    private static double score(Light light, Vec3 camera, float intensity) {
        return luminance(light)
                * intensity
                * light.radius()
                * light.radius()
                / Math.max(distanceSquared(light, camera), 1.0);
    }

    private static double distanceSquared(Light light, Vec3 camera) {
        var dx = light.position().getX() - camera.getX();
        var dy = light.position().getY() - camera.getY();
        var dz = light.position().getZ() - camera.getZ();
        return (double) dx * dx + (double) dy * dy + (double) dz * dz;
    }

    private static void insert(SelectedLight[] best, SelectedLight candidate) {
        for (var index = 0; index < best.length; index++) {
            if (best[index] == null || isPreferred(candidate, best[index])) {
                for (var move = best.length - 1; move > index; move--) {
                    best[move] = best[move - 1];
                }
                best[index] = candidate;
                return;
            }
        }
    }

    private static boolean isPreferred(SelectedLight candidate, SelectedLight current) {
        var scoreOrder = Double.compare(candidate.score(), current.score());
        if (scoreOrder != 0) {
            return scoreOrder > 0;
        }
        return compareAuthoredLight(candidate.light(), current.light()) < 0;
    }

    private static int compareAuthoredLight(Light left, Light right) {
        var order = Float.compare(left.position().getX(), right.position().getX());
        if (order == 0) {
            order = Float.compare(left.position().getY(), right.position().getY());
        }
        if (order == 0) {
            order = Float.compare(left.position().getZ(), right.position().getZ());
        }
        if (order == 0) {
            order = Float.compare(left.radius(), right.radius());
        }
        if (order == 0) {
            order = Float.compare(left.color().getX(), right.color().getX());
        }
        if (order == 0) {
            order = Float.compare(left.color().getY(), right.color().getY());
        }
        if (order == 0) {
            order = Float.compare(left.color().getZ(), right.color().getZ());
        }
        if (order == 0) {
            order = Integer.compare(left.lightEffect(), right.lightEffect());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getX(), right.direction().getX());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getY(), right.direction().getY());
        }
        if (order == 0) {
            order = Float.compare(left.direction().getZ(), right.direction().getZ());
        }
        if (order == 0) {
            order = Integer.compare(left.cone(), right.cone());
        }
        if (order == 0) {
            order = Integer.compare(left.lightType(), right.lightType());
        }
        if (order == 0) {
            order = Integer.compare(left.period(), right.period());
        }
        if (order == 0) {
            order = Integer.compare(left.phase(), right.phase());
        }
        return order;
    }

    private static boolean isSpot(Light light) {
        return light.lightEffect() == 8 || light.lightEffect() == 12;
    }

    private record SelectedLight(Light light, double score, float intensity) {}

    record Snapshot(List<Light> lights, float[] params) {
        private static final Snapshot DISABLED = new Snapshot(List.of(), new float[0]);

        static Snapshot disabled() {
            return DISABLED;
        }

        boolean available() {
            return !lights.isEmpty();
        }

        int lightCount() {
            return lights.size();
        }
    }
}
