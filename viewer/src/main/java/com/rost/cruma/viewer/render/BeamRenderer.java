package com.rost.cruma.viewer.render;

import com.rost.cruma.viewer.render.ParticleEmitter.BeamConfig;
import glm_.quat.Quat;
import glm_.vec3.Vec3;
import java.nio.FloatBuffer;

public final class BeamRenderer {
    private static final int MAX_SPINE_POINTS = 96;
    public static final int SPINE_SCRATCH_FLOATS = 3 * MAX_SPINE_POINTS;
    private static final int MAX_SHEETS = 16;
    private static final int FLOATS_PER_VERTEX = 9;
    private static final float SMALL_NUMBER = 1e-8f;
    private static final float EPS = 1e-4f;
    private static final int PTEP_OFFSET = 2;
    private static final int PTEP_ACTOR = 3;
    private static final int PTEP_TRACE_OFFSET = 4;
    private static final int PTEP_OFFSET_AS_ABSOLUTE = 5;

    private BeamRenderer() {}

    public static float[] buildRibbon(ParticleEmitter emitter, int i, Vec3 cameraPos, float[] scratch) {
        return build(emitter, i, cameraPos, scratch, 1);
    }

    public static float[] buildSheets(ParticleEmitter emitter, int i, Vec3 cameraPos, float[] scratch) {
        return build(emitter, i, cameraPos, scratch, getSheets(emitter));
    }

    public static int requiredControlFloats(ParticleEmitter emitter) {
        return Math.multiplyExact(Math.max(2, emitter.getBeamConfig().lowFrequencyPoints()), 3);
    }

    public static int getMostRibbonFloats(ParticleEmitter emitter) {
        return getSheets(emitter) * (getPoints(emitter.getBeamConfig()) - 1) * 6 * FLOATS_PER_VERTEX;
    }

    public static int appendRibbon(
            ParticleEmitter emitter, int i, Vec3 cameraPos, float[] scratch, float[] control, FloatBuffer out) {
        return append(emitter, i, cameraPos, scratch, control, out, getSheets(emitter));
    }

    private static int getSheets(ParticleEmitter emitter) {
        return clamp(emitter.getBeamConfig().rotatingSheets(), 1, MAX_SHEETS);
    }

    private static int getPoints(BeamConfig cfg) {
        return clamp(cfg.highFrequencyPoints(), 2, MAX_SPINE_POINTS);
    }

    private static float[] build(ParticleEmitter emitter, int i, Vec3 cameraPos, float[] scratch, int sheets) {
        var control = new float[requiredControlFloats(emitter)];
        var output = FloatBuffer.allocate(sheets * (MAX_SPINE_POINTS - 1) * 6 * FLOATS_PER_VERTEX);
        var count = append(emitter, i, cameraPos, scratch, control, output, sheets);
        if (count == 0) {
            return null;
        }
        var result = new float[count];
        output.flip();
        output.get(result);
        return result;
    }

    private static int append(
            ParticleEmitter emitter,
            int i,
            Vec3 cameraPos,
            float[] scratch,
            float[] control,
            FloatBuffer out,
            int sheets) {
        var cfg = emitter.getBeamConfig();
        var rotation = emitter.getActorRotation();
        var base = emitter.getActorPos();
        float size = emitter.getScaleX(i);
        if (size <= 0f) {
            return 0;
        }
        float a = emitter.getA(i);
        if (a <= 0f) {
            return 0;
        }
        var requiredControl = requiredControlFloats(emitter);
        if (control.length < requiredControl) {
            throw new IllegalArgumentException(
                    "beam control scratch is too small: " + control.length + " < " + requiredControl);
        }
        if (scratch.length < SPINE_SCRATCH_FLOATS) {
            throw new IllegalArgumentException(
                    "beam spine scratch is too small: " + scratch.length + " < " + SPINE_SCRATCH_FLOATS);
        }
        if (cfg.lowFrequencyPoints() < 2 || cfg.highFrequencyPoints() < 2) {
            return 0;
        }
        int points = layPoints(emitter, i, cfg, control, scratch);
        float totalLen = 0f;
        for (int j = 1; j < points; j++) {
            totalLen += distance(scratch, j - 1, j);
        }
        if (totalLen < EPS) {
            return 0;
        }

        var requiredOutput = sheets * (points - 1) * 6 * FLOATS_PER_VERTEX;
        if (out.remaining() < requiredOutput) {
            throw new IllegalArgumentException(
                    "beam output scratch is too small: " + out.remaining() + " < " + requiredOutput);
        }
        float r = emitter.getR(i), g = emitter.getG(i), b = emitter.getB(i);
        float uStep = cfg.uScale() / (points - 1);
        float vScale = cfg.vScale();
        float offX = cameraPos.getX() - base.getX();
        float offY = cameraPos.getY() - base.getY();
        float offZ = cameraPos.getZ() - base.getZ();
        var qx = rotation.x.floatValue();
        var qy = rotation.y.floatValue();
        var qz = rotation.z.floatValue();
        var qw = rotation.w.floatValue();
        var backX = 2f * (-qy * offZ + qz * offY);
        var backY = 2f * (-qz * offX + qx * offZ);
        var backZ = 2f * (-qx * offY + qy * offX);
        float camX = offX + qw * backX - qy * backZ + qz * backY;
        float camY = offY + qw * backY - qz * backX + qx * backZ;
        float camZ = offZ + qw * backZ - qx * backY + qy * backX;
        var start = out.position();
        for (int sheet = 0; sheet < sheets; sheet++) {
            double turn = (0x8000 / sheets) * sheet * (Math.PI / 0x8000);
            float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn);
            float prevX = 2f * scratch[0] - scratch[3];
            float prevY = 2f * scratch[1] - scratch[4];
            float prevZ = 2f * scratch[2] - scratch[5];
            float plX = 0f, plY = 0f, plZ = 0f, prX = 0f, prY = 0f, prZ = 0f, pu = 0f;
            for (int j = 0; j < points; j++) {
                float pX = scratch[j * 3], pY = scratch[j * 3 + 1], pZ = scratch[j * 3 + 2];
                float dX = pX - prevX, dY = pY - prevY, dZ = pZ - prevZ;
                float vX = prevX - camX, vY = prevY - camY, vZ = prevZ - camZ;
                float sX = dY * vZ - dZ * vY;
                float sY = dZ * vX - dX * vZ;
                float sZ = dX * vY - dY * vX;
                float sideSquared = sX * sX + sY * sY + sZ * sZ;
                if (sideSquared >= SMALL_NUMBER) {
                    float scale = (float) (1.0 / Math.sqrt(sideSquared));
                    sX *= scale;
                    sY *= scale;
                    sZ *= scale;
                }
                if (sheet != 0) {
                    float dirSquared = dX * dX + dY * dY + dZ * dZ;
                    if (dirSquared >= SMALL_NUMBER) {
                        float scale = (float) (1.0 / Math.sqrt(dirSquared));
                        dX *= scale;
                        dY *= scale;
                        dZ *= scale;
                    }
                    float dot = (dX * sX + dY * sY + dZ * sZ) * (1f - cos);
                    float tX = sX * cos + (dY * sZ - dZ * sY) * sin + dX * dot;
                    float tY = sY * cos + (dZ * sX - dX * sZ) * sin + dY * dot;
                    float tZ = sZ * cos + (dX * sY - dY * sX) * sin + dZ * dot;
                    sX = tX;
                    sY = tY;
                    sZ = tZ;
                }
                prevX = pX;
                prevY = pY;
                prevZ = pZ;
                sX *= size;
                sY *= size;
                sZ *= size;
                var turnX = 2f * (qy * pZ - qz * pY);
                var turnY = 2f * (qz * pX - qx * pZ);
                var turnZ = 2f * (qx * pY - qy * pX);
                float wX = pX + qw * turnX + qy * turnZ - qz * turnY + base.getX();
                float wY = pY + qw * turnY + qz * turnX - qx * turnZ + base.getY();
                float wZ = pZ + qw * turnZ + qx * turnY - qy * turnX + base.getZ();
                turnX = 2f * (qy * sZ - qz * sY);
                turnY = 2f * (qz * sX - qx * sZ);
                turnZ = 2f * (qx * sY - qy * sX);
                float eX = sX + qw * turnX + qy * turnZ - qz * turnY;
                float eY = sY + qw * turnY + qz * turnX - qx * turnZ;
                float eZ = sZ + qw * turnZ + qx * turnY - qy * turnX;
                float u = j * uStep;
                float lX = wX + eX, lY = wY + eY, lZ = wZ + eZ;
                float rX = wX - eX, rY = wY - eY, rZ = wZ - eZ;
                if (j > 0) {
                    putVertex(out, plX, plY, plZ, pu, 0f, r, g, b, a);
                    putVertex(out, prX, prY, prZ, pu, vScale, r, g, b, a);
                    putVertex(out, rX, rY, rZ, u, vScale, r, g, b, a);
                    putVertex(out, plX, plY, plZ, pu, 0f, r, g, b, a);
                    putVertex(out, rX, rY, rZ, u, vScale, r, g, b, a);
                    putVertex(out, lX, lY, lZ, u, 0f, r, g, b, a);
                }
                plX = lX;
                plY = lY;
                plZ = lZ;
                prX = rX;
                prY = rY;
                prZ = rZ;
                pu = u;
            }
        }
        for (int j = 0; j < points; j++) {
            rotateInPlace(rotation, scratch, j * 3);
            scratch[j * 3] += base.getX();
            scratch[j * 3 + 1] += base.getY();
            scratch[j * 3 + 2] += base.getZ();
        }
        return out.position() - start;
    }

    private static int layPoints(ParticleEmitter emitter, int i, BeamConfig cfg, float[] low, float[] out) {
        float sx = emitter.getLocalX(i), sy = emitter.getLocalY(i), sz = emitter.getLocalZ(i);
        float stepX = emitter.getBeamEndX(i), stepY = emitter.getBeamEndY(i), stepZ = emitter.getBeamEndZ(i);
        long seed = emitter.getBeamSeed(i);
        int lowPoints = cfg.lowFrequencyPoints();
        int points = getPoints(cfg);
        var lfMin = cfg.lfNoiseMin();
        var lfMax = cfg.lfNoiseMax();
        var hfMin = cfg.hfNoiseMin();
        var hfMax = cfg.hfNoiseMax();
        int mode = emitter.isBeamAimed() ? PTEP_ACTOR : cfg.determineEndPointBy();
        boolean endFixed = (mode == PTEP_OFFSET || mode == PTEP_TRACE_OFFSET || mode == PTEP_OFFSET_AS_ABSOLUTE)
                && !cfg.noiseDeterminesEndPoint();

        float lowStep = 1f / (lowPoints - 1);
        float pointStep = 1f / (points - 1);
        put(low, 0, sx, sy, sz);
        for (int k = 1; k < lowPoints; k++) {
            float part = lowStep * k;
            put(
                    low,
                    k,
                    hashedRandom(seed, k, 0, lfMin.getX(), lfMax.getX()) + sx + part * stepX,
                    hashedRandom(seed, k, 1, lfMin.getY(), lfMax.getY()) + sy + part * stepY,
                    hashedRandom(seed, k, 2, lfMin.getZ(), lfMax.getZ()) + sz + part * stepZ);
        }
        if (endFixed) {
            put(low, lowPoints - 1, sx + stepX, sy + stepY, sz + stepZ);
        }

        put(out, 0, sx, sy, sz);
        float along = pointStep;
        int at = 0;
        for (int j = 1; j < points; j++) {
            float nx = hashedRandom(seed, lowPoints + j, 0, hfMin.getX(), hfMax.getX());
            float ny = hashedRandom(seed, lowPoints + j, 1, hfMin.getY(), hfMax.getY());
            float nz = hashedRandom(seed, lowPoints + j, 2, hfMin.getZ(), hfMax.getZ());
            if (at + 1 < lowPoints) {
                float t = (lowPoints - 1) * along;
                float keep = 1f - t;
                put(
                        out,
                        j,
                        low[at * 3] * keep + low[at * 3 + 3] * t + nx,
                        low[at * 3 + 1] * keep + low[at * 3 + 4] * t + ny,
                        low[at * 3 + 2] * keep + low[at * 3 + 5] * t + nz);
            } else {
                int last = Math.min(at, lowPoints - 1);
                put(out, j, nx + low[last * 3], ny + low[last * 3 + 1], nz + low[last * 3 + 2]);
            }
            along += pointStep;
            if (lowStep <= along) {
                along -= lowStep;
                at++;
            }
        }
        if (endFixed) {
            put(out, points - 1, sx + stepX, sy + stepY, sz + stepZ);
        }
        return points;
    }

    private static void rotateInPlace(Quat rotation, float[] value, int offset) {
        var vx = value[offset];
        var vy = value[offset + 1];
        var vz = value[offset + 2];
        var qx = rotation.x.floatValue();
        var qy = rotation.y.floatValue();
        var qz = rotation.z.floatValue();
        var qw = rotation.w.floatValue();
        var tx = 2f * (qy * vz - qz * vy);
        var ty = 2f * (qz * vx - qx * vz);
        var tz = 2f * (qx * vy - qy * vx);
        value[offset] = vx + qw * tx + qy * tz - qz * ty;
        value[offset + 1] = vy + qw * ty + qz * tx - qx * tz;
        value[offset + 2] = vz + qw * tz + qx * ty - qy * tx;
    }

    private static void putVertex(
            FloatBuffer out, float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        out.put(x).put(y).put(z).put(u).put(v).put(r).put(g).put(b).put(a);
    }

    private static void put(float[] a, int index, float x, float y, float z) {
        a[index * 3] = x;
        a[index * 3 + 1] = y;
        a[index * 3 + 2] = z;
    }

    private static float distance(float[] p, int a, int b) {
        float dx = p[b * 3] - p[a * 3], dy = p[b * 3 + 1] - p[a * 3 + 1], dz = p[b * 3 + 2] - p[a * 3 + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float hashedRandom(long seed, int point, int axis, float min, float max) {
        if (min == max) {
            return min;
        }
        long z = seed + (long) (point * 3 + axis) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        float u = (z >>> 40) * (1.0f / (1 << 24));
        return min + u * (max - min);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
