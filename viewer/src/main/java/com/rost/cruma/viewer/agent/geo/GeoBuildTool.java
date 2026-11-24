package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.BUILD_POLL_MILLIS;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.STEPS_SHOWN;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.TILE;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.format;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.requireServer;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.startPoints;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.zones;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.geodata.GeodataBuildJob;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.PathNodes;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.Progress;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.StartSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class GeoBuildTool {
    private GeoBuildTool() {}

    static void requireGraphFolder(Path folder, @Nullable GeoFormat format, @Nullable ZoneTable table)
            throws ToolProblem {
        if (format == GeoFormat.L2J) {
            throw new ToolProblem("The pathnode graph belongs to L2OFF servers. Leave out format, or give L2OFF.");
        }
        if (!Files.isDirectory(folder)) {
            throw new ToolProblem("There is no folder " + folder + ".");
        }
        requireServer(true, table, folder);
        try (var files = Files.list(folder)) {
            if (files.noneMatch(file -> GeoFormat.parse(file.getFileName().toString()) != null
                    && file.getFileName().toString().endsWith("_conv.dat"))) {
                throw new ToolProblem("The folder has no L2OFF region files, X_Y_conv.dat, to build a graph from.");
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("The folder cannot be read: " + unreadable.getMessage());
        }
    }

    static ToolResult build(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException {
        if (args.readFlag("path_nodes", false)
                && args.list("tiles").isEmpty()
                && args.getText("target") == null
                && !args.readFlag("all", false)) {
            return rebuildGraph(
                    args, Path.of(args.getRequired("folder")).toAbsolutePath().normalize(), job);
        }
        var client = hub.resolveClient(args.getText("client"));
        var config = hub.getConfigFor(client);
        var variant = config.viewer().world().variant();
        var folder = Path.of(args.getRequired("folder")).toAbsolutePath().normalize();
        var format = format(args);
        var pathNodes = args.readFlag("path_nodes", false);
        var table = zones(args);
        if (pathNodes && format == GeoFormat.L2J) {
            throw new ToolProblem("The pathnode graph belongs to L2OFF servers. Leave out path_nodes, or build L2OFF.");
        }
        requireServer(pathNodes, table, folder);
        var chosen = format == null ? GeoFormat.L2OFF : format;
        var all = args.readFlag("all", false);
        var tiles = tiles(args, config);
        if (tiles.isEmpty() && !all) {
            throw new ToolProblem("Name what to build: tiles, a target, or all for every map of the client.");
        }
        var near = tiles.isEmpty() ? new GeoTile(20, 18) : tiles.getFirst();
        requireMaps(client, config, tiles, near);
        var replace = args.readFlag("replace", false);
        if (pathNodes
                && !replace
                && (Files.exists(folder.resolve("pathnode.idx")) || Files.exists(folder.resolve("pathnode.bin")))) {
            throw new ToolProblem(folder
                    + " has a pathnode graph, and path_nodes replaces it with no backup. Give replace to go on.");
        }
        var skipped = new ArrayList<String>();
        for (var tile : tiles) {
            if (!replace && Files.isRegularFile(folder.resolve(chosen.getFileName(tile)))) {
                skipped.add(GeoWords.region(tile));
            }
        }
        var npcpos = args.getText("npcpos");
        var sources = npcpos == null ? startPoints(config.viewer().world()) : new StartSources(Path.of(npcpos));
        var build = new GeodataBuildJob();
        job.onCancel(build::stop);
        build.start(
                client,
                variant,
                folder,
                chosen,
                near,
                all ? null : tiles,
                replace,
                pathNodes ? new PathNodes(table) : null,
                sources);
        try {
            while (build.getProgress().running()) {
                job.setProgress(progress(build.getProgress()));
                Thread.sleep(BUILD_POLL_MILLIS);
            }
        } catch (InterruptedException stopped) {
            build.stop();
            throw stopped;
        }
        var answer = built(build, folder, chosen, tiles, skipped);
        return sources.npcpos() == null
                ? answer
                : answer.withText("Start points also came from " + sources.npcpos() + ".");
    }

    private static ToolResult rebuildGraph(ToolArgs args, Path folder, Job job)
            throws ToolProblem, InterruptedException {
        var table = zones(args);
        requireGraphFolder(folder, format(args), table);
        var hadGraph = Files.exists(folder.resolve("pathnode.idx")) || Files.exists(folder.resolve("pathnode.bin"));
        var started = System.nanoTime();
        var build = new GeodataBuildJob();
        job.onCancel(build::stop);
        build.rebuildPathNodes(folder, table, true);
        try {
            while (build.getProgress().running()) {
                job.setProgress(progress(build.getProgress()));
                Thread.sleep(BUILD_POLL_MILLIS);
            }
        } catch (InterruptedException stopped) {
            build.stop();
            throw stopped;
        }
        var end = build.getProgress();
        if (!end.error().isBlank()) {
            throw new ToolProblem("The pathnode rebuild failed, and the old graph stays: " + end.error());
        }
        var answer = new JSONObject();
        answer.put("folder", folder.toString());
        answer.put("seconds", Math.round((System.nanoTime() - started) / 1e9));
        if (end.stopped()) {
            answer.put("stopped", "The rebuild was stopped, and the old graph stays as it was.");
        } else {
            answer.put("pathnode_regions", end.graphRegions());
            answer.put(
                    "done",
                    hadGraph
                            ? "The graph is new. The old graph files are in a dated backup folder beside the folder."
                            : "The folder has a pathnode graph now.");
        }
        answer.put("graph", GeoRoutes.graphState(folder, () -> false));
        return ToolResult.data(answer).withFolder(folder);
    }

    private static void requireMaps(Path client, Config config, List<GeoTile> tiles, GeoTile near) throws ToolProblem {
        List<GeoTile> maps;
        try {
            maps = ClientGeodataBuild.findRegions(
                    client, config.viewer().world().variant(), near);
        } catch (IOException unreadable) {
            throw new ToolProblem("The maps of the client cannot be read: " + unreadable.getMessage());
        }
        var absent = new ArrayList<String>();
        for (var tile : tiles) {
            if (!maps.contains(tile)) {
                absent.add(GeoWords.region(tile));
            }
        }
        if (!absent.isEmpty()) {
            throw new ToolProblem("The client has no map for " + String.join(", ", absent) + ".");
        }
    }

    private static List<GeoTile> tiles(ToolArgs args, Config config) throws ToolProblem {
        var tiles = new LinkedHashSet<GeoTile>();
        for (var word : args.list("tiles")) {
            var name = TILE.matcher(word);
            if (!name.matches()) {
                throw new ToolProblem("\"" + word + "\" is no region. Name one like 22_22.");
            }
            tiles.add(new GeoTile(Integer.parseInt(name.group(1)), Integer.parseInt(name.group(2))));
        }
        var text = args.getText("target");
        if (text != null) {
            var target = Target.parse(text);
            switch (target.kind()) {
                case POINT, TILE -> tiles.add(GeoWords.tile(target.x(), target.y()));
                case NAME -> {
                    var place = Target.place(config.viewer().camera().locations(), target.text());
                    if (place.isEmpty()) {
                        throw new ToolProblem("There is no place named \"" + text
                                + "\". Call place_find, or give a point or a tile.");
                    }
                    tiles.add(GeoWords.tile(place.get().x(), place.get().y()));
                }
                default -> throw new ToolProblem(text + " needs an open world. Give a place, a tile or a point.");
            }
        }
        return new ArrayList<>(tiles);
    }

    private static String progress(Progress progress) {
        if (progress.graph()) {
            return "Pathnode graph: " + progress.stage() + " " + Math.round(progress.fraction() * 100) + "%";
        }
        if (progress.tile() == null) {
            return progress.stage().isBlank() ? "Starting" : progress.stage();
        }
        return GeoWords.region(progress.tile())
                + " ("
                + (progress.done() + 1)
                + " of "
                + progress.total()
                + "): "
                + progress.stage()
                + " "
                + Math.round(progress.fraction() * 100)
                + "%";
    }

    private static ToolResult built(
            GeodataBuildJob build, Path folder, GeoFormat format, List<GeoTile> tiles, List<String> skipped)
            throws ToolProblem {
        var end = build.getProgress();
        if (!end.error().isBlank()) {
            throw new ToolProblem("The build failed after " + end.written() + " regions: " + end.error());
        }
        var answer = new JSONObject();
        answer.put("folder", folder.toString());
        answer.put("format", format.name());
        answer.put("written", end.written());
        answer.put("seconds", Math.round((end.regionNanos() - end.startedNanos()) / 1e9));
        if (end.stopped()) {
            answer.put("stopped", "The build was stopped. What it wrote stays, and a new build goes on from there.");
        }
        if (!skipped.isEmpty()) {
            answer.put("skipped", String.join(", ", skipped));
            answer.put("skipped_why", "These regions have a file. Give replace to build them again.");
        }
        var failures = build.getFailures();
        if (!failures.isEmpty()) {
            var failed = new JSONObject();
            for (var entry : failures.entrySet()) {
                failed.put(GeoWords.region(entry.getKey()), entry.getValue());
            }
            answer.put("failed", failed);
        }
        if (end.graphRegions() >= 0) {
            answer.put("pathnode_regions", end.graphRegions());
        }
        var files = new JSONObject();
        for (var tile : tiles.subList(0, Math.min(STEPS_SHOWN, tiles.size()))) {
            var file = folder.resolve(format.getFileName(tile));
            if (Files.isRegularFile(file)) {
                try {
                    files.put(file.getFileName().toString(), Files.size(file));
                } catch (IOException unreadable) {
                    files.put(file.getFileName().toString(), unreadable.getMessage());
                }
            }
        }
        answer.put("files", files);
        return ToolResult.data(answer).withFolder(folder);
    }
}
