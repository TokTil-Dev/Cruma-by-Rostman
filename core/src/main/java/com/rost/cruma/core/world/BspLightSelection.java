package com.rost.cruma.core.world;

import glm_.vec4.Vec4;
import java.util.List;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;

public final class BspLightSelection {
    private BspLightSelection() {}

    public record Sides(int[] back, int[] front) {}

    public static Sides split(Vec4 plane, List<Light> sources, int[] candidates) {
        var back = new IntArrayList();
        var front = new IntArrayList();
        for (var index : candidates) {
            var light = sources.get(index);
            if (light.lightEffect() == 19) {
                back.add(index);
                front.add(index);
                continue;
            }
            var position = light.position();
            var distance = position.getY() * plane.getY()
                    + plane.getX() * position.getX()
                    + position.getZ() * plane.getZ()
                    - plane.getW();
            if (-light.radius() < distance) {
                front.add(index);
            }
            if (distance < light.radius()) {
                back.add(index);
            }
        }
        return new Sides(back.toArray(), front.toArray());
    }
}
