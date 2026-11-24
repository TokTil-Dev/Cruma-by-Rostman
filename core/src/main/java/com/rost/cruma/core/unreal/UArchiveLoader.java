package com.rost.cruma.core.unreal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UArchiveLoader {
    private static final Set<String> REPORTED_MISSING_ARCHIVES = ConcurrentHashMap.newKeySet();
    private static final int MOST_CLASS_PARENTS = 32;
    private static final Map<String, Boolean> EMITTER_CLASSES = new ConcurrentHashMap<>();
    private static final Set<String> OTHER_ENGINE_CLASSES = Set.of(
            "level",
            "model",
            "polys",
            "brush",
            "staticmeshactor",
            "movablestaticmeshactor",
            "l2movablestaticmeshactor",
            "mover",
            "light",
            "nmovablesunlight",
            "zoneinfo",
            "levelinfo",
            "skyzoneinfo",
            "l2foginfo",
            "terraininfo",
            "terrainsector",
            "blockingvolume",
            "physicsvolume",
            "watervolume",
            "musicvolume",
            "ambientvolume",
            "ambientsoundobject",
            "ambientsound",
            "playerstart",
            "antiportalactor",
            "projector",
            "spriteemitter",
            "meshemitter",
            "beamemitter",
            "trailemitter",
            "sparkemitter",
            "vertmeshemitter",
            "staticmesh",
            "texture",
            "shader",
            "sound");

    private static final Logger log = LogManager.getLogger(UArchiveLoader.class);

    private final Path rootPath;
    private final List<USearchConfig> configs;
    private final Map<String, UArchive> archives = new HashMap<>();
    private final Map<Path, Map<String, Path>> directoryEntries = new HashMap<>();
    private final Set<String> missingArchives = new HashSet<>();
    private final Map<String, List<UProperty>> classDefaults = new HashMap<>();
    private final Map<String, List<ClassLink>> emitterChains = new HashMap<>();
    private final Map<String, List<StructLayouts.Field>> structLayouts = new HashMap<>();
    private final Map<String, String> arrayElementStructs = new HashMap<>();
    private final @Nullable DecryptedPackages sharedPackages;

    public UArchiveLoader(Path rootPath, List<USearchConfig> configs) {
        this(rootPath, configs, null);
    }

    public UArchiveLoader(Path rootPath, List<USearchConfig> configs, @Nullable DecryptedPackages sharedPackages) {
        this.rootPath = rootPath;
        this.configs = configs;
        this.sharedPackages = sharedPackages;
    }

    public record USearchConfig(String directory, String extension) {}

    public static EmitterClassTest emitterClassTest(Path clientRoot) {
        return new EmitterClassTest(clientRoot);
    }

    public static final class EmitterClassTest implements AutoCloseable {
        private final Path clientRoot;
        private final String clientKey;
        private @Nullable UArchiveLoader loader;

        private EmitterClassTest(Path clientRoot) {
            this.clientRoot = clientRoot;
            clientKey = clientRoot.toAbsolutePath().normalize() + "|";
        }

        public boolean isEmitterClass(String packageName, String className) {
            var engine = packageName.equalsIgnoreCase("Engine");
            if (engine && className.equalsIgnoreCase("Emitter")) {
                return true;
            }
            if (engine && OTHER_ENGINE_CLASSES.contains(className.toLowerCase(Locale.ROOT))) {
                return false;
            }
            var key = clientKey + (packageName + "." + className).toLowerCase(Locale.ROOT);
            var known = EMITTER_CLASSES.get(key);
            if (known == null) {
                if (loader == null) {
                    loader = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
                }
                known = !loader.findEmitterChain(packageName, className).isEmpty();
                EMITTER_CLASSES.put(key, known);
            }
            return known;
        }

        @Override
        public void close() {
            if (loader != null) {
                loader.clearArchives();
                loader = null;
            }
        }
    }

    public @Nullable UArchive loadArchive(String name) {
        return loadArchive(name, true);
    }

    public @Nullable UArchive loadArchiveIfPresent(String name) {
        return loadArchive(name, false);
    }

    public List<UProperty> findClassDefaults(String packageName, String className) {
        var key = (packageName + "." + className).toLowerCase(Locale.ROOT);
        var known = classDefaults.get(key);
        if (known == null) {
            try {
                var archive = loadArchiveIfPresent(packageName);
                known = archive == null ? List.of() : List.copyOf(ClassDefaults.findAll(archive, className));
            } catch (RuntimeException unreadable) {
                log.debug("No class defaults of {}: {}", key, unreadable.toString());
                known = List.of();
            }
            classDefaults.put(key, known);
        }
        return known;
    }

    public List<ClassLink> findEmitterChain(String packageName, String className) {
        var key = (packageName + "." + className).toLowerCase(Locale.ROOT);
        var known = emitterChains.get(key);
        if (known == null) {
            known = walkEmitterChain(packageName, className);
            emitterChains.put(key, known);
        }
        return known;
    }

    public record ClassLink(String packageName, String className) {}

    public @Nullable List<StructLayouts.Field> findStructLayout(String structName) {
        var key = structName.toLowerCase(Locale.ROOT);
        var known = structLayouts.get(key);
        if (known == null) {
            known = readStructLayout(structName);
            structLayouts.put(key, known);
        }
        return known.isEmpty() ? null : known;
    }

    public @Nullable String findArrayElementStruct(String arrayName) {
        var key = arrayName.toLowerCase(Locale.ROOT);
        var known = arrayElementStructs.get(key);
        if (known == null) {
            try {
                var engine = loadArchiveIfPresent("Engine");
                known = engine == null ? null : StructLayouts.readElementStruct(engine, arrayName);
            } catch (RuntimeException unreadable) {
                log.debug("No element struct of {}: {}", arrayName, unreadable.toString());
            }
            known = known == null ? "" : known;
            arrayElementStructs.put(key, known);
        }
        return known.isEmpty() ? null : known;
    }

    public UArchive createMemoryArchive(String name, ByteBuffer body) {
        return new UArchive(name, body, this);
    }

    public @Nullable UArchive replaceArchive(String name, UArchive archive) {
        missingArchives.remove(name);
        return archives.put(name, archive);
    }

    public void restoreArchive(String name, @Nullable UArchive previous) {
        if (previous == null) {
            archives.remove(name);
        } else {
            archives.put(name, previous);
        }
    }

    public void removeArchive(String name) {
        var removed = archives.remove(name);
        if (removed != null) {
            removed.close();
        }
    }

    public int getArchiveCount() {
        return archives.size();
    }

    public void clearArchives() {
        for (var archive : archives.values()) {
            archive.close();
        }
        archives.clear();
    }

    private @Nullable UArchive loadArchive(String name, boolean warnIfMissing) {
        var archive = archives.get(name);
        if (archive != null) {
            return archive;
        }
        if (missingArchives.contains(name)) {
            reportMissing(name, warnIfMissing);
            return null;
        }

        for (var config : configs) {
            var directory = rootPath.resolve(config.directory());
            if (!Files.isDirectory(directory)) {
                directory = caseInsensitiveEntry(rootPath, config.directory());
            }
            if (directory == null) {
                continue;
            }
            var filename = name + "." + config.extension();
            var path = directory.resolve(filename);
            if (!Files.isRegularFile(path)) {
                path = caseInsensitiveEntry(directory, filename);
            }
            if (path != null && Files.isRegularFile(path)) {
                log.debug("Loading archive package: {}", name);
                archive = loadArchive(name, path);
                archives.put(name, archive);
                return archive;
            }
        }

        missingArchives.add(name);
        reportMissing(name, warnIfMissing);
        return null;
    }

    private List<StructLayouts.Field> readStructLayout(String structName) {
        var fields = new ArrayList<StructLayouts.Field>();
        var name = structName;
        try {
            for (var parents = 0; name != null; parents++) {
                StructLayouts.Layout layout = null;
                for (var packageName : List.of("Engine", "Core")) {
                    var archive = loadArchiveIfPresent(packageName);
                    layout = archive == null ? null : StructLayouts.read(archive, name);
                    if (layout != null) {
                        break;
                    }
                }
                if (layout == null || parents == MOST_CLASS_PARENTS) {
                    return List.of();
                }
                fields.addAll(layout.fields());
                name = layout.parent();
            }
        } catch (RuntimeException unreadable) {
            log.debug("No layout of struct {}: {}", structName, unreadable.toString());
            return List.of();
        }
        return List.copyOf(fields);
    }

    private List<ClassLink> walkEmitterChain(String packageName, String className) {
        var chain = new ArrayList<ClassLink>();
        var at = new ClassLink(packageName, className);
        for (var steps = 0; steps < MOST_CLASS_PARENTS; steps++) {
            chain.add(at);
            if (at.packageName().equalsIgnoreCase("Engine") && at.className().equalsIgnoreCase("Emitter")) {
                return List.copyOf(chain);
            }
            if (at.packageName().equalsIgnoreCase("Core")) {
                return List.of();
            }
            var parent = findParentClass(at);
            if (parent == null) {
                return List.of();
            }
            at = parent;
        }
        return List.of();
    }

    private @Nullable ClassLink findParentClass(ClassLink link) {
        UArchive archive;
        try {
            archive = loadArchiveIfPresent(link.packageName());
        } catch (RuntimeException unreadable) {
            log.debug("No class package {}: {}", link.packageName(), unreadable.toString());
            return null;
        }
        if (archive == null) {
            return null;
        }
        var index = EffectClasses.classIndex(archive, link.className());
        if (index == 0) {
            return null;
        }
        var export = archive.getExportMap().get(index - 1);
        var savedOffset = archive.getOffset();
        var savedEnd = archive.getObjectEnd();
        int parent;
        try {
            archive.setPosition(export.getSerialOffset());
            archive.setObjectEnd(export.getSerialOffset() + export.getSerialSize());
            parent = archive.readIndex();
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            archive.setObjectEnd(savedEnd);
            archive.setPosition(savedOffset);
        }
        if (parent > 0 && parent <= archive.getExportMap().size()) {
            var local = archive.getExportMap().get(parent - 1);
            return "None".equals(local.getClassName())
                    ? new ClassLink(link.packageName(), local.getObjectName())
                    : null;
        }
        var imports = archive.getImportMap();
        if (parent >= 0 || -(long) parent > imports.size()) {
            return null;
        }
        var imported = imports.get(-parent - 1);
        if (!"Class".equalsIgnoreCase(imported.getClassName())) {
            return null;
        }
        var outer = imported;
        for (var steps = 0; outer.getPackageIndex() != 0 && steps < imports.size(); steps++) {
            var next = -(long) outer.getPackageIndex() - 1;
            if (next < 0 || next >= imports.size()) {
                return null;
            }
            outer = imports.get((int) next);
        }
        return outer == imported ? null : new ClassLink(outer.getObjectName(), imported.getObjectName());
    }

    private @Nullable Path caseInsensitiveEntry(Path directory, String name) {
        var entries = directoryEntries.computeIfAbsent(directory, source -> {
            var result = new HashMap<String, Path>();
            try (var children = Files.list(source)) {
                children.sorted()
                        .forEach(path ->
                                result.putIfAbsent(path.getFileName().toString().toLowerCase(Locale.ROOT), path));
            } catch (IOException failure) {
                log.debug("Cannot list package directory: {}", source, failure);
            }
            return result;
        });
        return entries.get(name.toLowerCase(Locale.ROOT));
    }

    private void reportMissing(String name, boolean warnIfMissing) {
        if (!warnIfMissing) {
            return;
        }
        var normalizedName = name.toLowerCase(Locale.ROOT);
        var reportKey = rootPath.toAbsolutePath().normalize() + "\0" + normalizedName;
        if (REPORTED_MISSING_ARCHIVES.add(reportKey)) {
            log.warn(
                    "Archive package not found: {} " + "(identical misses from parallel loaders will be DEBUG-only)",
                    name);
        } else {
            log.debug("Repeated missing archive package: {}", name);
        }
    }

    private UArchive loadArchive(String name, Path path) {
        var decrypted = sharedPackages == null ? UDecryptor.decryptManaged(path) : sharedPackages.open(path);
        final UArchive archive;
        try {
            archive = new UArchive(name, decrypted, this);
        } catch (Throwable failure) {
            decrypted.close();
            throw failure;
        }
        var header = archive.getHeader();
        log.debug(
                "Loaded archive package: {} (file ver: {}, license ver: {})",
                name,
                header.getFileVersion(),
                header.getLicenseVersion());
        return archive;
    }
}
