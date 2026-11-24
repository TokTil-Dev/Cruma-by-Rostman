package com.rost.cruma.viewer.geodata;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.geodata.GeoScenario.Camera;
import com.rost.cruma.viewer.geodata.GeoScenario.Endpoint;
import com.rost.cruma.viewer.geodata.GeoScenario.Expected;
import com.rost.cruma.viewer.geodata.GeoScenario.Fingerprints;
import com.rost.cruma.viewer.geodata.GeoScenario.Kind;
import com.rost.cruma.viewer.geodata.GeoScenario.Layer;
import com.rost.cruma.viewer.geodata.GeoScenario.Profile;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

public final class GeoScenarioStore {
    private GeoScenarioStore() {}

    public static GeoScenario read(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) {
            throw new IOException("Scenario exceeds 1 MiB");
        }
        try {
            return decode(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid geodata scenario", error);
        }
    }

    public static void write(Path path, GeoScenario scenario) throws IOException {
        write(path, scenario, false);
    }

    public static void write(Path path, GeoScenario scenario, boolean replaceExisting) throws IOException {
        writeText(path, encode(scenario), replaceExisting);
    }

    static void writeText(Path path, String text, boolean replaceExisting) throws IOException {
        writeBytes(path, text.getBytes(StandardCharsets.UTF_8), replaceExisting);
    }

    static void writeBytes(Path path, byte[] bytes, boolean replaceExisting) throws IOException {
        var destination = path.toAbsolutePath().normalize();
        var parent = destination.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, ".geodata-scenario-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (!replaceExisting) {
                Files.move(temporary, destination);
                return;
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String encode(GeoScenario scenario) {
        var root = new JSONObject();
        root.put("version", scenario.version());
        root.put("id", scenario.id());
        root.put("name", scenario.name());
        root.put("sourceAlias", scenario.sourceAlias());
        root.put("fingerprints", JSON.toJSON(scenario.fingerprints()));
        var camera = new JSONObject();
        camera.put("position", pointJson(scenario.camera().position()));
        camera.put("yaw", scenario.camera().yaw());
        camera.put("pitch", scenario.camera().pitch());
        camera.put(
                "state",
                scenario.camera().state() == null
                        ? null
                        : JSON.toJSON(scenario.camera().state()));
        root.put("camera", camera);
        root.put("a", endpointJson(scenario.a()));
        root.put("b", endpointJson(scenario.b()));
        root.put("profile", JSON.toJSON(scenario.profile()));
        var expected = new JSONObject();
        expected.put("success", scenario.expected().success());
        expected.put("complete", scenario.expected().complete());
        expected.put("status", scenario.expected().status());
        expected.put("finalPosition", pointJson(scenario.expected().finalPosition()));
        expected.put("traveledDistance", scenario.expected().traveledDistance());
        expected.put("digest", scenario.expected().digest());
        expected.put("tolerance", scenario.expected().tolerance());
        expected.put(
                "resolvedA",
                scenario.expected().resolvedA() == null
                        ? null
                        : JSON.toJSON(scenario.expected().resolvedA()));
        expected.put(
                "resolvedB",
                scenario.expected().resolvedB() == null
                        ? null
                        : JSON.toJSON(scenario.expected().resolvedB()));
        root.put("expected", expected);
        return JSON.toJSONString(root);
    }

    public static GeoScenario decode(String json) {
        if (json.length() > 1024 * 1024) {
            throw new IllegalArgumentException("Scenario exceeds 1 MiB");
        }
        try {
            var root = JSON.parseObject(json);
            var version = readInteger(root, "version");
            if (version != GeoScenario.VERSION) {
                throw new IllegalArgumentException("Unsupported geodata scenario version: " + version);
            }
            var fingerprint = root.getJSONObject("fingerprints");
            var camera = root.getJSONObject("camera");
            var profile = root.getJSONObject("profile");
            var expected = root.getJSONObject("expected");
            return new GeoScenario(
                    version,
                    root.getString("id"),
                    root.getString("name"),
                    root.getString("sourceAlias"),
                    new Fingerprints(
                            fingerprint.getString("data"),
                            fingerprint.getString("algorithm"),
                            fingerprint.getString("graph"),
                            readRegions(fingerprint),
                            fingerprint.containsKey("editedPreview") && readBoolean(fingerprint, "editedPreview")),
                    new Camera(
                            readPoint(camera.getJSONObject("position")),
                            readNumber(camera, "yaw"),
                            readNumber(camera, "pitch"),
                            camera.getJSONObject("state") == null
                                    ? null
                                    : camera.getJSONObject("state").toJavaObject(Snapshot.class)),
                    readEndpoint(root.getJSONObject("a")),
                    readEndpoint(root.getJSONObject("b")),
                    new Profile(
                            Kind.valueOf(profile.getString("kind")),
                            profile.getString("movementMode"),
                            readNumber(profile, "distance"),
                            readInteger(profile, "maxSteps"),
                            readInteger(profile, "maxTrace"),
                            readInteger(profile, "radius"),
                            readInteger(profile, "maxNodes"),
                            readInteger(profile, "maxPathVisits"),
                            readInteger(profile, "maxWaypoints"),
                            profile.containsKey("maxMillis") ? readInteger(profile, "maxMillis") : 5000),
                    new Expected(
                            readBoolean(expected, "success"),
                            readBoolean(expected, "complete"),
                            expected.getString("status"),
                            readPoint(expected.getJSONObject("finalPosition")),
                            readNumber(expected, "traveledDistance"),
                            expected.getString("digest"),
                            readNumber(expected, "tolerance"),
                            readLayer(expected.getJSONObject("resolvedA")),
                            readLayer(expected.getJSONObject("resolvedB"))));
        } catch (NullPointerException error) {
            throw new IllegalArgumentException("Required scenario field is missing", error);
        }
    }

    private static JSONObject pointJson(GeoPoint value) {
        if (value == null) {
            return null;
        }
        var point = new JSONObject();
        point.put("x", value.x());
        point.put("y", value.y());
        point.put("z", value.z());
        return point;
    }

    private static JSONObject endpointJson(Endpoint value) {
        if (value == null) {
            return null;
        }
        var endpoint = new JSONObject();
        endpoint.put("point", pointJson(value.point()));
        endpoint.put("selectedLayer", value.selectedLayer() == null ? null : JSON.toJSON(value.selectedLayer()));
        return endpoint;
    }

    private static GeoPoint readPoint(JSONObject value) {
        return value == null
                ? null
                : new GeoPoint(readNumber(value, "x"), readNumber(value, "y"), readNumber(value, "z"));
    }

    private static Endpoint readEndpoint(JSONObject value) {
        if (value == null) {
            return null;
        }
        var layer = value.getJSONObject("selectedLayer");
        return new Endpoint(
                readPoint(value.getJSONObject("point")),
                layer == null
                        ? null
                        : new Layer(
                                readInteger(layer, "x"),
                                readInteger(layer, "y"),
                                readInteger(layer, "index"),
                                readInteger(layer, "height")));
    }

    private static Layer readLayer(JSONObject layer) {
        return layer == null
                ? null
                : new Layer(
                        readInteger(layer, "x"),
                        readInteger(layer, "y"),
                        readInteger(layer, "index"),
                        readInteger(layer, "height"));
    }

    private static JSONObject requireField(JSONObject value, String key) {
        if (value == null || !value.containsKey(key) || value.get(key) == null) {
            throw new IllegalArgumentException("Missing scenario field: " + key);
        }
        return value;
    }

    private static Map<String, String> readRegions(JSONObject fingerprints) {
        var result = new TreeMap<String, String>();
        var regions = fingerprints.getJSONObject("regions");
        if (regions != null) {
            for (var key : regions.keySet()) {
                result.put(key, regions.getString(key));
            }
        }
        return result;
    }

    private static double readNumber(JSONObject value, String key) {
        var raw = requireField(value, key).get(key);
        if (!(raw instanceof Number number)) {
            throw new IllegalArgumentException("Invalid number: " + key);
        }
        return number.doubleValue();
    }

    private static int readInteger(JSONObject value, String key) {
        var number = readNumber(value, key);
        if (number != (int) number) {
            throw new IllegalArgumentException("Invalid integer: " + key);
        }
        return (int) number;
    }

    private static boolean readBoolean(JSONObject value, String key) {
        var raw = requireField(value, key).get(key);
        if (!(raw instanceof Boolean result)) {
            throw new IllegalArgumentException("Invalid boolean: " + key);
        }
        return result;
    }
}
