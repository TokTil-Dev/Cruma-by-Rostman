package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.BspLightmapRebuild;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.TerrainActorShadows;
import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.texture.DxtMipCodec;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.Entity.TextureFormat;
import com.rost.cruma.core.world.UnrealLoader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class EditingLightSurfaces {
    private final Map<String, int[]> savedSteadyLight = new ConcurrentHashMap<>();

    public record TerrainLight(String owner, Path map, Info info, short[] samples, int @Nullable [] rgb) {}

    public record Result(
            Map<Integer, Texture> lightmaps,
            Map<Integer, Texture> saved,
            List<TerrainLight> terrain,
            long bspNanos,
            long terrainNanos) {
        public Result {
            lightmaps = Map.copyOf(lightmaps);
            saved = Map.copyOf(saved);
            terrain = List.copyOf(terrain);
        }
    }

    static Texture lightmapTexture(int width, int height, byte[] texels) {
        return new Texture(
                TextureFormat.TEXTURE_DXT1,
                width,
                height,
                DxtMipCodec.buildChain(TextureFormat.TEXTURE_DXT1, width, height, texels),
                false);
    }

    Result rebuild(
            PackageEditDocument document,
            UArchiveLoader archives,
            Path clientRoot,
            Settings settings,
            BooleanSupplier cancelled)
            throws IOException {
        var started = System.nanoTime();
        var lightmaps = new LinkedHashMap<Integer, Texture>();
        var saved = new LinkedHashMap<Integer, Texture>();
        var options = BspLightmapRebuild.Options.preview();
        if (BspLightmapRebuild.isNeeded(document, List.of(), options)) {
            var rebuilt = BspLightmapRebuild.rebuild(
                    document, archives, settings, List.of(), options, (done, total) -> {}, cancelled);
            for (var page : rebuilt.pages()) {
                lightmaps.put(page.key(), lightmapTexture(page.width(), page.height(), page.texels()));
                saved.put(page.key(), lightmapTexture(page.width(), page.height(), page.saved()));
            }
        }
        var bspNanos = System.nanoTime() - started;
        started = System.nanoTime();
        var terrain = terrain(document, archives, clientRoot, cancelled);
        return new Result(lightmaps, saved, terrain, bspNanos, System.nanoTime() - started);
    }

    private List<TerrainLight> terrain(
            PackageEditDocument document, UArchiveLoader archives, Path clientRoot, BooleanSupplier cancelled)
            throws IOException {
        TerrainPackageSnapshot snapshot;
        try {
            snapshot = TerrainPackageSnapshot.open(document.getSource(), clientRoot);
        } catch (IOException noTerrain) {
            return List.of();
        }
        var owners = new ArrayList<Terrain>();
        for (var terrain : snapshot.getTerrains()) {
            if (isLit(terrain)) {
                owners.add(terrain);
            }
        }
        if (owners.isEmpty()) {
            return List.of();
        }
        var body = document.previewBody();
        if (TerrainActorShadows.isNeeded(document, false)) {
            var shadows = TerrainActorShadows.rebuild(
                    document, clientRoot, archives, Set.of(), false, (done, total) -> {}, cancelled);
            if (shadows.changes() != null) {
                body = document.previewBody(shadows.changes());
            }
        }
        if (cancelled.getAsBoolean()) {
            return List.of();
        }
        var loader = new UnrealLoader(clientRoot, new Options(false, 0), false);
        var result = new ArrayList<TerrainLight>();
        try {
            var saved = savedSteadyLight(document, archives, loader, owners);
            try (var edited = new UArchive(
                    mapName(document.getSource()), ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), archives)) {
                var editedPackage = new UPackage(edited);
                for (var terrain : owners) {
                    var rgb = loader.loadTerrainSteadyLight(editedPackage, terrain.path());
                    var samples = new TerrainHeightField(
                                    terrain.heightmap().getFirst(),
                                    terrain.info().toWorld())
                            .copySamples();
                    result.add(new TerrainLight(
                            terrain.path(),
                            document.getSource(),
                            terrain.info(),
                            samples,
                            Arrays.equals(saved.get(terrain.path()), rgb) ? null : rgb));
                }
            }
        } finally {
            loader.trimCaches();
        }
        return result;
    }

    private Map<String, int[]> savedSteadyLight(
            PackageEditDocument document, UArchiveLoader archives, UnrealLoader loader, List<Terrain> owners)
            throws IOException {
        var prefix = document.getSource().toAbsolutePath().normalize() + "|";
        var result = new LinkedHashMap<String, int[]>();
        var missing = new ArrayList<Terrain>();
        for (var terrain : owners) {
            var saved = savedSteadyLight.get(prefix + terrain.path());
            if (saved == null) {
                missing.add(terrain);
            } else {
                result.put(terrain.path(), saved);
            }
        }
        if (!missing.isEmpty()) {
            var body = PackageEditDocument.open(document.getSource()).previewBody();
            try (var archive = new UArchive(
                    mapName(document.getSource()), ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), archives)) {
                var source = new UPackage(archive);
                for (var terrain : missing) {
                    var saved = loader.loadTerrainSteadyLight(source, terrain.path());
                    savedSteadyLight.put(prefix + terrain.path(), saved);
                    result.put(terrain.path(), saved);
                }
            }
        }
        return result;
    }

    private static boolean isLit(Terrain terrain) {
        for (var sector : terrain.sectors()) {
            if (!sector.lights().isEmpty() || sector.hasTimeMaps() != 0) {
                return true;
            }
        }
        return false;
    }

    private static String mapName(Path map) {
        var name = map.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
