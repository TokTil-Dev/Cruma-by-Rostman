package com.rost.cruma.core.editing;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record PackageInventory(
        String name,
        int fileVersion,
        int licenseVersion,
        int container,
        List<Reference> imports,
        Map<String, List<Definition>> exports) {
    public record Reference(String path, String expectedClass) {}

    public record Definition(String path, String classPath, String superPath) {}

    public static PackageInventory inspect(Path filename, byte[] bytes) throws IOException {
        var source = new PackageBytes(filename, bytes);
        var imports = new ArrayList<Reference>();
        for (var i = 0; i < source.imports.size(); i++) {
            var entry = source.imports.get(i);
            imports.add(new Reference(
                    source.getObjectPath(-i - 1),
                    source.getName(entry.classPackage()) + "." + source.getName(entry.className())));
        }
        var exports = new LinkedHashMap<String, List<Definition>>();
        for (var entry : source.exports) {
            var path = source.getObjectPath(entry.index());
            var definition = new Definition(
                    path,
                    entry.classIndex() == 0 ? "Core.Class" : source.getObjectPath(entry.classIndex()),
                    entry.superIndex() == 0 ? "" : source.getObjectPath(entry.superIndex()));
            exports.computeIfAbsent(path.toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(definition);
        }
        exports.replaceAll((path, definitions) -> List.copyOf(definitions));
        return new PackageInventory(
                source.packageName,
                source.fileVersion,
                source.licenseVersion,
                source.container,
                List.copyOf(imports),
                Map.copyOf(exports));
    }

    public List<Definition> definitions(String path) {
        return exports.getOrDefault(path.toLowerCase(Locale.ROOT), List.of());
    }

    public Definition classDefinition(String path) {
        var classes = definitions(path).stream()
                .filter(item -> item.classPath().equalsIgnoreCase("Core.Class"))
                .toList();
        return classes.size() == 1 ? classes.getFirst() : null;
    }
}
