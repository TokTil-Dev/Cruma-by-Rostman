package com.rost.cruma.core.export;

import com.rost.cruma.core.export.SceneTimeline.View;
import com.rost.cruma.core.world.CameraPath;
import com.rost.cruma.core.world.CameraPath.Ease;
import com.rost.cruma.core.world.CameraPath.Key;
import com.rost.cruma.core.world.CameraPath.Pose;
import com.rost.cruma.core.world.ShotMath;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class PathFit {
    private static final double CUT_SHARE = 4;
    private static final double LEAST_CUT = 64;
    private static final double LEAST_CUT_TURN = 20;
    private static final double PLACE_STEPS = 100;
    private static final double ANGLE_STEPS = 1_000;
    private static final int PRUNE_REACH = 4;

    private PathFit() {}

    public record Fit(List<Key> keys, double worstPlace, double worstTurn, int cuts) {}

    public static @Nullable Fit fit(List<View> views, double place, double turn) {
        if (views.isEmpty()) {
            return null;
        }
        var samples = new ArrayList<Pose>(views.size());
        var seconds = new double[views.size()];
        for (var index = 0; index < seconds.length; index++) {
            samples.add(pose(views.get(index)));
            seconds[index] = views.get(index).seconds();
        }
        var cuts = cuts(samples);
        var kept = new TreeSet<Integer>();
        kept.add(0);
        kept.add(samples.size() - 1);
        for (var cut = cuts.nextSetBit(0); cut >= 0; cut = cuts.nextSetBit(cut + 1)) {
            kept.add(cut);
        }
        refine(samples, seconds, cuts, kept, place, turn);
        prune(samples, seconds, cuts, kept, place, turn);
        var path = refine(samples, seconds, cuts, kept, place, turn);
        return result(path, samples, seconds, cuts);
    }

    public static Pose pose(View view) {
        double x = view.lookX();
        double y = view.lookY();
        double z = view.lookZ();
        var levelX = -x * z;
        var levelY = -y * z;
        var levelZ = 1 - z * z;
        var length = Math.sqrt(levelX * levelX + levelY * levelY + levelZ * levelZ);
        var roll = 0.0;
        if (length > 1e-6) {
            levelX /= length;
            levelY /= length;
            levelZ /= length;
            var rightX = levelY * z - levelZ * y;
            var rightY = levelZ * x - levelX * z;
            var rightZ = levelX * y - levelY * x;
            var along = view.upX() * levelX + view.upY() * levelY + view.upZ() * levelZ;
            var across = view.upX() * rightX + view.upY() * rightY + view.upZ() * rightZ;
            roll = Math.toDegrees(Math.atan2(across, along));
        }
        return new Pose(
                view.x(),
                view.y(),
                view.z(),
                Math.toDegrees(Math.atan2(y, x)),
                Math.toDegrees(Math.asin(Math.clamp(z, -1, 1))),
                roll,
                view.fov());
    }

    private static CameraPath refine(
            List<Pose> samples, double[] seconds, BitSet cuts, TreeSet<Integer> kept, double place, double turn) {
        while (true) {
            var path = new CameraPath(keys(samples, seconds, cuts, kept), false);
            var added = new ArrayList<Integer>();
            Integer from = null;
            for (var to : kept) {
                if (from != null) {
                    var worst = worst(path, samples, seconds, from, to, place, turn);
                    if (worst >= 0) {
                        added.add(worst);
                    }
                }
                from = to;
            }
            if (added.isEmpty()) {
                return path;
            }
            kept.addAll(added);
        }
    }

    private static void prune(
            List<Pose> samples, double[] seconds, BitSet cuts, TreeSet<Integer> kept, double place, double turn) {
        for (var index : List.copyOf(kept)) {
            if (index == 0 || index == samples.size() - 1 || cuts.get(index)) {
                continue;
            }
            kept.remove(index);
            var before = reach(kept.headSet(index, false).descendingSet());
            var after = reach(kept.tailSet(index, false));
            var around = new TreeSet<>(before);
            around.addAll(after);
            var path = new CameraPath(keys(samples, seconds, cuts, around), false);
            if (worst(path, samples, seconds, inner(before), inner(after), place, turn) >= 0) {
                kept.add(index);
            }
        }
    }

    private static int inner(List<Integer> reach) {
        return reach.size() < PRUNE_REACH ? reach.getLast() : reach.get(PRUNE_REACH - 2);
    }

    private static List<Integer> reach(Iterable<Integer> from) {
        var keys = new ArrayList<Integer>(PRUNE_REACH);
        for (var index : from) {
            keys.add(index);
            if (keys.size() == PRUNE_REACH) {
                break;
            }
        }
        return keys;
    }

    private static BitSet cuts(List<Pose> samples) {
        var cuts = new BitSet();
        var lastMove = 0.0;
        var lastTurn = 0.0;
        for (var index = 1; index < samples.size(); index++) {
            var move = ShotMath.distance(samples.get(index - 1), samples.get(index));
            var turned = ShotMath.turned(samples.get(index - 1), samples.get(index));
            if (move > LEAST_CUT && move > lastMove * CUT_SHARE
                    || turned > LEAST_CUT_TURN && turned > lastTurn * CUT_SHARE) {
                cuts.set(index);
            }
            lastMove = move;
            lastTurn = turned;
        }
        return cuts;
    }

    private static int worst(
            CameraPath path, List<Pose> samples, double[] seconds, int from, int to, double place, double turn) {
        var worst = -1;
        var most = 1.0;
        for (var index = from + 1; index < to; index++) {
            var pose = path.getPose(seconds[index]);
            var sample = samples.get(index);
            var error = Math.max(
                    ShotMath.distance(pose, sample) / Math.max(place, 1e-6),
                    turnError(pose, sample) / Math.max(turn, 1e-6));
            if (error > most) {
                most = error;
                worst = index;
            }
        }
        return worst;
    }

    private static double turnError(Pose pose, Pose sample) {
        return Math.max(ShotMath.turned(pose, sample), Math.abs(pose.fov() - sample.fov()));
    }

    private static List<Key> keys(List<Pose> samples, double[] seconds, BitSet cuts, TreeSet<Integer> kept) {
        var keys = new ArrayList<Key>(kept.size());
        for (var index : kept) {
            if (cuts.get(index)) {
                keys.add(key(seconds[index], samples.get(index - 1)));
            }
            keys.add(key(seconds[index], samples.get(index)));
        }
        return keys;
    }

    private static Key key(double seconds, Pose pose) {
        return new Key(
                seconds,
                rounded(pose.x(), PLACE_STEPS),
                rounded(pose.y(), PLACE_STEPS),
                rounded(pose.z(), PLACE_STEPS),
                rounded(pose.yaw(), ANGLE_STEPS),
                rounded(pose.pitch(), ANGLE_STEPS),
                rounded(pose.roll(), ANGLE_STEPS),
                rounded(pose.fov(), ANGLE_STEPS),
                Ease.SMOOTH);
    }

    private static double rounded(double value, double steps) {
        return Math.round(value * steps) / steps;
    }

    private static Fit result(CameraPath path, List<Pose> samples, double[] seconds, BitSet cuts) {
        var worstPlace = 0.0;
        var worstTurn = 0.0;
        for (var index = 0; index < seconds.length; index++) {
            var pose = path.getPose(seconds[index]);
            worstPlace = Math.max(worstPlace, ShotMath.distance(pose, samples.get(index)));
            worstTurn = Math.max(worstTurn, turnError(pose, samples.get(index)));
        }
        return new Fit(path.getKeys(), worstPlace, worstTurn, cuts.cardinality());
    }
}
