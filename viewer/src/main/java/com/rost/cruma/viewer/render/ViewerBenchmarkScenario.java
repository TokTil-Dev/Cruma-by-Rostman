package com.rost.cruma.viewer.render;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.application.UiContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ViewerBenchmarkScenario {
    private static final String RESOURCE = "/bgfx/benchmark-scenarios.json";

    private final boolean enabled;
    private final String id;
    private final int displayWidth;
    private final int displayHeight;
    private final Pose initial;
    private final List<Pose> path;
    private final float simulationTimeSeconds;
    private final float simulationTimeStepSeconds;
    private final float timeOfDay;
    private final int viewDistance;
    private final int staticMeshDistance;
    private final boolean staticMeshCulling;
    private final Boolean terrain, staticMeshes, csg, passable;
    private final boolean bloom;
    private final int particleDistance;
    private final boolean waterBackTex;
    private final boolean waterShader;
    private final int waterEffectType;
    private final boolean customLighting;
    private final boolean customSunShadows;
    private final boolean customLocalLightShadows;
    private final boolean customRayTracedSunReference;
    private final Map<String, Object> contract;

    private ViewerBenchmarkScenario() {
        enabled = false;
        id = "disabled";
        displayWidth = 0;
        displayHeight = 0;
        initial = new Pose(0, 0, 0, 0, 0, 1);
        path = List.of(initial);
        simulationTimeSeconds = 0f;
        simulationTimeStepSeconds = 0f;
        timeOfDay = 12f;
        viewDistance = 0;
        staticMeshDistance = 0;
        staticMeshCulling = false;
        terrain = null;
        staticMeshes = null;
        csg = null;
        passable = null;
        bloom = false;
        particleDistance = 0;
        waterBackTex = false;
        waterShader = false;
        waterEffectType = 0;
        customLighting = false;
        customSunShadows = false;
        customLocalLightShadows = false;
        customRayTracedSunReference = false;
        contract = Map.of();
    }

    private ViewerBenchmarkScenario(JSONObject source) {
        enabled = true;
        id = source.getString("id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("benchmark scenario id cannot be blank");
        }
        var display = requireArray(source, "display", 2);
        displayWidth = requirePositive(display.getIntValue(0), "display width");
        displayHeight = requirePositive(display.getIntValue(1), "display height");
        initial = parsePose(requireArray(source, "camera", 5), 1);
        var poses = new ArrayList<Pose>();
        var sourcePath = source.getJSONArray("camera_path");
        if (sourcePath != null) {
            for (var value : sourcePath) {
                poses.add(parsePose((JSONArray) value, 6));
            }
        }
        if (poses.isEmpty()) {
            poses.add(initial);
        }
        path = List.copyOf(poses);
        simulationTimeSeconds = nonNegative(source.getFloatValue("simulation_time_seconds"), "simulation time");
        simulationTimeStepSeconds =
                nonNegative(source.getFloatValue("simulation_time_step_seconds"), "simulation time step");
        timeOfDay = requireInRange(source.getFloatValue("time_of_day"), 0f, 24f, "time of day");
        viewDistance = requirePositive(source.getIntValue("view_distance"), "view distance");
        staticMeshDistance =
                requirePositive(source.getIntValue("static_mesh_culling_distance"), "static mesh distance");
        staticMeshCulling = source.getBooleanValue("static_mesh_distance_culling");
        terrain = optionalBoolean(source, "terrain");
        staticMeshes = optionalBoolean(source, "static_meshes");
        csg = optionalBoolean(source, "csg");
        passable = optionalBoolean(source, "passable");
        bloom = source.getBooleanValue("bloom");
        particleDistance = Math.max(0, source.getIntValue("particle_distance"));
        waterBackTex = source.getBooleanValue("water_back_tex");
        waterShader = source.getBooleanValue("water_shader");
        waterEffectType = source.getIntValue("water_effect_type");
        customLighting = source.getBooleanValue("custom_lighting");
        customSunShadows = source.getBooleanValue("custom_sun_shadows");
        customLocalLightShadows = source.getBooleanValue("custom_local_light_shadows");
        customRayTracedSunReference = source.getBooleanValue("custom_ray_traced_sun_reference");
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("schema", "cruma.viewer.bgfx-scenario-contract/v1");
        metadata.put("id", id);
        metadata.put("display_width", displayWidth);
        metadata.put("display_height", displayHeight);
        metadata.put("initial_camera", initial);
        metadata.put("camera_path", path);
        metadata.put("simulation_time_seconds", simulationTimeSeconds);
        metadata.put("simulation_time_step_seconds", simulationTimeStepSeconds);
        metadata.put("time_of_day", timeOfDay);
        metadata.put("view_distance", viewDistance);
        metadata.put("static_mesh_culling_distance", staticMeshDistance);
        metadata.put("static_mesh_distance_culling", staticMeshCulling);
        metadata.put("bloom", bloom);
        metadata.put("particle_distance", particleDistance);
        metadata.put("water_back_tex", waterBackTex);
        metadata.put("water_shader", waterShader);
        metadata.put("water_effect_type", waterEffectType);
        metadata.put("custom_lighting", customLighting);
        metadata.put("custom_sun_shadows", customSunShadows);
        metadata.put("custom_local_light_shadows", customLocalLightShadows);
        metadata.put("custom_ray_traced_sun_reference", customRayTracedSunReference);
        contract = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static ViewerBenchmarkScenario load() {
        if (!ViewerBenchmarkProperties.isEnabled()) {
            return new ViewerBenchmarkScenario();
        }
        var requested = System.getProperty(ViewerBenchmarkProperties.SCENARIO, "")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (requested.isEmpty() || requested.equals("UNSPECIFIED")) {
            throw new IllegalArgumentException(ViewerBenchmarkProperties.SCENARIO + " must name a checked-in scenario");
        }
        try (var stream = ViewerBenchmarkScenario.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("missing benchmark scenario resource " + RESOURCE);
            }
            var root = JSON.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            if (!"cruma.viewer.bgfx-scenarios/v1".equals(root.getString("schema"))) {
                throw new IllegalStateException("unexpected benchmark scenario schema");
            }
            for (var value : root.getJSONArray("scenarios")) {
                var candidate = (JSONObject) value;
                if (requested.equals(candidate.getString("id"))) {
                    return new ViewerBenchmarkScenario(candidate);
                }
            }
            throw new IllegalArgumentException("unknown viewer benchmark scenario: " + requested);
        } catch (IOException failure) {
            throw new UncheckedIOException("failed to read benchmark scenarios", failure);
        }
    }

    static ViewerBenchmarkScenario fromJson(JSONObject source) {
        return new ViewerBenchmarkScenario(source);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getDisplayWidth(int fallback) {
        return enabled ? displayWidth : fallback;
    }

    public int getDisplayHeight(int fallback) {
        return enabled ? displayHeight : fallback;
    }

    public float getSimulationTimeSeconds() {
        return simulationTimeSeconds;
    }

    public float getSimulationTimeSeconds(long frame) {
        if (frame < 0L) {
            throw new IllegalArgumentException("benchmark frame cannot be negative");
        }
        return simulationTimeSeconds + simulationTimeStepSeconds * frame;
    }

    public float getSimulationTimeStepSeconds() {
        return simulationTimeStepSeconds;
    }

    public Map<String, Object> getContract() {
        return contract;
    }

    public void applyInitial(Camera camera, UiContext uiContext) {
        if (!enabled) {
            return;
        }
        applyPose(camera, initial);
        var rendering = uiContext.rendering;
        rendering.timeOfDay.set(timeOfDay);
        rendering.viewDistance.set(viewDistance);
        rendering.staticMeshesCullingDist.set(staticMeshDistance);
        rendering.staticMeshesDistCulling = staticMeshCulling;
        if (terrain != null) {
            rendering.terrain = terrain;
        }
        if (staticMeshes != null) {
            rendering.staticMeshes = staticMeshes;
        }
        if (csg != null) {
            rendering.csg = csg;
        }
        if (passable != null) {
            rendering.passable = passable;
        }
        rendering.bloom = bloom;
        rendering.particleVisibilityDistance.set(particleDistance);
        rendering.waterBackTex = waterBackTex;
        rendering.l2WaterShader = waterShader;
        rendering.l2WaterEffectType.set(waterEffectType);
        rendering.customLighting = customLighting;
        rendering.customSunShadows = customSunShadows;
        rendering.customLocalLightShadows = customLocalLightShadows;
        rendering.customRayTracedSunReference = customRayTracedSunReference;
    }

    public void applyFrame(Camera camera, long seenFrames, int warmupFrames) {
        if (!enabled) {
            return;
        }
        if (seenFrames < warmupFrames || path.size() == 1) {
            applyPose(camera, path.getFirst());
            return;
        }
        long cursor = seenFrames - warmupFrames;
        long total = 0L;
        for (var pose : path) {
            total += pose.frames;
        }
        if (total <= 0L) {
            applyPose(camera, path.getLast());
            return;
        }
        cursor %= total;
        for (var index = 0; index < path.size(); index++) {
            var from = path.get(index);
            if (cursor >= from.frames) {
                cursor -= from.frames;
                continue;
            }
            var to = path.get((index + 1) % path.size());
            var alpha = from.frames <= 1 ? 1.0 : cursor / (double) (from.frames - 1);
            applyPose(camera, interpolate(from, to, alpha));
            return;
        }
        applyPose(camera, path.getLast());
    }

    private static Boolean optionalBoolean(JSONObject source, String key) {
        return source.containsKey(key) ? source.getBooleanValue(key) : null;
    }

    private static Pose interpolate(Pose from, Pose to, double alpha) {
        return new Pose(
                lerp(from.x, to.x, alpha),
                lerp(from.y, to.y, alpha),
                lerp(from.z, to.z, alpha),
                lerpAngle(from.yaw, to.yaw, alpha),
                lerp(from.pitch, to.pitch, alpha),
                from.frames);
    }

    private static double lerp(double from, double to, double alpha) {
        return from + (to - from) * alpha;
    }

    private static double lerpAngle(double from, double to, double alpha) {
        var delta = (to - from + 540.0) % 360.0 - 180.0;
        return from + delta * alpha;
    }

    private static void applyPose(Camera camera, Pose pose) {
        camera.setPosition((float) pose.x, (float) pose.y, (float) pose.z);
        camera.setDirection(pose.yaw, pose.pitch);
    }

    private static Pose parsePose(JSONArray values, int minimum) {
        if (values == null || values.size() < minimum) {
            throw new IllegalArgumentException("camera pose requires " + minimum + " values");
        }
        return new Pose(
                requireFinite(values.getDoubleValue(0), "camera x"),
                requireFinite(values.getDoubleValue(1), "camera y"),
                requireFinite(values.getDoubleValue(2), "camera z"),
                requireFinite(values.getDoubleValue(3), "camera yaw"),
                requireInRange(values.getDoubleValue(4), -90.0, 90.0, "camera pitch"),
                minimum > 5 ? requirePositive(values.getIntValue(5), "camera hold frames") : 1);
    }

    private static JSONArray requireArray(JSONObject source, String name, int minimum) {
        var values = source.getJSONArray(name);
        if (values == null || values.size() < minimum) {
            throw new IllegalArgumentException(name + " requires at least " + minimum + " values");
        }
        return values;
    }

    private static int requirePositive(int value, String label) {
        if (value < 1) {
            throw new IllegalArgumentException(label + " must be positive");
        }
        return value;
    }

    private static float requireFinite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value;
    }

    private static double requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value;
    }

    private static float nonNegative(float value, String label) {
        requireFinite(value, label);
        if (value < 0f) {
            throw new IllegalArgumentException(label + " cannot be negative");
        }
        return value;
    }

    private static float requireInRange(float value, float minimum, float maximum, String label) {
        requireFinite(value, label);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be within " + minimum + ".." + maximum);
        }
        return value;
    }

    private static double requireInRange(double value, double minimum, double maximum, String label) {
        requireFinite(value, label);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " must be within " + minimum + ".." + maximum);
        }
        return value;
    }

    public record Pose(double x, double y, double z, double yaw, double pitch, int frames) {}
}
