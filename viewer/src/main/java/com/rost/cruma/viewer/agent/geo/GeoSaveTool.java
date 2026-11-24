package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.CELL;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.TILE;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterHold;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterWorker;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.around;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.format;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.regionBounds;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.requireServer;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.zones;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoDataWriter;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.geodata.GeodataEditIo;
import com.rost.cruma.viewer.geodata.GeodataEditIo.Operation;
import com.rost.cruma.viewer.render.GeodataState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class GeoSaveTool {
    private static final class Saving implements Step {
        private final GeodataState state;
        private final GeodataEditIo files;

        Saving(GeodataState state, GeodataEditIo files) {
            this.state = state;
            this.files = files;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var result = files.poll(state.editing);
            if (result == null) {
                return null;
            }
            files.close();
            if (result.operation() == null || result.path() == null) {
                throw new ToolProblem(result.message());
            }
            var intoSource = result.operation() == Operation.SAVE_SOURCE
                    || result.operation() == Operation.SAVE_SOURCE_PATH_NODES;
            var session = state.editing;
            if (intoSource && session != null && !session.hasUnsavedChanges()) {
                state.requestReload();
            }
            var answer = new JSONObject();
            answer.put("done", result.message());
            if (result.operation() == Operation.SAVE_PATCH) {
                answer.put("file", result.path().toString());
                answer.put("note", "load opens it later with geo_history, on the same region files.");
                return ToolResult.data(answer).withFolder(result.path().getParent());
            }
            answer.put("folder", result.path().toString());
            return ToolResult.data(answer).withFolder(result.path());
        }

        @Override
        public void cancel() {
            files.cancelExport();
            files.close();
        }
    }

    private final GeoSource source;

    GeoSaveTool(GeoSource source) {
        this.source = source;
    }

    Step save(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var into = args.getRequired("into");
        var pathNodes = args.readFlag("path_nodes", false);
        var table = zones(args);
        var format = format(args);
        if (pathNodes && format == GeoFormat.L2J) {
            throw new ToolProblem(
                    "The pathnode graph belongs to L2OFF servers. Leave out path_nodes, or export L2OFF.");
        }
        if (into.equalsIgnoreCase("source")) {
            if (format != null) {
                throw new ToolProblem(
                        "A save into the source keeps the format of each file. Export into a folder to change it.");
            }
            var session = state.editing;
            if (session == null || !session.isModified()) {
                throw new ToolProblem(
                        "There are no edits to save. Call geo_edit first, or geo_build with folder and path_nodes to"
                                + " rebuild only the pathnode graph.");
            }
            requireServer(pathNodes, table, session.getDocument().getBase().getSourcePath());
            var backup = args.readFlag("backup", true);
            return afterHold(viewer, () -> {
                var files = new GeodataEditIo();
                files.saveToSource(session, pathNodes, backup, table);
                return new Saving(state, files);
            });
        }
        var destination = Path.of(into).toAbsolutePath().normalize();
        var replace = args.readFlag("replace", false);
        if (into.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return patch(viewer, destination, replace);
        }
        var region = args.getText("region");
        if (region != null) {
            return exportRegion(viewer, region, destination, format, replace);
        }
        if (state.editing != null) {
            return exporting(viewer, destination, format, replace, pathNodes, table);
        }
        var folder = GeoSource.folder(null, state, viewer.agent().isOwned());
        var text = args.getText("target", "camera");
        var spot = GeoSpot.of(viewer.locate(text), text);
        return loaded(source.load(GeoSource.wish(folder, around(spot, spot, CELL), state)), files -> {
            GeoSource.holdRegions(state, files);
            return exporting(viewer, destination, format, replace, pathNodes, table);
        });
    }

    private Step exportRegion(
            ViewerContext viewer, String name, Path destination, @Nullable GeoFormat format, boolean replace)
            throws ToolProblem {
        var state = viewer.scene().geodata;
        var word = TILE.matcher(name.strip());
        if (!word.matches()) {
            throw new ToolProblem("\"" + name + "\" is no region. Name one like 22_22.");
        }
        var tile = new GeoTile(Integer.parseInt(word.group(1)), Integer.parseInt(word.group(2)));
        var folder = GeoSource.folder(null, state, viewer.agent().isOwned());
        if (destination.equals(folder)) {
            throw new ToolProblem("That is the source folder. Use into source to save there.");
        }
        return loaded(source.load(GeoSource.wish(folder, regionBounds(tile), state)), files -> {
            var session = state.editing;
            var edited = session != null
                    && Objects.equals(session.getDocument().getBase().getSourcePath(), files.getSourcePath());
            var base = edited ? session.getDocument().getBase() : files;
            var edits = edited ? session.getDocument().getChangedCells() : Map.<CellKey, List<Layer>>of();
            var own = base.getRegion(tile.x(), tile.y()) != null
                    ? base.getRegion(tile.x(), tile.y()).getFormat()
                    : files.getRegion(tile.x(), tile.y()) != null
                            ? files.getRegion(tile.x(), tile.y()).getFormat()
                            : null;
            if (own == null) {
                throw new ToolProblem("The folder has no readable file for region " + GeoWords.region(tile) + ": "
                        + files.getState(tile).detail());
            }
            var target = destination.resolve((format == null ? own : format).getFileName(tile));
            if (Files.exists(target) && !replace) {
                throw new ToolProblem(target + " exists. Give replace to replace it.");
            }
            return afterHold(
                    viewer,
                    () -> afterWorker(
                            () -> {
                                try {
                                    return GeoDataWriter.exportRegion(base, edits, tile, format, destination);
                                } catch (IOException failed) {
                                    throw new ToolProblem("The region was not written: " + failed.getMessage());
                                }
                            },
                            written -> {
                                var answer = new JSONObject();
                                answer.put(
                                        "done",
                                        "Wrote "
                                                + written.getFileName()
                                                + (edits.isEmpty() ? "" : ", with the edits of this region")
                                                + ".");
                                answer.put("file", written.toString());
                                return Step.done(ToolResult.data(answer).withFolder(destination));
                            }));
        });
    }

    private static Step patch(ViewerContext viewer, Path file, boolean replace) throws ToolProblem {
        var state = viewer.scene().geodata;
        var session = state.editing;
        if (session == null || !session.isModified()) {
            throw new ToolProblem("There are no edits for a patch. Call geo_edit first.");
        }
        if (Files.exists(file) && !replace) {
            throw new ToolProblem(file + " exists. Give replace to replace it.");
        }
        return afterHold(viewer, () -> {
            var files = new GeodataEditIo();
            files.submit(Operation.SAVE_PATCH, session, file, replace);
            return new Saving(state, files);
        });
    }

    private static Step exporting(
            ViewerContext viewer,
            Path destination,
            @Nullable GeoFormat format,
            boolean replace,
            boolean pathNodes,
            @Nullable ZoneTable table)
            throws ToolProblem {
        var state = viewer.scene().geodata;
        var session = state.editing;
        var folder = session.getDocument().getBase().getSourcePath();
        if (destination.equals(folder)) {
            throw new ToolProblem("That is the source folder. Use into source to save there.");
        }
        requireServer(pathNodes, table, folder);
        if (!replace) {
            requireNoFiles(session.getDocument().getBase(), destination, format, pathNodes);
        }
        return afterHold(viewer, () -> {
            var files = new GeodataEditIo();
            if (pathNodes) {
                files.exportPathNodes(session, destination, table, replace);
            } else {
                files.export(session, destination, format, replace);
            }
            return new Saving(state, files);
        });
    }

    private static void requireNoFiles(
            GeoDataSnapshot base, Path destination, @Nullable GeoFormat format, boolean pathNodes) throws ToolProblem {
        try {
            var existing =
                    GeoDataWriter.existingFiles(destination, GeoDataWriter.exportFileNames(base, format, pathNodes));
            if (!existing.isEmpty()) {
                throw new ToolProblem("The folder has "
                        + existing.size()
                        + " files of these names, such as "
                        + existing.getFirst()
                        + ". Give replace to replace them.");
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("The folder of the regions cannot be read: " + unreadable.getMessage());
        }
    }
}
