package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.point;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaChecks.Kind;
import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDefaults;
import com.rost.cruma.core.area.AreaDocument;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaSummary;
import com.rost.cruma.core.area.AreaTiles;
import com.rost.cruma.core.area.ServerArea;
import com.rost.cruma.core.spawn.Polygons;
import com.rost.cruma.core.spawn.SpawnChecks.Severity;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import com.rost.cruma.viewer.spawn.AreaStyle;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class AreaSteps {
    private static final double DEFAULT_RADIUS = 4000;
    private static final double TILE_HALF = 16384;
    private static final int SHOWN_NAMES = 5;
    private static final String NO_FOLDER =
            "No server script folder is set for this client. The person chooses it in Settings, on the page of the"
                    + " client, or in the NPC Spawns panel. In cruma.conf it is script_path in the client's entry of"
                    + " client_profiles.";
    private static final String HELD =
            "The person is dragging in Areas or has its review open. Call again when they are done.";

    private AreaSteps() {}

    record Region(
            Target target,
            double minX,
            double minY,
            double maxX,
            double maxY,
            @Nullable Double radius) {
        boolean reaches(ServerArea area) {
            if (area.points().isEmpty()) {
                return false;
            }
            if (radius == null) {
                return area.maxX() >= minX && area.minX() < maxX && area.maxY() >= minY && area.minY() < maxY;
            }
            return distance(area) <= radius;
        }

        double distance(ServerArea area) {
            return Polygons.distanceXy(area.points(), target.x(), target.y());
        }
    }

    static boolean isAreaDataReady(Desk desk) throws ToolProblem {
        var source = desk.areas().getSource();
        return switch (source.getPhase()) {
            case EMPTY -> {
                if (desk.scriptPath() == null) {
                    throw new ToolProblem(NO_FOLDER);
                }
                source.open(SpawnSource.resolveFolder(desk.scriptPath()));
                yield false;
            }
            case LOADING -> false;
            case MISSING ->
                throw new ToolProblem("The script folder " + source.getFolder() + " has no " + AreaDataFile.FILE_NAME
                        + ". The person can choose another folder in NPC Spawns or in Areas.");
            case FAILED ->
                throw new ToolProblem(AreaDataFile.FILE_NAME + " could not be read: " + source.getMessage()
                        + " The person can choose another folder in Areas.");
            case READY -> source.getDocument() != null;
        };
    }

    static AreaDocument document(Desk desk) throws ToolProblem {
        var document = desk.areas().getSource().getDocument();
        if (document == null) {
            throw new ToolProblem(AreaDataFile.FILE_NAME + " was closed. Call again.");
        }
        return document;
    }

    static @Nullable ToolResult waitForPerson(long deadline) throws ToolProblem {
        if (System.nanoTime() > deadline) {
            throw new ToolProblem(HELD);
        }
        return null;
    }

    static @Nullable Region region(ToolArgs args, Desk desk) throws ToolProblem {
        var text = args.getText("target");
        return text == null ? null : region(args, desk.places().locate(text));
    }

    static Region region(ToolArgs args, Target target) throws ToolProblem {
        if (target.kind() == Target.Kind.TILE && !args.has("radius")) {
            return new Region(
                    target,
                    target.x() - TILE_HALF,
                    target.y() - TILE_HALF,
                    target.x() + TILE_HALF,
                    target.y() + TILE_HALF,
                    null);
        }
        var radius = args.getNumber("radius", DEFAULT_RADIUS);
        if (!(radius > 0)) {
            throw new ToolProblem("radius must be above 0.");
        }
        return new Region(
                target, target.x() - radius, target.y() - radius, target.x() + radius, target.y() + radius, radius);
    }

    static ServerArea findArea(State state, String text) throws ToolProblem {
        var wanted = text.strip();
        if (wanted.startsWith("#")) {
            int index;
            try {
                index = Integer.parseInt(wanted.substring(1).strip());
            } catch (NumberFormatException wrong) {
                throw new ToolProblem(text + " is not a number such as #12.");
            }
            var area = state.area(index);
            if (area == null) {
                throw new ToolProblem("There is no area " + wanted + ". area_find names the areas.");
            }
            return area;
        }
        var found = new ArrayList<ServerArea>();
        for (var area : state.areas()) {
            if (area != null && area.name().equals(wanted)) {
                found.add(area);
            }
        }
        if (found.isEmpty()) {
            throw new ToolProblem("There is no area named " + wanted + ". area_find with query finds names.");
        }
        if (found.size() > 1) {
            var numbers = found.stream()
                    .limit(SHOWN_NAMES)
                    .map(area -> SpawnEditor.number(area.index()))
                    .toList();
            throw new ToolProblem(
                    found.size() + " areas are named " + wanted + ", such as " + numbers + ". Use a number.");
        }
        return found.getFirst();
    }

    static String tileOf(ServerArea area) {
        return AreaTiles.format(area.centerTileX(), area.centerTileY());
    }

    static String middleOf(ServerArea area) {
        return point(area.centerX(), area.centerY(), (area.bottom() + (double) area.top()) / 2);
    }

    static String kindWord(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    static JSONObject describeBriefly(ServerArea area, AreaDefaults defaults, int findings) {
        var item = new JSONObject();
        item.put("area", SpawnEditor.number(area.index()));
        item.put("name", area.name());
        item.put("type", area.typeWord());
        item.put("type_name", AreaStyle.title(area));
        item.put("tile", tileOf(area));
        if (area.line() > 0) {
            item.put("line", area.line());
        } else {
            item.put("new", true);
        }
        item.put("does", AreaSummary.describe(area, defaults));
        item.put("size", (area.maxX() - area.minX()) + " by " + (area.maxY() - area.minY()));
        item.put("heights", area.bottom() + " to " + area.top());
        item.put("middle", middleOf(area));
        if (!area.isOnByDefault()) {
            item.put("off", true);
        }
        var zones = area.get("instantzone_id");
        if (zones != null) {
            item.put("instance_zones", zones);
        }
        item.put("findings", findings);
        return item;
    }

    static JSONArray describeFindings(List<Finding> findings) {
        var sorted = new ArrayList<>(findings);
        sorted.sort((a, b) -> Boolean.compare(!a.isError(), !b.isError()));
        var list = new JSONArray();
        for (var finding : sorted) {
            var item = new JSONObject();
            item.put("severity", finding.severity() == Severity.ERROR ? "error" : "warning");
            item.put("kind", kindWord(finding.kind()));
            item.put("title", finding.title());
            item.put("detail", finding.detail());
            if (finding.hasFix()) {
                item.put("fix", finding.kind().getFixLabel());
            }
            list.add(item);
        }
        return list;
    }
}
