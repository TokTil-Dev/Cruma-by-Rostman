package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

public final class NativeTerrainLayerPasses {
    static final int LAYER_SLOTS = 32;

    static final int MIXED = 0xffff;

    static final int MIXED_LATER = 0xfffe;

    private NativeTerrainLayerPasses() {}

    record Weights(int width, int height, byte[] values) {
        Weights {
            if (width < 1 || height < 1 || values.length != width * height) {
                throw new IllegalArgumentException("Terrain layer weights do not match their size");
            }
        }

        int at(int x, int y) {
            return values[y * width + x] & 255;
        }
    }

    record Words(List<Integer> uniformity, List<Integer> passes, List<Integer> layers) {
        Words {
            uniformity = List.copyOf(uniformity);
            passes = List.copyOf(passes);
            layers = List.copyOf(layers);
        }

        boolean matches(Sector sector) {
            return (sector.layerUniformity().isEmpty()
                            || sector.layerUniformity().equals(uniformity))
                    && (sector.quadRenderPasses().isEmpty()
                            || sector.quadRenderPasses().equals(passes));
        }

        Sector applyTo(Sector sector, Words before) {
            if (!layers.equals(before.layers)) {
                return applyTo(sector);
            }
            var newUniformity = new ArrayList<Integer>();
            for (var i = 0; i < sector.layerUniformity().size(); i++) {
                newUniformity.add(
                        uniformity.get(i).equals(before.uniformity.get(i))
                                ? sector.layerUniformity().get(i)
                                : uniformity.get(i));
            }
            var newPasses = new ArrayList<Integer>();
            for (var i = 0; i < sector.quadRenderPasses().size(); i++) {
                var changed = passes.get(i) ^ before.passes.get(i);
                newPasses.add(sector.quadRenderPasses().get(i) & ~changed | passes.get(i) & changed);
            }
            return with(sector, newUniformity, newPasses);
        }

        Sector applyTo(Sector sector) {
            return with(
                    sector,
                    sector.layerUniformity().isEmpty() ? List.of() : uniformity,
                    sector.quadRenderPasses().isEmpty() ? List.of() : passes);
        }

        private static Sector with(Sector sector, List<Integer> uniformity, List<Integer> passes) {
            return new Sector(
                    sector.owner(),
                    sector.sizeX(),
                    sector.sizeY(),
                    sector.offsetX(),
                    sector.offsetY(),
                    sector.bounds(),
                    sector.lights(),
                    sector.hasTimeMaps(),
                    sector.timeMaps(),
                    uniformity,
                    passes);
        }
    }

    static Words build(List<Weights> layers, BitSet visible, BitSet turned, int samples, Sector sector) {
        Objects.requireNonNull(visible);
        Objects.requireNonNull(turned);
        if (layers.size() > LAYER_SLOTS) {
            throw new IllegalArgumentException("A Terrain has at most 32 layers");
        }
        var uniformity = new int[LAYER_SLOTS];
        Arrays.fill(uniformity, MIXED);
        var list = new ArrayList<Integer>();
        for (var layer = 0; layer < layers.size(); layer++) {
            if (isSectorAll(layers.get(layer), samples, sector, uniformity, layer, 0)) {
                continue;
            }
            var covered = false;
            for (var later = layer + 1; later < layers.size() && !covered; later++) {
                covered = isSectorAll(layers.get(later), samples, sector, uniformity, later, 255);
            }
            if (!covered) {
                list.add(layer);
            }
        }
        var words = new int[256];
        var sizeX = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), samples);
        var sizeY = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), samples);
        for (var ly = 0; ly < sizeY; ly++) {
            for (var lx = 0; lx < sizeX; lx++) {
                var x = sector.offsetX() + lx;
                var y = sector.offsetY() + ly;
                if (!visible.get(y * samples + x)) {
                    continue;
                }
                var turn = turned.get(y * samples + x);
                var word = 0;
                for (var pass = 0; pass < list.size(); pass++) {
                    if (pass == 0
                            || shouldRender(layers, list, pass, samples, x, y, 0, turn)
                            || shouldRender(layers, list, pass, samples, x, y, 1, turn)) {
                        word |= 1 << pass;
                    }
                }
                words[ly * 16 + lx] = word;
            }
        }
        return new Words(
                Arrays.stream(uniformity).boxed().toList(),
                Arrays.stream(words).boxed().toList(),
                list);
    }

    static int layerAlpha(Weights weights, int samples, int x, int y) {
        var blockWidth = (int) (weights.width() / (float) samples + .5f);
        var blockHeight = (int) (weights.height() / (float) samples + .5f);
        var value = 0;
        var zero = false;
        var full = false;
        for (var j = 0; j < blockHeight; j++) {
            for (var i = 0; i < blockWidth; i++) {
                value = weights.at(Math.min(x + i, weights.width() - 1), Math.min(y + j, weights.height() - 1));
                if (value == 0) {
                    zero = true;
                } else if (value == 255) {
                    full = true;
                } else {
                    return value;
                }
                if (zero && full) {
                    return 100;
                }
            }
        }
        return value;
    }

    static boolean reads(Weights weights, int samples, Sector sector, int x0, int y0, int x1, int y1) {
        var fx = weights.width() / (float) samples;
        var fy = weights.height() / (float) samples;
        var blockWidth = Math.max(1, (int) (fx + .5f));
        var blockHeight = Math.max(1, (int) (fy + .5f));
        var left = (int) Math.floor(sector.offsetX() * fx);
        var right = (int) Math.ceil((sector.offsetX() + sector.sizeX()) * fx) + blockWidth;
        var top = (int) Math.floor(sector.offsetY() * fy);
        var bottom = (int) Math.ceil((sector.offsetY() + sector.sizeY()) * fy) + blockHeight;
        return x1 >= left && x0 <= right && y1 >= top && y0 <= bottom;
    }

    public static int dxt1(byte[] body, int offset, int width, int x, int y) {
        var block = offset + ((y >> 2) * (width >> 2) + (x >> 2)) * 8;
        var c0 = (body[block] & 255) | (body[block + 1] & 255) << 8;
        var c1 = (body[block + 2] & 255) | (body[block + 3] & 255) << 8;
        var bits = (body[block + 4] & 255)
                | (body[block + 5] & 255) << 8
                | (body[block + 6] & 255) << 16
                | (body[block + 7] & 255) << 24;
        var index = bits >>> 2 * ((x & 3) + (y & 3) * 4) & 3;
        int color;
        if (c0 > c1) {
            color = switch (index) {
                case 0 -> c0;
                case 1 -> c1;
                case 2 -> (2 * c0 + 1 + c1) / 3;
                default -> (2 * c1 + 1 + c0) / 3;
            };
        } else {
            color = switch (index) {
                case 0 -> c0;
                case 1 -> c1;
                case 2 -> (c0 + c1) / 2;
                default -> 0;
            };
        }
        return ((color >> 8 & 0xf8) + (color >> 3 & 0xfc) + (color & 0x1f) * 8) / 3;
    }

    private static boolean shouldRender(
            List<Weights> layers, List<Integer> list, int pass, int samples, int x, int y, int triangle, boolean turn) {
        if (isTriangleAll(layers.get(list.get(pass)), samples, x, y, triangle, turn, 0)) {
            return false;
        }
        for (var later = pass + 1; later < list.size(); later++) {
            if (isTriangleAll(layers.get(list.get(later)), samples, x, y, triangle, turn, 255)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSectorAll(
            Weights weights, int samples, Sector sector, int[] uniformity, int layer, int value) {
        var fx = weights.width() / (float) samples;
        var fy = weights.height() / (float) samples;
        var x0 = (int) Math.floor(sector.offsetX() * fx);
        var x1 = (int) Math.ceil((sector.offsetX() + sector.sizeX()) * fx);
        var y0 = (int) Math.floor(sector.offsetY() * fy);
        var y1 = (int) Math.ceil((sector.offsetY() + sector.sizeY()) * fy);
        var matched = false;
        for (var x = x0; x <= x1; x++) {
            for (var y = y0; y <= y1; y++) {
                if (layerAlpha(weights, samples, x, y) != value) {
                    if (matched) {
                        uniformity[layer] = MIXED_LATER;
                    }
                    return false;
                }
                matched = true;
            }
        }
        uniformity[layer] = value;
        return true;
    }

    private static boolean isTriangleAll(
            Weights weights, int samples, int x, int y, int triangle, boolean turn, int value) {
        if (weights.width() == samples) {
            int[] corners;
            if (!turn) {
                corners = triangle == 0
                        ? new int[] {x, y, x, y + 1, x + 1, y + 1}
                        : new int[] {x, y, x + 1, y + 1, x + 1, y};
            } else {
                corners = triangle == 0
                        ? new int[] {x, y, x, y + 1, x + 1, y}
                        : new int[] {x, y + 1, x + 1, y + 1, x + 1, y};
            }
            for (var i = 0; i < corners.length; i += 2) {
                if (layerAlpha(weights, samples, corners[i], corners[i + 1]) != value) {
                    return false;
                }
            }
            return true;
        }
        var fx = weights.width() / (float) samples;
        var fy = weights.height() / (float) samples;
        var x0 = (int) Math.floor(x * fx);
        var span = (int) Math.ceil((x + 1) * fx) - x0;
        var y0 = (int) Math.floor(y * fy);
        for (var i = 0; i <= span; i++) {
            for (var j = 0; j <= span; j++) {
                boolean inside;
                if (!turn) {
                    inside = triangle == 0 ? j >= i : j <= i;
                } else {
                    inside = triangle == 0 ? i + j <= span : i + j >= span;
                }
                if (inside && layerAlpha(weights, samples, x0 + i, y0 + j) != value) {
                    return false;
                }
            }
        }
        return true;
    }
}
