package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaCheckTool.areaCheck;
import static com.rost.cruma.viewer.agent.spawn.AreaEditTool.areaEdit;
import static com.rost.cruma.viewer.agent.spawn.AreaFindTool.areaFind;
import static com.rost.cruma.viewer.agent.spawn.AreaSaveTool.areaSave;

import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Places;
import com.rost.cruma.viewer.spawn.AreaAccess;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

public final class AreaTools implements ToolGroup {
    private static final String SCRIPTS =
            "With no viewer open: the server's script folder. The client's folder from Settings when left out.";
    private static final String AREA = "An area: its name, or its number such as #12, as area_find gives it.";
    private static final String[] LISTS = {"types", "skills"};

    record Desk(AreaAccess areas, @Nullable Path scriptPath, Places places, boolean hidden, Access access) {}

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "area_find",
                                "Find areas",
                                "Server areas near a target, of a type or a search, or those that hold a point by the"
                                        + " server's rules. One area in full with its text, or lists. Unsaved edits"
                                        + " count.")
                        .target("Where to look. The whole file when left out.")
                        .number(
                                "radius",
                                "Units around the target. 4000 when left out, and a tile takes the whole tile.")
                        .flag(
                                "at",
                                "Only the areas that hold the target, a point with a height, as the server decides:"
                                        + " in the outline, from bottom to top, out of the excluded parts, and filed"
                                        + " under the tile of the point.")
                        .text("type", "Filter: a type word, such as peace_zone, also one of the file or your own.")
                        .text("query", "Filter: letters of the name, type, note or skill, as the panel's search.")
                        .flag(
                                "off",
                                "Filter: true for only the areas that are off when the server starts, false for"
                                        + " none of them.")
                        .flag("instance", "Filter: true for only the areas of instance zones, false for none of them.")
                        .text("area", "One area in full, by name or number, with its areadata.txt text.")
                        .choice("list", "A list in place of areas: the types of the file, or the skills.", LISTS)
                        .integer("limit", "How many to list. 30 when left out.")
                        .text("scripts", SCRIPTS),
                (args, viewer) -> areaFind(args, desk(viewer)),
                AreaFiles.alone(AreaFindTool::areaFind));
        tools.viewer(
                ToolSpec.named(
                                "area_check",
                                "Check areas",
                                "The checks of the Areas panel on the whole file, around a target or on one area,"
                                        + " the worst first. Its findings can go straight into findings_show as items.")
                        .target("The middle of the place to check. The whole file when left out.")
                        .number(
                                "radius",
                                "Units around the target. 4000 when left out, and a tile takes the whole tile.")
                        .text("area", AREA)
                        .integer("limit", "How many findings to list. 30 when left out."),
                (args, viewer) -> areaCheck(args, desk(viewer)));
        tools.viewer(
                ToolSpec.named(
                                "area_edit",
                                "Edit areas",
                                "Adds, changes, moves, copies and removes server areas in memory, applies the fix of a"
                                        + " finding, or undoes a step. Each call is one Undo step. area_save writes"
                                        + " them.")
                        .access(Access.EDIT)
                        .choice("action", "What to do.", AreaEditor.OPS)
                        .text("area", AREA)
                        .text(
                                "type",
                                "add_area and type: a type word, such as peace_zone, a word of the file such as"
                                        + " bless, or your own. A new area of a type this tool does not know gets no"
                                        + " settings of a type.")
                        .text("name", "add_area: a name of its own, free names when left out. rename: the new name.")
                        .target("add_area: the middle of a square, on the ground. move: the new middle. add_point: the"
                                + " point. duplicate and paste: the middle of the copy.")
                        .number("radius", "add_area: half the width of the square. 400 when left out, as New area.")
                        .list(
                                "corners",
                                "add_area, set_corners and add_part: corners as x,y. An excluded part takes the"
                                        + " heights of its area.")
                        .integer(
                                "bottom",
                                "add_area, set_corners and set_heights: the lowest height. On the ground when left"
                                        + " out: 200 below it, and 400 below the surface for water.")
                        .integer(
                                "top",
                                "add_area, set_corners and set_heights: the highest height. 400 above the"
                                        + " ground when left out, and the surface itself for water.")
                        .integer("dx", "move: units to move east.")
                        .integer("dy", "move: units to move south.")
                        .integer("dz", "move and duplicate: units to move up.")
                        .text("key", "set and remove_setting: a setting as areadata.txt names it, such as skill_name.")
                        .text("value", "set: the value as the file writes it, such as [s_poison] or {1;2}.")
                        .text("note", "note: the // line above the area. Empty takes it away.")
                        .integer(
                                "number",
                                "remove_part and remove_point: which one, counted from 0, as area_find lists them.")
                        .flag("restart", "add_point and remove_point: a restart point, in place of a teleport point.")
                        .text(
                                "kind",
                                "fix: the kind of finding, such as other_tile, as area_check names it. Only kinds"
                                        + " with a fix.")
                        .flag("all", "fix: every finding of the kind in the file, as Fix all.")
                        .text("text", "paste: areas as areadata.txt text, as area_find with area gives it.")
                        .required("action"),
                (args, viewer) -> areaEdit(args, desk(viewer)));
        tools.viewer(
                ToolSpec.named(
                                "area_save",
                                "Save areas",
                                "Checks the edits and writes areadata.txt into the server folder with a dated backup,"
                                        + " as Save in Areas. Refuses edits that broke something.")
                        .access(Access.WRITE)
                        .destructive()
                        .integer("limit", "How many changes to list. 30 when left out."),
                (args, viewer) -> areaSave(args, desk(viewer)));
    }

    private static Desk desk(ViewerContext viewer) {
        var spawns = viewer.world().spawns();
        return new Desk(
                viewer.system().getAreaAccess(),
                spawns == null ? null : spawns.scriptPath(),
                viewer::locate,
                viewer.agent().isHidden(),
                viewer.agent().getAccess());
    }
}
