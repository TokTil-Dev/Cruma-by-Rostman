package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastBaker.Terms;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class BeastCalibration {
    static final int ROBUST_ROUNDS = 6;

    static final float MAX_COEFFICIENT = 1024f;

    private BeastCalibration() {}

    public record Sample(float[] position, float[] normal, int[] slot0, int[] slot1, int[] lights, int group) {}

    public record Agreement(long channels, double meanAbsolute, double within4, double within8, double within16) {
        public static final Agreement NONE = new Agreement(0, 0, 0, 0, 0);

        public String describe() {
            return String.format(
                    Locale.ROOT,
                    "mean abs %.2f, within 4/8/16: %.1f%% / %.1f%% / %.1f%%",
                    meanAbsolute,
                    within4 * 100,
                    within8 * 100,
                    within16 * 100);
        }
    }

    public record Result(
            BeastBakeModel model,
            BeastBakeModel start,
            Agreement slot0Before,
            Agreement slot1Before,
            Agreement slot0After,
            Agreement slot1After,
            int samples,
            int groups,
            boolean calibrated) {}

    public static final class Tally {
        long channels;
        double absolute;
        long within4;
        long within8;
        long within16;

        public void add(float predicted, float saved) {
            var difference = Math.abs(predicted - saved);
            channels++;
            absolute += difference;
            if (difference <= 4) {
                within4++;
            }
            if (difference <= 8) {
                within8++;
            }
            if (difference <= 16) {
                within16++;
            }
        }

        public void add(Tally other) {
            channels += other.channels;
            absolute += other.absolute;
            within4 += other.within4;
            within8 += other.within8;
            within16 += other.within16;
        }

        public Agreement getAgreement() {
            if (channels == 0) {
                return Agreement.NONE;
            }
            return new Agreement(
                    channels,
                    absolute / channels,
                    within4 / (double) channels,
                    within8 / (double) channels,
                    within16 / (double) channels);
        }
    }

    public static Result fit(
            ActorLightingTrace trace,
            LocalLights lights,
            List<Sample> samples,
            BeastBakeModel start,
            int threads,
            BooleanSupplier cancelled) {
        if (samples.isEmpty()) {
            return new Result(
                    start, start, Agreement.NONE, Agreement.NONE, Agreement.NONE, Agreement.NONE, 0, 0, false);
        }
        var groups = (int) samples.stream().mapToInt(Sample::group).distinct().count();
        var sun = shadingSun(samples);
        var search = subsample(samples, 6000);
        sun = refineSun(trace, search, sun, 5f, 2, threads, cancelled);
        sun = refineSun(trace, search, sun, 1.25f, 2, threads, cancelled);
        var shaped = start.withSunDirection(sun);
        var computed = compute(trace, lights, samples, shaped, threads, cancelled);
        var limit = findBrightest(samples, sample -> true);
        var fitted = fitModel(computed, shaped, sample -> true).withLimit(limit);
        var held = fitModel(computed, shaped, sample -> (sample.sample.group & 1) == 0)
                .withLimit(findBrightest(samples, sample -> (sample.group() & 1) == 0));
        var after0 = new Tally();
        var after1 = new Tally();
        var before0 = new Tally();
        var before1 = new Tally();
        var startComputed = Arrays.equals(start.sunDirection(), sun)
                ? computed
                : compute(trace, lights, subsetOdd(samples), start, threads, cancelled);
        for (var entry : computed) {
            if ((entry.sample.group & 1) == 0) {
                continue;
            }
            measure(held, entry, after0, after1);
        }
        for (var entry : startComputed) {
            if ((entry.sample.group & 1) == 0) {
                continue;
            }
            measure(start, entry, before0, before1);
        }
        if (after0.channels > 0
                && before0.getAgreement().meanAbsolute()
                                + before1.getAgreement().meanAbsolute()
                        < after0.getAgreement().meanAbsolute()
                                + after1.getAgreement().meanAbsolute()) {
            return new Result(
                    start.withLimit(limit),
                    start,
                    before0.getAgreement(),
                    before1.getAgreement(),
                    before0.getAgreement(),
                    before1.getAgreement(),
                    samples.size(),
                    groups,
                    false);
        }
        return new Result(
                fitted,
                start,
                before0.getAgreement(),
                before1.getAgreement(),
                after0.getAgreement(),
                after1.getAgreement(),
                samples.size(),
                groups,
                true);
    }

    static float[] findBrightest(List<Sample> samples, Predicate<Sample> filter) {
        var result = new float[6];
        for (var sample : samples) {
            if (!filter.test(sample)) {
                continue;
            }
            for (var c = 0; c < 3; c++) {
                result[c] = Math.max(result[c], sample.slot0()[c]);
                result[3 + c] = Math.max(result[3 + c], sample.slot1()[c]);
            }
        }
        return result;
    }

    static void measure(BeastBakeModel model, Computed entry, Tally slot0, Tally slot1) {
        var rgb = new float[3];
        var terms = entry.toTerms(model);
        BeastBaker.computeSlot0(model, terms, rgb);
        for (var c = 0; c < 3; c++) {
            slot0.add(rgb[c], entry.sample.slot0[c]);
        }
        BeastBaker.computeSlot1(model, terms, rgb);
        for (var c = 0; c < 3; c++) {
            slot1.add(rgb[c], entry.sample.slot1[c]);
        }
    }

    record Visit(int light, float ratio, float cosine, boolean visible) {}

    static final class Computed {
        final Sample sample;
        final float direct;
        final float maskedDirect;
        final float sky;
        final float litBounce;
        final float darkBounce;
        final List<Visit> visits;

        final LocalLights lights;

        Computed(Sample sample, Terms terms, List<Visit> visits, LocalLights lights) {
            this.sample = sample;
            this.lights = lights;
            this.direct = terms.direct;
            this.maskedDirect = terms.maskedDirect;
            this.sky = terms.sky;
            this.litBounce = terms.litBounce;
            this.darkBounce = terms.darkBounce;
            this.visits = visits;
        }

        Terms toTerms(BeastBakeModel model) {
            var terms = new Terms();
            terms.direct = direct;
            terms.maskedDirect = maskedDirect;
            terms.sky = sky;
            terms.litBounce = litBounce;
            terms.darkBounce = darkBounce;
            for (var visit : visits) {
                if (model.localShadows() && !visit.visible) {
                    continue;
                }
                var light = lights.lights().get(visit.light);
                var factor = BeastBakeModel.falloff(model.falloff(), visit.ratio) * visit.cosine;
                terms.local[0] += light.red() * factor;
                terms.local[1] += light.green() * factor;
                terms.local[2] += light.blue() * factor;
            }
            return terms;
        }
    }

    static List<Computed> compute(
            ActorLightingTrace trace,
            LocalLights lights,
            List<Sample> samples,
            BeastBakeModel model,
            int threads,
            BooleanSupplier cancelled) {
        var result = new Computed[samples.size()];
        runParallel(samples.size(), threads, cancelled, (from, to) -> {
            var query = trace.query();
            var terms = new Terms();
            for (var i = from; i < to; i++) {
                var sample = samples.get(i);
                var visits = new ArrayList<Visit>();
                BeastBaker.computeTerms(
                        query,
                        lights,
                        sample.lights,
                        model,
                        sample.position,
                        sample.normal,
                        BeastBaker.seed(sample.position),
                        BeastBaker.CALIBRATION_RAYS,
                        terms,
                        (light, ratio, cosine, visible) -> visits.add(new Visit(light, ratio, cosine, visible)));
                result[i] = new Computed(sample, terms, visits, lights);
            }
        });
        return List.of(result);
    }

    static BeastBakeModel fitModel(List<Computed> computed, BeastBakeModel shaped, Predicate<Computed> filter) {
        var training = new ArrayList<Computed>();
        for (var entry : computed) {
            if (filter.test(entry)) {
                training.add(entry);
            }
        }
        if (training.isEmpty()) {
            return shaped;
        }
        BeastBakeModel best = null;
        var bestError = Double.MAX_VALUE;
        for (var gamma : new float[] {1f, 2.2f}) {
            var probe = new BeastBakeModel(
                    shaped.sunDirection(),
                    shaped.sun(),
                    shaped.maskedSun(),
                    shaped.sky(),
                    shaped.litBounce(),
                    shaped.darkBounce(),
                    shaped.local(),
                    shaped.falloff(),
                    shaped.localShadows(),
                    shaped.occlusionDistance(),
                    gamma);
            var slot0 = fitSlot0(training, probe);
            var error0 = slot0Error(training, slot0);
            float[] bestLocal = null;
            var bestFalloff = 0;
            var bestShadows = true;
            var bestError1 = Double.MAX_VALUE;
            for (var falloff = 0; falloff < BeastBakeModel.FALLOFFS; falloff++) {
                for (var shadows : new boolean[] {true, false}) {
                    var candidate = new BeastBakeModel(
                            slot0.sunDirection(),
                            slot0.sun(),
                            slot0.maskedSun(),
                            slot0.sky(),
                            slot0.litBounce(),
                            slot0.darkBounce(),
                            new float[] {1f, 1f, 1f},
                            falloff,
                            shadows,
                            slot0.occlusionDistance(),
                            gamma);
                    var local = fitSlot1(training, candidate);
                    var model = new BeastBakeModel(
                            slot0.sunDirection(),
                            slot0.sun(),
                            slot0.maskedSun(),
                            slot0.sky(),
                            slot0.litBounce(),
                            slot0.darkBounce(),
                            local,
                            falloff,
                            shadows,
                            slot0.occlusionDistance(),
                            gamma);
                    var error = slot1Error(training, model);
                    if (error < bestError1) {
                        bestError1 = error;
                        bestLocal = local;
                        bestFalloff = falloff;
                        bestShadows = shadows;
                    }
                }
            }
            var model = new BeastBakeModel(
                    slot0.sunDirection(),
                    slot0.sun(),
                    slot0.maskedSun(),
                    slot0.sky(),
                    slot0.litBounce(),
                    slot0.darkBounce(),
                    bestLocal,
                    bestFalloff,
                    bestShadows,
                    slot0.occlusionDistance(),
                    gamma);
            var error = error0 + bestError1;
            if (error < bestError) {
                bestError = error;
                best = model;
            }
        }
        return best;
    }

    static double[] nonNegative(double[][] ata, double[] atb) {
        var size = atb.length;
        var active = new boolean[size];
        Arrays.fill(active, true);
        while (true) {
            var solution = solve(ata, atb, active);
            var worst = -1;
            for (var i = 0; i < size; i++) {
                if (active[i] && solution[i] < 0 && (worst < 0 || solution[i] < solution[worst])) {
                    worst = i;
                }
            }
            if (worst < 0) {
                return solution;
            }
            active[worst] = false;
        }
    }

    static float[] shadingSun(List<Sample> samples) {
        float[] best = null;
        var bestCorrelation = -2.0;
        for (var elevation = 5; elevation <= 85; elevation += 5) {
            for (var azimuth = 0; azimuth < 360; azimuth += 5) {
                var direction = direction(azimuth, elevation);
                double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0;
                for (var sample : samples) {
                    var n = sample.normal;
                    var d = Math.max(0, n[0] * direction[0] + n[1] * direction[1] + n[2] * direction[2]);
                    var l = (sample.slot0[0] + sample.slot0[1] + sample.slot0[2]) / 3.0;
                    sx += d;
                    sy += l;
                    sxx += d * d;
                    syy += l * l;
                    sxy += d * l;
                }
                var count = samples.size();
                var denominator = Math.sqrt((count * sxx - sx * sx) * (count * syy - sy * sy));
                var correlation = denominator > 0 ? (count * sxy - sx * sy) / denominator : -1;
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation;
                    best = direction;
                }
            }
        }
        return best;
    }

    static float[] refineSun(
            ActorLightingTrace trace,
            List<Sample> samples,
            float[] start,
            float step,
            int steps,
            int threads,
            BooleanSupplier cancelled) {
        var azimuth = (float) Math.toDegrees(Math.atan2(start[1], start[0]));
        var elevation = (float) Math.toDegrees(Math.asin(Math.clamp(start[2], -1f, 1f)));
        float[] best = start;
        var bestError = Double.MAX_VALUE;
        for (var i = -steps; i <= steps; i++) {
            for (var j = -steps; j <= steps; j++) {
                var e = Math.clamp(elevation + j * step, 1f, 89f);
                var direction = direction(azimuth + i * step, e);
                var direct = new float[samples.size()];
                runParallel(samples.size(), threads, cancelled, (from, to) -> {
                    var query = trace.query();
                    for (var k = from; k < to; k++) {
                        var sample = samples.get(k);
                        var n = sample.normal;
                        var p = sample.position;
                        var facing = n[0] * direction[0] + n[1] * direction[1] + n[2] * direction[2];
                        if (facing > 0
                                && BeastBaker.isSunVisible(
                                        query,
                                        p[0] + n[0] * BeastBaker.BIAS,
                                        p[1] + n[1] * BeastBaker.BIAS,
                                        p[2] + n[2] * BeastBaker.BIAS,
                                        direction,
                                        true)) {
                            direct[k] = facing;
                        }
                    }
                });
                double sx = 0, sy = 0, sxx = 0, sxy = 0;
                var count = samples.size();
                for (var k = 0; k < count; k++) {
                    var s = samples.get(k).slot0;
                    var l = (s[0] + s[1] + s[2]) / 3.0;
                    sx += direct[k];
                    sy += l;
                    sxx += direct[k] * direct[k];
                    sxy += direct[k] * l;
                }
                var denominator = count * sxx - sx * sx;
                var slope = denominator > 0 ? (count * sxy - sx * sy) / denominator : 0;
                var intercept = (sy - slope * sx) / count;
                var error = 0.0;
                for (var k = 0; k < count; k++) {
                    var s = samples.get(k).slot0;
                    var l = (s[0] + s[1] + s[2]) / 3.0;
                    error += Math.abs(slope * direct[k] + intercept - l);
                }
                if (error < bestError) {
                    bestError = error;
                    best = direction;
                }
            }
        }
        return best;
    }

    public static float[] direction(float azimuth, float elevation) {
        var a = Math.toRadians(azimuth);
        var e = Math.toRadians(elevation);
        return new float[] {
            (float) (Math.cos(e) * Math.cos(a)), (float) (Math.cos(e) * Math.sin(a)), (float) Math.sin(e)
        };
    }

    static void runParallel(int count, int threads, BooleanSupplier cancelled, Range range) {
        if (count == 0) {
            return;
        }
        var parts = Math.max(1, Math.min(threads, count / 64 + 1));
        if (parts == 1) {
            checkCancelled(cancelled);
            range.run(0, count);
            return;
        }
        var executor = Executors.newFixedThreadPool(
                parts,
                Thread.ofPlatform().daemon().name("beast-calibration-", 0).factory());
        try {
            var futures = new ArrayList<Future<?>>();
            var chunk = 256;
            var next = new AtomicInteger();
            for (var part = 0; part < parts; part++) {
                futures.add(executor.submit(() -> {
                    while (true) {
                        checkCancelled(cancelled);
                        var from = next.getAndAdd(chunk);
                        if (from >= count) {
                            return;
                        }
                        range.run(from, Math.min(count, from + chunk));
                    }
                }));
            }
            for (var future : futures) {
                future.get();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Beast calibration cancelled");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failure.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static BeastBakeModel fitSlot0(List<Computed> training, BeastBakeModel model) {
        var coefficients = new float[5][3];
        var count = training.size();
        for (var c = 0; c < 3; c++) {
            var x = new double[count][];
            var y = new double[count];
            for (var i = 0; i < count; i++) {
                var entry = training.get(i);
                x[i] = new double[] {entry.direct, entry.maskedDirect, entry.sky, entry.litBounce, entry.darkBounce};
                y[i] = BeastBaker.decode(model, entry.sample.slot0[c]);
            }
            var weights = new double[count];
            Arrays.fill(weights, 1.0);
            var solution = new double[5];
            for (var round = 0; round < ROBUST_ROUNDS; round++) {
                var ata = new double[5][5];
                var atb = new double[5];
                for (var i = 0; i < count; i++) {
                    for (var a = 0; a < 5; a++) {
                        atb[a] += weights[i] * x[i][a] * y[i];
                        for (var b = 0; b < 5; b++) {
                            ata[a][b] += weights[i] * x[i][a] * x[i][b];
                        }
                    }
                }
                solution = nonNegative(ata, atb);
                for (var a = 0; a < 5; a++) {
                    solution[a] = Math.min(solution[a], MAX_COEFFICIENT);
                }
                for (var i = 0; i < count; i++) {
                    var predicted = 0.0;
                    for (var a = 0; a < 5; a++) {
                        predicted += solution[a] * x[i][a];
                    }
                    weights[i] = 1.0 / Math.max(1.0, Math.abs(predicted - y[i]));
                }
            }
            for (var a = 0; a < 5; a++) {
                coefficients[a][c] = (float) solution[a];
            }
        }
        return new BeastBakeModel(
                model.sunDirection(),
                coefficients[0],
                coefficients[1],
                coefficients[2],
                coefficients[3],
                coefficients[4],
                model.local(),
                model.falloff(),
                model.localShadows(),
                model.occlusionDistance(),
                model.gamma());
    }

    private static float[] fitSlot1(List<Computed> training, BeastBakeModel unit) {
        var count = training.size();
        var x = new double[3][count];
        var y = new double[3][count];
        for (var i = 0; i < count; i++) {
            var entry = training.get(i);
            var terms = entry.toTerms(unit);
            for (var c = 0; c < 3; c++) {
                x[c][i] = terms.local[c];
                y[c][i] = BeastBaker.decode(unit, entry.sample.slot1[c]);
            }
        }
        var result = new float[3];
        for (var c = 0; c < 3; c++) {
            var scale = 0.0;
            var weights = new double[count];
            Arrays.fill(weights, 1.0);
            for (var round = 0; round < ROBUST_ROUNDS; round++) {
                double xy = 0, xx = 0;
                for (var i = 0; i < count; i++) {
                    xy += weights[i] * x[c][i] * y[c][i];
                    xx += weights[i] * x[c][i] * x[c][i];
                }
                scale = xx > 0 ? Math.clamp(xy / xx, 0, MAX_COEFFICIENT) : 0;
                for (var i = 0; i < count; i++) {
                    weights[i] = 1.0 / Math.max(1.0, Math.abs(scale * x[c][i] - y[c][i]));
                }
            }
            result[c] = (float) scale;
        }
        return result;
    }

    private static double slot0Error(List<Computed> training, BeastBakeModel model) {
        var tally = new Tally();
        var rgb = new float[3];
        for (var entry : training) {
            BeastBaker.computeSlot0(model, entry.toTerms(model), rgb);
            for (var c = 0; c < 3; c++) {
                tally.add(rgb[c], entry.sample.slot0[c]);
            }
        }
        return tally.getAgreement().meanAbsolute();
    }

    private static double slot1Error(List<Computed> training, BeastBakeModel model) {
        var tally = new Tally();
        var rgb = new float[3];
        for (var entry : training) {
            BeastBaker.computeSlot1(model, entry.toTerms(model), rgb);
            for (var c = 0; c < 3; c++) {
                tally.add(rgb[c], entry.sample.slot1[c]);
            }
        }
        return tally.getAgreement().meanAbsolute();
    }

    private static double[] solve(double[][] ata, double[] atb, boolean[] active) {
        var size = atb.length;
        var index = new int[size];
        var count = 0;
        for (var i = 0; i < size; i++) {
            if (active[i]) {
                index[count++] = i;
            }
        }
        var a = new double[count][count + 1];
        for (var i = 0; i < count; i++) {
            for (var j = 0; j < count; j++) {
                a[i][j] = ata[index[i]][index[j]];
            }
            a[i][i] += 1e-6 * (1 + a[i][i]);
            a[i][count] = atb[index[i]];
        }
        for (var column = 0; column < count; column++) {
            var pivot = column;
            for (var row = column + 1; row < count; row++) {
                if (Math.abs(a[row][column]) > Math.abs(a[pivot][column])) {
                    pivot = row;
                }
            }
            var swap = a[column];
            a[column] = a[pivot];
            a[pivot] = swap;
            if (Math.abs(a[column][column]) < 1e-12) {
                continue;
            }
            for (var row = 0; row < count; row++) {
                if (row == column) {
                    continue;
                }
                var factor = a[row][column] / a[column][column];
                for (var k = column; k <= count; k++) {
                    a[row][k] -= factor * a[column][k];
                }
            }
        }
        var result = new double[size];
        for (var i = 0; i < count; i++) {
            result[index[i]] = Math.abs(a[i][i]) < 1e-12 ? 0 : a[i][count] / a[i][i];
        }
        return result;
    }

    private static List<Sample> subsample(List<Sample> samples, int limit) {
        if (samples.size() <= limit) {
            return samples;
        }
        var result = new ArrayList<Sample>(limit);
        var step = samples.size() / (double) limit;
        for (var i = 0; i < limit; i++) {
            result.add(samples.get((int) (i * step)));
        }
        return result;
    }

    private static List<Sample> subsetOdd(List<Sample> samples) {
        var result = new ArrayList<Sample>();
        for (var sample : samples) {
            if ((sample.group & 1) != 0) {
                result.add(sample);
            }
        }
        return result;
    }

    @FunctionalInterface
    interface Range {
        void run(int from, int to);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Beast calibration cancelled");
        }
    }
}
