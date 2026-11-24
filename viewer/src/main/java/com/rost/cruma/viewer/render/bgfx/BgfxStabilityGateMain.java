package com.rost.cruma.viewer.render.bgfx;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BgfxStabilityGateMain {
    private static final String REPORT_SCHEMA = "cruma.viewer.bgfx-stability/v1";
    private static final List<String> REQUIRED_MECHANICAL_CHECKS = List.of(
            "resource-lifetime",
            "async-readback",
            "fsr1-easu-rcas",
            "resize-provider-switch",
            "scene-material-water-reflection",
            "gpu-visibility-indirect",
            "worker-payload-streaming",
            "diagnostic-material",
            "imgui",
            "ordered-readback-ring");

    private BgfxStabilityGateMain() {}

    public static void main(String[] args) throws IOException {
        var options = Options.parse(args);
        var evaluation = evaluate(options.gates, options.reports, options.requireMatrix, options.requireExternal);
        var json = JSON.toJSONString(evaluation.report);
        if (options.output != null) {
            var parent = options.output.getParent();
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
            throw new IllegalStateException("viewer bgfx stability gates failed");
        }
    }

    static Evaluation evaluate(Path gatePath, List<Path> reportPaths, boolean requireMatrix, boolean requireExternal)
            throws IOException {
        var spec = JSON.parseObject(Files.readString(gatePath, StandardCharsets.UTF_8));
        var thresholds = spec.getJSONObject("stability");
        if (thresholds == null) {
            throw new IllegalArgumentException("gate file has no stability contract");
        }
        var requiredRenderers = new HashSet<String>();
        for (var value : spec.getJSONArray("required_renderers")) {
            requiredRenderers.add(canonicalRenderer(value.toString()));
        }
        var rows = new ArrayList<Map<String, Object>>();
        var observedRenderers = new HashSet<String>();
        var duplicateRenderers = new HashSet<String>();
        for (var path : reportPaths) {
            var row = evaluateReport(path, thresholds, requireExternal);
            rows.add(row);
            var renderer = (String) row.get("renderer");
            if (renderer != null && !observedRenderers.add(renderer)) {
                duplicateRenderers.add(renderer);
            }
        }
        var missingRenderers = new HashSet<>(requiredRenderers);
        missingRenderers.removeAll(observedRenderers);
        var passed = !rows.isEmpty()
                && rows.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")))
                && duplicateRenderers.isEmpty()
                && (!requireMatrix || missingRenderers.isEmpty());
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "cruma.viewer.bgfx-stability-gate-report/v1");
        report.put("generated_at", Instant.now().toString());
        report.put("passed", passed);
        report.put("require_matrix", requireMatrix);
        report.put("require_external_soak", requireExternal);
        report.put("reports", rows);
        report.put("observed_renderers", observedRenderers.stream().sorted().toList());
        report.put("missing_renderers", missingRenderers.stream().sorted().toList());
        report.put("duplicate_renderers", duplicateRenderers.stream().sorted().toList());
        if (!requireExternal) {
            report.put(
                    "external_evidence_pending",
                    Map.of(
                            "soak_hours_per_platform", thresholds.getDoubleValue("soak_hours_per_platform"),
                            "normal_use_soak_days_before_gl_removal",
                                    thresholds.getDoubleValue("normal_use_soak_days_before_gl_removal"),
                            "memory_return_tolerance_percent",
                                    thresholds.getDoubleValue("memory_return_tolerance_percent"),
                            "fsr1_peak_target_memory_over_native_percent",
                                    thresholds.getDoubleValue("fsr1_peak_target_memory_over_native_percent")));
        }
        return new Evaluation(passed, report);
    }

    private static Map<String, Object> evaluateReport(Path path, JSONObject thresholds, boolean requireExternal) {
        var row = new LinkedHashMap<String, Object>();
        row.put("path", path.toAbsolutePath().normalize().toString());
        var problems = new ArrayList<String>();
        try {
            var report = JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
            var renderer = canonicalRenderer(report.getString("renderer"));
            row.put("renderer", renderer);
            if (!REPORT_SCHEMA.equals(report.getString("schema"))) {
                problems.add("unexpected report schema " + report.getString("schema"));
            }
            if (!report.getBooleanValue("passed")) {
                problems.add("smoke report is not passing");
            }
            checkMinimum(
                    report,
                    "create_run_shutdown_cycles",
                    thresholds.getLongValue("create_run_shutdown_cycles"),
                    problems);
            checkMinimum(
                    report, "resize_provider_cycles", thresholds.getLongValue("framebuffer_resize_cycles"), problems);
            checkMinimum(report, "ordered_readback_items", thresholds.getLongValue("readback_items"), problems);
            checkMinimum(report, "max_views", 240L, problems);
            checkMinimum(report, "max_samplers", 16L, problems);
            checkMinimum(report, "max_vertex_streams", 3L, problems);
            if (report.getLongValue("maximum_post_verification_live_handles", -1L) != 0L) {
                problems.add("post-verification live handle count is not zero");
            }
            if (renderer == null || renderer.isBlank()) {
                problems.add("renderer is missing");
            }
            var checks = new HashSet<String>();
            var recordedChecks = report.getJSONArray("checks");
            if (recordedChecks != null) {
                for (var check : recordedChecks) {
                    checks.add(String.valueOf(check));
                }
            }
            for (var required : REQUIRED_MECHANICAL_CHECKS) {
                if (!checks.contains(required)) {
                    problems.add("mechanical smoke check is missing: " + required);
                }
            }
            if (requireExternal) {
                checkExternal(report, thresholds, problems);
            }
        } catch (RuntimeException | IOException failure) {
            problems.add(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        row.put("passed", problems.isEmpty());
        row.put("problems", problems);
        return row;
    }

    private static void checkExternal(JSONObject report, JSONObject thresholds, List<String> problems) {
        if (report.getDoubleValue("soak_hours") < thresholds.getDoubleValue("soak_hours_per_platform")) {
            problems.add("two-hour platform soak is missing or too short");
        }
        if (doubleOrInfinity(report, "memory_return_percent")
                > thresholds.getDoubleValue("memory_return_tolerance_percent")) {
            problems.add("memory return tolerance was exceeded or not recorded");
        }
        if (doubleOrInfinity(report, "fsr1_peak_target_memory_over_native_percent")
                > thresholds.getDoubleValue("fsr1_peak_target_memory_over_native_percent")) {
            problems.add("FSR1 peak target-memory limit was exceeded or not recorded");
        }
        if (report.getLongValue("device_losses", -1L) != 0L || report.getLongValue("fatal_callbacks", -1L) != 0L) {
            problems.add("device-loss/fatal-callback counters are missing or nonzero");
        }
        if (!report.getBooleanValue("queues_drained") || report.getBooleanValue("monotonic_memory_growth")) {
            problems.add("queue-drain/memory-growth soak result is missing or failed");
        }
        if (report.getString("signed_by") == null
                || report.getString("signed_by").isBlank()) {
            problems.add("release-owner signature is missing");
        }
    }

    private static void checkMinimum(JSONObject report, String field, long expected, List<String> problems) {
        var actual = report.getLongValue(field, -1L);
        if (actual < expected) {
            problems.add(field + " requires at least " + expected + ", got " + actual);
        }
    }

    private static double doubleOrInfinity(JSONObject report, String field) {
        var value = report.get(field);
        return value instanceof Number number && Double.isFinite(number.doubleValue())
                ? number.doubleValue()
                : Double.POSITIVE_INFINITY;
    }

    private static String canonicalRenderer(String raw) {
        if (raw == null) {
            return null;
        }
        var compact = raw.trim().replace(" ", "").replace("-", "").toLowerCase(Locale.ROOT);
        return switch (compact) {
            case "direct3d11", "d3d11" -> "Direct3D11";
            case "direct3d12", "d3d12" -> "Direct3D12";
            case "metal" -> "Metal";
            case "vulkan" -> "Vulkan";
            case "opengl", "gl" -> "OpenGL";
            default -> raw.trim();
        };
    }

    record Evaluation(boolean passed, Map<String, Object> report) {}

    private record Options(
            Path gates, List<Path> reports, Path output, boolean requireMatrix, boolean requireExternal) {
        private static Options parse(String[] args) {
            Path gates = null;
            Path output = null;
            var reports = new ArrayList<Path>();
            var requireMatrix = false;
            var requireExternal = false;
            for (var index = 0; index < args.length; index++) {
                var arg = args[index];
                switch (arg) {
                    case "--gates" ->
                        gates = Path.of(args[++index]).toAbsolutePath().normalize();
                    case "--report" ->
                        reports.add(Path.of(args[++index]).toAbsolutePath().normalize());
                    case "--output" ->
                        output = Path.of(args[++index]).toAbsolutePath().normalize();
                    case "--require-matrix" -> requireMatrix = true;
                    case "--require-external" -> requireExternal = true;
                    default -> throw new IllegalArgumentException("unknown argument " + arg);
                }
            }
            if (gates == null || reports.isEmpty()) {
                throw new IllegalArgumentException("--gates and at least one --report are required");
            }
            return new Options(gates, List.copyOf(reports), output, requireMatrix, requireExternal);
        }
    }
}
