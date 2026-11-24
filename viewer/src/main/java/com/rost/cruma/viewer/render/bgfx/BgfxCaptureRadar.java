package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.viewer.application.WindowContext.Tile;
import com.rost.cruma.viewer.config.CaptureRadarLayout;
import com.rost.cruma.viewer.config.Config.Capture.ImageFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.FileImageOutputStream;

final class BgfxCaptureRadar implements AutoCloseable {
    private final Path output;
    private final int tileResolution;
    private final int minX;
    private final int minY;
    private final CaptureRadarLayout layout;
    private final ImageFormat format;
    private final int jpegQuality;
    private final Path temporaryPixels;
    private final FileChannel pixels;
    private final ByteBuffer tileRow;
    private final Set<Long> occupied = new HashSet<>();

    BgfxCaptureRadar(
            Path output,
            int tileResolution,
            int minX,
            int maxX,
            int minY,
            int maxY,
            ImageFormat format,
            int jpegQuality) {
        if (output == null
                || tileResolution < 1
                || minX > maxX
                || minY > maxY
                || format == null
                || jpegQuality < 1
                || jpegQuality > 100) {
            throw new IllegalArgumentException("invalid capture radar configuration");
        }
        this.output = output.toAbsolutePath();
        this.tileResolution = tileResolution;
        this.minX = minX;
        this.minY = minY;
        this.format = format;
        this.jpegQuality = jpegQuality;
        layout = CaptureRadarLayout.forBounds(tileResolution, minX, maxX, minY, maxY);
        layout.checkFormat(format);
        layout.checkMemory(Runtime.getRuntime().maxMemory());
        tileRow = ByteBuffer.allocate(Math.multiplyExact(tileResolution, 3));
        Path pending = null;
        try {
            Files.createDirectories(this.output.getParent());
            pending = Files.createTempFile(this.output.getParent(), ".cruma-radar-", ".rgb");
            pixels = FileChannel.open(pending, StandardOpenOption.READ, StandardOpenOption.WRITE);
            temporaryPixels = pending;
        } catch (IOException failure) {
            if (pending != null) {
                try {
                    Files.deleteIfExists(pending);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw new UncheckedIOException("Failed to prepare capture radar " + this.output, failure);
        }
    }

    void add(Tile tile, int sourceWidth, int sourceHeight, ByteBuffer rgba, boolean originBottomLeft) {
        if (tile == null
                || rgba == null
                || sourceWidth < 1
                || sourceHeight < 1
                || rgba.remaining() < Math.multiplyExact(Math.multiplyExact(sourceWidth, sourceHeight), 4)) {
            throw new IllegalArgumentException("invalid radar tile payload");
        }
        long column = (long) tile.mapX() - minX;
        long row = (long) tile.mapY() - minY;
        if (column < 0 || column >= layout.columns() || row < 0 || row >= layout.rows()) {
            throw new IllegalArgumentException("tile " + tile + " lies outside the radar bounds");
        }
        long cell = row * layout.columns() + column;
        if (occupied.contains(cell)) {
            throw new IllegalStateException("duplicate radar tile " + tile);
        }
        var source = rgba.duplicate();
        var sourceBase = source.position();
        var destinationX = column * tileResolution;
        var destinationY = row * tileResolution;
        try {
            long tileBytes = (long) tileResolution * tileResolution * 3;
            if (Files.getFileStore(temporaryPixels).getUsableSpace() < tileBytes) {
                throw new IOException("Not enough free disk space for a radar tile. Need " + tileBytes + " bytes.");
            }
            for (var y = 0; y < tileResolution; y++) {
                var displaySourceY = sampleCoordinate(y, tileResolution, sourceHeight);
                var sourceY = originBottomLeft ? sourceHeight - 1 - displaySourceY : displaySourceY;
                var sourceRow = sourceBase + sourceY * sourceWidth * 4;
                tileRow.clear();
                for (var x = 0; x < tileResolution; x++) {
                    var sourceX = sampleCoordinate(x, tileResolution, sourceWidth);
                    var offset = sourceRow + sourceX * 4;
                    tileRow.put(source.get(offset)).put(source.get(offset + 1)).put(source.get(offset + 2));
                }
                tileRow.flip();
                pixels.position(((destinationY + y) * layout.width() + destinationX) * 3);
                while (tileRow.hasRemaining()) {
                    pixels.write(tileRow);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to store radar tile " + tile + " for " + output, failure);
        }
        occupied.add(cell);
    }

    void write() {
        if (occupied.isEmpty()) {
            throw new IllegalStateException("capture radar has no successfully captured tiles");
        }
        try {
            var encoded = Files.createTempFile(output.getParent(), ".cruma-radar-", "." + format.getExtension());
            try {
                encode(encoded);
                try {
                    Files.move(encoded, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(encoded, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(encoded);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write capture radar " + output, e);
        }
    }

    int getTilesAdded() {
        return occupied.size();
    }

    @Override
    public void close() {
        IOException failure = null;
        try {
            pixels.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        try {
            Files.deleteIfExists(temporaryPixels);
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) {
            throw new UncheckedIOException("Failed to clean up capture radar " + output, failure);
        }
    }

    private void encode(Path encoded) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName(format.getExtension());
        if (!writers.hasNext()) {
            throw new IllegalStateException("No " + format.name() + " writer is available for radar map " + output);
        }
        var writer = writers.next();
        try (var stream = new FileImageOutputStream(encoded.toFile())) {
            writer.setOutput(stream);
            var parameters = writer.getDefaultWriteParam();
            if (format == ImageFormat.JPEG) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(jpegQuality / 100f);
            }
            writer.write(
                    null,
                    new IIOImage(new RgbFileImage(pixels, layout.width(), layout.height()), null, null),
                    parameters);
        } finally {
            writer.dispose();
        }
    }

    private static int sampleCoordinate(int destination, int destinationSize, int sourceSize) {
        return Math.min(sourceSize - 1, (int) (((2L * destination + 1L) * sourceSize) / (2L * destinationSize)));
    }
}
