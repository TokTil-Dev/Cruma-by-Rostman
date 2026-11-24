package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoPathNodeBuild.Region;
import com.rost.cruma.core.geodata.PathNodeGraph.ZoneTable;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class GeoPathNodeLinks {
    private static final int MAX_CANDIDATES = 9 * 255;
    private static final int MAX_NODES = 0x00ffffff;
    private static final int MAX_WORKERS = 4;
    private static final long PARALLEL_NODE_THRESHOLD = 65536;
    private static final int SECTION_CHUNK = 16;
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();
    private static final String NATIVE_LINE_END = "\r\r\n";

    private GeoPathNodeLinks() {}

    static void generate(GeoPathNodeBuild build) throws IOException {
        var workers = build.nodes.size() < PARALLEL_NODE_THRESHOLD
                ? 1
                : Math.min(MAX_WORKERS, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        generate(build, workers);
    }

    static void generate(GeoPathNodeBuild build, int workers) throws IOException {
        generate(build, workers, () -> {});
    }

    @FunctionalInterface
    interface WorkerStart {
        void run() throws IOException;
    }

    static void generate(GeoPathNodeBuild build, int workers, WorkerStart workerStart) throws IOException {
        Objects.requireNonNull(workerStart);
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException("Path-node worker count must be between 1 and " + MAX_WORKERS);
        }
        var regions = orderedRegions(build);
        verifySections(build, regions, false);
        var sections = regions.size() * 16384;
        if (workers == 1) {
            var heaps = heaps();
            var done = new int[1];
            Checkpoint checkpoint = detail -> build.checkpoint(done[0], sections, detail);
            for (var section = 0; section < sections; section++) {
                done[0] = section;
                connectSection(build, regions, section, heaps, checkpoint);
            }
            return;
        }
        var stopping = new AtomicBoolean();
        var nextSection = new AtomicInteger();
        var factory = Thread.ofPlatform()
                .daemon(false)
                .name("cruma-pathnode-links-" + POOL_SEQUENCE.incrementAndGet() + "-", 1)
                .factory();
        var workerThreads = new ConcurrentLinkedQueue<Thread>();
        var executor = Executors.newFixedThreadPool(workers, task -> {
            var thread = factory.newThread(task);
            workerThreads.add(thread);
            return thread;
        });
        var completions = new ExecutorCompletionService<Void>(executor);
        try {
            for (var worker = 0; worker < workers; worker++) {
                completions.submit(() -> {
                    workerStart.run();
                    var heaps = heaps();
                    Checkpoint checkpoint = detail -> {
                        if (stopping.get() || Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("Path-node connections interrupted");
                        }
                    };
                    while (true) {
                        checkpoint.check("Connecting path nodes");
                        var first = nextSection.getAndAdd(SECTION_CHUNK);
                        if (first >= sections) {
                            return null;
                        }
                        var end = Math.min(sections, first + SECTION_CHUNK);
                        for (var section = first; section < end; section++) {
                            connectSection(build, regions, section, heaps, checkpoint);
                        }
                    }
                });
            }
            var finished = 0;
            while (finished < workers) {
                build.checkpoint(Math.min(sections, nextSection.get()), sections, "Connecting path nodes");
                var completed = completions.poll(50, TimeUnit.MILLISECONDS);
                if (completed != null) {
                    completed.get();
                    finished++;
                }
            }
            build.checkpoint(sections, sections, "Connecting path nodes");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var failure = new InterruptedIOException("Path-node connections interrupted");
            failure.initCause(interrupted);
            throw failure;
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof IOException error) {
                throw error;
            }
            if (cause instanceof RuntimeException error) {
                throw error;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Path-node connection worker failed", cause);
        } finally {
            stopping.set(true);
            stopAndJoin(executor, workerThreads);
        }
    }

    static void write(GeoPathNodeBuild build, Path directory) throws IOException {
        write(build, directory, ZoneTable.Y10_TO_26);
    }

    static void write(GeoPathNodeBuild build, Path directory, ZoneTable table) throws IOException {
        requireZoneTable(build, table);
        var regions = orderedRegions(build);
        var total = verifySections(build, regions, true);
        var byTile = new Region[20][17];
        for (var region : regions) {
            byTile[region.tileX() - 10][region.tileY() - 10] = region;
        }
        try (var index = Files.newBufferedWriter(
                        directory.resolve("pathnode.idx"), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
                var text = Files.newBufferedWriter(
                        directory.resolve("pathnode.txt"), StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
                var binary = new BufferedOutputStream(
                        Files.newOutputStream(directory.resolve("pathnode.bin"), StandardOpenOption.CREATE_NEW),
                        65536)) {
            var line = new TextLine();
            line.reset().append("Total = ").append(total).append(NATIVE_LINE_END);
            line.writeTo(index);
            line.writeTo(text);
            var record = new byte[PathNodeGraph.NODE_BYTES];
            binary.write(record);
            var written = 0;
            for (var tileX = 10; tileX <= 29; tileX++) {
                for (var tileY = 10; tileY <= table.lastTileY; tileY++) {
                    build.checkpoint("Writing path-node graph");
                    var region = byTile[tileX - 10][tileY - 10];
                    line.reset()
                            .append("--- Zone(")
                            .append(tileX)
                            .append(',')
                            .append(tileY)
                            .append(")[")
                            .append(region == null ? 0 : 1)
                            .append("] ---")
                            .append(NATIVE_LINE_END);
                    line.writeTo(index);
                    line.writeTo(text);
                    if (region == null) {
                        continue;
                    }
                    for (var sectionX = 0; sectionX < 128; sectionX++) {
                        for (var sectionY = 0; sectionY < 128; sectionY++) {
                            build.checkpoint("Writing path-node graph");
                            var x = region.minX() + sectionX * 256;
                            var y = region.minY() + sectionY * 256;
                            var count = build.nodes.count(x, y);
                            line.reset()
                                    .append(x)
                                    .append(' ')
                                    .append(y)
                                    .append(' ')
                                    .append(count)
                                    .append(NATIVE_LINE_END);
                            line.writeTo(index);
                            line.reset()
                                    .append("--- Section(")
                                    .append(x)
                                    .append(',')
                                    .append(y)
                                    .append(") Count(")
                                    .append(count)
                                    .append(") ---")
                                    .append(NATIVE_LINE_END);
                            line.writeTo(text);
                            for (var node = build.nodes.getHead(x, y); node != 0; node = build.nodes.next(node)) {
                                build.checkpoint("Writing path-node records");
                                var assigned = build.nodes.getIndex(node);
                                if (assigned != ++written) {
                                    throw new IOException("Path-node order changed during output");
                                }
                                var nodeX = build.nodes.getX(node);
                                var nodeY = build.nodes.getY(node);
                                var nodeZ = build.nodes.getZ(node);
                                putInt(record, 0, nodeX);
                                putInt(record, 4, nodeY);
                                putInt(record, 8, nodeZ);
                                var neighborCount = neighborCount(build, node);
                                line.reset()
                                        .append(assigned)
                                        .append(" : (")
                                        .append(nodeX)
                                        .append(' ')
                                        .append(nodeY)
                                        .append(' ')
                                        .append(nodeZ)
                                        .append(") (")
                                        .append(neighborCount)
                                        .append(") - ");
                                for (var slot = 0; slot < 8; slot++) {
                                    var neighbor = build.nodes.getLink(node, slot);
                                    var neighborIndex = neighbor == 0 ? 0 : build.nodes.getIndex(neighbor);
                                    if (neighbor != 0 && (neighborIndex < 1 || neighborIndex > total)) {
                                        throw new IOException("Unindexed path-node neighbor");
                                    }
                                    putInt(record, 12 + slot * 4, neighborIndex);
                                    if (slot < neighborCount) {
                                        line.append(neighborIndex).append(' ');
                                    }
                                }
                                line.append(NATIVE_LINE_END).writeTo(text);
                                binary.write(record);
                            }
                        }
                    }
                }
            }
            if (written != total) {
                throw new IOException("Path-node count changed during output");
            }
        }
    }

    static int directionBucket(int sourceX, int sourceY, int targetX, int targetY) {
        var angle = (double) (float) Math.atan2((float) targetY - (float) sourceY, (float) targetX - (float) sourceX)
                + Math.PI
                + Math.PI / 6;
        if (angle >= 2 * Math.PI) {
            angle += -2 * Math.PI;
        }
        return Math.clamp(4 - (int) ((angle / -Math.PI) * 2.0), 0, 7);
    }

    static final class Heap {
        private int[] nodes = new int[32];
        private double[] distances = new double[32];
        private int size;

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void add(int node, double distance) throws IOException {
            if (size == MAX_CANDIDATES) {
                throw new IOException("Path-node candidate count exceeds nine full sections");
            }
            if (size == nodes.length) {
                var capacity = Math.min(MAX_CANDIDATES, nodes.length * 2);
                nodes = Arrays.copyOf(nodes, capacity);
                distances = Arrays.copyOf(distances, capacity);
            }
            var hole = size++;
            while (hole > 0) {
                var parent = (hole - 1) / 2;
                if (distances[parent] <= distance) {
                    break;
                }
                nodes[hole] = nodes[parent];
                distances[hole] = distances[parent];
                hole = parent;
            }
            nodes[hole] = node;
            distances[hole] = distance;
        }

        int remove() {
            if (size == 0) {
                throw new IllegalStateException("Empty candidate heap");
            }
            var result = nodes[0];
            var last = --size;
            if (last == 0) {
                return result;
            }
            var savedNode = nodes[last];
            var savedDistance = distances[last];
            var hole = 0;
            var right = 2;
            while (right < size) {
                var child = distances[right - 1] < distances[right] ? right - 1 : right;
                nodes[hole] = nodes[child];
                distances[hole] = distances[child];
                hole = child;
                right = hole * 2 + 2;
            }
            if (right == size) {
                nodes[hole] = nodes[right - 1];
                distances[hole] = distances[right - 1];
                hole = right - 1;
            }
            while (hole > 0) {
                var parent = (hole - 1) / 2;
                if (!(savedDistance < distances[parent])) {
                    break;
                }
                nodes[hole] = nodes[parent];
                distances[hole] = distances[parent];
                hole = parent;
            }
            nodes[hole] = savedNode;
            distances[hole] = savedDistance;
            return result;
        }
    }

    static void requireZoneTable(GeoPathNodeBuild build, ZoneTable table) throws IOException {
        for (var region : build.regions) {
            if (region.tileY() > table.lastTileY) {
                throw new IOException("Region "
                        + region.tileX()
                        + "_"
                        + region.tileY()
                        + " lies past tile Y "
                        + table.lastTileY
                        + ", which "
                        + table.getZones()
                        + "-zone (C1-IL) servers cannot load: remove it from geo_index.txt or choose Kamael+");
            }
        }
    }

    private static Heap[] heaps() {
        var heaps = new Heap[8];
        for (var bucket = 0; bucket < heaps.length; bucket++) {
            heaps[bucket] = new Heap();
        }
        return heaps;
    }

    @FunctionalInterface
    private interface Checkpoint {
        void check(String detail) throws IOException;
    }

    private static void connectSection(
            GeoPathNodeBuild build, List<Region> regions, int section, Heap[] heaps, Checkpoint checkpoint)
            throws IOException {
        checkpoint.check("Connecting path nodes");
        var region = regions.get(section / 16384);
        var x = region.minX() + (section % 16384 / 128) * 256;
        var y = region.minY() + (section % 128) * 256;
        for (var node = build.nodes.getHead(x, y); node != 0; node = build.nodes.next(node)) {
            checkpoint.check("Connecting path nodes");
            for (var heap : heaps) {
                heap.clear();
            }
            for (var slot = 0; slot < 8; slot++) {
                build.nodes.setLink(node, slot, 0);
            }
            var sourceX = build.nodes.getX(node);
            var sourceY = build.nodes.getY(node);
            var sourceZ = build.nodes.getZ(node);
            for (var offsetX = -256; offsetX <= 256; offsetX += 256) {
                for (var offsetY = -256; offsetY <= 256; offsetY += 256) {
                    var first = build.nodes.getHead(sourceX + offsetX, sourceY + offsetY);
                    for (var candidate = first; candidate != 0; candidate = build.nodes.next(candidate)) {
                        checkpoint.check("Checking directed path-node connections");
                        if (candidate == node) {
                            continue;
                        }
                        var targetX = build.nodes.getX(candidate);
                        var targetY = build.nodes.getY(candidate);
                        var targetZ = build.nodes.getZ(candidate);
                        if (!build.canMove(sourceX, sourceY, sourceZ, targetX, targetY, targetZ)) {
                            continue;
                        }
                        var deltaX = (double) sourceX - targetX;
                        var deltaY = (double) sourceY - targetY;
                        var deltaZ = (double) sourceZ - targetZ;
                        var distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                        heaps[directionBucket(sourceX, sourceY, targetX, targetY)].add(candidate, distance);
                    }
                }
            }
            var remaining = 8;
            var linked = 0;
            while (remaining > 0) {
                var found = false;
                for (var heap : heaps) {
                    if (heap.isEmpty()) {
                        continue;
                    }
                    var candidate = heap.remove();
                    var duplicate = false;
                    for (var slot = 0; slot < linked; slot++) {
                        duplicate |= build.nodes.getLink(node, slot) == candidate;
                    }
                    if (!duplicate) {
                        build.nodes.setLink(node, linked++, candidate);
                    }
                    found = true;
                    if (--remaining == 0) {
                        break;
                    }
                }
                if (!found) {
                    break;
                }
            }
        }
    }

    private static void stopAndJoin(ExecutorService executor, Queue<Thread> workerThreads) {
        var interrupted = Thread.interrupted();
        try {
            executor.shutdownNow();
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            for (var thread : workerThreads) {
                while (thread.isAlive()) {
                    try {
                        thread.join();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static List<Region> orderedRegions(GeoPathNodeBuild build) {
        var regions = new ArrayList<>(build.regions);
        regions.sort(Comparator.comparingInt(Region::tileX).thenComparingInt(Region::tileY));
        return regions;
    }

    private static int verifySections(GeoPathNodeBuild build, List<Region> regions, boolean assignIndices)
            throws IOException {
        if (build.nodes.size() > MAX_NODES) {
            throw new IOException("Path-node count exceeds the server 24-bit index limit");
        }
        var total = 0;
        for (var region : regions) {
            for (var sectionX = 0; sectionX < 128; sectionX++) {
                for (var sectionY = 0; sectionY < 128; sectionY++) {
                    build.checkpoint(
                            assignIndices ? "Assigning native path-node indices" : "Checking path-node section limits");
                    var x = region.minX() + sectionX * 256;
                    var y = region.minY() + sectionY * 256;
                    var count = build.nodes.count(x, y);
                    if (count > 255) {
                        throw new IOException(
                                "Too many path nodes in section " + x + ", " + y + ": " + count + " (maximum 255)");
                    }
                    var actual = 0;
                    for (var node = build.nodes.getHead(x, y); node != 0; node = build.nodes.next(node)) {
                        if (++actual > 255 || actual > count) {
                            throw new IOException("Invalid path-node section chain");
                        }
                        if ((build.nodes.getX(node) & ~255) != x || (build.nodes.getY(node) & ~255) != y) {
                            throw new IOException("Path-node coordinates do not belong to their section");
                        }
                        total++;
                        if (assignIndices) {
                            build.nodes.setIndex(node, total);
                        }
                    }
                    if (actual != count) {
                        throw new IOException("Path-node section count does not match its chain");
                    }
                }
            }
        }
        if (total != build.nodes.size()) {
            throw new IOException("Path-node table contains nodes outside loaded regions");
        }
        return total;
    }

    private static int neighborCount(GeoPathNodeBuild build, int node) throws IOException {
        var count = 0;
        var ended = false;
        for (var slot = 0; slot < 8; slot++) {
            if (build.nodes.getLink(node, slot) == 0) {
                ended = true;
            } else if (ended) {
                throw new IOException("Path-node neighbors follow a zero sentinel");
            } else {
                count++;
            }
        }
        return count;
    }

    private static final class TextLine {
        private static final char[] DIGIT_PAIRS = digitPairs();

        private final char[] chars = new char[192];
        private final char[] decimal = new char[11];
        private int length;

        TextLine reset() {
            length = 0;
            return this;
        }

        TextLine append(String value) {
            value.getChars(0, value.length(), chars, length);
            length += value.length();
            return this;
        }

        TextLine append(char value) {
            chars[length++] = value;
            return this;
        }

        TextLine append(int value) {
            var negative = value < 0;
            var number = negative ? value : -value;
            var position = decimal.length;
            while (number <= -100) {
                var quotient = number / 100;
                var pair = (quotient * 100 - number) * 2;
                decimal[--position] = DIGIT_PAIRS[pair + 1];
                decimal[--position] = DIGIT_PAIRS[pair];
                number = quotient;
            }
            var pair = -number * 2;
            decimal[--position] = DIGIT_PAIRS[pair + 1];
            if (number <= -10) {
                decimal[--position] = DIGIT_PAIRS[pair];
            }
            if (negative) {
                decimal[--position] = '-';
            }
            var count = decimal.length - position;
            System.arraycopy(decimal, position, chars, length, count);
            length += count;
            return this;
        }

        void writeTo(BufferedWriter writer) throws IOException {
            writer.write(chars, 0, length);
        }

        private static char[] digitPairs() {
            var pairs = new char[200];
            for (var value = 0; value < 100; value++) {
                pairs[value * 2] = (char) ('0' + value / 10);
                pairs[value * 2 + 1] = (char) ('0' + value % 10);
            }
            return pairs;
        }
    }

    private static void putInt(byte[] record, int offset, int value) {
        record[offset] = (byte) value;
        record[offset + 1] = (byte) (value >>> 8);
        record[offset + 2] = (byte) (value >>> 16);
        record[offset + 3] = (byte) (value >>> 24);
    }
}
