package com.rost.cruma.viewer.agent.geo;

import static com.rost.cruma.viewer.agent.geo.GeoReadTool.HOLES_SHOWN;
import static com.rost.cruma.viewer.agent.geo.GeoScanTool.LISTED;

import com.rost.cruma.core.geodata.GeoFindingFixes.Fix;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.geo.GeoEdits.Operation;
import java.util.ArrayList;

public final class GeoTools implements ToolGroup {
    private final GeoSource source = new GeoSource();
    private final GeoSource folderB = new GeoSource();
    private final GeoReadTool readTool = new GeoReadTool(source);
    private final GeoCheckTool checkTool = new GeoCheckTool(source);
    private final GeoScanTool scanTool = new GeoScanTool(source, folderB);
    private final GeoCasesTool casesTool = new GeoCasesTool(source);
    private final GeoEditTool editTool = new GeoEditTool(source, folderB);
    private final GeoHistoryTool historyTool = new GeoHistoryTool(source);
    private final GeoSaveTool saveTool = new GeoSaveTool(source);

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "geo_read",
                                "Read geodata",
                                "The floors of the cell at a target, or a summary of an area: floors, blocked cells"
                                        + " and holes. Give radius, to or a tile for an area.")
                        .target("The cell, or the middle of the area. The camera when left out.")
                        .text("to", "The other corner of a rectangle, as a point.")
                        .integer("radius", "Units around the target, for an area.")
                        .integer("limit", "Most holes to list. " + HOLES_SHOWN + " when left out.")
                        .text("folder", "The geodata folder, when the viewer has none or should use another."),
                readTool::read);
        tools.viewer(
                ToolSpec.named(
                                "geo_check",
                                "Check geodata",
                                "Whether a move, a line of sight or a server path works, both ways or through stops,"
                                        + " and what stops it where. Also Reach, and the stored pathnode graph.")
                        .choice(
                                "kind",
                                "move is a straight walk, sight a line of sight, path the server's path search, reach"
                                    + " where one walks from the target, graph the pathnodes around it, files a check"
                                    + " of pathnode.idx and .bin. move when left out.",
                                "move",
                                "sight",
                                "path",
                                "reach",
                                "graph",
                                "files")
                        .target("Where it starts, or the middle for reach and graph. The camera when left out.")
                        .text("to", "Where move, sight and path end: a place, a point x,y,z or x,y.")
                        .list(
                                "stops",
                                "Points after to, the stops C to H of Route. The route then runs one way, leg by leg.")
                        .integer(
                                "body",
                                "For move and path: the radius of a body that must fit along the way, such as 16.")
                        .integer(
                                "radius",
                                "For reach and graph: units around the target. 1024 for reach, 512 for graph when left"
                                        + " out.")
                        .choice(
                                "connect",
                                "For reach: the floors its isolated area keeps, those walked both ways, from or to the"
                                        + " target, or either. both when left out.",
                                GeoRoutes.getConnectWords().toArray(String[]::new))
                        .integer(
                                "visits",
                                "Pathnode visits of one server call, for path: 400 for movement, 1000 for a chase. 400"
                                        + " when left out.")
                        .text("folder", "The geodata folder, when the viewer has none or should use another."),
                checkTool::check);
        tools.viewer(
                ToolSpec.named(
                                "geo_scan",
                                "Scan geodata",
                                "Finds the problems of an area as Check does, compares it with folder B, reviews what"
                                        + " the edits change, or lists the region files. Findings fit findings_show.")
                        .choice(
                                "kind",
                                "scan finds problems, compare the cells that differ in folder from, review what the"
                                        + " edits change before a save, regions the region files. scan when left out.",
                                "scan",
                                "compare",
                                "review",
                                "regions")
                        .target("The middle of the area. The camera when left out.")
                        .integer(
                                "radius",
                                "Half the side of the square, up to 4096. " + GeoScans.RADIUS + " when left out.")
                        .text("to", "The other corner of a rectangle, in place of radius.")
                        .text("from", "Folder B: for compare, and for regions to mark the files that differ.")
                        .flag(
                                "information",
                                "For scan: also lists the information findings, which the panel hides at first.")
                        .integer("limit", "Most findings, cells or regions to list. " + LISTED + " when left out.")
                        .text("folder", "The geodata folder, when the viewer has none or should use another."),
                scanTool::scan);
        tools.viewer(
                ToolSpec.named(
                                "geo_cases",
                                "Geodata cases",
                                "The saved checks of the Geodata panel and its bookmarks: replays query scenarios,"
                                        + " saves and replays diagnostic cases, lists and sets bookmarks.")
                        .writes()
                        .choice(
                                "action",
                                "run replays scenario files, with the edits. save_case saves the scan of an area,"
                                        + " replay replays a case. bookmark keeps the target's cell, unbookmark removes"
                                        + " one. save_case: Needs Write. bookmark, unbookmark: Needs Edit.",
                                "run",
                                "save_case",
                                "replay",
                                "bookmarks",
                                "bookmark",
                                "unbookmark")
                        .text(
                                "file",
                                "For run a scenario .json or a folder of them, for save_case and replay a case .json.")
                        .target("For save_case the middle of the area, for bookmark the cell. The camera when left"
                                + " out.")
                        .integer(
                                "radius",
                                "For save_case: half the side of the square. " + GeoScans.RADIUS + " when left out.")
                        .text("name", "For bookmark and unbookmark: the bookmark's name.")
                        .text("from", "For replay of a comparison case: folder B.")
                        .flag("replace", "For save_case: replaces a file of that name.")
                        .text("folder", "The geodata folder, when the viewer has none or should use another.")
                        .required("action"),
                casesTool::runCases);
        tools.viewer(
                ToolSpec.named(
                                "geo_edit",
                                "Edit geodata",
                                "Changes cells in memory as one Undo step. block and open change where one can walk,"
                                    + " edge one side of a cell, set and height the floor, floor goes on with a floor"
                                    + " over cells that have none there, rebuild takes the client's floor.")
                        .access(Access.EDIT)
                        .choice(
                                "action",
                                "What to do. floor gives each cell of the area with no floor near the height of the"
                                        + " target's floor a walkable one there, with the sides to walkable neighbors"
                                        + " open both ways. copy keeps cells for paste. revert drops the edits of the"
                                        + " target's region. fix applies the fixes of the scan findings there, as Fix"
                                        + " all. take makes the region as in folder from.",
                                Operation.words().toArray(String[]::new))
                        .target("The cell, with its height as x,y,z to choose a floor. Without a height the top floor"
                                + " is used.")
                        .text("to", "The other corner of a rectangle of cells, or the end of a slope with its height.")
                        .integer(
                                "radius",
                                "A round area of this many units around the target. Without radius and to, the one"
                                        + " cell. 16 units is one cell.")
                        .integer(
                                "tolerance",
                                "How far from the target's floor a floor still counts. 32 when left out, 64 for"
                                        + " rebuild.")
                        .list("directions", "For edge: the sides, such as north, east.")
                        .flag("open", "For edge: true opens the sides, false blocks them. false when left out.")
                        .choice(
                                "sides",
                                "For edge: both sets the neighbor's side too, this only this cell, opposite only the"
                                        + " neighbor. both when left out.",
                                "both",
                                "this",
                                "opposite")
                        .list(
                                "ways",
                                "For set and add_layer: the ways left open, such as north, east, or all or none."
                                        + " add_layer takes all when left out.")
                        .number(
                                "height",
                                "For set, add_layer and rule flat: the height, in steps of 8. For floor: the height"
                                        + " of the new floor, the one of the target's floor when left out.")
                        .choice(
                                "rule",
                                "For height: flat sets height, raise adds by, smooth evens the floor, slope runs from"
                                        + " target to to. flat when left out.",
                                "flat",
                                "raise",
                                "smooth",
                                "slope")
                        .number("by", "For rule raise: units up, or below 0 for down.")
                        .integer("step", "For rule slope: the height of each stair. A smooth slope when left out.")
                        .list(
                                "kinds",
                                "For fix: the kinds of findings, as geo_scan names them. Every problem when left out,"
                                        + " never information.")
                        .choice(
                                "fix",
                                "For fix: the fix to apply where a finding offers it. Its first fix when left out.",
                                fixWords())
                        .text("from", "For take: folder B.")
                        .text("label", "A few words for the Undo list, after \"AI: \".")
                        .text("folder", "The geodata folder, when the viewer has none or should use another.")
                        .required("action"),
                editTool::edit);
        tools.viewer(
                ToolSpec.named(
                                "geo_history",
                                "Geodata history",
                                "Lists the Undo steps of the geodata edits, takes them back and does them again, or"
                                        + " loads a patch.")
                        .access(Access.EDIT)
                        .choice(
                                "action",
                                "list shows the steps, load opens a patch on a session with no edits. list when left"
                                        + " out.",
                                "list",
                                "undo",
                                "redo",
                                "load")
                        .integer("steps", "How many steps to undo or redo. 1 when left out.")
                        .text("patch", "For load: the .json file of geo_save."),
                historyTool::runHistory);
        tools.viewer(
                ToolSpec.named(
                                "geo_save",
                                "Save geodata",
                                "Writes the edits: back into the source folder with a backup, as a folder of L2OFF or"
                                        + " L2J files, as one region, or as a patch.")
                        .access(Access.WRITE)
                        .destructive()
                        .text(
                                "into",
                                "source for the folder the edits came from, a folder to export into, or a .json file"
                                        + " for a patch of the edits.")
                        .text("region", "For an export: only this region, such as 22_22, with its edits.")
                        .choice(
                                "format",
                                "For an export: the format of the files. Each file keeps its own when left out.",
                                "L2OFF",
                                "L2J")
                        .flag("path_nodes", "Also builds the server's pathnode graph of the folder. L2OFF only.")
                        .choice(
                                "zones",
                                "The server's zone table for path_nodes. Follows the folder's pathnode.idx when left"
                                        + " out.",
                                "320",
                                "340")
                        .flag("replace", "For an export: replaces files of the same names there.")
                        .flag("backup", "For source: keeps the old files in a folder beside it. true when left out.")
                        .target("For an export with no edits yet: a region of the folder. The camera when left out.")
                        .required("into"),
                saveTool::save);
        tools.hub(
                ToolSpec.named(
                                "geo_build",
                                "Build geodata",
                                "Builds region files from the client's maps into a folder, as a long job, or with"
                                        + " path_nodes alone only the pathnode graph, with a backup. Works with no"
                                        + " viewer.")
                        .access(Access.WRITE)
                        .destructive()
                        .text(
                                "folder",
                                "The folder to write into. It must be named: a build never picks the client's folder"
                                        + " by itself.")
                        .list("tiles", "The regions, such as 22_22.")
                        .target("A place or a point, for the one region it stands in. In place of tiles.")
                        .flag("all", "Builds every map of the client, in place of tiles.")
                        .text(
                                "client",
                                "A client from cruma_status by name, or a client folder. The first known one when left"
                                        + " out.")
                        .choice(
                                "format",
                                "L2OFF writes X_Y_conv.dat, L2J writes X_Y.l2j. L2OFF when left out.",
                                "L2OFF",
                                "L2J")
                        .flag("replace", "Builds regions that have a file again. They are skipped when left out.")
                        .flag("path_nodes", "Ends with the pathnode graph of all regions in the folder. L2OFF only.")
                        .choice(
                                "zones",
                                "The server's zone table for path_nodes. Follows the folder's pathnode.idx when left"
                                        + " out.",
                                "320",
                                "340")
                        .text(
                                "npcpos",
                                "A server's npcpos.txt, for floors only its NPCs lead to. The one of the client's"
                                        + " script folder when left out, as in the panel.")
                        .required("folder")
                        .build(),
                GeoBuildTool::build);
    }

    private static String[] fixWords() {
        var words = new ArrayList<String>();
        for (var fix : Fix.values()) {
            words.add(GeoScans.fixWord(fix));
        }
        return words.toArray(String[]::new);
    }
}
