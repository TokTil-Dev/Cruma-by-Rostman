package com.rost.cruma.viewer.agent.geo;

import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.PathNodeGraph;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.geodata.GeodataBuildJob.StartSources;
import com.rost.cruma.viewer.spawn.SpawnSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class GeoSteps {
    static final int CELL = 16;
    static final int REGION = GeoTile.WORLD_SIZE;
    static final int STEPS_SHOWN = 20;
    static final long BUILD_POLL_MILLIS = 200;
    static final Pattern TILE = Pattern.compile("(\\d{1,2})_(\\d{1,2})");
    static final int MOST_GRAPH_RADIUS = 4096;

    private static final long HOLD_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int SCAN_MARGIN = 512;

    private GeoSteps() {}

    @FunctionalInterface
    interface Next<T> {
        Step start(T value) throws ToolProblem;
    }

    @FunctionalInterface
    interface Start {
        Step start() throws ToolProblem;
    }

    static Bounds scanArea(ToolArgs args, ViewerContext viewer, Target target) throws ToolProblem {
        var radius = args.getInteger("radius", 0);
        var to = args.getText("to");
        if (radius < 0 || radius > MOST_GRAPH_RADIUS) {
            throw new ToolProblem("radius must be from 1 to " + MOST_GRAPH_RADIUS + " units.");
        }
        if (to != null) {
            var corner = viewer.locate(to);
            return GeoEdits.between(target.x(), target.y(), corner.x(), corner.y());
        }
        var half = Math.ceilDiv(radius > 0 ? radius : GeoScans.RADIUS, CELL) * CELL;
        var x = GeoQueryEngine.cellMinimum(target.x());
        var y = GeoQueryEngine.cellMinimum(target.y());
        return new Bounds(x - half, y - half, x + half, y + half);
    }

    static Bounds grown(Bounds area) {
        return new Bounds(
                area.minX() - SCAN_MARGIN,
                area.minY() - SCAN_MARGIN,
                area.maxX() + SCAN_MARGIN,
                area.maxY() + SCAN_MARGIN);
    }

    static Path otherFolder(String from, Path folder) throws ToolProblem {
        var other = Path.of(from).toAbsolutePath().normalize();
        if (!Files.isDirectory(other)) {
            throw new ToolProblem("There is no folder " + other + ".");
        }
        if (other.equals(folder)) {
            throw new ToolProblem("from is the folder of this viewer. Give another folder as B.");
        }
        return other;
    }

    static Step loaded(CompletableFuture<GeoDataSnapshot> load, Next<GeoDataSnapshot> next) {
        return new Step() {
            private @Nullable Step following;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (following == null) {
                    if (!load.isDone()) {
                        return null;
                    }
                    try {
                        following = next.start(load.join());
                    } catch (CompletionException failed) {
                        throw new ToolProblem("The geodata cannot be read: "
                                + failed.getCause().getMessage());
                    }
                }
                return following.step();
            }

            @Override
            public void cancel() {
                if (following != null) {
                    following.cancel();
                }
            }
        };
    }

    static <T> Step afterWorker(Callable<T> work, Next<T> next) {
        var future = new CompletableFuture<T>();
        var thread = Thread.ofPlatform().name("cruma-agent-geodata").daemon().start(() -> {
            try {
                future.complete(work.call());
            } catch (Exception | Error failure) {
                future.completeExceptionally(failure);
            }
        });
        return new Step() {
            private @Nullable Step following;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (following == null) {
                    if (!future.isDone()) {
                        return null;
                    }
                    try {
                        following = next.start(future.join());
                    } catch (CompletionException failed) {
                        if (failed.getCause() instanceof ToolProblem problem) {
                            throw problem;
                        }
                        throw new ToolProblem(
                                "The geodata work failed: " + failed.getCause().getMessage());
                    }
                }
                return following.step();
            }

            @Override
            public void cancel() {
                if (following != null) {
                    following.cancel();
                } else {
                    thread.interrupt();
                }
            }
        };
    }

    static Step afterHold(ViewerContext viewer, Start next) {
        var deadline = System.nanoTime() + HOLD_NANOS;
        return new Step() {
            private @Nullable Step following;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (following == null) {
                    if (viewer.system().doesGeodataHoldEdits()) {
                        if (System.nanoTime() > deadline) {
                            throw new ToolProblem(
                                    "The person is still painting or saving geodata. Try again in a moment.");
                        }
                        return null;
                    }
                    following = next.start();
                }
                return following.step();
            }

            @Override
            public void cancel() {
                if (following != null) {
                    following.cancel();
                }
            }
        };
    }

    static boolean isInterrupted() {
        return Thread.currentThread().isInterrupted();
    }

    static Bounds around(GeoSpot a, GeoSpot b, int margin) {
        return around(List.of(a, b), margin);
    }

    static Bounds around(List<GeoSpot> spots, int margin) {
        var minX = Double.MAX_VALUE;
        var minY = Double.MAX_VALUE;
        var maxX = -Double.MAX_VALUE;
        var maxY = -Double.MAX_VALUE;
        for (var spot : spots) {
            minX = Math.min(minX, spot.x());
            minY = Math.min(minY, spot.y());
            maxX = Math.max(maxX, spot.x());
            maxY = Math.max(maxY, spot.y());
        }
        return new Bounds(
                (int) Math.floor(minX) - margin,
                (int) Math.floor(minY) - margin,
                (int) Math.ceil(maxX) + margin + 1,
                (int) Math.ceil(maxY) + margin + 1);
    }

    static Bounds regionBounds(GeoTile tile) {
        return new Bounds(tile.worldMinX(), tile.worldMinY(), tile.worldMinX() + REGION, tile.worldMinY() + REGION);
    }

    static void requireServer(boolean pathNodes, @Nullable ZoneTable table, Path folder) throws ToolProblem {
        if (!pathNodes || table != null) {
            return;
        }
        try {
            if (PathNodeGraph.zoneTable(folder).isEmpty()) {
                throw new ToolProblem(
                        "The folder has no pathnode.idx to follow. Give zones: 320 or 340, as the server has.");
            }
        } catch (IOException unreadable) {
            throw new ToolProblem(
                    "The pathnode.idx of the folder cannot be read. Give zones: 320 or 340, as the server has. "
                            + unreadable.getMessage());
        }
    }

    static @Nullable ZoneTable zones(ToolArgs args) throws ToolProblem {
        var zones = args.getText("zones");
        if (zones == null) {
            return null;
        }
        return switch (zones) {
            case "320" -> ZoneTable.Y10_TO_25;
            case "340" -> ZoneTable.Y10_TO_26;
            default -> throw new ToolProblem("zones must be 320 or 340.");
        };
    }

    static @Nullable GeoFormat format(ToolArgs args) throws ToolProblem {
        var format = args.getText("format");
        if (format == null) {
            return null;
        }
        return switch (format.toUpperCase(Locale.ROOT)) {
            case "L2OFF" -> GeoFormat.L2OFF;
            case "L2J" -> GeoFormat.L2J;
            default -> throw new ToolProblem("format must be L2OFF or L2J.");
        };
    }

    static StartSources startPoints(World world) {
        var spawns = world.spawns();
        if (spawns == null
                || spawns.scriptPath() == null
                || spawns.scriptPath().toString().isEmpty()
                || !Files.isDirectory(spawns.scriptPath())) {
            return StartSources.NONE;
        }
        try {
            var npcpos = SpawnSource.find(SpawnSource.resolveFolder(spawns.scriptPath()), SpawnSource.POSITIONS);
            return npcpos == null ? StartSources.NONE : new StartSources(npcpos);
        } catch (RuntimeException unreadable) {
            return StartSources.NONE;
        }
    }
}
