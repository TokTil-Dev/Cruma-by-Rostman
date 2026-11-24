package com.rost.cruma.core.geometry;

import glm_.vec3.Vec3;

public class Triangle {
    private final Vec3 a;
    private final Vec3 b;
    private final Vec3 c;

    public Triangle(Vec3 a, Vec3 b, Vec3 c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public Vec3 getClosestPointTo(Vec3 point) {
        var ab = b.minus(a);
        var ac = c.minus(a);
        var ap = point.minus(a);
        var d1 = ab.dot(ap);
        var d2 = ac.dot(ap);
        if (d1 <= 0.0f && d2 <= 0.0f) {
            return a;
        }

        var bp = point.minus(b);
        var d3 = ab.dot(bp);
        var d4 = ac.dot(bp);
        if (d3 >= 0.0f && d4 <= d3) {
            return b;
        }

        var vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0f && d1 >= 0.0f && d3 <= 0.0f) {
            var v = d1 / (d1 - d3);
            return a.plus(ab.times(v));
        }

        var cp = point.minus(c);
        var d5 = ab.dot(cp);
        var d6 = ac.dot(cp);
        if (d6 >= 0.0f && d5 <= d6) {
            return c;
        }

        var vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0f && d2 >= 0.0f && d6 <= 0.0f) {
            var w = d2 / (d2 - d6);
            return a.plus(ac.times(w));
        }

        var va = d3 * d6 - d5 * d4;
        if (va <= 0.0f && (d4 - d3) >= 0.0f && (d5 - d6) >= 0.0f) {
            var w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            return b.plus((c.minus(b)).times(w));
        }

        var denom = 1.0f / (va + vb + vc);
        var v = vb * denom;
        var w = vc * denom;
        return a.plus(ab.times(v)).plus(ac.times(w));
    }
}
