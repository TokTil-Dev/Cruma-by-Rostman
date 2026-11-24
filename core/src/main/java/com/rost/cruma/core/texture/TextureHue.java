package com.rost.cruma.core.texture;

import com.rost.cruma.core.world.Entity.TextureFormat;

public final class TextureHue {
    private static final int DXT1_BLOCK = 8;
    private static final int DXT35_BLOCK = 16;

    private TextureHue() {}

    public static int normalize(double degrees) {
        return (int) Math.floorMod(Math.round(degrees), 360L);
    }

    public static int rotate(int rgb, int degrees) {
        return rotate(rgb >>> 16 & 255, rgb >>> 8 & 255, rgb & 255, degrees);
    }

    public static int rotate(int red, int green, int blue, int degrees) {
        var rotated = rotate((float) red, green, blue, degrees);
        return Math.round(rotated[0]) << 16 | Math.round(rotated[1]) << 8 | Math.round(rotated[2]);
    }

    public static float[] rotate(float red, float green, float blue, int degrees) {
        var max = Math.max(red, Math.max(green, blue));
        var min = Math.min(red, Math.min(green, blue));
        var delta = max - min;
        if (delta <= 0f) {
            return new float[] {red, green, blue};
        }
        float hue;
        if (max == red) {
            hue = (green - blue) / delta;
        } else if (max == green) {
            hue = 2f + (blue - red) / delta;
        } else {
            hue = 4f + (red - green) / delta;
        }
        hue = (hue + degrees / 60f + 12f) % 6f;
        var sector = (int) hue;
        var middle = min + delta * (1f - Math.abs(hue % 2f - 1f));
        return switch (sector) {
            case 0 -> new float[] {max, middle, min};
            case 1 -> new float[] {middle, max, min};
            case 2 -> new float[] {min, max, middle};
            case 3 -> new float[] {min, middle, max};
            case 4 -> new float[] {middle, min, max};
            default -> new float[] {max, min, middle};
        };
    }

    public static byte[] rotateDxt(TextureFormat format, int width, int height, byte[] level, int degrees) {
        var dxt1 = format == TextureFormat.TEXTURE_DXT1;
        var blockSize = dxt1 ? DXT1_BLOCK : DXT35_BLOCK;
        var blocks = Math.min(((width + 3) / 4) * ((height + 3) / 4), level.length / blockSize);
        var result = level.clone();
        for (var block = 0; block < blocks; block++) {
            var offset = block * blockSize + (dxt1 ? 0 : 8);
            var first = result[offset] & 255 | (result[offset + 1] & 255) << 8;
            var second = result[offset + 2] & 255 | (result[offset + 3] & 255) << 8;
            var turnedFirst = rotate565(first, degrees);
            var turnedSecond = rotate565(second, degrees);
            if (dxt1) {
                var threeColors = first <= second;
                if (threeColors ? turnedFirst > turnedSecond : turnedFirst < turnedSecond) {
                    var swap = turnedFirst;
                    turnedFirst = turnedSecond;
                    turnedSecond = swap;
                    swapIndices(result, offset + 4, threeColors);
                } else if (!threeColors && turnedFirst == turnedSecond) {
                    if (turnedSecond > 0) {
                        turnedSecond--;
                    } else {
                        turnedFirst++;
                    }
                }
            }
            result[offset] = (byte) turnedFirst;
            result[offset + 1] = (byte) (turnedFirst >>> 8);
            result[offset + 2] = (byte) turnedSecond;
            result[offset + 3] = (byte) (turnedSecond >>> 8);
        }
        return result;
    }

    public static byte[] rotateBgra(byte[] level, int degrees) {
        var result = level.clone();
        for (var pixel = 0; pixel + 3 < result.length; pixel += 4) {
            var rgb = rotate(result[pixel + 2] & 255, result[pixel + 1] & 255, result[pixel] & 255, degrees);
            result[pixel] = (byte) rgb;
            result[pixel + 1] = (byte) (rgb >>> 8);
            result[pixel + 2] = (byte) (rgb >>> 16);
        }
        return result;
    }

    public static float saturation(byte[] rgba) {
        double weighted = 0;
        double total = 0;
        for (var pixel = 0; pixel + 3 < rgba.length; pixel += 4) {
            var red = rgba[pixel] & 255;
            var green = rgba[pixel + 1] & 255;
            var blue = rgba[pixel + 2] & 255;
            var max = Math.max(red, Math.max(green, blue));
            if (max == 0) {
                continue;
            }
            var min = Math.min(red, Math.min(green, blue));
            double weight = max * (rgba[pixel + 3] & 255);
            weighted += weight * (max - min) / max;
            total += weight;
        }
        return total == 0 ? 0f : (float) (weighted / total);
    }

    private static int rotate565(int color, int degrees) {
        var red = color >> 11 & 31;
        var green = color >> 5 & 63;
        var blue = color & 31;
        var rgb = rotate(red << 3 | red >> 2, green << 2 | green >> 4, blue << 3 | blue >> 2, degrees);
        return ((rgb >>> 16 & 255) * 31 + 127) / 255 << 11
                | ((rgb >>> 8 & 255) * 63 + 127) / 255 << 5
                | ((rgb & 255) * 31 + 127) / 255;
    }

    private static void swapIndices(byte[] block, int offset, boolean threeColors) {
        var indices = block[offset] & 255
                | (block[offset + 1] & 255) << 8
                | (block[offset + 2] & 255) << 16
                | (block[offset + 3] & 255) << 24;
        indices ^= threeColors ? ~(indices >>> 1) & 0x55555555 : 0x55555555;
        block[offset] = (byte) indices;
        block[offset + 1] = (byte) (indices >>> 8);
        block[offset + 2] = (byte) (indices >>> 16);
        block[offset + 3] = (byte) (indices >>> 24);
    }
}
