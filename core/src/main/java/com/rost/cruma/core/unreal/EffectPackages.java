package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class EffectPackages {
    private static final String STOCK_PREFIX = "lineageeffect";
    private static final String SKILL_PACKAGE = "LineageSkillEffect";
    private static final Set<String> ENGINE_PACKAGES = Set.of("core", "engine");

    private EffectPackages() {}

    public static boolean isStock(String fileName) {
        var name = fileName.toLowerCase(Locale.ROOT);
        return name.startsWith(STOCK_PREFIX) && name.endsWith(".u");
    }

    public static List<Path> find(Path clientRoot) throws IOException {
        var system = child(clientRoot, "System");
        if (system == null) {
            return List.of();
        }
        List<Path> files;
        try (var list = Files.list(system)) {
            files = list.filter(file -> file.getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .endsWith(".u"))
                    .sorted()
                    .toList();
        }
        var named = namedBySkills(clientRoot, system);
        var stock = new ArrayList<Path>();
        var others = new ArrayList<Path>();
        for (var file : files) {
            var fileName = file.getFileName().toString();
            if (isStock(fileName)) {
                stock.add(file);
            } else if (named.contains(fileName.substring(0, fileName.length() - 2)) && hasEffects(file)) {
                others.add(file);
            }
        }
        stock.addAll(others);
        return List.copyOf(stock);
    }

    public static Set<String> namedBySkills(Path clientRoot) throws IOException {
        var system = child(clientRoot, "System");
        return system == null ? Set.of() : namedBySkills(clientRoot, system);
    }

    private static Set<String> namedBySkills(Path clientRoot, Path system) throws IOException {
        var file = child(system, SKILL_PACKAGE + ".u");
        var name = SKILL_PACKAGE;
        if (file == null) {
            var animations = child(clientRoot, "Animations");
            file = animations == null ? null : child(animations, SkillEffects.VISUAL_PACKAGE + ".usk");
            name = SkillEffects.VISUAL_PACKAGE;
        }
        if (file == null) {
            return Set.of();
        }
        try (var archive = new UArchive(
                name, UDecryptor.decrypt(file), new UArchiveLoader(file.getParent(), List.<USearchConfig>of()))) {
            return namedBy(archive);
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
    }

    private static boolean hasEffects(Path file) {
        var fileName = file.getFileName().toString();
        try (var archive = new UArchive(
                fileName.substring(0, fileName.length() - 2),
                UDecryptor.decrypt(file),
                new UArchiveLoader(file.getParent(), List.<USearchConfig>of()))) {
            return !EffectClasses.list(archive).isEmpty();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static Set<String> namedBy(UArchive skills) {
        var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        var imports = skills.getImportMap();
        for (var entry : imports) {
            var outer = -entry.getPackageIndex() - 1;
            if (!"Class".equalsIgnoreCase(entry.getClassName()) || outer < 0 || outer >= imports.size()) {
                continue;
            }
            var owner = imports.get(outer);
            var name = owner.getObjectName();
            if (owner.getPackageIndex() == 0
                    && !ENGINE_PACKAGES.contains(name.toLowerCase(Locale.ROOT))
                    && !name.equalsIgnoreCase(skills.getName())) {
                names.add(name);
            }
        }
        return names;
    }

    private static @Nullable Path child(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var list = Files.list(directory)) {
            return list.filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
    }
}
