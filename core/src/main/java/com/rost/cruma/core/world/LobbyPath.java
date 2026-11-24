package com.rost.cruma.core.world;

import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.MapLink;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.MapScenes.Action;
import com.rost.cruma.core.world.MapScenes.Effect;
import com.rost.cruma.core.world.MapScenes.Kind;
import com.rost.cruma.core.world.MapScenes.Pose;
import com.rost.cruma.core.world.MapScenes.Scene;
import com.rost.cruma.core.world.MapScenes.Style;
import com.rost.cruma.core.world.MapScenes.SubAction;
import com.rost.cruma.core.world.MapScenes.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class LobbyPath {
    static final double STILL_SECONDS = 1;

    private static final String COPY = "_copy";
    private static final String FADE = "SubActionFade/";
    private static final double LEAST_FOV = 1;
    private static final double MOST_FOV = 170;
    private static final double SAME_MOMENT = 1e-6;
    private static final double STRAY = 1e-3;
    private static final double LEAST_SPEED = 1e-3;
    private static final double PAST_THE_END = 1;

    private LobbyPath() {}

    public record Sub(int action, int index, SubAction value, double start, double end) {}

    public static SceneDocument copy(
            Scene scene, String map, String client, String variant, long seed, String sha256, double fov) {
        var keys = keys(scene, fov);
        var path = new CameraPath(keys, false);
        var seconds = path.getEnd() > 0 ? path.getEnd() : STILL_SECONDS;
        return new SceneDocument(
                scene.tag() + COPY,
                scene.tag(),
                sha256,
                client,
                variant,
                map,
                seed,
                items(scene, seconds),
                List.of(path));
    }

    public static List<Key> keys(Scene scene, double fov) {
        var clock = clock(scene);
        var view = new Track(subs(scene), Effect.FOV, fov);
        var moments = moments(scene, clock);
        var fovMoments = view.getMoments();
        var actions = scene.actions();
        var keys = new ArrayList<Key>();
        var at = 0.0;
        var last = -1;
        for (var place = 0; place < actions.size(); place++) {
            var action = actions.get(place);
            if (action.seconds() <= 0 || scene.warp()) {
                continue;
            }
            var before = Math.floorMod(place - 1, actions.size());
            if (last != before) {
                keys.add(stop(action.from(), before, null, null, clock.toReal(at), view.getValue(at)));
            }
            var end = at + action.seconds();
            for (var moment : moments) {
                if (moment > at + SAME_MOMENT && moment < end - SAME_MOMENT) {
                    keys.add(rider(action, place, at, end, moment, clock, view, fovMoments));
                }
            }
            var curved = action.kind() == Kind.MOVE && action.style() == Style.BEZIER;
            keys.add(stop(
                    action.to(),
                    place,
                    curved ? action.control1().minus(action.from().place()) : null,
                    curved ? action.control2().minus(action.to().place()) : null,
                    clock.toReal(end),
                    view.getValue(end)));
            at = end;
            last = place;
        }
        if (keys.isEmpty()) {
            keys.add(stop(actions.getFirst().to(), 0, null, null, 0, limited(fov)));
        }
        return keys;
    }

    public static List<Sub> subs(Scene scene) {
        var subs = new ArrayList<Sub>();
        if (scene.warp()) {
            return subs;
        }
        var start = 0.0;
        var actions = scene.actions();
        for (var place = 0; place < actions.size(); place++) {
            var action = actions.get(place);
            for (var index = 0; index < action.subActions().size(); index++) {
                var sub = action.subActions().get(index);
                var begin = start + sub.delay();
                subs.add(new Sub(place, index, sub, begin, begin + sub.seconds()));
            }
            start += action.seconds();
        }
        return subs;
    }

    public static Clock clock(Scene scene) {
        return new Clock(new Track(subs(scene), Effect.SCENE_SPEED, 1));
    }

    public static Scene retimed(Scene scene, double[] seconds) {
        var actions = new ArrayList<Action>(seconds.length);
        for (var place = 0; place < seconds.length; place++) {
            var action = scene.actions().get(place);
            actions.add(new Action(
                    action.name(),
                    action.kind(),
                    action.style(),
                    action.from(),
                    action.control1(),
                    action.control2(),
                    action.to(),
                    seconds[place],
                    action.subActions()));
        }
        return new Scene(scene.tag(), scene.id(), scene.looping(), actions);
    }

    public static String fadeName(int action, int index) {
        return FADE + action + "/" + index;
    }

    public static int @Nullable [] fadeOf(String source) {
        if (!source.startsWith(FADE)) {
            return null;
        }
        var parts = source.substring(FADE.length()).split("/");
        try {
            return parts.length == 2 ? new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])} : null;
        } catch (NumberFormatException broken) {
            return null;
        }
    }

    private static double limited(double fov) {
        return Math.clamp(fov, LEAST_FOV, MOST_FOV);
    }

    private static Key stop(
            Pose pose, int action, @Nullable Vector leaves, @Nullable Vector reaches, double seconds, double fov) {
        var view = limited(fov);
        return new Key(
                seconds,
                pose.x(),
                pose.y(),
                pose.z(),
                pose.yawDegrees(),
                pose.pitchDegrees(),
                0,
                view,
                Ease.LINEAR,
                0,
                new MapLink(action, 0, 0, true, view, leaves, reaches));
    }

    private static Key rider(
            Action action,
            int place,
            double from,
            double to,
            double moment,
            Clock clock,
            Track view,
            TreeSet<Double> fovMoments) {
        var along = (moment - from) / (to - from);
        var first = clock.toReal(from);
        var when = (clock.toReal(moment) - first) / (clock.toReal(to) - first);
        var pose = action.at(along);
        var fov = limited(view.getValue(moment));
        var near = fovMoments.ceiling(moment - SAME_MOMENT);
        var shown = near != null && near <= moment + SAME_MOMENT;
        return new Key(
                clock.toReal(moment),
                pose.x(),
                pose.y(),
                pose.z(),
                pose.yawDegrees(),
                pose.pitchDegrees(),
                0,
                fov,
                Ease.LINEAR,
                0,
                new MapLink(place, along, when, shown, fov, null, null));
    }

    private static TreeSet<Double> moments(Scene scene, Clock clock) {
        var moments = new Track(subs(scene), Effect.FOV, 0).getMoments();
        moments.addAll(clock.track.getMoments());
        moments.addAll(clock.samples(scene.seconds()));
        return moments;
    }

    private static List<Item> items(Scene scene, double seconds) {
        var clock = clock(scene);
        var fades = new ArrayList<Sub>();
        for (var sub : subs(scene)) {
            if (sub.value().effect() == Effect.FADE && sub.start() < scene.seconds()) {
                fades.add(sub);
            }
        }
        fades.sort(Comparator.comparingDouble(Sub::start));
        var items = new ArrayList<Item>(fades.size() + 2);
        if (fades.isEmpty() || clock.toReal(fades.getFirst().start()) > SAME_MOMENT) {
            items.add(SceneDocument.blankItem(0));
        }
        for (var place = 0; place < fades.size(); place++) {
            var sub = fades.get(place);
            var moment = clock.toReal(sub.start());
            var length = clock.toReal(sub.end()) - moment;
            var next =
                    place + 1 < fades.size() ? clock.toReal(fades.get(place + 1).start()) : seconds + PAST_THE_END;
            var toBlack = sub.value().to() >= sub.value().from();
            var screen = toBlack
                    ? SceneScreens.fade(length, Math.max(0, next - moment - length), 0)
                    : SceneScreens.fade(0, 0, length);
            items.add(new Item(moment, "", null, List.of(), screen, null, 0, fadeName(sub.action(), sub.index())));
        }
        items.add(SceneDocument.blankItem(seconds));
        return items;
    }

    public static final class Clock {
        private final Track track;
        private final double[] real;

        private Clock(Track track) {
            this.track = track;
            real = new double[track.at.length];
            for (var index = 1; index < real.length; index++) {
                real[index] = real[index - 1] + seconds(index - 1, track.at[index] - track.at[index - 1]);
            }
        }

        public double toReal(double seconds) {
            var index = track.findStretch(seconds);
            return real[index] + seconds(index, seconds - track.at[index]);
        }

        public double toScene(double seconds) {
            var found = Arrays.binarySearch(real, seconds);
            var index = found >= 0 ? found : Math.max(0, -found - 2);
            var start = speed(track.from[index]);
            var slope = slope(index);
            var passed = seconds - real[index];
            var own = Math.abs(slope) < SAME_MOMENT ? passed * start : start * Math.expm1(slope * passed) / slope;
            return track.at[index] + own;
        }

        public boolean isEven() {
            for (var index = 0; index < track.from.length; index++) {
                if (speed(track.from[index]) != 1 || speed(track.to[index]) != 1) {
                    return false;
                }
            }
            return true;
        }

        private double seconds(int index, double length) {
            var start = speed(track.from[index]);
            var slope = slope(index);
            if (Math.abs(slope) < SAME_MOMENT) {
                return length / start;
            }
            return Math.log((start + slope * length) / start) / slope;
        }

        private double slope(int index) {
            if (index + 1 >= track.at.length) {
                return 0;
            }
            var length = track.at[index + 1] - track.at[index];
            return length <= 0 ? 0 : (speed(track.to[index]) - speed(track.from[index])) / length;
        }

        private List<Double> samples(double length) {
            var samples = new ArrayList<Double>();
            for (var index = 0; index + 1 < track.at.length; index++) {
                var slope = slope(index);
                if (Math.abs(slope) < SAME_MOMENT) {
                    continue;
                }
                var end = Math.min(track.at[index + 1], length);
                var moment = track.at[index];
                while (moment < end) {
                    var speed = speed(track.from[index]) + slope * (moment - track.at[index]);
                    moment += speed * Math.sqrt(8 * STRAY / Math.abs(slope * speed));
                    if (moment < end) {
                        samples.add(moment);
                    }
                }
            }
            return samples;
        }

        private static double speed(double value) {
            return Math.max(LEAST_SPEED, value);
        }
    }

    private static final class Track {
        private final double[] at;
        private final double[] from;
        private final double[] to;
        private final TreeSet<Double> ends = new TreeSet<>();

        Track(List<Sub> all, Effect effect, double first) {
            var subs = new ArrayList<Sub>();
            for (var sub : all) {
                if (sub.value().effect() == effect) {
                    subs.add(sub);
                }
            }
            var points = new TreeSet<Double>();
            points.add(0.0);
            for (var sub : subs) {
                points.add(sub.start());
                points.add(sub.end());
                ends.add(sub.start());
                ends.add(sub.end());
            }
            at = points.stream().mapToDouble(Double::doubleValue).toArray();
            from = new double[at.length];
            to = new double[at.length];
            var held = first;
            var begun = new double[subs.size()];
            Arrays.fill(begun, Double.NaN);
            for (var index = 0; index < at.length; index++) {
                var start = at[index];
                var end = index + 1 < at.length ? at[index + 1] : Double.POSITIVE_INFINITY;
                var running = -1;
                for (var place = 0; place < subs.size(); place++) {
                    var sub = subs.get(place);
                    if (sub.start() <= start && start < sub.end() && end <= sub.end()) {
                        running = place;
                        if (Double.isNaN(begun[place])) {
                            begun[place] = held;
                        }
                    }
                }
                if (running < 0) {
                    from[index] = held;
                    to[index] = held;
                } else {
                    var sub = subs.get(running);
                    from[index] = sub.value().value(share(sub, start), begun[running]);
                    to[index] = sub.value().value(share(sub, end), begun[running]);
                    held = to[index];
                }
                for (var sub : subs) {
                    if (sub.end() == end && sub.value().seconds() <= 0) {
                        held = sub.value().value(1, held);
                    }
                }
            }
        }

        double getValue(double seconds) {
            var index = findStretch(seconds);
            if (index + 1 >= at.length) {
                return from[index];
            }
            var share = (seconds - at[index]) / (at[index + 1] - at[index]);
            return from[index] + (to[index] - from[index]) * share;
        }

        int findStretch(double seconds) {
            var found = Arrays.binarySearch(at, seconds);
            return found >= 0 ? found : Math.max(0, -found - 2);
        }

        TreeSet<Double> getMoments() {
            return new TreeSet<>(ends);
        }

        private static double share(Sub sub, double moment) {
            return sub.value().seconds() <= 0
                    ? 1
                    : Math.min(1, (moment - sub.start()) / sub.value().seconds());
        }
    }
}
