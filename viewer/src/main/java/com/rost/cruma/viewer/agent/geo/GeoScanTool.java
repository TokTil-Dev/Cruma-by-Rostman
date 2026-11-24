package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.BUILD_POLL_MILLIS;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.grown;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.otherFolder;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.scanArea;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataDiff;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataDiff.Limits;
import com.rost.cruma.core.geodata.GeoEditCheck;
import com.rost.cruma.core.spawn.SpawnHealth;
import com.rost.cruma.core.spawn.SpawnHealth.Impact;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.geo.GeoSource.Wish;
import com.rost.cruma.viewer.geodata.GeoRegionCatalog;
import com.rost.cruma.viewer.geodata.GeodataEditReview;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class GeoScanTool {
    static final int LISTED = 30;

    private static final int MOST_LISTED = 200;
    private static final long CATALOG_NANOS = TimeUnit.MINUTES.toNanos(2);

    private final GeoSource source;
    private final GeoSource folderB;

    GeoScanTool(GeoSource source, GeoSource folderB) {
        this.source = source;
        this.folderB = folderB;
    }

    Step scan(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var kind = args.getText("kind", "scan");
        var limit = Math.clamp(args.getInteger("limit", LISTED), 1, MOST_LISTED);
        if (kind.equals("review")) {
            return review(viewer, limit);
        }
        var folder =
                GeoSource.folder(args.getText("folder"), state, viewer.agent().isOwned());
        var from = args.getText("from");
        var other = from == null ? null : otherFolder(from, folder);
        if (kind.equals("regions")) {
            return regions(folder, other, limit);
        }
        var text = args.getText("target", "camera");
        var target = viewer.locate(text);
        var area = scanArea(args, viewer, target);
        GeoScans.requireScanSize(area);
        return switch (kind) {
            case "scan" -> {
                var information = args.readFlag("information", false);
                yield loaded(source.load(GeoSource.wish(folder, grown(area), state)), files -> {
                    GeoSource.requireRegion(files);
                    var data = GeoSource.view(state, files);
                    return Step.onWorker(() -> ToolResult.data(GeoScans.scanned(
                            GeoScans.scan(data, area, GeoSteps::isInterrupted), area, limit, information)));
                });
            }
            case "compare" -> {
                if (other == null) {
                    throw new ToolProblem("compare needs from, folder B: another folder of region files.");
                }
                yield loaded(source.load(GeoSource.wish(folder, area, state)), files -> {
                    GeoSource.requireRegion(files);
                    var data = GeoSource.view(state, files);
                    return loaded(
                            folderB.load(new Wish(other, area, 0, 0)),
                            filesB -> Step.onWorker(() -> {
                                var limits = new Limits(
                                        GeoScans.MOST_CELLS,
                                        GeoScans.COMPARED_REPORTS,
                                        20_000_000,
                                        GeoSteps::isInterrupted);
                                return ToolResult.data(GeoScans.compared(
                                        GeoDataDiff.compare(data, filesB, area, limits),
                                        area,
                                        other.toString(),
                                        limit));
                            }));
                });
            }
            default -> throw new ToolProblem("kind must be scan, compare, review or regions.");
        };
    }

    private static Step review(ViewerContext viewer, int limit) throws ToolProblem {
        var session = viewer.scene().geodata.editing;
        if (session == null || !session.isModified()) {
            throw new ToolProblem("There are no geodata edits to review. Call geo_edit first.");
        }
        var document = session.getDocument();
        var before = document.getBase();
        var after = document.snapshot();
        var changed = document.getChangedCells();
        var spawns = spawnCheck(viewer);
        return Step.onWorker(() -> {
            var check = GeoEditCheck.check(
                    before, after, changed.keySet(), GeoScans.REVIEW_CELLS, GeoScans.DROP, GeoSteps::isInterrupted);
            var impact = spawns == null
                    ? null
                    : spawns.check(before, after, GeodataEditReview.editedArea(List.copyOf(changed.keySet())));
            return ToolResult.data(GeoScans.reviewed(check, impact, before, changed, limit));
        });
    }

    @FunctionalInterface
    private interface SpawnCheck {
        Impact check(GeoDataAccess before, GeoDataAccess after, Bounds area);
    }

    private static @Nullable SpawnCheck spawnCheck(ViewerContext viewer) {
        var spawns = viewer.system().getSpawnAccess().getSource();
        var document = spawns.getDocument();
        if (document == null) {
            return null;
        }
        var held = document.getState();
        var npcs = spawns.getNpcs();
        return (before, after, area) -> SpawnHealth.findIntroduced(
                held,
                SpawnHealth.makersIn(held, area.minX(), area.minY(), area.maxX(), area.maxY()),
                npcs,
                before,
                after,
                GeoSteps::isInterrupted);
    }

    private static Step regions(Path folder, @Nullable Path other, int limit) {
        return Step.onWorker(() -> {
            try (var catalog = new GeoRegionCatalog()) {
                catalog.request(folder, 0, other);
                var deadline = System.nanoTime() + CATALOG_NANOS;
                while (true) {
                    var read = catalog.getCatalog();
                    if (read != null && (other == null || read.compared())) {
                        return ToolResult.data(GeoScans.regions(read, limit));
                    }
                    if (System.nanoTime() > deadline) {
                        if (read == null) {
                            throw new ToolProblem("The folder could not be read in time.");
                        }
                        return ToolResult.data(GeoScans.regions(read, limit));
                    }
                    Thread.sleep(BUILD_POLL_MILLIS / 4);
                }
            }
        });
    }
}
