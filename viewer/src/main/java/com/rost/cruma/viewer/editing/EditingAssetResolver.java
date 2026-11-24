package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TexturePackageWriter;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.UObject;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.world.BspMaterialMapping;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.viewer.editing.EditingSession.ImportedTexture;
import com.rost.cruma.viewer.editing.EditingSession.Rebuild;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

public final class EditingAssetResolver implements AutoCloseable {
    private final Path clientRoot;
    private final Options mipOptions;
    private final TextureQuality textureQuality;
    private final MaterialClient materialClient;
    private final float impostorDistanceMultiplier;
    private final List<Path> imports = new ArrayList<>();

    public EditingAssetResolver(
            Path clientRoot, Options mipOptions, TextureQuality textureQuality, float impostorDistanceMultiplier) {
        this.clientRoot = clientRoot;
        this.mipOptions = mipOptions;
        this.textureQuality = textureQuality;
        this.materialClient = MaterialClient.detect(clientRoot);
        this.impostorDistanceMultiplier = impostorDistanceMultiplier;
    }

    public Map<String, List<Entity>> rebuild(Rebuild change) {
        var replacements = new LinkedHashMap<String, List<Entity>>();
        for (var actor : change.affectedActors()) {
            replacements.put(actor, new ArrayList<>());
        }
        var bodies = new HashMap<String, byte[]>();
        try {
            for (var entry : change.documents().entrySet()) {
                var document = entry.getValue();
                var snapshot = document.snapshot();
                try {
                    for (var actor : change.affectedActors()) {
                        var baseline = change.baselines().get(actor);
                        if (baseline != null && document.findActor(actor).isPresent()) {
                            document.updateTransform(actor, baseline);
                        }
                    }
                    var lighting = change.lighting().get(entry.getKey());
                    bodies.put(
                            entry.getKey().toLowerCase(Locale.ROOT),
                            lighting == null ? document.previewBody() : document.previewBody(lighting));
                } finally {
                    document.restore(snapshot);
                }
            }
            var archives = new OverlayArchives(clientRoot, bodies, change.assetPackages());
            var loader = new UnrealLoader(clientRoot, mipOptions, false, textureQuality, materialClient);
            loader.setImpostorDistanceMultiplier(impostorDistanceMultiplier);
            try {
                for (var entry : change.documents().entrySet()) {
                    var paths = new LinkedHashSet<String>();
                    for (var actor : change.affectedActors()) {
                        if (entry.getValue().findActor(actor).isPresent()) {
                            paths.add(actor);
                        }
                    }
                    if (paths.isEmpty()) {
                        continue;
                    }
                    var archive = archives.loadArchive(entry.getKey());
                    if (archive == null) {
                        throw new IOException("Cannot load edited map " + entry.getKey());
                    }
                    for (var entity : loader.loadEditorActors(new UPackage(archive), paths)) {
                        var target = replacements.get(entity.getSourceActor());
                        if (target != null) {
                            target.add(entity);
                        }
                    }
                    for (var actor : paths) {
                        if (replacements.get(actor).stream().noneMatch(entity -> !entity.isWireframe())) {
                            throw new IOException(
                                    "Cannot resolve the mesh for " + actor + ". The edit was not applied.");
                        }
                    }
                }
                return replacements;
            } finally {
                loader.trimCaches();
                loader.getDxtChains().clear();
                archives.clearArchives();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public void readEffect(String actor, Consumer<List<UObject>> use) {
        var dot = actor.indexOf('.');
        if (dot <= 0) {
            use.accept(List.of());
            return;
        }
        var archives = new OverlayArchives(clientRoot, Map.of(), Map.of());
        try {
            var archive = archives.loadArchiveIfPresent(actor.substring(0, dot));
            var children = List.<UObject>of();
            if (archive != null) {
                var effects = new ArrayList<EmitterActor>();
                new UPackage(archive).loadEmitterActors(effects);
                for (var effect : effects) {
                    if (effect.getFullName().equalsIgnoreCase(actor)) {
                        children = effect.getChildEmitters();
                        break;
                    }
                }
            }
            use.accept(children);
        } finally {
            archives.clearArchives();
        }
    }

    public List<Entity> loadBsp(String map, byte[] body, Map<String, Path> assetPackages) {
        try {
            var archives = new OverlayArchives(clientRoot, Map.of(map.toLowerCase(Locale.ROOT), body), assetPackages);
            var loader = new UnrealLoader(clientRoot, mipOptions, true, textureQuality, materialClient);
            try {
                var archive = archives.loadArchive(map);
                if (archive == null) {
                    throw new IOException("Cannot load edited map " + map);
                }
                return loader.loadEditorBsp(new UPackage(archive));
            } finally {
                loader.trimCaches();
                loader.getDxtChains().clear();
                archives.clearArchives();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public List<Entity> loadBspSurfaces(
            String map, byte[] body, Map<String, Path> assetPackages, Set<Integer> surfaces) {
        try {
            var archives = new OverlayArchives(clientRoot, Map.of(map.toLowerCase(Locale.ROOT), body), assetPackages);
            var loader = new UnrealLoader(clientRoot, mipOptions, false, textureQuality, materialClient);
            try {
                var archive = archives.loadArchive(map);
                if (archive == null) {
                    throw new IOException("Cannot load edited map " + map);
                }
                return loader.loadEditorBspSurfaces(new UPackage(archive), surfaces);
            } finally {
                loader.trimCaches();
                loader.getDxtChains().clear();
                archives.clearArchives();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public int @Nullable [] getMappingSize(String material, Map<String, Path> assetPackages) {
        var archives = new OverlayArchives(clientRoot, Map.of(), assetPackages);
        try {
            return BspMaterialMapping.getSize(archives, material);
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            archives.clearArchives();
        }
    }

    public ImportedTexture importTexture(Path image, String packageName, String textureName) throws IOException {
        var probe = new OverlayArchives(clientRoot, Map.of(), Map.of());
        try {
            if (probe.loadArchiveIfPresent(packageName) != null) {
                throw new IOException("Choose a new package name. " + packageName + " already exists in this client.");
            }
        } finally {
            probe.clearArchives();
        }
        var directory = Files.createTempDirectory("cruma-editor-texture-");
        try {
            var result = TexturePackageWriter.importImage(image, directory, packageName, textureName);
            imports.add(result.packageFile());
            return new ImportedTexture(result.packageFile(), result.objectPath());
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(directory);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        for (var file : imports) {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(file.getParent());
            } catch (IOException ignored) {
                file.toFile().deleteOnExit();
            }
        }
        imports.clear();
    }

    private static final class OverlayArchives extends UArchiveLoader {
        private final Map<String, byte[]> bodies;
        private final Map<String, Path> packages = new HashMap<>();
        private final Map<String, UArchive> overlays = new HashMap<>();

        OverlayArchives(Path root, Map<String, byte[]> bodies, Map<String, Path> packages) {
            super(
                    root,
                    List.of(
                            new USearchConfig("Maps", "unr"),
                            new USearchConfig("StaticMeshes", "usx"),
                            new USearchConfig("Textures", "utx"),
                            new USearchConfig("SysTextures", "utx"),
                            new USearchConfig("Animations", "ukx"),
                            new USearchConfig("System", "u"),
                            new USearchConfig("Sounds", "uax")));
            this.bodies = bodies;
            packages.forEach((name, path) -> this.packages.put(name.toLowerCase(Locale.ROOT), path));
        }

        @Override
        public UArchive loadArchive(String name) {
            return loadOverlay(name, false);
        }

        @Override
        public UArchive loadArchiveIfPresent(String name) {
            return loadOverlay(name, true);
        }

        @Override
        public void clearArchives() {
            overlays.values().forEach(UArchive::close);
            overlays.clear();
            super.clearArchives();
        }

        private UArchive loadOverlay(String name, boolean optional) {
            var key = name.toLowerCase(Locale.ROOT);
            var found = overlays.get(key);
            if (found != null) {
                return found;
            }
            var body = bodies.get(key);
            var file = packages.get(key);
            if (body == null && file == null) {
                return optional ? super.loadArchiveIfPresent(name) : super.loadArchive(name);
            }
            var bytes = body == null
                    ? UDecryptor.decrypt(file)
                    : ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
            var archive = new UArchive(name, bytes, this);
            overlays.put(key, archive);
            return archive;
        }
    }
}
