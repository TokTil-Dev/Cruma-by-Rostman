package com.rost.cruma.viewer.agent.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.spawn.SpawnMaker;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.HubTool;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.spawn.SpawnAccess;
import com.rost.cruma.viewer.spawn.SpawnBookmarks;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

final class SpawnFiles {
    private static @Nullable SpawnFiles last;

    private final Path folder;
    private final @Nullable Path client;
    private final SpawnSource source = new SpawnSource();
    private final NpcDataSource npcData = new NpcDataSource();
    private final SpawnBookmarks bookmarks;

    private SpawnFiles(Path folder, @Nullable Path client, Path configDirectory) {
        this.folder = folder;
        this.client = client;
        bookmarks = new SpawnBookmarks(configDirectory.resolve("spawn-bookmarks.txt"));
        source.open(folder);
        npcData.open(folder);
        npcData.openClient(client);
    }

    @FunctionalInterface
    interface Call {
        Step start(ToolArgs args, Desk desk) throws ToolProblem;
    }

    static HubTool alone(Call call) {
        return (args, hub, job) -> run(args, hub, job, call);
    }

    static Path folder(String text) throws ToolProblem {
        try {
            var path = Path.of(text);
            var resolved = path.isAbsolute() ? SpawnSource.resolveFolder(path) : null;
            if (resolved == null) {
                throw new ToolProblem(
                        "scripts must be the full path of a folder with npcpos.txt, or of a folder that holds script.");
            }
            return resolved;
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("scripts is not a path: " + text);
        }
    }

    static @Nullable Path configured(AgentHub hub, Path client) {
        try {
            var spawns = hub.getConfigFor(client).viewer().world().spawns();
            var path = spawns == null ? null : spawns.scriptPath();
            return path == null || path.toString().isEmpty() || !Files.isDirectory(path)
                    ? null
                    : SpawnSource.resolveFolder(path);
        } catch (RuntimeException unreadable) {
            LogManager.getLogger(SpawnFiles.class).info("No script folder for {}: {}", client, unreadable.toString());
            return null;
        }
    }

    static Target locate(AgentHub hub, String text) throws ToolProblem {
        var target = Target.parse(text);
        return switch (target.kind()) {
            case POINT, TILE -> target;
            case NAME -> {
                var place = Target.place(hub.getConfig().viewer().camera().locations(), text)
                        .orElseThrow(() -> new ToolProblem("There is no place named \"" + text
                                + "\". Call place_find, or give a point or a tile."));
                yield new Target(Kind.POINT, text, place.x(), place.y(), place.z());
            }
            default -> throw new ToolProblem(text + " needs an open world. Give a place, a tile or a point.");
        };
    }

    private boolean hasChanged() {
        return source.hasChangedOnDisk() || npcData.hasChangedOnDisk();
    }

    private void close() {
        source.close();
        npcData.close();
    }

    private Desk desk(AgentHub hub) {
        return new Desk(access(), folder, text -> locate(hub, text), true, hub.getAccess());
    }

    private SpawnAccess access() {
        return new SpawnAccess() {
            @Override
            public SpawnSource getSource() {
                return source;
            }

            @Override
            public NpcDataSource getNpcData() {
                return npcData;
            }

            @Override
            public boolean isHeld() {
                return false;
            }

            @Override
            public boolean isNpcHeld() {
                return false;
            }

            @Override
            public List<String> getBookmarks() {
                try {
                    return bookmarks.load(folder);
                } catch (IOException unreadable) {
                    return List.of();
                }
            }

            @Override
            public boolean bookmark(SpawnMaker maker, boolean on) {
                return false;
            }

            @Override
            public boolean hasGeodata() {
                return false;
            }

            @Override
            public @Nullable GeoDataAccess readArea(double minX, double minY, double maxX, double maxY) {
                return null;
            }

            @Override
            public double findClientFloor(double x, double y, double z) {
                return Double.NaN;
            }

            @Override
            public @Nullable Look look(int npcId) {
                return null;
            }
        };
    }

    private static ToolResult run(ToolArgs args, AgentHub hub, Job job, Call call)
            throws ToolProblem, InterruptedException {
        var files = files(args, hub);
        synchronized (files) {
            job.setProgress("Reading the script folder " + files.folder);
            var step = call.start(args, files.desk(hub));
            while (true) {
                files.source.poll();
                files.npcData.poll();
                var result = step.step();
                if (result != null) {
                    return result;
                }
                if (job.isCancelled()) {
                    step.cancel();
                    throw new InterruptedException();
                }
                Thread.sleep(2);
            }
        }
    }

    private static synchronized SpawnFiles files(ToolArgs args, AgentHub hub) throws ToolProblem {
        var asked = args.getText("scripts");
        var folder = asked == null ? null : folder(asked);
        Path client = null;
        for (var known : hub.getClients()) {
            var configured = configured(hub, known);
            if (configured != null && (folder == null || configured.equals(folder))) {
                folder = configured;
                client = known;
                break;
            }
        }
        if (folder == null) {
            throw new ToolProblem(
                    "No viewer is open and no script folder is known. Pass scripts with the server's script folder, or"
                            + " call world_open.");
        }
        if (last == null || !last.folder.equals(folder) || !Objects.equals(last.client, client) || last.hasChanged()) {
            if (last != null) {
                var old = last;
                synchronized (old) {
                    old.close();
                }
            }
            last = new SpawnFiles(folder, client, hub.getConfigDirectory());
        }
        return last;
    }
}
