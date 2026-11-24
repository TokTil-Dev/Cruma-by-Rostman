package com.rost.cruma.viewer.geodata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.viewer.geodata.GeoDiagnosticCase.Request;
import com.rost.cruma.viewer.geodata.GeodataDiagnosticJobs.Kind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class GeoDiagnosticCaseStore {
    public static final int MAX_BYTES = 64 * 1024;
    public static final String FORMAT = "cruma-geodata-diagnostic";

    private GeoDiagnosticCaseStore() {}

    public static GeoDiagnosticCase read(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Diagnostic case exceeds 64 KiB");
        }
        try {
            return decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid diagnostic case: " + exception.getMessage(), exception);
        }
    }

    public static void write(Path path, GeoDiagnosticCase diagnostic, boolean replaceExisting) throws IOException {
        var destination = path.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new IOException("Save diagnostic cases as JSON files");
        }
        if (!replaceExisting && Files.exists(destination)) {
            throw new IOException("Diagnostic case already exists. Explicit replacement is required");
        }
        var parent = destination.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, ".geodata-case-", ".tmp");
        try {
            Files.writeString(temporary, encode(diagnostic), StandardCharsets.UTF_8);
            if (replaceExisting) {
                try {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String encode(GeoDiagnosticCase diagnostic) {
        var root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", diagnostic.version());
        root.put("algorithm", diagnostic.algorithm());
        root.put("fingerprintScope", diagnostic.fingerprintScope());
        root.put("sourceATrace", diagnostic.sourceATrace());
        root.put("sourceBTrace", diagnostic.sourceBTrace());
        root.put("outcome", diagnostic.outcome());
        root.put("clientTrace", diagnostic.clientTrace());
        var request = diagnostic.request();
        var input = new JSONObject();
        input.put("kind", request.kind().name());
        input.put("minX", request.bounds().minX());
        input.put("minY", request.bounds().minY());
        input.put("maxX", request.bounds().maxX());
        input.put("maxY", request.bounds().maxY());
        input.put("maxCells", request.maxCells());
        input.put("maxReports", request.maxReports());
        input.put("maxTransitions", request.maxTransitions());
        input.put("abruptHeight", request.abruptHeight());
        input.put("maxLayerComparisons", request.maxLayerComparisons());
        input.put("clientGeometry", request.clientGeometry());
        input.put("surfaceTolerance", request.surfaceTolerance());
        input.put("maxClientTriangleTests", request.maxClientTriangleTests());
        root.put("request", input);
        return JSON.toJSONString(root);
    }

    public static GeoDiagnosticCase decode(String json) {
        if (json.length() > MAX_BYTES) {
            throw new IllegalArgumentException("Diagnostic case exceeds 64 KiB");
        }
        try {
            var root = JSON.parseObject(json);
            if (root == null || !FORMAT.equals(root.getString("format"))) {
                throw new IllegalArgumentException("Unsupported file type. Expected a geodata diagnostic case");
            }
            var input = root.getJSONObject("request");
            if (input == null) {
                throw new IllegalArgumentException("Missing diagnostic request");
            }
            var request = new Request(
                    Kind.valueOf(input.getString("kind")),
                    new Bounds(
                            readInteger(input, "minX"),
                            readInteger(input, "minY"),
                            readInteger(input, "maxX"),
                            readInteger(input, "maxY")),
                    readInteger(input, "maxCells"),
                    readInteger(input, "maxReports"),
                    readInteger(input, "maxTransitions"),
                    readInteger(input, "abruptHeight"),
                    readInteger(input, "maxLayerComparisons"),
                    readBoolean(input, "clientGeometry"),
                    readInteger(input, "surfaceTolerance"),
                    readInteger(input, "maxClientTriangleTests"));
            return new GeoDiagnosticCase(
                    readInteger(root, "version"),
                    root.getString("algorithm"),
                    root.getString("fingerprintScope"),
                    request,
                    root.getString("sourceATrace"),
                    root.getString("sourceBTrace"),
                    root.getString("outcome"),
                    root.getString("clientTrace"));
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("Invalid diagnostic case JSON", exception);
        }
    }

    private static int readInteger(JSONObject object, String key) {
        if (!(object.get(key) instanceof Number number)) {
            throw new IllegalArgumentException("Missing integer field " + key);
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer field " + key);
        }
    }

    private static boolean readBoolean(JSONObject object, String key) {
        if (!(object.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("Missing boolean field " + key);
        }
        return value;
    }
}
