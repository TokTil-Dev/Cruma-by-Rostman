package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.geometry.Bounds3f;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.UMap;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import com.rost.cruma.core.world.UnrealLoader.DecorationGround;
import com.rost.cruma.core.world.UnrealLoader.DecorationTemplate;
import com.rost.cruma.core.world.ZoneRender;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.viewer.render.api.StableRenderId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

final class BgfxDecorations implements AutoCloseable {
    static final String OWNER = "@decorations";

    static final int CHUNK_SECTORS = 4;

    private static final Logger log = LogManager.getLogger(BgfxDecorations.class);
    private static final float LEAVE_FACTOR = 1.1f;
    private static final float LEAVE_MARGIN = 1024f;
    private static final long APPLY_NANOS = 3_000_000L;
    private static final int MIN_POOL_SLOTS = 4096;
    private static final int FIRST_POOL_FACTOR = 4;
    private static final int RESIDUAL_LIMIT = 262_144;
    private static final int SETTLE_SLOTS = 65_536;
    private static final long RETIRED_LIMIT = 1_000_000L;
    private static final long SUMMARY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long ASK_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int PRIORITY_EDITOR = 0;
    private static final int PRIORITY_NEAR = 1;

    private final Host host;
    private final ThreadPoolExecutor builders;
    private final ConcurrentLinkedQueue<Object> finished = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, Terrain> terrains = new LinkedHashMap<>();
    private final Set<String> streamedLayers = ConcurrentHashMap.newKeySet();
    private final Map<String, EntityMesh> meshes = new HashMap<>();
    private final Map<String, MeshGeometry> meshGeometries = new ConcurrentHashMap<>();
    private final Map<PoolKey, Pool> pools = new HashMap<>();
    private final AtomicInteger order = new AtomicInteger();
    private @Nullable Bounds3f decoratedBounds;
    private boolean synchronous;
    private long retiredSlots;
    private long slotRevision;
    private long summaryAt;
    private long topologyAskedAt = Long.MIN_VALUE / 2;
    private long movedAt;
    private @Nullable Camera lastCamera;
    private long longestApply;
    private long summarizedInstances = -1;
    private boolean closed;

    interface Host {
        BgfxSceneGeometry installGeometry(Entity source, BgfxSceneGeometry.Prepared prepared);

        BgfxSceneObject createPool(
                Entity representative, BgfxSceneGeometry geometry, long stableId, int capacity, Bounds3f bounds);

        void publishPool(BgfxSceneObject pool, @Nullable BgfxSceneObject replaced);

        boolean isInTopology(BgfxSceneObject pool);

        long getTopologyGeneration();

        void requestTopology();

        void removePool(BgfxSceneObject pool);

        void writeSlots(BgfxSceneObject pool, int first, int count);

        void slotsChanged();

        boolean registerRays(
                String owner, Entity source, BgfxSceneGeometry.Prepared geometry, BgfxSceneObject.Prepared placement);

        void removeRays(String owner);
    }

    BgfxDecorations(Host host) {
        this.host = host;
        var threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
        var started = new AtomicInteger();
        builders =
                new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), task -> {
                    var thread = new Thread(task, "decoration-builder-" + started.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                });
        builders.allowCoreThreadTimeOut(true);
    }

    void setSynchronous(boolean synchronous) {
        this.synchronous = synchronous;
    }

    void register(UMap map) {
        requireOpen();
        if (map.decorations().isEmpty()) {
            return;
        }
        var byOwner = new LinkedHashMap<String, List<DecorationTemplate>>();
        for (var template : map.decorations()) {
            byOwner.computeIfAbsent(template.getOwner(), ignored -> new ArrayList<>())
                    .add(template);
        }
        for (var entry : byOwner.entrySet()) {
            var previous = terrains.remove(entry.getKey());
            if (previous != null) {
                drop(previous);
            }
            var terrain = new Terrain(entry.getKey(), map.name());
            for (var template : entry.getValue()) {
                var key = meshKey(template);
                var canonical = meshes.computeIfAbsent(key, ignored -> template.getSourceMesh());
                terrain.layers.add(new Layer(terrain, template.withSourceMesh(canonical), key));
            }
            terrain.ground = terrain.layers.getFirst().template.getSavedGround();
            terrain.refreshBounds();
            terrains.put(terrain.owner, terrain);
            for (var layer : terrain.layers) {
                streamedLayers.add(layerName(terrain.owner, layer.template.getLayerIndex()));
            }
            decoratedBounds = union(decoratedBounds, terrain.bounds);
        }
        for (var pool : pools.values()) {
            pool.object.setDecorationPoolBounds(decoratedBounds);
        }
    }

    void evict(Set<String> maps) {
        if (closed || terrains.isEmpty()) {
            return;
        }
        var gone = new ArrayList<Terrain>();
        for (var terrain : terrains.values()) {
            if (maps.contains(terrain.map)) {
                gone.add(terrain);
            }
        }
        for (var terrain : gone) {
            terrains.remove(terrain.owner);
            drop(terrain);
        }
    }

    boolean streams(String owner, int layerIndex) {
        return streamedLayers.contains(layerName(owner, layerIndex));
    }

    void setSources(String owner, Map<Integer, DecorationDensity> densities, @Nullable DecorationGround ground) {
        requireOpen();
        var terrain = terrains.get(owner);
        if (terrain == null) {
            return;
        }
        terrain.revision++;
        if (ground != null) {
            terrain.ground = ground;
            terrain.refreshBounds();
        }
        for (var layer : terrain.layers) {
            var density = densities.get(layer.template.getLayerIndex());
            if (density != null) {
                layer.density = density;
            }
            if (density == null && ground == null) {
                continue;
            }
            for (var chunk : layer.chunks) {
                if (chunk.wanted) {
                    schedule(chunk, PRIORITY_EDITOR);
                }
            }
        }
    }

    void restoreSources(String owner) {
        var terrain = terrains.get(owner);
        if (terrain == null) {
            return;
        }
        var densities = new HashMap<Integer, DecorationDensity>();
        for (var layer : terrain.layers) {
            densities.put(layer.template.getLayerIndex(), layer.template.getSavedDensity());
        }
        setSources(owner, densities, terrain.layers.getFirst().template.getSavedGround());
    }

    void update(Camera camera, float multiplier, ZoneStateChoice zoneState) {
        if (closed || terrains.isEmpty() && pools.isEmpty()) {
            return;
        }
        var reachMultiplier = multiplier > 0f && Float.isFinite(multiplier) ? multiplier : Float.POSITIVE_INFINITY;
        var changed = new HashSet<Chunk>();
        for (var terrain : terrains.values()) {
            for (var layer : terrain.layers) {
                var fade = layer.template.getFadeoutRadiusMax();
                var reach = (fade > 0f ? fade * reachMultiplier : 0f) + layer.extent;
                var grows = layer.template.getZoneRender().shows(zoneState);
                for (var chunk : layer.chunks) {
                    var distance = camera.distanceSquared(chunk);
                    var enter = reach + chunk.radius;
                    var wanted = grows
                            && (chunk.wanted
                                    ? distance <= square(enter * LEAVE_FACTOR + LEAVE_MARGIN)
                                    : distance <= square(enter));
                    if (wanted == chunk.wanted) {
                        continue;
                    }
                    chunk.wanted = wanted;
                    if (wanted) {
                        if (chunk.builtRevision != terrain.revision && chunk.pendingRevision != terrain.revision) {
                            schedule(chunk, PRIORITY_NEAR);
                        }
                    } else if (chunk.pool != null) {
                        release(chunk);
                        changed.add(chunk);
                    } else {
                        chunk.builtRevision = -1;
                        chunk.hashes = null;
                    }
                }
            }
        }
        var deadline = System.nanoTime() + APPLY_NANOS;
        drain(synchronous ? Long.MAX_VALUE : deadline, changed);
        while (synchronous && (inFlight.get() > 0 || !finished.isEmpty())) {
            LockSupport.parkNanos(200_000L);
            drain(Long.MAX_VALUE, changed);
        }
        if (!changed.isEmpty()) {
            slotRevision++;
            host.slotsChanged();
        }
        if (!camera.equals(lastCamera)) {
            lastCamera = camera;
            movedAt = System.nanoTime();
        }
        askForTopology();
        summarize();
    }

    Snapshot getSnapshot() {
        var chunks = 0;
        var instances = 0L;
        var wanted = 0;
        for (var terrain : terrains.values()) {
            for (var layer : terrain.layers) {
                for (var chunk : layer.chunks) {
                    wanted += chunk.wanted ? 1 : 0;
                    if (chunk.pool != null) {
                        chunks++;
                        instances += chunk.count;
                    }
                }
            }
        }
        var capacity = 0L;
        for (var pool : pools.values()) {
            capacity += pool.capacity;
        }
        return new Snapshot(terrains.size(), wanted, chunks, instances, pools.size(), capacity, inFlight.get());
    }

    long getSlotRevision() {
        return slotRevision;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        builders.shutdownNow();
        pools.clear();
        terrains.clear();
        finished.clear();
    }

    record Snapshot(
            int terrains, int wantedChunks, int residentChunks, long instances, int pools, long slots, int building) {}

    record Camera(float x, float y, float z, float forwardX, float forwardY, float forwardZ, boolean orthographic) {
        private float distanceSquared(Chunk chunk) {
            var dx = chunk.centerX - x;
            var dy = chunk.centerY - y;
            var dz = chunk.centerZ - z;
            var squared = dx * dx + dy * dy + dz * dz;
            if (!orthographic) {
                return squared;
            }
            var along = dx * forwardX + dy * forwardY + dz * forwardZ;
            return Math.max(0f, squared - along * along);
        }
    }

    private record PoolKey(String mesh, int zone, boolean terrainLit) {}

    private void submit(int priority, Runnable work) {
        if (closed) {
            return;
        }
        inFlight.incrementAndGet();
        builders.execute(new Job(priority, order.getAndIncrement(), () -> {
            try {
                work.run();
            } catch (RuntimeException | Error failure) {
                log.warn("A decoration chunk could not be built", failure);
            } finally {
                inFlight.decrementAndGet();
            }
        }));
    }

    private void schedule(Chunk chunk, int priority) {
        var terrain = chunk.layer.terrain;
        var revision = terrain.revision;
        chunk.pendingRevision = revision;
        var template = chunk.layer.template;
        var density = chunk.layer.density;
        var ground = terrain.ground;
        var priorHashes = chunk.builtRevision < 0 ? null : chunk.hashes;
        var mesh = meshGeometry(chunk.layer.meshKey);
        submit(priority, () -> {
            var hashes = new long[chunk.sectors.length];
            for (var index = 0; index < hashes.length; index++) {
                hashes[index] = template.computeSectorHash(chunk.sectors[index], density, ground);
            }
            if (priorHashes != null && Arrays.equals(priorHashes, hashes)) {
                finished.add(new Built(chunk, revision, true, priorHashes, hashes, null, null));
                return;
            }
            var entity = template.build(density, ground, chunk.sectors);
            BgfxSceneObject.Prepared placement = null;
            if (entity != null) {
                entity.setZoneRender(ZoneRender.EVERY_STATE);
                placement = BgfxSceneObject.prepare(entity, mesh.prepare(entity), OWNER, 0);
            }
            finished.add(new Built(chunk, revision, false, priorHashes, hashes, placement, entity));
        });
    }

    private void drain(long deadline, Set<Chunk> changed) {
        while (System.nanoTime() < deadline) {
            var next = finished.poll();
            if (next == null) {
                break;
            }
            switch (next) {
                case Built built -> {
                    var started = System.nanoTime();
                    if (apply(built)) {
                        changed.add(built.chunk);
                    }
                    longestApply = Math.max(longestApply, System.nanoTime() - started);
                }
                default -> throw new IllegalStateException("unknown decoration result");
            }
        }
    }

    private boolean apply(Built built) {
        var chunk = built.chunk;
        var terrain = chunk.layer.terrain;
        if (terrains.get(terrain.owner) != terrain || chunk.pendingRevision != built.revision) {
            return false;
        }
        chunk.pendingRevision = -1;
        if (!chunk.wanted) {
            return false;
        }
        if (built.unchanged) {
            if (chunk.builtRevision >= 0 && chunk.hashes == built.priorHashes) {
                chunk.builtRevision = built.revision;
                return false;
            }
            schedule(chunk, PRIORITY_NEAR);
            return false;
        }
        var hadSlots = chunk.pool != null;
        if (hadSlots) {
            release(chunk);
        }
        chunk.hashes = built.hashes;
        chunk.builtRevision = built.revision;
        if (built.placement == null || built.entity == null) {
            return hadSlots;
        }
        var records = built.placement.instanceRecords();
        var count = records.length / BgfxSceneObject.INSTANCE_FLOATS;
        var key = chunk.layer.poolKey;
        var pool = pools.get(key);
        if (pool == null) {
            pool = createPool(key, built.entity, Math.max(MIN_POOL_SLOTS, count * FIRST_POOL_FACTOR));
        }
        var slot = pool.allocate(count);
        if (slot < 0) {
            pool = grow(pool, count);
            slot = pool.allocate(count);
        }
        chunk.pool = pool;
        chunk.slot = slot;
        chunk.count = count;
        pool.chunks.add(chunk);
        pool.object.writeDecorationSlots(
                slot, records, count, chunk.layer.template.getActor(), chunk.layer.template.getFadeoutRadiusMax());
        host.writeSlots(pool.object, slot, count);
        var rayOwner = OWNER + '/' + chunk.layer.template.getActor() + '/' + chunk.sectors[0];
        if (host.registerRays(
                rayOwner, built.entity, meshGeometry(chunk.layer.meshKey).prepare(built.entity), built.placement)) {
            chunk.rayOwner = rayOwner;
        }
        return true;
    }

    private void release(Chunk chunk) {
        var pool = chunk.pool;
        if (pool != null) {
            pool.object.clearDecorationSlots(chunk.slot, chunk.count);
            host.writeSlots(pool.object, chunk.slot, chunk.count);
            pool.free(chunk.slot, chunk.count);
            pool.chunks.remove(chunk);
        }
        if (chunk.rayOwner != null) {
            host.removeRays(chunk.rayOwner);
            chunk.rayOwner = null;
        }
        chunk.pool = null;
        chunk.slot = -1;
        chunk.count = 0;
        chunk.builtRevision = -1;
        chunk.hashes = null;
    }

    private Pool createPool(PoolKey key, Entity representative, int capacity) {
        var geometry = meshGeometry(key.mesh()).install(host, representative);
        var slots = capacity;
        var object = host.createPool(
                representative,
                geometry,
                poolId(key, 0),
                slots,
                decoratedBounds == null ? bounds(representative) : decoratedBounds);
        var pool = new Pool(key, object, slots, representative);
        pool.createdGeneration = host.getTopologyGeneration();
        pools.put(key, pool);
        host.publishPool(object, null);
        log.debug("Decoration pool {} zone {}: {} slots", key.mesh(), key.zone(), slots);
        return pool;
    }

    private Pool grow(Pool pool, int needed) {
        var used = 0;
        for (var chunk : pool.chunks) {
            used += chunk.count;
        }
        var slots = (int) Math.min(Integer.MAX_VALUE / 2, Math.max(MIN_POOL_SLOTS, (used + (long) needed) * 2L));
        var geometry = pool.object.getGeometry();
        var object = host.createPool(
                pool.representative, geometry, poolId(pool.key, pool.generation + 1), slots, decoratedBounds);
        var grown = new Pool(pool.key, object, slots, pool.representative);
        grown.generation = pool.generation + 1;
        grown.createdGeneration = host.getTopologyGeneration();
        var records = pool.object.getInstanceRecords();
        for (var chunk : List.copyOf(pool.chunks)) {
            var slot = grown.allocate(chunk.count);
            var copy = Arrays.copyOfRange(
                    records,
                    chunk.slot * BgfxSceneObject.INSTANCE_FLOATS,
                    (chunk.slot + chunk.count) * BgfxSceneObject.INSTANCE_FLOATS);
            object.writeDecorationSlots(
                    slot,
                    copy,
                    chunk.count,
                    chunk.layer.template.getActor(),
                    chunk.layer.template.getFadeoutRadiusMax());
            chunk.pool = grown;
            chunk.slot = slot;
            grown.chunks.add(chunk);
        }
        pools.put(pool.key, grown);
        retiredSlots += pool.capacity;
        host.publishPool(object, pool.object);
        log.info(
                "Decoration pool {} zone {} grew from {} to {} slots",
                pool.key.mesh(),
                pool.key.zone(),
                pool.capacity,
                slots);
        return grown;
    }

    private void askForTopology() {
        var residual = 0L;
        var residualSlots = 0L;
        var waiting = false;
        var generation = host.getTopologyGeneration();
        for (var pool : pools.values()) {
            if (pool.chunks.isEmpty() || generation != pool.createdGeneration || host.isInTopology(pool.object)) {
                continue;
            }
            waiting = true;
            residualSlots += pool.capacity;
            for (var chunk : pool.chunks) {
                residual += chunk.count;
            }
        }
        if (!waiting) {
            retiredSlots = 0;
            return;
        }
        var now = System.nanoTime();
        var settled = inFlight.get() == 0
                && finished.isEmpty()
                && now - movedAt >= SETTLE_NANOS
                && residualSlots > SETTLE_SLOTS;
        if ((settled || residual > RESIDUAL_LIMIT || retiredSlots > RETIRED_LIMIT)
                && now - topologyAskedAt >= ASK_NANOS) {
            host.requestTopology();
            topologyAskedAt = now;
            retiredSlots = 0;
        }
    }

    private void drop(Terrain terrain) {
        if (terrains.get(terrain.owner) == null) {
            for (var layer : terrain.layers) {
                streamedLayers.remove(layerName(terrain.owner, layer.template.getLayerIndex()));
            }
        }
        for (var layer : terrain.layers) {
            for (var chunk : layer.chunks) {
                if (chunk.pool != null) {
                    release(chunk);
                }
                chunk.wanted = false;
                chunk.pendingRevision = -1;
            }
        }
        var empty = new ArrayList<PoolKey>();
        for (var pool : pools.values()) {
            if (pool.chunks.isEmpty() && !isKeyRegistered(pool.key)) {
                empty.add(pool.key);
            }
        }
        for (var key : empty) {
            host.removePool(pools.remove(key).object);
        }
        slotRevision++;
        host.slotsChanged();
    }

    private boolean isKeyRegistered(PoolKey key) {
        for (var terrain : terrains.values()) {
            for (var layer : terrain.layers) {
                if (layer.poolKey.equals(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private MeshGeometry meshGeometry(String key) {
        return meshGeometries.computeIfAbsent(key, MeshGeometry::new);
    }

    private void summarize() {
        var now = System.nanoTime();
        if (inFlight.get() > 0 || now - summaryAt < SUMMARY_NANOS) {
            return;
        }
        var snapshot = getSnapshot();
        if (snapshot.instances() == summarizedInstances) {
            return;
        }
        summaryAt = now;
        summarizedInstances = snapshot.instances();
        log.info(
                "Decorations near the camera: {} chunks, {} meshes, in {} pools of {} slots; a chunk showed in {} ms at"
                        + " most",
                snapshot.residentChunks(),
                snapshot.instances(),
                snapshot.pools(),
                snapshot.slots(),
                String.format(Locale.ROOT, "%.1f", longestApply / 1e6));
        longestApply = 0;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("decorations are closed");
        }
    }

    private static String layerName(String owner, int layerIndex) {
        return owner + '#' + layerIndex;
    }

    private static String meshKey(DecorationTemplate template) {
        return template.getMeshName().toLowerCase(Locale.ROOT) + "#terrain-deco";
    }

    private static long poolId(PoolKey key, int generation) {
        return StableRenderId.builder("decoration-pool")
                .put(key.mesh())
                .put(key.zone())
                .put(key.terrainLit() ? 1 : 0)
                .put(generation)
                .build();
    }

    private static Bounds3f bounds(Entity entity) {
        var box = ((EntityMesh) entity.getMesh()).boundingBox();
        return new Bounds3f(
                box.getMin().getX(),
                box.getMin().getY(),
                box.getMin().getZ(),
                box.getMax().getX(),
                box.getMax().getY(),
                box.getMax().getZ());
    }

    private static Bounds3f union(@Nullable Bounds3f left, Bounds3f right) {
        return left == null
                ? right
                : new Bounds3f(
                        Math.min(left.minX(), right.minX()),
                        Math.min(left.minY(), right.minY()),
                        Math.min(left.minZ(), right.minZ()),
                        Math.max(left.maxX(), right.maxX()),
                        Math.max(left.maxY(), right.maxY()),
                        Math.max(left.maxZ(), right.maxZ()));
    }

    private static float square(float value) {
        return value * value;
    }

    private static final class Terrain {
        private final String owner;
        private final String map;
        private final List<Layer> layers = new ArrayList<>();
        private DecorationGround ground;
        private int revision;
        private Bounds3f bounds;

        private Terrain(String owner, String map) {
            this.owner = owner;
            this.map = map;
        }

        private @Nullable Layer findLayer(int layerIndex) {
            for (var layer : layers) {
                if (layer.template.getLayerIndex() == layerIndex) {
                    return layer;
                }
            }
            return null;
        }

        private void refreshBounds() {
            Bounds3f all = null;
            for (var layer : layers) {
                for (var chunk : layer.chunks) {
                    Bounds3f box = null;
                    for (var sector : chunk.sectors) {
                        box = union(box, layer.template.getSectorBounds(sector, ground));
                    }
                    chunk.setBounds(box, layer.extent);
                    all = union(all, box);
                }
            }
            bounds = all;
        }
    }

    private static final class Layer {
        private final Terrain terrain;
        private final DecorationTemplate template;
        private final String meshKey;
        private final PoolKey poolKey;
        private final float extent;
        private final Chunk[] chunks;
        private DecorationDensity density;

        private Layer(Terrain terrain, DecorationTemplate template, String meshKey) {
            this.terrain = terrain;
            this.template = template;
            this.meshKey = meshKey;
            density = template.getSavedDensity();
            extent = template.getMeshExtent();
            poolKey = new PoolKey(meshKey, template.getZone(), template.isTerrainLit());
            var grouped = new LinkedHashMap<Long, List<Integer>>();
            var span = 16 * CHUNK_SECTORS;
            for (var sector = 0; sector < template.getSectorCount(); sector++) {
                var key = ((long) Math.floorDiv(template.getSectorOffsetY(sector), span) << 32)
                        | (Math.floorDiv(template.getSectorOffsetX(sector), span) & 0xffffffffL);
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(sector);
            }
            chunks = new Chunk[grouped.size()];
            var index = 0;
            for (var sectors : grouped.values()) {
                chunks[index] = new Chunk(
                        this, sectors.stream().mapToInt(Integer::intValue).toArray());
                index++;
            }
        }
    }

    private static final class Chunk {
        private final Layer layer;
        private final int[] sectors;
        private float centerX;
        private float centerY;
        private float centerZ;
        private float radius;
        private boolean wanted;
        private int builtRevision = -1;
        private long @Nullable [] hashes;
        private @Nullable Pool pool;
        private int slot = -1;
        private int count;
        private @Nullable String rayOwner;
        private int pendingRevision = -1;

        private Chunk(Layer layer, int[] sectors) {
            this.layer = layer;
            this.sectors = sectors;
        }

        private void setBounds(@Nullable Bounds3f box, float extent) {
            if (box == null) {
                return;
            }
            centerX = (box.minX() + box.maxX()) / 2f;
            centerY = (box.minY() + box.maxY()) / 2f;
            centerZ = (box.minZ() + box.maxZ()) / 2f;
            var dx = box.maxX() - box.minX();
            var dy = box.maxY() - box.minY();
            var dz = box.maxZ() - box.minZ() + 2f * extent;
            radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / 2f;
        }
    }

    private static final class Pool {
        private final PoolKey key;
        private final BgfxSceneObject object;
        private final int capacity;
        private final Entity representative;
        private final List<int[]> freeRanges = new ArrayList<>();
        private final Set<Chunk> chunks = Collections.newSetFromMap(new IdentityHashMap<>());
        private int generation;
        private long createdGeneration;

        private Pool(PoolKey key, BgfxSceneObject object, int capacity, Entity representative) {
            this.key = key;
            this.object = object;
            this.capacity = capacity;
            this.representative = representative;
            freeRanges.add(new int[] {0, capacity});
        }

        private int allocate(int count) {
            for (var index = 0; index < freeRanges.size(); index++) {
                var range = freeRanges.get(index);
                if (range[1] >= count) {
                    var first = range[0];
                    if (range[1] == count) {
                        freeRanges.remove(index);
                    } else {
                        range[0] += count;
                        range[1] -= count;
                    }
                    return first;
                }
            }
            return -1;
        }

        private void free(int first, int count) {
            var index = 0;
            while (index < freeRanges.size() && freeRanges.get(index)[0] < first) {
                index++;
            }
            freeRanges.add(index, new int[] {first, count});
            if (index + 1 < freeRanges.size()
                    && freeRanges.get(index)[0] + freeRanges.get(index)[1]
                            == freeRanges.get(index + 1)[0]) {
                freeRanges.get(index)[1] += freeRanges.remove(index + 1)[1];
            }
            if (index > 0
                    && freeRanges.get(index - 1)[0] + freeRanges.get(index - 1)[1]
                            == freeRanges.get(index)[0]) {
                freeRanges.get(index - 1)[1] += freeRanges.remove(index)[1];
            }
        }
    }

    private static final class MeshGeometry {
        private final String key;
        private volatile BgfxSceneGeometry.@Nullable Prepared prepared;
        private @Nullable BgfxSceneGeometry installed;

        private MeshGeometry(String key) {
            this.key = key;
        }

        private BgfxSceneGeometry.Prepared prepare(Entity entity) {
            var known = prepared;
            if (known != null) {
                return known;
            }
            synchronized (this) {
                if (prepared == null) {
                    prepared = BgfxSceneGeometry.prepare(entity, OWNER + '/' + key, 0);
                }
                return prepared;
            }
        }

        private BgfxSceneGeometry install(Host host, Entity entity) {
            if (installed == null || installed.isClosed()) {
                installed = host.installGeometry(entity, prepare(entity));
            }
            return installed;
        }
    }

    private record Built(
            Chunk chunk,
            int revision,
            boolean unchanged,
            long @Nullable [] priorHashes,
            long[] hashes,
            BgfxSceneObject.@Nullable Prepared placement,
            @Nullable Entity entity) {}

    private record Job(int priority, int order, Runnable work) implements Runnable, Comparable<Job> {
        @Override
        public void run() {
            work.run();
        }

        @Override
        public int compareTo(Job other) {
            return priority != other.priority
                    ? Integer.compare(priority, other.priority)
                    : Integer.compare(order, other.order);
        }
    }
}
