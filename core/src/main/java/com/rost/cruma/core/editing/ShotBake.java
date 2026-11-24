package com.rost.cruma.core.editing;

import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneItems;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class ShotBake {
    public static final int HELPER_NPC = 29153;

    public static final double STEP = .5;

    private static final double BEHIND = 220;
    private static final double SAME_MOMENT = 0.0005;
    private static final double SETTLE = 0.1;
    private static final int SMALL_REL = 1;
    private static final double MOST_TURN = 120;
    private static final double LEAST_RADIUS = 150;
    private static final double MOST_PITCH = 75;
    private static final double[] DISTANCES = {0.25, 0.5, 1, 2};
    private static final double[] AHEAD = {300, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000, 12000};
    private static final double[] AHEAD_WIDTHS = {1.25, 1.5, 2, 3};
    private static final double AHEAD_WORTH = 1.5;
    private static final int DIRECTIONS = 8;
    private static final double TRY_STEP = 1.0 / 15;

    private ShotBake() {}

    public record Baked(
            SceneDocument document, int helperIndex, double x, double y, double z, int shots, List<Item> replaced) {
        public Baked {
            replaced = List.copyOf(replaced);
        }
    }

    public static Baked bake(SceneDocument document) {
        return bake(document, HELPER_NPC, STEP);
    }

    public static Baked bake(SceneDocument document, int helperNpc, double step) {
        if (document.paths().isEmpty()) {
            return new Baked(document, 0, 0, 0, 0, 0, List.of());
        }
        var scene = document.scene();
        var flight = new Flight(scene, step);
        var center = flight.getBestCenter();
        var index = freeIndex(scene);
        var shots = flight.getShots(center, index);
        var result = merge(scene, shots, center, helperNpc, index);
        return new Baked(
                new SceneDocument(
                        document.name(),
                        document.basedOn(),
                        document.basedOnSha256(),
                        document.client(),
                        document.variant(),
                        document.map(),
                        document.seed(),
                        result.items,
                        List.of(),
                        document.replaces(),
                        document.hour()),
                index,
                center[0],
                center[1],
                center[2],
                shots.size(),
                result.replaced);
    }

    private static int freeIndex(Scene scene) {
        var taken = new TreeSet<Integer>();
        for (var item : scene.items()) {
            for (var actor : item.actors()) {
                taken.add(actor.index());
            }
        }
        var index = 1;
        while (taken.contains(index)) {
            index++;
        }
        return index;
    }

    private static Merged merge(Scene scene, List<Timed> shots, double[] center, int helperNpc, int index) {
        var times = scene.times();
        var items = new ArrayList<Item>(times.length + shots.size() + 1);
        var replaced = new ArrayList<Item>();
        for (var i = 0; i < times.length; i++) {
            var item = scene.items().get(i).at(times[i]);
            var shot = item.camera();
            if (shot != null && shot.specialView() && !shot.blank()) {
                replaced.add(item);
                item = withShot(item, Camera.blank(shot.wide()));
            }
            items.add(item);
        }
        var original = List.copyOf(items);
        for (var timed : shots) {
            var shot = timed.shot(SceneItems.isWideAt(original, timed.seconds));
            var at = free(items, timed.seconds);
            if (at >= 0) {
                items.set(at, withShot(items.get(at), shot));
            } else {
                items.add(after(items, timed.seconds), new Item(timed.seconds, "", shot, List.of(), null, null));
            }
        }
        var first = shots.getFirst().seconds;
        var spawn = new Actor(
                false,
                Action.SPAWN,
                helperNpc,
                index,
                0,
                center[0],
                center[1],
                center[2],
                0,
                0,
                "",
                true,
                false,
                false,
                false,
                0,
                0,
                false,
                false,
                0,
                0,
                List.of());
        items.addFirst(
                new Item(first, "", Camera.blank(SceneItems.isWideAt(original, first)), List.of(spawn), null, null));
        return new Merged(items, replaced);
    }

    private static int free(List<Item> items, double seconds) {
        for (var i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var shot = item.camera();
            if (Math.abs(item.seconds() - seconds) < SAME_MOMENT && (shot == null || shot.blank())) {
                return i;
            }
        }
        return -1;
    }

    private static int after(List<Item> items, double seconds) {
        var at = 0;
        while (at < items.size() && items.get(at).seconds() <= seconds) {
            at++;
        }
        return at;
    }

    private static Item withShot(Item item, Camera shot) {
        return new Item(
                item.seconds(),
                item.description(),
                shot,
                item.actors(),
                item.screen(),
                item.music(),
                item.playRate(),
                item.source());
    }

    private static double wrap(double degrees) {
        return Math.IEEEremainder(degrees, 360);
    }

    private static double milliseconds(double seconds) {
        return Math.rint(seconds * 1000) / 1000;
    }

    private record Merged(List<Item> items, List<Item> replaced) {}

    private record Timed(
            double seconds,
            int target,
            int distance,
            int yaw,
            int pitch,
            double time,
            double hold,
            int relYaw,
            int relPitch,
            int relRoll,
            boolean immediate,
            double fov) {
        Camera shot(boolean wide) {
            return new Camera(
                    Camera.SPECIAL_VIEW,
                    target,
                    distance,
                    yaw,
                    pitch,
                    time,
                    hold,
                    relYaw,
                    relPitch,
                    relRoll,
                    wide,
                    false,
                    immediate,
                    fov);
        }

        boolean needsSettling() {
            return Math.abs(relYaw) > SMALL_REL || Math.abs(relPitch) > SMALL_REL || Math.abs(relRoll) > SMALL_REL;
        }

        boolean sameNumbers(Timed other) {
            return distance == other.distance
                    && yaw == other.yaw
                    && pitch == other.pitch
                    && relYaw == other.relYaw
                    && relPitch == other.relPitch
                    && relRoll == other.relRoll;
        }
    }

    private record Around(double radius, double yaw, double pitch) {
        static Around of(Pose pose, double[] center) {
            var dx = center[0] - pose.x();
            var dy = center[1] - pose.y();
            var dz = center[2] - pose.z();
            var flat = Math.hypot(dx, dy);
            return new Around(
                    Math.sqrt(flat * flat + dz * dz),
                    Math.toDegrees(Math.atan2(dy, dx)),
                    Math.toDegrees(Math.atan2(dz, flat)));
        }
    }

    private static final class Flight {
        private final Scene scene;
        private final double end;
        private final List<Double> samples = new ArrayList<>();
        private final TreeSet<Double> cuts = new TreeSet<>();

        Flight(Scene scene, double step) {
            this.scene = scene;
            end = scene.seconds();
            var moments = new TreeSet<Double>();
            moments.add(0.0);
            var first = true;
            for (var path : scene.paths()) {
                if (!first) {
                    cuts.add(milliseconds(path.getStart()));
                }
                first = false;
                var last = Double.NaN;
                for (var key : path.getKeys()) {
                    var moment = milliseconds(key.seconds());
                    moments.add(moment);
                    if (moment == last) {
                        cuts.add(moment);
                    }
                    last = moment;
                }
            }
            var previous = -1.0;
            for (var moment : moments) {
                if (previous >= 0) {
                    addSamples(previous, moment, step);
                }
                previous = moment;
            }
            if (samples.isEmpty()) {
                samples.add(0.0);
            }
            samples.add(Math.max(previous, 0));
        }

        double[] getBestCenter() {
            var lowest = new double[] {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            var highest = new double[] {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (var at = 0.0; at <= end; at += TRY_STEP) {
                var pose = pose(at);
                var place = new double[] {pose.x(), pose.y(), pose.z()};
                for (var i = 0; i < 3; i++) {
                    lowest[i] = Math.min(lowest[i], place[i]);
                    highest[i] = Math.max(highest[i], place[i]);
                }
            }
            var middle = new double[] {
                (lowest[0] + highest[0]) / 2, (lowest[1] + highest[1]) / 2, (lowest[2] + highest[2]) / 2
            };
            var width = Math.max(highest[0] - lowest[0], Math.max(highest[1] - lowest[1], highest[2] - lowest[2]));
            var around = new ArrayList<double[]>();
            around.add(middle);
            for (var direction = 0; direction < DIRECTIONS; direction++) {
                var angle = 2 * Math.PI * direction / DIRECTIONS;
                for (var distance : DISTANCES) {
                    around.add(new double[] {
                        middle[0] + Math.cos(angle) * distance * width,
                        middle[1] + Math.sin(angle) * distance * width,
                        middle[2]
                    });
                }
            }
            var ahead = new ArrayList<double[]>();
            for (var distance : AHEAD) {
                ahead.add(ahead(distance));
            }
            for (var share : AHEAD_WIDTHS) {
                ahead.add(ahead(share * width));
            }
            var bestAhead = best(ahead);
            var bestAround = best(around);
            return error(bestAhead) <= error(bestAround) * AHEAD_WORTH ? bestAhead : bestAround;
        }

        List<Timed> getShots(double[] center, int index) {
            var shots = new ArrayList<Timed>();
            var lastFov = 0.0;
            var count = samples.size();
            for (var i = 0; i + 1 < count || i == 0; i++) {
                var start = samples.get(i);
                var stop = i + 1 < count ? samples.get(i + 1) : start;
                if (i == 0 || cuts.contains(start)) {
                    var pose = pose(start);
                    var placing = shot(pose, center, index, start, 0, i == 0, fov(pose.fov(), lastFov));
                    lastFov = pose.fov();
                    shots.add(placing);
                    if (i > 0 || placing.needsSettling()) {
                        start = milliseconds(start + SETTLE);
                    }
                }
                if (stop <= start) {
                    continue;
                }
                var goal = pose(cuts.contains(stop) ? stop - 0.001 : stop);
                var fov = pose((start + stop) / 2).fov();
                var next = shot(goal, center, index, start, stop - start, false, fov(fov, lastFov));
                if (!next.sameNumbers(shots.getLast()) || next.fov != 0) {
                    shots.add(next);
                    lastFov = fov;
                }
            }
            return shots;
        }

        private void addSamples(double from, double to, double step) {
            var count = moves(from, to) ? Math.max(1, (int) Math.round((to - from) / step)) : 1;
            for (var i = 0; i < count; i++) {
                addSample(
                        milliseconds(from + (to - from) * i / count),
                        milliseconds(from + (to - from) * (i + 1) / count));
            }
        }

        private void addSample(double from, double to) {
            var before = pose(from);
            var after = pose(to - 0.001);
            var turn =
                    Math.max(Math.abs(wrap(after.yaw() - before.yaw())), Math.abs(wrap(after.roll() - before.roll())));
            if (turn > MOST_TURN && to - from > 0.1) {
                var middle = milliseconds((from + to) / 2);
                addSample(from, middle);
                addSample(middle, to);
                return;
            }
            samples.add(from);
        }

        private boolean moves(double from, double to) {
            var a = pose(from);
            var b = pose((from + to) / 2);
            var c = pose(to - 0.001);
            return !a.equals(b) || !a.equals(c);
        }

        private Pose pose(double seconds) {
            return scene.flown(seconds);
        }

        private double[] ahead(double distance) {
            var first = pose(0);
            var yaw = Math.toRadians(first.yaw());
            var pitch = Math.toRadians(first.pitch());
            return new double[] {
                first.x() + Math.cos(pitch) * Math.cos(yaw) * distance,
                first.y() + Math.cos(pitch) * Math.sin(yaw) * distance,
                first.z() + Math.sin(pitch) * distance
            };
        }

        private double[] best(List<double[]> tried) {
            var best = tried.getLast();
            var lowest = Double.POSITIVE_INFINITY;
            for (var center : tried) {
                var error = error(center);
                if (error < lowest) {
                    lowest = error;
                    best = center;
                }
            }
            return best;
        }

        private double error(double[] center) {
            var worst = 0.0;
            var from = around(pose(0), center);
            if (from == null) {
                return Double.POSITIVE_INFINITY;
            }
            for (var i = 0; i + 1 < samples.size(); i++) {
                var start = samples.get(i);
                var stop = samples.get(i + 1);
                var to = around(pose(cuts.contains(stop) ? stop - 0.001 : stop), center);
                if (to == null) {
                    return Double.POSITIVE_INFINITY;
                }
                for (var at = start; at < stop; at += TRY_STEP) {
                    var part = (at - start) / (stop - start);
                    var yaw = Math.toRadians(from[1] + wrap(to[1] - from[1]) * part);
                    var pitch = Math.toRadians(from[2] + (to[2] - from[2]) * part);
                    var radius = from[0] + (to[0] - from[0]) * part + BEHIND;
                    var pose = pose(at);
                    var dx = center[0] - Math.cos(pitch) * Math.cos(yaw) * radius - pose.x();
                    var dy = center[1] - Math.cos(pitch) * Math.sin(yaw) * radius - pose.y();
                    var dz = center[2] - Math.sin(pitch) * radius - pose.z();
                    worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
                from = cuts.contains(stop) ? around(pose(stop), center) : to;
                if (from == null) {
                    return Double.POSITIVE_INFINITY;
                }
            }
            return worst;
        }

        private double[] around(Pose pose, double[] center) {
            var around = Around.of(pose, center);
            if (around.radius < LEAST_RADIUS || Math.abs(around.pitch) > MOST_PITCH) {
                return null;
            }
            return new double[] {Math.rint(around.radius - BEHIND), Math.rint(around.yaw), Math.rint(around.pitch)};
        }

        private Timed shot(
                Pose pose, double[] center, int index, double seconds, double time, boolean immediate, double fov) {
            var around = Around.of(pose, center);
            var yaw = Math.rint(around.yaw);
            var pitch = Math.rint(around.pitch);
            return new Timed(
                    seconds,
                    index,
                    (int) Math.rint(around.radius - BEHIND),
                    (int) -yaw,
                    (int) -pitch,
                    time,
                    milliseconds(end - seconds + 1),
                    (int) Math.rint(wrap(pose.yaw() - yaw)),
                    (int) Math.rint(pose.pitch() - pitch),
                    (int) Math.rint(wrap(pose.roll())),
                    immediate,
                    fov);
        }

        private static double fov(double wanted, double last) {
            return wanted == last ? 0 : wanted;
        }
    }
}
