package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.SceneCheck;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.editing.ClientInstaller;
import com.rost.cruma.viewer.editing.SceneFileStore;
import com.rost.cruma.viewer.editing.SceneInto;
import com.rost.cruma.viewer.editing.SceneInto.Prepared;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class SceneInstall {
    static final List<String> ACTIONS = List.of("check", "write", "install", "restore");
    private static final String TOOL = "scene_install";
    private static final int ROWS = 20;
    private static final String UNPROVEN = "No client has run such a file yet, so nobody knows if the game takes it.";

    private SceneInstall() {}

    static ToolResult call(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var action = args.getText("action", "check").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be check, write, install or restore.");
        }
        if (action.equals("install") || action.equals("restore")) {
            ClientWriteTools.requireWrite(hub, TOOL, action);
            var folder = ClientWriteTools.requiredFolder(args, "name the folder that " + TOOL + " write made.");
            return action.equals("install") ? install(args, hub, folder) : restore(folder);
        }
        var name = args.getText("scene");
        if (name == null) {
            throw new ToolProblem("scene is missing: name your scene from scene_list.");
        }
        var file = own(hub, name);
        var document = read(file, name);
        var client = clientOf(hub, args.getText("client"), document);
        job.setProgress("Writing the file in memory and reading it back");
        var prepared = prepare(hub, client, document, args.getText("replaces"));
        var answer = describe(prepared);
        if (action.equals("check")) {
            answer.put(
                    "next",
                    prepared.canWrite()
                            ? "write makes the file in a new folder. The client does not change."
                            : "There is nothing to write.");
            return ToolResult.data(answer);
        }
        if (!prepared.canWrite()) {
            throw new ToolProblem(
                    "There is nothing to write. " + why(prepared) + " Call with action check to see it all.");
        }
        var folder = ClientWriteTools.newFolder(args, hub, document.name());
        if (Files.exists(folder)) {
            throw new ToolProblem("A write goes into a new folder, and " + folder
                    + " is there. Leave folder out, or name a new one.");
        }
        hub.getRoom().require(folder.getParent(), hub.getAccess());
        job.setProgress("Writing the file");
        List<Path> written;
        try {
            written = prepared.service().publish(prepared.plan(), folder, job::isCancelled);
        } catch (CancellationException stopped) {
            throw new ToolProblem("Stopped. Nothing was written.");
        } catch (IOException failed) {
            throw new ToolProblem("The file was not written: " + failed.getMessage());
        }
        hub.getRoom().wrote(folder);
        var result = new JSONObject();
        result.put("wrote", written.size());
        result.put("folder", folder.toString());
        result.put("file", prepared.file());
        result.put("client", client.toString());
        result.put(
                "next",
                "The client has not changed. install with this folder and into, best a copy of the client, puts the"
                        + " file in, and restore takes it out. "
                        + UNPROVEN);
        return ToolResult.data(result)
                .withFolder(folder)
                .saying("Wrote " + prepared.file() + " of " + document.name() + " into " + folder);
    }

    private static ToolResult install(ToolArgs args, AgentHub hub, Path folder) throws ToolProblem {
        var into = args.getText("into");
        var asked = args.getText("client");
        if (into == null && asked == null) {
            throw new ToolProblem(
                    "into is missing: name the client folder that takes the file, best a copy of the client.");
        }
        var client = into != null ? ClientWriteTools.fullPath(into, "into") : hub.resolveClient(asked);
        List<Path> files;
        try {
            var plan = ClientInstaller.inspect(folder, client);
            var problem = plan.problem();
            if (problem != null) {
                throw new ToolProblem(problem);
            }
            files = ClientInstaller.install(plan);
        } catch (IOException failed) {
            throw new ToolProblem("Nothing was installed: " + failed.getMessage());
        }
        var answer = new JSONObject();
        answer.put("installed", files.size());
        answer.put("client", client.toString());
        var names = new JSONArray();
        for (var file : files) {
            names.add(client.relativize(file).toString().replace('\\', '/'));
        }
        answer.put("files", names);
        answer.put(
                "warning", UNPROVEN + " The old file is in " + folder + ", and restore with this folder puts it back.");
        answer.put(
                "note",
                "A viewer that holds this client reads the file again when it opens the world again. Restore this"
                    + " folder before you install another file into this client, as a new file would start from this"
                    + " one.");
        return ToolResult.data(answer).withFolder(folder).saying("Installed " + names + " into " + client);
    }

    private static ToolResult restore(Path folder) throws ToolProblem {
        var client = ClientInstaller.installedClient(folder);
        if (client == null) {
            throw new ToolProblem("This folder is not installed in a client, so there is nothing to restore.");
        }
        List<Path> files;
        try {
            files = ClientInstaller.restore(folder);
        } catch (IOException failed) {
            throw new ToolProblem("Nothing was restored: " + failed.getMessage());
        }
        var answer = new JSONObject();
        answer.put("restored", files.size());
        answer.put("client", client.toString());
        return ToolResult.data(answer)
                .withFolder(folder)
                .saying("Restored " + files.size() + " client file" + (files.size() == 1 ? "" : "s") + " in " + client);
    }

    private static Prepared prepare(AgentHub hub, Path client, SceneDocument document, @Nullable String replaces)
            throws ToolProblem {
        try {
            if (!document.map().isEmpty()) {
                if (document.basedOn().isEmpty()) {
                    throw new ToolProblem("The game picks a scene of a lobby by its Tag, so a new scene of "
                            + document.map() + " would never play. It can go into a video.");
                }
                return SceneInto.lobby(client, document, false);
            }
            var target = target(client, document, replaces);
            var variant = document.variant().isEmpty()
                    ? hub.getConfigFor(client).viewer().world().variant()
                    : WorldVariant.parse(document.variant());
            return SceneInto.scene(
                    client, variant, replaces == null ? document : document.withReplaces(target), target);
        } catch (IOException | IllegalArgumentException failed) {
            throw new ToolProblem("The file could not be made: " + failed.getMessage());
        }
    }

    private static String target(Path client, SceneDocument document, @Nullable String replaces) throws ToolProblem {
        var names = new ArrayList<String>();
        for (var scene : GameScenes.find(client)) {
            names.add(scene.name());
        }
        if (names.isEmpty()) {
            throw new ToolProblem(client.getFileName()
                    + " has no cut scenes, so a scene has no place to go. A number of its own is not offered.");
        }
        var wanted = replaces != null ? replaces : document.target();
        for (var name : names) {
            if (name.equalsIgnoreCase(wanted)) {
                return name;
            }
        }
        var some = String.join(", ", names.subList(0, Math.min(8, names.size())));
        throw new ToolProblem((wanted.isEmpty() ? "" : client.getFileName() + " has no cut scene " + wanted + ". ")
                + "Give replaces: the cut scene of the client this one goes over. It keeps that name and its number,"
                + " as there are no new numbers. Such as "
                + some
                + ", of "
                + names.size()
                + ". scene_list lists them.");
    }

    private static JSONObject describe(Prepared prepared) {
        var answer = new JSONObject();
        var document = prepared.document();
        answer.put("scene", document.name());
        answer.put("file", prepared.file());
        if (prepared.check() != null) {
            scene(answer, prepared, prepared.check());
        }
        if (prepared.lobby() != null) {
            lobby(answer, prepared);
        }
        if (!prepared.notes().isEmpty()) {
            answer.put("notes", prepared.notes());
        }
        if (prepared.plan() != null && !prepared.plan().getErrors().isEmpty()) {
            answer.put("errors", prepared.plan().getErrors());
        }
        answer.put("can_write", prepared.canWrite());
        return answer;
    }

    private static void scene(JSONObject answer, Prepared prepared, SceneCheck check) {
        answer.put("replaces", check.scene());
        answer.put("seconds", ClientWriteTools.round(check.seconds()));
        answer.put("client_seconds", ClientWriteTools.round(check.originalSeconds()));
        if (check.runsLonger()) {
            answer.put(
                    "longer",
                    "It plays longer than " + check.scene()
                            + ". play_time of ScenePlayerData.dat may cut it, and nobody knows if the client reads"
                            + " that number.");
        }
        if (!prepared.keptObjects()) {
            answer.put(
                    "new_objects",
                    "This scene comes from another file or class, so all its items go in as new objects.");
        }
        rows(answer, "will_not_hold", check.lost(), loss -> {
            var row = new JSONObject();
            row.put("seconds", ClientWriteTools.round(loss.seconds()));
            row.put("part", loss.part());
            row.put("field", loss.field());
            row.put("value", loss.value());
            return row;
        });
        rows(answer, "client_lacks", check.lacks(), lack -> {
            var row = new JSONObject();
            row.put("seconds", ClientWriteTools.round(lack.seconds()));
            row.put("lacks", SceneInto.text(lack));
            return row;
        });
        rows(answer, "marks_that_run", check.marks(), mark -> {
            var row = new JSONObject();
            row.put("seconds", ClientWriteTools.round(mark.seconds()));
            row.put("actor", mark.index());
            row.put("npc", mark.npc());
            row.put("sequence", mark.sequence());
            return row;
        });
        if (!check.path().isEmpty()) {
            rows(answer, "path_error", check.path(), error -> {
                var row = new JSONObject();
                row.put("rate", error.rate());
                row.put("units", Math.round(error.units()));
                row.put("degrees", ClientWriteTools.round(error.degrees()));
                row.put("first_frames_degrees", ClientWriteTools.round(error.startDegrees()));
                row.put("roll_degrees", ClientWriteTools.round(error.rollDegrees()));
                row.put("fov_degrees", ClientWriteTools.round(error.fovDegrees()));
                return row;
            });
        }
    }

    private static void lobby(JSONObject answer, Prepared prepared) {
        var edits = prepared.lobby();
        var check = edits.check();
        answer.put("map", prepared.document().map());
        answer.put("based_on", prepared.document().basedOn());
        if (!check.alsoChanged().isEmpty()) {
            answer.put("also_changes", check.alsoChanged());
        }
        var refused = new ArrayList<>(edits.refused());
        for (var refusal : check.refused()) {
            refused.add(refusal.reason());
        }
        if (!refused.isEmpty()) {
            answer.put("refused", refused);
        }
        if (!edits.notes().isEmpty()) {
            answer.put("map_notes", edits.notes());
        }
        if (!edits.stays().isEmpty()) {
            answer.put("stays_in_video", edits.stays());
        }
        if (refused.isEmpty() && edits.edits().isEmpty()) {
            answer.put("nothing_to_write", "This copy holds the numbers of the map.");
            return;
        }
        rows(answer, "scenes_that_change", check.scenes(), change -> {
            var row = new JSONObject();
            row.put("tag", change.tag());
            row.put("before", ClientWriteTools.round(change.before().seconds()));
            row.put("after", ClientWriteTools.round(change.after().seconds()));
            if (!change.asked()) {
                row.put("through", "a shared object");
            }
            return row;
        });
        rows(answer, "numbers_that_change", check.changes(), change -> {
            var row = new JSONObject();
            var object = change.object();
            row.put("object", object.substring(object.lastIndexOf('.') + 1));
            row.put("field", change.field());
            row.put("before", change.before());
            row.put("after", change.after());
            return row;
        });
        answer.put("file_keeps_size", check.grown() == 0);
    }

    private static String why(Prepared prepared) {
        var lobby = prepared.lobby();
        if (lobby != null) {
            var reasons = new ArrayList<>(lobby.refused());
            for (var refusal : lobby.check().refused()) {
                reasons.add(refusal.reason());
            }
            return reasons.isEmpty() ? "This copy holds the numbers of the map." : String.join(" ", reasons);
        }
        if (prepared.plan() != null && !prepared.plan().getErrors().isEmpty()) {
            return String.join(" ", prepared.plan().getErrors());
        }
        return "The scene is the same as " + prepared.check().scene() + " of the client.";
    }

    private static <T> void rows(JSONObject answer, String key, List<T> items, Function<T, JSONObject> row) {
        var list = new JSONArray();
        for (var item : items.subList(0, Math.min(ROWS, items.size()))) {
            list.add(row.apply(item));
        }
        answer.put(key, list);
        if (items.size() > ROWS) {
            answer.put(key + "_more", items.size() - ROWS);
        }
    }

    private static Path own(AgentHub hub, String name) throws ToolProblem {
        var folder = hub.getConfig().viewer().director().scenesFolder();
        var file = new SceneFileStore(folder, folder).getFile(name);
        if (!Files.isRegularFile(file)) {
            throw new ToolProblem("You have no scene named " + name + ". " + TOOL
                    + " takes your own scenes: copy a cut scene first with film_edit copy_of, and change it"
                    + " with film_actors.");
        }
        return file;
    }

    private static SceneDocument read(Path file, String name) throws ToolProblem {
        try {
            return SceneFileStore.read(file);
        } catch (IOException refused) {
            throw new ToolProblem("The scene " + name + " does not open: " + refused.getMessage());
        }
    }

    private static Path clientOf(AgentHub hub, @Nullable String asked, SceneDocument document) throws ToolProblem {
        if (asked != null || document.client().isEmpty()) {
            return hub.resolveClient(asked);
        }
        try {
            return hub.resolveClient(document.client());
        } catch (ToolProblem unknown) {
            throw new ToolProblem("The scene was made on " + document.client()
                    + ", which Cruma does not know here. Pass client with its folder.");
        }
    }
}
