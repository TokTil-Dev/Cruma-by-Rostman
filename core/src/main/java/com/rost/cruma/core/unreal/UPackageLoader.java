package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public class UPackageLoader {
    private final UArchiveLoader archiveLoader;

    public UPackageLoader(Path rootPath, List<USearchConfig> configs) {
        this(rootPath, configs, null);
    }

    public UPackageLoader(Path rootPath, List<USearchConfig> configs, @Nullable DecryptedPackages sharedPackages) {
        this.archiveLoader = new UArchiveLoader(rootPath, configs, sharedPackages);
    }

    public Optional<UPackage> loadPackage(String name) {
        var archive = archiveLoader.loadArchive(name);
        return archive == null ? Optional.empty() : Optional.of(new UPackage(archive));
    }

    public Optional<UPackage> loadPackageIfPresent(String name) {
        var archive = archiveLoader.loadArchiveIfPresent(name);
        return archive == null ? Optional.empty() : Optional.of(new UPackage(archive));
    }

    public UArchiveLoader getArchiveLoader() {
        return archiveLoader;
    }

    public void removePackage(String name) {
        archiveLoader.removeArchive(name);
    }

    public int getPackageCount() {
        return archiveLoader.getArchiveCount();
    }

    public void clearPackages() {
        archiveLoader.clearArchives();
    }
}
