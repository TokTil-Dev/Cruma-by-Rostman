package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.ActorLightingScene.Mesh;
import java.util.Arrays;
import org.eclipse.collections.api.list.primitive.MutableFloatList;
import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

public final class BeastTexels {
    private final Mesh mesh;
    private final int width;
    private final int height;
    private final int[] texels;
    private final int[] triangles;
    private final float[] weights;

    private BeastTexels(Mesh mesh, int width, int height, int[] texels, int[] triangles, float[] weights) {
        this.mesh = mesh;
        this.width = width;
        this.height = height;
        this.texels = texels;
        this.triangles = triangles;
        this.weights = weights;
    }

    public static BeastTexels of(Mesh mesh, int width, int height) {
        return of(mesh, width, height, .5f);
    }

    static BeastTexels of(Mesh mesh, int width, int height, float center) {
        if (!mesh.hasBeastLayout()) {
            throw new IllegalArgumentException("The mesh " + mesh.name() + " has no Beast lightmap layout");
        }
        if (width < 1 || height < 1 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Invalid Beast lightmap size " + width + " x " + height);
        }
        var uvs = mesh.beastUvs();
        var indices = mesh.triangles();
        var owner = new int[width * height];
        Arrays.fill(owner, -1);
        MutableIntList texels = new IntArrayList();
        MutableIntList triangles = new IntArrayList();
        MutableFloatList weights = new FloatArrayList();
        for (var triangle = 0; triangle < indices.length / 3; triangle++) {
            var a = indices[triangle * 3];
            var b = indices[triangle * 3 + 1];
            var c = indices[triangle * 3 + 2];
            var ax = uvs[a * 2] * width - center;
            var ay = uvs[a * 2 + 1] * height - center;
            var bx = uvs[b * 2] * width - center;
            var by = uvs[b * 2 + 1] * height - center;
            var cx = uvs[c * 2] * width - center;
            var cy = uvs[c * 2 + 1] * height - center;
            var area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (!(Math.abs(area) > 1e-9f)) {
                continue;
            }
            var minX = (int) Math.max(0, Math.floor(Math.min(ax, Math.min(bx, cx))));
            var maxX = (int) Math.min(width - 1, Math.ceil(Math.max(ax, Math.max(bx, cx))));
            var minY = (int) Math.max(0, Math.floor(Math.min(ay, Math.min(by, cy))));
            var maxY = (int) Math.min(height - 1, Math.ceil(Math.max(ay, Math.max(by, cy))));
            for (var y = minY; y <= maxY; y++) {
                for (var x = minX; x <= maxX; x++) {
                    var texel = y * width + x;
                    if (owner[texel] >= 0) {
                        continue;
                    }
                    var w0 = ((bx - x) * (cy - y) - (by - y) * (cx - x)) / area;
                    var w1 = ((cx - x) * (ay - y) - (cy - y) * (ax - x)) / area;
                    var w2 = 1f - w0 - w1;
                    if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) {
                        continue;
                    }
                    owner[texel] = triangle;
                    texels.add(texel);
                    triangles.add(triangle);
                    weights.add(w0);
                    weights.add(w1);
                }
            }
        }
        return new BeastTexels(mesh, width, height, texels.toArray(), triangles.toArray(), weights.toArray());
    }

    public static void dilate(float[] rgb, boolean[] covered, int width, int height, int rings) {
        var filled = covered.clone();
        double r = 0, g = 0, b = 0;
        var count = 0;
        for (var i = 0; i < filled.length; i++) {
            if (filled[i]) {
                r += rgb[i * 3];
                g += rgb[i * 3 + 1];
                b += rgb[i * 3 + 2];
                count++;
            }
        }
        var next = filled.clone();
        for (var ring = 0; ring < rings; ring++) {
            var changed = false;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var i = y * width + x;
                    if (filled[i]) {
                        continue;
                    }
                    float sr = 0, sg = 0, sb = 0;
                    var n = 0;
                    for (var dy = -1; dy <= 1; dy++) {
                        for (var dx = -1; dx <= 1; dx++) {
                            var nx = x + dx;
                            var ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= width || ny >= height || !filled[ny * width + nx]) {
                                continue;
                            }
                            var j = ny * width + nx;
                            sr += rgb[j * 3];
                            sg += rgb[j * 3 + 1];
                            sb += rgb[j * 3 + 2];
                            n++;
                        }
                    }
                    if (n > 0) {
                        rgb[i * 3] = sr / n;
                        rgb[i * 3 + 1] = sg / n;
                        rgb[i * 3 + 2] = sb / n;
                        next[i] = true;
                        changed = true;
                    }
                }
            }
            System.arraycopy(next, 0, filled, 0, filled.length);
            if (!changed) {
                break;
            }
        }
        if (count == 0) {
            return;
        }
        for (var i = 0; i < filled.length; i++) {
            if (!filled[i]) {
                rgb[i * 3] = (float) (r / count);
                rgb[i * 3 + 1] = (float) (g / count);
                rgb[i * 3 + 2] = (float) (b / count);
            }
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getCount() {
        return texels.length;
    }

    public int getTexel(int index) {
        return texels[index];
    }

    public int getTriangle(int index) {
        return triangles[index];
    }

    public float[] getWeights(int index) {
        var w0 = weights[index * 2];
        var w1 = weights[index * 2 + 1];
        return new float[] {w0, w1, 1f - w0 - w1};
    }

    public boolean computeWorldPoint(float[] localToWorld, int index, float[] position, float[] normal) {
        var triangle = triangles[index];
        var w0 = weights[index * 2];
        var w1 = weights[index * 2 + 1];
        var w2 = 1f - w0 - w1;
        var corners = mesh.triangles();
        var a = corners[triangle * 3] * 3;
        var b = corners[triangle * 3 + 1] * 3;
        var c = corners[triangle * 3 + 2] * 3;
        var p = mesh.positions();
        var n = mesh.normals();
        var lx = p[a] * w0 + p[b] * w1 + p[c] * w2;
        var ly = p[a + 1] * w0 + p[b + 1] * w1 + p[c + 1] * w2;
        var lz = p[a + 2] * w0 + p[b + 2] * w1 + p[c + 2] * w2;
        var nx = n[a] * w0 + n[b] * w1 + n[c] * w2;
        var ny = n[a + 1] * w0 + n[b + 1] * w1 + n[c + 1] * w2;
        var nz = n[a + 2] * w0 + n[b + 2] * w1 + n[c + 2] * w2;
        var m = localToWorld;
        position[0] = lx * m[0] + ly * m[4] + lz * m[8] + m[12];
        position[1] = lx * m[1] + ly * m[5] + lz * m[9] + m[13];
        position[2] = lx * m[2] + ly * m[6] + lz * m[10] + m[14];
        var c00 = m[5] * m[10] - m[6] * m[9];
        var c01 = m[6] * m[8] - m[4] * m[10];
        var c02 = m[4] * m[9] - m[5] * m[8];
        var c10 = m[2] * m[9] - m[1] * m[10];
        var c11 = m[0] * m[10] - m[2] * m[8];
        var c12 = m[1] * m[8] - m[0] * m[9];
        var c20 = m[1] * m[6] - m[2] * m[5];
        var c21 = m[2] * m[4] - m[0] * m[6];
        var c22 = m[0] * m[5] - m[1] * m[4];
        var determinant = m[0] * c00 + m[1] * c01 + m[2] * c02;
        var sign = determinant < 0 ? -1f : 1f;
        var wx = (nx * c00 + ny * c10 + nz * c20) * sign;
        var wy = (nx * c01 + ny * c11 + nz * c21) * sign;
        var wz = (nx * c02 + ny * c12 + nz * c22) * sign;
        var length = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (!(length > 1e-12f)) {
            return false;
        }
        normal[0] = wx / length;
        normal[1] = wy / length;
        normal[2] = wz / length;
        return true;
    }

    public boolean[] getCoverage() {
        var covered = new boolean[width * height];
        for (var texel : texels) {
            covered[texel] = true;
        }
        return covered;
    }
}
