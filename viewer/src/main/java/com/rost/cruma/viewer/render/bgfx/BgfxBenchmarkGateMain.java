package com.rost.cruma.viewer.render.bgfx;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BgfxBenchmarkGateMain {
    private static final String GATE_SCHEMA = "cruma.viewer.bgfx-gates/v1";
    private static final Set<String> OPERATIONS =
            Set.of("minimum_reduction_percent", "maximum_regression_percent", "maximum_absolute");

    private BgfxBenchmarkGateMain() {}

    public static void main(String[] args) throws IOException {
        var options = Options.parse(args);
        var evaluation = evaluate(options.gates, options.artifacts, options.validateOnly);
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
            throw new IllegalStateException(
                    "viewer bgfx benchmark gates failed; see the machine-readable report above");
        }
    }

    static Evaluation evaluate(Path gatePath, List<Path> artifactPaths, boolean validateOnly) throws IOException {
        var spec = JSON.parseObject(Files.readString(gatePath, StandardCharsets.UTF_8));
        var problems = validateSpec(spec);
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", "cruma.viewer.bgfx-gate-report/v1");
        report.put("generated_at", Instant.now().toString());
        report.put("gate_file", gatePath.toAbsolutePath().normalize().toString());
        if (!problems.isEmpty()) {
            report.put("passed", false);
            report.put("spec_errors", problems);
            return new Evaluation(false, report);
        }
        if (validateOnly) {
            report.put("passed", true);
            report.put("mode", "validate-only");
            report.put("performance_gates", spec.getJSONArray("performance").size());
            return new Evaluation(true, report);
        }

        var contract = spec.getJSONObject("benchmark_contract");
        var checkpointContracts = spec.getJSONObject("checkpoint_contracts");
        var artifacts = new HashMap<ArtifactKey, Artifact>();
        var artifactErrors = new ArrayList<String>();
        for (var path : artifactPaths) {
            try {
                var artifact = readArtifact(path, contract, checkpointContracts);
                var previous = artifacts.putIfAbsent(artifact.key, artifact);
                if (previous != null) {
                    artifactErrors.add(
                            "duplicate artifact identity " + artifact.key + ": " + previous.path + " and " + path);
                }
            } catch (RuntimeException | IOException failure) {
                artifactErrors.add(path + ": " + failure.getMessage());
            }
        }

        var gateResults = new ArrayList<Map<String, Object>>();
        for (var value : spec.getJSONArray("performance")) {
            gateResults.add(evaluateGateSafely((JSONObject) value, artifacts));
        }
        var cross = spec.getJSONObject("cross_renderer");
        for (var rendererValue : spec.getJSONArray("required_renderers")) {
            var expanded = new JSONObject(cross);
            expanded.put("id", "cross_renderer_" + rendererValue.toString().toLowerCase());
            expanded.put("renderer", rendererValue.toString());
            gateResults.add(evaluateGateSafely(expanded, artifacts));
        }

        var passed = artifactErrors.isEmpty()
                && !artifactPaths.isEmpty()
                && gateResults.stream().allMatch(row -> Boolean.TRUE.equals(row.get("passed")));
        report.put("passed", passed);
        report.put("artifact_count", artifacts.size());
        report.put("artifact_errors", artifactErrors);
        report.put("gates", gateResults);
        report.put("p4", spec.getJSONObject("p4"));
        report.put("external_visual_contract", spec.getJSONObject("visual"));
        report.put("external_stability_contract", spec.getJSONObject("stability"));
        return new Evaluation(passed, report);
    }

    private static List<String> validateSpec(JSONObject spec) {
        var errors = new ArrayList<String>();
        if (!GATE_SCHEMA.equals(spec.getString("schema"))) {
            errors.add("unexpected gate schema " + spec.getString("schema"));
        }
        var contract = spec.getJSONObject("benchmark_contract");
        if (contract == null
                || contract.getIntValue("minimum_warmup_frames", -1) < 0
                || contract.getIntValue("minimum_measured_frames", 0) < 1
                || contract.getString("artifact_schema") == null) {
            errors.add("invalid benchmark_contract");
        }
        var ids = new HashSet<String>();
        var requiredRenderers = rendererSet(spec.getJSONArray("required_renderers"));
        var requiredScenarios = stringSet(spec.getJSONArray("required_scenarios"));
        if (requiredRenderers.isEmpty()) {
            errors.add("required_renderers is empty");
        }
        if (requiredScenarios.isEmpty()) {
            errors.add("required_scenarios is empty");
        }
        var performance = spec.getJSONArray("performance");
        if (performance == null || performance.isEmpty()) {
            errors.add("performance gate list is empty");
            return errors;
        }
        for (var value : performance) {
            var gate = (JSONObject) value;
            var id = gate.getString("id");
            if (id == null || id.isBlank() || !ids.add(id)) {
                errors.add("missing or duplicate performance gate id " + id);
            }
            validateGate(gate, errors, id);
            var scenario = gate.getString("scenario");
            if (scenario != null && !requiredScenarios.contains(scenario)) {
                errors.add(id + ": scenario is absent from required_scenarios: " + scenario);
            }
            var renderer = canonicalRenderer(gate.getString("renderer"));
            if (renderer != null && !requiredRenderers.contains(renderer)) {
                errors.add(id + ": renderer is absent from required_renderers: " + renderer);
            }
        }
        var checkpointContracts = spec.getJSONObject("checkpoint_contracts");
        if (checkpointContracts == null || checkpointContracts.isEmpty()) {
            errors.add("checkpoint_contracts is empty");
        } else {
            var referenced = new HashSet<String>();
            for (var value : performance) {
                var gate = (JSONObject) value;
                referenced.add(gate.getString("candidate"));
                if (gate.getString("baseline") != null) {
                    referenced.add(gate.getString("baseline"));
                }
            }
            var cross = spec.getJSONObject("cross_renderer");
            if (cross != null) {
                referenced.add(cross.getString("candidate"));
                if (cross.getString("baseline") != null) {
                    referenced.add(cross.getString("baseline"));
                }
            }
            for (var checkpoint : referenced) {
                if (!checkpointContracts.containsKey(checkpoint)) {
                    errors.add("missing checkpoint contract " + checkpoint);
                } else if (!(checkpointContracts.get(checkpoint) instanceof JSONObject)) {
                    errors.add("checkpoint contract must be an object: " + checkpoint);
                }
            }
        }
        var cross = spec.getJSONObject("cross_renderer");
        if (cross == null) {
            errors.add("missing cross_renderer gate");
        } else {
            validateGate(cross, errors, "cross_renderer");
            var scenario = cross.getString("scenario");
            if (scenario != null && !requiredScenarios.contains(scenario)) {
                errors.add("cross_renderer: scenario is absent from required_scenarios: " + scenario);
            }
        }
        validateVisual(spec.getJSONObject("visual"), errors);
        var p4 = spec.getJSONObject("p4");
        if (p4 == null || p4.getBooleanValue("retained")) {
            errors.add("P4 (parallel encoders) must remain removed unless its threshold contract is updated");
        }
        return errors;
    }

    private static void validateVisual(JSONObject visual, List<String> errors) {
        if (visual == null || visual.isEmpty()) {
            errors.add("visual threshold map is empty");
            return;
        }
        for (var entry : visual.entrySet()) {
            if (!(entry.getValue() instanceof JSONObject profile) || profile.isEmpty()) {
                errors.add("visual threshold profile is empty: " + entry.getKey());
                continue;
            }
            var supported = 0;
            for (var key : List.of(
                    "minimum_ssim",
                    "maximum_p99_rgb_error_255",
                    "maximum_mean_channel_drift_255",
                    "maximum_p99_alpha_error_255")) {
                if (!profile.containsKey(key)) {
                    continue;
                }
                supported++;
                var value = profile.getDoubleValue(key);
                var upper = "minimum_ssim".equals(key) ? 1.0 : 255.0;
                if (!Double.isFinite(value) || value < 0.0 || value > upper) {
                    errors.add(entry.getKey() + ": invalid visual threshold " + key);
                }
            }
            if (supported == 0) {
                errors.add(entry.getKey() + ": no supported visual thresholds");
            }
        }
    }

    private static Set<String> stringSet(JSONArray values) {
        var result = new HashSet<String>();
        if (values == null) {
            return result;
        }
        for (var value : values) {
            if (value != null && !value.toString().isBlank()) {
                result.add(value.toString().trim());
            }
        }
        return result;
    }

    private static Set<String> rendererSet(JSONArray values) {
        var result = new HashSet<String>();
        if (values == null) {
            return result;
        }
        for (var value : values) {
            if (value != null && !value.toString().isBlank()) {
                result.add(canonicalRenderer(value.toString()));
            }
        }
        return result;
    }

    private static void validateGate(JSONObject gate, List<String> errors, String id) {
        var operation = gate.getString("operation");
        if (!OPERATIONS.contains(operation)) {
            errors.add(id + ": unsupported operation " + operation);
        }
        if (gate.getString("scenario") == null
                || gate.getString("candidate") == null
                || gate.getString("metric") == null
                || !Double.isFinite(gate.getDoubleValue("threshold"))
                || gate.getDoubleValue("threshold") < 0.0) {
            errors.add(id + ": incomplete gate identity/metric/threshold");
        }
        if (!"maximum_absolute".equals(operation) && gate.getString("baseline") == null) {
            errors.add(id + ": relative gate requires a baseline");
        }
        var requiredValue = gate.get("required_active_optimizations");
        if (requiredValue != null) {
            if (!(requiredValue instanceof JSONArray required)) {
                errors.add(id + ": required_active_optimizations must be an array");
            } else {
                var names = new HashSet<String>();
                for (var value : required) {
                    var name = value == null ? "" : value.toString().trim();
                    if (name.isEmpty() || !names.add(name)) {
                        errors.add(id + ": blank or duplicate required optimization " + name);
                    }
                }
            }
        }
    }

    private static Artifact readArtifact(Path path, JSONObject contract, JSONObject checkpointContracts)
            throws IOException {
        JSONObject header = null;
        JSONObject summary = null;
        long frameRows = 0L;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                var row = JSON.parseObject(line);
                switch (row.getString("type")) {
                    case "header" -> {
                        if (header != null) {
                            throw new IllegalArgumentException("multiple header rows");
                        }
                        header = row;
                    }
                    case "frame" -> frameRows++;
                    case "summary" -> summary = row;
                    default -> throw new IllegalArgumentException("unknown JSONL row type");
                }
            }
        }
        if (header == null || summary == null) {
            throw new IllegalArgumentException("artifact requires one header and a terminal summary");
        }
        var expectedSchema = contract.getString("artifact_schema");
        if (!expectedSchema.equals(header.getString("schema"))) {
            throw new IllegalArgumentException("artifact schema is " + header.getString("schema"));
        }
        if (header.getIntValue("warmup_frames") < contract.getIntValue("minimum_warmup_frames")
                || header.getIntValue("measured_frames") < contract.getIntValue("minimum_measured_frames")) {
            throw new IllegalArgumentException("artifact has too few warm-up or measured frames");
        }
        if (header.getIntValue("streaming_ready_tiles") != 0) {
            throw new IllegalArgumentException(
                    "partial-world streaming snapshots are diagnostic artifacts, not release benchmarks");
        }
        for (var field : contract.getJSONArray("required_header_fields")) {
            if (!header.containsKey(field.toString()) || header.get(field.toString()) == null) {
                throw new IllegalArgumentException("missing header field " + field);
            }
        }
        var forbidden = contract.getJSONArray("forbid_header_values");
        for (var field : List.of("checkpoint", "scenario", "commit")) {
            var value = header.getString(field);
            if (value == null || value.isBlank() || forbidden.contains(value)) {
                throw new IllegalArgumentException("unrecorded header field " + field);
            }
        }
        for (var field : contract.getJSONArray("required_summary_fields")) {
            if (!summary.containsKey(field.toString()) || summary.get(field.toString()) == null) {
                throw new IllegalArgumentException("missing summary field " + field);
            }
        }
        if (!summary.getBooleanValue("complete")
                || summary.getLongValue("samples") != header.getLongValue("measured_frames")
                || frameRows != summary.getLongValue("samples")) {
            throw new IllegalArgumentException("incomplete or internally inconsistent artifact");
        }
        validateRuntimeAttestation(header, summary);
        validateSceneCensus(summary);
        var capabilities = header.getJSONObject("capabilities");
        var renderer = capabilities == null ? null : capabilities.getString("renderer");
        if (renderer == null || renderer.isBlank()) {
            throw new IllegalArgumentException("capabilities.renderer is missing");
        }
        var scenarioContract = header.getJSONObject("scenario_contract");
        if (scenarioContract == null || !header.getString("scenario").equals(scenarioContract.getString("id"))) {
            throw new IllegalArgumentException("scenario_contract does not match header scenario");
        }
        validateCheckpointContract(header, checkpointContracts);
        return new Artifact(
                new ArtifactKey(header.getString("checkpoint"), header.getString("scenario"), renderer),
                path.toAbsolutePath().normalize(),
                header,
                summary);
    }

    private static void validateRuntimeAttestation(JSONObject header, JSONObject summary) {
        var samples = summary.getLongValue("samples");
        if (summary.containsKey("optimization_requested_frames") || summary.containsKey("optimization_active_frames")) {
            var requested = frameCounts(summary, "optimization_requested_frames", samples);
            var active = frameCounts(summary, "optimization_active_frames", samples);
            for (var entry : active.entrySet()) {
                if (entry.getValue() > requested.getOrDefault(entry.getKey(), 0L)) {
                    throw new IllegalArgumentException("optimization "
                            + entry.getKey()
                            + " is active for "
                            + entry.getValue()
                            + " frames but requested for only "
                            + requested.getOrDefault(entry.getKey(), 0L));
                }
            }
        }

        var graphics = header.getJSONObject("graphics");
        var upscaler = graphics == null ? null : graphics.getJSONObject("upscaler");
        var configuredAlgorithm = upscaler == null ? null : upscaler.getString("algorithm");
        if (configuredAlgorithm == null || configuredAlgorithm.isBlank()) {
            return;
        }
        var expectedProvider = switch (configuredAlgorithm.trim().toUpperCase(Locale.ROOT)) {
            case "NATIVE" -> "native";
            case "BILINEAR" -> "bilinear";
            case "FSR1" -> "fsr1";
            default ->
                throw new IllegalArgumentException("unsupported configured upscaler algorithm " + configuredAlgorithm);
        };
        var providers = frameCounts(summary, "active_upscaler_frames", samples);
        var total = providers.values().stream().mapToLong(Long::longValue).sum();
        if (total != samples || providers.getOrDefault(expectedProvider, 0L) != samples) {
            throw new IllegalArgumentException("configured upscaler "
                    + configuredAlgorithm
                    + " requires active provider "
                    + expectedProvider
                    + " for all "
                    + samples
                    + " frames, got "
                    + providers);
        }
    }

    private static void validateSceneCensus(JSONObject summary) {
        var hasStart = summary.containsKey("scene_census_start");
        var hasFinal = summary.containsKey("scene_census_final");
        if (!hasStart && !hasFinal) {
            return;
        }
        var start = summary.getJSONObject("scene_census_start");
        var end = summary.getJSONObject("scene_census_final");
        if (start == null || end == null) {
            throw new IllegalArgumentException("scene census requires start and final inventories");
        }
        var startFingerprint = start.getString("contentFingerprint");
        var finalFingerprint = end.getString("contentFingerprint");
        if (start.getIntValue("mapCount") < 1
                || start.getIntValue("primitiveCount") < 1
                || startFingerprint == null
                || !startFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("scene census is empty or has an invalid fingerprint");
        }
        if (!startFingerprint.equals(finalFingerprint)) {
            throw new IllegalArgumentException("decoded scene workload changed during measurement: " + startFingerprint
                    + " -> " + finalFingerprint);
        }
    }

    private static Map<String, Long> frameCounts(JSONObject summary, String field, long samples) {
        var value = summary.get(field);
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("missing or invalid summary field " + field);
        }
        var result = new LinkedHashMap<String, Long>();
        for (var entry : values.entrySet()) {
            var name = entry.getKey() == null ? "" : entry.getKey().toString().trim();
            if (name.isEmpty() || !(entry.getValue() instanceof Number number)) {
                throw new IllegalArgumentException("invalid frame counter in " + field);
            }
            var count = number.longValue();
            if (count < 0L || count > samples || Double.compare(number.doubleValue(), (double) count) != 0) {
                throw new IllegalArgumentException(
                        "invalid frame count " + entry.getValue() + " for " + field + '.' + name);
            }
            result.put(name, count);
        }
        return result;
    }

    private static void validateCheckpointContract(JSONObject header, JSONObject checkpointContracts) {
        if (checkpointContracts == null) {
            return;
        }
        var checkpoint = header.getString("checkpoint");
        var expected = checkpointContracts.getJSONObject(checkpoint);
        if (expected == null) {
            throw new IllegalArgumentException("no graphics contract for checkpoint " + checkpoint);
        }
        for (var entry : expected.entrySet()) {
            var actual = valueAt(header, entry.getKey());
            var wanted = entry.getValue();
            var equal = actual instanceof Number actualNumber && wanted instanceof Number wantedNumber
                    ? Double.compare(actualNumber.doubleValue(), wantedNumber.doubleValue()) == 0
                    : Objects.equals(actual, wanted);
            if (!equal) {
                throw new IllegalArgumentException(
                        "checkpoint " + checkpoint + " requires " + entry.getKey() + '=' + wanted + ", got " + actual);
            }
        }
    }

    private static Object valueAt(Map<String, ?> root, String path) {
        Object current = root;
        for (var part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Map<String, Object> evaluateGate(JSONObject gate, Map<ArtifactKey, Artifact> artifacts) {
        var result = new LinkedHashMap<String, Object>();
        var id = gate.getString("id");
        var renderer = gate.getString("renderer");
        var scenario = gate.getString("scenario");
        var candidateKey = new ArtifactKey(gate.getString("candidate"), scenario, renderer);
        var candidate = artifacts.get(candidateKey);
        result.put("id", id);
        result.put("metric", gate.getString("metric"));
        result.put("operation", gate.getString("operation"));
        result.put("threshold", gate.getDoubleValue("threshold"));
        if (candidate == null) {
            result.put("passed", false);
            result.put("reason", "missing candidate artifact " + candidateKey);
            return result;
        }
        var requiredOptimizations = gate.getJSONArray("required_active_optimizations");
        if (requiredOptimizations != null && !requiredOptimizations.isEmpty()) {
            var samples = candidate.summary.getLongValue("samples");
            var active = frameCounts(candidate.summary, "optimization_active_frames", samples);
            for (var value : requiredOptimizations) {
                var name = value.toString();
                var activeFrames = active.getOrDefault(name, 0L);
                if (activeFrames != samples) {
                    result.put("candidate", candidateKey.toString());
                    result.put("passed", false);
                    result.put(
                            "reason",
                            "required optimization "
                                    + name
                                    + " was active for "
                                    + activeFrames
                                    + '/'
                                    + samples
                                    + " measured frames");
                    return result;
                }
            }
            result.put("required_active_optimizations", List.copyOf(requiredOptimizations));
        }
        var candidateValue = readMetric(candidate.summary, gate.getString("metric"));
        result.put("candidate", candidateKey.toString());
        result.put("candidate_value", candidateValue);
        var operation = gate.getString("operation");
        if ("maximum_absolute".equals(operation)) {
            var passed = candidateValue <= gate.getDoubleValue("threshold");
            result.put("actual", candidateValue);
            result.put("passed", passed);
            return result;
        }
        var baselineRenderer = gate.getString("baseline_renderer");
        if (baselineRenderer == null || baselineRenderer.isBlank()) {
            baselineRenderer = renderer;
        }
        var baselineKey = new ArtifactKey(gate.getString("baseline"), scenario, baselineRenderer);
        var baseline = artifacts.get(baselineKey);
        if (baseline == null) {
            result.put("passed", false);
            result.put("reason", "missing baseline artifact " + baselineKey);
            return result;
        }
        var candidateContract = candidate.header.getJSONObject("scenario_contract");
        var baselineContract = baseline.header.getJSONObject("scenario_contract");
        if (!Objects.equals(candidateContract, baselineContract)) {
            result.put("passed", false);
            result.put("reason", "candidate and baseline scenario contracts differ");
            return result;
        }
        var candidateFingerprint = sceneFingerprint(candidate.summary);
        var baselineFingerprint = sceneFingerprint(baseline.summary);
        if (candidateFingerprint != null || baselineFingerprint != null) {
            if (!Objects.equals(candidateFingerprint, baselineFingerprint)) {
                result.put("passed", false);
                result.put(
                        "reason",
                        "candidate and baseline scene fingerprints differ: " + candidateFingerprint + " != "
                                + baselineFingerprint);
                return result;
            }
        }
        var baselineValue = readMetric(baseline.summary, gate.getString("metric"));
        if (baselineValue <= 0.0) {
            result.put("passed", false);
            result.put("reason", "baseline metric must be positive, got " + baselineValue);
            return result;
        }
        var actual = "minimum_reduction_percent".equals(operation)
                ? (1.0 - candidateValue / baselineValue) * 100.0
                : (candidateValue / baselineValue - 1.0) * 100.0;
        result.put("baseline", baselineKey.toString());
        result.put("baseline_value", baselineValue);
        result.put("actual", actual);
        result.put(
                "passed",
                "minimum_reduction_percent".equals(operation)
                        ? actual >= gate.getDoubleValue("threshold")
                        : actual <= gate.getDoubleValue("threshold"));
        return result;
    }

    private static String sceneFingerprint(JSONObject summary) {
        var census = summary.getJSONObject("scene_census_final");
        return census == null ? null : census.getString("contentFingerprint");
    }

    private static Map<String, Object> evaluateGateSafely(JSONObject gate, Map<ArtifactKey, Artifact> artifacts) {
        try {
            return evaluateGate(gate, artifacts);
        } catch (RuntimeException failure) {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", gate.getString("id"));
            result.put("metric", gate.getString("metric"));
            result.put("operation", gate.getString("operation"));
            result.put("threshold", gate.getDoubleValue("threshold"));
            result.put("passed", false);
            result.put(
                    "reason", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return result;
        }
    }

    private static double readMetric(JSONObject summary, String path) {
        var current = valueAt(summary, path);
        if (current == null) {
            throw new IllegalArgumentException("missing metric " + path);
        }
        if (!(current instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("metric is unsupported/non-finite: " + path);
        }
        return number.doubleValue();
    }

    private static String canonicalRenderer(String raw) {
        if (raw == null) {
            return null;
        }
        var trimmed = raw.trim();
        var compact = trimmed.replace(" ", "").replace("-", "").toLowerCase();
        if (compact.startsWith("direct3d11") || compact.startsWith("d3d11") || compact.startsWith("dx11")) {
            return "Direct3D11";
        }
        if (compact.startsWith("direct3d12") || compact.startsWith("d3d12") || compact.startsWith("dx12")) {
            return "Direct3D12";
        }
        if (compact.startsWith("metal")) {
            return "Metal";
        }
        if (compact.startsWith("vulkan")) {
            return "Vulkan";
        }
        if (compact.startsWith("opengl")) {
            return "OpenGL";
        }
        return trimmed;
    }

    record Evaluation(boolean passed, Map<String, Object> report) {}

    private record ArtifactKey(String checkpoint, String scenario, String renderer) {
        private ArtifactKey {
            if (checkpoint == null || scenario == null || renderer == null) {
                throw new IllegalArgumentException("artifact identity cannot contain nulls");
            }
            checkpoint = checkpoint.trim();
            scenario = scenario.trim();
            renderer = canonicalRenderer(renderer);
            if (checkpoint.isEmpty() || scenario.isEmpty() || renderer.isEmpty()) {
                throw new IllegalArgumentException("artifact identity cannot contain blank values");
            }
        }

        @Override
        public String toString() {
            return checkpoint + '/' + scenario + '/' + renderer;
        }
    }

    private record Artifact(ArtifactKey key, Path path, JSONObject header, JSONObject summary) {}

    private record Options(Path gates, List<Path> artifacts, Path output, boolean validateOnly) {
        private static Options parse(String[] args) {
            Path gates = Path.of("config/viewer-bgfx-gates.json");
            Path output = null;
            var artifacts = new ArrayList<Path>();
            var validateOnly = false;
            for (var index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--gates" -> gates = Path.of(requireValue(args, ++index, "--gates"));
                    case "--artifact" -> artifacts.add(Path.of(requireValue(args, ++index, "--artifact")));
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--validate-only" -> validateOnly = true;
                    default -> throw new IllegalArgumentException("unknown gate argument " + args[index]);
                }
            }
            if (!validateOnly && artifacts.isEmpty()) {
                throw new IllegalArgumentException("at least one --artifact is required");
            }
            return new Options(gates, List.copyOf(artifacts), output, validateOnly);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
