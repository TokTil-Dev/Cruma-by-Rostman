package com.rost.cruma.core.env;

import glm_.vec3.Vec3;

public final class TimeEnvColor {
    private static final double HUE_85 = 0.01176471;
    private static final double HUE_84 = 0.01190476;
    private static final double SAT_SCALE = 0.003921569;
    private static final double BRI_SCALE = 0.005490196;

    private TimeEnvColor() {}

    public record Pick(int curr, int next, double frac) {}

    public static int[] hsvToRgb(int h, int s, double v) {
        h &= 0xFF;
        double r, g, b;
        if (h < 86) {
            b = 0.0;
            r = (85 - h) * HUE_85;
            g = h * HUE_85;
        } else if (h < 171) {
            r = 0.0;
            g = (170 - h) * HUE_85;
            b = (h - 85) * HUE_85;
        } else {
            r = (h - 170) * HUE_85;
            g = 0.0;
            b = (255 - h) * HUE_84;
        }

        var saturation = (s & 0xff) * SAT_SCALE;
        var lightness = v * BRI_SCALE;
        var sqrLightness = Math.sqrt(lightness);
        lightness = (lightness / (sqrLightness + 0.01)) * 0.7;
        if (lightness > 1.0) {
            lightness = 1.0;
        } else if (lightness < 0.0) {
            lightness = 0.0;
        }

        var x = ((1.0 - r) * saturation + r) * lightness;
        var y = ((1.0 - g) * saturation + g) * lightness;
        var z = ((1.0 - b) * saturation + b) * lightness;
        return new int[] {trunc(x * 255.0), trunc(y * 255.0), trunc(z * 255.0)};
    }

    public static int[] interpRgb(double hour, TimeColorKey[] keys) {
        var p = pick(hour, keyTimes(keys));
        var c = keys[p.curr()];
        var n = keys[p.next()];
        return new int[] {
            trunc(c.r() + (n.r() - c.r()) * p.frac()),
            trunc(c.g() + (n.g() - c.g()) * p.frac()),
            trunc(c.b() + (n.b() - c.b()) * p.frac())
        };
    }

    public static int[] interpHsvColor(double hour, TimeHsvKey[] keys) {
        var p = pickHsv(hour, keys);
        var c = keys[p.curr()];
        var n = keys[p.next()];
        var v = c.bri() + (n.bri() - c.bri()) * p.frac();
        var a = hsvToRgb(c.hue(), c.sat(), v);
        var b = hsvToRgb(n.hue(), n.sat(), v);
        return new int[] {lerpByte(a[0], b[0], p.frac()), lerpByte(a[1], b[1], p.frac()), lerpByte(a[2], b[2], p.frac())
        };
    }

    public static int[] interpHsvDynamicLightColor(double hour, TimeHsvKey[] keys) {
        var p = pickHsv(hour, keys);
        var c = keys[p.curr()];
        var n = keys[p.next()];
        var a = hsvToRgb(c.hue(), c.sat(), 255);
        var b = hsvToRgb(n.hue(), n.sat(), 255);
        var brightness = interpBrightness(hour, keys) / 255.0;
        return new int[] {
            trunc((a[0] + (b[0] - a[0]) * p.frac()) * brightness),
            trunc((a[1] + (b[1] - a[1]) * p.frac()) * brightness),
            trunc((a[2] + (b[2] - a[2]) * p.frac()) * brightness)
        };
    }

    public static double interpBrightness(double hour, TimeHsvKey[] keys) {
        var p = pickHsv(hour, keys);
        return p.frac() * (keys[p.next()].bri() - keys[p.curr()].bri()) + keys[p.curr()].bri();
    }

    public static float interpScalar(double hour, TimeScaleKey[] keys) {
        if (keys.length == 0) {
            return 1f;
        }
        var p = pick(hour, keyTimes(keys));
        var c = keys[p.curr()].s();
        var n = keys[p.next()].s();
        return (float) (c + (n - c) * p.frac());
    }

    public static Vec3 toVec3(int[] rgb) {
        return new Vec3(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f);
    }

    public static Pick pick(double hour, int[] times) {
        var n = times.length;
        if (n <= 1) {
            return new Pick(0, 0, 0.0);
        }
        var nm1 = n - 1;
        int idxc = 0;
        int idxn = 1;
        while (idxc < nm1) {
            if (hour >= times[idxc] && hour < times[idxn]) {
                break;
            }
            if (times[idxn] > hour && times[idxc] <= hour) {
                break;
            }
            idxc++;
            idxn++;
            if (idxn >= n) {
                return new Pick(nm1, nm1, 0.0);
            }
        }

        idxc = 0;
        idxn = 1;
        while (idxn < n && (times[idxc] > hour || times[idxn] <= hour)) {
            if (idxc >= nm1) {
                return new Pick(nm1, nm1, 0.0);
            }
            idxc++;
            idxn++;
        }
        if (idxn >= n) {
            return new Pick(nm1, nm1, 0.0);
        }

        var tc = times[idxc];
        var tn = times[idxn];
        if (tn == tc) {
            return new Pick(idxc, idxn, 0.0);
        }
        return new Pick(idxc, idxn, (hour - tc) / (double) (tn - tc));
    }

    private static int lerpByte(int a, int b, double alpha) {
        return trunc(a + (b - a) * alpha);
    }

    private static int trunc(double value) {
        return Math.max(0, (int) value);
    }

    private static int[] keyTimes(TimeColorKey[] keys) {
        var t = new int[keys.length];
        for (var i = 0; i < keys.length; i++) {
            t[i] = keys[i].t();
        }
        return t;
    }

    private static Pick pickHsv(double hour, TimeHsvKey[] keys) {
        if (keys.length <= 1) {
            return new Pick(0, 0, 0);
        }
        for (var i = 0; i + 1 < keys.length; i++) {
            var start = (double) keys[i].t();
            var end = (double) keys[i + 1].t();
            if (hour >= start && hour < end) {
                return new Pick(i, i + 1, (hour - start) / (end - start));
            }
        }
        return new Pick(keys.length - 1, keys.length - 1, 0);
    }

    private static int[] keyTimes(TimeScaleKey[] keys) {
        var t = new int[keys.length];
        for (var i = 0; i < keys.length; i++) {
            t[i] = keys[i].t();
        }
        return t;
    }
}
