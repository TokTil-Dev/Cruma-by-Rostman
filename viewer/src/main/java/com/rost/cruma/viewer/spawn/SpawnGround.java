package com.rost.cruma.viewer.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Availability;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataRepository;
import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.core.spawn.SpawnSampler;
import com.rost.cruma.core.spawn.SpawnSampler.Candidates;
import com.rost.cruma.core.spawn.SpawnTerritory;
import com.rost.cruma.viewer.geodata.ClientFloors;
import com.rost.cruma.viewer.geodata.ClientGround;
import com.rost.cruma.viewer.geodata.GeoDataPicker;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SpawnGround implements AutoCloseable {
    static final int DRAPE_STEP = 48;

    private static final int MAX_DRAPES = 8192;
    private static final int MAX_CANDIDATES = 512;
    private static final int MAX_PENDING = 2048;

    private static final int MAX_REGIONS = 6;

    private final Map<SpawnTerritory, Drape> drapes = newLruMap(MAX_DRAPES);

    private Map<SpawnTerritory, Drape> givenDrapes = new IdentityHashMap<>();

    private Map<SpawnTerritory, Drape> givenBefore = new IdentityHashMap<>();
    private long givenGeneration = -1;

    private volatile Map<SpawnTerritory, Drape> staleDrapes = Map.of();

    private final Map<CandidateKey, Candidates> candidates = newLruMap(MAX_CANDIDATES);
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final Set<Job> pending = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong jobsAsked = new AtomicLong();
    private final Thread worker;
    private volatile @Nullable Path source;
    private volatile @Nullable GeoDataRepository repository;
    private volatile @Nullable ClientFloors client;
    private long clientRevision = -1;
    private volatile long generation;
    private volatile boolean closed;
    private volatile @Nullable GeoDataSnapshot readSnapshot;
    private volatile @Nullable GeoDataRepository readFrom;

    private final Set<GeoTile> read = ConcurrentHashMap.newKeySet();

    public SpawnGround() {
        worker = new Thread(this::run, "npc-spawn-ground");
        worker.setDaemon(true);
        worker.start();
    }

    public record Drape(float[] points, int[] corners, boolean grounded, boolean clientFloors, float level) {
        public Drape(float[] points, int[] corners, boolean grounded, boolean clientFloors) {
            this(points, corners, grounded, clientFloors, mean(points));
        }

        public Drape(float[] points, int[] corners, boolean grounded) {
            this(points, corners, grounded, false);
        }

        public int samples() {
            return points.length / 3;
        }

        private static float mean(float[] points) {
            var sum = 0.0;
            for (var i = 2; i < points.length; i += 3) {
                sum += points[i];
            }
            return (float) (sum / Math.max(1, points.length / 3));
        }
    }

    public enum GroundSource {
        GEODATA,
        CLIENT,
        NONE
    }

    public record CandidateKey(
            List<SpawnTerritory> territories, List<SpawnTerritory> banned, int maximumNpc, boolean flying) {
        public CandidateKey {
            territories = List.copyOf(territories);
            banned = List.copyOf(banned);
        }
    }

    private sealed interface Job permits DrapeJob, CandidateJob, LoadJob {}

    private record DrapeJob(SpawnTerritory territory) implements Job {}

    private record CandidateJob(CandidateKey key) implements Job {}

    private record LoadJob(GeoTile tile) implements Job {}

    static Drape computeDrape(
            @Nullable GeoDataRepository repository, SpawnTerritory territory, @Nullable ClientFloors client) {
        GeoDataAccess data = null;
        if (repository != null) {
            data = load(repository, territory.minX(), territory.minY(), territory.maxX(), territory.maxY());
            if (data == null) {
                return buildDrape(null, territory, client);
            }
        }
        return buildDrape(withClient(data, client), territory, client);
    }

    static Drape buildDrape(@Nullable GeoDataAccess data, SpawnTerritory territory, @Nullable ClientFloors client) {
        var engine = data == null ? null : new GeoQueryEngine(data);
        var points = territory.points();
        var out = new ArrayList<float[]>();
        var corners = new int[points.size()];
        var grounded = data != null;
        var fromClient = false;
        var top = territory.top();
        var bottom = territory.bottom();
        for (var i = 0; i < points.size(); i++) {
            var a = points.get(i);
            var b = points.get((i + 1) % points.size());
            corners[i] = out.size();
            var length = Math.hypot(b.x() - a.x(), b.y() - a.y());
            var steps = Math.max(1, (int) Math.ceil(length / DRAPE_STEP));
            for (var step = 0; step < steps; step++) {
                var t = step / (double) steps;
                var x = a.x() + (b.x() - a.x()) * t;
                var y = a.y() + (b.y() - a.y()) * t;
                var z = Double.NaN;
                if (engine != null) {
                    var cellX = (int) Math.floor(x);
                    var cellY = (int) Math.floor(y);
                    var cell = data.cellAt(cellX, cellY);
                    z = floor(engine, data, cell, cellX, cellY, bottom, top);
                    if (ClientGround.isFromClient(cell)) {
                        fromClient = true;
                    } else {
                        z = snap(client, x, y, z);
                    }
                }
                if (Double.isNaN(z)) {
                    grounded = false;
                }
                out.add(new float[] {(float) x, (float) y, (float) z});
            }
        }
        if (!out.isEmpty()) {
            out.add(out.getFirst());
        }
        fillGaps(out, bottom);
        despike(out);
        var flat = new float[out.size() * 3];
        for (var i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, flat, i * 3, 3);
        }
        return new Drape(flat, corners, grounded, fromClient);
    }

    static double floor(GeoQueryEngine engine, GeoDataAccess data, int x, int y, int bottom, int top) {
        return floor(engine, data, data.cellAt(x, y), x, y, bottom, top);
    }

    static void despike(List<float[]> samples) {
        for (var i = 1; i + 1 < samples.size(); i++) {
            var before = samples.get(i - 1)[2];
            var after = samples.get(i + 1)[2];
            var here = samples.get(i)[2];
            var middle = (before + after) * .5f;
            if (Math.abs(before - after) <= 48 && Math.abs(here - middle) > 64) {
                samples.get(i)[2] = middle;
            }
        }
    }

    static Candidates computeCandidates(GeoDataRepository repository, CandidateKey key, BooleanSupplier cancelled) {
        return computeCandidates(repository, null, key, cancelled);
    }

    static Candidates computeCandidates(
            @Nullable GeoDataRepository repository,
            @Nullable ClientFloors client,
            CandidateKey key,
            BooleanSupplier cancelled) {
        var minX = Integer.MAX_VALUE;
        var minY = Integer.MAX_VALUE;
        var maxX = Integer.MIN_VALUE;
        var maxY = Integer.MIN_VALUE;
        for (var territory : key.territories()) {
            minX = Math.min(minX, territory.minX());
            minY = Math.min(minY, territory.minY());
            maxX = Math.max(maxX, territory.maxX());
            maxY = Math.max(maxY, territory.maxY());
        }
        if (minX > maxX) {
            return new Candidates(List.of(), false, false);
        }
        GeoDataAccess data = null;
        if (repository != null) {
            data = load(repository, minX, minY, maxX, maxY);
            if (data == null) {
                return new Candidates(List.of(), false, true);
            }
        }
        var ground = withClient(data, client);
        if (ground == null) {
            return new Candidates(List.of(), false, true);
        }
        var result = SpawnSampler.findCandidates(
                key.territories(),
                key.banned(),
                key.maximumNpc(),
                key.flying(),
                ground,
                new Random(key.hashCode()),
                cancelled);
        if (ground instanceof ClientGround floors && floors.hasReadClient()) {
            return new Candidates(result.points(), result.usedFallback(), result.geodataMissing(), true);
        }
        return result;
    }

    public void setSource(@Nullable Path folder) {
        if (Objects.equals(folder, source)) {
            return;
        }
        source = folder;
        generation++;
        queue.clear();
        pending.clear();
        drapes.clear();
        staleDrapes = Map.of();
        candidates.clear();
        read.clear();
        repository = folder == null ? null : new GeoDataRepository(folder, 12, 1536L * 1024 * 1024);
    }

    public void setClient(@Nullable ClientFloors surfaces) {
        var revision = surfaces == null ? -1 : surfaces.getRevision();
        if (surfaces == client && revision == clientRevision) {
            return;
        }
        client = surfaces;
        clientRevision = revision;
        generation++;
        queue.removeIf(DrapeJob.class::isInstance);
        pending.removeIf(DrapeJob.class::isInstance);
        synchronized (drapes) {
            staleDrapes = Map.copyOf(drapes);
        }
        drapes.clear();
        synchronized (candidates) {
            candidates.values().removeIf(Candidates::clientFloors);
        }
    }

    public boolean isAvailable() {
        return repository != null;
    }

    public boolean isIdle() {
        return pending.isEmpty();
    }

    public long getJobsAsked() {
        return jobsAsked.get();
    }

    public boolean isReady(double x, double y) {
        return getGroundSource(x, y) != GroundSource.NONE;
    }

    public GroundSource getGroundSource(double x, double y) {
        var availability = availability(x, y);
        if (availability == Availability.AVAILABLE) {
            return GroundSource.GEODATA;
        }
        var surfaces = client;
        if (availability == Availability.MISSING && surfaces != null && surfaces.covers(x, y)) {
            return GroundSource.CLIENT;
        }
        return GroundSource.NONE;
    }

    public double findLayerAbove(double x, double y, double z) {
        var above = Double.NaN;
        switch (getGroundSource(x, y)) {
            case GEODATA -> {
                var cell = readSnapshot.cellAt((int) Math.floor(x), (int) Math.floor(y));
                for (var layer : cell.layers()) {
                    if (layer.height() > z && (Double.isNaN(above) || layer.height() < above)) {
                        above = layer.height();
                    }
                }
            }
            case CLIENT -> {
                var surfaces = client;
                var floors = surfaces == null ? null : surfaces.getFloors(x, y);
                if (floors != null) {
                    for (var floor : floors) {
                        if (floor > z) {
                            return floor;
                        }
                    }
                }
            }
            case NONE -> {}
        }
        return above;
    }

    public boolean isMissing(double x, double y) {
        return availability(x, y) == Availability.MISSING;
    }

    public void refreshMissing() {
        var current = repository;
        if (current == null) {
            return;
        }
        current.forgetMissing();
        generation++;
        synchronized (drapes) {
            drapes.values().removeIf(drape -> !drape.grounded() || drape.clientFloors());
        }
        candidates.clear();
    }

    public @Nullable Drape getDrape(SpawnTerritory territory) {
        if (givenGeneration != generation) {
            givenDrapes.clear();
            givenBefore.clear();
            givenGeneration = generation;
        }
        var drape = givenDrapes.get(territory);
        if (drape != null) {
            return drape;
        }
        drape = givenBefore.get(territory);
        if (drape == null) {
            drape = drapes.get(territory);
        }
        if (drape == null) {
            if (hasGround()) {
                submit(new DrapeJob(territory));
                return staleDrapes.get(territory);
            }
            return null;
        }
        if (givenDrapes.size() >= MAX_DRAPES) {
            var emptied = givenBefore;
            emptied.clear();
            givenBefore = givenDrapes;
            givenDrapes = emptied;
        }
        givenDrapes.put(territory, drape);
        return drape;
    }

    public @Nullable Drape createDrapeNow(SpawnTerritory territory) {
        var cached = drapes.get(territory);
        if (cached != null) {
            return cached;
        }
        var data = readNow();
        if (data == null) {
            return null;
        }
        var drape = buildDrape(data, territory, client);
        if (drape.grounded()) {
            drapes.put(territory, drape);
        } else {
            submit(new DrapeJob(territory));
        }
        return drape;
    }

    public @Nullable Candidates getCandidates(CandidateKey key) {
        var result = candidates.get(key);
        if (result == null && hasGround()) {
            submit(new CandidateJob(key));
        }
        return result;
    }

    public double getGroundAt(double x, double y, double fromZ) {
        var current = repository;
        var snapshot = current == null ? null : current.snapshot();
        var data = withClient(snapshot, client);
        if (data == null) {
            return Double.NaN;
        }
        var point = SpawnSampler.findGround(
                new GeoQueryEngine(data), data, (int) Math.floor(x), (int) Math.floor(y), (int) Math.round(fromZ));
        if (point == null) {
            var tile = GeoTile.fromWorld((int) Math.floor(x), (int) Math.floor(y));
            if (snapshot != null
                    && tile.containsWorld()
                    && snapshot.getState(tile).availability() == Availability.NOT_LOADED) {
                submit(new LoadJob(tile));
            }
            return Double.NaN;
        }
        return point.z();
    }

    public @Nullable GeoPoint findRayGround(GeoPoint origin, GeoPoint direction, double maxDistance) {
        var current = repository;
        var surfaces = client;
        GeoPoint found = null;
        var reach = maxDistance;
        if (current != null) {
            var hit = GeoDataPicker.firstHit(current.snapshot(), origin, direction, maxDistance);
            if (hit != null) {
                found = hit.position();
                reach = hit.distance();
            }
        }
        if (surfaces != null) {
            var hit = surfaces.findRayHit(
                    origin.x(), origin.y(), origin.z(), direction.x(), direction.y(), direction.z(), reach);
            if (hit != null && (current == null || isMissing(hit.x(), hit.y()))) {
                found = new GeoPoint(hit.x(), hit.y(), hit.z());
            }
        }
        return found;
    }

    public double findClientFloor(double x, double y, double z) {
        var surfaces = client;
        return surfaces == null ? Double.NaN : surfaces.findFloorBelow(x, y, z);
    }

    public @Nullable GeoDataAccess readArea(double minX, double minY, double maxX, double maxY) {
        var current = repository;
        if (current == null) {
            return null;
        }
        var regionsX = Math.floorDiv((int) maxX + 128, 32768) - Math.floorDiv((int) minX - 128, 32768) + 1;
        var regionsY = Math.floorDiv((int) maxY + 128, 32768) - Math.floorDiv((int) minY - 128, 32768) + 1;
        if (regionsX * regionsY > 9) {
            return null;
        }
        return current.loadBounds((int) minX - 128, (int) minY - 128, (int) maxX + 129, (int) maxY + 129);
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
    }

    private Availability availability(double x, double y) {
        var current = repository;
        if (current == null) {
            return Availability.MISSING;
        }
        var tile = GeoTile.fromWorld((int) Math.floor(x), (int) Math.floor(y));
        if (!tile.containsWorld()) {
            return Availability.OUTSIDE_WORLD;
        }
        var snapshot = readSnapshot;
        if (snapshot == null || readFrom != current || snapshot.revision() != current.getRevision()) {
            snapshot = current.snapshot();
            readSnapshot = snapshot;
            readFrom = current;
        }
        var availability = snapshot.getState(tile).availability();
        if (availability == Availability.AVAILABLE) {
            read.add(tile);
        } else if (availability == Availability.NOT_LOADED) {
            if (read.contains(tile)) {
                return Availability.AVAILABLE;
            }
            submit(new LoadJob(tile));
        }
        return availability;
    }

    private boolean hasGround() {
        return repository != null || client != null;
    }

    private @Nullable GeoDataAccess readNow() {
        var current = repository;
        return withClient(current == null ? null : current.snapshot(), client);
    }

    private void submit(Job job) {
        if (closed || !pending.add(job)) {
            return;
        }
        jobsAsked.incrementAndGet();
        queue.addFirst(job);
        while (queue.size() > MAX_PENDING) {
            var dropped = queue.pollLast();
            if (dropped != null) {
                pending.remove(dropped);
            }
        }
    }

    private void run() {
        while (!closed) {
            Job job;
            try {
                job = queue.takeFirst();
            } catch (InterruptedException interrupted) {
                return;
            }
            var started = generation;
            var current = repository;
            var surfaces = client;
            try {
                switch (job) {
                    case DrapeJob drapeJob -> {
                        if (current != null || surfaces != null) {
                            var drape = computeDrape(current, drapeJob.territory(), surfaces);
                            if (started == generation) {
                                drapes.put(drapeJob.territory(), drape);
                            }
                        }
                    }
                    case LoadJob loadJob -> {
                        if (current != null) {
                            current.loadRegion(
                                    loadJob.tile().x(), loadJob.tile().y());
                        }
                    }
                    case CandidateJob candidateJob -> {
                        if (current != null || surfaces != null) {
                            var result = computeCandidates(
                                    current, surfaces, candidateJob.key(), () -> started != generation);
                            if (started == generation) {
                                candidates.put(candidateJob.key(), result);
                            }
                        }
                    }
                }
            } catch (RuntimeException failure) {
                LogManager.getLogger(SpawnGround.class).warn("Spawn ground query failed: {}", failure.toString());
            } finally {
                pending.remove(job);
            }
        }
    }

    private static <K, V> Map<K, V> newLruMap(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(256, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Entry<K, V> eldest) {
                return size() > capacity;
            }
        });
    }

    private static @Nullable GeoDataAccess load(GeoDataRepository repository, int minX, int minY, int maxX, int maxY) {
        var regionsX = Math.floorDiv(maxX + 128, 32768) - Math.floorDiv(minX - 128, 32768) + 1;
        var regionsY = Math.floorDiv(maxY + 128, 32768) - Math.floorDiv(minY - 128, 32768) + 1;
        if (regionsX * regionsY > MAX_REGIONS) {
            return null;
        }
        return repository.loadBounds(minX - 128, minY - 128, maxX + 129, maxY + 129);
    }

    private static double floor(
            GeoQueryEngine engine, GeoDataAccess data, Cell cell, int x, int y, int bottom, int top) {
        if (cell.availability() != Availability.AVAILABLE || cell.layers().isEmpty()) {
            return Double.NaN;
        }
        var lowest = Integer.MAX_VALUE;
        for (var layer : cell.layers()) {
            if (layer.height() >= bottom && layer.height() <= top) {
                lowest = Math.min(lowest, layer.height());
            }
        }
        if (lowest != Integer.MAX_VALUE) {
            return lowest;
        }
        var ground = SpawnSampler.findGround(engine, data, x, y, top);
        return ground == null ? Double.NaN : Math.clamp(ground.z(), bottom, top);
    }

    private static @Nullable GeoDataAccess withClient(@Nullable GeoDataAccess data, @Nullable ClientFloors client) {
        return client == null ? data : new ClientGround(data, client);
    }

    private static double snap(@Nullable ClientFloors client, double x, double y, double z) {
        return client == null ? z : client.snap(x, y, z);
    }

    private static void fillGaps(List<float[]> samples, int bottom) {
        var last = Float.NaN;
        for (var sample : samples) {
            if (!Float.isNaN(sample[2])) {
                last = sample[2];
                break;
            }
        }
        if (Float.isNaN(last)) {
            last = bottom;
        }
        for (var sample : samples) {
            if (Float.isNaN(sample[2])) {
                sample[2] = last;
            } else {
                last = sample[2];
            }
        }
    }
}
