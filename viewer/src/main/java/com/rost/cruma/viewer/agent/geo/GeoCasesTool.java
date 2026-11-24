package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.CELL;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.afterWorker;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.around;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.grown;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.otherFolder;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.scanArea;

import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.geodata.GeoBookmarkStore;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCaseStore;
import com.rost.cruma.viewer.geodata.GeoScenarioSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

final class GeoCasesTool {
    private final GeoSource source;

    GeoCasesTool(GeoSource source) {
        this.source = source;
    }

    Step runCases(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var action = args.getRequired("action");
        var folder =
                GeoSource.folder(args.getText("folder"), state, viewer.agent().isOwned());
        return switch (action) {
            case "bookmarks" -> {
                try {
                    yield Step.done(ToolResult.data(GeoCases.bookmarks(
                            new GeoBookmarkStore(settings(viewer).resolve(GeoCases.BOOKMARKS)).load(folder), folder)));
                } catch (IOException unreadable) {
                    throw new ToolProblem("The bookmarks cannot be read: " + unreadable.getMessage());
                }
            }
            case "bookmark", "unbookmark" -> mark(args, viewer, folder, action.equals("bookmark"));
            case "run" -> {
                var file = existing(args.getText("file"), "run needs file, a scenario .json or a folder of them.");
                var session = state.editing;
                var edited = session != null
                        && session.isModified()
                        && Objects.equals(session.getDocument().getBase().getSourcePath(), folder);
                var document = edited ? session.getDocument() : null;
                var frozen = edited ? document.snapshot() : null;
                yield Step.onWorker(() -> {
                    var source =
                            document == null ? null : GeoScenarioSource.prepare(document.getBase(), frozen, document);
                    return ToolResult.data(GeoCases.run(file, folder, source, GeoSteps::isInterrupted));
                });
            }
            case "save_case" -> {
                requireAccess(viewer, Access.WRITE, "save_case writes a file");
                var file = Path.of(args.getRequired("file")).toAbsolutePath().normalize();
                if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                    throw new ToolProblem("A case is a .json file. Give file a name that ends with .json.");
                }
                var replace = args.readFlag("replace", false);
                var text = args.getText("target", "camera");
                var area = scanArea(args, viewer, viewer.locate(text));
                GeoScans.requireScanSize(area);
                yield loaded(source.load(GeoSource.wish(folder, grown(area), state)), files -> {
                    GeoSource.requireRegion(files);
                    var data = GeoSource.view(state, files);
                    return Step.onWorker(
                            () -> ToolResult.data(GeoCases.saveCase(data, area, file, replace, GeoSteps::isInterrupted))
                                    .withFolder(file.getParent()));
                });
            }
            case "replay" -> {
                var file = existing(args.getText("file"), "replay needs file, a case .json.");
                var from = args.getText("from");
                var other = from == null ? null : otherFolder(from, folder);
                yield afterWorker(
                        () -> {
                            try {
                                return GeoDiagnosticCaseStore.read(file);
                            } catch (IOException | RuntimeException unreadable) {
                                throw new ToolProblem("The case cannot be read: " + unreadable.getMessage());
                            }
                        },
                        saved -> loaded(
                                source.load(GeoSource.wish(
                                        folder, grown(saved.request().bounds()), state)),
                                files -> {
                                    var data = GeoSource.view(state, files);
                                    return Step.onWorker(() -> ToolResult.data(
                                            GeoCases.replay(data, file, other, GeoSteps::isInterrupted)));
                                }));
            }
            default ->
                throw new ToolProblem("action must be run, save_case, replay, bookmarks, bookmark or unbookmark.");
        };
    }

    private Step mark(ToolArgs args, ViewerContext viewer, Path folder, boolean add) throws ToolProblem {
        requireAccess(viewer, Access.EDIT, "A bookmark changes the list of the panel");
        var state = viewer.scene().geodata;
        var config = settings(viewer);
        var name = args.getText("name");
        if (!add) {
            if (name == null) {
                throw new ToolProblem("unbookmark needs name, as action bookmarks lists them.");
            }
            return Step.done(ToolResult.data(GeoCases.mark(state, config, folder, name, null)));
        }
        var text = args.getText("target", "camera");
        var spot = GeoSpot.of(viewer.locate(text), text);
        return loaded(source.load(GeoSource.wish(folder, around(spot, spot, CELL), state)), files -> {
            var data = GeoSource.view(state, files);
            var floor = spot.floor(data);
            if (floor == null) {
                var cell = spot.cell(data);
                throw new ToolProblem(
                        cell.availability() == Availability.AVAILABLE
                                ? "The cell has no floor to keep."
                                : GeoWords.noData(cell));
            }
            var at = new GeoPoint(
                    floor.cell().x() + CELL / 2,
                    floor.cell().y() + CELL / 2,
                    floor.layer().height());
            var title = name != null ? name : GeoWords.point(at).replace(",", ", ");
            return Step.done(ToolResult.data(GeoCases.mark(state, config, folder, title, at)));
        });
    }

    private static Path settings(ViewerContext viewer) throws ToolProblem {
        if (viewer.configDirectory() == null) {
            throw new ToolProblem("This viewer keeps no settings folder, so it has no bookmarks.");
        }
        return viewer.configDirectory();
    }

    private static Path existing(@Nullable String text, String missing) throws ToolProblem {
        if (text == null) {
            throw new ToolProblem(missing);
        }
        var path = Path.of(text).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            throw new ToolProblem("There is no " + path + ".");
        }
        return path;
    }

    private static void requireAccess(ViewerContext viewer, Access level, String what) throws ToolProblem {
        if (!viewer.agent().getAccess().allows(level)) {
            throw new ToolProblem(what
                    + ", so it needs "
                    + level.getLabel()
                    + " access, and this viewer gives "
                    + viewer.agent().getAccess().getLabel()
                    + ". Ask the person to raise it in the AI agent panel of Cruma.");
        }
    }
}
