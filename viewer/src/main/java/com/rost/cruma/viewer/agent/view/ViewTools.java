package com.rost.cruma.viewer.agent.view;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.ServerEvents;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Located;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.view.Eyes.Clearing;
import com.rost.cruma.viewer.agent.view.Eyes.Seen;
import com.rost.cruma.viewer.agent.view.Eyes.Sight;
import com.rost.cruma.viewer.agent.view.MapEventEdit.Result;
import com.rost.cruma.viewer.agent.view.Picture.Box;
import com.rost.cruma.viewer.agent.view.Picture.Sheet;
import com.rost.cruma.viewer.agent.view.SeenWorld.Capture;
import com.rost.cruma.viewer.agent.view.SeenWorld.Mark;
import com.rost.cruma.viewer.agent.view.SeenWorld.View;
import com.rost.cruma.viewer.application.Screenshots;
import com.rost.cruma.viewer.geodata.GeoDataFocusMode;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.CameraView;
import com.rost.cruma.viewer.render.ParticleSystem;
import com.rost.cruma.viewer.render.ViewMode;
import com.rost.cruma.viewer.render.api.DebugSceneObject.Category;
import com.rost.cruma.viewer.ui.OrthoGridSteps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ViewTools implements ToolGroup {
    private static final int DEFAULT_LEGEND = 12;
    private static final int MOST_LEGEND = 30;
    private static final int DEFAULT_AROUND = 4;
    private static final int MOST_AROUND = 8;
    private static final double AROUND_PITCH = -15;
    private static final int DEFAULT_CLIP_FRAMES = 6;
    private static final int MOST_CLIP_FRAMES = 12;
    private static final double DEFAULT_CLIP_SECONDS = 3;
    private static final double MOST_CLIP_SECONDS = 30;
    private static final double HOUR_OF_A_DAY = 24;
    private static final float LEAST_FOV = 5;
    private static final float MOST_FOV = 150;
    private static final double EYE_HEIGHT = 44;
    private static final long PICK_MILLIS = 3_000;
    private static final int PICK_FRAMES = 3;
    private static final float FAR_PICK = Float.MAX_VALUE;
    private static final String[] SKIES = {"normal", "dusk", "dawn", "cursed"};
    private static final String[] SHOWN = {"scene", "map_events", "performance"};
    private static final double MOST_FOG = 200;
    private static final String MERGED_NOTE =
            "The viewer draws some of these merged with others of their tile, as it does outside Edit. They keep their"
                    + " names, and edit_mode gives each actor a transform of its own.";
    private static final String CLEARED_NOTE =
            "Something stood between the camera and the target, so the camera moved to see it.";
    private static final String UNTESTED_NOTE =
            "This view gives no depth of the scene, so the list is what lies in view, biggest first, not what is"
                    + " proved visible.";

    static String compass(double degrees) {
        String[] words = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
        var index = (int) Math.round(((degrees % 360) + 360) % 360 / 45) % words.length;
        return words[index];
    }

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "view_look",
                                "Look",
                                "A picture of the world, from the camera or around a target, with a list of what is"
                                        + " visible. Use marks to number the objects.")
                        .target("What to look at. Without it, the picture is from the camera.")
                        .number(
                                "distance",
                                "How far the camera stands from the target. It fits an object, and is 1500 for a"
                                        + " place, when left out.")
                        .number(
                                "yaw",
                                "Which way to look: 0 east, 90 south, 180 west, 270 north. The camera's yaw when left"
                                        + " out.")
                        .number("pitch", "Tilt: 0 level, negative looks down. -20 with a target when left out.")
                        .flag(
                                "stay",
                                "Keep the camera at the new pose. The camera goes back when left out, so a person's"
                                        + " view stays.")
                        .integer("width", "Width of the picture in pixels. 1280 when left out.")
                        .flag(
                                "marks",
                                "Draw numbered boxes on the visible objects. Then #7 is a target, and the list has the"
                                        + " same numbers.")
                        .flag("interface", "Show what the person sees, with the panels.")
                        .integer(
                                "limit",
                                "How many objects the list names, at most " + MOST_LEGEND + ". 12 when left out.")
                        .needsGraphics(),
                (args, viewer) -> ViewScript.start(viewer, script -> look(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_around",
                                "Look around",
                                "One sheet of pictures around a target, each labelled with where the camera stands. It"
                                        + " costs the tokens of one picture.")
                        .target("What to look at.")
                        .number(
                                "distance",
                                "How far the camera stands from the target. It fits an object, and is 1500 for a"
                                        + " place, when left out.")
                        .number("pitch", "Tilt of each view. -15 when left out.")
                        .integer("views", "How many views, at most " + MOST_AROUND + ". 4 when left out.")
                        .integer("width", "Width of the sheet in pixels. 1280 when left out.")
                        .required("target")
                        .needsGraphics(),
                (args, viewer) -> ViewScript.start(viewer, script -> around(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_clip",
                                "Watch",
                                "One sheet of frames over some seconds, to see what moves. It costs the tokens of one"
                                        + " picture, and says how much of the view changed between frames.")
                        .target("What to watch. Without it, the frames are from the camera.")
                        .number("distance", "How far the camera stands from the target.")
                        .number("yaw", "Which way to look.")
                        .number("pitch", "Tilt of the view.")
                        .number(
                                "seconds",
                                "How long to watch, at most " + (int) MOST_CLIP_SECONDS + ". 3 when left out.")
                        .integer("frames", "How many frames, at most " + MOST_CLIP_FRAMES + ". 6 when left out.")
                        .integer("width", "Width of the sheet in pixels. 1280 when left out.")
                        .needsGraphics(),
                (args, viewer) -> ViewScript.start(viewer, script -> clip(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_pick",
                                "Pick",
                                "What stands at a pixel of a picture: the object, the world point and the distance. It"
                                        + " uses the camera of that picture. In Edit, a BSP surface of a map whose"
                                        + " surfaces changed also gives its surface and its material now.")
                        .number("x", "Pixels from the left of the picture.")
                        .number("y", "Pixels from the top of the picture.")
                        .integer("capture", "The capture id of the picture. The last picture when left out.")
                        .required("x", "y")
                        .needsGraphics(),
                (args, viewer) -> ViewScript.start(viewer, script -> pick(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_camera",
                                "Camera",
                                "Reads the camera, or moves it and leaves it there. For a picture that leaves the"
                                        + " camera alone, call view_look.")
                        .target("Where to go. It sets where the camera looks, or where it stands with eye.")
                        .number(
                                "distance",
                                "How far from the target. It fits an object, and is 1500 for a place, when left out.")
                        .number("yaw", "Which way to look: 0 east, 90 south, 180 west, 270 north.")
                        .number("pitch", "Tilt: 0 level, negative looks down.")
                        .number("fov", "Field of view in degrees.")
                        .choice(
                                "view",
                                "The view: the free camera, or the flat views of a map.",
                                "free",
                                "top",
                                "front",
                                "side")
                        .number("zoom", "Zoom of a flat view in percent, 10 to 1000: 100 shows one tile across.")
                        .flag("grid", "Lines in world units over the flat views.")
                        .integer("grid_step", "Units between grid lines. 0 follows the zoom.")
                        .flag("frame", "Fit the target in the view, as the Focus button does.")
                        .flag("eye", "Stand on the ground at the target, at the height of a player's eye."),
                (args, viewer) -> ViewScript.start(viewer, script -> camera(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_settings",
                                "View settings",
                                "Reads and sets how the world shows: hour, sky, layers, overlays, map events, cuts,"
                                    + " Scene settings, animation and A/B. With no arguments it lists the values and"
                                    + " the choices.")
                        .number("hour", "Hour of the day, from 0 to 24.")
                        .choice("sky", "The sky of the day.", SKIES)
                        .choice("mode", "How surfaces draw.", "lit", "unlit", "wire")
                        .flag("terrain", "Show the Terrain.")
                        .flag("static_meshes", "Show static meshes.")
                        .flag("csg", "Show BSP surfaces.")
                        .flag("particles", "Show effects.")
                        .flag("geodata", "Show the geodata around the camera.")
                        .flag("spawns", "Show NPC spawns.")
                        .choice(
                                "map_events",
                                "Where map events start. Another start clears the choices of events and castles. Kept"
                                        + " for exports.",
                                MapEventEdit.STARTS)
                        .schema(
                                "events",
                                object("Map events to switch by id, such as {\"22226292\":\"on\"}. show map_events"
                                        + " lists them."))
                        .schema(
                                "castles",
                                object("Castle sides, such as {\"Giran\":\"dark\"} or {\"all\":\"both\"}: light,"
                                        + " dark, both or none."))
                        .schema(
                                "by_time",
                                object("Effects and sounds by time of day, all or by their hours: clock, always or"
                                        + " off, such as {\"all\":\"off\"}."))
                        .schema(
                                "zone_states",
                                object("The zone state of each map, 1 to 10, 0 as the map or \"default\", such as"
                                        + " {\"25_18_Classic\":3}; {\"all\":0} clears every choice. A map of"
                                        + " Essence whose zones start in state 0 is in state 1 by default. show"
                                        + " map_events lists the maps and their states."))
                        .text(
                                "set_time",
                                "The hours of effects or sounds by time of day: the hour goes to their next window,"
                                        + " as Set time does.")
                        .number("fog", "Fog in percent: 100 is the client's fog, 0 none, more brings it closer.")
                        .number("cut_top", "Cut away what stands above this height. It works in the top view.")
                        .number("cut_bottom", "Cut away what lies below this height. It works in the top view.")
                        .list("cut_keep", "Layers a height cut draws whole: terrain, static_meshes, csg.")
                        .text("cut_circle", "Cut only inside x,y,radius, or off.")
                        .number("slice_near", "Front or Side view: hide what stands before this coordinate.")
                        .number("slice_far", "Front or Side view: hide what stands behind this coordinate.")
                        .flag("cuts_off", "Take the height cuts and slices away.")
                        .schema(
                                "scene",
                                object("Scene settings by name, such as {\"gamma\":1.2,\"bloom\":true}. show scene"
                                        + " lists them."))
                        .flag("reset", "Reset Scene settings to defaults, before scene.")
                        .choice("playback", "The animation of the world.", ViewControls.PLAYBACK)
                        .number("speed", "Animation speed, 0.1 to 4.")
                        .flag("isolate", "Draw only the selection, as Isolate does.")
                        .number("others_opacity", "With isolate, how solid the rest shows, 0 to 100.")
                        .flag("bounds", "Show the box of the selection.")
                        .text("hide_effect", "An effect actor to hide.")
                        .text("show_effect", "A hidden effect actor to show again.")
                        .choice(
                                "compare",
                                "Compare A/B: start freezes the world, save keeps the settings, show puts them back.",
                                ViewControls.COMPARE)
                        .choice("show", "Also list Scene settings, map events or the Performance numbers.", SHOWN)
                        .writes(),
                (args, viewer) -> ViewScript.start(viewer, script -> settings(script, args)));
        tools.viewer(
                ToolSpec.named(
                                "view_screenshot",
                                "Screenshot",
                                "Saves a full size picture of the view into the screenshots folder, as F12 does, and"
                                        + " answers its path.")
                        .flag("interface", "With the panels, as the window shows. As Settings say when left out.")
                        .writes()
                        .needsGraphics(),
                (args, viewer) -> ViewScript.start(viewer, script -> screenshot(script, args)));
    }

    private static JSONObject object(String text) {
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put("description", text);
        return schema;
    }

    private static ToolResult pick(ViewScript script, ToolArgs args) throws Exception {
        var x = args.getNumber("x", 0);
        var y = args.getNumber("y", 0);
        var wanted = args.has("capture") ? Integer.valueOf(args.getInteger("capture", 0)) : null;
        Eyes.ready(script);
        var memory = script.read(viewer -> viewer.agent().getSeen());
        var capture = memory.capture(wanted);
        if (capture == null) {
            throw new ToolProblem(
                    wanted == null
                            ? "No picture yet. Call view_look first."
                            : "There is no capture " + wanted + " any more. Take a new picture.");
        }
        var view = capture.viewAt(x, y);
        if (view == null) {
            throw new ToolProblem("The picture is "
                    + capture.width()
                    + " by "
                    + capture.height()
                    + ", so "
                    + Math.round(x)
                    + ","
                    + Math.round(y)
                    + " lies outside it.");
        }
        var ndc = view.ndc(x, y);
        script.wantDepth();
        script.run(viewer -> {
            script.holdCamera(viewer);
            viewer.scene().camera.restoreSnapshot(view.camera());
            viewer.backend().onCameraRestored();
        });
        script.frames(Eyes.SETTLE_FRAMES);
        var answer = new JSONObject();
        answer.put("capture", capture.id());
        if (view.camera().ortho()) {
            pickFlat(script, ndc, answer);
        } else {
            pickDeep(script, ndc, answer);
        }
        return ToolResult.data(answer);
    }

    private static void pickDeep(ViewScript script, float[] ndc, JSONObject answer)
            throws ToolProblem, InterruptedException {
        var ray = Eyes.rays(script, ndc).getFirst();
        if (!ray.hit()) {
            answer.put("hit", false);
            answer.put(
                    "note",
                    "Nothing stands there: it is sky, or farther than " + Math.round(Eyes.DEPTH_REACH) + " units.");
            return;
        }
        var object = Eyes.hitObject(script, ray);
        answer.put("hit", true);
        answer.put("point", point(ray.hitX(), ray.hitY(), ray.hitZ()));
        answer.put("distance", Math.round(ray.distance()));
        answer.put("tile", tile(ray.hitX(), ray.hitY()));
        if (object != null) {
            describe(script, object, answer);
            nameSurface(script, object, new Vector3(ray.hitX(), ray.hitY(), ray.hitZ()), answer);
        } else {
            answer.put("note", "The surface has no object: it may be an effect or a hidden layer.");
        }
    }

    private static void pickFlat(ViewScript script, float[] ndc, JSONObject answer)
            throws ToolProblem, InterruptedException {
        var at = new float[3];
        var hit = script.poll(
                viewer -> {
                    viewer.ui().rendering.agentPickFrames = PICK_FRAMES;
                    var found = viewer.backend()
                            .pickDebugSceneObject(ndc[0], ndc[1], FAR_PICK, EnumSet.allOf(Category.class));
                    if (found == null) {
                        return null;
                    }
                    var camera = viewer.scene().camera;
                    var origin = camera.getCursorRayOrigin(ndc[0], ndc[1]);
                    var direction = camera.getCursorRayDirection(ndc[0], ndc[1]);
                    var x = origin.getX() + direction.getX() * found.distance();
                    var y = origin.getY() + direction.getY() * found.distance();
                    var z = origin.getZ() + direction.getZ() * found.distance();
                    at[0] = x;
                    at[1] = y;
                    at[2] = z;
                    answer.put("point", point(x, y, z));
                    answer.put("tile", tile(x, y));
                    return found;
                },
                PICK_MILLIS,
                "No object stands there, or the picker of the interface took the frame. Try again.");
        answer.put("hit", true);
        var placement = ScenePlacement.of(hit.object());
        describe(script, placement, answer);
        nameSurface(script, placement, new Vector3(at[0], at[1], at[2]), answer);
    }

    private static void nameSurface(ViewScript script, ScenePlacement found, Vector3 point, JSONObject answer)
            throws ToolProblem, InterruptedException {
        if (found.category() != Category.CSG) {
            return;
        }
        var map = found.row().owner();
        var named = script.read(viewer -> {
            var session = viewer.editing();
            var surface = session == null ? null : session.pickChangedSurface(map, point);
            if (surface == null) {
                return Optional.<String[]>empty();
            }
            try {
                return Optional.of(new String[] {
                    surface.path(), session.getSurfaceMaterial(surface), session.getSourceSurfaceMaterial(surface)
                });
            } catch (IOException unreadable) {
                return Optional.<String[]>empty();
            }
        });
        if (named.isEmpty()) {
            return;
        }
        var object = answer.getJSONObject("object");
        object.put("surface", named.get()[0]);
        var material = named.get()[1];
        if (material.equals(named.get()[2])) {
            return;
        }
        if (!material.isEmpty()) {
            object.put("name", material.substring(material.lastIndexOf('.') + 1));
            object.put("mesh", material);
        }
        object.put("material", material);
    }

    private static void describe(ViewScript script, ScenePlacement found, JSONObject answer)
            throws ToolProblem, InterruptedException {
        var placement = WorldFacts.named(script.read(WorldFacts::book), found);
        var object = new JSONObject();
        object.put("name", placement.name());
        object.put("mesh", placement.mesh());
        object.put("class", placement.className());
        object.put("category", placement.category().name().toLowerCase(Locale.ROOT));
        object.put("material", placement.row().materialName());
        answer.put("object", object);
        if (placement.merged()) {
            answer.put("note", MERGED_NOTE);
        }
    }

    private static String point(double x, double y, double z) {
        return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
    }

    private static String tile(float x, float y) {
        var tile = WorldTile.at(x, y);
        return tile.x() + "_" + tile.y();
    }

    private static ToolResult camera(ViewScript script, ToolArgs args) throws Exception {
        var changes = args.has("target")
                || args.has("yaw")
                || args.has("pitch")
                || args.has("fov")
                || args.has("view")
                || args.has("zoom")
                || args.has("grid")
                || args.has("grid_step");
        if (changes) {
            Eyes.ready(script);
            move(script, args);
            script.frames(1);
        }
        return ToolResult.data(script.read(ViewTools::cameraNow));
    }

    private static JSONObject cameraNow(ViewerContext viewer) {
        var camera = viewer.scene().camera;
        var data = Pose.write(camera);
        data.put("fov", Math.round(camera.getFov()));
        data.put(
                "view",
                camera.getView() == CameraView.PERSPECTIVE
                        ? "free"
                        : camera.getView().name().toLowerCase(Locale.ROOT));
        if (camera.isInteractiveOrtho()) {
            data.put("zoom", Math.round(Camera.zoomPercent(camera.getOrthoZoomFactor())));
        }
        if (viewer.ui().rendering.orthoGrid) {
            data.put(
                    "grid",
                    viewer.ui().rendering.orthoGridStep > 0 ? viewer.ui().rendering.orthoGridStep : "by the zoom");
        }
        var eye = camera.getWorldFocusPosition();
        data.put("tile", tile(eye.getX(), eye.getY()));
        return data;
    }

    private static void move(ViewScript script, ToolArgs args) throws ToolProblem, InterruptedException {
        var view = WorldFacts.choice(args, "view", "free", "perspective", "top", "front", "side");
        var fov = args.getNumber("fov");
        var zoom = args.getNumber("zoom");
        script.run(viewer -> {
            var camera = viewer.scene().camera;
            var free = view == null ? !camera.isInteractiveOrtho() : "free".equals(view) || "perspective".equals(view);
            if (zoom != null && free) {
                throw new ToolProblem("zoom works in the top, front and side views. The free camera takes fov.");
            }
            if (view != null) {
                camera.setView(free ? CameraView.PERSPECTIVE : CameraView.valueOf(view.toUpperCase(Locale.ROOT)));
            }
            if (fov != null) {
                camera.setFov((float) Math.clamp(fov, LEAST_FOV, MOST_FOV));
            }
            var rendering = viewer.ui().rendering;
            rendering.orthoGrid = args.readFlag("grid", rendering.orthoGrid);
            if (args.has("grid_step")) {
                rendering.orthoGridStep = Math.clamp(args.getInteger("grid_step", 0), 0, OrthoGridSteps.MAX_STEP);
            }
        });
        aim(script, args);
        if (zoom != null) {
            script.run(
                    viewer -> viewer.scene().camera.setOrthoZoomFactor(Camera.zoomFactorOfPercent(zoom.floatValue())));
        }
    }

    private static void aim(ViewScript script, ToolArgs args) throws ToolProblem, InterruptedException {
        var text = args.getText("target");
        if (text == null) {
            if (args.has("yaw") || args.has("pitch")) {
                turn(script, args);
            }
            return;
        }
        var found = script.read(viewer -> viewer.located(text));
        if (args.readFlag("eye", false)) {
            stand(script, found, args);
        } else if (args.readFlag("frame", false)) {
            frame(script, found, args);
        } else {
            var yaw = args.getNumber("yaw");
            var cameraYaw = script.read(viewer -> viewer.scene().camera.getYawDegrees());
            var pose = Eyes.aimed(
                    script,
                    found,
                    args.getNumber("distance"),
                    yaw == null ? cameraYaw : yaw,
                    args.getNumber("pitch", -20));
            script.run(viewer -> pose.applyTo(viewer.scene().camera));
        }
    }

    private static void turn(ViewScript script, ToolArgs args) throws ToolProblem, InterruptedException {
        var yaw = args.getNumber("yaw");
        var pitch = args.getNumber("pitch");
        script.run(viewer -> {
            var camera = viewer.scene().camera;
            camera.setDirection(
                    yaw == null ? camera.getYawDegrees() : yaw, pitch == null ? camera.getPitchDegrees() : pitch);
        });
    }

    private static void frame(ViewScript script, Located found, ToolArgs args)
            throws ToolProblem, InterruptedException {
        var target = found.target();
        var z = Eyes.height(script, target);
        var half = Eyes.fitDistance(null) / 4;
        var box = found.bounds() != null
                ? found.bounds()
                : new Bounds3f(
                        (float) (target.x() - half),
                        (float) (target.y() - half),
                        (float) (z - half),
                        (float) (target.x() + half),
                        (float) (target.y() + half),
                        (float) (z + half));
        script.run(viewer -> {
            var camera = viewer.scene().camera;
            if (args.has("yaw") || args.has("pitch")) {
                camera.setDirection(
                        args.getNumber("yaw", camera.getYawDegrees()),
                        args.getNumber("pitch", camera.getPitchDegrees()));
            }
            camera.focus(box);
        });
    }

    private static void stand(ViewScript script, Located found, ToolArgs args)
            throws ToolProblem, InterruptedException {
        var target = found.target();
        script.wantDepth();
        var yaw = args.getNumber(
                "yaw", script.read(viewer -> viewer.scene().camera.getYawDegrees()));
        var ground = target.z() != null
                ? Eyes.below(script, target.x(), target.y(), target.z())
                : Eyes.surfaces(script, new double[] {target.x()}, new double[] {target.y()})[0];
        script.run(script::putCameraBack);
        var z = Double.isFinite(ground) ? ground : Eyes.height(script, target);
        var pose = new Pose(target.x(), target.y(), z + EYE_HEIGHT, yaw, args.getNumber("pitch", 0));
        script.run(viewer -> pose.applyTo(viewer.scene().camera));
    }

    private static ToolResult settings(ViewScript script, ToolArgs args) throws Exception {
        var show = WorldFacts.choice(args, "show", SHOWN);
        var changed = false;
        for (var key : args.getJson().keySet()) {
            changed |= !key.equals("viewer") && !key.equals("show");
        }
        var events = MapEventEdit.asks(args) || "map_events".equals(show);
        var server = events ? ServerEvents.of(script.read(ViewTools::scripts).orElse(null)) : ServerEvents.NONE;
        var notes = new ArrayList<String>();
        if (changed) {
            notes.addAll(script.read(viewer -> set(viewer, args, server)));
            if ("step".equals(args.getText("playback"))) {
                script.frames(2);
            }
        }
        var asked = changed;
        return ToolResult.data(script.read(viewer -> {
            var values = current(viewer);
            var rendering = viewer.ui().rendering;
            if (!asked && show == null) {
                values.put("choices", choices());
            }
            if ("scene".equals(show) || args.has("scene") || args.has("reset")) {
                values.put("scene", SceneSettings.values(rendering));
                if (!asked) {
                    values.put("scene_choices", SceneSettings.ranges());
                }
                if (!rendering.stats.musicStatus.isBlank()) {
                    values.put("music_now", rendering.stats.musicStatus);
                }
            }
            if (events) {
                values.put(
                        "map_events",
                        MapEventEdit.describe(
                                rendering.gameEventStates,
                                viewer.system().getMapEventCatalog(),
                                server,
                                viewer.system().getZoneStateMaps(),
                                viewer.system().getZoneStateDefaults(),
                                rendering.timeOfDay.get()));
            }
            if ("performance".equals(show)) {
                values.put("performance", ViewControls.performance(viewer));
            }
            if (!notes.isEmpty()) {
                values.put("notes", notes);
            }
            return values;
        }));
    }

    private static Optional<Path> scripts(ViewerContext viewer) {
        var spawns = viewer.world().spawns();
        return Optional.ofNullable(spawns == null ? null : spawns.scriptPath());
    }

    private static List<String> set(ViewerContext viewer, ToolArgs args, ServerEvents server) throws ToolProblem {
        var rendering = viewer.ui().rendering;
        ViewControls.check(viewer, args);
        var geodata = viewer.scene().geodata;
        if (args.readFlag("geodata", false) && geodata.path == null) {
            throw new ToolProblem(
                    "This client has no geodata folder. Ask the person to choose one in the Geodata panel.");
        }
        var spawns = viewer.system().getSpawns();
        if (args.readFlag("spawns", false) && spawns.getScriptFolder() == null) {
            throw new ToolProblem(
                    "This client has no script folder. Ask the person to choose one in the NPC spawns panel.");
        }
        var scene = args.getJson().get("scene");
        if (scene != null && !(scene instanceof JSONObject)) {
            throw new ToolProblem("scene must be an object, such as {\"gamma\":1.2}.");
        }
        if (scene != null) {
            SceneSettings.check(rendering, (JSONObject) scene, args.readFlag("reset", false));
        }
        Result events = null;
        if (MapEventEdit.asks(args)) {
            events = MapEventEdit.apply(
                    args,
                    rendering.gameEventStates,
                    viewer.system().getMapEventCatalog(),
                    server,
                    viewer.system().getZoneStateMaps(),
                    viewer.system().getZoneStateDefaults(),
                    rendering.timeOfDay.get());
        }
        if (args.readFlag("reset", false)) {
            viewer.system().resetSceneSettings();
        }
        if (scene != null) {
            SceneSettings.apply(rendering, (JSONObject) scene);
        }
        if (args.has("hour")) {
            var hour = args.getNumber("hour", 0);
            rendering.timeOfDay.set((float) (((hour % HOUR_OF_A_DAY) + HOUR_OF_A_DAY) % HOUR_OF_A_DAY));
        }
        if (args.has("sky")) {
            rendering.skyPreset.set(List.of(SKIES).indexOf(WorldFacts.choice(args, "sky", SKIES)));
        }
        if (args.has("mode")) {
            viewer.scene()
                    .setViewMode(
                            switch (WorldFacts.choice(args, "mode", "lit", "unlit", "wire")) {
                                case "unlit" -> ViewMode.UNLIT;
                                case "wire" -> ViewMode.WIREFRAME;
                                default -> ViewMode.LIT;
                            });
        }
        rendering.terrain = args.readFlag("terrain", rendering.terrain);
        rendering.staticMeshes = args.readFlag("static_meshes", rendering.staticMeshes);
        rendering.csg = args.readFlag("csg", rendering.csg);
        if (args.has("particles")) {
            rendering.particleVisibilityDistance.set(
                    args.readFlag("particles", true) ? ParticleSystem.DEFAULT_VISIBILITY_DISTANCE : 0);
        }
        geodata.enabled = args.readFlag("geodata", geodata.enabled);
        if (geodata.enabled && viewer.agent().isHidden()) {
            geodata.focusMode = GeoDataFocusMode.CAMERA;
        }
        spawns.setOverlayOn(args.readFlag("spawns", spawns.isOverlayOn()));
        var notes = new ArrayList<String>();
        if (events != null) {
            if (!events.state().equals(rendering.gameEventStates)) {
                viewer.system().chooseMapEvents(events.state());
            }
            if (events.hour() != null) {
                rendering.timeOfDay.set(events.hour());
            }
            notes.addAll(events.notes());
        }
        if (args.has("fog")) {
            rendering.fogStrength.set((float) Math.clamp(args.getNumber("fog", 100), 0, MOST_FOG));
        }
        if (args.has("cut_top")) {
            rendering.orthoCutTop = true;
            rendering.orthoTop[0] = args.getInteger("cut_top", 0);
        }
        if (args.has("cut_bottom")) {
            rendering.orthoCutBottom = true;
            rendering.orthoBottom[0] = args.getInteger("cut_bottom", 0);
        }
        if (args.readFlag("cuts_off", false)) {
            rendering.orthoCutTop = false;
            rendering.orthoCutBottom = false;
        }
        ViewControls.apply(viewer, args);
        return notes;
    }

    private static JSONObject current(ViewerContext viewer) {
        var rendering = viewer.ui().rendering;
        var values = new JSONObject();
        values.put("hour", Math.round(rendering.timeOfDay.get() * 10) / 10.0);
        var skyZone = rendering.getSkyZone();
        if (skyZone.hasTime()) {
            values.put("place_hour", Math.round(skyZone.hour() * 10) / 10.0);
            values.put("place_light", "TimeEnv" + skyZone.timeEnv());
            values.put("sky_zone", skyZone.timeZone().name());
        }
        values.put("sky", SKIES[Math.clamp(rendering.skyPreset.get(), 0, SKIES.length - 1)]);
        values.put(
                "mode",
                switch (viewer.scene().getChosenViewMode()) {
                    case UNLIT -> "unlit";
                    case WIREFRAME -> "wire";
                    case LIT -> "lit";
                });
        values.put("terrain", rendering.terrain);
        values.put("static_meshes", rendering.staticMeshes);
        values.put("csg", rendering.csg);
        values.put("particles", rendering.particleVisibilityDistance.get() > 0);
        values.put("geodata", viewer.scene().geodata.enabled);
        values.put(
                "spawns",
                viewer.system().getSpawns().isOverlayOn()
                        && viewer.system().getSpawns().getScriptFolder() != null);
        var events = rendering.gameEventStates;
        values.put("map_events", events.start().key);
        if (!events.summary().equals(events.start().label)) {
            values.put("map_event_choices", events.summary());
        }
        values.put("fog", Math.round(rendering.fogStrength.get()));
        if (rendering.orthoCutTop) {
            values.put("cut_top", rendering.orthoTop[0]);
        }
        if (rendering.orthoCutBottom) {
            values.put("cut_bottom", rendering.orthoBottom[0]);
        }
        ViewControls.describe(viewer, values);
        return values;
    }

    private static JSONObject choices() {
        var choices = new JSONObject();
        choices.put("hour", "0 to 24");
        choices.put("sky", JSONArray.of((Object[]) SKIES));
        choices.put("mode", JSONArray.of("lit", "unlit", "wire"));
        choices.put("map_events", JSONArray.of((Object[]) MapEventEdit.STARTS));
        choices.put("fog", "percent, 0 to 200, 100 is the client's fog");
        choices.put("playback", JSONArray.of((Object[]) ViewControls.PLAYBACK));
        choices.put("compare", JSONArray.of((Object[]) ViewControls.COMPARE));
        choices.put("show", JSONArray.of((Object[]) SHOWN));
        return choices;
    }

    private static ToolResult screenshot(ViewScript script, ToolArgs args) throws Exception {
        Eyes.ready(script);
        var withInterface = args.has("interface")
                ? args.readFlag("interface", false)
                : script.read(viewer -> viewer.ui().screenshots.isIncludeInterface());
        var file = script.read(
                viewer -> Screenshots.nextFile(viewer.ui().screenshots.getFolder(), LocalDateTime.now()));
        Files.createDirectories(file.getParent());
        var done = new CompletableFuture<Path>();
        script.run(viewer -> viewer.backend().takeAgentShot(file, withInterface, (written, failure) -> {
            if (failure == null) {
                done.complete(written);
            } else {
                done.completeExceptionally(failure);
            }
        }));
        var written = Eyes.awaitShot(script, done);
        script.run(viewer -> viewer.ui().screenshots.recordSaved(written));
        var image = Picture.read(written);
        var answer = new JSONObject();
        answer.put("file", written.toString());
        answer.put("size", image.getWidth() + "x" + image.getHeight());
        answer.put("interface", withInterface);
        return ToolResult.data(answer)
                .withFolder(written.getParent())
                .saying("Saved a screenshot to " + written.getFileName());
    }

    private static ToolResult look(ViewScript script, ToolArgs args) throws Exception {
        var width = width(args);
        var limit = Math.clamp(args.getInteger("limit", DEFAULT_LEGEND), 1, MOST_LEGEND);
        var marks = args.readFlag("marks", false);
        var withInterface = args.readFlag("interface", false);
        var stay = args.readFlag("stay", false);
        Eyes.ready(script);
        Eyes.settled(script);
        script.wantDepth();
        var clearing = new Clearing(false, null);
        var text = args.getText("target");
        if (text != null) {
            var found = script.read(viewer -> viewer.located(text));
            var yaw = args.getNumber(
                    "yaw", script.read(viewer -> viewer.scene().camera.getYawDegrees()));
            clearing = Eyes.moveToSee(
                    script,
                    found,
                    args.getNumber("distance"),
                    yaw,
                    args.getNumber("pitch", Eyes.DEFAULT_PITCH),
                    !args.has("pitch"));
        } else {
            var pose = Eyes.pose(script, args);
            if (pose != null) {
                Eyes.move(script, pose);
            }
            script.frames(Eyes.SETTLE_FRAMES);
        }
        Eyes.overlay(script);
        var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
        var shot = Eyes.startShot(script, withInterface);
        var sight = Eyes.visible(script, lens, limit);
        var file = Eyes.awaitShot(script, shot);
        var answer = script.read(viewer -> {
            var data = new JSONObject();
            data.put("camera", Pose.write(viewer.scene().camera));
            data.put("hour", Math.round(viewer.ui().rendering.timeOfDay.get() * 10) / 10.0);
            var skyZone = viewer.ui().rendering.getSkyZone();
            if (skyZone.hasTime()) {
                data.put("place_hour", Math.round(skyZone.hour() * 10) / 10.0);
            }
            return data;
        });
        if (stay) {
            script.keepCamera();
        } else {
            script.run(script::putCameraBack);
        }
        var seen = named(
                script, sight.seen().subList(0, Math.min(limit, sight.seen().size())));
        var image = Picture.scaled(Picture.read(file), width);
        if (marks) {
            Picture.mark(image, boxes(seen));
        }
        var bytes = Picture.jpeg(image);
        Files.write(file, bytes);
        Eyes.tidy(file);
        var memory = script.read(viewer -> viewer.agent().getSeen());
        var id = memory.nextCaptureId();
        memory.add(new Capture(
                id,
                file,
                image.getWidth(),
                image.getHeight(),
                List.of(new View(0, 0, image.getWidth(), image.getHeight(), lens.camera()))));
        if (marks) {
            var numbered = new ArrayList<Mark>();
            for (var index = 0; index < seen.size(); index++) {
                numbered.add(new Mark(index + 1, seen.get(index).placement()));
            }
            memory.marks(numbered);
        }
        answer.put("capture", id);
        answer.put("file", file.toString());
        answer.put("size", image.getWidth() + "x" + image.getHeight());
        answer.put("visible", sight.seen().size());
        answer.put("legend", legend(seen, marks));
        note(answer, sight, seen, clearing);
        return ToolResult.data(answer).withImage(bytes, "image/jpeg").withFolder(file.getParent());
    }

    private static List<Seen> named(ViewScript script, List<Seen> seen) throws ToolProblem, InterruptedException {
        var book = script.read(WorldFacts::book);
        var result = new ArrayList<Seen>();
        for (var item : seen) {
            result.add(item.with(WorldFacts.named(book, item.placement())));
        }
        return result;
    }

    private static int width(ToolArgs args) throws ToolProblem {
        return Math.clamp(args.getInteger("width", Picture.DEFAULT_WIDTH), Picture.LEAST_WIDTH, Picture.MOST_WIDTH);
    }

    private static List<Box> boxes(List<Seen> seen) {
        var boxes = new ArrayList<Box>();
        for (var index = 0; index < seen.size(); index++) {
            var area = seen.get(index).area();
            boxes.add(new Box(
                    index + 1,
                    area.left(),
                    area.top(),
                    area.right(),
                    area.bottom(),
                    seen.get(index).spotX(),
                    seen.get(index).spotY()));
        }
        return boxes;
    }

    private static JSONObject legend(List<Seen> seen, boolean numbered) {
        var columns = new JSONArray();
        if (numbered) {
            columns.add("mark");
        }
        columns.addAll(List.of("name", "mesh", "class", "point", "distance", "tile"));
        var rows = new JSONArray();
        for (var index = 0; index < seen.size(); index++) {
            var item = seen.get(index);
            var placement = item.placement();
            var row = new JSONArray();
            if (numbered) {
                row.add(index + 1);
            }
            row.add(placement.name());
            row.add(placement.mesh());
            row.add(placement.className());
            row.add(placement.point());
            row.add(Float.isNaN(item.distance()) ? null : Long.valueOf(Math.round(item.distance())));
            row.add(placement.tile());
            rows.add(row);
        }
        var legend = new JSONObject();
        legend.put("columns", columns);
        legend.put("rows", rows);
        return legend;
    }

    private static void note(JSONObject answer, Sight sight, List<Seen> seen, Clearing clearing) {
        var notes = new ArrayList<String>();
        if (clearing.moved()) {
            notes.add(CLEARED_NOTE);
        }
        if (clearing.wall() != null) {
            notes.add(clearing.wall()
                    + " stands before the target, and the camera found no way past it. Take another yaw or a"
                    + " shorter distance.");
        }
        if (!sight.tested()) {
            notes.add(UNTESTED_NOTE);
        }
        for (var item : seen) {
            if (item.placement().merged()) {
                notes.add(MERGED_NOTE);
                break;
            }
        }
        if (!notes.isEmpty()) {
            answer.put("note", String.join(" ", notes));
        }
    }

    private static ToolResult around(ViewScript script, ToolArgs args) throws Exception {
        var width = width(args);
        var count = Math.clamp(args.getInteger("views", DEFAULT_AROUND), 2, MOST_AROUND);
        var pitch = args.getNumber("pitch", AROUND_PITCH);
        var text = args.getRequired("target");
        Eyes.ready(script);
        var found = script.read(viewer -> viewer.located(text));
        var wanted = args.getNumber("distance");
        var views = new ArrayList<Sheet>();
        for (var index = 0; index < count; index++) {
            var yaw = 90 + index * 360.0 / count;
            var clearing = Eyes.moveToSee(script, found, wanted, yaw, pitch, !args.has("pitch"));
            var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
            var file = Eyes.shoot(script, false);
            views.add(new Sheet(Picture.read(file), "from " + compass(yaw + 180) + clearing.label(), lens.camera()));
            Files.deleteIfExists(file);
        }
        script.run(script::putCameraBack);
        var target = found.target();
        var answer = new JSONObject();
        answer.put("target", text);
        answer.put(
                "point",
                Math.round(target.x()) + "," + Math.round(target.y()) + "," + Math.round(Eyes.height(script, target)));
        var labels = new JSONArray();
        for (var view : views) {
            labels.add(view.label());
        }
        answer.put("views", labels);
        return sheet(script, views, width, answer);
    }

    private static ToolResult clip(ViewScript script, ToolArgs args) throws Exception {
        var width = width(args);
        var seconds = Math.clamp(args.getNumber("seconds", DEFAULT_CLIP_SECONDS), .2, MOST_CLIP_SECONDS);
        var frames = Math.clamp(args.getInteger("frames", DEFAULT_CLIP_FRAMES), 2, MOST_CLIP_FRAMES);
        Eyes.ready(script);
        var pose = Eyes.pose(script, args);
        if (pose != null) {
            Eyes.move(script, pose);
            script.frames(Eyes.SETTLE_FRAMES);
        }
        var lens = script.read(viewer -> Lens.of(viewer.scene().camera));
        var start = System.nanoTime();
        var views = new ArrayList<Sheet>();
        for (var index = 0; index < frames; index++) {
            var due = start + (long) (seconds * 1e9 * index / (frames - 1));
            script.poll(
                    viewer -> System.nanoTime() >= due ? Boolean.TRUE : null,
                    (long) (seconds * 2000) + 5000,
                    "The viewer draws no frames.");
            var file = Eyes.shoot(script, false);
            var taken = (System.nanoTime() - start) / 1e9;
            views.add(new Sheet(Picture.read(file), String.format("%.1f s", taken), lens.camera()));
            Files.deleteIfExists(file);
        }
        script.run(script::putCameraBack);
        var changed = new JSONArray();
        for (var index = 1; index < views.size(); index++) {
            changed.add(Picture.changed(
                    views.get(index - 1).image(), views.get(index).image()));
        }
        var answer = new JSONObject();
        answer.put("seconds", Math.round(seconds * 10) / 10.0);
        answer.put("frames", frames);
        answer.put("changed", changed);
        return sheet(script, views, width, answer);
    }

    private static ToolResult sheet(ViewScript script, List<Sheet> views, int width, JSONObject answer)
            throws IOException, ToolProblem, InterruptedException {
        var first = views.getFirst().image();
        var layout = Picture.layout(views.size(), width, first.getWidth() / (double) first.getHeight());
        var image = Picture.sheet(views, layout);
        var bytes = Picture.jpeg(image);
        var file = script.read(Eyes::newFile);
        Files.write(file, bytes);
        Eyes.tidy(file);
        var memory = script.read(viewer -> viewer.agent().getSeen());
        var id = memory.nextCaptureId();
        memory.add(new Capture(id, file, image.getWidth(), image.getHeight(), Picture.views(views, layout)));
        answer.put("capture", id);
        answer.put("file", file.toString());
        answer.put("size", image.getWidth() + "x" + image.getHeight());
        return ToolResult.data(answer).withImage(bytes, "image/jpeg").withFolder(file.getParent());
    }
}
