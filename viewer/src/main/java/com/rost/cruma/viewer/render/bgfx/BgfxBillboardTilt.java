package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.unreal.primitives.URotator;

final class BgfxBillboardTilt {
    private static final float UNITS_TO_RADIANS = (float) (Math.PI / 32768.0);

    private BgfxBillboardTilt() {}

    static boolean isBillboard(float[] records, int offset) {
        return ((int) Math.floor(records[offset + 15] / 2f) & 1) == 1;
    }

    static boolean carriesTilt(float[] records, int offset) {
        return isBillboard(records, offset) && records[offset + 11] < -0.5f;
    }

    static boolean isTilted(float[] records, int offset) {
        return carriesTilt(records, offset) && (records[offset + 3] != 0f || records[offset + 7] != 0f);
    }

    static void write(float[] records, int offset, URotator rotation) {
        records[offset + 3] = rotation.pitch() * UNITS_TO_RADIANS;
        records[offset + 7] = rotation.roll() * UNITS_TO_RADIANS;
    }

    static void writeFromMatrix(float[] records, int offset) {
        var x0 = records[offset];
        var y0 = records[offset + 1];
        var z0 = records[offset + 2];
        var length0 = (float) Math.sqrt(x0 * x0 + y0 * y0 + z0 * z0);
        var length2 = (float) Math.sqrt(records[offset + 8] * records[offset + 8]
                + records[offset + 9] * records[offset + 9]
                + records[offset + 10] * records[offset + 10]);
        if (length0 <= 0f || length2 <= 0f) {
            records[offset + 3] = 0f;
            records[offset + 7] = 0f;
            return;
        }
        var horizontal = (float) Math.sqrt(x0 * x0 + y0 * y0) / length0;
        var pitch = (float) Math.atan2(z0 / length0, horizontal);
        if (horizontal <= 1e-6f) {
            records[offset + 3] = pitch;
            records[offset + 7] = 0f;
            return;
        }
        var yaw = (float) Math.atan2(y0, x0);
        var cosineYaw = (float) Math.cos(yaw);
        var sineYaw = (float) Math.sin(yaw);
        var x2 = records[offset + 8] / length2;
        var y2 = records[offset + 9] / length2;
        var z2 = records[offset + 10] / length2;
        var alongX = x2 * cosineYaw + y2 * sineYaw;
        var alongY = y2 * cosineYaw - x2 * sineYaw;
        var roll = (float) Math.atan2(alongY, z2 * Math.cos(pitch) - alongX * Math.sin(pitch));
        records[offset + 3] = pitch;
        records[offset + 7] = roll;
    }
}
