package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastBaker.Terms;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class BeastRebake {
    static final float BOUNCE_REACH = 512f;

    static final int DILATION_RINGS = 4;

    private BeastRebake() {}

    public record Update(float[] slot0, float[] slot1, boolean[] changed, int changedTexels) {}

    public static float[][] bakeFull(
            ActorLightingTrace trace,
            LocalLights lights,
            BeastBakeModel model,
            Mesh mesh,
            float[] localToWorld,
            int width,
            int height,
            BooleanSupplier cancelled) {
        return BeastBaker.bake(trace, lights, model, mesh, localToWorld, width, height, cancelled);
    }

    public static float[][] transferBake(
            float[][] saved,
            ActorLightingTrace fromTrace,
            ActorLightingTrace toTrace,
            LocalLights lights,
            BeastBakeModel model,
            Mesh mesh,
            float[] fromMatrix,
            float[] toMatrix,
            int width,
            int height,
            BooleanSupplier cancelled) {
        var texels = BeastTexels.of(mesh, width, height);
        var fromQuery = fromTrace.query();
        var toQuery = toTrace.query();
        var fromCandidates = lights.findReaching(BeastBaker.worldBox(mesh, fromMatrix));
        var toCandidates = lights.findReaching(BeastBaker.worldBox(mesh, toMatrix));
        var slot0 = saved[0].clone();
        var slot1 = saved[1].clone();
        var delta0 = new float[slot0.length];
        var delta1 = new float[slot1.length];
        var covered = new boolean[width * height];
        var before = new Terms();
        var after = new Terms();
        var p = new float[3];
        var n = new float[3];
        var q = new float[3];
        var m = new float[3];
        var a = new float[3];
        var b = new float[3];
        for (var i = 0; i < texels.getCount(); i++) {
            checkCancelled(cancelled, i);
            if (!texels.computeWorldPoint(fromMatrix, i, p, n) || !texels.computeWorldPoint(toMatrix, i, q, m)) {
                continue;
            }
            var texel = texels.getTexel(i);
            var seed = BeastBaker.seed(new float[] {texel, width, height});
            BeastBaker.computeTerms(fromQuery, lights, fromCandidates, model, p, n, seed, before, null);
            BeastBaker.computeTerms(toQuery, lights, toCandidates, model, q, m, seed, after, null);
            covered[texel] = true;
            BeastBaker.computeSlot0Linear(model, before, a);
            BeastBaker.computeSlot0Linear(model, after, b);
            apply(model, 0, slot0, delta0, texel, a, b);
            BeastBaker.computeSlot1Linear(model, before, a);
            BeastBaker.computeSlot1Linear(model, after, b);
            apply(model, 1, slot1, delta1, texel, a, b);
        }
        spreadDelta(slot0, delta0, covered, width, height);
        spreadDelta(slot1, delta1, covered, width, height);
        return new float[][] {slot0, slot1};
    }

    public static float[][] combine(float[][] full, float[][] transfer) {
        var slot0 = new float[full[0].length];
        for (var i = 0; i < slot0.length; i++) {
            slot0[i] = (full[0][i] + transfer[0][i]) / 2;
        }
        return new float[][] {slot0, transfer[1].clone()};
    }

    public static Update rebakeDifferential(
            float[][] saved,
            ActorLightingTrace oldTrace,
            ActorLightingTrace newTrace,
            LocalLights lights,
            BeastBakeModel model,
            Mesh mesh,
            float[] localToWorld,
            int width,
            int height,
            List<float[]> changedBoxes,
            BooleanSupplier cancelled) {
        var slot0 = saved[0].clone();
        var slot1 = saved[1].clone();
        var changed = new boolean[width * height];
        var box = BeastBaker.worldBox(mesh, localToWorld);
        if (!reaches(box, changedBoxes, model)) {
            return new Update(slot0, slot1, changed, 0);
        }
        var texels = BeastTexels.of(mesh, width, height);
        var oldQuery = oldTrace.query();
        var newQuery = newTrace.query();
        var candidates = lights.findReaching(box);
        var delta0 = new float[slot0.length];
        var delta1 = new float[slot1.length];
        var touched = new boolean[width * height];
        var delta = new Terms();
        var p = new float[3];
        var n = new float[3];
        var d = new float[3];
        var sun = model.sunDirection();
        var reach = model.occlusionDistance() + 2 * BeastBaker.BIAS;
        var count = 0;
        for (var i = 0; i < texels.getCount(); i++) {
            checkCancelled(cancelled, i);
            if (!texels.computeWorldPoint(localToWorld, i, p, n)) {
                continue;
            }
            var near = isNearAnyBox(p, changedBoxes, reach);
            if (!near
                    && !doesSunRayMeetAnyBox(p, sun, changedBoxes)
                    && !isTowardsLights(p, candidates, lights, changedBoxes)) {
                continue;
            }
            var texel = texels.getTexel(i);
            var seed = BeastBaker.seed(new float[] {texel, width, height});
            var bounce = near && isNearAnyBox(p, changedBoxes, BOUNCE_REACH);
            BeastBaker.computeDeltaTerms(
                    oldQuery, newQuery, lights, candidates, model, p, n, seed, changedBoxes, near, bounce, delta);
            BeastBaker.computeSlot0Linear(model, delta, d);
            var moved = apply(model, 0, slot0, delta0, texel, d);
            BeastBaker.computeSlot1Linear(model, delta, d);
            moved |= apply(model, 1, slot1, delta1, texel, d);
            if (moved) {
                touched[texel] = true;
                count++;
            }
        }
        if (count == 0) {
            return new Update(slot0, slot1, changed, 0);
        }
        var covered = texels.getCoverage();
        System.arraycopy(touched, 0, changed, 0, changed.length);
        spreadTouched(slot0, slot1, delta0, delta1, touched, covered, changed, width, height);
        return new Update(slot0, slot1, changed, count);
    }

    static boolean reaches(float[] box, List<float[]> changedBoxes, BeastBakeModel model) {
        var sun = model.sunDirection();
        var far = ActorLightingTrace.SUN_RAY_LENGTH;
        var radius = model.occlusionDistance() + 2 * BeastBaker.BIAS;
        for (var changed : changedBoxes) {
            var swept = new float[6];
            for (var axis = 0; axis < 3; axis++) {
                swept[axis] = Math.min(box[axis], box[axis] + sun[axis] * far) - radius;
                swept[axis + 3] = Math.max(box[axis + 3], box[axis + 3] + sun[axis] * far) + radius;
            }
            if (overlaps(swept, changed)) {
                return true;
            }
        }
        return false;
    }

    static int[] relevantLights(LocalLights lights, List<float[]> boxes) {
        var seen = new TreeSet<Integer>();
        for (var box : boxes) {
            for (var index : lights.findReaching(box)) {
                seen.add(index);
            }
        }
        return seen.stream().mapToInt(Integer::intValue).toArray();
    }

    static int[] findReaching(LocalLights lights, int[] subset, float[] p) {
        if (subset.length == 0) {
            return subset;
        }
        var result = new int[subset.length];
        var count = 0;
        for (var index : subset) {
            var light = lights.lights().get(index);
            var dx = (double) light.x() - p[0];
            var dy = (double) light.y() - p[1];
            var dz = (double) light.z() - p[2];
            if (dx * dx + dy * dy + dz * dz < (double) light.radius() * light.radius()) {
                result[count++] = index;
            }
        }
        return Arrays.copyOf(result, count);
    }

    static boolean isNearAnyBox(float[] p, List<float[]> boxes, float reach) {
        for (var box : boxes) {
            var gap = 0.0;
            for (var axis = 0; axis < 3; axis++) {
                var value = Math.max(0, Math.max(box[axis] - p[axis], p[axis] - box[axis + 3]));
                gap += value * value;
            }
            if (gap <= (double) reach * reach) {
                return true;
            }
        }
        return false;
    }

    static boolean doesSunRayMeetAnyBox(float[] p, float[] sun, List<float[]> boxes) {
        var far = ActorLightingTrace.SUN_RAY_LENGTH;
        return BeastBaker.meetsAnyBox(
                boxes, p[0], p[1], p[2], p[0] + sun[0] * far, p[1] + sun[1] * far, p[2] + sun[2] * far);
    }

    static boolean isTowardsLights(float[] p, int[] candidates, LocalLights lights, List<float[]> boxes) {
        for (var index : candidates) {
            var light = lights.lights().get(index);
            if (BeastBaker.meetsAnyBox(boxes, p[0], p[1], p[2], light.x(), light.y(), light.z())) {
                return true;
            }
        }
        return false;
    }

    static boolean doesSegmentHitBox(
            float sx, float sy, float sz, float ex, float ey, float ez, float[] box, float grow) {
        var t0 = 0.0;
        var t1 = 1.0;
        var start = new double[] {sx, sy, sz};
        var delta = new double[] {ex - sx, ey - sy, ez - sz};
        for (var axis = 0; axis < 3; axis++) {
            var min = box[axis] - grow;
            var max = box[axis + 3] + grow;
            if (Math.abs(delta[axis]) < 1e-12) {
                if (start[axis] < min || start[axis] > max) {
                    return false;
                }
                continue;
            }
            var a = (min - start[axis]) / delta[axis];
            var b = (max - start[axis]) / delta[axis];
            t0 = Math.max(t0, Math.min(a, b));
            t1 = Math.min(t1, Math.max(a, b));
            if (t0 > t1) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(float[] a, float[] b) {
        return a[0] <= b[3] && a[3] >= b[0] && a[1] <= b[4] && a[4] >= b[1] && a[2] <= b[5] && a[5] >= b[2];
    }

    private static boolean apply(
            BeastBakeModel model, int slot, float[] image, float[] delta, int texel, float[] change) {
        var moved = false;
        for (var c = 0; c < 3; c++) {
            var stored = image[texel * 3 + c];
            var next = BeastBaker.encode(model, BeastBaker.decode(model, stored) + change[c]);
            next = Math.min(next, Math.max(stored, model.limit(slot, c)));
            delta[texel * 3 + c] = next - stored;
            image[texel * 3 + c] = next;
            moved |= Math.abs(next - stored) >= 1f;
        }
        return moved;
    }

    private static boolean apply(
            BeastBakeModel model, int slot, float[] image, float[] delta, int texel, float[] before, float[] after) {
        var change = new float[] {after[0] - before[0], after[1] - before[1], after[2] - before[2]};
        return apply(model, slot, image, delta, texel, change);
    }

    private static void spreadDelta(float[] image, float[] delta, boolean[] covered, int width, int height) {
        var filled = covered.clone();
        for (var ring = 0; ring < DILATION_RINGS; ring++) {
            var next = filled.clone();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var i = y * width + x;
                    if (filled[i]) {
                        continue;
                    }
                    var sum = new float[3];
                    var count = 0;
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = x + dx;
                            var ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || !filled[ny * width + nx]) {
                                continue;
                            }
                            for (var c = 0; c < 3; c++) {
                                sum[c] += delta[(ny * width + nx) * 3 + c];
                            }
                            count++;
                        }
                    }
                    if (count > 0) {
                        for (var c = 0; c < 3; c++) {
                            delta[i * 3 + c] = sum[c] / count;
                            image[i * 3 + c] = Math.clamp(image[i * 3 + c] + delta[i * 3 + c], 0f, 255f);
                        }
                        next[i] = true;
                    }
                }
            }
            System.arraycopy(next, 0, filled, 0, filled.length);
        }
    }

    private static void spreadTouched(
            float[] slot0,
            float[] slot1,
            float[] delta0,
            float[] delta1,
            boolean[] touched,
            boolean[] covered,
            boolean[] changed,
            int width,
            int height) {
        var source = touched.clone();
        for (var ring = 0; ring < DILATION_RINGS; ring++) {
            var next = source.clone();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var i = y * width + x;
                    if (covered[i] || source[i]) {
                        continue;
                    }
                    var sum0 = new float[3];
                    var sum1 = new float[3];
                    var count = 0;
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = x + dx;
                            var ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || !source[ny * width + nx]) {
                                continue;
                            }
                            var j = ny * width + nx;
                            for (var c = 0; c < 3; c++) {
                                sum0[c] += delta0[j * 3 + c];
                                sum1[c] += delta1[j * 3 + c];
                            }
                            count++;
                        }
                    }
                    if (count == 0) {
                        continue;
                    }
                    for (var c = 0; c < 3; c++) {
                        delta0[i * 3 + c] = sum0[c] / count;
                        delta1[i * 3 + c] = sum1[c] / count;
                        slot0[i * 3 + c] = Math.clamp(slot0[i * 3 + c] + delta0[i * 3 + c], 0f, 255f);
                        slot1[i * 3 + c] = Math.clamp(slot1[i * 3 + c] + delta1[i * 3 + c], 0f, 255f);
                    }
                    next[i] = true;
                    changed[i] = true;
                }
            }
            System.arraycopy(next, 0, source, 0, source.length);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled, int index) {
        if ((index & 255) == 0
                && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
            throw new CancellationException("Beast bake cancelled");
        }
    }
}
