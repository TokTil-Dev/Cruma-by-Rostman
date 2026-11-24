package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UDecryptor;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LightingArchives extends UArchiveLoader {
    private final Map<String, Path> packages = new HashMap<>();
    private final Map<String, UArchive> overlays = new HashMap<>();

    LightingArchives(Path clientRoot, Map<String, Path> supplied) {
        super(
                clientRoot,
                List.of(
                        new USearchConfig("Maps", "unr"),
                        new USearchConfig("StaticMeshes", "usx"),
                        new USearchConfig("Textures", "utx"),
                        new USearchConfig("SysTextures", "utx"),
                        new USearchConfig("Animations", "ukx"),
                        new USearchConfig("System", "u"),
                        new USearchConfig("Sounds", "uax")));
        supplied.forEach((name, path) -> packages.put(name.toLowerCase(Locale.ROOT), path));
    }

    static Map<String, Path> byPackageName(List<Path> files) {
        var result = new HashMap<String, Path>();
        for (var file : files) {
            var name = file.getFileName().toString();
            var dot = name.lastIndexOf('.');
            result.put(dot < 0 ? name : name.substring(0, dot), file);
        }
        return result;
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
        var file = packages.get(key);
        if (file == null) {
            return optional ? super.loadArchiveIfPresent(name) : super.loadArchive(name);
        }
        var archive = new UArchive(name, UDecryptor.decrypt(file), this);
        overlays.put(key, archive);
        return archive;
    }
}
