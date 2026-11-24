package com.rost.cruma.viewer.render.bgfx;

import java.awt.image.DataBuffer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

final class RgbFileDataBuffer extends DataBuffer {
    private final FileChannel pixels;
    private final int width;
    private final byte[] row;
    private int cachedY = -1;

    RgbFileDataBuffer(FileChannel pixels, int width, int height) {
        super(TYPE_BYTE, Math.multiplyExact(width, height), 3);
        this.pixels = pixels;
        this.width = width;
        row = new byte[Math.multiplyExact(width, 3)];
    }

    @Override
    public int getElem(int index) {
        return getElem(0, index);
    }

    @Override
    public int getElem(int bank, int index) {
        if (bank < 0 || bank >= 3 || index < 0 || index >= getSize()) {
            throw new IndexOutOfBoundsException("Radar pixel lies outside the image bounds.");
        }
        int y = index / width;
        if (cachedY != y) {
            readRow(y);
        }
        return row[(index % width) * 3 + bank] & 0xff;
    }

    @Override
    public void setElem(int index, int value) {
        throw new UnsupportedOperationException("Radar image pixels are read-only.");
    }

    @Override
    public void setElem(int bank, int index, int value) {
        throw new UnsupportedOperationException("Radar image pixels are read-only.");
    }

    private void readRow(int y) {
        Arrays.fill(row, (byte) 0);
        var buffer = ByteBuffer.wrap(row);
        long position = (long) y * row.length;
        try {
            while (buffer.hasRemaining()) {
                int read = pixels.read(buffer, position);
                if (read < 0) {
                    break;
                }
                position += read;
            }
            cachedY = y;
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to read radar map pixels.", failure);
        }
    }
}
