package com.rost.cruma.viewer.config;

import com.rost.cruma.viewer.config.Config.Capture.ImageFormat;

public record CaptureRadarLayout(int columns, int rows, int tileResolution) {
    public CaptureRadarLayout {
        if (columns < 1 || rows < 1 || tileResolution < 1) {
            throw new IllegalArgumentException("Radar bounds and tile resolution must be positive.");
        }
        try {
            int width = Math.multiplyExact(columns, tileResolution);
            int height = Math.multiplyExact(rows, tileResolution);
            Math.addExact(Math.multiplyExact(width, 3), 8);
            Math.multiplyExact(Math.multiplyExact((long) width, height), 3);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(
                    "Radar dimensions are too large for PNG. Reduce the area or tile resolution.", failure);
        }
    }

    public static CaptureRadarLayout forBounds(int tileResolution, int minX, int maxX, int minY, int maxY) {
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("Radar tile bounds are inverted.");
        }
        try {
            return new CaptureRadarLayout(
                    Math.toIntExact((long) maxX - minX + 1), Math.toIntExact((long) maxY - minY + 1), tileResolution);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Radar tile range is too large.", failure);
        }
    }

    public int width() {
        return columns * tileResolution;
    }

    public int height() {
        return rows * tileResolution;
    }

    public long temporaryBytes() {
        return (long) width() * height() * 3;
    }

    public void checkFormat(ImageFormat format) {
        if (format == ImageFormat.JPEG
                && (width() > 65_500 || height() > 65_500 || (long) width() * height() > Integer.MAX_VALUE)) {
            throw new IllegalArgumentException(
                    "Radar map is too large for the JPEG encoder. Choose PNG or reduce the area or radar tile"
                            + " resolution.");
        }
    }

    public long workingBytes() {
        return (long) width() * 3 * 14 + (long) tileResolution * 3 + 1024 * 1024;
    }

    public void checkMemory(long availableBytes) {
        if (workingBytes() > availableBytes) {
            throw new IllegalArgumentException("Radar rows need about " + workingBytes()
                    + " bytes of RAM. Reduce the image width or give Java more memory.");
        }
    }
}
