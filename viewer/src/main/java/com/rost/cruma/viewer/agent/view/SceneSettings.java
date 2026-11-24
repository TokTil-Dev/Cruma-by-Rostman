package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.application.UiContext.Rendering;
import com.rost.cruma.viewer.ui.SceneSettingsDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

final class SceneSettings {
    private static final String[] MOONS = {"auto", "moon_0", "moon_1", "moon_2", "moon_3", "moon_4"};
    private static final String[] MODELS = {"authored_hybrid", "realistic_relighting", "global_illumination"};
    private static final String[] REFLECTIONS = {"none", "all", "sky"};
    private static final String[] EMITTER_DISTANCES = {"authored", "custom"};
    private static final int FARTHEST = 2_000_000;
    private static final Map<String, Setting> SETTINGS = settings();

    private SceneSettings() {}

    @FunctionalInterface
    private interface Writer {
        void write(Rendering settings, Object value) throws ToolProblem;
    }

    private record Setting(String name, String range, Function<Rendering, Object> read, Writer write) {}

    static JSONObject values(Rendering settings) {
        var values = new JSONObject();
        for (var setting : SETTINGS.values()) {
            values.put(setting.name(), setting.read().apply(settings));
        }
        return values;
    }

    static JSONObject ranges() {
        var ranges = new JSONObject();
        for (var setting : SETTINGS.values()) {
            ranges.put(setting.name(), setting.range());
        }
        return ranges;
    }

    static void apply(Rendering settings, JSONObject asked) throws ToolProblem {
        check(settings, asked, false);
        for (var entry : asked.entrySet()) {
            setting(entry.getKey()).write().write(settings, entry.getValue());
        }
    }

    static void check(Rendering settings, JSONObject asked, boolean afterReset) throws ToolProblem {
        var probe = new Rendering();
        if (afterReset) {
            SceneSettingsDefaults.restore(probe);
        } else {
            copy(settings, probe);
        }
        probe.stats.customRayTracingAvailable = settings.stats.customRayTracingAvailable;
        probe.stats.customSunShadowsAvailable = settings.stats.customSunShadowsAvailable;
        probe.stats.customLocalLightShadowsAvailable = settings.stats.customLocalLightShadowsAvailable;
        for (var entry : asked.entrySet()) {
            setting(entry.getKey()).write().write(probe, entry.getValue());
        }
    }

    private static Setting setting(String name) throws ToolProblem {
        var setting = SETTINGS.get(name.toLowerCase(Locale.ROOT));
        if (setting == null) {
            throw new ToolProblem(
                    "There is no scene setting \"" + name + "\". Call view_settings with show scene for the names.");
        }
        return setting;
    }

    private static void copy(Rendering from, Rendering to) {
        to.customLighting = from.customLighting;
        to.customSunShadows = from.customSunShadows;
        to.l2WaterShader = from.l2WaterShader;
        to.waterBackTex = from.waterBackTex;
    }

    private static Map<String, Setting> settings() {
        var list = List.of(
                flag("textures", r -> r.textures, (r, on) -> r.textures = on),
                flag("passable", r -> r.passable, (r, on) -> r.passable = on),
                number(
                        "terrain_detail",
                        0,
                        1024,
                        "px on screen, 0 keeps detail everywhere",
                        r -> r.terrainDetailPixels.get(),
                        (r, value) -> r.terrainDetailPixels.set(value)),
                number(
                        "impostor_distance",
                        1,
                        20,
                        "times the client's distance",
                        r -> r.impostorDistanceMultiplier.get(),
                        (r, value) -> r.impostorDistanceMultiplier.set(value)),
                number(
                        "grass_distance",
                        1,
                        20,
                        "times the usual distance",
                        r -> r.grassDistanceMultiplier.get(),
                        (r, value) -> r.grassDistanceMultiplier.set(value)),
                flag("frustum_culling", r -> r.culling, (r, on) -> r.culling = on),
                flag(
                        "mesh_distance_culling",
                        r -> r.staticMeshesDistCulling,
                        (r, on) -> r.staticMeshesDistCulling = on),
                whole(
                        "mesh_distance",
                        0,
                        FARTHEST,
                        "units",
                        r -> r.staticMeshesCullingDist.get(),
                        (r, value) -> r.staticMeshesCullingDist.set(value)),
                whole(
                        "view_distance",
                        1000,
                        FARTHEST,
                        "units",
                        r -> r.viewDistance.get(),
                        (r, value) -> r.viewDistance.set(value)),
                flag("surface_tint", r -> r.debugTint, (r, on) -> r.debugTint = on),
                flag("custom_lighting", r -> r.customLighting, (r, on) -> r.customLighting = on),
                flag(
                        "protect_interiors",
                        r -> r.customProtectAuthoredInteriors,
                        (r, on) -> r.customProtectAuthoredInteriors = on),
                new Setting(
                        "lighting_model",
                        String.join(", ", MODELS) + ". global_illumination needs Metal ray tracing",
                        r -> MODELS[Math.clamp(r.customLightingModel.get(), 0, MODELS.length - 1)],
                        (r, value) -> {
                            var model = choice("lighting_model", value, MODELS);
                            if (model == 2 && !r.stats.customRayTracingAvailable) {
                                throw new ToolProblem(
                                        "global_illumination needs Metal ray tracing, which this viewer has not.");
                            }
                            r.customLightingModel.set(model);
                        }),
                number(
                        "indirect_light",
                        0,
                        2,
                        "times, for global_illumination",
                        r -> r.customGiStrength.get(),
                        (r, value) -> r.customGiStrength.set(value)),
                number(
                        "hybrid_shadow",
                        0,
                        100,
                        "percent, for authored_hybrid",
                        r -> r.customAuthoredShadowStrength.get(),
                        (r, value) -> r.customAuthoredShadowStrength.set(value)),
                available(
                        "sun_cascades",
                        r -> r.customSunShadows,
                        r -> r.stats.customSunShadowsAvailable,
                        (r, on) -> r.customSunShadows = on),
                available(
                        "local_light_shadows",
                        r -> r.customLocalLightShadows,
                        r -> r.stats.customLocalLightShadowsAvailable,
                        (r, on) -> r.customLocalLightShadows = on),
                available(
                        "ray_traced_sun",
                        r -> r.customRayTracedSunReference,
                        r -> r.stats.customRayTracingAvailable && r.customSunShadows,
                        (r, on) -> r.customRayTracedSunReference = on),
                new Setting(
                        "moon",
                        String.join(", ", MOONS) + ". auto follows the sky",
                        r -> MOONS[Math.clamp(r.moonVariant.get(), 0, MOONS.length - 1)],
                        (r, value) -> r.moonVariant.set(choice("moon", value, MOONS))),
                number(
                        "sun_size",
                        10,
                        400,
                        "world size of the sun",
                        r -> r.realSkySunSize.get(),
                        (r, value) -> r.realSkySunSize.set(value)),
                number(
                        "gamma",
                        .5,
                        2,
                        "1 is neutral",
                        r -> r.displayGamma.get(),
                        (r, value) -> r.displayGamma.set(value)),
                flag("bloom", r -> r.bloom, (r, on) -> r.bloom = on),
                whole(
                        "particle_distance",
                        0,
                        FARTHEST,
                        "units, 0 turns effects off",
                        r -> r.particleVisibilityDistance.get(),
                        (r, value) -> r.particleVisibilityDistance.set(value)),
                new Setting(
                        "emitter_distance",
                        String.join(", ", EMITTER_DISTANCES) + ". authored keeps each emitter's own limit",
                        r -> EMITTER_DISTANCES[Math.clamp(r.emitterDistanceMode.get(), 0, 1)],
                        (r, value) -> r.emitterDistanceMode.set(choice("emitter_distance", value, EMITTER_DISTANCES))),
                flag("realistic_water", r -> r.l2WaterShader && r.waterBackTex, (r, on) -> {
                    r.l2WaterShader = on;
                    r.waterBackTex = on;
                }),
                new Setting(
                        "water_reflection",
                        String.join(", ", REFLECTIONS) + ". Only with realistic_water off",
                        r -> REFLECTIONS[Math.clamp(r.l2WaterEffectType.get(), 0, REFLECTIONS.length - 1)],
                        (r, value) -> {
                            var reflection = choice("water_reflection", value, REFLECTIONS);
                            if (r.l2WaterShader && r.waterBackTex) {
                                throw new ToolProblem(
                                        "water_reflection works with realistic_water off, as in the drawer.");
                            }
                            r.l2WaterEffectType.set(reflection);
                        }),
                flag("music", r -> r.musicEnabled, (r, on) -> r.musicEnabled = on),
                number(
                        "music_volume",
                        0,
                        100,
                        "percent",
                        r -> r.musicVolume.get(),
                        (r, value) -> r.musicVolume.set(value)),
                number(
                        "replay_gap",
                        0,
                        300,
                        "seconds",
                        r -> r.musicWaitSeconds.get(),
                        (r, value) -> r.musicWaitSeconds.set(value)),
                number(
                        "idle_gap",
                        0,
                        300,
                        "seconds",
                        r -> r.musicIdleWaitSeconds.get(),
                        (r, value) -> r.musicIdleWaitSeconds.set(value)),
                number(
                        "music_fade",
                        0,
                        10,
                        "seconds",
                        r -> r.musicFadeSeconds.get(),
                        (r, value) -> r.musicFadeSeconds.set(value)),
                flag("ambient", r -> r.soundEnabled, (r, on) -> r.soundEnabled = on),
                number(
                        "ambient_volume",
                        0,
                        100,
                        "percent",
                        r -> r.soundVolume.get(),
                        (r, value) -> r.soundVolume.set(value)),
                whole(
                        "max_voices",
                        1,
                        64,
                        "sounds at once",
                        r -> r.soundMaxVoices.get(),
                        (r, value) -> r.soundMaxVoices.set(value)));
        var settings = new LinkedHashMap<String, Setting>();
        for (var setting : list) {
            settings.put(setting.name(), setting);
        }
        return settings;
    }

    @FunctionalInterface
    private interface Switch {
        void set(Rendering settings, boolean on);
    }

    @FunctionalInterface
    private interface Amount {
        void set(Rendering settings, float value);
    }

    @FunctionalInterface
    private interface Count {
        void set(Rendering settings, int value);
    }

    private static Setting flag(String name, Function<Rendering, Boolean> read, Switch write) {
        return new Setting(name, "true or false", read::apply, (r, value) -> write.set(r, parseFlag(name, value)));
    }

    private static Setting available(
            String name, Function<Rendering, Boolean> read, Function<Rendering, Boolean> can, Switch write) {
        return new Setting(name, "true or false, where the graphics card can", read::apply, (r, value) -> {
            var on = parseFlag(name, value);
            if (on && !can.apply(r)) {
                throw new ToolProblem(name
                        + " cannot run in this viewer: its graphics card or its other settings do not allow"
                        + " it, so the drawer shows it dimmed.");
            }
            write.set(r, on);
        });
    }

    private static Setting number(
            String name, double least, double most, String unit, Function<Rendering, Float> read, Amount write) {
        return new Setting(
                name,
                number(least) + " to " + number(most) + ", " + unit,
                r -> Math.round(read.apply(r) * 100) / 100.0,
                (r, value) -> write.set(r, (float) Math.clamp(number(name, value), least, most)));
    }

    private static Setting whole(
            String name, int least, int most, String unit, Function<Rendering, Integer> read, Count write) {
        return new Setting(
                name,
                least + " to " + most + ", " + unit,
                read::apply,
                (r, value) -> write.set(r, (int) Math.clamp(Math.round(number(name, value)), least, most)));
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static boolean parseFlag(String name, Object value) throws ToolProblem {
        if (value instanceof Boolean on) {
            return on;
        }
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw new ToolProblem(name + " takes true or false.");
    }

    private static double number(String name, Object value) throws ToolProblem {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.strip());
            } catch (NumberFormatException wrong) {
            }
        }
        throw new ToolProblem(name + " takes a number.");
    }

    private static int choice(String name, Object value, String[] choices) throws ToolProblem {
        var text = String.valueOf(value).strip().toLowerCase(Locale.ROOT);
        for (var index = 0; index < choices.length; index++) {
            if (choices[index].equals(text)) {
                return index;
            }
        }
        throw new ToolProblem(name + " takes " + String.join(", ", choices) + ", not \"" + value + "\".");
    }
}
