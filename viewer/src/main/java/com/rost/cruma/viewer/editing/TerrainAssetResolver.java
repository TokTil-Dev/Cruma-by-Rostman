package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainEditorGeometry;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.unreal.UPackage;
import com.rost.cruma.core.world.MaterialClient;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.DecorationTemplate;
import java.nio.file.Path;
import java.util.List;

public final class TerrainAssetResolver {
    private final Path root;
    private final Options options;
    private final TextureQuality quality;

    public TerrainAssetResolver(Path root, Options options, TextureQuality quality) {
        this.root = root;
        this.options = options;
        this.quality = quality;
    }

    public record Loaded(TerrainEditorGeometry geometry, List<DecorationTemplate> decorations) {
        public Loaded {
            decorations = List.copyOf(decorations);
        }
    }

    public TerrainEditorGeometry load(Path map, String owner, Info info) {
        return load(map, owner, info, false).geometry();
    }

    public Loaded loadWithDecorations(Path map, String owner, Info info) {
        return load(map, owner, info, true);
    }

    private Loaded load(Path map, String owner, Info info, boolean decorations) {
        var archives = new UArchiveLoader(
                root,
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx"),
                        new USearchConfig("System", "u")));
        var loader = new UnrealLoader(root, options, false, quality, MaterialClient.detect(root));
        var name = map.getFileName().toString();
        name = name.substring(0, name.lastIndexOf('.'));
        try (var archive = new UArchive(name, UDecryptor.decrypt(map), archives)) {
            var uPackage = new UPackage(archive);
            var geometry = loader.loadEditorTerrain(uPackage, owner, info);
            return new Loaded(geometry, decorations ? loader.loadEditorDecorations(uPackage, owner) : List.of());
        } finally {
            loader.trimCaches();
            loader.getDxtChains().clear();
            archives.clearArchives();
        }
    }
}
