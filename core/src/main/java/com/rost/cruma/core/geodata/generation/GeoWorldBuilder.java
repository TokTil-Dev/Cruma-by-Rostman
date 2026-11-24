package com.rost.cruma.core.geodata.generation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

public final class GeoWorldBuilder {
    static final float DROP_START = 15000f;

    static final float LOWEST = -16200f;

    static final int MAX_START_ROUNDS = 8;

    static final float SAME_FLOOR = 32f;

    private static final int BATCH = 1 << 16;

    private static final byte OUTSIDE = 1;
    private static final byte FAILED = 2;
    private static final byte STEP_10 = 3;
    private static final byte STEP_20 = 4;

    private final CollisionWorld world;

    private final ThreadLocal<ScoutMover> movers = ThreadLocal.withInitial(this::mover);

    private final BuiltRegion region;
    private final Settings settings;
    private final Progress progress;
    private final BooleanSupplier cancelled;
    private final IntArrayList queue = new IntArrayList();

    private int head;

    private final BitSet late = new BitSet();

    private ExecutorService executor;

    private GeoWorldBuilder(
            CollisionWorld world, BuiltRegion region, Settings settings, Progress progress, BooleanSupplier cancelled) {
        this.world = world;
        this.region = region;
        this.settings = settings;
        this.progress = progress;
        this.cancelled = cancelled;
    }

    public record Settings(float radius, float halfHeight, int cellLimit, int threads) {
        public Settings {
            if (!(radius > 0) || !(halfHeight > 0)) {
                throw new IllegalArgumentException("The box needs a positive size");
            }
            if (cellLimit < 2 || cellLimit > BuiltRegion.CELLS) {
                throw new IllegalArgumentException("Cell limit must be in 2.." + BuiltRegion.CELLS);
            }
            if (threads < 1) {
                throw new IllegalArgumentException("At least one thread is needed");
            }
        }

        public static Settings forClient(int threads) {
            return new Settings(8f, 23.5f, BuiltRegion.CELLS, threads);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void report(String stage, long done, long total);
    }

    @FunctionalInterface
    public interface StartRounds {
        StartRounds NONE = (region, round) -> new float[0];

        float[] next(BuiltRegion region, int round);
    }

    public static BuiltRegion build(
            CollisionWorld world,
            int tileX,
            int tileY,
            Settings settings,
            Progress progress,
            BooleanSupplier cancelled) {
        return build(world, tileX, tileY, settings, new float[0], progress, cancelled);
    }

    public static BuiltRegion build(
            CollisionWorld world,
            int tileX,
            int tileY,
            Settings settings,
            float[] startPoints,
            Progress progress,
            BooleanSupplier cancelled) {
        return build(world, tileX, tileY, settings, startPoints, List.of(), progress, cancelled);
    }

    public static BuiltRegion build(
            CollisionWorld world,
            int tileX,
            int tileY,
            Settings settings,
            float[] startPoints,
            StartRounds rounds,
            Progress progress,
            BooleanSupplier cancelled) {
        return build(world, tileX, tileY, settings, startPoints, List.of(rounds), progress, cancelled);
    }

    public static BuiltRegion build(
            CollisionWorld world,
            int tileX,
            int tileY,
            Settings settings,
            float[] startPoints,
            List<StartRounds> rounds,
            Progress progress,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(startPoints);
        Objects.requireNonNull(rounds);
        Objects.requireNonNull(world);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(progress);
        Objects.requireNonNull(cancelled);
        var builder = new GeoWorldBuilder(
                world, new BuiltRegion(tileX, tileY, settings.cellLimit()), settings, progress, cancelled);
        try (var executor = Executors.newFixedThreadPool(
                settings.threads(), Thread.ofPlatform().daemon().factory())) {
            builder.executor = executor;
            var sources = List.copyOf(rounds);
            builder.drop();
            builder.dropAtStarts(startPoints);
            builder.dropAtStarts(fixedStarts(tileX, tileY));
            builder.floodFillLayers();
            for (var round = 0; round < MAX_START_ROUNDS; round++) {
                var any = false;
                for (var source : sources) {
                    var points = source.next(builder.region, round);
                    if (points.length < 5) {
                        continue;
                    }
                    any = true;
                    builder.dropAtLateStarts(points);
                    builder.floodFillLayers();
                }
                if (!any) {
                    break;
                }
            }
        }
        return builder.region;
    }

    static float[] fixedStarts(int tileX, int tileY) {
        if (tileX == 20 && tileY == 21) {
            return new float[] {16128, 114193, -3410, 17682, 114081, -11650, 17716, 110413, -9260, 17704, 118403, -6747
            };
        }
        if (tileX == 21 && tileY == 25) {
            return new float[] {49350, 248464, -5900};
        }
        return new float[0];
    }

    private void drop() {
        var limit = settings.cellLimit();
        var landed = new float[limit * limit];
        var done = new AtomicInteger();
        runInParallel(limit, (mover, cellX) -> {
            var location = new float[3];
            for (var cellY = 0; cellY < limit; cellY++) {
                location[0] = region.getMinX() + 8 + cellX * BuiltRegion.CELL_SIZE;
                location[1] = region.getMinY() + 8 + cellY * BuiltRegion.CELL_SIZE;
                location[2] = DROP_START;
                var height = Float.NaN;
                do {
                    if (mover.move(location, 0, 0, -2 * DROP_START) == ScoutMover.BLOCKED) {
                        height = location[2];
                        break;
                    }
                } while (location[2] > LOWEST);
                landed[cellX * limit + cellY] = height;
            }
            progress.report("Dropping", done.incrementAndGet(), limit);
        });
        for (var cellX = 0; cellX < limit; cellX++) {
            for (var cellY = 0; cellY < limit; cellY++) {
                var height = landed[cellX * limit + cellY];
                if (Float.isNaN(height)) {
                    continue;
                }
                var layer = region.add(
                        cellX,
                        cellY,
                        BuiltRegion.heightStep(height),
                        region.getMinX() + 8 + cellX * BuiltRegion.CELL_SIZE,
                        region.getMinY() + 8 + cellY * BuiltRegion.CELL_SIZE,
                        height,
                        true);
                if (layer >= 0) {
                    queue.add(layer);
                }
            }
        }
    }

    private void dropAtStarts(float[] startPoints) {
        var mover = mover();
        var location = new float[3];
        for (var p = 0; p + 2 < startPoints.length; p += 3) {
            if (!findCellCenter(startPoints[p], startPoints[p + 1], location)) {
                continue;
            }
            location[2] = startPoints[p + 2];
            var cellX = region.toCellX(location[0]);
            var cellY = region.toCellY(location[1]);
            var landed = false;
            do {
                if (mover.move(location, 0, 0, -1000f) == ScoutMover.BLOCKED) {
                    landed = true;
                    break;
                }
            } while (location[2] > LOWEST);
            if (!landed || location[2] <= LOWEST) {
                continue;
            }
            var layer = region.add(
                    cellX, cellY, BuiltRegion.heightStep(location[2]), location[0], location[1], location[2], false);
            if (layer >= 0) {
                queue.add(layer);
            }
        }
    }

    private void dropAtLateStarts(float[] points) {
        var count = points.length / 5;
        var landed = new float[count];
        runInParallel(count, (mover, point) -> {
            var location = new float[3];
            var offset = point * 5;
            landed[point] = Float.NaN;
            if (!findCellCenter(points[offset], points[offset + 1], location)) {
                return;
            }
            location[2] = points[offset + 2];
            var lowest = Math.max(LOWEST, points[offset + 3] - 8f);
            var first = true;
            do {
                var before = location[2];
                if (mover.move(location, 0, 0, -1000f) == ScoutMover.BLOCKED) {
                    if (!first || location[2] != before) {
                        landed[point] = location[2];
                    }
                    return;
                }
                first = false;
            } while (location[2] > lowest);
        });
        var location = new float[3];
        for (var point = 0; point < count; point++) {
            var z = landed[point];
            var offset = point * 5;
            if (Float.isNaN(z) || z <= LOWEST || !findCellCenter(points[offset], points[offset + 1], location)) {
                continue;
            }
            var height = BuiltRegion.heightStep(z) * 8;
            if (height < points[offset + 3] || height > points[offset + 4]) {
                continue;
            }
            var x = location[0];
            var y = location[1];
            if (hasLayerNear(x, y, z)) {
                continue;
            }
            var layer = region.add(region.toCellX(x), region.toCellY(y), BuiltRegion.heightStep(z), x, y, z, false);
            if (layer >= 0) {
                late.set(layer);
                queue.add(layer);
            }
        }
    }

    private boolean hasLayerNear(float x, float y, float z) {
        var cellX = region.toCellX(x);
        var cellY = region.toCellY(y);
        if (cellX < 0 || cellY < 0) {
            return false;
        }
        var step = BuiltRegion.heightStep(z) * 8;
        for (var other = region.getFirstLayer(cellX, cellY); other >= 0; other = region.nextLayer(other)) {
            if (Math.abs(region.getHeight(other) - step) <= SAME_FLOOR) {
                return true;
            }
        }
        return false;
    }

    private boolean findCellCenter(float x, float y, float[] location) {
        var offsetX = (int) x - region.getMinX();
        var offsetY = (int) y - region.getMinY();
        offsetX = (offsetX + (offsetX < 0 ? 15 : 0)) & ~15;
        offsetY = (offsetY + (offsetY < 0 ? 15 : 0)) & ~15;
        location[0] = offsetX + 8 + region.getMinX();
        location[1] = offsetY + 8 + region.getMinY();
        return region.toCellX(location[0]) >= 0 && region.toCellY(location[1]) >= 0;
    }

    private void floodFillLayers() {
        var outcomes = new byte[BATCH * 4];
        var positions = new float[BATCH * 4 * 3];
        while (head < queue.size()) {
            var batch = Math.min(BATCH, queue.size() - head);
            var first = head;
            var order = nearbyOrder(first, batch);
            runInParallel(batch, (mover, index) -> {
                var item = order[index];
                walkNeighbors(mover, queue.get(first + item), item, outcomes, positions);
            });
            for (var item = 0; item < batch; item++) {
                var layer = queue.get(first + item);
                for (var direction = 0; direction < 4; direction++) {
                    var slot = item * 4 + direction;
                    switch (outcomes[slot]) {
                        case OUTSIDE -> region.setOpen(layer, direction, true);
                        case STEP_10 -> {
                            if (positions[slot * 3 + 2] > LOWEST) {
                                addNeighbor(layer, direction, positions, slot * 3, true);
                            } else {
                                region.setOpen(layer, direction, false);
                            }
                        }
                        case STEP_20 -> {
                            region.setOpen(layer, direction, false);
                            if (positions[slot * 3 + 2] > LOWEST) {
                                addNeighbor(layer, direction, positions, slot * 3, false);
                            }
                        }
                        default -> region.setOpen(layer, direction, false);
                    }
                }
            }
            head += batch;
            progress.report("Walking", head, queue.size());
        }
    }

    private void walkNeighbors(ScoutMover mover, int layer, int item, byte[] outcomes, float[] positions) {
        var x = region.getX(layer);
        var y = region.getY(layer);
        var location = new float[3];
        for (var direction = 0; direction < 4; direction++) {
            var slot = item * 4 + direction;
            var targetX = (BuiltRegion.DIRECTION_X[direction] << 4) + x;
            var targetY = (BuiltRegion.DIRECTION_Y[direction] << 4) + y;
            if (targetX < region.getMinX()
                    || targetX > region.getMinX() + BuiltRegion.REGION_SIZE - 1
                    || targetY < region.getMinY()
                    || targetY > region.getMinY() + BuiltRegion.REGION_SIZE - 1) {
                outcomes[slot] = OUTSIDE;
                continue;
            }
            location[0] = x;
            location[1] = y;
            location[2] = region.getZ(layer) + 1f;
            byte outcome;
            if (mover.reach(location, targetX, targetY, 1f)) {
                outcome = STEP_10;
            } else if (mover.hasFallenOut()) {
                outcome = FAILED;
            } else {
                outcome = mover.reach(location, targetX, targetY, 2f) ? STEP_20 : FAILED;
            }
            outcomes[slot] = outcome;
            positions[slot * 3] = location[0];
            positions[slot * 3 + 1] = location[1];
            positions[slot * 3 + 2] = location[2];
        }
    }

    private void addNeighbor(int layer, int direction, float[] positions, int offset, boolean open) {
        var x = positions[offset];
        var y = positions[offset + 1];
        var z = positions[offset + 2];
        var fromLate = late.get(layer);
        if (region.find(x, y, z) < 0
                && region.find(x, y, z + 8f) < 0
                && region.find(x, y, z - 8f) < 0
                && (!fromLate || !hasLayerNear(x, y, z))) {
            var added = region.add(region.toCellX(x), region.toCellY(y), BuiltRegion.heightStep(z), x, y, z, false);
            if (added >= 0) {
                if (fromLate) {
                    late.set(added);
                }
                queue.add(added);
            }
        }
        region.setOpen(layer, direction, open);
    }

    @FunctionalInterface
    private interface Task {
        void run(ScoutMover mover, int item);
    }

    private int[] nearbyOrder(int first, int batch) {
        var keys = new long[batch];
        for (var item = 0; item < batch; item++) {
            var layer = queue.get(first + item);
            var cellX = region.toCellX(region.getX(layer)) & 2047;
            var cellY = region.toCellY(region.getY(layer)) & 2047;
            keys[item] = (long) interleaveBits(cellX, cellY) << 20 | item;
        }
        Arrays.sort(keys);
        var order = new int[batch];
        for (var item = 0; item < batch; item++) {
            order[item] = (int) (keys[item] & 0xFFFFF);
        }
        return order;
    }

    private void runInParallel(int count, Task task) {
        checkCancelled();
        var nextItem = new AtomicInteger();
        var run = Math.clamp(count / (settings.threads() * 8), 1, 16);
        var futures = new ArrayList<Future<?>>();
        for (var thread = 0; thread < settings.threads(); thread++) {
            futures.add(executor.submit(() -> {
                var mover = movers.get();
                for (var start = nextItem.getAndAdd(run); start < count; start = nextItem.getAndAdd(run)) {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    for (var item = start; item < Math.min(start + run, count); item++) {
                        task.run(mover, item);
                    }
                }
            }));
        }
        for (var future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Geodata build interrupted");
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Geodata build failed", failure.getCause());
            }
        }
        checkCancelled();
    }

    private ScoutMover mover() {
        return new ScoutMover(world, settings.radius(), settings.halfHeight());
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Geodata build cancelled");
        }
    }

    private static int interleaveBits(int x, int y) {
        var key = 0;
        for (var bit = 0; bit < 11; bit++) {
            key |= (x >> bit & 1) << 2 * bit | (y >> bit & 1) << 2 * bit + 1;
        }
        return key;
    }
}
