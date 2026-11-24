package com.rost.cruma.core.export;

import com.rost.cruma.core.texture.TexturePixels;
import com.rost.cruma.core.world.Entity.BlendMode;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.Entity.Texture;
import com.rost.cruma.core.world.SkyRules;
import com.rost.cruma.core.world.USky;
import com.rost.cruma.core.world.ZoneStateChoice;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;

final class SkyPanorama {
    private static final double PIECE_ANGLE = Math.toRadians(8);

    private static final int BIN = 16;

    private SkyPanorama() {}

    record Sprite(RgbaImage image, Vec3 direction, double size, float[] tint) {
        Sprite(Texture texture, Vec3 direction, double size, float[] tint) {
            this(SkyPanorama.decodeImage(texture), direction, size, tint);
        }
    }

    record Shell(
            String name,
            float[] positions,
            float[] uvs,
            int[] indices,
            @Nullable RgbaImage image,
            boolean repeatU,
            boolean repeatV,
            BlendMode blend,
            boolean depthWrite,
            float alphaCutoff,
            float[] tint) {}

    static List<Shell> collectShells(USky sky, TintSource tint, ZoneStateChoice zoneState) {
        var result = new ArrayList<Shell>();
        for (var layer : sky.drawLayers()) {
            if (!sky.isLayerActive(layer)
                    || !layer.showsInZoneState(zoneState)
                    || !(layer.entity().getMesh() instanceof EntityMesh mesh)
                    || mesh.vertices().size() < 3
                    || mesh.indices().length < 3
                    || mesh.surfaces().isEmpty()) {
                continue;
            }
            var surface = mesh.surfaces().getFirst();
            var vertices = mesh.vertexBuffer();
            var positions = new float[vertices.size() * 3];
            var uvs = new float[vertices.size() * 2];
            var origin = sky.origin();
            for (var index = 0; index < vertices.size(); index++) {
                positions[index * 3] = vertices.getPositionX(index) - origin.getX();
                positions[index * 3 + 1] = vertices.getPositionY(index) - origin.getY();
                positions[index * 3 + 2] = vertices.getPositionZ(index) - origin.getZ();
                uvs[index * 2] = vertices.getUvX(index);
                uvs[index * 2 + 1] = vertices.getUvY(index);
            }
            var indices = new int[surface.indexCount()];
            System.arraycopy(mesh.indices(), surface.indexOffset(), indices, 0, indices.length);
            var texture = surface.material() == null ? null : surface.material().getTexture();
            var image = texture == null ? null : decodeImage(texture);
            var color =
                    tint.getTint(layer.name(), sky.cloudIndex(layer.name()), SkyRules.authoredTint(surface.material()));
            result.add(new Shell(
                    layer.name(),
                    positions,
                    uvs,
                    indices,
                    image,
                    texture == null || !texture.clampU(),
                    texture == null || !texture.clampV(),
                    surface.blendMode(),
                    surface.depthWrite(),
                    SkyRules.alphaCutoff(surface.blendMode(), surface.alphaRef()),
                    new float[] {color.getX(), color.getY(), color.getZ()}));
        }
        return result;
    }

    interface TintSource {
        Vec3 getTint(String name, int cloudIndex, Vec3 authored);
    }

    static RgbaImage bake(List<Shell> shells, List<Sprite> sprites, int width, BooleanSupplier cancelled) {
        var height = width / 2;
        var pieces = new ArrayList<Pieces>();
        for (var shell : shells) {
            pieces.add(Pieces.of(shell, width, height));
        }
        var color = new float[width * height * 3];
        var covered = new boolean[width * height];
        IntStream.range(0, height).parallel().forEach(y -> {
            if (cancelled.getAsBoolean()) {
                return;
            }
            var texel = new float[4];
            var hit = new double[3];
            for (var x = 0; x < width; x++) {
                var direction = toDirection(x + .5, y + .5, width, height);
                var depth = Double.POSITIVE_INFINITY;
                float red = 0;
                float green = 0;
                float blue = 0;
                var any = false;
                for (var index = 0; index < shells.size(); index++) {
                    var shell = shells.get(index);
                    if (!pieces.get(index).isNearest(direction, x, y, hit)) {
                        continue;
                    }
                    var distance = hit[0];
                    if (distance >= depth) {
                        continue;
                    }
                    if (shell.image() != null) {
                        sample(shell, hit[1], hit[2], texel);
                    } else {
                        texel[0] = texel[1] = texel[2] = texel[3] = 1;
                    }
                    var alpha = SkyRules.usesAlpha(shell.blend()) ? texel[3] : 1f;
                    if (shell.alphaCutoff() >= 0 && alpha <= shell.alphaCutoff()) {
                        continue;
                    }
                    any = true;
                    if (shell.depthWrite()) {
                        depth = distance;
                    }
                    var sr = texel[0] * shell.tint()[0];
                    var sg = texel[1] * shell.tint()[1];
                    var sb = texel[2] * shell.tint()[2];
                    switch (shell.blend()) {
                        case ADDITIVE -> {
                            red += sr;
                            green += sg;
                            blue += sb;
                        }
                        case BRIGHTEN -> {
                            red += sr * alpha;
                            green += sg * alpha;
                            blue += sb * alpha;
                        }
                        case MODULATE -> {
                            red *= 2 * sr;
                            green *= 2 * sg;
                            blue *= 2 * sb;
                        }
                        case SCREEN -> {
                            red = sr + red * (1 - sr);
                            green = sg + green * (1 - sg);
                            blue = sb + blue * (1 - sb);
                        }
                        case DARKEN -> {
                            red *= 1 - sr;
                            green *= 1 - sg;
                            blue *= 1 - sb;
                        }
                        case TRANSLUCENT, EMISSIVE -> {
                            red = sr * alpha + red * (1 - alpha);
                            green = sg * alpha + green * (1 - alpha);
                            blue = sb * alpha + blue * (1 - alpha);
                        }
                        default -> {
                            red = sr;
                            green = sg;
                            blue = sb;
                        }
                    }
                    red = Math.clamp(red, 0f, 1f);
                    green = Math.clamp(green, 0f, 1f);
                    blue = Math.clamp(blue, 0f, 1f);
                }
                for (var sprite : sprites) {
                    if (sampleSprite(sprite, direction, texel)) {
                        red = Math.min(1f, red + texel[0] * sprite.tint()[0] * texel[3]);
                        green = Math.min(1f, green + texel[1] * sprite.tint()[1] * texel[3]);
                        blue = Math.min(1f, blue + texel[2] * sprite.tint()[2] * texel[3]);
                    }
                }
                var pixel = y * width + x;
                covered[pixel] = any;
                color[pixel * 3] = red;
                color[pixel * 3 + 1] = green;
                color[pixel * 3 + 2] = blue;
            }
        });
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("export cancelled");
        }
        fillBelow(color, covered, width, height);
        var rgba = new byte[width * height * 4];
        for (var pixel = 0; pixel < width * height; pixel++) {
            rgba[pixel * 4] = RgbaImage.toByte(color[pixel * 3]);
            rgba[pixel * 4 + 1] = RgbaImage.toByte(color[pixel * 3 + 1]);
            rgba[pixel * 4 + 2] = RgbaImage.toByte(color[pixel * 3 + 2]);
            rgba[pixel * 4 + 3] = (byte) 0xFF;
        }
        return new RgbaImage(width, height, rgba);
    }

    static Vec3 toDirection(double x, double y, int width, int height) {
        var longitude = Math.PI - 2 * Math.PI * x / width;
        var latitude = Math.PI / 2 - Math.PI * y / height;
        var cos = Math.cos(latitude);
        return new Vec3(
                (float) (cos * Math.cos(longitude)), (float) (-cos * Math.sin(longitude)), (float) Math.sin(latitude));
    }

    static double[] toPixel(double dx, double dy, double dz, int width, int height) {
        var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var longitude = Math.atan2(-dy, dx);
        var latitude = Math.asin(Math.clamp(dz / length, -1, 1));
        var x = (Math.PI - longitude) * width / (2 * Math.PI);
        var y = (Math.PI / 2 - latitude) * height / Math.PI;
        return new double[] {x, y};
    }

    static RgbaImage decodeImage(Texture texture) {
        var width = Math.max(1, texture.width());
        var height = Math.max(1, texture.height());
        byte[] rgba;
        try {
            rgba = TexturePixels.decodeBaseRgba(texture);
        } catch (RuntimeException unreadable) {
            rgba = new byte[0];
        }
        if (rgba.length != width * height * 4) {
            rgba = new byte[width * height * 4];
            Arrays.fill(rgba, (byte) 0xFF);
        }
        return new RgbaImage(width, height, rgba);
    }

    private static void sample(Shell shell, double u, double v, float[] out) {
        var image = shell.image();
        if (shell.repeatU() == shell.repeatV()) {
            image.sample((float) u, (float) v, shell.repeatU(), out);
            return;
        }
        var wrappedU = shell.repeatU() ? u - Math.floor(u) : Math.clamp(u, 0, 1);
        var wrappedV = shell.repeatV() ? v - Math.floor(v) : Math.clamp(v, 0, 1);
        image.sample((float) wrappedU, (float) wrappedV, false, out);
    }

    private static boolean sampleSprite(Sprite sprite, Vec3 direction, float[] out) {
        var s = sprite.direction();
        var along = direction.getX() * s.getX() + direction.getY() * s.getY() + direction.getZ() * s.getZ();
        if (along <= 0) {
            return false;
        }
        var right = cross(s, new Vec3(0f, 0f, 1f));
        if (length(right) < 1e-4) {
            right = new Vec3(1f, 0f, 0f);
        }
        right = normalize(right);
        var up = normalize(cross(right, s));
        var half = Math.tan(sprite.size() / 2);
        var px = (direction.getX() / along - s.getX());
        var py = (direction.getY() / along - s.getY());
        var pz = (direction.getZ() / along - s.getZ());
        var u = (px * right.getX() + py * right.getY() + pz * right.getZ()) / (2 * half) + .5;
        var v = .5 - (px * up.getX() + py * up.getY() + pz * up.getZ()) / (2 * half);
        if (u < 0 || u > 1 || v < 0 || v > 1) {
            return false;
        }
        sprite.image().sample((float) u, (float) v, false, out);
        return true;
    }

    private static void fillBelow(float[] color, boolean[] covered, int width, int height) {
        for (var x = 0; x < width; x++) {
            var last = -1;
            for (var y = 0; y < height; y++) {
                if (covered[y * width + x]) {
                    last = y;
                }
            }
            if (last < 0) {
                continue;
            }
            for (var y = last + 1; y < height; y++) {
                System.arraycopy(color, (last * width + x) * 3, color, (y * width + x) * 3, 3);
            }
        }
    }

    private static Vec3 cross(Vec3 a, Vec3 b) {
        return new Vec3(
                a.getY() * b.getZ() - a.getZ() * b.getY(),
                a.getZ() * b.getX() - a.getX() * b.getZ(),
                a.getX() * b.getY() - a.getY() * b.getX());
    }

    private static float length(Vec3 a) {
        return (float) Math.sqrt(a.getX() * a.getX() + a.getY() * a.getY() + a.getZ() * a.getZ());
    }

    private static Vec3 normalize(Vec3 a) {
        var length = length(a);
        return new Vec3(a.getX() / length, a.getY() / length, a.getZ() / length);
    }

    private static final class Pieces {
        private final double[] corners;
        private final double[] uvs;
        private final int[] start;
        private final int[] pieces;
        private final int columns;

        private Pieces(double[] corners, double[] uvs, int[] start, int[] pieces, int columns) {
            this.corners = corners;
            this.uvs = uvs;
            this.start = start;
            this.pieces = pieces;
            this.columns = columns;
        }

        static Pieces of(Shell shell, int width, int height) {
            var corners = new ArrayList<double[]>();
            var indices = shell.indices();
            for (var triangle = 0; triangle + 2 < indices.length; triangle += 3) {
                var a = readCorner(shell, indices[triangle]);
                var b = readCorner(shell, indices[triangle + 1]);
                var c = readCorner(shell, indices[triangle + 2]);
                split(a, b, c, corners, 0);
            }
            var columns = (width + BIN - 1) / BIN;
            var rows = (height + BIN - 1) / BIN;
            var lists = new ArrayList<List<Integer>>();
            for (var bin = 0; bin < columns * rows; bin++) {
                lists.add(new ArrayList<>());
            }
            var flat = new double[corners.size() * 9];
            var flatUvs = new double[corners.size() * 6];
            for (var piece = 0; piece < corners.size(); piece++) {
                var corner = corners.get(piece);
                for (var k = 0; k < 3; k++) {
                    flat[piece * 9 + k * 3] = corner[k * 5];
                    flat[piece * 9 + k * 3 + 1] = corner[k * 5 + 1];
                    flat[piece * 9 + k * 3 + 2] = corner[k * 5 + 2];
                    flatUvs[piece * 6 + k * 2] = corner[k * 5 + 3];
                    flatUvs[piece * 6 + k * 2 + 1] = corner[k * 5 + 4];
                }
                addToBins(flat, piece, width, height, columns, rows, lists);
            }
            var start = new int[columns * rows + 1];
            var total = 0;
            for (var bin = 0; bin < lists.size(); bin++) {
                start[bin] = total;
                total += lists.get(bin).size();
            }
            start[lists.size()] = total;
            var pieces = new int[total];
            for (var bin = 0; bin < lists.size(); bin++) {
                var list = lists.get(bin);
                for (var index = 0; index < list.size(); index++) {
                    pieces[start[bin] + index] = list.get(index);
                }
            }
            return new Pieces(flat, flatUvs, start, pieces, columns);
        }

        boolean isNearest(Vec3 direction, int x, int y, double[] hit) {
            var bin = (y / BIN) * columns + x / BIN;
            var best = Double.POSITIVE_INFINITY;
            var dx = direction.getX();
            var dy = direction.getY();
            var dz = direction.getZ();
            for (var index = start[bin]; index < start[bin + 1]; index++) {
                var piece = pieces[index];
                var base = piece * 9;
                var ax = corners[base];
                var ay = corners[base + 1];
                var az = corners[base + 2];
                var e1x = corners[base + 3] - ax;
                var e1y = corners[base + 4] - ay;
                var e1z = corners[base + 5] - az;
                var e2x = corners[base + 6] - ax;
                var e2y = corners[base + 7] - ay;
                var e2z = corners[base + 8] - az;
                var px = dy * e2z - dz * e2y;
                var py = dz * e2x - dx * e2z;
                var pz = dx * e2y - dy * e2x;
                var det = e1x * px + e1y * py + e1z * pz;
                if (Math.abs(det) < 1e-12) {
                    continue;
                }
                var inverse = 1 / det;
                var tx = -ax;
                var ty = -ay;
                var tz = -az;
                var u = (tx * px + ty * py + tz * pz) * inverse;
                if (u < -1e-6 || u > 1 + 1e-6) {
                    continue;
                }
                var qx = ty * e1z - tz * e1y;
                var qy = tz * e1x - tx * e1z;
                var qz = tx * e1y - ty * e1x;
                var v = (dx * qx + dy * qy + dz * qz) * inverse;
                if (v < -1e-6 || u + v > 1 + 1e-6) {
                    continue;
                }
                var distance = (e2x * qx + e2y * qy + e2z * qz) * inverse;
                if (distance <= 0 || distance >= best) {
                    continue;
                }
                best = distance;
                var uvBase = piece * 6;
                var w = 1 - u - v;
                hit[0] = distance;
                hit[1] = w * uvs[uvBase] + u * uvs[uvBase + 2] + v * uvs[uvBase + 4];
                hit[2] = w * uvs[uvBase + 1] + u * uvs[uvBase + 3] + v * uvs[uvBase + 5];
            }
            return best < Double.POSITIVE_INFINITY;
        }

        private static double[] readCorner(Shell shell, int index) {
            return new double[] {
                shell.positions()[index * 3],
                shell.positions()[index * 3 + 1],
                shell.positions()[index * 3 + 2],
                shell.uvs()[index * 2],
                shell.uvs()[index * 2 + 1]
            };
        }

        private static void split(double[] a, double[] b, double[] c, List<double[]> out, int depth) {
            var ab = angle(a, b);
            var bc = angle(b, c);
            var ca = angle(c, a);
            var longest = Math.max(ab, Math.max(bc, ca));
            if (longest <= PIECE_ANGLE || depth > 12) {
                var piece = new double[15];
                System.arraycopy(a, 0, piece, 0, 5);
                System.arraycopy(b, 0, piece, 5, 5);
                System.arraycopy(c, 0, piece, 10, 5);
                out.add(piece);
                return;
            }
            if (longest == ab) {
                var m = middle(a, b);
                split(a, m, c, out, depth + 1);
                split(m, b, c, out, depth + 1);
            } else if (longest == bc) {
                var m = middle(b, c);
                split(a, b, m, out, depth + 1);
                split(a, m, c, out, depth + 1);
            } else {
                var m = middle(c, a);
                split(a, b, m, out, depth + 1);
                split(m, b, c, out, depth + 1);
            }
        }

        private static double[] middle(double[] a, double[] b) {
            var m = new double[5];
            for (var k = 0; k < 5; k++) {
                m[k] = (a[k] + b[k]) / 2;
            }
            return m;
        }

        private static double angle(double[] a, double[] b) {
            var la = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
            var lb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
            if (la == 0 || lb == 0) {
                return 0;
            }
            var cos = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb);
            return Math.acos(Math.clamp(cos, -1, 1));
        }

        private static void addToBins(
                double[] flat, int piece, int width, int height, int columns, int rows, List<List<Integer>> lists) {
            var xs = new double[3];
            var minY = Double.POSITIVE_INFINITY;
            var maxY = Double.NEGATIVE_INFINITY;
            var nearPole = false;
            for (var k = 0; k < 3; k++) {
                var base = piece * 9 + k * 3;
                var dx = flat[base];
                var dy = flat[base + 1];
                var dz = flat[base + 2];
                var length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length == 0) {
                    return;
                }
                nearPole |= Math.abs(dz / length) > Math.cos(2 * PIECE_ANGLE);
                var point = toPixel(dx, dy, dz, width, height);
                xs[k] = point[0];
                minY = Math.min(minY, point[1]);
                maxY = Math.max(maxY, point[1]);
            }
            var margin = PIECE_ANGLE / Math.PI * height / 2 + 1;
            minY -= margin;
            maxY += margin;
            if (nearPole) {
                if (minY < height / 2.0) {
                    minY = 0;
                } else {
                    maxY = height;
                }
                addRange(0, width, minY, maxY, columns, rows, piece, lists);
                return;
            }
            Arrays.sort(xs);
            if (xs[2] - xs[0] > width / 2.0) {
                var low = xs[1] < width / 2.0 ? xs[1] : xs[0];
                var high = xs[1] < width / 2.0 ? xs[2] : xs[1];
                addRange(high - margin, width, minY, maxY, columns, rows, piece, lists);
                addRange(0, low + margin, minY, maxY, columns, rows, piece, lists);
                return;
            }
            addRange(xs[0] - margin, xs[2] + margin, minY, maxY, columns, rows, piece, lists);
        }

        private static void addRange(
                double lowX,
                double highX,
                double lowY,
                double highY,
                int columns,
                int rows,
                int piece,
                List<List<Integer>> lists) {
            var firstColumn = Math.max(0, (int) Math.floor(lowX / BIN));
            var lastColumn = Math.min(columns - 1, (int) Math.floor(highX / BIN));
            var firstRow = Math.max(0, (int) Math.floor(lowY / BIN));
            var lastRow = Math.min(rows - 1, (int) Math.floor(highY / BIN));
            for (var row = firstRow; row <= lastRow; row++) {
                for (var column = firstColumn; column <= lastColumn; column++) {
                    lists.get(row * columns + column).add(piece);
                }
            }
        }
    }
}
