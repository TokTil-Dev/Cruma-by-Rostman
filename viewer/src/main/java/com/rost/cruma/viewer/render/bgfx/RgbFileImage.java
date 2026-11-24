package com.rost.cruma.viewer.render.bgfx;

import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BandedSampleModel;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.PixelInterleavedSampleModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Vector;

final class RgbFileImage implements RenderedImage {
    private final FileChannel pixels;
    private final int width;
    private final int height;
    private final ColorModel colors = new ComponentColorModel(
            ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);
    private final SampleModel samples;

    RgbFileImage(FileChannel pixels, int width, int height) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        samples = new PixelInterleavedSampleModel(
                DataBuffer.TYPE_BYTE, width, 1, 3, Math.multiplyExact(width, 3), new int[] {0, 1, 2});
    }

    @Override
    public Raster getData(Rectangle area) {
        if (area.x < 0
                || area.y < 0
                || area.width < 1
                || area.height < 1
                || (long) area.x + area.width > width
                || (long) area.y + area.height > height) {
            throw new IllegalArgumentException("Image read lies outside the radar bounds.");
        }
        var raster = Raster.createInterleavedRaster(
                DataBuffer.TYPE_BYTE, area.width, area.height, 3, new Point(area.x, area.y));
        var bytes = ((DataBufferByte) raster.getDataBuffer()).getData();
        int rowBytes = Math.multiplyExact(area.width, 3);
        try {
            for (int y = 0; y < area.height; y++) {
                var row = ByteBuffer.wrap(bytes, y * rowBytes, rowBytes);
                long position = ((long) (area.y + y) * width + area.x) * 3;
                while (row.hasRemaining()) {
                    int read = pixels.read(row, position);
                    if (read < 0) {
                        break;
                    }
                    position += read;
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read capture radar pixels.", failure);
        }
        return raster;
    }

    @Override
    public Raster getData() {
        return Raster.createRaster(
                new BandedSampleModel(DataBuffer.TYPE_BYTE, width, height, 3),
                new RgbFileDataBuffer(pixels, width, height),
                new Point());
    }

    @Override
    public WritableRaster copyData(WritableRaster target) {
        if (target == null) {
            target = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 3, new Point());
        }
        var area = target.getBounds().intersection(new Rectangle(0, 0, width, height));
        if (!area.isEmpty()) {
            for (int y = area.y; y < area.y + area.height; y++) {
                target.setRect(getData(new Rectangle(area.x, y, area.width, 1)));
            }
        }
        return target;
    }

    @Override
    public Raster getTile(int x, int y) {
        if (x != 0 || y < 0 || y >= height) {
            throw new IllegalArgumentException("Image row lies outside the radar bounds.");
        }
        return getData(new Rectangle(0, y, width, 1));
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinX() {
        return 0;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getNumXTiles() {
        return 1;
    }

    @Override
    public int getNumYTiles() {
        return height;
    }

    @Override
    public int getMinTileX() {
        return 0;
    }

    @Override
    public int getMinTileY() {
        return 0;
    }

    @Override
    public int getTileWidth() {
        return width;
    }

    @Override
    public int getTileHeight() {
        return 1;
    }

    @Override
    public int getTileGridXOffset() {
        return 0;
    }

    @Override
    public int getTileGridYOffset() {
        return 0;
    }

    @Override
    public ColorModel getColorModel() {
        return colors;
    }

    @Override
    public SampleModel getSampleModel() {
        return samples;
    }

    @Override
    public Vector<RenderedImage> getSources() {
        return null;
    }

    @Override
    public Object getProperty(String name) {
        return Image.UndefinedProperty;
    }

    @Override
    public String[] getPropertyNames() {
        return null;
    }
}
