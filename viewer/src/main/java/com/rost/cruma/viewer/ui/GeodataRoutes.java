package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoEditedGraphValidator;
import com.rost.cruma.core.geodata.GeoEditedGraphValidator.Change;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.SightResult;
import com.rost.cruma.core.geodata.GeoReachability;
import com.rost.cruma.core.geodata.GeoReachability.Access;
import com.rost.cruma.core.geodata.GeoReachability.FrontierReason;
import com.rost.cruma.core.geodata.GeoReachability.Node;
import com.rost.cruma.core.geodata.ServerPathFinder;
import com.rost.cruma.core.geodata.ServerPathFinder.Replay;
import com.rost.cruma.core.geodata.ServerPathFinder.ReplayStatus;
import com.rost.cruma.core.geodata.ServerPathFinder.Status;
import com.rost.cruma.viewer.geodata.GeoGraphView;
import com.rost.cruma.viewer.geodata.GeoInspection;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoLivePathGraph;
import com.rost.cruma.viewer.geodata.GeoLivePathGraph.State;
import com.rost.cruma.viewer.geodata.GeoOverlayLines;
import com.rost.cruma.viewer.geodata.GeoTracePlayback;
import com.rost.cruma.viewer.geodata.GeodataQueryJobs;
import com.rost.cruma.viewer.geodata.GeodataQueryJobs.GraphAudit;
import com.rost.cruma.viewer.geodata.GeodataQueryJobs.Kind;
import com.rost.cruma.viewer.geodata.GeodataQueryJobs.Leg;
import com.rost.cruma.viewer.geodata.GeodataQueryJobs.Legs;
import com.rost.cruma.viewer.render.Camera;
import com.rost.cruma.viewer.render.GeodataState;
import com.rost.cruma.viewer.render.api.ViewerRenderBackend;
import com.rost.cruma.viewer.ui.GeodataSpawnLink.SpawnMark;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import glm_.vec2.Vec2;
import glm_.vec3.Vec3;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class GeodataRoutes implements AutoCloseable {
    static final int MAX_POINTS = 8;

    static final int PICK_ROWS = 10;

    static final int PICK_REACH = 3000;

    static final int REACH_DOTS = 24_000;

    static final float HIDDEN_PIN = .45f;

    static final float PIN_DEPTH_TOLERANCE = 64;

    private static final int STOP_COLOR = ImColor.rgb(180, 140, 255);

    private static final int[] BODY_RADII = {0, 8, 16, 32};

    private static final int[] WALK_SPEEDS = {80, 160, 640};

    private static final float LIFT = 6;

    private static final int ROUTE_LINE = ImColor.rgba(110, 230, 250, 240);
    private static final int ROUTE_RETURN = ImColor.rgba(245, 180, 90, 150);
    private static final int ROUTE_B = ImColor.rgb(245, 180, 90);

    private static final int REACH_MUTUAL = ImColor.rgba(100, 230, 145, 210);
    private static final int REACH_OUT = ImColor.rgba(90, 170, 255, 210);
    private static final int REACH_IN = ImColor.rgba(240, 140, 85, 210);

    private ViewerRenderBackend backend;
    private final boolean[] pinHidden = new boolean[ViewerRenderBackend.MARKERS];
    private final GeodataQueryJobs jobs = new GeodataQueryJobs();
    private final GeoLivePathGraph live = new GeoLivePathGraph();
    private GeodataSpawnLink spawns = GeodataSpawnLink.NONE;
    private final ArrayList<Selection> stops = new ArrayList<>();
    private boolean addingStop;
    private int body;
    private int npcRadius;
    private String npcName = "";
    private String pickProblem = "";
    private String pickRequest = "";
    private List<GeoPoint> walkLine = List.of();
    private double walkAt;
    private boolean walking;
    private boolean walkPaused;
    private int walkSpeed = 1;
    private boolean resultUsesLiveGraph;
    private GeoOverlayLines lines = new GeoOverlayLines();
    private GeoPoint profileHover;
    private final TraceControls forwardTrace = new TraceControls();
    private final TraceControls reverseTrace = new TraceControls();
    private final int[] reachRadius = {1024};
    private final int[] customVisits = {10_000};
    private final int[] reachNodes = {100_000};
    private GeoReachability.Result reachShownFor;
    private List<Entry<Node, Access>> reachShown = List.of();
    private GeoPoint reachStart;
    private boolean automatic;
    private final int[] reachMillis = {5000};
    private final int[] validationRadius = {1024};
    private final int[] validationEdges = {512};
    private final int[] validationMillis = {5000};
    private Mode mode = Mode.PATH;
    private int profile;
    private boolean originalPathPair = true;
    private boolean resultUsesOriginalPair;
    private boolean showFrontier = true;
    private Submitted submitted;

    public enum Mode {
        PATH(
                "Path",
                "The route the server finds from A to B, as it would for a character or monster: walk to the "
                        + "pathnode graph, follow it and leave it near B."),
        STRAIGHT(
                "Straight",
                "A character walking in a straight line from A to B. Shows where it stops, without "
                        + "looking for a detour."),
        SIGHT(
                "Sight",
                "Whether A can see B by geodata, as for casting and arrows. Doors and moving objects are not "
                        + "part of geodata."),
        REACH(
                "Reach",
                "Every cell a character can walk to from A, up to the radius, gets a dot. Green: you can walk there"
                    + " and back. Blue: only away from A, such as jumping down a drop you cannot climb back. Orange:"
                    + " only toward A. A cell without a dot cannot be reached from A on foot, or lies beyond the"
                    + " radius.");

        final String label;
        final String help;

        Mode(String label, String help) {
            this.label = label;
            this.help = help;
        }

        boolean needsB() {
            return this != REACH;
        }
    }

    static @Nullable Selection selectionAt(GeoDataAccess data, GeoPoint at) {
        if (data == null) {
            return null;
        }
        var floor = new GeoQueryEngine(data).findBaseCell(at);
        if (floor == null) {
            return null;
        }
        return new Selection(
                new GeoCellRef(
                        data.getSourceId(),
                        data.revision(),
                        floor.cell().x(),
                        floor.cell().y(),
                        floor.layer().index()),
                floor.layer().height(),
                floor.layer().nswe());
    }

    static String toReadable(String value) {
        var text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String formatCoordinates(GeoPoint point) {
        return String.format(Locale.ROOT, "%.0f, %.0f, %.0f", point.x(), point.y(), point.z());
    }

    public Mode getMode() {
        return mode;
    }

    void setMode(Mode mode) {
        this.mode = mode;
    }

    void setReachRadius(int units) {
        reachRadius[0] = units;
    }

    void setSpawns(GeodataSpawnLink link) {
        spawns = link;
    }

    public void handleClick(GeodataState state, Selection hovered, boolean moveA) {
        handleClick(state, hovered, moveA, false);
    }

    public void handleClick(GeodataState state, Selection hovered, boolean moveA, boolean addStop) {
        var inspection = state.inspection;
        if (hovered == null) {
            return;
        }
        var stopsAllowed = mode == Mode.PATH || mode == Mode.STRAIGHT;
        if ((addStop || addingStop)
                && stopsAllowed
                && inspection.pointA != null
                && inspection.pointB != null
                && !moveA) {
            addingStop = false;
            if (stops.size() + 2 < MAX_POINTS) {
                stops.add(hovered);
            }
            inspection.selected = hovered;
            return;
        }
        if (!moveA && !stops.isEmpty() && stopsAllowed) {
            stops.set(stops.size() - 1, hovered);
            inspection.selected = hovered;
            return;
        }
        if (moveA || inspection.pointA == null || !mode.needsB()) {
            inspection.pointA = hovered;
            if (!mode.needsB()) {
                inspection.pointB = null;
            }
        } else {
            inspection.pointB = hovered;
        }
        inspection.selected = hovered;
    }

    public void setEnds(GeodataState state, Selection a, Selection b) {
        if (!mode.needsB()) {
            mode = Mode.PATH;
            jobs.cancel();
            submitted = null;
        }
        state.inspection.pointA = a;
        state.inspection.pointB = b;
        state.inspection.selected = b;
    }

    public void drawDrag(
            Camera camera,
            ImDrawList draw,
            GeoOverlayLines target,
            Selection from,
            Selection to,
            int width,
            int height) {
        drawPin(camera, draw, from, "A", 0, GeodataWidgets.OPEN, width, height);
        if (to != null && !to.ref().equals(from.ref())) {
            target.addLine(from.center(), to.center(), LIFT, ImColor.rgba(8, 14, 20, 150), 5);
            target.addLine(from.center(), to.center(), LIFT, ROUTE_LINE, 2);
            drawPin(camera, draw, to, "B", 1, ROUTE_B, width, height);
        }
    }

    public boolean hasPins(GeodataState state) {
        return state.inspection.pointA != null || state.inspection.pointB != null;
    }

    public void clear(GeodataState state) {
        state.inspection.pointA = null;
        state.inspection.pointB = null;
        stops.clear();
        addingStop = false;
        stopWalk();
        jobs.cancel();
        submitted = null;
    }

    public void swap(GeodataState state) {
        var inspection = state.inspection;
        var a = inspection.pointA;
        inspection.pointA = inspection.pointB;
        inspection.pointB = a;
    }

    public void tick(GeodataState state) {
        live.update(state.editing);
        var data = state.queryData();
        var a = state.inspection.pointA;
        var b = mode.needsB() ? state.inspection.pointB : null;
        var result = getCurrentResult(state);
        if (data == null || a == null || mode.needsB() && b == null) {
            return;
        }
        var key = new Submitted(
                mode,
                a,
                b,
                data.getSourceId(),
                data.revision(),
                visits(),
                originalPathPair,
                reachRadius[0],
                reachNodes[0],
                reachMillis[0],
                liveGraph(state) == null ? "" : liveGraph(state).toString(),
                List.copyOf(stops),
                getBodyRadius());
        var stale = automatic && !key.equals(submitted);
        if (result != null && !stale || jobs.isBusy() || key.equals(submitted)) {
            return;
        }
        submitted = key;
        automatic = true;
        if (shouldRunLegs()) {
            submitLegs(state);
            return;
        }
        submit(
                state,
                mode == Mode.PATH
                        ? Kind.REPLAY
                        : mode == Mode.STRAIGHT ? Kind.MOVEMENT : mode == Mode.SIGHT ? Kind.SIGHT : Kind.REACHABILITY);
    }

    public void draw(GeodataState state, Camera camera) {
        var inspection = state.inspection;
        var picked = GeodataWidgets.segmented("route-mode", labels(), mode.ordinal());
        if (picked >= 0 && picked != mode.ordinal()) {
            mode = Mode.values()[picked];
            jobs.cancel();
            submitted = null;
            if (!mode.needsB()) {
                inspection.pointB = null;
            }
        }
        GeodataWidgets.tip(mode.help);
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(GeodataWidgets.MUTED, mode.help);
        ImGui.popTextWrapPos();
        ImGui.dummy(0, 2);
        drawEndpoint(
                "A",
                inspection.pointA,
                GeodataWidgets.OPEN,
                "Click a cell in the view",
                () -> {
                    inspection.pointA = null;
                    jobs.cancel();
                },
                () -> pickRequest = "A");
        pickPopup(state, camera, "A", selection -> inspection.pointA = selection);
        if (mode.needsB()) {
            drawEndpoint(
                    "B",
                    inspection.pointB,
                    ROUTE_B,
                    inspection.pointA == null ? "Then click the destination" : "Click the destination",
                    () -> {
                        inspection.pointB = null;
                        jobs.cancel();
                    },
                    () -> pickRequest = "B");
            pickPopup(state, camera, "B", selection -> inspection.pointB = selection);
        }
        for (var index = 0; index < stops.size() && (mode == Mode.PATH || mode == Mode.STRAIGHT); index++) {
            var stop = index;
            var label = String.valueOf((char) ('C' + index));
            drawEndpoint(
                    label,
                    stops.get(index),
                    STOP_COLOR,
                    "",
                    () -> {
                        stops.remove(stop);
                        jobs.cancel();
                    },
                    () -> pickRequest = label);
            pickPopup(state, camera, label, selection -> {
                if (stop < stops.size()) {
                    stops.set(stop, selection);
                }
            });
        }
        if (inspection.pointA != null && inspection.pointB != null && mode.needsB()) {
            if (GeodataWidgets.rowButton("Swap A ↔ B", 0, 2)) {
                swap(state);
            }
            if (GeodataWidgets.rowButton("Clear", 1, 2)) {
                clear(state);
            }
        }
        if ((mode == Mode.PATH || mode == Mode.STRAIGHT)
                && inspection.pointA != null
                && inspection.pointB != null
                && stops.size() + 2 < MAX_POINTS) {
            if (GeodataWidgets.actionButton(
                    "route-add-stop",
                    Glyph.PLUS,
                    addingStop ? "Click the next stop in the view" : "Add stop " + (char) ('C' + stops.size()),
                    -1,
                    addingStop)) {
                addingStop = !addingStop;
            }
            GeodataWidgets.tip(
                    "The route goes on from the last point to the next stop, up to " + MAX_POINTS
                            + " points, and every leg is checked.",
                    "Ctrl+Click");
        }
        if (mode == Mode.PATH || mode == Mode.STRAIGHT) {
            drawBodyChoice();
        }
        if (!pickProblem.isBlank()) {
            ImGui.pushTextWrapPos(0);
            ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.WARNING);
            ImGui.textUnformatted(pickProblem);
            ImGui.popStyleColor();
            ImGui.popTextWrapPos();
        }
        if (mode == Mode.REACH) {
            ImGui.dummy(0, 2);
            GeodataFields.slider(
                    "Radius",
                    reachRadius,
                    16,
                    8192,
                    "How far from A to explore, in world units. One cell is 16 units. This limits the check, not"
                            + " movement.");
        }
        var modified = state.editing != null && state.editing.isModified();
        if (mode == Mode.PATH && modified) {
            drawLiveGraphStatus(state);
        }
        ImGui.dummy(0, 4);
        drawResult(state, camera);
        if (GeodataWidgets.header("Options##route")) {
            ImGui.textColored(GeodataWidgets.MUTED, "Path search budget");
            var chosen = GeodataWidgets.segmented(
                    "route-profile", new String[] {"Move · 400", "Chase · 1000", "Custom"}, profile);
            if (chosen >= 0) {
                profile = chosen;
                jobs.cancel();
                submitted = null;
            }
            GeodataWidgets.tip(
                    "Maximum pathnode visits of one server call: 400 for ordinary movement, 1000 for chasing. Custom"
                            + " goes beyond the server.");
            if (profile == 2) {
                var before = customVisits[0];
                GeodataFields.slider(
                        "Visited nodes",
                        customVisits,
                        1000,
                        100_000,
                        "Pathnode visits per server call. The discovered-node pool grows with it, five nodes per"
                                + " visit.");
                if (customVisits[0] != before) {
                    jobs.cancel();
                    submitted = null;
                }
                ImGui.pushTextWrapPos(0);
                ImGui.textColored(
                        GeodataWidgets.WARNING, "Not server behavior: a route found here can fail on the server.");
                ImGui.popTextWrapPos();
            }
            if (mode == Mode.REACH) {
                GeodataFields.slider(
                        "Cells per direction",
                        reachNodes,
                        1,
                        1_000_000,
                        "Maximum visited layers for each direction. Raise it when the result says the cell limit was"
                                + " reached.");
                GeodataFields.slider(
                        "Time limit (ms)",
                        reachMillis,
                        1,
                        600_000,
                        "Maximum time for the whole check. A partial result means unexplored cells, not blocked"
                                + " terrain.");
                if (ImGui.checkbox("Mark where the search stopped", showFrontier)) {
                    showFrontier = !showFrontier;
                }
            }
            ImGui.beginDisabled(inspection.pointA == null || inspection.pointB == null);
            if (GeodataWidgets.actionButton("route-one-step", null, "One server call (debug)", -1, false)) {
                submitted = new Submitted(
                        mode,
                        inspection.pointA,
                        inspection.pointB,
                        "",
                        0,
                        visits(),
                        originalPathPair,
                        reachRadius[0],
                        reachNodes[0],
                        reachMillis[0],
                        "",
                        List.of(),
                        0);
                automatic = false;
                submit(state, Kind.PATH);
            }
            GeodataWidgets.tip("Runs a single server pathfinding call and shows its next waypoint and search trace.");
            ImGui.endDisabled();
        }
        if (GeodataWidgets.header("Pathnode graph##route")) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    "The server does not search cells for long routes. It searches a graph of points, pathnodes, built"
                            + " in advance by PathMaker and stored in pathnode.idx and pathnode.bin. Each link means a"
                            + " character can walk straight between two points. Path above follows this graph.");
            ImGui.popTextWrapPos();
            var shown = getCurrentResult(state);
            var showingGraph = shown != null && shown.kind() == Kind.GRAPH;
            ImGui.beginDisabled(inspection.pointA == null && !showingGraph);
            if (GeodataWidgets.actionButton(
                    "route-graph",
                    null,
                    showingGraph ? "Hide stored graph" : "Show stored graph around A",
                    -1,
                    showingGraph)) {
                jobs.cancel();
                if (showingGraph) {
                    submitted = null;
                } else {
                    submitted = new Submitted(
                            mode,
                            inspection.pointA,
                            inspection.pointB,
                            "",
                            0,
                            visits(),
                            originalPathPair,
                            reachRadius[0],
                            reachNodes[0],
                            reachMillis[0],
                            "",
                            List.of(),
                            0);
                    automatic = false;
                    submit(state, Kind.GRAPH);
                }
            }
            GeodataWidgets.tip(
                    "Dots are pathnodes around A and lines are their stored links. A route from A first walks to a"
                            + " nearby node, follows links and leaves the graph near B. Missing links mean the server"
                            + " cannot route there.");
            ImGui.endDisabled();
            if (GeodataWidgets.actionButton("route-audit", null, "Check graph file integrity", -1, false)) {
                resultUsesOriginalPair = true;
                resultUsesLiveGraph = false;
                submitted = new Submitted(
                        mode,
                        inspection.pointA,
                        inspection.pointB,
                        "",
                        0,
                        visits(),
                        originalPathPair,
                        reachRadius[0],
                        reachNodes[0],
                        reachMillis[0],
                        "",
                        List.of(),
                        0);
                automatic = false;
                jobs.submit(
                        Kind.GRAPH_AUDIT,
                        state.getSourceSnapshot(),
                        centerOf(inspection.pointA),
                        centerOf(inspection.pointB),
                        state.radius,
                        state.getSourceSnapshot().getSourcePath(),
                        400,
                        false);
            }
            GeodataWidgets.tip(
                    "Reads every node and link of pathnode.idx/bin and hashes the binary. This checks the file"
                            + " structure, not movement.");
            if (modified && GeodataWidgets.header("Advanced##route-graph")) {
                compareLinks(state, inspection, modified);
            }
        }
    }

    int getBodyRadius() {
        return body < BODY_RADII.length ? BODY_RADII[body] : npcRadius;
    }

    public boolean isWalking() {
        return walking;
    }

    public void toggleWalk() {
        if (walking) {
            walkPaused = !walkPaused;
        }
    }

    public void stopWalk() {
        walking = false;
        walkPaused = false;
        walkLine = List.of();
    }

    public GeodataQueryJobs.Result getCurrentResult(GeodataState state) {
        var selection = state.inspection;
        return jobs.poll(
                resultUsesOriginalPair ? state.getSourceSnapshot() : state.queryData(),
                centerOf(selection.pointA),
                centerOf(selection.pointB),
                selection.pointA == null ? null : selection.pointA.ref());
    }

    public void drawOverlay(GeodataState state, Camera camera, ImDrawList draw, int width, int height) {
        lines = state.lines;
        reachStart = state.inspection.pointA == null ? null : state.inspection.pointA.center();
        var result = getCurrentResult(state);
        if (result != null) {
            overlayTrace(result.reverse(), reverseTrace, camera, draw, width, height, ROUTE_RETURN, 1.5f);
            overlayTrace(result.forward(), forwardTrace, camera, draw, width, height, ROUTE_LINE, 3f);
            if (showFrontier && result.forward() instanceof GeoReachability.Result reached) {
                for (var frontier : reached.frontier()
                        .subList(0, Math.min(2048, reached.frontier().size()))) {
                    var color = switch (frontier.reason()) {
                        case RADIUS -> ImColor.rgba(250, 220, 80, 220);
                        case UNAVAILABLE -> ImColor.rgba(220, 110, 255, 220);
                        default -> ImColor.rgba(255, 105, 90, 220);
                    };
                    var target = new GeoPoint(
                            frontier.x() + 8, frontier.y() + 8, frontier.from().height());
                    line(frontier.from().center(), target, color, 1.5f);
                }
            }
        }
        if (profileHover != null) {
            lines.addDot(profileHover, LIFT, ImColor.rgb(255, 255, 255), 12);
        }
        if (result != null && result.forward() instanceof Legs legs) {
            for (var leg : legs.legs()) {
                if (leg.narrow() != null) {
                    drawCross(camera, draw, leg.narrow().at(), GeodataWidgets.WARNING, width, height);
                }
            }
        }
        for (var index = 0; index < stops.size() && (mode == Mode.PATH || mode == Mode.STRAIGHT); index++) {
            drawPin(
                    camera,
                    draw,
                    stops.get(index),
                    String.valueOf((char) ('C' + index)),
                    2 + index,
                    STOP_COLOR,
                    width,
                    height);
        }
        walk(camera);
        drawPin(camera, draw, state.inspection.pointA, "A", 0, GeodataWidgets.OPEN, width, height);
        if (mode.needsB()) {
            drawPin(camera, draw, state.inspection.pointB, "B", 1, ROUTE_B, width, height);
        }
    }

    @Override
    public void close() {
        jobs.close();
        live.close();
    }

    public void setDepthSource(ViewerRenderBackend backend) {
        this.backend = backend;
    }

    void line(GeoPoint a, GeoPoint b, int color, float thickness) {
        if (thickness > 2) {
            lines.addLine(a, b, LIFT, ImColor.rgba(8, 14, 20, 150), thickness + 3);
        }
        lines.addLine(a, b, LIFT, color, thickness);
    }

    void point(GeoPoint point, int color, float radius) {
        lines.addDot(point, LIFT, color, radius * 2);
    }

    private void compareLinks(GeodataState state, GeoInspection inspection, boolean modified) {
        ImGui.beginDisabled(inspection.pointA == null || !modified);
        if (GeodataWidgets.actionButton("route-edited-graph", null, "Compare graph links with edits", -1, false)) {
            resultUsesOriginalPair = false;
            resultUsesLiveGraph = false;
            submitted = new Submitted(
                    mode,
                    inspection.pointA,
                    inspection.pointB,
                    "",
                    0,
                    visits(),
                    originalPathPair,
                    reachRadius[0],
                    reachNodes[0],
                    reachMillis[0],
                    "",
                    List.of(),
                    0);
            automatic = false;
            jobs.submitEditedGraph(
                    state.getSourceSnapshot(),
                    state.queryData(),
                    state.editing.getDocument().getChangedCells().keySet(),
                    inspection.pointA.center(),
                    centerOf(inspection.pointB),
                    state.getSourceSnapshot().getSourcePath(),
                    validationRadius[0],
                    validationEdges[0],
                    validationMillis[0]);
        }
        GeodataWidgets.tip(
                "Experimental. Checks stored links near A against movement in the original and the edited data. It"
                        + " does not rebuild the graph.");
        ImGui.endDisabled();
        if (modified) {
            GeodataFields.slider("Scan radius", validationRadius, 16, 4096, "Graph nodes around A that are compared.");
            GeodataFields.slider("Link limit", validationEdges, 1, 8192, "Maximum compared link pairs.");
            GeodataFields.slider(
                    "Time limit (ms)##graph", validationMillis, 1, 600_000, "Maximum time for the comparison.");
        }
    }

    private void drawLiveGraphStatus(GeodataState state) {
        GeodataWidgets.space(GeodataWidgets.ROW);
        var status = live.getStatus();
        var a = centerOf(state.inspection.pointA);
        var b = centerOf(state.inspection.pointB);
        var usesLive = liveGraph(state) != null;
        if (usesLive) {
            GeodataWidgets.dot(GeodataWidgets.OPEN);
            ImGui.textUnformatted("Paths use the edited graph");
            GeodataWidgets.tip("The pathnode graph of " + status.names()
                    + " was built again from the edited cells, as Save with pathnode would build it, so routes"
                    + " follow your edits.");
            return;
        }
        switch (status.state()) {
            case UPDATING -> {
                GeodataWidgets.dot(GeodataWidgets.ACCENT);
                ImGui.textUnformatted("Paths use the edited graph \u00b7 updating\u2026");
                GeodataWidgets.tip(
                        "The graph of the edited regions is being built. Until it is ready, paths use the stored"
                                + " graph.");
            }
            case FAILED -> {
                ImGui.pushTextWrapPos(0);
                ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.WARNING);
                ImGui.textUnformatted("The edited graph could not be built: " + status.detail());
                ImGui.popStyleColor();
                ImGui.popTextWrapPos();
            }
            default -> {
                if (status.state() == State.READY && !status.covers(a, b)) {
                    hint("The edited graph holds " + status.names() + " only. This route uses the stored graph.");
                }
            }
        }
        if (ImGui.checkbox("Use the original geodata for paths", originalPathPair)) {
            originalPathPair = !originalPathPair;
            jobs.cancel();
            submitted = null;
        }
        GeodataWidgets.tip(
                "The stored pathnode graph was built for the original files. With this off, the edited preview is"
                        + " searched with that stale graph.");
    }

    private @Nullable Path liveGraph(GeodataState state) {
        if (state.editing == null || !state.editing.isModified() || mode != Mode.PATH) {
            return null;
        }
        var folder = live.getGraphDirectory(state.editing.getDocument().revision());
        return folder != null
                        && live.getStatus().covers(centerOf(state.inspection.pointA), centerOf(state.inspection.pointB))
                ? folder
                : null;
    }

    private boolean shouldRunLegs() {
        return (mode == Mode.PATH || mode == Mode.STRAIGHT) && (!stops.isEmpty() || getBodyRadius() > 0);
    }

    private void submitLegs(GeodataState state) {
        var points = new ArrayList<GeoPoint>();
        points.add(centerOf(state.inspection.pointA));
        points.add(centerOf(state.inspection.pointB));
        for (var stop : stops) {
            points.add(stop.center());
        }
        var modified = state.editing != null && state.editing.isModified();
        var graph = mode == Mode.PATH ? liveGraph(state) : null;
        resultUsesLiveGraph = graph != null;
        resultUsesOriginalPair = mode == Mode.PATH && modified && graph == null && originalPathPair;
        GeoDataAccess data = resultUsesOriginalPair ? state.getSourceSnapshot() : state.queryData();
        jobs.submitLegs(
                mode == Mode.PATH ? Kind.REPLAY : Kind.MOVEMENT,
                data,
                points,
                graph != null ? graph : state.getSourceSnapshot().getSourcePath(),
                visits(),
                modified && !resultUsesOriginalPair && graph == null,
                getBodyRadius());
    }

    private void drawBodyChoice() {
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted("Body");
        ImGui.popStyleColor();
        var labels = new String[] {"Point", "8", "16", "32", npcRadius > 0 ? "NPC " + npcRadius : "NPC"};
        ImGui.beginDisabled(false);
        var picked = GeodataWidgets.segmented("route-body", labels, body);
        ImGui.endDisabled();
        if (picked >= 0 && (picked < BODY_RADII.length || npcRadius > 0)) {
            body = picked;
            jobs.cancel();
            submitted = null;
        }
        GeodataWidgets.tip(
                "The server moves a creature as one point; geodata has no body size. With a size, every cell within"
                        + " that radius of the route must be open ground near its height, which approximates a wide"
                        + " creature. NPC takes the collision radius of the NPC spawn an end was set from"
                        + (npcName.isBlank() ? "." : ": " + npcName + ", " + npcRadius + "."));
    }

    private void pickPopup(GeodataState state, Camera camera, String label, Consumer<Selection> set) {
        if (label.equals(pickRequest)) {
            ImGui.openPopup("route-pick-" + label);
            pickRequest = "";
        }
        ImGui.setNextWindowSize(300, 0);
        if (!ImGui.beginPopup("route-pick-" + label)) {
            return;
        }
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted("SET " + label);
        ImGui.popStyleColor();
        GeodataWidgets.space(GeodataWidgets.ROW);
        var data = state.queryData();
        var selected = spawns.getSelectedSpawn();
        if (ImGui.menuItem(
                        "Selected spawn" + (selected == null ? "" : " \u00b7 " + selected.npc()),
                        "",
                        false,
                        selected != null)
                && selected != null) {
            place(state, data, new GeoPoint(selected.x(), selected.y(), selected.z()), set, selected);
        }
        if (selected == null && ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled)) {
            HudWidgets.showTooltip("Select an NPC entry in NPC Spawns first.");
        }
        var eye = camera.getWorldFocusPosition();
        if (ImGui.menuItem("Camera position")) {
            place(state, data, new GeoPoint(eye.getX(), eye.getY(), eye.getZ()), set, null);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
        ImGui.textUnformatted("FIXED NPCS NEARBY");
        ImGui.popStyleColor();
        if (!spawns.areSpawnsReady()) {
            ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.FAINT);
            ImGui.textUnformatted("Open npcpos.txt in NPC Spawns to pick its NPCs.");
            ImGui.popStyleColor();
        } else {
            var nearby = new ArrayList<SpawnMark>();
            for (var mark : spawns.findSpawnMarks(
                    eye.getX() - PICK_REACH,
                    eye.getY() - PICK_REACH,
                    eye.getX() + PICK_REACH,
                    eye.getY() + PICK_REACH,
                    2000)) {
                if (mark.fixed()) {
                    nearby.add(mark);
                }
            }
            nearby.sort(Comparator.comparingDouble(mark -> Math.hypot(mark.x() - eye.getX(), mark.y() - eye.getY())));
            for (var index = 0; index < Math.min(PICK_ROWS, nearby.size()); index++) {
                var mark = nearby.get(index);
                var away = Math.round(Math.hypot(mark.x() - eye.getX(), mark.y() - eye.getY()));
                if (ImGui.menuItem(
                        mark.npc() + " \u00b7 " + mark.maker() + "##pick-" + label + index, away + " away")) {
                    place(state, data, new GeoPoint(mark.x(), mark.y(), mark.z()), set, mark);
                }
            }
            if (nearby.isEmpty()) {
                ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.FAINT);
                ImGui.textUnformatted("No fixed NPC within " + PICK_REACH + " units of the camera.");
                ImGui.popStyleColor();
            }
        }
        ImGui.endPopup();
    }

    private void place(GeodataState state, GeoDataAccess data, GeoPoint at, Consumer<Selection> set, SpawnMark mark) {
        var selection = selectionAt(data, at);
        if (selection == null) {
            pickProblem = "No geodata floor at " + formatCoordinates(at) + ". Fly closer so its region loads.";
            return;
        }
        pickProblem = "";
        set.accept(selection);
        state.inspection.selected = selection;
        if (mark != null && mark.radius() > 0) {
            npcRadius = mark.radius();
            npcName = mark.npc();
        }
        jobs.cancel();
        submitted = null;
    }

    private void drawLegsSummary(Legs legs, Camera camera) {
        var total = 0.0;
        Leg failed = null;
        for (var leg : legs.legs()) {
            total += leg.length();
            if (failed == null && !leg.reaches()) {
                failed = leg;
            }
        }
        var names = legs.legs().size() + 1;
        var last = String.valueOf(names <= 2 ? 'B' : (char) ('A' + names - 1));
        if (failed == null) {
            verdict(
                    true,
                    String.format(
                            Locale.ROOT,
                            "Reaches %s \u00b7 %d %s \u00b7 %,.0f units",
                            last,
                            legs.legs().size(),
                            legs.legs().size() == 1 ? "leg" : "legs",
                            total));
        } else {
            var index = legs.legs().indexOf(failed);
            verdict(
                    false,
                    "Leg "
                            + name(index)
                            + " \u2192 "
                            + name(index + 1)
                            + (failed.narrow() != null && doesLegReach(failed) ? " is too narrow" : " stops"));
        }
        if (legs.radius() > 0) {
            hint("Checked for a body of radius " + legs.radius() + ".");
        }
        for (var index = 0; index < legs.legs().size(); index++) {
            var leg = legs.legs().get(index);
            var text = name(index) + " \u2192 " + name(index + 1) + "  " + legText(leg);
            GeodataWidgets.dot(
                    leg.reaches()
                            ? GeodataWidgets.OPEN
                            : leg.narrow() != null && doesLegReach(leg)
                                    ? GeodataWidgets.WARNING
                                    : GeodataWidgets.BLOCKED);
            ImGui.pushStyleColor(ImGuiCol.Text, GeodataWidgets.MUTED);
            if (ImGui.selectable(text + "##route-leg-" + index)) {
                var at = leg.narrow() != null ? leg.narrow().at() : leg.from();
                GeodataWorkspace.focus(camera, at);
            }
            ImGui.popStyleColor();
            GeodataWidgets.tip(
                    leg.narrow() != null ? "Fly to where the body does not fit" : "Fly to the start of this leg");
        }
    }

    private void walkControls(Object result) {
        var line = walkLine(result);
        if (line.size() < 2) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var width = GeodataWidgets.columnWidth(2);
        if (HudWidgets.button(
                "route-walk",
                walking && !walkPaused ? Glyph.PAUSE : Glyph.PLAY,
                walking ? walkPaused ? "Go on" : "Pause" : "Walk",
                width,
                26,
                !walking,
                true)) {
            if (!walking) {
                walkLine = line;
                walkAt = 0;
                walking = true;
                walkPaused = false;
            } else {
                walkPaused = !walkPaused;
            }
        }
        GeodataWidgets.tip("A marker walks the route and the camera follows it.", "Space pauses");
        ImGui.sameLine();
        var picked =
                GeodataWidgets.segmented("route-walk-speed", new String[] {"Walk", "Run", "Fast"}, walkSpeed, width);
        if (picked >= 0) {
            walkSpeed = picked;
        }
        GeodataWidgets.tip("Walk 80, run 160 or fast 640 units a second.");
    }

    private void walk(Camera camera) {
        if (!walking || walkLine.size() < 2) {
            return;
        }
        if (!walkPaused) {
            walkAt += WALK_SPEEDS[walkSpeed] * ImGui.getIO().getDeltaTime();
        }
        var remaining = walkAt;
        var at = walkLine.getLast();
        for (var index = 1; index < walkLine.size(); index++) {
            var from = walkLine.get(index - 1);
            var to = walkLine.get(index);
            var length = Math.hypot(to.x() - from.x(), to.y() - from.y());
            if (remaining <= length) {
                var t = length == 0 ? 1 : remaining / length;
                at = new GeoPoint(
                        from.x() + (to.x() - from.x()) * t,
                        from.y() + (to.y() - from.y()) * t,
                        from.z() + (to.z() - from.z()) * t);
                break;
            }
            remaining -= length;
        }
        lines.addDot(at, LIFT + 10, ImColor.rgba(8, 14, 20, 220), 20);
        lines.addDot(at, LIFT + 10, ImColor.rgb(255, 255, 255), 14);
        if (!camera.isOrtho()) {
            var direction = camera.getCursorRayDirection(0, 0);
            var distance = 700f;
            camera.setPosition(
                    (float) at.x() - direction.getX() * distance,
                    (float) at.y() - direction.getY() * distance,
                    (float) at.z() - direction.getZ() * distance);
        } else {
            camera.setPosition(
                    (float) at.x(), (float) at.y(), camera.getPosition().getZ());
        }
        if (remaining > 0 && at == walkLine.getLast() && walkAt > 0) {
            walking = false;
        }
    }

    private int visits() {
        return switch (profile) {
            case 0 -> 400;
            case 1 -> 1000;
            default -> customVisits[0];
        };
    }

    private record Submitted(
            Mode mode,
            Selection a,
            Selection b,
            String sourceId,
            long revision,
            int profile,
            boolean original,
            int radius,
            int reachNodes,
            int reachMillis,
            String graph,
            List<Selection> stops,
            int body) {}

    private void submit(GeodataState state, Kind kind) {
        var modified = state.editing != null && state.editing.isModified();
        var graph = kind == Kind.PATH || kind == Kind.REPLAY ? liveGraph(state) : null;
        resultUsesLiveGraph = graph != null;
        resultUsesOriginalPair =
                modified && graph == null && originalPathPair && (kind == Kind.PATH || kind == Kind.REPLAY);
        GeoDataAccess data = resultUsesOriginalPair ? state.getSourceSnapshot() : state.queryData();
        var a = centerOf(state.inspection.pointA);
        var b = centerOf(state.inspection.pointB);
        if (kind == Kind.REACHABILITY) {
            jobs.submitReachability(
                    data, state.inspection.pointA.ref(), a, b, reachRadius[0], reachNodes[0], reachMillis[0]);
            return;
        }
        jobs.submit(
                kind,
                data,
                a,
                b,
                state.radius,
                graph != null ? graph : state.getSourceSnapshot().getSourcePath(),
                visits(),
                modified && !resultUsesOriginalPair && graph == null,
                reachNodes[0],
                reachMillis[0]);
    }

    private void drawResult(GeodataState state, Camera camera) {
        var result = getCurrentResult(state);
        var a = state.inspection.pointA;
        if (jobs.isBusy()) {
            ImGui.textColored(GeodataWidgets.MUTED, "Computing…");
            if (GeodataWidgets.actionButton("route-cancel", null, "Cancel", -1, false)) {
                jobs.cancel();
            }
            return;
        }
        if (result == null) {
            if (a == null) {
                hint("Click A in the view, or drag from A to B to place both. Shift-click moves A later.");
            } else if (mode.needsB() && state.inspection.pointB == null) {
                hint("Now click B. Clicking again moves B, and a drag places both ends.");
            }
            return;
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        ImGui.getWindowDrawList()
                .addLine(x, y + 2, x + ImGui.getContentRegionAvailX(), y + 2, GeodataWidgets.DIVIDER, 1);
        ImGui.dummy(0, 6);
        if (!result.error().isBlank()) {
            verdict(false, "Query failed");
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.MUTED, result.error());
            if (result.kind() == Kind.PATH || result.kind() == Kind.REPLAY) {
                ImGui.textColored(
                        GeodataWidgets.MUTED,
                        "Path search needs pathnode.idx and pathnode.bin from the same generator run next to the"
                                + " geodata files. Straight and Reach work without them.");
            }
            ImGui.popTextWrapPos();
            return;
        }
        if (result.forward() instanceof Legs legs) {
            drawLegsSummary(legs, camera);
        } else {
            drawSummary(result);
        }
        ImGui.textColored(
                GeodataWidgets.FAINT,
                String.format(
                        Locale.ROOT,
                        "%.1f ms%s",
                        result.elapsedNanos() / 1e6,
                        resultUsesLiveGraph
                                ? " · edited graph"
                                : resultUsesOriginalPair && result.kind() != Kind.GRAPH_AUDIT
                                        ? " · original geodata"
                                        : ""));
        drawHeightProfile(result.forward());
        walkControls(result.forward());
        drawTrace("A → B", result.forward(), forwardTrace, camera);
        drawTrace("B → A", result.reverse(), reverseTrace, camera);
        if (GeodataWidgets.header("Details##route-details")) {
            ImGui.pushTextWrapPos(0);
            drawDetails("A → B", result.forward());
            drawDetails("B → A", result.reverse());
            ImGui.popTextWrapPos();
        }
    }

    private void drawHeightProfile(Object result) {
        profileHover = null;
        var points = new ArrayList<GeoPoint>();
        if (result instanceof Legs legs) {
            points.addAll(collectRoutePoints(legs));
        } else if (result instanceof Replay replay) {
            points.addAll(replay.waypoints());
        } else if (result instanceof MoveResult move) {
            move.samples().forEach(sample -> points.add(sample.position()));
        } else if (result instanceof SightResult sight) {
            sight.samples().forEach(sample -> points.add(sample.position()));
        }
        if (points.size() < 2) {
            return;
        }
        var along = new double[points.size()];
        var lowest = points.getFirst().z();
        var highest = lowest;
        var up = 0.0;
        var down = 0.0;
        for (var index = 1; index < points.size(); index++) {
            var previous = points.get(index - 1);
            var point = points.get(index);
            along[index] = along[index - 1] + Math.hypot(point.x() - previous.x(), point.y() - previous.y());
            lowest = Math.min(lowest, point.z());
            highest = Math.max(highest, point.z());
            var change = point.z() - previous.z();
            if (change > 0) {
                up += change;
            } else {
                down -= change;
            }
        }
        var total = Math.max(1, along[along.length - 1]);
        ImGui.dummy(0, 2);
        ImGui.textColored(
                GeodataWidgets.MUTED,
                String.format(
                        Locale.ROOT,
                        "Height %.0f to %.0f · up %.0f · down %.0f · %.0f units",
                        lowest,
                        highest,
                        up,
                        down,
                        total));
        var draw = ImGui.getWindowDrawList();
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 64f;
        var padding = 6f;
        draw.addRectFilled(left, top, left + width, top + height, GeodataWidgets.CARD, 6f);
        var range = Math.max(64, highest - lowest);
        var middle = (highest + lowest) * .5;
        var xs = new float[points.size()];
        var ys = new float[points.size()];
        for (var index = 0; index < points.size(); index++) {
            xs[index] = (float) (left + padding + (width - 2 * padding) * along[index] / total);
            ys[index] = (float) (top + height * .5 - (points.get(index).z() - middle) / range * (height - 2 * padding));
        }
        for (var index = 1; index < points.size(); index++) {
            draw.addLine(xs[index - 1], ys[index - 1], xs[index], ys[index], ROUTE_LINE, 2);
        }
        if (result instanceof Replay || result instanceof Legs) {
            for (var index = 0; index < points.size(); index++) {
                draw.addCircleFilled(xs[index], ys[index], 2.5f, ROUTE_LINE, 8);
            }
        }
        ImGui.invisibleButton("##route-profile", width, height);
        if (ImGui.isItemHovered()) {
            var share = Math.clamp((ImGui.getMousePosX() - left - padding) / (width - 2 * padding), 0, 1) * total;
            var segment = 1;
            while (segment < along.length - 1 && along[segment] < share) {
                segment++;
            }
            var from = points.get(segment - 1);
            var to = points.get(segment);
            var span = along[segment] - along[segment - 1];
            var t = span <= 0 ? 0 : (share - along[segment - 1]) / span;
            profileHover = new GeoPoint(
                    from.x() + (to.x() - from.x()) * t,
                    from.y() + (to.y() - from.y()) * t,
                    from.z() + (to.z() - from.z()) * t);
            var x = (float) (left + padding + (width - 2 * padding) * share / total);
            var y = (float) (top + height * .5 - (profileHover.z() - middle) / range * (height - 2 * padding));
            draw.addLine(x, top + 4, x, top + height - 4, ImColor.rgba(255, 255, 255, 70), 1);
            draw.addCircleFilled(x, y, 4, ImColor.rgb(255, 255, 255), 12);
            HudWidgets.showTooltip(String.format(Locale.ROOT, "Z %.0f · %.0f units from A", profileHover.z(), share));
        }
    }

    private void overlayTrace(
            Object result,
            TraceControls controls,
            Camera camera,
            ImDrawList draw,
            int width,
            int height,
            int color,
            float thickness) {
        controls.bind(result);
        var cursor = controls.cursor;
        if (controls.enabled && cursor.getCount() != 0) {
            for (var at = Math.max(0, cursor.getIndex() - 2047); at <= cursor.getIndex(); ++at) {
                var step = cursor.step(at);
                if (step.from() != null && step.to() != null) {
                    line(step.from(), step.to(), color, thickness);
                }
            }
            var step = cursor.getSelected();
            if (step.to() != null) {
                point(step.to(), ImColor.rgb(255, 255, 255), 4);
            }
        } else {
            overlay(result, camera, draw, width, height, color, thickness);
        }
    }

    private void overlay(
            Object result, Camera camera, ImDrawList draw, int width, int height, int color, float thickness) {
        if (result instanceof MoveResult movement) {
            drawPolyline(
                    movement.samples().size(),
                    index -> movement.samples().get(index).position(),
                    color,
                    thickness);
            if (!movement.reachedDestination()) {
                drawCross(camera, draw, movement.finalPosition(), GeodataWidgets.BLOCKED, width, height);
            }
        } else if (result instanceof SightResult sight) {
            drawPolyline(
                    sight.samples().size(), index -> sight.samples().get(index).position(), color, thickness);
        } else if (result instanceof ServerPathFinder.Result path) {
            for (var index = 0; index < Math.min(2048, path.trace().size()); index++) {
                var edge = path.trace().get(index);
                if (edge.from() != null && edge.to() != null) {
                    line(edge.from(), edge.to(), color, 1);
                }
            }
            if (path.nextWaypoint() != null) {
                point(path.nextWaypoint(), color, 5);
            }
        } else if (result instanceof Replay replay) {
            drawPolyline(replay.waypoints().size(), replay.waypoints()::get, color, thickness);
            for (var waypoint : replay.waypoints()) {
                point(waypoint, color, thickness + 1.5f);
            }
        } else if (result instanceof Legs legs) {
            for (var leg : legs.legs()) {
                overlay(
                        leg.result(),
                        camera,
                        draw,
                        width,
                        height,
                        leg.reaches() ? color : GeodataWidgets.WARNING,
                        thickness);
            }
        } else if (result instanceof GeoEditedGraphValidator.Result validation) {
            for (var edge : validation
                    .edges()
                    .subList(0, Math.min(2048, validation.edges().size()))) {
                line(edge.from(), edge.to(), edgeColor(edge.forward().change()), 1.5f);
            }
        } else if (result instanceof GeoGraphView graph) {
            for (var edge : graph.edges()) {
                line(edge.from(), edge.to(), color, 1);
            }
            for (var node : graph.nodes()) {
                point(node.position(), color, 2.5f);
            }
        } else if (result instanceof GeoReachability.Result reachability) {
            for (var entry : nearestReached(reachability)) {
                var tint = switch (entry.getValue()) {
                    case MUTUAL -> REACH_MUTUAL;
                    case OUTBOUND_ONLY -> REACH_OUT;
                    case INBOUND_ONLY -> REACH_IN;
                };
                point(entry.getKey().center(), tint, 2.5f);
            }
        }
    }

    private List<Entry<Node, Access>> nearestReached(GeoReachability.Result reachability) {
        if (reachability != reachShownFor) {
            var entries = new ArrayList<>(reachability.nodes().entrySet());
            var start = reachStart;
            if (start != null) {
                entries.sort(Comparator.comparingDouble(
                        entry -> entry.getKey().center().distanceSquared(start)));
            }
            reachShown = List.copyOf(entries.subList(0, Math.min(REACH_DOTS, entries.size())));
            reachShownFor = reachability;
        }
        return reachShown;
    }

    private interface PointAt {
        GeoPoint at(int index);
    }

    private void drawPolyline(int count, PointAt points, int color, float thickness) {
        var limit = Math.min(4096, count);
        for (var index = 1; index < limit; index++) {
            line(points.at(index - 1), points.at(index), color, thickness);
        }
    }

    private void drawPin(
            Camera camera,
            ImDrawList draw,
            Selection selection,
            String label,
            int marker,
            int color,
            int width,
            int height) {
        if (selection == null) {
            return;
        }
        var base = project(camera, selection.center(), 2, width, height);
        var top = project(camera, selection.center(), 40, width, height);
        if (base == null || top == null) {
            return;
        }
        var hidden = isPinHidden(camera, selection, marker, base, width, height);
        var dx = base.getX() - top.getX();
        var dy = base.getY() - top.getY();
        var length = (float) Math.sqrt(dx * dx + dy * dy);
        var stemX = length > 10 ? top.getX() + dx / length * 10 : base.getX();
        var stemY = length > 10 ? top.getY() + dy / length * 10 : base.getY();
        if (!hidden) {
            draw.addLine(base.getX(), base.getY(), stemX, stemY, color, 2);
            draw.addCircleFilled(base.getX(), base.getY(), 3.5f, color, 12);
            draw.addCircleFilled(top.getX(), top.getY(), 10, color, 20);
            draw.addCircle(top.getX(), top.getY(), 10, ImColor.rgba(10, 16, 22, 220), 20, 1.5f);
            draw.addText(
                    top.getX() - ImGui.calcTextSizeX(label) * .5f,
                    top.getY() - ImGui.getFontSize() * .5f,
                    ImColor.rgb(12, 18, 24),
                    label);
            return;
        }
        var faint = faded(color, HIDDEN_PIN);
        draw.addLine(base.getX(), base.getY(), stemX, stemY, faint, 1.5f);
        draw.addCircleFilled(base.getX(), base.getY(), 3, faint, 12);
        draw.addCircleFilled(top.getX(), top.getY(), 10, ImColor.rgba(10, 16, 22, 170), 20);
        draw.addCircle(top.getX(), top.getY(), 10, faded(color, .6f), 20, 1.5f);
        draw.addText(
                top.getX() - ImGui.calcTextSizeX(label) * .5f,
                top.getY() - ImGui.getFontSize() * .5f,
                faded(color, .75f),
                label);
    }

    private boolean isPinHidden(Camera camera, Selection selection, int marker, Vec2 base, int width, int height) {
        if (backend == null || marker >= pinHidden.length) {
            return false;
        }
        var ndcX = base.getX() / width * 2f - 1f;
        var ndcY = 1f - base.getY() / height * 2f;
        var scene = backend.getMarkerRayDistance(marker, ndcX, ndcY, 300_000);
        if (!Float.isNaN(scene)) {
            var origin = camera.getCursorRayOrigin(ndcX, ndcY);
            var foot = selection.center();
            var dx = foot.x() - origin.getX();
            var dy = foot.y() - origin.getY();
            var dz = foot.z() + 2 - origin.getZ();
            pinHidden[marker] = Math.sqrt(dx * dx + dy * dy + dz * dz) > scene + PIN_DEPTH_TOLERANCE;
        }
        return pinHidden[marker];
    }

    private static String name(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static boolean doesLegReach(Leg leg) {
        return leg.result() instanceof MoveResult move
                ? move.reachedDestination()
                : leg.result() instanceof Replay replay && replay.status() == ReplayStatus.REACHED_DESTINATION;
    }

    private static String legText(Leg leg) {
        if (leg.narrow() != null && doesLegReach(leg)) {
            return String.format(
                    Locale.ROOT,
                    "too narrow at %.0f, %.0f",
                    leg.narrow().at().x(),
                    leg.narrow().at().y());
        }
        if (leg.result() instanceof MoveResult move) {
            return move.reachedDestination()
                    ? String.format(Locale.ROOT, "walks straight \u00b7 %,.0f units", leg.length())
                    : "stops: "
                            + toReadable(move.failure().name())
                            + " after "
                            + Math.round(move.traveledDistance())
                            + " units";
        }
        if (leg.result() instanceof Replay replay) {
            return replay.status() == ReplayStatus.REACHED_DESTINATION
                    ? String.format(
                            Locale.ROOT,
                            "%d waypoints \u00b7 %,.0f units",
                            replay.waypoints().size(),
                            leg.length())
                    : toReadable(replay.status().name()).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static List<GeoPoint> collectRoutePoints(Legs legs) {
        var points = new ArrayList<GeoPoint>();
        for (var leg : legs.legs()) {
            for (var point : leg.line()) {
                if (points.isEmpty() || !points.getLast().equals(point)) {
                    points.add(point);
                }
            }
        }
        return points;
    }

    private static List<GeoPoint> walkLine(Object result) {
        if (result instanceof Legs legs) {
            return collectRoutePoints(legs);
        }
        if (result instanceof Replay replay) {
            return replay.waypoints();
        }
        if (result instanceof MoveResult move) {
            var points = new ArrayList<GeoPoint>();
            move.samples().forEach(sample -> points.add(sample.position()));
            return points;
        }
        return List.of();
    }

    private static String[] labels() {
        var values = Mode.values();
        var labels = new String[values.length];
        for (var index = 0; index < values.length; index++) {
            labels[index] = values[index].label;
        }
        return labels;
    }

    private static GeoPoint centerOf(Selection selection) {
        return selection == null ? null : selection.center();
    }

    private static void drawEndpoint(
            String label, Selection point, int color, String empty, Runnable clear, Runnable pick) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = 30f;
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 6f);
        draw.addCircleFilled(x + 15, y + height * .5f, 9, color, 16);
        draw.addText(
                x + 15 - ImGui.calcTextSizeX(label) * .5f,
                y + (height - ImGui.getFontSize()) * .5f - 1,
                ImColor.rgb(12, 18, 24),
                label);
        var text = point == null ? empty : formatCoordinates(point.center());
        draw.addText(
                x + 32,
                y + (height - ImGui.getFontSize()) * .5f - 1,
                point == null ? GeodataWidgets.MUTED : GeodataWidgets.TEXT,
                text);
        ImGui.dummy(width, height);
        var after = ImGui.getCursorScreenPos();
        ImGui.setCursorScreenPos(x + width - (point != null ? 56 : 28), y + 2);
        if (HudWidgets.button("pick-" + label, Glyph.NPC, "", 26, 26, false, false)) {
            pick.run();
        }
        GeodataWidgets.tip("Set " + label + " at an NPC spawn or at the camera");
        if (point != null) {
            ImGui.sameLine(0, 2);
            if (HudWidgets.button("clear-" + label, Glyph.CLOSE, "", 26, 26, false, false)) {
                clear.run();
            }
            GeodataWidgets.tip("Remove " + label);
        }
        ImGui.setCursorScreenPos(after.x, after.y);
        ImGui.dummy(0, 0);
    }

    private static void hint(String text) {
        ImGui.pushTextWrapPos(0);
        ImGui.textColored(GeodataWidgets.MUTED, text);
        ImGui.popTextWrapPos();
    }

    private static void verdict(boolean good, String text) {
        GeodataWidgets.dot(good ? GeodataWidgets.OPEN : GeodataWidgets.BLOCKED);
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted(text);
        ImGui.popTextWrapPos();
    }

    private static void drawSummary(GeodataQueryJobs.Result result) {
        var forward = result.forward();
        var reverse = result.reverse();
        if (forward instanceof Replay replay) {
            verdict(
                    replay.status() == ReplayStatus.REACHED_DESTINATION,
                    switch (replay.status()) {
                        case REACHED_DESTINATION ->
                            "Reaches B · " + replay.waypoints().size() + " waypoints";
                        case STOPPED -> isBudgetReached(replay) ? "Search budget reached before B" : "Stops before B";
                        case WAYPOINT_LIMIT -> "Waypoint limit reached";
                        case CYCLE_DETECTED -> "Route repeats a waypoint";
                    });
            if (isBudgetReached(replay)) {
                ImGui.textColored(GeodataWidgets.WARNING, "Raise the budget under Options, Custom.");
            }
            if (reverse instanceof Replay back) {
                ImGui.textColored(
                        GeodataWidgets.MUTED,
                        "Back to A: "
                                + (back.status() == ReplayStatus.REACHED_DESTINATION
                                        ? "reaches A · " + back.waypoints().size() + " waypoints"
                                        : back.status()
                                                .name()
                                                .toLowerCase(Locale.ROOT)
                                                .replace('_', ' ')));
            }
        } else if (forward instanceof MoveResult move) {
            verdict(
                    move.reachedDestination(),
                    move.reachedDestination()
                            ? "Walks straight to B"
                            : "Stops: "
                                    + toReadable(move.failure().name())
                                    + " after "
                                    + Math.round(move.traveledDistance())
                                    + " units");
            if (reverse instanceof MoveResult back) {
                ImGui.textColored(
                        GeodataWidgets.MUTED,
                        "Back to A: "
                                + (back.reachedDestination()
                                        ? "walks straight"
                                        : "stops, " + toReadable(back.failure().name())));
            }
        } else if (forward instanceof SightResult sight) {
            verdict(
                    sight.visible(),
                    sight.visible()
                            ? "B is visible from A"
                            : "Sight blocked: " + toReadable(sight.failure().name()));
            if (reverse instanceof SightResult back) {
                ImGui.textColored(GeodataWidgets.MUTED, "From B: " + (back.visible() ? "A is visible" : "blocked"));
            }
        } else if (forward instanceof GeoReachability.Result reach) {
            var counts = new EnumMap<Access, Integer>(Access.class);
            for (var value : reach.nodes().values()) {
                counts.merge(value, 1, Integer::sum);
            }
            verdict(
                    reach.completeness() == Completeness.COMPLETE,
                    String.format(
                            Locale.ROOT,
                            "%,d reachable layers · %s",
                            reach.nodes().size(),
                            GeodataWidgets.completenessLabel(reach.completeness())));
            drawLegendEntry(REACH_MUTUAL, "Walk there and back", counts.getOrDefault(Access.MUTUAL, 0));
            drawLegendEntry(REACH_OUT, "Only from A (drops)", counts.getOrDefault(Access.OUTBOUND_ONLY, 0));
            drawLegendEntry(REACH_IN, "Only back to A", counts.getOrDefault(Access.INBOUND_ONLY, 0));
            if (reach.nodes().size() > REACH_DOTS) {
                hint(String.format(Locale.ROOT, "The view marks the %,d cells nearest to A.", REACH_DOTS));
            }
        } else if (forward instanceof ServerPathFinder.Result path) {
            verdict(
                    path.nextWaypoint() != null,
                    toReadable(path.status().name())
                            + (path.nextWaypoint() == null ? "" : " · next " + formatCoordinates(path.nextWaypoint())));
            ImGui.textColored(
                    GeodataWidgets.MUTED, "Visited " + path.visitedNodes() + " · discovered " + path.discoveredNodes());
        } else if (forward instanceof GraphAudit audit) {
            verdict(
                    audit.audit().isValid(),
                    audit.audit().isValid()
                            ? String.format(
                                    Locale.ROOT,
                                    "Graph files valid · %,d nodes",
                                    audit.audit().nodesChecked())
                            : audit.audit().cancelled()
                                    ? "Graph check cancelled"
                                    : audit.audit().issueCount() + " graph issues");
        } else if (forward instanceof GeoEditedGraphValidator.Result validation) {
            var changes = new EnumMap<Change, Integer>(Change.class);
            for (var edge : validation.edges()) {
                changes.merge(edge.forward().change(), 1, Integer::sum);
                changes.merge(edge.reverse().change(), 1, Integer::sum);
            }
            verdict(
                    changes.getOrDefault(Change.LOST, 0) == 0,
                    validation.edges().size() + " link pairs compared");
            changes.forEach((change, count) -> drawLegendEntry(edgeColor(change), toReadable(change.name()), count));
        } else if (forward instanceof GeoGraphView graph) {
            verdict(
                    true,
                    graph.nodes().size()
                            + " nodes · "
                            + graph.edges().size()
                            + " links"
                            + (graph.truncated() ? " (limited)" : ""));
        }
    }

    private static boolean isBudgetReached(Replay replay) {
        if (replay.calls().isEmpty()) {
            return false;
        }
        var status = replay.calls().getLast().status();
        return status == Status.VISIT_LIMIT || status == Status.VISIT_POOL_LIMIT;
    }

    private static void drawLegendEntry(int color, String label, int count) {
        GeodataWidgets.dot(color);
        ImGui.textColored(GeodataWidgets.MUTED, String.format(Locale.ROOT, "%s · %,d", label, count));
    }

    private static void drawDetails(String label, Object result) {
        if (result instanceof MoveResult movement) {
            ImGui.textColored(GeodataWidgets.MUTED, label);
            ImGui.textUnformatted(movement.failure() + " · " + movement.completeness());
            ImGui.textUnformatted("Final " + formatCoordinates(movement.finalPosition()) + " · traveled "
                    + Math.round(movement.traveledDistance()));
        } else if (result instanceof SightResult sight) {
            ImGui.textColored(GeodataWidgets.MUTED, label);
            ImGui.textUnformatted(sight.visible() + " · " + sight.failure() + " · " + sight.completeness());
        } else if (result instanceof Replay replay) {
            ImGui.textColored(GeodataWidgets.MUTED, label);
            ImGui.textUnformatted(replay.detail());
            ImGui.textUnformatted("Waypoints " + replay.waypoints().size() + " · server calls "
                    + replay.calls().size());
        } else if (result instanceof ServerPathFinder.Result path) {
            ImGui.textColored(GeodataWidgets.MUTED, label);
            ImGui.textUnformatted(path.detail());
            ImGui.textUnformatted("Start anchors: " + path.startAnchors());
            ImGui.textUnformatted("Destination anchors: " + path.destinationAnchors());
        } else if (result instanceof GraphAudit graphAudit) {
            var audit = graphAudit.audit();
            ImGui.textUnformatted("Checked nodes " + audit.nodesChecked() + " · links " + audit.edgesChecked());
            ImGui.textUnformatted("Binary bytes " + graphAudit.metadata().binaryBytes());
            if (!audit.binarySha256().isBlank()) {
                ImGui.textUnformatted("SHA-256 " + audit.binarySha256());
            }
            for (var issue : audit.issues()) {
                ImGui.textUnformatted(issue);
            }
        } else if (result instanceof GeoEditedGraphValidator.Result validation) {
            ImGui.textUnformatted(
                    "Scanned nodes " + validation.scannedNodes() + " · candidates " + validation.candidateEdges());
            ImGui.textColored(GeodataWidgets.MUTED, "A local pass is not a whole-graph validation.");
        } else if (result instanceof GeoReachability.Result reachability) {
            var reasons = new EnumMap<FrontierReason, Integer>(FrontierReason.class);
            for (var frontier : reachability.frontier()) {
                reasons.merge(frontier.reason(), 1, Integer::sum);
            }
            ImGui.textUnformatted("Unavailable cells: " + reachability.unavailableCells());
            reasons.forEach((reason, count) -> ImGui.textUnformatted(toReadable(reason.name()) + " marks: " + count));
            ImGui.textColored(GeodataWidgets.MUTED, "Marks show where the search stopped. They are not walls.");
        }
    }

    private static final class TraceControls {
        final GeoTracePlayback cursor = new GeoTracePlayback();
        boolean enabled;
        boolean playing;
        long lastTick;
        Object owner;

        void bind(Object result) {
            if (owner != result) {
                owner = result;
                playing = false;
                enabled = false;
            }
            cursor.bind(result);
        }
    }

    private static void drawTrace(String label, Object result, TraceControls controls, Camera camera) {
        controls.bind(result);
        var cursor = controls.cursor;
        if (cursor.getCount() == 0 || !GeodataWidgets.header("Step through " + label)) {
            return;
        }
        ImGui.pushID(label);
        if (ImGui.checkbox("Show steps in the view", controls.enabled)) {
            controls.enabled = !controls.enabled;
        }
        if (GeodataWidgets.rowButton("◀", 0, 3)) {
            cursor.seek(cursor.getIndex() - 1);
            controls.playing = false;
        }
        if (GeodataWidgets.rowButton(controls.playing ? "Pause" : "Play", 1, 3)) {
            controls.playing = !controls.playing;
            controls.enabled = true;
            controls.lastTick = System.nanoTime();
            if (cursor.getIndex() == cursor.getCount() - 1) {
                cursor.seek(0);
            }
        }
        if (GeodataWidgets.rowButton("▶", 2, 3)) {
            cursor.advance();
            controls.playing = false;
        }
        if (controls.playing && System.nanoTime() - controls.lastTick >= 100_000_000L) {
            cursor.advance();
            controls.lastTick = System.nanoTime();
            if (cursor.getIndex() == cursor.getCount() - 1) {
                controls.playing = false;
            }
        }
        var step = new int[] {cursor.getIndex() + 1};
        ImGui.setNextItemWidth(-1);
        if (ImGui.sliderInt("##step", step, 1, cursor.getCount(), "Step %d of " + cursor.getCount())) {
            cursor.seek(step[0] - 1);
            controls.playing = false;
        }
        var selected = cursor.getSelected();
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted(selected.description());
        ImGui.popTextWrapPos();
        if (selected.to() != null
                && GeodataWidgets.actionButton("trace-focus", Glyph.FOCUS, "Focus this step", -1, false)) {
            GeodataWorkspace.focus(camera, selected.to());
        }
        if (cursor.isTruncated()) {
            ImGui.textColored(GeodataWidgets.MUTED, "The recorded trace reached its budget.");
        }
        ImGui.popID();
    }

    private static int faded(int color, float alpha) {
        return (color & 0x00ffffff) | Math.round((color >>> 24) * alpha) << 24;
    }

    private static Vec3 toVector(GeoPoint point, float lift) {
        return new Vec3((float) point.x(), (float) point.y(), (float) point.z() + lift);
    }

    private static Vec2 project(Camera camera, GeoPoint point, float lift, int width, int height) {
        return camera.projectToScreenRaw(toVector(point, lift), width, height);
    }

    private static void drawCross(Camera camera, ImDrawList draw, GeoPoint point, int color, int width, int height) {
        var screen = project(camera, point, 6, width, height);
        if (screen != null) {
            var x = screen.getX();
            var y = screen.getY();
            draw.addCircleFilled(x, y, 9, ImColor.rgba(8, 14, 20, 200), 16);
            draw.addLine(x - 5, y - 5, x + 5, y + 5, color, 2.5f);
            draw.addLine(x + 5, y - 5, x - 5, y + 5, color, 2.5f);
        }
    }

    private static int edgeColor(Change change) {
        return switch (change) {
            case LOST -> ImColor.rgba(250, 90, 80, 230);
            case GAINED -> ImColor.rgba(90, 250, 130, 230);
            case CHANGED -> ImColor.rgba(250, 200, 80, 230);
            case INCOMPLETE -> ImColor.rgba(190, 190, 190, 210);
            case UNCHANGED -> ImColor.rgba(100, 170, 245, 140);
        };
    }
}
