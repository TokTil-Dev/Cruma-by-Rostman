package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.application.OrthoSlice;
import com.rost.cruma.viewer.config.CutCircle;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.ParticleEmitter;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class ViewControls {
    static final String[] PLAYBACK = {"play", "pause", "step"};
    static final String[] COMPARE = {"start", "save_a", "save_b", "show_a", "show_b", "end"};
    private static final String[] KEPT = {"terrain", "static_meshes", "csg", "none"};
    private static final double LEAST_SPEED = .1;
    private static final double MOST_SPEED = 4;
    private static final float PERCENT = 100f;
    private static final int HISTORY = 120;

    private ViewControls() {}

    static void check(ViewerContext viewer, ToolArgs args) throws ToolProblem {
        var compare = WorldFacts.choice(args, "compare", COMPARE);
        var comparison = viewer.system().getWorkspace().getComparison();
        var locked = "start".equals(compare) || viewer.scene().playback.isComparisonLocked() && !"end".equals(compare);
        if (locked
                && (args.has("playback") || args.has("speed") || args.has("hide_effect") || args.has("show_effect"))) {
            throw new ToolProblem(
                    "A comparison holds the animation and the effects, as in the view bar. Call view_settings with"
                            + " compare end first.");
        }
        if (compare != null && !"start".equals(compare) && !"end".equals(compare) && !comparison.isActive()) {
            throw new ToolProblem("No comparison runs. Call view_settings with compare start first.");
        }
        WorldFacts.choice(args, "playback", PLAYBACK);
        var speed = args.getNumber("speed");
        if (speed != null && (speed < LEAST_SPEED || speed > MOST_SPEED)) {
            throw new ToolProblem("speed takes 0.1 to 4, as the view bar does.");
        }
        if (args.readFlag("isolate", false) && viewer.scene().inspection.selected == null) {
            throw new ToolProblem("Nothing is selected to isolate. Call selection with set first.");
        }
        for (var name : List.of("hide_effect", "show_effect")) {
            var actor = args.getText(name);
            if (actor != null) {
                emitter(viewer, actor);
            }
        }
        if (args.has("slice_near") || args.has("slice_far")) {
            var view = viewer.scene().camera.getView();
            if (view != CameraView.FRONT && view != CameraView.SIDE) {
                throw new ToolProblem(
                        "slice_near and slice_far cut the Front or the Side view. Call view_camera with view front or"
                                + " side first. The Top view takes cut_top and cut_bottom.");
            }
        }
        for (var layer : args.list("cut_keep")) {
            if (!List.of(KEPT).contains(layer)) {
                throw new ToolProblem("cut_keep takes terrain, static_meshes and csg, or none, not \"" + layer + "\".");
            }
        }
        circle(args);
    }

    static void apply(ViewerContext viewer, ToolArgs args) throws ToolProblem {
        var rendering = viewer.ui().rendering;
        var playback = viewer.scene().playback;
        var comparison = viewer.system().getWorkspace().getComparison();
        var compare = WorldFacts.choice(args, "compare", COMPARE);
        if (compare != null) {
            switch (compare) {
                case "start" -> comparison.begin(rendering, playback);
                case "end" -> comparison.finish(playback);
                default -> {
                    var side = compare.substring(compare.length() - 1).toUpperCase(Locale.ROOT);
                    if (compare.startsWith("save")) {
                        comparison.save(side, rendering);
                    } else {
                        comparison.show(side, rendering);
                    }
                }
            }
        }
        var word = WorldFacts.choice(args, "playback", PLAYBACK);
        if (word != null) {
            switch (word) {
                case "play" -> playback.setPaused(false);
                case "pause" -> playback.setPaused(true);
                default -> playback.step();
            }
        }
        var speed = args.getNumber("speed");
        if (speed != null) {
            playback.setSpeed(speed);
        }
        var inspection = viewer.scene().inspection;
        if (args.has("isolate")) {
            inspection.setIsolated(args.readFlag("isolate", false));
        }
        if (args.has("others_opacity")) {
            inspection.setOthersOpacity((float) (args.getNumber("others_opacity", 0) / PERCENT));
        }
        inspection.showBounds = args.readFlag("bounds", inspection.showBounds);
        if (args.has("hide_effect")) {
            inspection.setEffectHidden(emitter(viewer, args.getRequired("hide_effect")), true);
        }
        if (args.has("show_effect")) {
            inspection.setEffectHidden(emitter(viewer, args.getRequired("show_effect")), false);
        }
        if (args.has("cut_keep")) {
            var kept = args.list("cut_keep");
            rendering.orthoKeepTerrain = kept.contains("terrain");
            rendering.orthoKeepStaticMeshes = kept.contains("static_meshes");
            rendering.orthoKeepCsg = kept.contains("csg");
        }
        var circle = circle(args);
        if (args.has("cut_circle")) {
            rendering.orthoCircle = circle != null;
            if (circle != null) {
                rendering.orthoCircleX.set(circle[0]);
                rendering.orthoCircleY.set(circle[1]);
                rendering.orthoCircleRadius[0] = circle[2];
            }
        }
        if (args.has("slice_near") || args.has("slice_far")) {
            var view = viewer.scene().camera.getView();
            var slice = rendering.getSlice(view);
            if (args.has("slice_near")) {
                slice.cutNear = true;
                slice.near[0] = bound(args.getNumber("slice_near", 0));
            }
            if (args.has("slice_far")) {
                slice.cutFar = true;
                slice.far[0] = bound(args.getNumber("slice_far", 0));
            }
            slice.keepOrder(view.getDepthDirection());
        }
        if (args.readFlag("cuts_off", false)) {
            rendering.frontSlice.off();
            rendering.sideSlice.off();
        }
    }

    static void describe(ViewerContext viewer, JSONObject values) {
        var rendering = viewer.ui().rendering;
        var playback = viewer.scene().playback;
        var animation = new JSONObject();
        animation.put("state", playback.isPaused() ? "paused" : "playing");
        animation.put("speed", playback.getSpeed());
        animation.put("seconds", Math.round(playback.getPreciseSeconds() * 100) / 100.0);
        values.put("animation", animation);
        var comparison = viewer.system().getWorkspace().getComparison();
        if (comparison.isActive()) {
            var compare = new JSONObject();
            compare.put("shown", comparison.getShown());
            compare.put("changed", comparison.isModified(rendering));
            values.put("compare", compare);
        }
        var inspection = viewer.scene().inspection;
        if (inspection.isIsolated()) {
            values.put("isolate", true);
            values.put("others_opacity", Math.round(inspection.getOthersOpacity() * PERCENT));
        }
        if (inspection.showBounds) {
            values.put("bounds", true);
        }
        if (rendering.orthoCutTop || rendering.orthoCutBottom) {
            var kept = new JSONArray();
            if (rendering.orthoKeepTerrain) {
                kept.add("terrain");
            }
            if (rendering.orthoKeepStaticMeshes) {
                kept.add("static_meshes");
            }
            if (rendering.orthoKeepCsg) {
                kept.add("csg");
            }
            values.put("cut_keep", kept);
            if (rendering.orthoCircle) {
                values.put(
                        "cut_circle",
                        rendering.orthoCircleX.get()
                                + ","
                                + rendering.orthoCircleY.get()
                                + ","
                                + rendering.orthoCircleRadius[0]);
            }
        }
        slice(values, "front", rendering.frontSlice);
        slice(values, "side", rendering.sideSlice);
    }

    static JSONObject performance(ViewerContext viewer) {
        var rendering = viewer.ui().rendering;
        var stats = rendering.stats;
        var history = viewer.system().getFrameHistoryStats();
        var numbers = new JSONObject();
        numbers.put("fps", stats.fps);
        numbers.put("frame_ms", tenth(stats.loopLatencyNs / 1e6));
        numbers.put("p95_ms", tenth(history.p95Ms()));
        numbers.put("over_budget", history.overBudgetFrames() + " of " + HISTORY + " frames");
        numbers.put("cpu_ms", tenth(stats.bgfxFrameCpuMs));
        numbers.put("submit_ms", tenth(stats.sceneSubmitMs));
        numbers.put("gpu_ms", rendering.gpuProfiling ? tenth(stats.sceneGpuMs) : null);
        numbers.put("submits", stats.draws);
        numbers.put("visible", stats.gpuVisibilityCountersValid ? stats.gpuVisibilityCompacted : "sampling");
        numbers.put("fx", stats.particleMeshDraws + stats.particleBillboardDraws + stats.particleBeamDraws);
        if (rendering.loading.active) {
            numbers.put("streaming", rendering.loading.tilesLoaded + " of " + rendering.loading.tilesTotal + " tiles");
        }
        if (viewer.agent().isHidden()) {
            numbers.put(
                    "note",
                    "A viewer with no window draws while a call works, so these are the numbers of those frames.");
        }
        return numbers;
    }

    private static @Nullable Double tenth(double value) {
        return Double.isFinite(value) ? Math.round(value * 10) / 10.0 : null;
    }

    private static void slice(JSONObject values, String view, OrthoSlice slice) {
        if (!slice.on()) {
            return;
        }
        var cut = new JSONObject();
        if (slice.cutNear) {
            cut.put("near", slice.near[0]);
        }
        if (slice.cutFar) {
            cut.put("far", slice.far[0]);
        }
        values.put("slice_" + view, cut);
    }

    private static int bound(double value) {
        return (int) Math.clamp(Math.round(value), -OrthoSlice.LIMIT, OrthoSlice.LIMIT);
    }

    private static int @Nullable [] circle(ToolArgs args) throws ToolProblem {
        var text = args.getText("cut_circle");
        if (text == null || text.equalsIgnoreCase("off")) {
            return null;
        }
        var parts = text.split(",");
        try {
            if (parts.length == 2 || parts.length == 3) {
                var radius = parts.length == 3 ? Math.round(Double.parseDouble(parts[2].strip())) : 4000;
                return new int[] {
                    (int) Math.round(Double.parseDouble(parts[0].strip())),
                    (int) Math.round(Double.parseDouble(parts[1].strip())),
                    (int) Math.clamp(radius, 1, CutCircle.MAX_RADIUS)
                };
            }
        } catch (NumberFormatException wrong) {
        }
        throw new ToolProblem("cut_circle takes x,y,radius in world units, such as 83400,148000,3000, or off.");
    }

    private static ParticleEmitter emitter(ViewerContext viewer, String actor) throws ToolProblem {
        for (var emitter : viewer.scene().particles.getEmitters()) {
            if (actor.equalsIgnoreCase(emitter.getRenderActorName())) {
                return emitter;
            }
        }
        throw new ToolProblem(
                "There is no effect actor " + actor + " in this viewer. Call actors_find with category effect.");
    }
}
