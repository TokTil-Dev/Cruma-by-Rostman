package com.rost.cruma.viewer.agent.view;

import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.PackageEditDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class ActorBook {
    private static final Logger log = LogManager.getLogger(ActorBook.class);

    private final Path clientRoot;
    private final Map<String, Map<String, ActorRecord>> maps = new ConcurrentHashMap<>();

    ActorBook(Path clientRoot) {
        this.clientRoot = clientRoot;
    }

    static String mapOf(String actorPath) {
        var end = actorPath.indexOf('.');
        return end < 0 ? actorPath : actorPath.substring(0, end);
    }

    @Nullable
    ActorRecord getActor(String path) {
        return getActors(mapOf(path)).get(path);
    }

    Map<String, ActorRecord> getActors(String map) {
        return maps.computeIfAbsent(map.toLowerCase(Locale.ROOT), this::read);
    }

    private Map<String, ActorRecord> read(String map) {
        var result = new HashMap<String, ActorRecord>();
        try {
            var file = find(map);
            if (file != null) {
                for (var actor : PackageEditDocument.open(file).getActors()) {
                    result.put(actor.sourcePath(), actor);
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Could not read the actors of {}", map, failure);
        }
        return result;
    }

    private @Nullable Path find(String map) throws IOException {
        var folder = child(clientRoot, "maps");
        return folder == null ? null : child(folder, map + ".unr");
    }

    private static @Nullable Path child(Path folder, String name) throws IOException {
        try (var files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .orElse(null);
        }
    }
}
