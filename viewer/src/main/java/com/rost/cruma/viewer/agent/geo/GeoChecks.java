package com.rost.cruma.viewer.agent.geo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoQueryEngine.Failure;
import com.rost.cruma.core.geodata.GeoQueryEngine.Limits;
import com.rost.cruma.core.geodata.GeoQueryEngine.MoveResult;
import com.rost.cruma.core.geodata.GeoQueryEngine.SightResult;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.ServerPathFinder;
import com.rost.cruma.core.geodata.ServerPathFinder.Options;
import com.rost.cruma.core.geodata.ServerPathFinder.Replay;
import com.rost.cruma.core.geodata.ServerPathFinder.ReplayStatus;
import com.rost.cruma.core.geodata.ServerPathFinder.Status;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

final class GeoChecks {
    static final int SERVER_VISITS = 400;

    private static final int MOST_STEPS = 65_536;
    private static final int MOST_TRACE = 8192;
    private static final int MOST_WAYPOINTS = 128;
    private static final int WAYPOINTS_SHOWN = 12;
    private static final String GEODATA_ONLY =
            "Only geodata was checked. Doors and objects of the world can still block it.";

    private GeoChecks() {}

    static JSONObject move(GeoDataAccess data, GeoSpot from, GeoSpot to, BooleanSupplier stopped) {
        var engine = new GeoQueryEngine(data);
        var limits = new Limits(MOST_STEPS, MOST_TRACE, stopped);
        var start = from.point(data);
        var end = to.point(data);
        var there = engine.moveStraight(start, end, GeoQueryEngine.DEFAULT_DISTANCE, limits);
        if (arrivesElsewhere(there, to)) {
            end = new GeoPoint(end.x(), end.y(), there.finalPosition().z());
            there = engine.moveStraight(start, end, GeoQueryEngine.DEFAULT_DISTANCE, limits);
        }
        var back = engine.moveStraight(end, start, GeoQueryEngine.DEFAULT_DISTANCE, limits);
        if (arrivesElsewhere(back, from)) {
            back = engine.moveStraight(
                    end,
                    new GeoPoint(start.x(), start.y(), back.finalPosition().z()),
                    GeoQueryEngine.DEFAULT_DISTANCE,
                    limits);
        }
        var answer = new JSONObject();
        answer.put("kind", "move");
        answer.put("from", GeoWords.point(start));
        answer.put("to", GeoWords.point(end));
        answer.put("works", reaches(there));
        answer.put("there", leg(there, end));
        answer.put("back", leg(back, start));
        answer.put("summary", summary(reaches(there), reaches(back)));
        return answer;
    }

    static JSONObject sight(GeoDataAccess data, GeoSpot from, GeoSpot to, BooleanSupplier stopped) {
        var engine = new GeoQueryEngine(data);
        var limits = new Limits(MOST_STEPS, MOST_TRACE, stopped);
        var start = from.point(data);
        var end = to.point(data);
        var first = engine.checkSight(start, end, limits);
        var looks = new JSONArray();
        looks.add(look(first, end.z() < start.z() ? start : end));
        var answer = new JSONObject();
        answer.put("kind", "sight");
        answer.put("from", GeoWords.point(start));
        answer.put("to", GeoWords.point(end));
        answer.put("works", first.visible());
        answer.put("looks", looks);
        if (start.z() == end.z()) {
            var second = engine.checkSight(end, start, limits);
            looks.add(look(second, start));
            answer.put(
                    "summary",
                    first.visible() && second.visible()
                            ? "The line is clear."
                            : first.visible() || second.visible()
                                    ? "The line is clear from one end only."
                                    : "The line is cut.");
            answer.put("note", GEODATA_ONLY);
        } else {
            answer.put("summary", first.visible() ? "The line is clear." : "The line is cut.");
            answer.put(
                    "note",
                    GEODATA_ONLY + " The server looks from the higher end, so the check is the same both ways.");
        }
        return answer;
    }

    static JSONObject path(
            GeoDataAccess data,
            Path graph,
            GeoSpot from,
            GeoSpot to,
            int visits,
            @Nullable String note,
            BooleanSupplier stopped)
            throws ToolProblem {
        if (!Files.isRegularFile(graph.resolve("pathnode.idx"))
                || !Files.isRegularFile(graph.resolve("pathnode.bin"))) {
            throw new ToolProblem(
                    "The folder has no pathnode.idx and pathnode.bin, so the server would find no path here. Use kind"
                            + " move, or make the graph with geo_save path_nodes.");
        }
        var start = from.point(data);
        var end = to.point(data);
        var options = new Options(visits, MOST_TRACE, false, stopped);
        Replay there;
        Replay back;
        try (var nodes = PathNodeGraph.open(graph, stopped)) {
            var finder = new ServerPathFinder(nodes, new GeoQueryEngine(data), data);
            there = finder.replay(start, end, options, MOST_WAYPOINTS);
            back = finder.replay(end, start, options, MOST_WAYPOINTS);
        } catch (IOException unreadable) {
            throw new ToolProblem("The pathnode graph of " + graph + " cannot be read: " + unreadable.getMessage());
        }
        var answer = new JSONObject();
        answer.put("kind", "path");
        answer.put("from", GeoWords.point(start));
        answer.put("to", GeoWords.point(end));
        answer.put("works", there.status() == ReplayStatus.REACHED_DESTINATION);
        answer.put("there", leg(there, visits));
        answer.put("back", leg(back, visits));
        answer.put(
                "summary",
                summary(
                        there.status() == ReplayStatus.REACHED_DESTINATION,
                        back.status() == ReplayStatus.REACHED_DESTINATION));
        if (note != null) {
            answer.put("note", note);
        }
        return answer;
    }

    static boolean arrivesElsewhere(MoveResult move, GeoSpot end) {
        return !end.exact() && move.reachedDestination() && move.failure() == Failure.HEIGHT_MISMATCH;
    }

    static boolean reaches(MoveResult move) {
        return move.success() && move.reachedDestination();
    }

    static JSONObject leg(MoveResult move, GeoPoint end) {
        var leg = new JSONObject();
        leg.put("reaches", reaches(move));
        leg.put("walked", Math.round(move.traveledDistance()));
        if (reaches(move)) {
            leg.put("ends", GeoWords.point(move.finalPosition()));
            return leg;
        }
        leg.put("stopped_at", GeoWords.point(move.finalPosition()));
        leg.put(
                "why",
                move.failure() == Failure.NONE && move.distanceExhausted()
                        ? "It stayed clear for the " + Math.round(GeoQueryEngine.DEFAULT_DISTANCE)
                                + " units the server checks in one move. Check the rest as a second move, or use kind"
                                + " path."
                        : GeoWords.why(move, end));
        return leg;
    }

    static JSONObject leg(Replay replay, int visits) {
        var points = replay.waypoints();
        var length = 0.0;
        for (var index = 1; index < points.size(); index++) {
            length += Math.hypot(
                    points.get(index).x() - points.get(index - 1).x(),
                    points.get(index).y() - points.get(index - 1).y());
        }
        var leg = new JSONObject();
        var reached = replay.status() == ReplayStatus.REACHED_DESTINATION;
        leg.put("reaches", reached);
        leg.put("waypoints", points.size());
        leg.put("length", Math.round(length));
        var shown = new JSONArray();
        for (var index = 0; index < Math.min(points.size(), WAYPOINTS_SHOWN); index++) {
            shown.add(GeoWords.point(points.get(index)));
        }
        leg.put("points", shown);
        if (points.size() > WAYPOINTS_SHOWN) {
            leg.put("points_left_out", points.size() - WAYPOINTS_SHOWN);
        }
        if (reached) {
            leg.put("straight", points.size() <= 2 && replay.calls().getLast().status() == Status.CAN_GO);
        } else {
            leg.put("stopped_at", GeoWords.point(points.getLast()));
            leg.put("why", why(replay, visits));
        }
        return leg;
    }

    private static String summary(boolean there, boolean back) {
        if (there && back) {
            return "It works both ways.";
        }
        if (there) {
            return "It works there, but not back.";
        }
        return back ? "It does not work there, but it works back." : "It does not work in either way.";
    }

    private static JSONObject look(SightResult sight, GeoPoint from) {
        var look = new JSONObject();
        look.put("looked_from", GeoWords.point(from));
        look.put("sees", sight.visible());
        if (!sight.visible()) {
            var at =
                    sight.samples().isEmpty() ? from : sight.samples().getLast().position();
            look.put("stopped_at", GeoWords.point(at));
            look.put("why", GeoWords.why(sight, at));
        }
        return look;
    }

    private static String why(Replay replay, int visits) {
        if (replay.status() != ReplayStatus.STOPPED || replay.calls().isEmpty()) {
            return replay.detail();
        }
        var last = replay.calls().getLast();
        return last.isSuccess() ? replay.detail() : GeoWords.why(last.status(), visits, last.detail());
    }
}
