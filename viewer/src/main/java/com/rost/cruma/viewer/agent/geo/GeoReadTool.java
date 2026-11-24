package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoSteps.CELL;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.REGION;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.around;
import static com.rost.cruma.viewer.agent.geo.GeoSteps.loaded;

import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import org.jetbrains.annotations.Nullable;

final class GeoReadTool {
    static final int HOLES_SHOWN = 8;

    private final GeoSource source;

    GeoReadTool(GeoSource source) {
        this.source = source;
    }

    Step read(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var state = viewer.scene().geodata;
        var folder =
                GeoSource.folder(args.getText("folder"), state, viewer.agent().isOwned());
        var text = args.getText("target", "camera");
        var target = viewer.locate(text);
        var spot = GeoSpot.of(target, text);
        var area = area(args, viewer, target);
        var limit = Math.clamp(args.getInteger("limit", HOLES_SHOWN), 1, 100);
        var needs = area != null ? area : around(spot, spot, CELL);
        return loaded(source.load(GeoSource.wish(folder, needs, state)), files -> {
            var data = GeoSource.view(state, files);
            if (area == null) {
                return Step.done(ToolResult.data(GeoReads.cell(data, spot)));
            }
            GeoSource.requireRegion(files);
            return Step.onWorker(() -> ToolResult.data(GeoReads.summary(data, area, limit, GeoSteps::isInterrupted)));
        });
    }

    private static @Nullable Bounds area(ToolArgs args, ViewerContext viewer, Target target) throws ToolProblem {
        var radius = args.getInteger("radius", 0);
        var to = args.getText("to");
        if (radius < 0) {
            throw new ToolProblem("radius must not be below 0.");
        }
        Bounds area = null;
        if (to != null) {
            var corner = viewer.locate(to);
            area = GeoEdits.between(target.x(), target.y(), corner.x(), corner.y());
        } else if (radius > 0) {
            area = new Bounds(
                    Math.round(target.x()) - radius,
                    Math.round(target.y()) - radius,
                    Math.round(target.x()) + radius,
                    Math.round(target.y()) + radius);
        } else if (target.kind() == Kind.TILE) {
            var tile = GeoWords.tile(target.x(), target.y());
            area = new Bounds(tile.worldMinX(), tile.worldMinY(), tile.worldMinX() + REGION, tile.worldMinY() + REGION);
        }
        if (area != null
                && (long) (area.maxX() - area.minX()) / CELL * ((area.maxY() - area.minY()) / CELL)
                        > GeoReads.MOST_CELLS) {
            throw new ToolProblem("That area is wider than a region. Read it in parts.");
        }
        return area;
    }
}
