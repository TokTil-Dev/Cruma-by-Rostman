package com.rost.cruma.viewer.agent.geo;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataReader;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.render.GeodataState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

final class GeoSource {
    static final int MOST_REGIONS = 16;

    private static final long MOST_BYTES = 512L * 1024 * 1024;

    private final GeoDataRepository repository =
            new GeoDataRepository(null, MOST_REGIONS, MOST_BYTES, GeoDataReader::read);
    private long seenReload = -1;
    private long seenMissing = -1;
    private @Nullable FileTime seenChange;

    record Wish(Path folder, Bounds area, long reload, long missing) {}

    static Path folder(@Nullable String asked, GeodataState state, boolean owned) throws ToolProblem {
        var current = state.path == null ? null : state.path.toAbsolutePath().normalize();
        if (asked == null) {
            if (current == null) {
                throw new ToolProblem(
                        "This viewer has no geodata folder. Give folder, the folder with the _conv.dat or .l2j files,"
                                + " or make one with geo_build.");
            }
            return current;
        }
        var wanted = Path.of(asked).toAbsolutePath().normalize();
        if (!Files.isDirectory(wanted)) {
            throw new ToolProblem("There is no folder " + wanted + ".");
        }
        if (wanted.equals(current)) {
            return wanted;
        }
        if (current != null && !owned) {
            throw new ToolProblem("This is a window of the person, and it shows " + current
                    + ". Ask them to choose another folder, or open a viewer of your own with world_open.");
        }
        if (state.editing != null) {
            if (state.editing.hasUnsavedChanges()) {
                throw new ToolProblem("The edits of " + current
                        + " are not saved. Save them with geo_save or take them back with geo_history before"
                        + " you change the folder.");
            }
            state.editing = null;
        }
        state.path = wanted;
        return wanted;
    }

    static Wish wish(Path folder, Bounds area, GeodataState state) {
        return new Wish(folder, area, state.getReloadRevision(), state.getMissingRevision());
    }

    static GeoDataAccess view(GeodataState state, GeoDataSnapshot files) {
        var session = state.editing;
        if (session == null) {
            return files;
        }
        var base = session.getDocument().getBase();
        if (!Objects.equals(base.getSourcePath(), files.getSourcePath())) {
            return files;
        }
        return new EditedView(state.queryData(), base, files);
    }

    static void holdRegions(GeodataState state, GeoDataSnapshot files) throws ToolProblem {
        requireRegion(files);
        var session = state.editing;
        if (session != null) {
            var base = session.getDocument().getBase();
            var outside = new TreeSet<String>();
            for (var entry : files.getTiles().entrySet()) {
                if (entry.getValue().region() != null
                        && base.getRegion(entry.getKey().x(), entry.getKey().y()) == null) {
                    outside.add(GeoWords.region(entry.getKey()));
                }
            }
            if (outside.isEmpty()) {
                return;
            }
            if (session.isModified()) {
                throw new ToolProblem("The editing session holds "
                        + regions(base)
                        + ", not "
                        + String.join(", ", outside)
                        + ", and it has edits. Save them with geo_save or take them back with geo_history,"
                        + " then edit there.");
            }
        }
        var tiles = new HashMap<>(files.getTiles());
        var known = state.getSourceSnapshot();
        if (known != null && Objects.equals(known.getSourcePath(), files.getSourcePath())) {
            for (var entry : known.getTiles().entrySet()) {
                tiles.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        state.startEditing(files.withTiles(tiles));
    }

    static void requireRegion(GeoDataSnapshot files) throws ToolProblem {
        var reasons = new ArrayList<String>();
        for (var entry : files.getTiles().entrySet()) {
            if (entry.getValue().availability() == Availability.AVAILABLE) {
                return;
            }
            reasons.add(
                    GeoWords.region(entry.getKey()) + ": " + entry.getValue().detail());
        }
        throw new ToolProblem(
                reasons.isEmpty()
                        ? "That area is outside the server world."
                        : "No region of that area can be read. " + String.join("; ", reasons)
                                + ". Check the folder, or make the region with geo_build.");
    }

    static String regions(GeoDataSnapshot data) {
        var names = new TreeSet<String>();
        for (var entry : data.getTiles().entrySet()) {
            if (entry.getValue().region() != null) {
                names.add(GeoWords.region(entry.getKey()));
            }
        }
        return String.join(", ", names);
    }

    private record EditedView(GeoDataAccess edited, GeoDataSnapshot base, GeoDataSnapshot files)
            implements GeoDataAccess {
        @Override
        public Cell cellAt(int worldX, int worldY) {
            var tile = GeoTile.fromWorld(worldX, worldY);
            return base.getState(tile).region() != null ? edited.cellAt(worldX, worldY) : files.cellAt(worldX, worldY);
        }

        @Override
        public long revision() {
            return edited.revision();
        }

        @Override
        public String getSourceId() {
            return edited.getSourceId();
        }
    }

    CompletableFuture<GeoDataSnapshot> load(Wish wish) {
        var future = new CompletableFuture<GeoDataSnapshot>();
        Thread.ofPlatform().name("cruma-agent-geodata").daemon().start(() -> {
            try {
                future.complete(read(wish));
            } catch (Exception | Error failure) {
                future.completeExceptionally(failure);
            }
        });
        return future;
    }

    synchronized GeoDataSnapshot read(Wish wish) throws IOException {
        repository.setSource(wish.folder());
        var change = Files.getLastModifiedTime(wish.folder());
        if (wish.reload() != seenReload || !change.equals(seenChange)) {
            repository.refresh();
        } else if (wish.missing() != seenMissing) {
            repository.forgetMissing();
        }
        seenReload = wish.reload();
        seenMissing = wish.missing();
        seenChange = change;
        var area = wish.area();
        return repository.loadBounds(area.minX(), area.minY(), area.maxX(), area.maxY());
    }
}
