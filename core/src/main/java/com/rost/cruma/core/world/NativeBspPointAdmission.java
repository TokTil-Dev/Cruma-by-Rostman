package com.rost.cruma.core.world;

import com.rost.cruma.core.world.NativeBspShadowProjection.Plane;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import java.util.Objects;

public final class NativeBspPointAdmission {
    private NativeBspPointAdmission() {}

    public record Node(Plane plane, Vector sphereCenter, float sphereRadius, boolean twoSided) {
        public Node {
            Objects.requireNonNull(plane);
            Objects.requireNonNull(sphereCenter);
            requireFinite(sphereRadius);
            if (sphereRadius < 0) {
                throw new IllegalArgumentException("negative BSP node sphere radius");
            }
        }
    }

    public static boolean mayReceive(Node node, Vector position, float authoredRadius) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(position);
        requireFinite(authoredRadius);
        if (authoredRadius < 0) {
            throw new IllegalArgumentException("negative authored light radius");
        }
        var dx = position.x() - node.sphereCenter.x();
        var dy = position.y() - node.sphereCenter.y();
        var dz = position.z() - node.sphereCenter.z();
        var square = (dx * dx + dy * dy) + dz * dz;
        var radius = (authoredRadius + 1f) * 25f;
        var sum = radius + node.sphereRadius;
        var radiusSquare = sum * sum;
        requireFinite(square, radius, sum, radiusSquare);
        if (!(square < radiusSquare)) {
            return false;
        }
        if (node.twoSided) {
            return true;
        }
        var p = node.plane;
        var front = ((position.y() * p.y() + position.x() * p.x()) + position.z() * p.z()) - p.w();
        requireFinite(front);
        return front > 0;
    }

    private static void requireFinite(float... values) {
        for (var value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("non-finite BSP point admission arithmetic");
            }
        }
    }
}
