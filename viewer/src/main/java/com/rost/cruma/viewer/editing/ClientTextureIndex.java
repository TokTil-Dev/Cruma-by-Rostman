package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;

public final class ClientTextureIndex implements AutoCloseable {
    private static final List<USearchConfig> SEARCH =
            List.of(new USearchConfig("Textures", "utx"), new USearchConfig("SysTextures", "utx"));

    private final Path client;
    private final Thread reader;
    private volatile List<String> textures = List.of();
    private volatile boolean reading = true;
    private volatile boolean closed;
    private String foundFor = "";
    private int foundOf = -1;
    private List<String> found = List.of();

    public ClientTextureIndex(Path client) {
        this.client = client;
        reader = new Thread(this::read, "effect-textures");
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isReading() {
        return reading;
    }

    public int getCount() {
        return textures.size();
    }

    public List<String> findMatching(String filter, int limit) {
        var known = textures;
        if (filter.equals(foundFor) && known.size() == foundOf) {
            return found;
        }
        var result = new ArrayList<String>();
        for (var texture : known) {
            if (result.size() >= limit) {
                break;
            }
            if (filter.isEmpty() || texture.toLowerCase(Locale.ROOT).contains(filter)) {
                result.add(texture);
            }
        }
        foundFor = filter;
        foundOf = known.size();
        found = List.copyOf(result);
        return found;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void read() {
        var archives = new UArchiveLoader(client, SEARCH);
        var all = new ArrayList<String>();
        try {
            for (var file : packageFiles()) {
                if (closed) {
                    return;
                }
                var name = file.getFileName().toString();
                name = name.substring(0, name.length() - ".utx".length());
                try {
                    var archive = archives.loadArchiveIfPresent(name);
                    if (archive != null) {
                        for (var export : archive.getExportMap()) {
                            if ("Texture".equals(export.getClassName())) {
                                all.add(archive.getName() + "." + export.getObjectName());
                            }
                        }
                    }
                } catch (RuntimeException unreadable) {
                    LogManager.getLogger(ClientTextureIndex.class)
                            .debug("Textures of {} unavailable: {}", name, unreadable.toString());
                } finally {
                    archives.removeArchive(name);
                }
                textures = List.copyOf(all);
            }
        } catch (IOException failure) {
            LogManager.getLogger(ClientTextureIndex.class).warn("The client's textures could not be listed", failure);
        } finally {
            reading = false;
        }
    }

    private List<Path> packageFiles() throws IOException {
        var files = new ArrayList<Path>();
        try (var folders = Files.list(client)) {
            for (var folder : folders.filter(Files::isDirectory).toList()) {
                var name = folder.getFileName().toString();
                if (name.equalsIgnoreCase("Textures") || name.equalsIgnoreCase("SysTextures")) {
                    try (var entries = Files.list(folder)) {
                        files.addAll(entries.filter(file -> file.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".utx"))
                                .toList());
                    }
                }
            }
        }
        files.sort(Comparator.comparingInt(ClientTextureIndex::rank)
                .thenComparing(file -> file.getFileName().toString().toLowerCase(Locale.ROOT)));
        return files;
    }

    private static int rank(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("fx_") || name.contains("effect")) {
            return 0;
        }
        return file.getParent().getFileName().toString().equalsIgnoreCase("Textures") ? 1 : 2;
    }
}
