package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Kind;
import com.rost.cruma.core.unreal.edit.MapTileOperation.TileRef;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.launch.TileOperationWork;
import com.rost.cruma.viewer.launch.TileOperationWork.Listener;
import com.rost.cruma.viewer.launch.TileOperationWork.Planned;
import com.rost.cruma.viewer.launch.TileOperationWork.Ran;
import com.rost.cruma.viewer.launch.TileOperationWork.Request;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class TileMoves {
    static final List<String> ACTIONS = List.of("plan", "run", "export", "undo", "backups");
    private static final String TOOL = "tiles_move";
    private static final Pattern TILE = Pattern.compile("(\\d+)_(\\d+)(_classic)?", Pattern.CASE_INSENSITIVE);
    private static final int BACKUPS = 20;

    private TileMoves() {}

    static ToolResult call(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var action = args.getText("action", "plan").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be plan, run, export, undo or backups.");
        }
        if ((action.equals("run") || action.equals("undo")) && args.getText("client") == null) {
            throw new ToolProblem(
                    "client is missing: " + action + " changes a client, so name it, best a copy of the client.");
        }
        var client = hub.resolveClient(args.getText("client"));
        var config = hub.getConfigFor(client);
        switch (action) {
            case "backups" -> {
                return backups(client);
            }
            case "undo" -> {
                ClientWriteTools.requireWrite(hub, TOOL, action);
                return undo(
                        args,
                        config,
                        ClientWriteTools.requiredFolder(args, "name a backup folder from action backups."));
            }
            default -> {
            }
        }
        var export = action.equals("export");
        if (action.equals("run")) {
            ClientWriteTools.requireWrite(hub, TOOL, action);
        }
        var request = request(args, hub, client, config, export);
        job.setProgress("Checking the tiles");
        var planned = TileOperationWork.plan(request);
        if (planned.plan() == null) {
            throw new ToolProblem(
                    planned.targetExists() && request.kind() != Kind.SWAP && !request.replace()
                            ? request.target() + ".unr is already in " + client.getFileName()
                                    + ". Pass replace to write over it, with a backup for undo, or kind swap."
                            : planned.problem());
        }
        if (action.equals("plan")) {
            return ToolResult.data(plan(planned, config));
        }
        var folder =
                export ? ClientWriteTools.newFolder(args, hub, request.source() + " to " + request.target()) : null;
        if (folder != null) {
            hub.getRoom().require(folder.getParent(), hub.getAccess());
        }
        var ran = run(planned, folder, args.readFlag("backup", true), job);
        var result = ran.result();
        var answer = new JSONObject();
        answer.put(
                "done",
                folder != null
                        ? "Exported " + request.target() + "."
                        : switch (request.kind()) {
                            case COPY -> "Copied " + request.source() + " to " + request.target() + ".";
                            case MOVE -> "Moved " + request.source() + " to " + request.target() + ".";
                            case SWAP -> "Swapped " + request.source() + " and " + request.target() + ".";
                        });
        answer.put("details", ran.details());
        answer.put("written", paths(result.written()));
        if (!result.removed().isEmpty()) {
            answer.put("removed", paths(result.removed()));
        }
        if (folder != null) {
            hub.getRoom().wrote(folder);
            answer.put("folder", folder.toString());
            return ToolResult.data(answer)
                    .withFolder(folder)
                    .saying("Exported " + request.target() + " into " + folder);
        }
        if (result.backup() != null) {
            answer.put("backup", result.backup().toString());
            answer.put("undo", TOOL + " undo with this backup folder puts the files back, while they stay as written.");
        }
        answer.put(
                "note",
                "A viewer that holds these tiles shows the old maps until it opens the world again, and so does the"
                        + " map image of the start menu. Neighbouring tiles do not change.");
        var maps = planned.plan().maps();
        return ToolResult.data(answer).withFolder(maps).saying(answer.getString("done"));
    }

    private static Ran run(Planned planned, @Nullable Path folder, boolean keep, Job job) throws ToolProblem {
        try {
            return TileOperationWork.run(
                    planned,
                    folder,
                    keep,
                    new Listener() {
                        @Override
                        public void step(String text) {
                            job.setProgress(text);
                        }

                        @Override
                        public void stoppable(boolean value) {
                        }
                    },
                    job::isCancelled);
        } catch (CancellationException stopped) {
            throw new ToolProblem("Stopped. Nothing was changed.");
        } catch (IOException | RuntimeException failed) {
            throw new ToolProblem(
                    "Nothing was changed: " + (failed.getMessage() == null ? failed.toString() : failed.getMessage()));
        }
    }

    private static JSONObject plan(Planned planned, Config config) {
        var plan = planned.plan();
        var request = planned.request();
        var answer = new JSONObject();
        answer.put(
                "operation",
                request.kind().getLabel()
                        + " "
                        + request.source()
                        + (request.kind() == Kind.SWAP ? " and " : " to ")
                        + request.target());
        answer.put(
                "offset",
                signed(request.target().x() - request.source().x()) + ", "
                        + signed(request.target().y() - request.source().y()) + " tiles");
        var writes = new JSONArray();
        for (var write : plan.writes()) {
            writes.add(write.fileName());
        }
        answer.put("writes", writes);
        if (!plan.replaced().isEmpty()) {
            answer.put("replaces", names(plan.replaced()));
        }
        if (!plan.removed().isEmpty()) {
            answer.put("removes", names(plan.removed()));
        }
        if (!plan.warnings().isEmpty()) {
            answer.put("warnings", plan.warnings());
        }
        var server = new JSONArray();
        for (var part : planned.server()) {
            if (part.summary().isEmpty()) {
                continue;
            }
            var row = new JSONObject();
            row.put("text", part.summary());
            if (part.warning()) {
                row.put("warning", true);
            }
            server.add(row);
        }
        if (!server.isEmpty()) {
            answer.put("server_files", server);
        }
        if (planned.pathNodes() && !request.rebuildPathNodes()) {
            answer.put("pathnodes", "The geodata folder has a pathnode graph. pathnodes rebuilds it after run.");
        }
        var world = config.viewer().world();
        if (request.geodata() == null
                && folder(world.geodata() == null ? null : world.geodata().path()) != null) {
            answer.put("geodata", "The client's settings name a geodata folder. geodata takes the region along.");
        }
        if (request.scripts() == null
                && request.sourceRoot().equals(request.root())
                && folder(world.spawns() == null ? null : world.spawns().scriptPath()) != null) {
            answer.put(
                    "spawns", "The client's settings name a script folder. spawns takes the NPCs of the tile along.");
        }
        answer.put("note", "Neighbouring tiles do not change, so terrain and water may not meet at the edges.");
        answer.put(
                "next",
                "run does it, with a backup for undo when backup is left on. export writes the files into a new folder"
                        + " instead.");
        return answer;
    }

    private static ToolResult undo(ToolArgs args, Config config, Path backup) throws ToolProblem {
        try {
            var undone = TileOperationWork.undo(backup, geodata(args, config, false));
            var answer = new JSONObject();
            answer.put("restored", paths(undone.restored()));
            answer.put("details", undone.details());
            return ToolResult.data(answer)
                    .withFolder(backup.getParent())
                    .saying("Restored " + undone.restored().size() + " files of " + backup.getFileName());
        } catch (IOException failed) {
            throw new ToolProblem("Nothing was restored: " + failed.getMessage());
        }
    }

    private static ToolResult backups(Path client) {
        var all = TileOperationWork.backups(client);
        var rows = new JSONArray();
        for (var backup : all.subList(0, Math.min(BACKUPS, all.size()))) {
            var row = new JSONObject();
            row.put("operation", backup.operation() + " " + backup.source() + " to " + backup.target());
            row.put("time", backup.time().toString());
            row.put("folder", backup.folder().toString());
            if (backup.undone()) {
                row.put("restored", true);
            }
            rows.add(row);
        }
        var answer = new JSONObject();
        answer.put("client", client.getFileName().toString());
        answer.put("backups", rows);
        if (all.size() > BACKUPS) {
            answer.put("more", all.size() - BACKUPS);
        }
        return ToolResult.data(answer);
    }

    private static Request request(ToolArgs args, AgentHub hub, Path client, Config config, boolean export)
            throws ToolProblem {
        var kind = switch (args.getText("kind", "copy").toLowerCase(Locale.ROOT)) {
            case "copy" -> Kind.COPY;
            case "move" -> Kind.MOVE;
            case "swap" -> Kind.SWAP;
            default -> throw new ToolProblem("kind must be copy, move or swap.");
        };
        var source = tile(args, "from");
        var target = tile(args, "to");
        var other = args.getText("from_client");
        var sourceRoot = other == null ? client : hub.resolveClient(other);
        var fromOther = !sourceRoot
                .toAbsolutePath()
                .normalize()
                .equals(client.toAbsolutePath().normalize());
        var mode = args.getText("geodata");
        if (mode != null && !mode.equalsIgnoreCase("copy") && !mode.equalsIgnoreCase("build")) {
            throw new ToolProblem("geodata must be copy or build.");
        }
        var geodata = mode == null ? null : geodata(args, config, true);
        var spawns = args.readFlag("spawns", false);
        if (spawns && fromOther) {
            throw new ToolProblem("The spawns go with a tile of this client only.");
        }
        Path scripts = null;
        if (spawns) {
            var asked = args.getText("scripts");
            var world = config.viewer().world();
            var folder = folder(
                    asked != null
                            ? ClientWriteTools.fullPath(asked, "scripts")
                            : world.spawns() == null ? null : world.spawns().scriptPath());
            if (folder == null) {
                throw new ToolProblem(
                        "spawns needs the server's script folder with npcpos.txt. Pass scripts, or ask the person to"
                                + " set it in Settings for this client.");
            }
            scripts = SpawnSource.resolveFolder(folder);
        }
        if (args.readFlag("pathnodes", false) && geodata == null) {
            throw new ToolProblem("pathnodes goes with geodata: pass geodata copy or build.");
        }
        var build = fromOther || "build".equalsIgnoreCase(mode);
        return new Request(
                client,
                sourceRoot,
                kind,
                source,
                target,
                args.readFlag("replace", false),
                geodata,
                scripts,
                build,
                args.readFlag("pathnodes", false) && !export);
    }

    private static @Nullable Path geodata(ToolArgs args, Config config, boolean needed) throws ToolProblem {
        var asked = args.getText("geodata_folder");
        var settings = config.viewer().world().geodata();
        var folder = folder(
                asked != null
                        ? ClientWriteTools.fullPath(asked, "geodata_folder")
                        : settings == null ? null : settings.path());
        if (folder == null && needed) {
            throw new ToolProblem(
                    "geodata needs the server's geodata folder. Pass geodata_folder, or ask the person to set it in"
                            + " Settings for this client.");
        }
        return folder;
    }

    private static @Nullable Path folder(@Nullable Path path) {
        return path == null || path.toString().isEmpty() || !Files.isDirectory(path) ? null : path;
    }

    private static TileRef tile(ToolArgs args, String name) throws ToolProblem {
        var text = args.getText(name);
        if (text == null) {
            throw new ToolProblem(
                    name + " is missing: give a tile such as 22_22, or 21_26_Classic for the Classic world.");
        }
        var match = TILE.matcher(text);
        if (!match.matches()) {
            throw new ToolProblem(name + " must be a tile such as 22_22, or 21_26_Classic for the Classic world, not \""
                    + text + "\".");
        }
        return new TileRef(
                Integer.parseInt(match.group(1)),
                Integer.parseInt(match.group(2)),
                match.group(3) == null ? WorldVariant.MAIN : WorldVariant.CLASSIC);
    }

    private static JSONArray paths(List<Path> files) {
        var list = new JSONArray();
        for (var file : files) {
            list.add(file.toString());
        }
        return list;
    }

    private static JSONArray names(List<Path> files) {
        var list = new JSONArray();
        for (var file : files) {
            list.add(file.getFileName().toString());
        }
        return list;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
