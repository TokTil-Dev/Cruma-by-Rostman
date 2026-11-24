package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.UserPaths;
import com.rost.cruma.viewer.launch.ClientInstallation;
import com.rost.cruma.viewer.launch.MapPreviewCache;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record ClientFolderStatus(String text, int dot) {
    public static final ClientFolderStatus NONE = new ClientFolderStatus("", 0);

    public static final List<String> LONGEST =
            List.of("No npcpos or npcdata", "No region files here", "Folder not found", "9999 regions");

    public static ClientFolderStatus ofGeodata(@Nullable Path folder) {
        if (folder == null) {
            return NONE;
        }
        if (!Files.isDirectory(folder)) {
            return new ClientFolderStatus("Folder not found", GeodataWidgets.WARNING);
        }
        try (var files = Files.list(folder)) {
            var count = files.filter(
                            file -> GeoFormat.parse(file.getFileName().toString()) != null && Files.isRegularFile(file))
                    .count();
            return count == 0
                    ? new ClientFolderStatus("No region files here", GeodataWidgets.WARNING)
                    : new ClientFolderStatus(count + (count == 1 ? " region" : " regions"), GeodataWidgets.OPEN);
        } catch (IOException | RuntimeException unreadable) {
            return new ClientFolderStatus("Folder not found", GeodataWidgets.WARNING);
        }
    }

    public static ClientFolderStatus ofScripts(@Nullable Path folder) {
        if (folder == null) {
            return NONE;
        }
        if (!Files.isDirectory(folder)) {
            return new ClientFolderStatus("Folder not found", GeodataWidgets.WARNING);
        }
        var resolved = SpawnSource.resolveFolder(folder);
        var positions = resolved != null && SpawnSource.find(resolved, SpawnSource.POSITIONS) != null;
        var npcs = resolved != null && SpawnSource.find(resolved, SpawnSource.NPCS) != null;
        if (positions && npcs) {
            return new ClientFolderStatus("npcpos, npcdata", GeodataWidgets.OPEN);
        }
        return new ClientFolderStatus(
                positions ? "No npcdata" : npcs ? "No npcpos" : "No npcpos or npcdata", GeodataWidgets.WARNING);
    }

    public static ClientFolderStatus ofMapImage(boolean ready) {
        return ready ? new ClientFolderStatus("Ready", GeodataWidgets.OPEN) : new ClientFolderStatus("Not made yet", 0);
    }

    public static boolean isMapImageReady(@Nullable Path client, WorldVariant world, Path configDirectory) {
        if (client == null) {
            return false;
        }
        try {
            return new MapPreviewCache(configDirectory, ClientInstallation.inspect(client, world)).isReady();
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    public static @Nullable Path resolve(String typed, Path configDirectory) {
        if (typed.isBlank()) {
            return null;
        }
        try {
            return UserPaths.resolve(typed, configDirectory);
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }
}
