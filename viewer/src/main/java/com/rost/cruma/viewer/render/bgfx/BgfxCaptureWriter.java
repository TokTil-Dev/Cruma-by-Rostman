package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.application.WindowContext.Tile;
import com.rost.cruma.viewer.config.CaptureView;
import com.rost.cruma.viewer.config.Config.Capture.Output;
import com.rost.cruma.viewer.config.Config.Viewer.World;
import com.rost.cruma.viewer.render.api.ReadbackTicket;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.stb.STBImageWrite;
import org.lwjgl.system.MemoryUtil;

final class BgfxCaptureWriter implements AutoCloseable {
    static final int CLEAR_GRAY = 0x1a;

    private static final int MAX_PENDING_TILE_JOBS = 2;

    private static final Logger log = LogManager.getLogger(BgfxCaptureWriter.class);

    private final boolean originBottomLeft;
    private final Output captureOutput;
    private final BgfxCaptureRadar radar;
    private final boolean acceptUniformTiles;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "bgfx-capture-writer");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Waiting> waiting = new ArrayList<>();
    private final List<CompletableFuture<Void>> writes = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private boolean closed;
    private boolean writeFailed;

    BgfxCaptureWriter(boolean originBottomLeft) {
        this(originBottomLeft, null, null);
    }

    BgfxCaptureWriter(boolean originBottomLeft, Output captureOutput, World world) {
        this(originBottomLeft, captureOutput, world, false);
    }

    BgfxCaptureWriter(boolean originBottomLeft, Output captureOutput, World world, boolean acceptUniformTiles) {
        if ((captureOutput == null) != (world == null)) {
            throw new IllegalArgumentException("capture output and world must be supplied together");
        }
        this.originBottomLeft = originBottomLeft;
        this.acceptUniformTiles = acceptUniformTiles;
        this.captureOutput = captureOutput;
        this.radar = captureOutput != null && captureOutput.radar().enabled()
                ? new BgfxCaptureRadar(
                        captureOutput.radarPath(),
                        captureOutput.radar().tileResolution(),
                        world.minXTile(),
                        world.maxXTile(),
                        world.minYTile(),
                        world.maxYTile(),
                        captureOutput.radar().format(),
                        captureOutput.radar().jpegQuality())
                : null;
    }

    static void writeJpeg(Path output, int width, int height, ByteBuffer rgba, boolean originBottomLeft) {
        writeJpeg(output, width, height, rgba, originBottomLeft, 90);
    }

    static void writeJpeg(Path output, int width, int height, ByteBuffer rgba, boolean originBottomLeft, int quality) {
        validateAndCreateParent(output, width, height, rgba);
        if (quality < 1 || quality > 100) {
            throw new IllegalArgumentException("JPEG quality must be within 1..100");
        }
        var rgb = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 3));
        try {
            var source = rgba.duplicate();
            var base = source.position();
            for (var y = 0; y < height; y++) {
                var sourceY = originBottomLeft ? height - 1 - y : y;
                var row = base + sourceY * width * 4;
                for (var x = 0; x < width; x++) {
                    var pixel = row + x * 4;
                    rgb.put(source.get(pixel));
                    rgb.put(source.get(pixel + 1));
                    rgb.put(source.get(pixel + 2));
                }
            }
            rgb.flip();
            if (!STBImageWrite.stbi_write_jpg(output.toString(), width, height, 3, rgb, quality)) {
                throw new IllegalStateException("stbi_write_jpg failed for " + output);
            }
        } finally {
            MemoryUtil.memFree(rgb);
        }
    }

    static void writePng(Path output, int width, int height, ByteBuffer rgba, boolean originBottomLeft) {
        validateAndCreateParent(output, width, height, rgba);
        var pixels = MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width, height), 3));
        try {
            var source = rgba.duplicate();
            var base = source.position();
            for (var y = 0; y < height; y++) {
                var sourceY = originBottomLeft ? height - 1 - y : y;
                var row = base + sourceY * width * 4;
                for (var x = 0; x < width; x++) {
                    var pixel = row + x * 4;
                    pixels.put(source.get(pixel));
                    pixels.put(source.get(pixel + 1));
                    pixels.put(source.get(pixel + 2));
                }
            }
            pixels.flip();
            if (!STBImageWrite.stbi_write_png(output.toString(), width, height, 3, pixels, width * 3)) {
                throw new IllegalStateException("stbi_write_png failed for " + output);
            }
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    static boolean containsVisibleRgb(ByteBuffer rgba, int width, int height) {
        if (rgba == null || width < 1 || height < 1) {
            return false;
        }
        var required = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rgba.remaining() < required) {
            return false;
        }
        var source = rgba.duplicate();
        var base = source.position();
        for (var offset = 0; offset < required; offset += 4) {
            if ((source.get(base + offset) | source.get(base + offset + 1) | source.get(base + offset + 2)) != 0) {
                return true;
            }
        }
        return false;
    }

    static boolean isClearFrame(ByteBuffer rgba, int width, int height) {
        if (containsRgbVariation(rgba, width, height)) {
            return false;
        }
        if (rgba == null || rgba.remaining() < 4) {
            return true;
        }
        var base = rgba.position();
        return Byte.toUnsignedInt(rgba.get(base)) == CLEAR_GRAY
                && Byte.toUnsignedInt(rgba.get(base + 1)) == CLEAR_GRAY
                && Byte.toUnsignedInt(rgba.get(base + 2)) == CLEAR_GRAY;
    }

    static boolean containsRgbVariation(ByteBuffer rgba, int width, int height) {
        if (rgba == null || width < 1 || height < 1) {
            return false;
        }
        var required = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (rgba.remaining() < required) {
            return false;
        }
        var source = rgba.duplicate();
        var base = source.position();
        var red = source.get(base);
        var green = source.get(base + 1);
        var blue = source.get(base + 2);
        for (var offset = 4; offset < required; offset += 4) {
            if (source.get(base + offset) != red
                    || source.get(base + offset + 1) != green
                    || source.get(base + offset + 2) != blue) {
                return true;
            }
        }
        return false;
    }

    void add(ReadbackTicket ticket, Path output) {
        if (closed || ticket == null || output == null) {
            throw new IllegalStateException("capture writer is closed or received an invalid job");
        }
        waiting.add(new Waiting(ticket, output, null, null, null));
    }

    void addShot(ReadbackTicket ticket, Path output, BiConsumer<Path, Throwable> done) {
        if (closed || ticket == null || output == null || done == null) {
            throw new IllegalStateException("capture writer is closed or received an invalid job");
        }
        waiting.add(new Waiting(ticket, output, null, null, done));
    }

    void addTile(ReadbackTicket ticket, Tile tile) {
        addTile(ticket, tile, null);
    }

    void addTile(ReadbackTicket ticket, Tile tile, @Nullable CaptureView view) {
        if (closed || captureOutput == null || ticket == null || tile == null) {
            throw new IllegalStateException("tile capture writer is not configured or received an invalid job");
        }
        var extra = view != null && !view.main() ? view : null;
        var output = extra != null
                ? captureOutput.viewPath(extra)
                : captureOutput.writeTiles() ? captureOutput.tilePath(tile.mapX(), tile.mapY()) : null;
        waiting.add(new Waiting(ticket, output, tile, extra, null));
    }

    boolean canAcceptTile() {
        return !closed && waiting.size() + writes.size() < MAX_PENDING_TILE_JOBS;
    }

    void poll() {
        requireOpen();
        for (var iterator = waiting.iterator(); iterator.hasNext(); ) {
            var item = iterator.next();
            switch (item.ticket.getState()) {
                case READY -> {
                    var data = item.ticket.getData();
                    var extent = item.ticket.getExtent();
                    var future = CompletableFuture.runAsync(
                            () -> write(item, extent.width(), extent.height(), data), executor);
                    if (item.done != null) {
                        shots.add(new Shot(future, item.output, item.done));
                    } else {
                        writes.add(future);
                    }
                    iterator.remove();
                }
                case FAILED -> {
                    iterator.remove();
                    var failure = new IllegalStateException(
                            "capture " + item.label() + " failed: " + item.ticket.getFailure());
                    if (item.done == null) {
                        throw failure;
                    }
                    item.done.accept(item.output, failure);
                }
                case CANCELLED -> {
                    iterator.remove();
                    var failure = new IllegalStateException("capture " + item.label() + " was cancelled");
                    if (item.done == null) {
                        throw failure;
                    }
                    item.done.accept(item.output, failure);
                }
                case QUEUED, SUBMITTED -> {}
            }
        }
        for (var iterator = shots.iterator(); iterator.hasNext(); ) {
            var shot = iterator.next();
            if (!shot.future.isDone()) {
                continue;
            }
            iterator.remove();
            Throwable failure = null;
            try {
                shot.future.join();
            } catch (RuntimeException error) {
                failure = error.getCause() == null ? error : error.getCause();
            }
            shot.done.accept(shot.output, failure);
        }
        for (Iterator<CompletableFuture<Void>> iterator = writes.iterator(); iterator.hasNext(); ) {
            var future = iterator.next();
            if (!future.isDone()) {
                continue;
            }
            iterator.remove();
            try {
                future.join();
            } catch (RuntimeException failure) {
                writeFailed = true;
                throw failure;
            }
        }
    }

    boolean hasPendingWork() {
        return !waiting.isEmpty() || !writes.isEmpty() || !shots.isEmpty();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (var item : waiting) {
            item.ticket.cancel();
        }
        waiting.clear();
        executor.shutdown();
        RuntimeException failure = null;
        for (var write : writes) {
            try {
                write.join();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        writes.clear();
        for (var shot : shots) {
            try {
                shot.future.join();
            } catch (RuntimeException ignored) {
            }
        }
        shots.clear();
        try (var completedRadar = radar) {
            if (failure == null && !writeFailed && completedRadar != null && completedRadar.getTilesAdded() > 0) {
                completedRadar.write();
                log.info(
                        "Wrote {}-tile capture radar to {}", completedRadar.getTilesAdded(), captureOutput.radarPath());
            }
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void write(Waiting item, int width, int height, ByteBuffer rgba) {
        if (item.tile != null
                && item.view != null
                && !acceptUniformTiles
                && !containsRgbVariation(rgba, width, height)) {
            log.warn("View {} is empty, nothing was drawn between its heights. No file was written", item.view.label());
            return;
        }
        if (item.tile != null && item.view == null && !acceptUniformTiles && isClearFrame(rgba, width, height)) {
            throw new IllegalStateException(
                    "tile " + item.tile + " capture is a uniform frame. World geometry was not rendered");
        }
        if (item.tile != null && item.view == null && !containsRgbVariation(rgba, width, height)) {
            log.info(
                    "Tile {} is one colour, such as flat ground over everything else; it is written as it is",
                    item.tile);
        }
        if (item.output != null) {
            writeCapture(
                    item.output,
                    width,
                    height,
                    rgba,
                    originBottomLeft,
                    captureOutput == null ? 90 : captureOutput.jpegQuality());
        }
        if (item.tile != null && item.view == null && radar != null) {
            radar.add(item.tile, width, height, rgba, originBottomLeft);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("capture writer is closed");
        }
    }

    private record Shot(CompletableFuture<Void> future, Path output, BiConsumer<Path, Throwable> done) {}

    private record Waiting(
            ReadbackTicket ticket,
            Path output,
            Tile tile,
            @Nullable CaptureView view,
            @Nullable BiConsumer<Path, Throwable> done) {
        private String label() {
            return output != null ? output.toString() : "tile " + tile;
        }
    }

    private static void writeCapture(
            Path output, int width, int height, ByteBuffer rgba, boolean originBottomLeft, int jpegQuality) {
        if (output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            writePng(output, width, height, rgba, originBottomLeft);
        } else {
            writeJpeg(output, width, height, rgba, originBottomLeft, jpegQuality);
        }
    }

    private static void validateAndCreateParent(Path output, int width, int height, ByteBuffer rgba) {
        if (output == null
                || width < 1
                || height < 1
                || rgba == null
                || rgba.remaining() < Math.multiplyExact(Math.multiplyExact(width, height), 4)) {
            throw new IllegalArgumentException("invalid capture pixel payload");
        }
        try {
            var parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create capture directory for " + output, e);
        }
    }
}
