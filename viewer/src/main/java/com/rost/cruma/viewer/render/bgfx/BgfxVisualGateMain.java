package com.rost.cruma.viewer.render.bgfx;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

public final class BgfxVisualGateMain {
    private static final String MANIFEST_SCHEMA = "cruma.viewer.bgfx-visual-suite/v1";
    private static final int SSIM_WINDOW = 8;
    private static final double SSIM_C1 = Math.pow(0.01 * 255.0, 2.0);
    private static final double SSIM_C2 = Math.pow(0.03 * 255.0, 2.0);

    private BgfxVisualGateMain() {}

    public static void main(String[] args) throws IOException {
        var options = Options.parse(args);
        var requests = options.manifest == null
                ? List.of(new ComparisonRequest(
                        options.id, options.profile, options.reference, options.candidate, options.mask))
                : readManifest(options.manifest);
        var evaluation = evaluate(options.gates, requests);
        var json = JSON.toJSONString(evaluation.report);
        if (options.output != null) {
            var parent = options.output.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    options.output,
                    json + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
        System.out.println(json);
        if (!evaluation.passed) {
            throw new IllegalStateException("viewer visual gates failed");
        }
    }

    static Evaluation evaluate(Path gatesPath, List<ComparisonRequest> requests) {
        var rows = new ArrayList<Map<String, Object>>();
        var errors = new ArrayList<String>();
        JSONObject visual;
        try {
            visual = JSON.parseObject(Files.readString(gatesPath, StandardCharsets.UTF_8))
                    .getJSONObject("visual");
        } catch (RuntimeException | IOException failure) {
            visual = null;
            errors.add(gatesPath + ": " + failure.getMessage());
        }
        if (visual == null || visual.isEmpty()) {
            errors.add("visual threshold map is missing or empty");
        }
        if (requests == null || requests.isEmpty()) {
            errors.add("visual comparison list is empty");
        } else if (visual != null) {
            for (var request : requests) {
                try {
                    rows.add(compare(request, visual));
                } catch (RuntimeException | IOException failure) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", request == null ? null : request.id);
                    row.put("passed", false);
                    row.put(
                            "reason",
                            failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                    rows.add(row);
                }
            }
        }
        var passed = errors.isEmpty()
                && !rows.isEmpty()
                && rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "cruma.viewer.bgfx-visual-report/v1");
        report.put("generated_at", Instant.now().toString());
        report.put("gate_file", gatesPath.toAbsolutePath().normalize().toString());
        report.put("passed", passed);
        report.put("errors", errors);
        report.put("comparisons", rows);
        return new Evaluation(passed, report);
    }

    private static Map<String, Object> compare(ComparisonRequest request, JSONObject visual) throws IOException {
        if (request == null
                || request.id == null
                || request.id.isBlank()
                || request.profile == null
                || request.profile.isBlank()
                || request.reference == null
                || request.candidate == null) {
            throw new IllegalArgumentException("incomplete visual comparison identity");
        }
        var threshold = visual.getJSONObject(request.profile);
        if (threshold == null || threshold.isEmpty()) {
            throw new IllegalArgumentException("unknown visual threshold profile " + request.profile);
        }
        var minimumSsim = optionalFinite(threshold, "minimum_ssim", Double.NEGATIVE_INFINITY);
        var maximumP99 = optionalFinite(threshold, "maximum_p99_rgb_error_255", Double.POSITIVE_INFINITY);
        var maximumDrift = optionalFinite(threshold, "maximum_mean_channel_drift_255", Double.POSITIVE_INFINITY);
        var maximumAlpha = optionalFinite(threshold, "maximum_p99_alpha_error_255", Double.POSITIVE_INFINITY);
        if (!Double.isFinite(minimumSsim)
                && !Double.isFinite(maximumP99)
                && !Double.isFinite(maximumDrift)
                && !Double.isFinite(maximumAlpha)) {
            throw new IllegalArgumentException(
                    "visual threshold profile has no supported finite limits: " + request.profile);
        }

        var reference = readLossless(request.reference, "reference");
        var candidate = readLossless(request.candidate, "candidate");
        if (reference.getWidth() != candidate.getWidth() || reference.getHeight() != candidate.getHeight()) {
            throw new IllegalArgumentException("image extent mismatch: reference="
                    + reference.getWidth()
                    + 'x'
                    + reference.getHeight()
                    + ", candidate="
                    + candidate.getWidth()
                    + 'x'
                    + candidate.getHeight());
        }
        var mask = request.mask == null ? null : readLossless(request.mask, "mask");
        if (mask != null && (mask.getWidth() != reference.getWidth() || mask.getHeight() != reference.getHeight())) {
            throw new IllegalArgumentException("mask extent does not match compared images");
        }
        var maskUsesAlpha = mask != null && hasNonOpaqueAlpha(mask);
        var metrics = computeMetrics(reference, candidate, mask, maskUsesAlpha);
        var passed = metrics.ssim >= minimumSsim
                && metrics.p99RgbError <= maximumP99
                && metrics.maximumMeanDrift() <= maximumDrift
                && metrics.p99AlphaError <= maximumAlpha;

        var row = new LinkedHashMap<String, Object>();
        row.put("id", request.id);
        row.put("profile", request.profile);
        row.put("reference", request.reference.toAbsolutePath().normalize().toString());
        row.put("candidate", request.candidate.toAbsolutePath().normalize().toString());
        row.put(
                "mask",
                request.mask == null
                        ? null
                        : request.mask.toAbsolutePath().normalize().toString());
        row.put("width", reference.getWidth());
        row.put("height", reference.getHeight());
        row.put("compared_pixels", metrics.pixels);
        row.put("ssim", metrics.ssim);
        row.put("p99_rgb_error_255", metrics.p99RgbError);
        row.put("p99_alpha_error_255", metrics.p99AlphaError);
        row.put(
                "mean_channel_drift_255",
                Map.of(
                        "r", metrics.meanDriftR,
                        "g", metrics.meanDriftG,
                        "b", metrics.meanDriftB));
        var limits = new LinkedHashMap<String, Object>();
        if (Double.isFinite(minimumSsim)) {
            limits.put("minimum_ssim", minimumSsim);
        }
        if (Double.isFinite(maximumP99)) {
            limits.put("maximum_p99_rgb_error_255", maximumP99);
        }
        if (Double.isFinite(maximumDrift)) {
            limits.put("maximum_mean_channel_drift_255", maximumDrift);
        }
        if (Double.isFinite(maximumAlpha)) {
            limits.put("maximum_p99_alpha_error_255", maximumAlpha);
        }
        row.put("limits", limits);
        row.put("passed", passed);
        return row;
    }

    private static Metrics computeMetrics(
            BufferedImage reference, BufferedImage candidate, BufferedImage mask, boolean maskUsesAlpha) {
        var width = reference.getWidth();
        var height = reference.getHeight();
        var rgbErrors = new long[256];
        var alphaErrors = new long[256];
        long pixels = 0L;
        long driftR = 0L;
        long driftG = 0L;
        long driftB = 0L;
        double weightedSsim = 0.0;
        long ssimPixels = 0L;

        for (var blockY = 0; blockY < height; blockY += SSIM_WINDOW) {
            for (var blockX = 0; blockX < width; blockX += SSIM_WINDOW) {
                var statistics = new WindowStatistics();
                var maximumY = Math.min(height, blockY + SSIM_WINDOW);
                var maximumX = Math.min(width, blockX + SSIM_WINDOW);
                for (var y = blockY; y < maximumY; y++) {
                    for (var x = blockX; x < maximumX; x++) {
                        if (!isIncluded(mask, maskUsesAlpha, x, y)) {
                            continue;
                        }
                        var left = reference.getRGB(x, y);
                        var right = candidate.getRGB(x, y);
                        var lr = left >>> 16 & 0xff;
                        var lg = left >>> 8 & 0xff;
                        var lb = left & 0xff;
                        var la = left >>> 24 & 0xff;
                        var rr = right >>> 16 & 0xff;
                        var rg = right >>> 8 & 0xff;
                        var rb = right & 0xff;
                        var ra = right >>> 24 & 0xff;
                        rgbErrors[Math.abs(rr - lr)]++;
                        rgbErrors[Math.abs(rg - lg)]++;
                        rgbErrors[Math.abs(rb - lb)]++;
                        alphaErrors[Math.abs(ra - la)]++;
                        driftR += rr - lr;
                        driftG += rg - lg;
                        driftB += rb - lb;
                        pixels++;
                        statistics.add(lr, lg, lb, rr, rg, rb);
                    }
                }
                if (statistics.count > 0L) {
                    weightedSsim += statistics.getSsim() * statistics.count;
                    ssimPixels += statistics.count;
                }
            }
        }
        if (pixels == 0L || ssimPixels == 0L) {
            throw new IllegalArgumentException("comparison mask selects no pixels");
        }
        return new Metrics(
                pixels,
                weightedSsim / ssimPixels,
                percentile99(rgbErrors, Math.multiplyExact(pixels, 3L)),
                percentile99(alphaErrors, pixels),
                Math.abs(driftR / (double) pixels),
                Math.abs(driftG / (double) pixels),
                Math.abs(driftB / (double) pixels));
    }

    private static int percentile99(long[] histogram, long samples) {
        var target = Math.max(1L, (long) Math.ceil(samples * 0.99));
        long cumulative = 0L;
        for (var value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= target) {
                return value;
            }
        }
        throw new IllegalStateException("error histogram is internally inconsistent");
    }

    private static boolean isIncluded(BufferedImage mask, boolean usesAlpha, int x, int y) {
        if (mask == null) {
            return true;
        }
        var pixel = mask.getRGB(x, y);
        if (usesAlpha) {
            return (pixel >>> 24 & 0xff) != 0;
        }
        return (pixel & 0x00ffffff) != 0;
    }

    private static boolean hasNonOpaqueAlpha(BufferedImage image) {
        for (var y = 0; y < image.getHeight(); y++) {
            for (var x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24 & 0xff) != 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage readLossless(Path path, String role) throws IOException {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(role + " image does not exist: " + path);
        }
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png")) {
            throw new IllegalArgumentException(role + " image must be a lossless PNG: " + path);
        }
        var image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IllegalArgumentException("unsupported or corrupt " + role + " image: " + path);
        }
        return image;
    }

    private static double optionalFinite(JSONObject source, String key, double fallback) {
        if (!source.containsKey(key)) {
            return fallback;
        }
        var value = source.getDoubleValue(key);
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(key + " must be finite and non-negative");
        }
        return value;
    }

    private static List<ComparisonRequest> readManifest(Path manifest) throws IOException {
        var root = JSON.parseObject(Files.readString(manifest, StandardCharsets.UTF_8));
        if (!MANIFEST_SCHEMA.equals(root.getString("schema"))) {
            throw new IllegalArgumentException("unexpected visual manifest schema");
        }
        var rows = root.getJSONArray("comparisons");
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("visual manifest comparison list is empty");
        }
        var base = manifest.toAbsolutePath().normalize().getParent();
        var result = new ArrayList<ComparisonRequest>(rows.size());
        for (var value : rows) {
            var row = (JSONObject) value;
            result.add(new ComparisonRequest(
                    row.getString("id"),
                    row.getString("profile"),
                    resolve(base, row.getString("reference")),
                    resolve(base, row.getString("candidate")),
                    row.getString("mask") == null ? null : resolve(base, row.getString("mask"))));
        }
        return List.copyOf(result);
    }

    private static Path resolve(Path base, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : base.resolve(path).normalize();
    }

    record Evaluation(boolean passed, Map<String, Object> report) {}

    record ComparisonRequest(String id, String profile, Path reference, Path candidate, Path mask) {}

    private record Metrics(
            long pixels,
            double ssim,
            int p99RgbError,
            int p99AlphaError,
            double meanDriftR,
            double meanDriftG,
            double meanDriftB) {
        double maximumMeanDrift() {
            return Math.max(meanDriftR, Math.max(meanDriftG, meanDriftB));
        }
    }

    private static final class WindowStatistics {
        private final double[] left = new double[3];
        private final double[] right = new double[3];
        private final double[] leftSquared = new double[3];
        private final double[] rightSquared = new double[3];
        private final double[] product = new double[3];
        private long count;

        void add(int lr, int lg, int lb, int rr, int rg, int rb) {
            add(0, lr, rr);
            add(1, lg, rg);
            add(2, lb, rb);
            count++;
        }

        double getSsim() {
            var result = 0.0;
            for (var channel = 0; channel < 3; channel++) {
                var meanX = left[channel] / count;
                var meanY = right[channel] / count;
                var varianceX = Math.max(0.0, leftSquared[channel] / count - meanX * meanX);
                var varianceY = Math.max(0.0, rightSquared[channel] / count - meanY * meanY);
                var covariance = product[channel] / count - meanX * meanY;
                var numerator = (2.0 * meanX * meanY + SSIM_C1) * (2.0 * covariance + SSIM_C2);
                var denominator = (meanX * meanX + meanY * meanY + SSIM_C1) * (varianceX + varianceY + SSIM_C2);
                result += Math.max(-1.0, Math.min(1.0, numerator / denominator));
            }
            return result / 3.0;
        }

        private void add(int channel, double x, double y) {
            left[channel] += x;
            right[channel] += y;
            leftSquared[channel] += x * x;
            rightSquared[channel] += y * y;
            product[channel] += x * y;
        }
    }

    private record Options(
            Path gates,
            Path manifest,
            Path reference,
            Path candidate,
            Path mask,
            String profile,
            String id,
            Path output) {
        private static Options parse(String[] args) {
            Path gates = Path.of("config/viewer-bgfx-gates.json");
            Path manifest = null;
            Path reference = null;
            Path candidate = null;
            Path mask = null;
            Path output = null;
            String profile = null;
            String id = "visual-comparison";
            for (var index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--gates" -> gates = Path.of(requireValue(args, ++index, "--gates"));
                    case "--manifest" -> manifest = Path.of(requireValue(args, ++index, "--manifest"));
                    case "--reference" -> reference = Path.of(requireValue(args, ++index, "--reference"));
                    case "--candidate" -> candidate = Path.of(requireValue(args, ++index, "--candidate"));
                    case "--mask" -> mask = Path.of(requireValue(args, ++index, "--mask"));
                    case "--profile" -> profile = requireValue(args, ++index, "--profile");
                    case "--id" -> id = requireValue(args, ++index, "--id");
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    default -> throw new IllegalArgumentException("unknown visual gate argument " + args[index]);
                }
            }
            if (manifest != null && (reference != null || candidate != null || profile != null)) {
                throw new IllegalArgumentException("--manifest cannot be combined with single-image options");
            }
            if (manifest == null && (reference == null || candidate == null || profile == null)) {
                throw new IllegalArgumentException("use --manifest or provide --reference, --candidate and --profile");
            }
            return new Options(gates, manifest, reference, candidate, mask, profile, id, output);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
