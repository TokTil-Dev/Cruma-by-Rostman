package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.geodata.GeoPathNodeFolder;
import com.rost.cruma.core.geodata.GeoPathNodeFolder.State;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.geodata.generation.ClientGeodataBuild;
import com.rost.cruma.core.unreal.edit.MapTileOperation;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Backup;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Extra;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Kind;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Plan;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Refused;
import com.rost.cruma.core.unreal.edit.MapTileOperation.Result;
import com.rost.cruma.core.unreal.edit.MapTileOperation.TileRef;
import com.rost.cruma.core.unreal.edit.TilePackages;
import com.rost.cruma.core.unreal.edit.TileServerFiles;
import com.rost.cruma.core.unreal.edit.TileServerFiles.Part;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TileOperationWork {
    private TileOperationWork() {}

    public record Request(
            Path root,
            Path sourceRoot,
            Kind kind,
            TileRef source,
            TileRef target,
            boolean replace,
            @Nullable Path geodata,
            @Nullable Path scripts,
            boolean buildGeodata,
            boolean rebuildPathNodes) {}

    public record Planned(
            @Nullable Plan plan,
            String problem,
            boolean targetExists,
            List<Part> server,
            boolean pathNodes,
            Request request) {}

    public record Ran(Result result, List<String> details) {}

    public record Undone(List<Path> restored, List<String> details) {}

    public interface Listener {
        void step(String text);

        void stoppable(boolean value);
    }

    public static Planned plan(Request request) {
        var exists = false;
        try {
            exists = MapTileOperation.findMapFile(request.root(), request.target()) != null;
            var plan = MapTileOperation.plan(
                    request.sourceRoot(),
                    request.root(),
                    request.kind(),
                    request.source(),
                    request.target(),
                    request.replace());
            var server = new ArrayList<Part>();
            if (!request.sourceRoot().equals(request.root())) {
                var packages = TilePackages.plan(
                        request.sourceRoot(),
                        request.root(),
                        MapTileOperation.findMapFile(request.sourceRoot(), request.source()));
                var lines = new ArrayList<Part>();
                for (var note : packages.notes()) {
                    lines.add(new Part(List.of(), note, false));
                }
                for (var warning : packages.warnings()) {
                    lines.add(new Part(List.of(), warning, true));
                }
                if (!lines.isEmpty()) {
                    lines.set(
                            0,
                            new Part(
                                    packages.extras(),
                                    lines.getFirst().summary(),
                                    lines.getFirst().warning()));
                } else if (!packages.extras().isEmpty()) {
                    lines.add(new Part(packages.extras(), "", false));
                }
                server.addAll(lines);
            }
            var pathNodes = false;
            if (request.geodata() != null) {
                server.add(
                        request.buildGeodata()
                                ? TileServerFiles.plannedGeodata(
                                        request.geodata(),
                                        request.kind(),
                                        request.source(),
                                        request.target(),
                                        request.replace())
                                : TileServerFiles.planGeodata(
                                        request.geodata(),
                                        request.kind(),
                                        request.source(),
                                        request.target(),
                                        request.replace()));
                pathNodes = TileServerFiles.hasPathNodes(request.geodata());
                if (pathNodes) {
                    server.add(
                            request.rebuildPathNodes()
                                    ? new Part(
                                            List.of(),
                                            "Rebuilds the folder's pathnode graph afterwards, about a second a region.",
                                            false)
                                    : new Part(
                                            List.of(),
                                            "The folder's pathnode graph is out of date afterwards. Tick Rebuild"
                                                    + " pathnodes, or rebuild it in Geodata.",
                                            true));
                }
            }
            if (request.scripts() != null) {
                server.add(TileServerFiles.planSpawns(
                        request.scripts(), request.kind(), request.source(), request.target()));
            }
            var extras = new ArrayList<Extra>();
            for (var part : server) {
                extras.addAll(part.extras());
            }
            return new Planned(
                    MapTileOperation.withExtras(plan, extras), "", exists, List.copyOf(server), pathNodes, request);
        } catch (Exception failure) {
            return new Planned(
                    null,
                    failure.getMessage() == null ? failure.toString() : failure.getMessage(),
                    exists,
                    List.of(),
                    request.geodata() != null && TileServerFiles.hasPathNodes(request.geodata()),
                    request);
        }
    }

    public static Ran run(Planned planned, @Nullable Path folder, boolean keep, Listener listener, BooleanSupplier stop)
            throws IOException {
        var plan = planned.plan();
        if (plan == null) {
            throw new Refused(planned.problem());
        }
        var request = planned.request();
        var details = new ArrayList<String>();
        var operation = plan;
        if (request.buildGeodata() && request.geodata() != null) {
            operation = MapTileOperation.withExtras(plan, buildRegions(request, details, listener, stop));
        }
        listener.stoppable(false);
        listener.step("Writing, checking, then putting files in place");
        if (folder != null) {
            var result = MapTileOperation.export(operation, folder);
            details.addFirst("Wrote " + joinFileNames(result.written()) + ".");
            details.add("In " + folder + ".");
            return new Ran(result, List.copyOf(details));
        }
        var result = MapTileOperation.run(operation, keep, LocalDateTime.now());
        details.addFirst("Wrote " + joinFileNames(result.written()) + ".");
        if (!result.removed().isEmpty()) {
            details.add(1, "Removed " + joinFileNames(result.removed()) + ".");
        }
        details.add(
                result.backup() == null
                        ? "No backup was kept."
                        : "Backup in " + result.backup().getFileName() + ".");
        if (request.rebuildPathNodes() && request.geodata() != null) {
            details.add(rebuildPathNodes(request.geodata(), keep, listener, stop));
        }
        return new Ran(result, List.copyOf(details));
    }

    public static Undone undo(Path backupFolder, @Nullable Path geodata) throws IOException {
        var restored = MapTileOperation.undo(backupFolder);
        var details = new ArrayList<String>();
        details.add("Put back " + joinFileNames(restored) + ".");
        if (geodata != null && TileServerFiles.hasPathNodes(geodata) && hasStaleGraph(geodata)) {
            details.add("The folder's pathnode graph no longer matches its regions. Rebuild it in Geodata.");
        }
        return new Undone(restored, List.copyOf(details));
    }

    public static List<Backup> backups(Path root) {
        try {
            return MapTileOperation.listBackups(root);
        } catch (Exception unreadable) {
            return List.of();
        }
    }

    public static String pathNodeStep(long completed, long total, String detail) {
        var step = "Rebuilding the pathnode graph · " + detail;
        return total > 0 ? step + " · " + Math.clamp(completed * 100 / total, 0, 100) + "%" : step;
    }

    public static String joinFileNames(List<Path> files) {
        return String.join(
                ", ", files.stream().map(file -> file.getFileName().toString()).toList());
    }

    private static List<Extra> buildRegions(
            Request request, List<String> details, Listener listener, BooleanSupplier stop) throws IOException {
        var threads = ClientGeodataBuild.defaultThreads();
        listener.stoppable(true);
        var part = TileServerFiles.builtGeodata(
                request.geodata(),
                request.kind(),
                request.sourceRoot(),
                request.source(),
                request.root(),
                request.target(),
                request.replace(),
                (client, tile) -> ClientGeodataBuild.buildRegion(
                        client,
                        tile.variant(),
                        new GeoTile(tile.x(), tile.y()),
                        threads,
                        new ClientGeodataBuild.Listener() {
                            @Override
                            public void started(GeoTile region, int index, int total) {
                                listener.step("Building geodata " + region);
                            }

                            @Override
                            public void progress(GeoTile region, String stage, double fraction) {
                                listener.step("Building geodata "
                                        + region
                                        + " · "
                                        + stage
                                        + " "
                                        + Math.round(fraction * 100)
                                        + "%");
                            }

                            @Override
                            public void finished(GeoTile region, @Nullable Path file) {}
                        },
                        stop));
        if (stop.getAsBoolean()) {
            throw new CancellationException("Geodata build stopped");
        }
        details.add(part.summary());
        return part.extras();
    }

    private static String rebuildPathNodes(Path folder, boolean keep, Listener listener, BooleanSupplier stop) {
        listener.stoppable(true);
        listener.step("Rebuilding the pathnode graph");
        try {
            var result = GeoPathNodeFolder.rebuild(
                    folder,
                    null,
                    keep,
                    LocalDateTime.now(),
                    (completed, total, detail) -> listener.step(pathNodeStep(completed, total, detail)),
                    stop);
            return "Rebuilt the pathnode graph of "
                    + result.regions()
                    + " regions"
                    + (result.backup() == null
                            ? "."
                            : "; the old one is in " + result.backup().getFileName() + ".");
        } catch (IOException | RuntimeException failure) {
            var reason = stop.getAsBoolean()
                    ? "it was stopped"
                    : failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return "The pathnode graph was not rebuilt, as " + reason + ". Rebuild it in Geodata.";
        }
    }

    private static boolean hasStaleGraph(Path folder) {
        try {
            return GeoPathNodeFolder.check(folder, () -> false).state() == State.STALE;
        } catch (IOException | RuntimeException unreadable) {
            return false;
        }
    }
}
