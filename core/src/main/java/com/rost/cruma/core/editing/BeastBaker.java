package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Light;
import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import com.rost.cruma.core.editing.ActorLightingTrace.Query;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public final class BeastBaker {
    public static final int HEMISPHERE_RAYS = 32;

    public static final int CALIBRATION_RAYS = 64;

    static final float BIAS = 4f;

    private static final float[] HEMISPHERE = createLocalHemisphere(HEMISPHERE_RAYS);
    private static final float[] CALIBRATION_HEMISPHERE = createLocalHemisphere(CALIBRATION_RAYS);

    private BeastBaker() {}

    public static final class Terms {
        public float direct;

        public float maskedDirect;

        public float sky;

        public float litBounce;

        public float darkBounce;

        public final float[] local = new float[3];

        void clear() {
            direct = 0;
            maskedDirect = 0;
            sky = 0;
            litBounce = 0;
            darkBounce = 0;
            local[0] = 0;
            local[1] = 0;
            local[2] = 0;
        }
    }

    @FunctionalInterface
    public interface LightVisitor {
        void visit(int light, float ratio, float cosine, boolean visible);
    }

    public record LocalLights(List<Light> lights) {
        public LocalLights {
            lights = List.copyOf(lights);
        }

        public static LocalLights of(ActorLightingScene scene) {
            var result = new ArrayList<Light>();
            for (var light : scene.getBeastLights().isEmpty() ? scene.getLights() : scene.getBeastLights()) {
                if (light.effect() == ActorLightingBaker.SUNLIGHT
                        || light.type() == 0
                        || light.excluded()
                        || light.hidden()) {
                    continue;
                }
                result.add(light);
            }
            return new LocalLights(result);
        }

        public int[] findReaching(float[] box) {
            MutableIntList result = new IntArrayList();
            for (var i = 0; i < lights.size(); i++) {
                var light = lights.get(i);
                var gap = 0.0;
                var x = Math.max(0, Math.max(box[0] - light.x(), light.x() - box[3]));
                var y = Math.max(0, Math.max(box[1] - light.y(), light.y() - box[4]));
                var z = Math.max(0, Math.max(box[2] - light.z(), light.z() - box[5]));
                gap = x * x + y * y + z * z;
                if (gap < (double) light.radius() * light.radius()) {
                    result.add(i);
                }
            }
            return result.toArray();
        }
    }

    public static void computeTerms(
            Query query,
            LocalLights lights,
            int[] candidates,
            BeastBakeModel model,
            float[] p,
            float[] n,
            int seed,
            Terms out,
            @Nullable LightVisitor visitor) {
        computeTerms(query, lights, candidates, model, p, n, seed, HEMISPHERE_RAYS, out, visitor);
    }

    public static void computeTerms(
            Query query,
            LocalLights lights,
            int[] candidates,
            BeastBakeModel model,
            float[] p,
            float[] n,
            int seed,
            int rays,
            Terms out,
            @Nullable LightVisitor visitor) {
        out.clear();
        var sun = model.sunDirection();
        var sx = p[0] + n[0] * BIAS;
        var sy = p[1] + n[1] * BIAS;
        var sz = p[2] + n[2] * BIAS;
        var facing = n[0] * sun[0] + n[1] * sun[1] + n[2] * sun[2];
        if (facing > 0) {
            if (isSunVisible(query, sx, sy, sz, sun, true)) {
                out.direct = facing;
            } else if (isSunVisible(query, sx, sy, sz, sun, false)) {
                out.maskedDirect = facing;
            }
        }
        computeHemisphereTerms(query, model, sun, n, sx, sy, sz, seed, rays, out);
        computeLocalTerms(query, lights, candidates, model, p, n, sx, sy, sz, out, visitor);
    }

    public static void computeDeltaTerms(
            Query before,
            Query after,
            LocalLights lights,
            int[] candidates,
            BeastBakeModel model,
            float[] p,
            float[] n,
            int seed,
            List<float[]> boxes,
            boolean near,
            boolean bounce,
            Terms delta) {
        delta.clear();
        var sun = model.sunDirection();
        var sx = p[0] + n[0] * BIAS;
        var sy = p[1] + n[1] * BIAS;
        var sz = p[2] + n[2] * BIAS;
        var far = ActorLightingTrace.SUN_RAY_LENGTH;
        var facing = n[0] * sun[0] + n[1] * sun[1] + n[2] * sun[2];
        if (facing > 0 && meetsAnyBox(boxes, sx, sy, sz, sx + sun[0] * far, sy + sun[1] * far, sz + sun[2] * far)) {
            var old = sunState(before, sx, sy, sz, sun);
            var now = sunState(after, sx, sy, sz, sun);
            delta.direct = facing * ((now == 2 ? 1 : 0) - (old == 2 ? 1 : 0));
            delta.maskedDirect = facing * ((now == 1 ? 1 : 0) - (old == 1 ? 1 : 0));
        }
        if (near) {
            var directions = computeHemisphereRays(n, seed, HEMISPHERE_RAYS);
            var count = directions.length / 3;
            var weight = 1f / count;
            var distance = model.occlusionDistance();
            for (var ray = 0; ray < count; ray++) {
                var dx = directions[ray * 3];
                var dy = directions[ray * 3 + 1];
                var dz = directions[ray * 3 + 2];
                var ex = sx + dx * distance;
                var ey = sy + dy * distance;
                var ez = sz + dz * distance;
                if (meetsAnyBox(boxes, sx, sy, sz, ex, ey, ez)) {
                    add(delta, hemisphereState(after, sx, sy, sz, ex, ey, ez, dx, dy, dz, sun), weight);
                    add(delta, hemisphereState(before, sx, sy, sz, ex, ey, ez, dx, dy, dz, sun), -weight);
                    continue;
                }
                if (!bounce || !after.findFirstHit(sx, sy, sz, ex, ey, ez, true)) {
                    continue;
                }
                var hx = after.hitLocationX - dx * BIAS;
                var hy = after.hitLocationY - dy * BIAS;
                var hz = after.hitLocationZ - dz * BIAS;
                if (!meetsAnyBox(boxes, hx, hy, hz, hx + sun[0] * far, hy + sun[1] * far, hz + sun[2] * far)) {
                    continue;
                }
                var old = isSunVisible(before, hx, hy, hz, sun, true);
                var now = isSunVisible(after, hx, hy, hz, sun, true);
                if (old != now) {
                    delta.litBounce += now ? weight : -weight;
                    delta.darkBounce += now ? -weight : weight;
                }
            }
        }
        if (!model.localShadows()) {
            return;
        }
        for (var index : candidates) {
            var light = lights.lights().get(index);
            var dx = light.x() - p[0];
            var dy = light.y() - p[1];
            var dz = light.z() - p[2];
            var squared = dx * dx + dy * dy + dz * dz;
            var radius = light.radius();
            if (!(squared < radius * radius)
                    || !(squared > 1e-6f)
                    || !meetsAnyBox(boxes, sx, sy, sz, light.x(), light.y(), light.z())) {
                continue;
            }
            var distance = (float) Math.sqrt(squared);
            var cosine = (n[0] * dx + n[1] * dy + n[2] * dz) / distance;
            if (!(cosine > 0)) {
                continue;
            }
            cosine *= computeConeFactor(light, dx, dy, dz, distance);
            if (!(cosine > 0)) {
                continue;
            }
            var old = before.clear(sx, sy, sz, light.x(), light.y(), light.z(), true);
            var now = after.clear(sx, sy, sz, light.x(), light.y(), light.z(), true);
            if (old == now) {
                continue;
            }
            var factor = BeastBakeModel.falloff(model.falloff(), distance / radius) * cosine * (now ? 1 : -1);
            delta.local[0] += light.red() * factor;
            delta.local[1] += light.green() * factor;
            delta.local[2] += light.blue() * factor;
        }
    }

    static boolean meetsAnyBox(List<float[]> boxes, float sx, float sy, float sz, float ex, float ey, float ez) {
        for (var box : boxes) {
            if (BeastRebake.doesSegmentHitBox(sx, sy, sz, ex, ey, ez, box, BIAS)) {
                return true;
            }
        }
        return false;
    }

    static float[] computeHemisphereRays(float[] n, int seed, int rays) {
        float tx, ty, tz;
        if (Math.abs(n[2]) < .9f) {
            tx = -n[1];
            ty = n[0];
            tz = 0;
        } else {
            tx = 0;
            ty = -n[2];
            tz = n[1];
        }
        var tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        tx /= tl;
        ty /= tl;
        tz /= tl;
        var bx = n[1] * tz - n[2] * ty;
        var by = n[2] * tx - n[0] * tz;
        var bz = n[0] * ty - n[1] * tx;
        var angle = (mix(seed) & 0xffff) / 65536f * (float) (Math.PI * 2);
        var cos = (float) Math.cos(angle);
        var sin = (float) Math.sin(angle);
        var set = rays == CALIBRATION_RAYS ? CALIBRATION_HEMISPHERE : HEMISPHERE;
        var count = set.length / 3;
        var result = new float[count * 3];
        for (var ray = 0; ray < count; ray++) {
            var u = set[ray * 3];
            var v = set[ray * 3 + 1];
            var w = set[ray * 3 + 2];
            var ru = u * cos - v * sin;
            var rv = u * sin + v * cos;
            result[ray * 3] = tx * ru + bx * rv + n[0] * w;
            result[ray * 3 + 1] = ty * ru + by * rv + n[1] * w;
            result[ray * 3 + 2] = tz * ru + bz * rv + n[2] * w;
        }
        return result;
    }

    static void computeHemisphereTerms(
            Query query,
            BeastBakeModel model,
            float[] sun,
            float[] n,
            float sx,
            float sy,
            float sz,
            int seed,
            int rays,
            Terms out) {
        var directions = computeHemisphereRays(n, seed, rays);
        var count = directions.length / 3;
        var weight = 1f / count;
        var distance = model.occlusionDistance();
        for (var ray = 0; ray < count; ray++) {
            var dx = directions[ray * 3];
            var dy = directions[ray * 3 + 1];
            var dz = directions[ray * 3 + 2];
            add(
                    out,
                    hemisphereState(
                            query,
                            sx,
                            sy,
                            sz,
                            sx + dx * distance,
                            sy + dy * distance,
                            sz + dz * distance,
                            dx,
                            dy,
                            dz,
                            sun),
                    weight);
        }
    }

    static void computeLocalTerms(
            Query query,
            LocalLights lights,
            int[] candidates,
            BeastBakeModel model,
            float[] p,
            float[] n,
            float sx,
            float sy,
            float sz,
            Terms out,
            @Nullable LightVisitor visitor) {
        for (var index : candidates) {
            var light = lights.lights().get(index);
            var dx = light.x() - p[0];
            var dy = light.y() - p[1];
            var dz = light.z() - p[2];
            var squared = dx * dx + dy * dy + dz * dz;
            var radius = light.radius();
            if (!(squared < radius * radius) || !(squared > 1e-6f)) {
                continue;
            }
            var distance = (float) Math.sqrt(squared);
            var cosine = (n[0] * dx + n[1] * dy + n[2] * dz) / distance;
            if (!(cosine > 0)) {
                continue;
            }
            cosine *= computeConeFactor(light, dx, dy, dz, distance);
            if (!(cosine > 0)) {
                continue;
            }
            var ratio = distance / radius;
            var visible = !(visitor != null || model.localShadows())
                    || query.clear(sx, sy, sz, light.x(), light.y(), light.z(), true);
            if (visitor != null) {
                visitor.visit(index, ratio, cosine, visible);
            }
            if (!visible && model.localShadows()) {
                continue;
            }
            var factor = BeastBakeModel.falloff(model.falloff(), ratio) * cosine;
            out.local[0] += light.red() * factor;
            out.local[1] += light.green() * factor;
            out.local[2] += light.blue() * factor;
        }
    }

    static float computeConeFactor(Light light, float dx, float dy, float dz, float distance) {
        if (light.effect() != 8 && light.effect() != 12) {
            return 1f;
        }
        var sine = 1f - light.cone() / 256f;
        if (!(sine < 1f)) {
            return 0f;
        }
        var reciprocal = 1f / (1f - sine);
        var directionDot = -(dx * light.directionX() + dy * light.directionY() + dz * light.directionZ()) / distance;
        if (!(directionDot > sine)) {
            return 0f;
        }
        var value = directionDot * reciprocal - reciprocal * sine;
        return value * value;
    }

    static boolean isSunVisible(Query query, float x, float y, float z, float[] sun, boolean cutouts) {
        return query.clear(
                x + sun[0] * BIAS,
                y + sun[1] * BIAS,
                z + sun[2] * BIAS,
                x + sun[0] * ActorLightingTrace.SUN_RAY_LENGTH,
                y + sun[1] * ActorLightingTrace.SUN_RAY_LENGTH,
                z + sun[2] * ActorLightingTrace.SUN_RAY_LENGTH,
                cutouts);
    }

    public static void computeSlot0Linear(BeastBakeModel model, Terms terms, float[] rgb) {
        for (var c = 0; c < 3; c++) {
            rgb[c] = model.sun(c) * terms.direct
                    + model.maskedSun(c) * terms.maskedDirect
                    + model.sky(c) * terms.sky
                    + model.litBounce(c) * terms.litBounce
                    + model.darkBounce(c) * terms.darkBounce;
        }
    }

    public static void computeSlot1Linear(BeastBakeModel model, Terms terms, float[] rgb) {
        for (var c = 0; c < 3; c++) {
            rgb[c] = model.local(c) * terms.local[c];
        }
    }

    public static void computeSlot0(BeastBakeModel model, Terms terms, float[] rgb) {
        for (var c = 0; c < 3; c++) {
            rgb[c] = Math.min(
                    model.limit(0, c),
                    encode(
                            model,
                            model.sun(c) * terms.direct
                                    + model.maskedSun(c) * terms.maskedDirect
                                    + model.sky(c) * terms.sky
                                    + model.litBounce(c) * terms.litBounce
                                    + model.darkBounce(c) * terms.darkBounce));
        }
    }

    public static void computeSlot1(BeastBakeModel model, Terms terms, float[] rgb) {
        for (var c = 0; c < 3; c++) {
            rgb[c] = Math.min(model.limit(1, c), encode(model, model.local(c) * terms.local[c]));
        }
    }

    public static float encode(BeastBakeModel model, float linear) {
        if (!(linear > 0)) {
            return 0f;
        }
        if (model.gamma() == 1f) {
            return Math.min(255f, linear);
        }
        return 255f * (float) Math.pow(Math.min(1f, linear / 255f), 1.0 / model.gamma());
    }

    public static float decode(BeastBakeModel model, float stored) {
        if (model.gamma() == 1f) {
            return stored;
        }
        return 255f * (float) Math.pow(Math.max(0f, stored) / 255f, model.gamma());
    }

    public static float[][] bake(
            ActorLightingTrace trace,
            LocalLights lights,
            BeastBakeModel model,
            Mesh mesh,
            float[] localToWorld,
            int width,
            int height,
            BooleanSupplier cancelled) {
        var texels = BeastTexels.of(mesh, width, height);
        var query = trace.query();
        var slot0 = new float[width * height * 3];
        var slot1 = new float[width * height * 3];
        var box = worldBox(mesh, localToWorld);
        var candidates = lights.findReaching(box);
        var terms = new Terms();
        var p = new float[3];
        var n = new float[3];
        var rgb = new float[3];
        var covered = texels.getCoverage();
        for (var i = 0; i < texels.getCount(); i++) {
            if ((i & 255) == 0
                    && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
                throw new CancellationException("Beast bake cancelled");
            }
            var texel = texels.getTexel(i);
            if (!texels.computeWorldPoint(localToWorld, i, p, n)) {
                covered[texel] = false;
                continue;
            }
            computeTerms(query, lights, candidates, model, p, n, seed(p), terms, null);
            computeSlot0(model, terms, rgb);
            System.arraycopy(rgb, 0, slot0, texel * 3, 3);
            computeSlot1(model, terms, rgb);
            System.arraycopy(rgb, 0, slot1, texel * 3, 3);
        }
        BeastTexels.dilate(slot0, covered, width, height, 4);
        BeastTexels.dilate(slot1, covered, width, height, 4);
        return new float[][] {slot0, slot1};
    }

    static float[] worldBox(Mesh mesh, float[] m) {
        var box = new float[] {
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE
        };
        var positions = mesh.positions();
        for (var i = 0; i < positions.length; i += 3) {
            for (var axis = 0; axis < 3; axis++) {
                var value = positions[i] * m[axis]
                        + positions[i + 1] * m[4 + axis]
                        + positions[i + 2] * m[8 + axis]
                        + m[12 + axis];
                box[axis] = Math.min(box[axis], value);
                box[axis + 3] = Math.max(box[axis + 3], value);
            }
        }
        return box;
    }

    static int seed(float[] p) {
        return mix(Float.floatToIntBits(p[0]) * 73856093
                ^ Float.floatToIntBits(p[1]) * 19349663
                ^ Float.floatToIntBits(p[2]) * 83492791);
    }

    private static int sunState(Query query, float x, float y, float z, float[] sun) {
        if (isSunVisible(query, x, y, z, sun, true)) {
            return 2;
        }
        return isSunVisible(query, x, y, z, sun, false) ? 1 : 0;
    }

    private static int hemisphereState(
            Query query,
            float sx,
            float sy,
            float sz,
            float ex,
            float ey,
            float ez,
            float dx,
            float dy,
            float dz,
            float[] sun) {
        if (!query.findFirstHit(sx, sy, sz, ex, ey, ez, true)) {
            return 0;
        }
        var hx = query.hitLocationX - dx * BIAS;
        var hy = query.hitLocationY - dy * BIAS;
        var hz = query.hitLocationZ - dz * BIAS;
        return isSunVisible(query, hx, hy, hz, sun, true) ? 1 : 2;
    }

    private static void add(Terms terms, int state, float weight) {
        switch (state) {
            case 0 -> terms.sky += weight;
            case 1 -> terms.litBounce += weight;
            default -> terms.darkBounce += weight;
        }
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        value ^= value >>> 16;
        return value;
    }

    private static float[] createLocalHemisphere(int count) {
        var result = new float[count * 3];
        for (var i = 0; i < count; i++) {
            var u = (i + .5f) / count;
            var v = Integer.reverse(i) >>> 1;
            var phi = (float) (v / (double) (1L << 31) * Math.PI * 2);
            var r = (float) Math.sqrt(u);
            result[i * 3] = r * (float) Math.cos(phi);
            result[i * 3 + 1] = r * (float) Math.sin(phi);
            result[i * 3 + 2] = (float) Math.sqrt(Math.max(0f, 1f - u));
        }
        return result;
    }
}
