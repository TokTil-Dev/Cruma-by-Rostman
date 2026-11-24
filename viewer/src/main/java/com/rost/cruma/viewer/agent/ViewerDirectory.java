package com.rost.cruma.viewer.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ViewerDirectory {
    private static final Logger log = LogManager.getLogger(ViewerDirectory.class);

    private static final long ASK_AGAIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final Path folder;
    private final @Nullable String version;
    private final Map<String, ViewerLink> links = new LinkedHashMap<>();
    private final Map<Path, Long> askAgain = new HashMap<>();
    private @Nullable String used;

    ViewerDirectory(Path folder) {
        this(folder, ViewerEndpoint.ownVersion());
    }

    ViewerDirectory(Path folder, @Nullable String version) {
        this.folder = folder;
        this.version = version;
    }

    public synchronized List<ViewerLink> list() {
        refresh();
        var list = new ArrayList<>(links.values());
        list.sort((a, b) -> Boolean.compare(
                b.getEndpoint().id().equals(used), a.getEndpoint().id().equals(used)));
        return list;
    }

    public synchronized ViewerLink pick(@Nullable String id) throws ToolProblem {
        refresh();
        var link = id != null ? links.get(id) : used != null ? links.get(used) : null;
        if (id != null && link == null) {
            throw new ToolProblem("There is no viewer " + id + ". Call cruma_status for the viewers that run.");
        }
        if (id == null && used != null && link == null) {
            throw new ToolProblem(
                    "The viewer you used last has closed. Call cruma_status and name a viewer, or call world_open.");
        }
        if (link == null && !links.isEmpty()) {
            link = new ArrayList<>(links.values()).getLast();
        }
        if (link == null) {
            throw new ToolProblem("""
                No world is open. Call world_open with a target such as "Giran Castle Town", or ask the person \
                to turn on Let an AI agent use this window in Cruma.\
                """);
        }
        requireSameVersion(link);
        used = link.getEndpoint().id();
        return link;
    }

    synchronized ViewerLink pickWindow(@Nullable String id) throws ToolProblem {
        refresh();
        var window = window(id);
        if (window != null) {
            requireSameVersion(window);
            return window;
        }
        throw new ToolProblem("""
            No window is open, so the person would not see this. Ask them to turn on Let an AI agent use this \
            window in Cruma, or call world_open with window visible.\
            """);
    }

    public synchronized @Nullable ViewerLink ofProcess(long pid) {
        refresh();
        return links.get("v" + pid);
    }

    public synchronized void use(ViewerLink link) {
        used = link.getEndpoint().id();
    }

    public @Nullable String getRefusal(ViewerLink link) {
        return link.getEndpoint().refuses(version);
    }

    private @Nullable ViewerLink window(@Nullable String id) {
        var named = id == null ? null : links.get(id);
        if (named != null && !named.getEndpoint().hidden()) {
            return named;
        }
        var last = used == null ? null : links.get(used);
        if (last != null && !last.getEndpoint().hidden()) {
            return last;
        }
        for (var link : links.values()) {
            if (!link.getEndpoint().hidden()) {
                return link;
            }
        }
        return null;
    }

    private void requireSameVersion(ViewerLink link) throws ToolProblem {
        var refused = getRefusal(link);
        if (refused != null) {
            throw new ToolProblem(refused);
        }
    }

    private void refresh() {
        links.values().removeIf(link -> !link.isOpen());
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (var files = Files.list(folder)) {
            var endpoints = files.filter(ViewerEndpoint::isEndpoint).toList();
            askAgain.keySet().retainAll(endpoints);
            for (var file : endpoints) {
                connect(file);
            }
        } catch (IOException unreadable) {
            log.warn("Could not read {}", folder, unreadable);
        }
    }

    private void connect(Path file) {
        var again = askAgain.get(file);
        if (again != null && System.nanoTime() - again < 0) {
            return;
        }
        try {
            var endpoint = ViewerEndpoint.read(file);
            if (links.containsKey(endpoint.id())) {
                return;
            }
            if (ProcessHandle.of(endpoint.pid()).filter(ProcessHandle::isAlive).isEmpty()) {
                Files.deleteIfExists(file);
                return;
            }
            links.put(endpoint.id(), ViewerLink.connect(endpoint));
            askAgain.remove(file);
        } catch (IOException | RuntimeException failed) {
            askAgain.put(file, System.nanoTime() + ASK_AGAIN_NANOS);
            log.info("Could not reach the viewer of {}: {}", file.getFileName(), failed.toString());
        }
    }
}
