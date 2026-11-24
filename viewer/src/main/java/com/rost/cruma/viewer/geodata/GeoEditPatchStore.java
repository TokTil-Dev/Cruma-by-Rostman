package com.rost.cruma.viewer.geodata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoDataAccess.Layer;
import com.rost.cruma.core.geodata.GeoEditDocument.CellEdit;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.geodata.GeoEditingSession.Patch;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.TreeMap;

public final class GeoEditPatchStore {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private GeoEditPatchStore() {}

    public static Patch read(Path path) throws IOException {
        if (Files.size(path) > MAX_BYTES) {
            throw new IOException("Geodata patch exceeds 16 MiB");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Geodata patch exceeds 16 MiB");
        }
        try {
            return decode(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid geodata patch: " + exception.getMessage(), exception);
        }
    }

    public static void write(Path path, Patch patch, boolean replaceExisting) throws IOException {
        var destination = path.toAbsolutePath().normalize();
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
            throw new IOException("Save geodata patches as JSON files");
        }
        if (!replaceExisting && Files.exists(destination)) {
            throw new IOException("Patch already exists. Explicit replacement is required");
        }
        var bytes = encode(patch).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Geodata patch exceeds 16 MiB");
        }
        var parent = destination.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, ".geodata-patch-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (replaceExisting) {
                try {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
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

    public static String encode(Patch patch) {
        var root = new JSONObject();
        root.put("version", patch.version());
        var sources = new JSONArray();
        var ordered =
                new TreeMap<GeoTile, String>(Comparator.comparingInt(GeoTile::x).thenComparingInt(GeoTile::y));
        ordered.putAll(patch.sourceFingerprints());
        for (var entry : ordered.entrySet()) {
            var source = new JSONObject();
            source.put("tileX", entry.getKey().x());
            source.put("tileY", entry.getKey().y());
            source.put("sha256", entry.getValue());
            sources.add(source);
        }
        root.put("sources", sources);
        var cells = new JSONArray();
        var edits = new ArrayList<>(patch.cells());
        edits.sort(Comparator.comparingInt(CellEdit::x).thenComparingInt(CellEdit::y));
        for (var edit : edits) {
            var cell = new JSONObject();
            cell.put("x", edit.x());
            cell.put("y", edit.y());
            var layers = new JSONArray();
            for (var layer : edit.layers()) {
                var value = new JSONObject();
                value.put("height", layer.height());
                value.put("nswe", layer.nswe());
                layers.add(value);
            }
            cell.put("layers", layers);
            cells.add(cell);
        }
        root.put("cells", cells);
        var json = JSON.toJSONString(root);
        if (json.length() > MAX_BYTES) {
            throw new IllegalArgumentException("Geodata patch exceeds 16 MiB");
        }
        return json;
    }

    public static Patch decode(String json) {
        if (json.length() > MAX_BYTES) {
            throw new IllegalArgumentException("Geodata patch exceeds 16 MiB");
        }
        try {
            var root = JSON.parseObject(json);
            var version = readInteger(root, "version");
            var sourceValues = root.getJSONArray("sources");
            var cellValues = root.getJSONArray("cells");
            if (sourceValues == null || sourceValues.size() > 340 || cellValues == null || cellValues.size() > 262144) {
                throw new IllegalArgumentException("Invalid patch source or cell list");
            }
            var sources = new LinkedHashMap<GeoTile, String>();
            for (var index = 0; index < sourceValues.size(); index++) {
                var source = sourceValues.getJSONObject(index);
                var tile = new GeoTile(readInteger(source, "tileX"), readInteger(source, "tileY"));
                var hash = source.getString("sha256");
                if (hash == null || sources.putIfAbsent(tile, hash) != null) {
                    throw new IllegalArgumentException("Missing or duplicate patch source fingerprint");
                }
            }
            var cells = new ArrayList<CellEdit>();
            var totalLayers = 0L;
            for (var index = 0; index < cellValues.size(); index++) {
                var cell = cellValues.getJSONObject(index);
                var layerValues = cell.getJSONArray("layers");
                if (layerValues == null || layerValues.size() > Short.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid patch layer list");
                }
                totalLayers += layerValues.size();
                if (totalLayers > 2_000_000) {
                    throw new IllegalArgumentException("Patch exceeds layer limit");
                }
                var layers = new ArrayList<Layer>();
                for (var layerIndex = 0; layerIndex < layerValues.size(); layerIndex++) {
                    var layer = layerValues.getJSONObject(layerIndex);
                    layers.add(new Layer(layerIndex, readInteger(layer, "height"), readInteger(layer, "nswe")));
                }
                cells.add(new CellEdit(readInteger(cell, "x"), readInteger(cell, "y"), layers));
            }
            return new Patch(version, sources, cells);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException invalid) {
                throw invalid;
            }
            throw new IllegalArgumentException("Invalid geodata patch JSON", exception);
        }
    }

    private static int readInteger(JSONObject object, String key) {
        if (object == null || !(object.get(key) instanceof Number number)) {
            throw new IllegalArgumentException("Missing integer field " + key);
        }
        try {
            return new BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer field " + key);
        }
    }
}
