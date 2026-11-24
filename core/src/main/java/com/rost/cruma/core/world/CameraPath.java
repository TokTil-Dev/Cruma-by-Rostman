package com.rost.cruma.core.world;

import com.rost.cruma.core.world.MapScenes.Action;
import com.rost.cruma.core.world.MapScenes.Kind;
import com.rost.cruma.core.world.MapScenes.Style;
import com.rost.cruma.core.world.MapScenes.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

public final class CameraPath {
    private static final double TILE_STEP = 1024;
    private static final double NO_LENGTH = 1e-6;

    private final List<Key> keys;
    private final List<Key> stops;
    private final boolean evenSpeed;
    private final List<Action> ways;
    private final List<Action> segments;
    private final double[] distances;
    private final double[][] steps;
    private final Curve yaw;
    private final Curve pitch;
    private final Curve roll;
    private final Curve fov;
    private final List<String> tiles;
    private final boolean looks;

    public CameraPath(List<Key> keys, boolean evenSpeed) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("A camera path needs a key");
        }
        this.evenSpeed = evenSpeed;
        var sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.comparingDouble(Key::seconds));
        var stops = stops(sorted);
        ways = List.copyOf(segments(stops, evenSpeed));
        var all = withRiders(stops, ways, sorted);
        segments = split(all, stops, ways);
        steps = evenSpeed ? steps(segments) : new double[0][];
        distances = evenSpeed ? distances(steps) : new double[0];
        this.keys = List.copyOf(evenSpeed ? spread(all, distances) : all);
        this.stops = List.copyOf(stops(this.keys));
        yaw = Curve.turn(this.keys, Key::yaw);
        pitch = Curve.plain(this.keys, Key::pitch);
        roll = Curve.turn(this.keys, Key::roll);
        fov = Curve.plain(this.keys, Key::fov);
        tiles = tilesUnder(this.keys, segments);
        looks = this.keys.stream().anyMatch(key -> key.lookAt() > 0);
    }

    public enum Ease {
        SMOOTH,
        LINEAR
    }

    public record Key(
            double seconds,
            double x,
            double y,
            double z,
            double yaw,
            double pitch,
            double roll,
            double fov,
            Ease ease,
            int lookAt,
            @Nullable MapLink link) {
        public Key(
                double seconds,
                double x,
                double y,
                double z,
                double yaw,
                double pitch,
                double roll,
                double fov,
                Ease ease,
                int lookAt) {
            this(seconds, x, y, z, yaw, pitch, roll, fov, ease, lookAt, null);
        }

        public Key(
                double seconds,
                double x,
                double y,
                double z,
                double yaw,
                double pitch,
                double roll,
                double fov,
                Ease ease) {
            this(seconds, x, y, z, yaw, pitch, roll, fov, ease, 0);
        }

        public Key withSeconds(double value) {
            return new Key(value, x, y, z, yaw, pitch, roll, fov, ease, lookAt, link);
        }

        public Key withEase(Ease value) {
            return new Key(seconds, x, y, z, yaw, pitch, roll, fov, value, lookAt, link);
        }

        public Key withLookAt(int index) {
            return new Key(seconds, x, y, z, yaw, pitch, roll, fov, ease, index, link);
        }

        public Key withLink(@Nullable MapLink value) {
            return new Key(seconds, x, y, z, yaw, pitch, roll, fov, ease, lookAt, value);
        }

        public Key at(double placeX, double placeY, double placeZ) {
            return new Key(seconds, placeX, placeY, placeZ, yaw, pitch, roll, fov, ease, lookAt, link);
        }

        public Key with(
                double placeX, double placeY, double placeZ, double turn, double tilt, double tip, double view) {
            return new Key(seconds, placeX, placeY, placeZ, turn, tilt, tip, view, ease, lookAt, link);
        }

        public boolean rides() {
            return link != null && link.rides();
        }
    }

    public record MapLink(
            int action,
            double along,
            double when,
            boolean shown,
            double fov,
            @Nullable Vector leaves,
            @Nullable Vector reaches) {
        public MapLink(int action, double fov) {
            this(action, 0, 0, true, fov, null, null);
        }

        public boolean rides() {
            return along > 0;
        }
    }

    @FunctionalInterface
    public interface Places {
        @Nullable
        Vector place(int index, double seconds);
    }

    public record Pose(double x, double y, double z, double yaw, double pitch, double roll, double fov) {}

    public List<Key> getKeys() {
        return keys;
    }

    public boolean hasEvenSpeed() {
        return evenSpeed;
    }

    public List<Key> getStops() {
        return stops;
    }

    public List<Action> getWays() {
        return ways;
    }

    public double getStart() {
        return keys.getFirst().seconds();
    }

    public double getEnd() {
        return keys.getLast().seconds();
    }

    public Pose getPose(double seconds) {
        if (keys.size() == 1 || seconds <= getStart()) {
            return getPose(keys.getFirst());
        }
        if (seconds >= getEnd()) {
            return getPose(keys.getLast());
        }
        var index = segment(seconds);
        var from = keys.get(index);
        var gap = keys.get(index + 1).seconds() - from.seconds();
        var t = (seconds - from.seconds()) / gap;
        var place = segments.get(index).place(evenSpeed ? evenProgress(index, seconds) : t);
        return new Pose(
                place.x(),
                place.y(),
                place.z(),
                Math.IEEEremainder(yaw.getValue(index, t, gap), 360),
                pitch.getValue(index, t, gap),
                Math.IEEEremainder(roll.getValue(index, t, gap), 360),
                fov.getValue(index, t, gap));
    }

    public Pose getPose(double seconds, Places places) {
        var pose = getPose(seconds);
        if (!looks) {
            return pose;
        }
        var index = seconds <= getStart() || keys.size() == 1
                ? 0
                : seconds >= getEnd() ? keys.size() - 1 : segment(seconds);
        var from = keys.get(index);
        var to = index + 1 < keys.size() ? keys.get(index + 1) : from;
        var gap = to.seconds() - from.seconds();
        var share = gap <= 0 ? 0 : Math.clamp((seconds - from.seconds()) / gap, 0, 1);
        if (from.ease() != Ease.LINEAR || to.ease() != Ease.LINEAR) {
            share = share * share * (3 - 2 * share);
        }
        var first = from.lookAt() > 0 ? places.place(from.lookAt(), seconds) : null;
        var second = to.lookAt() > 0 && to != from ? places.place(to.lookAt(), seconds) : null;
        if (first == null && second == null) {
            return pose;
        }
        Vector point;
        double weight;
        if (first != null && second != null) {
            point = first.plus(second.minus(first).times(share));
            weight = 1;
        } else if (first != null) {
            point = first;
            weight = to == from ? 1 : 1 - share;
        } else {
            point = second;
            weight = share;
        }
        var across = point.x() - pose.x();
        var along = point.y() - pose.y();
        var up = point.z() - pose.z();
        var flat = Math.hypot(across, along);
        if (flat == 0 && up == 0) {
            return pose;
        }
        var yaw = Math.toDegrees(Math.atan2(along, across));
        var pitch = Math.toDegrees(Math.atan2(up, flat));
        return new Pose(
                pose.x(),
                pose.y(),
                pose.z(),
                Math.IEEEremainder(pose.yaw() + Math.IEEEremainder(yaw - pose.yaw(), 360) * weight, 360),
                pose.pitch() + (pitch - pose.pitch()) * weight,
                pose.roll(),
                pose.fov());
    }

    public boolean looks() {
        return looks;
    }

    public double length() {
        var length = 0.0;
        for (var segment : segments) {
            length += segment.length();
        }
        return length;
    }

    public List<List<Vector>> getOutline(double step) {
        var lines = new ArrayList<List<Vector>>();
        var points = new ArrayList<Vector>();
        var first = keys.getFirst();
        points.add(new Vector(first.x(), first.y(), first.z()));
        for (var index = 0; index < segments.size(); index++) {
            var next = keys.get(index + 1);
            if (next.seconds() == keys.get(index).seconds()) {
                lines.add(points);
                points = new ArrayList<>();
                points.add(new Vector(next.x(), next.y(), next.z()));
                continue;
            }
            var segment = segments.get(index);
            var count = stepsOf(segment, step);
            for (var i = 1; i <= count; i++) {
                points.add(segment.place((double) i / count));
            }
        }
        lines.add(points);
        return lines;
    }

    public List<String> getTiles() {
        return tiles;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CameraPath path && evenSpeed == path.evenSpeed && keys.equals(path.keys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keys, evenSpeed);
    }

    @Override
    public String toString() {
        return "CameraPath" + keys + (evenSpeed ? " at an even speed" : "");
    }

    private int segment(double seconds) {
        var low = 0;
        var high = keys.size() - 2;
        while (low < high) {
            var middle = (low + high + 1) >>> 1;
            if (keys.get(middle).seconds() <= seconds) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    private double evenProgress(int index, double seconds) {
        var total = distances[distances.length - 1];
        if (total < NO_LENGTH) {
            return 0;
        }
        var along = total * (seconds - getStart()) / (getEnd() - getStart()) - distances[index];
        var table = steps[index];
        if (table[table.length - 1] < NO_LENGTH) {
            return 0;
        }
        var found = Arrays.binarySearch(table, along);
        if (found >= 0) {
            return (double) found / (table.length - 1);
        }
        var after = Math.min(-found - 1, table.length - 1);
        var before = Math.max(after - 1, 0);
        var span = table[after] - table[before];
        var part = span <= 0 ? 0 : (along - table[before]) / span;
        return (before + Math.clamp(part, 0, 1)) / (table.length - 1);
    }

    private static Pose getPose(Key key) {
        return new Pose(
                key.x(),
                key.y(),
                key.z(),
                Math.IEEEremainder(key.yaw(), 360),
                key.pitch(),
                Math.IEEEremainder(key.roll(), 360),
                key.fov());
    }

    private static List<Key> stops(List<Key> sorted) {
        var ends = new HashSet<Integer>();
        var first = true;
        for (var key : sorted) {
            if (!key.rides()) {
                if (!first && key.link() != null) {
                    ends.add(key.link().action());
                }
                first = false;
            }
        }
        var stops = new ArrayList<Key>(sorted.size());
        for (var key : sorted) {
            if (!key.rides() || !ends.contains(key.link().action())) {
                stops.add(key);
            }
        }
        return stops;
    }

    private static List<Key> withRiders(List<Key> stops, List<Action> ways, List<Key> sorted) {
        var all = new ArrayList<Key>(sorted.size());
        all.add(stops.getFirst());
        var riders = new ArrayList<Key>();
        for (var index = 1; index < stops.size(); index++) {
            var from = stops.get(index - 1);
            var to = stops.get(index);
            riders.clear();
            for (var key : sorted) {
                if (key.rides()
                        && to.link() != null
                        && !to.rides()
                        && key.link().action() == to.link().action()) {
                    riders.add(key);
                }
            }
            riders.sort(Comparator.comparingDouble(key -> key.link().along()));
            for (var rider : riders) {
                all.add(rider(rider, from, to, ways.get(index - 1)));
            }
            all.add(to);
        }
        return all;
    }

    private static Key rider(Key key, Key from, Key to, Action way) {
        var link = key.link();
        var along = link.along();
        var place = way.place(along);
        return new Key(
                from.seconds() + (to.seconds() - from.seconds()) * link.when(),
                place.x(),
                place.y(),
                place.z(),
                MapScenes.turn(from.yaw(), to.yaw(), along),
                from.pitch() + (to.pitch() - from.pitch()) * along,
                MapScenes.turn(from.roll(), to.roll(), along),
                key.fov(),
                Ease.LINEAR,
                key.lookAt(),
                link);
    }

    private static List<Action> split(List<Key> all, List<Key> stops, List<Action> ways) {
        if (all.size() == stops.size()) {
            return ways;
        }
        var segments = new ArrayList<Action>(all.size());
        var stop = 0;
        var from = 0.0;
        for (var index = 0; index + 1 < all.size(); index++) {
            var next = all.get(index + 1);
            var ends = next.equals(stops.get(stop + 1));
            var to = ends ? 1 : next.link().along();
            segments.add(part(ways.get(stop), from, to, all.get(index), next));
            stop += ends ? 1 : 0;
            from = ends ? 0 : to;
        }
        return segments;
    }

    private static Action part(Action way, double from, double to, Key start, Key end) {
        return new Action(
                "",
                Kind.MOVE,
                Style.BEZIER,
                mapPose(start),
                blossom(way, from, from, to),
                blossom(way, from, to, to),
                mapPose(end),
                end.seconds() - start.seconds(),
                List.of());
    }

    private static Vector blossom(Action way, double u, double v, double w) {
        var first = mix(way.from().place(), way.control1(), u);
        var second = mix(way.control1(), way.control2(), u);
        var third = mix(way.control2(), way.to().place(), u);
        return mix(mix(first, second, v), mix(second, third, v), w);
    }

    private static Vector mix(Vector from, Vector to, double share) {
        return from.plus(to.minus(from).times(share));
    }

    private static List<Action> segments(List<Key> keys, boolean evenSpeed) {
        var segments = new ArrayList<Action>(keys.size());
        var gaps = gaps(keys, evenSpeed);
        for (var i = 0; i + 1 < keys.size(); i++) {
            var from = keys.get(i);
            var to = keys.get(i + 1);
            var start = place(from);
            var end = place(to);
            var chord = end.minus(start);
            var third = chord.times(1.0 / 3);
            var curve =
                    to.link() != null && to.link().leaves() != null && to.link().reaches() != null ? to.link() : null;
            var control1 = curve != null
                    ? start.plus(curve.leaves())
                    : from.ease() == Ease.LINEAR
                            ? start.plus(third)
                            : start.plus(velocity(keys, gaps, i).times(gaps[i] / 3));
            var control2 = curve != null
                    ? end.plus(curve.reaches())
                    : to.ease() == Ease.LINEAR
                            ? end.minus(third)
                            : end.minus(velocity(keys, gaps, i + 1).times(gaps[i] / 3));
            segments.add(new Action(
                    "",
                    Kind.MOVE,
                    Style.BEZIER,
                    mapPose(from),
                    control1,
                    control2,
                    mapPose(to),
                    to.seconds() - from.seconds(),
                    List.of()));
        }
        return segments;
    }

    private static double[] gaps(List<Key> keys, boolean evenSpeed) {
        var gaps = new double[keys.size() - 1];
        for (var i = 0; i < gaps.length; i++) {
            gaps[i] = evenSpeed
                    ? place(keys.get(i)).distance(place(keys.get(i + 1)))
                    : keys.get(i + 1).seconds() - keys.get(i).seconds();
        }
        return gaps;
    }

    private static Vector velocity(List<Key> keys, double[] gaps, int index) {
        var before = index > 0 ? gaps[index - 1] : 0;
        var after = index < gaps.length ? gaps[index] : 0;
        if (before <= 0 && after <= 0) {
            return new Vector(0, 0, 0);
        }
        var earlier = before > 0 ? keys.get(index - 1) : keys.get(index);
        var later = after > 0 ? keys.get(index + 1) : keys.get(index);
        return place(later).minus(place(earlier)).times(1 / (before + after));
    }

    private static Vector place(Key key) {
        return new Vector(key.x(), key.y(), key.z());
    }

    private static MapScenes.Pose mapPose(Key key) {
        return new MapScenes.Pose(key.x(), key.y(), key.z(), key.yaw(), key.pitch());
    }

    private static double[][] steps(List<Action> segments) {
        var steps = new double[segments.size()][];
        for (var i = 0; i < steps.length; i++) {
            var table = new double[MapScenes.PATH_STEPS + 1];
            var previous = segments.get(i).place(0);
            for (var step = 1; step < table.length; step++) {
                var next = segments.get(i).place((double) step / MapScenes.PATH_STEPS);
                table[step] = table[step - 1] + next.distance(previous);
                previous = next;
            }
            steps[i] = table;
        }
        return steps;
    }

    private static double[] distances(double[][] steps) {
        var distances = new double[steps.length + 1];
        for (var i = 0; i < steps.length; i++) {
            distances[i + 1] = distances[i] + steps[i][steps[i].length - 1];
        }
        return distances;
    }

    private static List<Key> spread(List<Key> keys, double[] distances) {
        var total = distances[distances.length - 1];
        if (total < NO_LENGTH) {
            return keys;
        }
        var start = keys.getFirst().seconds();
        var span = keys.getLast().seconds() - start;
        var spread = new ArrayList<>(keys);
        for (var i = 1; i + 1 < keys.size(); i++) {
            spread.set(i, keys.get(i).withSeconds(start + span * distances[i] / total));
        }
        return spread;
    }

    private static int stepsOf(Action segment, double step) {
        return Math.max(1, (int) Math.ceil(segment.length() / step));
    }

    private static List<String> tilesUnder(List<Key> keys, List<Action> segments) {
        var tiles = new LinkedHashSet<String>();
        add(tiles, place(keys.getFirst()));
        for (var segment : segments) {
            var count = stepsOf(segment, TILE_STEP);
            for (var i = 1; i <= count; i++) {
                add(tiles, segment.place((double) i / count));
            }
        }
        return List.copyOf(tiles);
    }

    private static void add(LinkedHashSet<String> tiles, Vector place) {
        var tile = WorldTile.at((float) place.x(), (float) place.y());
        tiles.add(tile.x() + "_" + tile.y());
    }

    private static final class Curve {
        private final double[] values;
        private final double[] leaves;
        private final double[] reaches;

        private Curve(List<Key> keys, double[] values) {
            this.values = values;
            leaves = new double[values.length];
            reaches = new double[values.length];
            for (var i = 0; i < values.length; i++) {
                var before = i > 0 ? slope(keys, values, i - 1) : Double.NaN;
                var after = i + 1 < values.length ? slope(keys, values, i) : Double.NaN;
                var linear = keys.get(i).ease() == Ease.LINEAR;
                var through = through(before, after);
                leaves[i] = linear && !Double.isNaN(after) ? after : through;
                reaches[i] = linear && !Double.isNaN(before) ? before : through;
            }
        }

        static Curve plain(List<Key> keys, ToDoubleFunction<Key> number) {
            var values = new double[keys.size()];
            for (var i = 0; i < values.length; i++) {
                values[i] = number.applyAsDouble(keys.get(i));
            }
            return new Curve(keys, values);
        }

        static Curve turn(List<Key> keys, ToDoubleFunction<Key> number) {
            var values = new double[keys.size()];
            values[0] = number.applyAsDouble(keys.getFirst());
            for (var i = 1; i < values.length; i++) {
                values[i] = values[i - 1] + MapScenes.turn(0, number.applyAsDouble(keys.get(i)) - values[i - 1], 1);
            }
            return new Curve(keys, values);
        }

        double getValue(int index, double t, double gap) {
            var square = t * t;
            var cube = square * t;
            return (2 * cube - 3 * square + 1) * values[index]
                    + (cube - 2 * square + t) * gap * leaves[index]
                    + (3 * square - 2 * cube) * values[index + 1]
                    + (cube - square) * gap * reaches[index + 1];
        }

        private static double slope(List<Key> keys, double[] values, int index) {
            var gap = keys.get(index + 1).seconds() - keys.get(index).seconds();
            return gap <= 0 ? Double.NaN : (values[index + 1] - values[index]) / gap;
        }

        private static double through(double before, double after) {
            if (Double.isNaN(before)) {
                return Double.isNaN(after) ? 0 : after;
            }
            if (Double.isNaN(after)) {
                return before;
            }
            if (before * after <= 0) {
                return 0;
            }
            var limit = 3 * Math.min(Math.abs(before), Math.abs(after));
            return Math.copySign(Math.min(Math.abs(before + after) / 2, limit), before);
        }
    }
}
