package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoBodyCheck;
import com.rost.cruma.core.geodata.GeoCellRef;
import com.rost.cruma.core.geodata.GeoConnectedSurface;
import com.rost.cruma.core.geodata.GeoConnectedSurface.Connectivity;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoPathNodeFolder;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoQueryEngine.Limits;
import com.rost.cruma.core.geodata.GeoReachability;
import com.rost.cruma.core.geodata.GeoReachability.Access;
import com.rost.cruma.core.geodata.GeoReachability.FrontierReason;
import com.rost.cruma.core.geodata.GeoReachability.Node;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.core.geodata.ServerPathFinder;
import com.rost.cruma.core.geodata.ServerPathFinder.Options;
import com.rost.cruma.core.geodata.ServerPathFinder.ReplayStatus;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.geodata.GeoGraphView;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class GeoRoutes {
    static final int REACH_NODES = 100_000;

    static final long REACH_MILLIS = 5000;

    static final int GRAPH_NODES = 2048;

    private static final int MOST_TRACE = 8192;
    private static final int MOST_WAYPOINTS = 128;
    private static final int ISSUES_SHOWN = 12;
    private static final List<String> CONNECT = List.of("both", "from", "to", "either");

    private GeoRoutes() {}

    static List<String> getConnectWords() {
        return CONNECT;
    }

    static JSONObject reach(GeoDataAccess data, GeoSpot from, int radius, String connect, BooleanSupplier stopped)
            throws ToolProblem {
        var connectivity = switch (connect) {
            case "both" -> Connectivity.MUTUAL;
            case "from" -> Connectivity.OUTGOING;
            case "to" -> Connectivity.INCOMING;
            case "either" -> Connectivity.EITHER;
            default -> throw new ToolProblem("connect must be both, from, to or either.");
        };
        var floor = from.floor(data);
        if (floor == null) {
            var cell = from.cell(data);
            throw new ToolProblem(
                    cell.availability() == Availability.AVAILABLE
                            ? "The cell at " + cell.x() + "," + cell.y() + " has no floor to start from."
                            : GeoWords.noData(cell));
        }
        var seed = new GeoCellRef(
                data.getSourceId(),
                data.revision(),
                floor.cell().x(),
                floor.cell().y(),
                floor.layer().index());
        var limits = new GeoReachability.Limits(radius, REACH_NODES, REACH_MILLIS, stopped);
        var area = GeoConnectedSurface.select(data, seed, connectivity, limits);
        var reach = area.reachability();
        var counts = new EnumMap<Access, Integer>(Access.class);
        for (var access : reach.nodes().values()) {
            counts.merge(access, 1, Integer::sum);
        }
        var answer = new JSONObject();
        answer.put("kind", "reach");
        answer.put("from", GeoWords.point(Node.of(floor).center()));
        answer.put("radius", radius);
        answer.put("floors", reach.nodes().size());
        answer.put("search", word(reach.completeness()));
        answer.put("both_ways", counts.getOrDefault(Access.MUTUAL, 0));
        answer.put("only_there", counts.getOrDefault(Access.OUTBOUND_ONLY, 0));
        answer.put("only_back", counts.getOrDefault(Access.INBOUND_ONLY, 0));
        if (!reach.frontier().isEmpty()) {
            var reasons = new EnumMap<FrontierReason, Integer>(FrontierReason.class);
            for (var frontier : reach.frontier()) {
                reasons.merge(frontier.reason(), 1, Integer::sum);
            }
            var stops = new JSONObject();
            reasons.forEach((reason, count) -> stops.put(word(reason.name()), count));
            answer.put("stopped_by", stops);
        }
        if (reach.unavailableCells() > 0) {
            answer.put("cells_without_data", reach.unavailableCells());
        }
        answer.put("isolated", isolated(area.layers(), connect, Node.of(floor)));
        answer.put(
                "note",
                "only_there floors are reached by a drop and lead no way back. The search stops at the radius, which"
                        + " is no wall.");
        return answer;
    }

    static JSONObject route(
            GeoDataAccess data,
            @Nullable Path graph,
            List<GeoSpot> spots,
            int visits,
            int body,
            @Nullable String note,
            BooleanSupplier stopped)
            throws ToolProblem {
        var engine = new GeoQueryEngine(data);
        var limits = new Limits(65_536, MOST_TRACE, stopped);
        var legs = new JSONArray();
        var length = 0L;
        var reaches = true;
        var firstStop = -1;
        PathNodeGraph nodes = null;
        try {
            if (graph != null) {
                requireGraph(graph);
                nodes = PathNodeGraph.open(graph, stopped);
            }
            var from = spots.getFirst().point(data);
            for (var index = 1; index < spots.size(); index++) {
                var spot = spots.get(index);
                var to = spot.point(data);
                JSONObject leg;
                var line = new ArrayList<GeoPoint>();
                boolean reached;
                if (nodes == null) {
                    var move = engine.moveStraight(from, to, GeoQueryEngine.DEFAULT_DISTANCE, limits);
                    if (GeoChecks.arrivesElsewhere(move, spot)) {
                        to = new GeoPoint(to.x(), to.y(), move.finalPosition().z());
                        move = engine.moveStraight(from, to, GeoQueryEngine.DEFAULT_DISTANCE, limits);
                    }
                    move.samples().forEach(sample -> line.add(sample.position()));
                    leg = GeoChecks.leg(move, to);
                    reached = GeoChecks.reaches(move);
                    leg.remove("reaches");
                } else {
                    var finder = new ServerPathFinder(nodes, engine, data);
                    var replay =
                            finder.replay(from, to, new Options(visits, MOST_TRACE, false, stopped), MOST_WAYPOINTS);
                    line.addAll(replay.waypoints());
                    leg = GeoChecks.leg(replay, visits);
                    leg.put("server_calls", replay.calls().size());
                    reached = replay.status() == ReplayStatus.REACHED_DESTINATION;
                    leg.remove("reaches");
                    if (reached) {
                        to = replay.waypoints().getLast();
                    }
                }
                var narrow = GeoBodyCheck.check(data, line, body, stopped);
                var ordered = new JSONObject();
                ordered.put("leg", letter(index - 1) + " to " + letter(index));
                ordered.put("from", GeoWords.point(from));
                ordered.put("to", GeoWords.point(to));
                ordered.put("reaches", reached && narrow == null);
                if (narrow != null) {
                    ordered.put("too_narrow_at", GeoWords.point(narrow.at()));
                    ordered.put(
                            "why",
                            "A body of "
                                    + body
                                    + " units touches the cell at "
                                    + narrow.cellX()
                                    + ","
                                    + narrow.cellY()
                                    + ", which has no open ground near this height.");
                }
                ordered.putAll(leg);
                legs.add(ordered);
                length += leg.containsKey("walked") ? leg.getLongValue("walked") : leg.getLongValue("length");
                if (!(reached && narrow == null) && firstStop < 0) {
                    firstStop = index - 1;
                    reaches = false;
                }
                from = to;
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("The pathnode graph of " + graph + " cannot be read: " + unreadable.getMessage());
        } finally {
            if (nodes != null) {
                nodes.close();
            }
        }
        var answer = new JSONObject();
        answer.put("kind", graph == null ? "move" : "path");
        answer.put("works", reaches);
        answer.put(
                "summary",
                reaches
                        ? "It reaches "
                                + letter(spots.size() - 1)
                                + " through "
                                + legs.size()
                                + " legs, "
                                + length
                                + " units."
                        : "Leg " + legs.getJSONObject(firstStop).getString("leg") + " stops first.");
        if (body > 0) {
            answer.put("body", body);
        }
        answer.put("length", length);
        answer.put("legs", legs);
        answer.put(
                "note",
                (note == null ? "" : note + " ")
                        + "A route through stops runs one way, leg by leg."
                        + (body > 0 ? " The body check is an estimate: the server moves a point." : ""));
        return answer;
    }

    static JSONObject graph(GeoDataAccess data, Path folder, GeoSpot at, int radius, BooleanSupplier stopped)
            throws ToolProblem {
        requireGraph(folder);
        var center = at.point(data);
        var answer = new JSONObject();
        answer.put("kind", "graph");
        answer.put("around", GeoWords.point(center));
        answer.put("radius", radius);
        try (var nodes = PathNodeGraph.open(folder, stopped)) {
            var view = GeoGraphView.read(nodes, center, radius, GRAPH_NODES, stopped);
            answer.put("nodes", view.nodes().size());
            answer.put("links", view.edges().size());
            if (view.truncated()) {
                answer.put("limited", "Only the first " + GRAPH_NODES + " nodes were read. Give a smaller radius.");
            }
            PathNodeGraph.Node nearest = null;
            var best = Double.MAX_VALUE;
            for (var node : view.nodes()) {
                var far = Math.hypot(
                        node.position().x() - center.x(), node.position().y() - center.y());
                if (far < best) {
                    best = far;
                    nearest = node;
                }
            }
            if (nearest != null) {
                var near = new JSONObject();
                near.put("at", GeoWords.point(nearest.position()));
                near.put("distance", Math.round(best));
                var links = 0;
                for (var neighbor : nearest.neighbors()) {
                    if (neighbor == 0) {
                        break;
                    }
                    links++;
                }
                near.put("links", links);
                answer.put("nearest_node", near);
            } else {
                answer.put("why", "No pathnode stands within the radius, so the server cannot route here.");
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("The pathnode graph of " + folder + " cannot be read: " + unreadable.getMessage());
        }
        answer.put("graph", graphState(folder, stopped));
        answer.put(
                "note",
                "A route walks from its start to a near node, follows links and leaves the graph near its end. Missing"
                        + " links mean the server cannot route there.");
        return answer;
    }

    static JSONObject files(Path folder, BooleanSupplier stopped) throws ToolProblem {
        requireGraph(folder);
        var answer = new JSONObject();
        answer.put("kind", "files");
        answer.put("folder", folder.toString());
        try (var nodes = PathNodeGraph.open(folder, stopped)) {
            var audit = nodes.audit(stopped);
            answer.put("valid", audit.isValid());
            answer.put("nodes", audit.nodesChecked());
            answer.put("links", audit.edgesChecked());
            answer.put("binary_bytes", nodes.getMetadata().binaryBytes());
            if (!audit.binarySha256().isBlank()) {
                answer.put("sha256", audit.binarySha256());
            }
            if (audit.cancelled()) {
                answer.put("stopped", "The check was stopped before its end.");
            }
            if (audit.issueCount() > 0) {
                answer.put("issues", audit.issueCount());
                var shown = new JSONArray();
                shown.addAll(audit.issues()
                        .subList(0, Math.min(ISSUES_SHOWN, audit.issues().size())));
                answer.put("first_issues", shown);
            }
        } catch (IOException unreadable) {
            throw new ToolProblem("The pathnode graph of " + folder + " cannot be read: " + unreadable.getMessage());
        }
        answer.put("graph", graphState(folder, stopped));
        return answer;
    }

    static JSONObject graphState(Path folder, BooleanSupplier stopped) {
        var state = new JSONObject();
        try {
            var status = GeoPathNodeFolder.check(folder, stopped);
            state.put("state", word(status.state().name()));
            if (status.table() != null) {
                state.put("zones", status.table() == ZoneTable.Y10_TO_25 ? "320" : "340");
            }
            state.put(
                    "means",
                    switch (status.state()) {
                        case NONE -> "The folder has no pathnode.idx.";
                        case CURRENT -> "Cruma built the graph from the regions the folder has now.";
                        case STALE ->
                            "Regions changed since the graph was built: "
                                    + String.join(
                                            ", ",
                                            status.changed()
                                                    .subList(
                                                            0,
                                                            Math.min(
                                                                    ISSUES_SHOWN,
                                                                    status.changed()
                                                                            .size())))
                                    + ". Rebuild it with geo_build path_nodes.";
                        case UNKNOWN ->
                            "Another tool built this graph, so nothing says which regions it was built from.";
                    });
        } catch (IOException unreadable) {
            state.put("state", "unreadable");
            state.put("means", unreadable.getMessage());
        }
        return state;
    }

    static String letter(int index) {
        return String.valueOf((char) ('A' + index));
    }

    private static JSONObject isolated(Set<Node> layers, String connect, Node start) {
        var cells = new HashSet<Long>();
        var regions = new TreeSet<String>();
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        var lowest = Integer.MAX_VALUE;
        var highest = Integer.MIN_VALUE;
        Node farthest = null;
        var distance = -1.0;
        for (var node : layers) {
            cells.add(((long) node.x() << 32) ^ (node.y() & 0xffffffffL));
            regions.add(GeoWords.region(node.x(), node.y()));
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + 16);
            maxY = Math.max(maxY, node.y() + 16);
            lowest = Math.min(lowest, node.height());
            highest = Math.max(highest, node.height());
            var far = Math.hypot(node.x() - start.x(), node.y() - start.y());
            if (far > distance) {
                distance = far;
                farthest = node;
            }
        }
        var area = new JSONObject();
        area.put("connect", connect);
        area.put("floors", layers.size());
        area.put("cells", cells.size());
        if (farthest != null) {
            area.put("box", minX + "," + minY + " to " + maxX + "," + maxY);
            area.put("lowest", lowest);
            area.put("highest", highest);
            area.put("regions", String.join(", ", regions));
            area.put("farthest", GeoWords.point(farthest.center()));
        }
        return area;
    }

    private static void requireGraph(Path folder) throws ToolProblem {
        if (!Files.isRegularFile(folder.resolve("pathnode.idx"))
                || !Files.isRegularFile(folder.resolve("pathnode.bin"))) {
            throw new ToolProblem(
                    "The folder has no pathnode.idx and pathnode.bin. Make the graph with geo_build path_nodes, or use"
                            + " kind move.");
        }
    }

    private static String word(Completeness completeness) {
        return word(completeness.name());
    }

    private static String word(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
