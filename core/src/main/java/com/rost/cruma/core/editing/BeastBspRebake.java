package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingChanges.BeastTexture;
import com.rost.cruma.core.editing.BeastBaker.LocalLights;
import com.rost.cruma.core.editing.BeastBaker.Terms;
import com.rost.cruma.core.editing.BeastBsp.Record;
import com.rost.cruma.core.editing.BeastCalibration.Sample;
import com.rost.cruma.core.editing.BspLightmapTail.Texels;
import com.rost.cruma.core.editing.BspLightmapTail.VariantKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class BeastBspRebake {
    static final int CALIBRATION_TEXELS = 12000;

    private BeastBspRebake() {}

    record Images(int width, int height, float[] slot0, float[] slot1, List<byte[]> mips0, List<byte[]> mips1) {}

    record Calibrated(
            List<Record> records,
            Map<Integer, Images> images,
            LocalLights lights,
            BeastCalibration.Result fit,
            boolean flat0,
            boolean flat1) {
        BeastBakeModel model() {
            return fit.model();
        }
    }

    record Result(Map<VariantKey, Texels> texels, int records, int surfaces, int texelsChanged, int blocks) {
        static final Result NONE = new Result(Map.of(), 0, 0, 0, 0);
    }

    static Map<Integer, Images> decodeImages(BspLightmapTail tail, List<Record> records) {
        var result = new HashMap<Integer, Images>();
        for (var record : records) {
            result.computeIfAbsent(record.texture(), texture -> {
                var variants = tail.textures.get(texture).variants();
                var first = variants.get(0);
                var second = variants.get(1);
                return new Images(
                        first.width(),
                        first.height(),
                        BeastTextureCodec.decode(first.full(), first.width(), first.height()),
                        BeastTextureCodec.decode(second.full(), second.width(), second.height()),
                        List.of(first.full(), first.half()),
                        List.of(second.full(), second.half()));
            });
        }
        return result;
    }

    static Calibrated calibrate(
            BspLightmapTail tail,
            List<Record> records,
            ActorLightingTrace trace,
            LocalLights lights,
            BeastBakeModel start,
            float center,
            int threads,
            BooleanSupplier cancelled) {
        var images = decodeImages(tail, records);
        var samples = collectSamples(records, images, lights, center, CALIBRATION_TEXELS);
        var fit = BeastCalibration.fit(trace, lights, samples, start, threads, cancelled);
        return new Calibrated(
                List.copyOf(records),
                Map.copyOf(images),
                lights,
                fit,
                isFlat(records, images, true),
                isFlat(records, images, false));
    }

    static double[] measureHoldout(
            List<Record> records,
            Map<Integer, Images> images,
            ActorLightingTrace trace,
            LocalLights lights,
            BeastBakeModel start,
            int threads,
            BooleanSupplier cancelled) {
        var even = new ArrayList<Record>();
        var odd = new ArrayList<Record>();
        for (var i = 0; i < records.size(); i++) {
            (i % 2 == 0 ? even : odd).add(records.get(i));
        }
        if (even.isEmpty() || odd.isEmpty()) {
            return new double[] {0, Double.MAX_VALUE};
        }
        var fit = BeastCalibration.fit(
                trace,
                lights,
                collectSamples(even, images, lights, BeastBsp.CENTER, CALIBRATION_TEXELS),
                start,
                threads,
                cancelled);
        var models = new BeastBakeModel[] {start, fit.calibrated() ? fit.model() : start};
        var measured = collectSamples(odd, images, lights, BeastBsp.CENTER, CALIBRATION_TEXELS / 3);
        var query = trace.query();
        var terms = new Terms();
        var rgb = new float[3];
        var result = new double[2];
        long channels = 0;
        for (var sample : measured) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Beast BSP calibration cancelled");
            }
            for (var m = 0; m < 2; m++) {
                BeastBaker.computeTerms(
                        query,
                        lights,
                        sample.lights(),
                        models[m],
                        sample.position(),
                        sample.normal(),
                        BeastBaker.seed(sample.position()),
                        BeastBaker.CALIBRATION_RAYS,
                        terms,
                        null);
                BeastBaker.computeSlot0(models[m], terms, rgb);
                for (var c = 0; c < 3; c++) {
                    result[m] += Math.abs(Math.clamp(Math.round(rgb[c]), 0, 255) - sample.slot0()[c]);
                }
                BeastBaker.computeSlot1(models[m], terms, rgb);
                for (var c = 0; c < 3; c++) {
                    result[m] += Math.abs(Math.clamp(Math.round(rgb[c]), 0, 255) - sample.slot1()[c]);
                }
            }
            channels += 6;
        }
        if (channels == 0) {
            return new double[] {0, Double.MAX_VALUE};
        }
        result[0] /= channels;
        result[1] /= channels;
        return result;
    }

    static List<Sample> collectSamples(
            List<Record> records, Map<Integer, Images> images, LocalLights lights, float center, int wanted) {
        var total = 0;
        for (var record : records) {
            total += record.coveredCount();
        }
        var stride = Math.max(1, total / Math.max(1, wanted));
        var samples = new ArrayList<Sample>();
        var p = new float[3];
        var n = new float[3];
        var counter = 0;
        for (var record : records) {
            var image = images.get(record.texture());
            for (var y = 0; y < record.height(); y++) {
                for (var x = 0; x < record.width(); x++) {
                    if (!record.world(x, y, center, p, n)) {
                        continue;
                    }
                    if (counter++ % stride != 0) {
                        continue;
                    }
                    var t = ((record.offsetY() + y) * image.width() + record.offsetX() + x) * 3;
                    samples.add(new Sample(
                            p.clone(),
                            n.clone(),
                            readRgb(image.slot0(), t),
                            readRgb(image.slot1(), t),
                            lights.findReaching(new float[] {p[0], p[1], p[2], p[0], p[1], p[2]}),
                            record.index()));
                }
            }
        }
        return samples;
    }

    static Result rebake(
            Calibrated calibrated,
            ActorLightingTrace before,
            ActorLightingTrace after,
            List<float[]> boxes,
            BooleanSupplier cancelled) {
        if (boxes.isEmpty() || calibrated.flat0() && calibrated.flat1()) {
            return Result.NONE;
        }
        var model = calibrated.model();
        var lights = calibrated.lights();
        var oldQuery = before.query();
        var newQuery = after.query();
        var slot0 = new HashMap<Integer, float[]>();
        var slot1 = new HashMap<Integer, float[]>();
        var changed0 = new HashMap<Integer, boolean[]>();
        var changed1 = new HashMap<Integer, boolean[]>();
        for (var entry : calibrated.images().entrySet()) {
            var images = entry.getValue();
            slot0.put(entry.getKey(), images.slot0().clone());
            slot1.put(entry.getKey(), images.slot1().clone());
            changed0.put(entry.getKey(), new boolean[images.width() * images.height()]);
            changed1.put(entry.getKey(), new boolean[images.width() * images.height()]);
        }
        var p = new float[3];
        var n = new float[3];
        var b = new float[3];
        var zero = new float[3];
        var delta = new Terms();
        var records = 0;
        var surfaces = new HashSet<Integer>();
        var texels = 0;
        for (var record : calibrated.records()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Beast BSP rebake cancelled");
            }
            var images = calibrated.images().get(record.texture());
            var image0 = slot0.get(record.texture());
            var image1 = slot1.get(record.texture());
            var mask0 = changed0.get(record.texture());
            var mask1 = changed1.get(record.texture());
            var local = recordMaxima(record, images);
            var touched = false;
            for (var y = 0; y < record.height(); y++) {
                for (var x = 0; x < record.width(); x++) {
                    if (!record.world(x, y, BeastBsp.CENTER, p, n)) {
                        continue;
                    }
                    var candidates = lights.findReaching(new float[] {p[0], p[1], p[2], p[0], p[1], p[2]});
                    var near = BeastRebake.isNearAnyBox(p, boxes, model.occlusionDistance() + 2 * BeastBaker.BIAS);
                    if (!near
                            && !BeastRebake.doesSunRayMeetAnyBox(p, model.sunDirection(), boxes)
                            && !BeastRebake.isTowardsLights(p, candidates, lights, boxes)) {
                        continue;
                    }
                    var bounce = near && BeastRebake.isNearAnyBox(p, boxes, BeastRebake.BOUNCE_REACH);
                    var seed = BeastBaker.seed(p);
                    BeastBaker.computeDeltaTerms(
                            oldQuery, newQuery, lights, candidates, model, p, n, seed, boxes, near, bounce, delta);
                    var texel = (record.offsetY() + y) * images.width() + record.offsetX() + x;
                    var moved = false;
                    if (!calibrated.flat0()) {
                        BeastBaker.computeSlot0Linear(model, delta, b);
                        if (apply(model, 0, image0, texel, zero, b, local)) {
                            mask0[texel] = true;
                            moved = true;
                        }
                    }
                    if (!calibrated.flat1()) {
                        BeastBaker.computeSlot1Linear(model, delta, b);
                        if (apply(model, 1, image1, texel, zero, b, local)) {
                            mask1[texel] = true;
                            moved = true;
                        }
                    }
                    if (moved) {
                        texels++;
                        touched = true;
                    }
                }
            }
            if (touched) {
                records++;
                surfaces.add(record.surface());
            }
        }
        var result = new LinkedHashMap<VariantKey, Texels>();
        var blocks = 0;
        for (var entry : calibrated.images().entrySet()) {
            var texture = entry.getKey();
            var images = entry.getValue();
            for (var slot = 0; slot < 2; slot++) {
                var mask = slot == 0 ? changed0.get(texture) : changed1.get(texture);
                if (!hasAnyTrue(mask)) {
                    continue;
                }
                var saved = slot == 0 ? images.mips0() : images.mips1();
                var image = slot == 0 ? slot0.get(texture) : slot1.get(texture);
                BeastTexture written = BeastRebuild.changedTexture(saved, image, mask, images.width(), images.height());
                blocks += changedBlocks(saved.get(0), written.mips().get(0));
                result.put(
                        new VariantKey(texture, slot),
                        new Texels(written.mips().get(0), written.mips().get(1)));
            }
        }
        return new Result(result, records, surfaces.size(), texels, blocks);
    }

    private static float[] recordMaxima(Record record, Images images) {
        var result = new float[6];
        for (var y = 0; y < record.height(); y++) {
            for (var x = 0; x < record.width(); x++) {
                if (!record.covered()[y * record.width() + x]) {
                    continue;
                }
                var t = ((record.offsetY() + y) * images.width() + record.offsetX() + x) * 3;
                for (var c = 0; c < 3; c++) {
                    result[c] = Math.max(result[c], images.slot0()[t + c]);
                    result[3 + c] = Math.max(result[3 + c], images.slot1()[t + c]);
                }
            }
        }
        return result;
    }

    private static boolean apply(
            BeastBakeModel model, int slot, float[] image, int texel, float[] before, float[] after, float[] local) {
        var moved = false;
        for (var c = 0; c < 3; c++) {
            var stored = image[texel * 3 + c];
            var next = BeastBaker.encode(model, BeastBaker.decode(model, stored) + after[c] - before[c]);
            next = Math.min(next, Math.max(stored, Math.min(model.limit(slot, c), local[slot * 3 + c])));
            image[texel * 3 + c] = next;
            moved |= Math.abs(next - stored) >= 1f;
        }
        return moved;
    }

    private static boolean isFlat(List<Record> records, Map<Integer, Images> images, boolean first) {
        for (var c = 0; c < 3; c++) {
            var low = Float.MAX_VALUE;
            var high = -Float.MAX_VALUE;
            for (var record : records) {
                var entry = images.get(record.texture());
                var image = first ? entry.slot0() : entry.slot1();
                for (var y = 0; y < record.height(); y++) {
                    for (var x = 0; x < record.width(); x++) {
                        if (record.covered()[y * record.width() + x]) {
                            var value = image[((record.offsetY() + y) * entry.width() + record.offsetX() + x) * 3 + c];
                            low = Math.min(low, value);
                            high = Math.max(high, value);
                        }
                    }
                }
            }
            if (high - low > BeastTerrainRebake.FLAT_SPREAD) {
                return false;
            }
        }
        return true;
    }

    private static int changedBlocks(byte[] before, byte[] after) {
        var count = 0;
        for (var block = 0; block < before.length / 8; block++) {
            if (!Arrays.equals(before, block * 8, block * 8 + 8, after, block * 8, block * 8 + 8)) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasAnyTrue(boolean[] mask) {
        for (var value : mask) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    private static int[] readRgb(float[] image, int t) {
        return new int[] {Math.round(image[t]), Math.round(image[t + 1]), Math.round(image[t + 2])};
    }
}
