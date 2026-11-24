package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class P152TerrainEnvironmentInputs {
    private static final int MAX_ENV_BYTES = 256 * 1024;
    private static final String ENV_SHA256 = "c024f6cc5204871133a5335e456a194fb15fa3e9f28a345c8f574d4490cb2c26";
    private static final List<String> TIME_SHA256 = List.of(
            "08e74215fec90a8ac36cf6736d764121d7183bd3cca7eb8443af34f97f73e42f",
            "2ad26f0bb6fd615b9e7246a6b16853f8809a3a39b9804a576576e05e9cad53e8",
            "9ff6881593b78119627b6abff6cbdc0143233f9791a2c2d51e26e339a4186a47",
            "a5957d0eafd4b36cd04d8436b57ca86dd6db02cd2d4bf99d6f086058f9e96205",
            "6813bfd9d44732d99117607d8eb0388569769c4a67c26ab57a991736c1b459ea",
            "b9dbcca2667f99f0b165bf37f01f5c3393326626c0e2037f53091ccbeaa13154");

    private final P152EffectiveInputs runtime;
    private final List<Source> sources;
    private final List<Environment> environments;
    private final List<Input> inputs;

    private P152TerrainEnvironmentInputs(
            P152EffectiveInputs runtime, List<Source> sources, List<Environment> environments) {
        this.runtime = runtime;
        this.sources = List.copyOf(sources);
        this.environments = List.copyOf(environments);
        var receipts = new ArrayList<>(runtime.getReceipt());
        for (var source : sources) {
            receipts.add(new Input(source.logicalPath().getFileName().toString(), source.sha256()));
        }
        inputs = List.copyOf(receipts);
    }

    public record Ambient(float time, int red, int green, int blue) {
        public Ambient {
            requireTime(time);
            requireByte(red);
            requireByte(green);
            requireByte(blue);
        }
    }

    public record Sun(float time, int hue, int saturation, float brightness) {
        public Sun {
            requireTime(time);
            requireByte(hue);
            requireByte(saturation);
            if (!Float.isFinite(brightness) || brightness < 0) {
                throw new IllegalArgumentException("Invalid Terrain sunlight brightness");
            }
        }
    }

    public record Environment(int type, List<Ambient> ambient, List<Sun> sun) {
        public Environment {
            if (type < 0 || type >= 6) {
                throw new IllegalArgumentException("Unsupported ordinary TimeEnv type");
            }
            ambient = List.copyOf(ambient);
            sun = List.copyOf(sun);
            requireTimes(ambient.stream().map(Ambient::time).toList());
            requireTimes(sun.stream().map(Sun::time).toList());
        }
    }

    private record Captured(Source source, byte[] bytes) {}

    public static P152TerrainEnvironmentInputs capture(Path clientRoot) throws IOException {
        return capture(clientRoot, () -> false);
    }

    public static P152TerrainEnvironmentInputs capture(Path clientRoot, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toAbsolutePath().normalize();
        var runtime = P152EffectiveInputs.open(root, cancelled);
        var sources = new ArrayList<Source>();
        var setup = readSource(root, "Env.int", ENV_SHA256, cancelled);
        sources.add(setup.source());
        var names = fileNames(setup.bytes());
        var environments = new ArrayList<Environment>();
        for (var i = 0; i < names.size(); i++) {
            var source = readSource(root, names.get(i), TIME_SHA256.get(i), cancelled);
            sources.add(source.source());
            environments.add(parseEnvironment(i, source.bytes()));
        }
        var identities = new HashSet<Path>();
        for (var source : sources) {
            if (!identities.add(source.realPath())) {
                throw new IOException("Terrain environment inputs alias the same physical path");
            }
        }
        var result = new P152TerrainEnvironmentInputs(runtime, sources, environments);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    static List<String> fileNames(byte[] bytes) throws IOException {
        var setup = requireSection(parse(bytes), "EnvSetup");
        if (parseInteger(requireValue(setup, "TimeEnvFileNum")) != 6) {
            throw new IOException("Ordinary TimeEnv file count differs from the source profile");
        }
        var names = new ArrayList<String>();
        for (var i = 0; i < 6; i++) {
            var name = "TimeEnv" + i + ".int";
            if (!requireValue(setup, "TimeEnvFileName" + i).equalsIgnoreCase(name)) {
                throw new IOException("Ordinary TimeEnv filename differs from the source profile");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    static Environment parseEnvironment(int expectedType, byte[] bytes) throws IOException {
        var ini = parse(bytes);
        var type = parseInteger(requireValue(requireSection(ini, "EnvType"), "EnvType"));
        if (type != expectedType) {
            throw new IOException("Ordinary TimeEnv type differs from the source profile");
        }
        var ambientSection = requireSection(ini, "TerrainAmbient");
        var sunSection = requireSection(ini, "HSVTerrainLight");
        var ambientCount = count(ambientSection);
        var sunCount = count(sunSection);
        var ambient = new ArrayList<Ambient>();
        var sun = new ArrayList<Sun>();
        try {
            for (var i = 1; i <= ambientCount; i++) {
                var fields = parseFields(requireValue(ambientSection, "Color" + i), List.of("t", "r", "g", "b"));
                ambient.add(new Ambient(
                        parseDecimal(fields.get("t")),
                        parseInteger(fields.get("r")),
                        parseInteger(fields.get("g")),
                        parseInteger(fields.get("b"))));
            }
            for (var i = 1; i <= sunCount; i++) {
                var fields = parseFields(requireValue(sunSection, "Light" + i), List.of("t", "hue", "sat", "bri"));
                sun.add(new Sun(
                        parseDecimal(fields.get("t")),
                        parseInteger(fields.get("hue")),
                        parseInteger(fields.get("sat")),
                        parseDecimal(fields.get("bri"))));
            }
            return new Environment(type, ambient, sun);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Terrain environment record is outside the bounded profile", invalid);
        }
    }

    public List<Environment> getEnvironments() {
        return environments;
    }

    public List<Input> getInputs() {
        return inputs;
    }

    public void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        for (var source : sources) {
            source.verifyUnchanged(cancelled);
        }
        runtime.verifySourcesUnchanged(cancelled);
    }

    private static Captured readSource(Path root, String name, String expected, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var logical = root.resolve("System").resolve(name);
        var real = logical.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new IOException("Terrain environment source escapes the client root");
        }
        var bytes = P152CaptureIo.read(real, MAX_ENV_BYTES, cancelled);
        if (!P152CaptureIo.hash(bytes, cancelled).equals(expected)) {
            throw new IOException("Unaudited Terrain environment fingerprint: " + name);
        }
        var source = new Source(logical, real, expected);
        source.verifyUnchanged(cancelled);
        return new Captured(source, bytes);
    }

    private static Map<String, Map<String, String>> parse(byte[] bytes) throws IOException {
        if (bytes.length < 28
                || bytes.length > MAX_ENV_BYTES
                || !new String(bytes, 0, 28, StandardCharsets.UTF_16LE).equals("Lineage2Ver111")) {
            throw new IOException("Unqualified Terrain environment container");
        }
        var decoded = new byte[bytes.length - 28];
        for (var i = 0; i < decoded.length; i++) {
            decoded[i] = (byte) (bytes[i + 28] ^ 0xac);
            if (decoded[i] == 0) {
                throw new IOException("Unexpected NUL in Terrain environment container");
            }
        }
        var result = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> current = null;
        for (var line : new String(decoded, StandardCharsets.ISO_8859_1).split("[\\r\\n]")) {
            if (line.startsWith("[") && line.endsWith("]")) {
                current = result.computeIfAbsent(
                        foldCase(line.substring(1, line.length() - 1)), ignored -> new LinkedHashMap<>());
            } else if (current != null) {
                var equal = line.indexOf('=');
                if (equal >= 0) {
                    var text = line.substring(equal + 1);
                    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                        text = text.substring(1, text.length() - 1);
                    }
                    current.put(foldCase(line.substring(0, equal)), text);
                }
            }
        }
        return result;
    }

    private static Map<String, String> requireSection(Map<String, Map<String, String>> ini, String name)
            throws IOException {
        var section = ini.get(foldCase(name));
        if (section == null) {
            throw new IOException("Missing Terrain environment section: " + name);
        }
        return section;
    }

    private static String requireValue(Map<String, String> section, String name) throws IOException {
        var value = section.get(foldCase(name));
        if (value == null || !value.chars().allMatch(c -> c > 0 && c < 128)) {
            throw new IOException("Missing or non-ASCII Terrain environment value: " + name);
        }
        return value;
    }

    private static Map<String, String> parseFields(String text, List<String> names) throws IOException {
        text = text.trim();
        if (!text.startsWith("(") || !text.endsWith(")")) {
            throw new IOException("Malformed Terrain environment color record");
        }
        var fields = new LinkedHashMap<String, String>();
        for (var field : text.substring(1, text.length() - 1).split(",", -1)) {
            var equal = field.indexOf('=');
            if (equal < 1
                    || fields.put(
                                    foldCase(field.substring(0, equal).trim()),
                                    field.substring(equal + 1).trim())
                            != null) {
                throw new IOException("Malformed or duplicate Terrain environment color field");
            }
        }
        if (fields.size() != names.size() || !fields.keySet().containsAll(names)) {
            throw new IOException("Missing or unexpected Terrain environment color field");
        }
        return fields;
    }

    private static int count(Map<String, String> section) throws IOException {
        var count = parseInteger(requireValue(section, "Num"));
        if (count < 2 || count > 32) {
            throw new IOException("Terrain environment record count is outside the bounded profile");
        }
        return count;
    }

    private static int parseInteger(String text) throws IOException {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid Terrain environment integer", invalid);
        }
    }

    private static float parseDecimal(String text) throws IOException {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid Terrain environment number", invalid);
        }
    }

    private static String foldCase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireTime(float time) {
        if (!Float.isFinite(time) || time < 0 || time > 24) {
            throw new IllegalArgumentException("Invalid Terrain environment key time");
        }
    }

    private static void requireByte(int value) {
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Invalid Terrain environment color byte");
        }
    }

    private static void requireTimes(List<Float> times) {
        if (times.size() < 2 || times.size() > 32 || times.getFirst() != 0f || times.getLast() != 24f) {
            throw new IllegalArgumentException("Terrain environment keys do not span the complete day");
        }
        for (var i = 1; i < times.size(); i++) {
            if (times.get(i) <= times.get(i - 1)) {
                throw new IllegalArgumentException("Terrain environment key times must increase");
            }
        }
    }
}
