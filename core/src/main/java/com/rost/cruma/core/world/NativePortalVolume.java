package com.rost.cruma.core.world;

import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.util.ArrayList;
import java.util.List;

public final class NativePortalVolume {
    private NativePortalVolume() {}

    public static float area(List<Vec3> vertices) {
        var result = 0f;
        if (vertices.size() < 3) {
            return result;
        }
        var base = vertices.getFirst();
        var a = vertices.get(1).minus(base);
        for (var i = 2; i < vertices.size(); i++) {
            var b = vertices.get(i).minus(base);
            var x = a.getY() * b.getZ() - a.getZ() * b.getY();
            var y = a.getZ() * b.getX() - a.getX() * b.getZ();
            var z = a.getX() * b.getY() - a.getY() * b.getX();
            result = (float) Math.sqrt(x * x + y * y + z * z) + result;
            a = b;
        }
        return result;
    }

    public static List<Vec3> clip(List<Vec3> vertices, List<Vec4> planes) {
        if (vertices.size() > 16) {
            throw new IllegalArgumentException("native FPoly holds at most 16 vertices");
        }
        var result = vertices.stream().map(Vec3::new).toList();
        for (var plane : planes) {
            var nx = -plane.getX();
            var ny = -plane.getY();
            var nz = -plane.getZ();
            var bx = plane.getW() * plane.getX();
            var by = plane.getY() * plane.getW();
            var bz = plane.getZ() * plane.getW();
            var w = ny * by + nx * bx + nz * bz;
            var front = false;
            var back = false;
            var sides = new boolean[result.size()];
            for (var i = 0; i < result.size(); i++) {
                var v = result.get(i);
                var distance = v.getY() * ny + nx * v.getX() + v.getZ() * nz - w;
                sides[i] = distance >= 0f;
                front |= distance > .25f;
                back |= distance < -.25f;
            }
            if (!front && back) {
                return List.of();
            }
            if (!front || !back || result.size() >= 14) {
                continue;
            }
            var clipped = new ArrayList<Vec3>();
            var previous = result.getLast();
            var previousFront = sides[sides.length - 1];
            for (var i = 0; i < result.size(); i++) {
                var current = result.get(i);
                if (sides[i] != previousFront) {
                    var dx = current.getX() - previous.getX();
                    var dy = current.getY() - previous.getY();
                    var dz = current.getZ() - previous.getZ();
                    var amount = (w - (previous.getX() * nx + ny * previous.getY() + nz * previous.getZ()))
                            / (nx * dx + ny * dy + nz * dz);
                    clipped.add(new Vec3(
                            previous.getX() + dx * amount,
                            previous.getY() + dy * amount,
                            previous.getZ() + dz * amount));
                }
                if (sides[i]) {
                    clipped.add(current);
                }
                previous = current;
                previousFront = sides[i];
            }
            result = List.copyOf(clipped);
        }
        return result;
    }

    public static List<Vec4> planes(Vec3 normal, List<Vec3> vertices, Vec3 origin) {
        if (vertices.size() > 16) {
            throw new IllegalArgumentException("native FPoly holds at most 16 vertices");
        }
        var result = new ArrayList<Vec4>(vertices.size() + 1);
        var first = vertices.isEmpty() ? new Vec3() : vertices.getFirst();
        result.add(planeThrough(normal, first));
        for (var i = 0; i < vertices.size(); i++) {
            var a = vertices.get(i).minus(origin);
            var b = vertices.get((i + 1) % vertices.size()).minus(origin);
            var x = a.getY() * b.getZ() - a.getZ() * b.getY();
            var y = a.getZ() * b.getX() - a.getX() * b.getZ();
            var z = a.getX() * b.getY() - a.getY() * b.getX();
            var square = x * x + y * y + z * z;
            if (square < 1.0e-8) {
                x = y = z = 0f;
            } else {
                var inverse = 1f / (float) Math.sqrt(square);
                x *= inverse;
                y *= inverse;
                z *= inverse;
            }
            result.add(planeThrough(new Vec3(x, y, z), origin));
        }
        return List.copyOf(result);
    }

    private static Vec4 planeThrough(Vec3 normal, Vec3 point) {
        return new Vec4(
                normal.getX(),
                normal.getY(),
                normal.getZ(),
                point.getY() * normal.getY() + normal.getX() * point.getX() + point.getZ() * normal.getZ());
    }
}
