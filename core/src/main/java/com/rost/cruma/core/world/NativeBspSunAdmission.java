package com.rost.cruma.core.world;

import com.rost.cruma.core.world.NativeBspPointAdmission.Node;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import java.util.Objects;

public final class NativeBspSunAdmission {
    private NativeBspSunAdmission() {}

    public static boolean mayReceive(Node node, Vector rawTowardLight) {
        Objects.requireNonNull(node);
        Objects.requireNonNull(rawTowardLight);
        var plane = node.plane();
        var dot = (plane.y() * rawTowardLight.y() + plane.x() * rawTowardLight.x()) + plane.z() * rawTowardLight.z();
        if (!Float.isFinite(dot)) {
            throw new IllegalArgumentException("non-finite BSP sunlight admission arithmetic");
        }
        return dot > 0f || (node.twoSided() && dot < 0f);
    }
}
