package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeClassDefaults.Definition;
import com.rost.cruma.core.editing.NativeClassDefaults.Inheritance;
import com.rost.cruma.core.editing.NativeClassDefaults.Property;
import com.rost.cruma.core.editing.P152EffectiveInputs.Fact;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.Origin;
import com.rost.cruma.core.editing.P152EffectiveInputs.Provenance;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.PackageBytes.ExportEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public final class P152TerrainMaterialInputs {
    private static final int MAX_PACKAGE_BYTES = 256 * 1024 * 1024;
    private static final String DEFAULT_TEXTURE = "Engine.DefaultTexture";
    private static final List<String> CHAIN = List.of(
            "Engine.Texture", "Engine.BitmapMaterial", "Engine.RenderedMaterial", "Engine.Material", "Core.Object");
    private static final Map<String, String> ASSETS = Map.of(
            "T_Glacia", "2c482e31d93fc7ec8940ab55592b6f3e88a1d034b7b0a3fa863c322bc1fa3b79",
            "T_12_24", P152TerrainSunBake.HEIGHT_HASH,
            "T_texture", "7b050a67c9753ebd5884970f48ac1d2867a3206481b9f62bf700857afd60f91c");

    private final Path clientRoot;
    private final P152EffectiveInputs runtime;
    private final List<Source> sources;
    private final Map<String, Path> assetPaths;
    private final List<Layer> layers;
    private final List<Texture> textures;
    private final List<Input> inputs;

    private P152TerrainMaterialInputs(
            Path clientRoot,
            P152EffectiveInputs runtime,
            List<Source> sources,
            Map<String, Path> assetPaths,
            List<Layer> layers,
            List<Texture> textures) {
        this.clientRoot = clientRoot;
        this.runtime = runtime;
        this.sources = List.copyOf(sources);
        this.assetPaths = Map.copyOf(assetPaths);
        this.layers = List.copyOf(layers);
        this.textures = List.copyOf(textures);
        var receipts = new LinkedHashMap<String, Input>();
        for (var input : runtime.getReceipt()) {
            receipts.put(input.name(), input);
        }
        for (var source : sources) {
            var name = source.logicalPath().getFileName().toString();
            receipts.put(name, new Input(name, source.sha256()));
        }
        inputs = List.copyOf(receipts.values());
    }

    public record Layer(int index, String texture, String alphaMap) {}

    public record FreshTexture(
            Fact<String> fallbackMaterial,
            Fact<String> defaultMaterial,
            Fact<String> detail,
            Fact<String> animNext,
            Fact<String> initialAnimCurrent,
            Fact<Boolean> initialUseFallback,
            Fact<Boolean> initialValidated,
            Fact<Float> maxFrameRate) {}

    public record Texture(String objectPath, int exportReference, String packageSha256, FreshTexture freshLoad) {}

    private record PackageInput(Source source, PackageBytes data, NativeClassDefaults classDefaults) {}

    private record Value(Property property, Provenance provenance) {}

    public static P152TerrainMaterialInputs capture(Path clientRoot) throws IOException {
        return capture(clientRoot, () -> false);
    }

    public static P152TerrainMaterialInputs capture(Path clientRoot, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toAbsolutePath().normalize();
        var runtime = P152EffectiveInputs.open(root, cancelled);
        var packages = new LinkedHashMap<String, PackageInput>();
        var libraries = new ArrayList<NativeClassDefaults>();
        var sources = new ArrayList<Source>();
        for (var name : List.of("Core.u", "Engine.u")) {
            var expected = runtime.getReceipt().stream()
                    .filter(i -> i.name().equals(name))
                    .findFirst()
                    .orElseThrow()
                    .sha256();
            var input = capturePackage(root, root.resolve("System").resolve(name), expected, cancelled);
            sources.add(input.source());
            libraries.add(input.classDefaults());
            packages.put(input.data().packageName.toLowerCase(Locale.ROOT), input);
        }
        var defaults = NativeClassDefaults.resolve(libraries, "Engine.Texture");
        var map = capturePackage(root, root.resolve("Maps/12_24.unr"), P152TerrainSunBake.MAP_HASH, cancelled);
        sources.add(map.source());
        var layers = readLayers(map.data());
        var assetPaths = new LinkedHashMap<String, Path>();
        for (var name : List.of("T_Glacia", "T_12_24", "T_texture")) {
            var path = findAsset(root, name, cancelled);
            var input = capturePackage(root, path, ASSETS.get(name), cancelled);
            sources.add(input.source());
            packages.put(name.toLowerCase(Locale.ROOT), input);
            assetPaths.put(name, path);
        }
        var identities = new HashSet<Path>();
        for (var source : sources) {
            if (!identities.add(source.realPath())) {
                throw new IOException("Material input packages alias the same physical path");
            }
        }
        var roots = new LinkedHashSet<String>();
        for (var layer : layers) {
            roots.add(layer.texture());
            roots.add(layer.alphaMap());
        }
        if (roots.size() != 16 || !roots.add(DEFAULT_TEXTURE)) {
            throw new IOException("Terrain material roots differ from the audited source profile");
        }
        var textures = new ArrayList<Texture>();
        for (var objectPath : roots) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var input = packages.get(
                    objectPath.substring(0, objectPath.indexOf('.')).toLowerCase(Locale.ROOT));
            if (input == null) {
                throw new IOException("Terrain material root is outside the captured packages");
            }
            var source = input.data();
            var entry = find(source, objectPath);
            var properties = textureProperties(source, entry);
            var facts = resolve(defaults, properties, input.source().sha256());
            requireClosedReferences(facts, objectPath.equals(DEFAULT_TEXTURE) ? "" : DEFAULT_TEXTURE);
            textures.add(new Texture(objectPath, entry.index(), input.source().sha256(), facts));
        }
        var result = new P152TerrainMaterialInputs(root, runtime, sources, assetPaths, layers, textures);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    static FreshTexture resolve(Inheritance defaults, List<Property> authored, String hash) throws IOException {
        if (!defaults.chain().stream().map(Definition::path).toList().equals(CHAIN)) {
            throw new IOException("Texture facts require the exact native class chain");
        }
        return new FreshTexture(
                resolveReference(defaults, authored, hash, "FallbackMaterial", "Engine.Material"),
                resolveReference(defaults, authored, hash, "DefaultMaterial", "Engine.Material"),
                resolveReference(defaults, authored, hash, "Detail", "Engine.Texture"),
                resolveReference(defaults, authored, hash, "AnimNext", "Engine.Texture"),
                transientZero(defaults, authored, hash, "AnimCurrent", "Engine.Texture", ""),
                transientZero(defaults, authored, hash, "UseFallback", "Engine.Material", false),
                transientZero(defaults, authored, hash, "Validated", "Engine.Material", false),
                maxFrameRate(defaults, authored, hash));
    }

    static void requireClosedReferences(FreshTexture facts, String defaultTexture) throws IOException {
        if (!facts.fallbackMaterial().value().isEmpty()
                || !facts.detail().value().isEmpty()
                || !facts.animNext().value().isEmpty()
                || !facts.initialAnimCurrent().value().isEmpty()
                || !facts.defaultMaterial().value().equals(defaultTexture)
                || facts.initialUseFallback().value()
                || facts.initialValidated().value()
                || facts.maxFrameRate().value() != 0.0f) {
            throw new IOException("Texture references or animation differ from the bounded fresh-load profile");
        }
    }

    static List<Property> textureProperties(PackageBytes source, ExportEntry entry) throws IOException {
        final List<Property> properties;
        if (entry.classIndex() < 0) {
            properties = P152EffectiveInputs.actorProperties(source, source.getObjectPath(entry.index()), "Texture");
        } else {
            if (!source.packageName.equals("Engine")
                    || source.fileVersion != 132
                    || source.licenseVersion != 40
                    || entry.index() != 2802
                    || entry.flags() != 0x000e0004
                    || !source.getObjectPath(entry.index()).equals(DEFAULT_TEXTURE)
                    || entry.classIndex() <= 0
                    || source.exports.get(entry.classIndex() - 1).classIndex() != 0
                    || !source.getObjectPath(entry.classIndex()).equals("Engine.Texture")) {
                throw new IOException("Unaudited local Texture class identity");
            }
            var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
            properties = P152EffectiveInputs.taggedProperties(source, c);
            var independent = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
            NativeLevelEditor.skipProperties(source, independent, entry.flags());
            if (c.position != independent.position) {
                throw new IOException("DefaultTexture property boundaries disagree");
            }
        }
        var resolved = new ArrayList<Property>();
        for (var property : properties) {
            var path = property.type() == 5 ? referencePath(source, property) : property.referencedObject();
            resolved.add(new Property(
                    property.name(),
                    property.type(),
                    property.arrayIndex(),
                    property.structType(),
                    property.booleanValue(),
                    property.payload(),
                    path));
        }
        return List.copyOf(resolved);
    }

    static List<Layer> readLayers(PackageBytes source) throws IOException {
        var result = new ArrayList<Layer>();
        var indices = new HashSet<Integer>();
        for (var property : P152EffectiveInputs.actorProperties(source, "12_24.TerrainInfo0", "TerrainInfo")) {
            if (!property.name().equals("Layers")) {
                continue;
            }
            if (property.type() != 10
                    || !"TerrainLayer".equals(property.structType())
                    || property.arrayIndex() < 0
                    || property.arrayIndex() >= 32
                    || !indices.add(property.arrayIndex())) {
                throw new IOException("Unaudited Terrain material layer schema");
            }
            var bytes = property.payload();
            var c = new Cursor(bytes, 0, bytes.length);
            var fields = P152EffectiveInputs.taggedProperties(source, c);
            if (c.position != c.end) {
                throw new IOException("Unread Terrain material layer suffix");
            }
            var texture = findUnique(fields, "Texture");
            var alpha = findUnique(fields, "AlphaMap");
            if (texture == null || alpha == null) {
                throw new IOException("Missing Terrain material layer root");
            }
            var texturePath = referencePath(source, texture);
            var alphaPath = referencePath(source, alpha);
            if (property.arrayIndex() >= 8) {
                if (!texturePath.isEmpty() || !alphaPath.isEmpty()) {
                    throw new IOException("Unexpected active Terrain material layer");
                }
                continue;
            }
            if (texturePath.isEmpty() || alphaPath.isEmpty()) {
                throw new IOException("Null Terrain material layer root");
            }
            result.add(new Layer(property.arrayIndex(), texturePath, alphaPath));
        }
        if (result.size() != 8) {
            throw new IOException("Terrain material layer count differs from the source profile");
        }
        return List.copyOf(result);
    }

    static Path findAsset(Path root, String name, BooleanSupplier cancelled) throws IOException {
        var matches = new ArrayList<Path>();
        for (var directory : List.of("Textures", "SysTextures")) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var folder = root.resolve(directory);
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try (var files = Files.list(folder)) {
                var iterator = files.iterator();
                var entries = 0;
                while (true) {
                    P152FreshHashQuery.checkCancelled(cancelled);
                    if (!iterator.hasNext()) {
                        break;
                    }
                    if (++entries > 100_000) {
                        throw new IOException("Material package namespace exceeds capture bound");
                    }
                    var path = iterator.next();
                    if (path.getFileName().toString().equalsIgnoreCase(name + ".utx") && Files.isRegularFile(path)) {
                        matches.add(path);
                        if (matches.size() > 1) {
                            throw new IOException("Missing or ambiguous material package: " + name);
                        }
                    }
                }
            }
        }
        if (matches.size() != 1) {
            throw new IOException("Missing or ambiguous material package: " + name);
        }
        return matches.getFirst();
    }

    public List<Layer> getLayers() {
        return layers;
    }

    public List<Texture> getTextures() {
        return textures;
    }

    public List<Input> getInputs() {
        return inputs;
    }

    public void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    public void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        for (var entry : assetPaths.entrySet()) {
            if (!findAsset(clientRoot, entry.getKey(), cancelled).equals(entry.getValue())) {
                throw new IOException("Material input package namespace changed");
            }
        }
        for (var source : sources) {
            source.verifyUnchanged(cancelled);
        }
        runtime.verifySourcesUnchanged(cancelled);
    }

    private static Fact<String> resolveReference(
            Inheritance defaults, List<Property> authored, String hash, String name, String declaring)
            throws IOException {
        var value = findValue(defaults, authored, hash, name);
        if (value == null) {
            return zero(defaults, declaring, name, "");
        }
        var property = value.property();
        if (property.type() != 5 || property.referencedObject() == null) {
            throw new IOException("Texture input requires a resolved ObjectProperty: " + name);
        }
        var payload = property.payload();
        var c = new Cursor(payload, 0, payload.length);
        var reference = c.readIndex();
        var path = property.referencedObject();
        var isNull = path.isEmpty() || path.equals("None");
        if (c.position != c.end || (reference == 0) != isNull) {
            throw new IOException("Malformed texture reference: " + name);
        }
        return new Fact<>(isNull ? "" : path, value.provenance());
    }

    private static Fact<Float> maxFrameRate(Inheritance defaults, List<Property> authored, String hash)
            throws IOException {
        var value = findValue(defaults, authored, hash, "MaxFrameRate");
        if (value == null) {
            return zero(defaults, "Engine.Texture", "MaxFrameRate", 0.0f);
        }
        var property = value.property();
        if (property.type() != 4 || property.payload().length != 4) {
            throw new IOException("Texture MaxFrameRate requires a FloatProperty");
        }
        var rate = ByteBuffer.wrap(property.payload())
                .order(ByteOrder.LITTLE_ENDIAN)
                .getFloat();
        if (!Float.isFinite(rate) || rate < 0) {
            throw new IOException("Invalid texture MaxFrameRate");
        }
        return new Fact<>(rate, value.provenance());
    }

    private static <T> Fact<T> transientZero(
            Inheritance defaults, List<Property> authored, String hash, String name, String declaring, T zero)
            throws IOException {
        if (findValue(defaults, authored, hash, name) != null) {
            throw new IOException("Serialized transient texture input is unaudited: " + name);
        }
        return zero(defaults, declaring, name, zero);
    }

    private static <T> Fact<T> zero(Inheritance defaults, String declaring, String name, T zero) {
        var definition = defaults.chain().stream()
                .filter(d -> d.path().equals(declaring))
                .findFirst()
                .orElseThrow();
        return new Fact<>(
                zero, new Provenance(Origin.NATIVE_ZERO_INITIALIZATION, declaring, name, definition.sourceSha256()));
    }

    private static Value findValue(Inheritance defaults, List<Property> authored, String hash, String name)
            throws IOException {
        var instance = findUnique(authored, name);
        Value inherited = null;
        for (var definition : defaults.chain()) {
            var property = findUnique(definition.properties(), name);
            if (property != null && inherited == null) {
                inherited = new Value(
                        property,
                        new Provenance(
                                Origin.SERIALIZED_CLASS_DEFAULT, definition.path(), name, definition.sourceSha256()));
            }
        }
        return instance == null
                ? inherited
                : new Value(instance, new Provenance(Origin.INSTANCE, "Engine.Texture", name, hash));
    }

    private static Property findUnique(List<Property> properties, String name) throws IOException {
        Property found = null;
        for (var property : properties) {
            if (!property.name().equalsIgnoreCase(name)) {
                continue;
            }
            if (found != null || property.arrayIndex() != 0) {
                throw new IOException("Ambiguous or indexed texture input: " + name);
            }
            found = property;
        }
        return found;
    }

    private static String referencePath(PackageBytes source, Property property) throws IOException {
        if (property.type() != 5) {
            throw new IOException("Material root requires an ObjectProperty");
        }
        var bytes = property.payload();
        var c = new Cursor(bytes, 0, bytes.length);
        var reference = c.readIndex();
        source.checkReference(reference);
        if (c.position != c.end) {
            throw new IOException("Unread material reference payload");
        }
        return reference == 0 ? "" : source.getObjectPath(reference);
    }

    private static ExportEntry find(PackageBytes source, String path) throws IOException {
        ExportEntry found = null;
        for (var entry : source.exports) {
            if (source.getObjectPath(entry.index()).equals(path)) {
                if (found != null) {
                    throw new IOException("Ambiguous material export");
                }
                found = entry;
            }
        }
        if (found == null) {
            throw new IOException("Missing material export: " + path);
        }
        return found;
    }

    private static PackageInput capturePackage(Path root, Path path, String expected, BooleanSupplier cancelled)
            throws IOException {
        var logical = path.toAbsolutePath().normalize();
        var real = logical.toRealPath();
        if (!real.startsWith(root.toRealPath())) {
            throw new IOException("Material package is outside the captured client root");
        }
        var bytes = P152CaptureIo.read(real, MAX_PACKAGE_BYTES, cancelled);
        var source = new Source(logical, real, P152CaptureIo.hash(bytes, cancelled));
        if (!source.sha256().equals(expected) || !logical.toRealPath().equals(real)) {
            throw new IOException("Unaudited or changed material package fingerprint: " + path.getFileName());
        }
        var library = path.getFileName().toString().endsWith(".u") ? NativeClassDefaults.read(logical, bytes) : null;
        return new PackageInput(source, new PackageBytes(logical, bytes), library);
    }
}
