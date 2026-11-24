package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.view.Pose;
import com.rost.cruma.viewer.agent.view.ScenePlacement;
import com.rost.cruma.viewer.application.ApplicationContext;
import com.rost.cruma.viewer.application.UiContext;
import com.rost.cruma.viewer.application.WindowContext;
import com.rost.cruma.viewer.config.Config.Viewer.Camera;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.render.RenderingContext;
import com.rost.cruma.viewer.render.bgfx.BgfxViewerRenderBackend;
import com.rost.cruma.viewer.system.LoadingSystem;
import com.rost.cruma.viewer.system.UiSystem;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record ViewerContext(
        World world,
        Camera camera,
        List<String> maps,
        @Nullable Path configDirectory,
        ApplicationContext application,
        UiContext ui,
        WindowContext window,
        RenderingContext scene,
        BgfxViewerRenderBackend backend,
        UiSystem system,
        LoadingSystem loading,
        AgentRuntime agent) {
    private static final int DEPTH_FRAMES = 3;

    public boolean ready() {
        var load = ui.rendering.loading;
        return !load.active && !load.reloading && load.tilesLoaded >= load.tilesTotal;
    }

    JSONObject status() {
        var load = ui.rendering.loading;
        var status = new JSONObject();
        status.put("ready", ready());
        status.put("tiles_loaded", load.tilesLoaded);
        status.put("tiles_total", load.tilesTotal);
        status.put("mode", ui.workspaceMode == null ? "EXPLORE" : ui.workspaceMode.name());
        status.put("editing", ui.editingPrepared);
        status.put(
                "unsaved",
                scene.doesEditingNeedSave()
                        || geodataNeedsSave()
                        || spawnsNeedSave()
                        || system.getEffects().hasUnexportedEdits());
        status.put("hour", Math.round(ui.rendering.timeOfDay.get() * 10) / 10.0);
        status.put("camera", Pose.write(scene.camera));
        status.put("access", agent.getAccess().getWord());
        status.put("stopped", agent.isStopped());
        return status;
    }

    public @Nullable EditingSession editing() {
        return ui.editingPrepared ? scene.getEditing() : null;
    }

    public void holdDepth() {
        ui.rendering.agentDepthFrames = DEPTH_FRAMES;
    }

    public Target locate(String text) throws ToolProblem {
        return located(text).target();
    }

    public Located located(String text) throws ToolProblem {
        var target = Target.parse(text);
        return switch (target.kind()) {
            case POINT, TILE -> new Located(target, null, null);
            case CAMERA -> {
                var eye = scene.camera.getPosition();
                yield new Located(new Target(Kind.POINT, text, eye.getX(), eye.getY(), eye.getZ()), null, null);
            }
            case NAME -> named(text, target);
            case SELECTION -> {
                var chosen = Chosen.current(this);
                if (chosen == null) {
                    throw new ToolProblem(
                            "Nothing is selected. Ask the person to click something, or call selection with set.");
                }
                yield chosen.located(text);
            }
            case MARK -> marked(text);
        };
    }

    private boolean geodataNeedsSave() {
        var session = scene.geodata.editing;
        return session != null && session.hasUnsavedChanges();
    }

    private boolean spawnsNeedSave() {
        var document = system.getSpawnAccess().getSource().getDocument();
        return document != null && document.isModified()
                || system.getAreaAccess().getSource().isModified();
    }

    private Located named(String text, Target target) throws ToolProblem {
        var place = Target.place(camera.locations(), target.text());
        if (place.isPresent()) {
            return new Located(
                    new Target(
                            Kind.POINT,
                            text,
                            place.get().x(),
                            place.get().y(),
                            place.get().z()),
                    null,
                    null);
        }
        var rows = backend.getEditorActorObjects(Set.of(target.text()));
        if (rows.isEmpty()) {
            return effect(text, target.text());
        }
        var placement = ScenePlacement.of(rows.getFirst());
        for (var row : rows.subList(1, rows.size())) {
            placement = placement.with(row);
        }
        return at(text, placement);
    }

    private Located effect(String text, String actor) throws ToolProblem {
        for (var emitter : scene.particles.getEmitters()) {
            if (actor.equals(emitter.getRenderActorName())) {
                var at = emitter.getActorPos();
                return new Located(new Target(Kind.POINT, text, at.getX(), at.getY(), at.getZ()), null, null);
            }
        }
        throw new ToolProblem(
                "There is no place or loaded actor named \"" + text + "\". Call place_find or actors_find to search.");
    }

    private Located marked(String text) throws ToolProblem {
        var number = Integer.parseInt(text.strip().substring(1));
        var placement = agent.getSeen().mark(number);
        if (placement == null) {
            var marks = agent.getSeen().getMarkCount();
            throw new ToolProblem(
                    marks == 0
                            ? "No picture has marks yet. Call view_look with marks."
                            : "The last picture with marks has marks 1 to " + marks + ".");
        }
        return at(text, placement);
    }

    private static Located at(String text, ScenePlacement placement) {
        var box = placement.bounds();
        return new Located(
                new Target(Kind.POINT, text, box.centerX(), box.centerY(), box.centerZ()), box, placement.row());
    }
}
