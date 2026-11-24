package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditGround.Answer;
import com.rost.cruma.viewer.agent.edit.EditGround.Ask;
import com.rost.cruma.viewer.editing.ActorClipboard;
import com.rost.cruma.viewer.editing.ActorClipboard.Clip;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import com.rost.cruma.viewer.editing.MeshFavorites;
import com.rost.cruma.viewer.editing.MeshLibrary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class PlaceExtras {
    private static final long GROUND_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final float ABOVE_TARGET = 64;
    private static final float ABOVE_TERRAIN = 1024;
    private static final float ABOVE_ALL = 16000;

    private PlaceExtras() {}

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var list = args.getText("list");
        if (list != null) {
            var limit = EditSteps.limit(args);
            return EditSteps.editing(
                    viewer, session, () -> Step.done(list(list, args.getText("prefab"), viewer, session, limit)));
        }
        var text = args.getRequired("prefab");
        var clip = clip(text);
        var target = EditSteps.locate(viewer, session, args.getRequired("target"));
        var onGround = args.readFlag("on_ground", false);
        if (!onGround && target.z() == null) {
            throw new ToolProblem(target.text() + " has no height. Give a point x,y,z, or use on_ground.");
        }
        var loaded = EditSteps.loadedMaps(viewer, session);
        var map = WorldTile.mapAt(target.x(), target.y(), loaded);
        if (!loaded.contains(map)) {
            throw new ToolProblem("The map " + map + " is not loaded in this viewer. Open it with world_open.");
        }
        var name = name(text, clip);
        var x = (float) target.x();
        var y = (float) target.y();
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.opening(session, List.of(map), () -> {
                    if (!onGround) {
                        return Step.done(placed(
                                viewer,
                                session,
                                clip,
                                new Vector3(x, y, target.z().floatValue()),
                                name,
                                null));
                    }
                    var probe = session.getGroundProbe();
                    return EditSteps.after(
                            Step.until(
                                    () -> probe.isReady(x, y),
                                    GROUND_MILLIS,
                                    "The Terrain could not be read in time.",
                                    () -> ToolResult.text("")),
                            () -> {
                                var terrain = probe.getCurrent(x, y);
                                var top = target.z() != null
                                        ? target.z().floatValue() + ABOVE_TARGET
                                        : terrain != null ? terrain + ABOVE_TERRAIN : ABOVE_ALL;
                                var answers = new ArrayList<@Nullable Answer>();
                                return EditGround.read(
                                        viewer, session, List.of(Ask.forNew(map, x, y, top)), answers, () -> {
                                            var answer = answers.getFirst();
                                            if (answer == null) {
                                                throw new ToolProblem(
                                                        "No Terrain or BSP floor lies under " + target.text() + ".");
                                            }
                                            return Step.done(placed(
                                                    viewer,
                                                    session,
                                                    clip,
                                                    new Vector3(x, y, answer.z()),
                                                    name,
                                                    answer));
                                        });
                            });
                }));
    }

    static Clip clip(String text) throws ToolProblem {
        try {
            if (text.startsWith("{")) {
                return ActorClipboard.decode(text);
            }
            var file = file(text);
            if (!Files.isRegularFile(file)) {
                throw new ToolProblem("There is no prefab " + file + ". actors_place with list prefabs lists them.");
            }
            return ActorClipboard.decode(Files.readString(file));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new ToolProblem("The prefab cannot be read: " + unreadable.getMessage());
        }
    }

    private static Path file(String text) throws ToolProblem {
        try {
            var path = Path.of(text);
            return ActorClipboard.prefabFile(
                    path.isAbsolute() ? path : ActorClipboard.prefabFolder().resolve(path));
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("prefab is not a name or a path: " + wrong.getMessage());
        }
    }

    private static String name(String text, Clip clip) {
        if (text.startsWith("{")) {
            return clip.items().size() == 1
                    ? EditSteps.subject(List.of(clip.items().getFirst().mesh()))
                    : clip.items().size() + " actors";
        }
        var file = Path.of(text).getFileName().toString();
        return file.toLowerCase(Locale.ROOT).endsWith(".json") ? file.substring(0, file.length() - 5) : file;
    }

    private static ToolResult placed(
            ViewerContext viewer,
            EditingSession session,
            Clip clip,
            Vector3 point,
            String name,
            @Nullable Answer ground)
            throws ToolProblem {
        var loaded = EditSteps.loadedMaps(viewer, session);
        var home = WorldTile.mapAt(point.x(), point.y(), loaded);
        var placements = ActorClipboard.placements(clip, point, location -> {
            var map = WorldTile.mapAt(location.x(), location.y(), loaded);
            return loaded.contains(map) ? map : home;
        });
        var maps = new LinkedHashSet<String>();
        placements.forEach(placement -> maps.add(placement.map()));
        for (var map : maps) {
            if (!session.getOpenedMaps().contains(map)) {
                session.openMap(map);
            }
        }
        var label = EditSteps.AI + "Place " + name;
        var paths = session.placeMany(placements, label, null);
        var data = new JSONObject();
        data.put("placed", paths.size());
        data.put("prefab", name);
        data.put("at", EditSteps.point(point));
        if (ground != null) {
            data.put("ground_is", ground.from());
        }
        data.put("actors", paths.subList(0, Math.min(20, paths.size())));
        data.put("step", label);
        return ToolResult.data(data);
    }

    private static ToolResult list(
            String which, @Nullable String folderText, ViewerContext viewer, EditingSession session, int limit)
            throws ToolProblem {
        var items = new ArrayList<String>();
        var data = new JSONObject();
        switch (which.toLowerCase(Locale.ROOT)) {
            case "favourites" -> {
                var directory = viewer.configDirectory();
                if (directory == null) {
                    throw new ToolProblem("This viewer keeps no files, so it has no favourites.");
                }
                var file = new MeshLibrary(directory, session.getClientRoot())
                        .getDirectory()
                        .resolve(MeshFavorites.FILE);
                items.addAll(new MeshFavorites(file).list());
            }
            case "recent" -> {
                var meshes = new LinkedHashSet<String>();
                for (var change : session.getChanges()) {
                    if (change.kinds().contains(ChangeKind.NEW)
                            && !change.mesh().isBlank()) {
                        meshes.add(change.mesh());
                    }
                }
                items.addAll(meshes);
                data.put("note", "The meshes of the actors placed in this session, by the person or an agent.");
            }
            case "prefabs" -> {
                var folder = folderText == null ? ActorClipboard.prefabFolder() : Path.of(folderText);
                data.put("folder", folder.toString());
                for (var file : ActorClipboard.prefabs(folder)) {
                    items.add(file.toString());
                }
            }
            default -> throw new ToolProblem("list must be favourites, recent or prefabs.");
        }
        data.put("count", items.size());
        data.put(which, new JSONArray(new ArrayList<Object>(items.subList(0, Math.min(limit, items.size())))));
        if (items.size() > limit) {
            data.put("more", items.size() - limit);
        }
        return ToolResult.data(data);
    }
}
