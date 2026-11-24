package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.BspNodeFlags;
import com.rost.cruma.core.unreal.Bsp.UModel;
import glm_.mat4x4.Mat4;
import glm_.vec3.Vec3;
import java.util.Arrays;

public final class BrushVolume {
    private final Box worldBounds;
    private final Mat4 worldToBrush;
    private final float[] worldPlanes;
    private final float[] planes;
    private final int[] front;
    private final int[] back;
    private final boolean rootOutside;
    private final boolean[] csg;

    public BrushVolume(UModel brush, Mat4 brushToWorld) {
        this.worldBounds = new Box(Arrays.asList(brush.getPoints()), brushToWorld);
        this.worldToBrush = brushToWorld.inverse();
        var nodes = brush.getNodes();
        this.planes = new float[nodes.length * 4];
        this.front = new int[nodes.length];
        this.back = new int[nodes.length];
        this.rootOutside = brush.isRootOutside();
        this.csg = new boolean[nodes.length];
        for (var i = 0; i < nodes.length; i++) {
            var plane = nodes[i].getPlane();
            var p = i * 4;
            planes[p] = plane.getX();
            planes[p + 1] = plane.getY();
            planes[p + 2] = plane.getZ();
            planes[p + 3] = plane.getW();
            front[i] = nodes[i].getFrontIndex();
            back[i] = nodes[i].getBackIndex();
            csg[i] = nodes[i].getVertexCount() != 0
                    && (nodes[i].getFlags() & (BspNodeFlags.NF_NotCsg | BspNodeFlags.NF_IsNew)) == 0;
        }
        this.worldPlanes = BrushTransforms.worldPlanes(brushToWorld, planes);
    }

    public BrushVolume(Box worldBounds, Mat4 worldToBrush, float[] planes, int[] front, int[] back) {
        if (planes.length != front.length * 4 || front.length != back.length) {
            throw new IllegalArgumentException(
                    "A BSP needs 4 plane values and one front/back child per node, got planes=" + planes.length
                            + " front=" + front.length + " back=" + back.length);
        }
        this.worldBounds = worldBounds;
        this.worldToBrush = worldToBrush;
        this.worldPlanes = BrushTransforms.worldPlanes(worldToBrush.inverse(), planes);
        this.planes = planes;
        this.front = front;
        this.back = back;
        this.rootOutside = true;
        this.csg = new boolean[front.length];
        Arrays.fill(csg, true);
    }

    public static BrushVolume ofBox(Vec3 min, Vec3 max) {
        var planes = new float[] {
            1f,
            0f,
            0f,
            max.getX(),
            -1f,
            0f,
            0f,
            -min.getX(),
            0f,
            1f,
            0f,
            max.getY(),
            0f,
            -1f,
            0f,
            -min.getY(),
            0f,
            0f,
            1f,
            max.getZ(),
            0f,
            0f,
            -1f,
            -min.getZ()
        };
        var front = new int[] {-1, -1, -1, -1, -1, -1};
        var back = new int[] {1, 2, 3, 4, 5, -1};
        return new BrushVolume(new Box(min, max), new Mat4(), planes, front, back);
    }

    public record Tree(float[] planes, int[] front, int[] back, boolean[] decides, boolean rootOutside) {}

    public Tree getWorldTree() {
        return new Tree(worldPlanes.clone(), front.clone(), back.clone(), csg.clone(), rootOutside);
    }

    public Box getWorldBounds() {
        return worldBounds;
    }

    public boolean contains(Vec3 point) {
        if (!containsInBounds(point)) {
            return false;
        }
        if (front.length == 0) {
            return !rootOutside;
        }
        var index = 0;
        var outside = rootOutside;
        for (var guard = 0; index >= 0 && index < front.length && guard <= front.length; guard++) {
            var p = index * 4;
            var side = worldPlanes[p] * point.getX()
                    + worldPlanes[p + 1] * point.getY()
                    + worldPlanes[p + 2] * point.getZ()
                    - worldPlanes[p + 3];
            var isFront = side > 0f;
            if (csg[index]) {
                outside = isFront;
            }
            var next = isFront ? front[index] : back[index];
            if (next < 0) {
                return !outside;
            }
            index = next;
        }
        return false;
    }

    Mat4 getWorldToBrush() {
        return worldToBrush;
    }

    float[] getPlanes() {
        return planes;
    }

    int[] getFront() {
        return front;
    }

    int[] getBack() {
        return back;
    }

    private boolean containsInBounds(Vec3 point) {
        var min = worldBounds.getMin();
        var max = worldBounds.getMax();
        return point.getX() >= min.getX()
                && point.getX() <= max.getX()
                && point.getY() >= min.getY()
                && point.getY() <= max.getY()
                && point.getZ() >= min.getZ()
                && point.getZ() <= max.getZ();
    }
}
