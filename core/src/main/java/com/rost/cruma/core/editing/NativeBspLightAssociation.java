package com.rost.cruma.core.editing;

import com.rost.cruma.core.unreal.Bsp.UModel;
import java.util.ArrayDeque;
import java.util.Arrays;

final class NativeBspLightAssociation {
    private static final IntList EMPTY = new IntList();

    private NativeBspLightAssociation() {}

    record Light(
            int effect,
            boolean specialLit,
            boolean excluded,
            int zone,
            boolean skyZone,
            float x,
            float y,
            float z,
            float worldRadius,
            float rawDirectionX,
            float rawDirectionY,
            float rawDirectionZ) {}

    static int[][] build(UModel model, Light[] lights, int[] lightSlots) {
        var nodes = model.getNodes();
        var surfaces = model.getSurfaces();
        var leaves = model.getLeaves();
        var surfaceCount = surfaces == null ? 0 : surfaces.length;
        var result = new IntList[surfaceCount];
        for (var i = 0; i < surfaceCount; i++) {
            result[i] = new IntList();
        }
        if (nodes == null || nodes.length == 0 || surfaces == null || leaves == null) {
            return finish(result);
        }
        var unions = new IntList[nodes.length];
        var stack = new ArrayDeque<int[]>();
        stack.push(new int[] {0, 0});
        var visiting = new boolean[nodes.length];
        while (!stack.isEmpty()) {
            var top = stack.peek();
            var index = top[0];
            var node = nodes[index];
            var frontLeaf = node.getLeaf()[1];
            var backLeaf = node.getLeaf()[0];
            if (top[1] == 0) {
                top[1] = 1;
                if (visiting[index]) {
                    throw new IllegalStateException("Cyclic BSP node tree");
                }
                visiting[index] = true;
                if (frontLeaf == -1 && node.getFrontIndex() >= 0 && unions[node.getFrontIndex()] == null) {
                    stack.push(new int[] {node.getFrontIndex(), 0});
                    continue;
                }
            }
            if (top[1] == 1) {
                top[1] = 2;
                if (backLeaf == -1 && node.getBackIndex() >= 0 && unions[node.getBackIndex()] == null) {
                    stack.push(new int[] {node.getBackIndex(), 0});
                    continue;
                }
            }
            stack.pop();
            var front = frontLeaf != -1
                    ? leafLights(model, frontLeaf, lights, lightSlots)
                    : node.getFrontIndex() >= 0 ? unions[node.getFrontIndex()] : new IntList();
            var back = backLeaf != -1
                    ? leafLights(model, backLeaf, lights, lightSlots)
                    : node.getBackIndex() >= 0 ? unions[node.getBackIndex()] : new IntList();
            for (var chain = index; chain >= 0 && chain < nodes.length; chain = nodes[chain].getPlaneIndex()) {
                var surface = nodes[chain].getSurfaceIndex();
                if (surface < 0 || surface >= surfaceCount) {
                    continue;
                }
                var flags = (int) surfaces[surface].getPolygonFlags();
                filter(model, chain, flags, front, lights, result[surface]);
                if ((flags & 0x100) != 0) {
                    filter(model, chain, flags, back, lights, result[surface]);
                }
                if (nodes[chain].getPlaneIndex() == chain) {
                    break;
                }
            }
            var union = new IntList();
            for (var i = 0; i < front.size; i++) {
                union.addUnique(front.values[i]);
            }
            for (var i = 0; i < back.size; i++) {
                union.addUnique(back.values[i]);
            }
            unions[index] = union;
            if (frontLeaf == -1 && node.getFrontIndex() >= 0) {
                unions[node.getFrontIndex()] = EMPTY;
            }
            if (backLeaf == -1 && node.getBackIndex() >= 0) {
                unions[node.getBackIndex()] = EMPTY;
            }
        }
        return finish(result);
    }

    private static IntList leafLights(UModel model, int leaf, Light[] lights, int[] slots) {
        var result = new IntList();
        var leaves = model.getLeaves();
        if (leaf < 0 || leaf >= leaves.length) {
            return result;
        }
        var start = leaves[leaf].permeating();
        if (start < 0) {
            return result;
        }
        for (var slot = start; slot < slots.length; slot++) {
            var light = slots[slot];
            if (light == -1) {
                break;
            }
            if (light < 0 || lights[light].excluded()) {
                continue;
            }
            result.add(light);
        }
        return result;
    }

    private static void filter(
            UModel model, int nodeIndex, int surfaceFlags, IntList candidates, Light[] lights, IntList output) {
        var node = model.getNodes()[nodeIndex];
        var plane = node.getPlane();
        var nx = plane.getX();
        var ny = plane.getY();
        var nz = plane.getZ();
        var nw = plane.getW();
        var specialLit = (surfaceFlags >>> 20 & 1) != 0;
        var twoSided = (surfaceFlags & 0x100) != 0;
        var sphere = node.getExclusiveSphereBound();
        for (var i = 0; i < candidates.size; i++) {
            var index = candidates.values[i];
            var light = lights[index];
            if (light.specialLit() != specialLit) {
                continue;
            }
            boolean admitted;
            if (light.effect() == NativeBspLightmapTexels.LE_SUNLIGHT) {
                var dot = (ny * -light.rawDirectionY() + nx * -light.rawDirectionX()) + nz * -light.rawDirectionZ();
                var front = (light.skyZone() || node.getZone()[1] == light.zone()) && 0f < dot;
                var back = (light.skyZone() || node.getZone()[0] == light.zone()) && dot < 0f && twoSided;
                admitted = front || back;
            } else {
                var dx = light.x() - sphere.getX();
                var dy = light.y() - sphere.getY();
                var dz = light.z() - sphere.getZ();
                var distance = dx * dx + dy * dy + dz * dz;
                var reach = light.worldRadius() + sphere.getW();
                admitted = distance < reach * reach
                        && (twoSided || 0f < (light.y() * ny + light.x() * nx + light.z() * nz) - nw);
            }
            if (admitted) {
                output.addUnique(index);
            }
        }
    }

    private static int[][] finish(IntList[] lists) {
        var result = new int[lists.length][];
        for (var i = 0; i < lists.length; i++) {
            result[i] = Arrays.copyOf(lists[i].values, lists[i].size);
        }
        return result;
    }

    private static final class IntList {
        int[] values = new int[4];
        int size;

        void add(int value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        void addUnique(int value) {
            for (var i = 0; i < size; i++) {
                if (values[i] == value) {
                    return;
                }
            }
            add(value);
        }
    }
}
