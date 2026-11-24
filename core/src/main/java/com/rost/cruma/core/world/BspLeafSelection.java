package com.rost.cruma.core.world;

import com.rost.cruma.core.geometry.Box;
import com.rost.cruma.core.unreal.Bsp.UBspLeaf;
import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UArchive;
import java.util.function.IntConsumer;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.jetbrains.annotations.Nullable;

public final class BspLeafSelection {
    private BspLeafSelection() {}

    public static boolean hasTag(@Nullable String tag) {
        return tag != null && !isSameTag(UArchive.NONE_NAME, tag);
    }

    public static boolean hasActorFilter(UActor actor) {
        return hasTag(actor.getForcedRegionTag())
                || actor.getForcedRegion() != 0
                || hasTag(actor.getForcedVisibilityZoneTag());
    }

    public static int[] filterVisibility(
            UModel model, int[] leaves, @Nullable String visibilityTag, @Nullable ZoneInfoActor levelInfo) {
        if (!hasTag(visibilityTag)) {
            return leaves;
        }
        var result = new IntArrayList();
        for (var leaf : leaves) {
            if (matchesVisibility(model, leaf, visibilityTag, levelInfo)) {
                result.add(leaf);
            }
        }
        return result.toArray();
    }

    public static int[] collect(
            UModel model, Box box, int[] nodeStack, @Nullable String forcedRegionTag, int forcedRegion) {
        var result = new IntArrayList();
        visit(model, box, nodeStack, forcedRegionTag, forcedRegion, result::add);
        return result.toArray();
    }

    public static long zoneMask(
            UModel model, Box box, int[] nodeStack, @Nullable String forcedRegionTag, int forcedRegion) {
        return zoneMask(model, box, nodeStack, forcedRegionTag, forcedRegion, UArchive.NONE_NAME, null);
    }

    public static long zoneMask(
            UModel model,
            Box box,
            int[] nodeStack,
            @Nullable String forcedRegionTag,
            int forcedRegion,
            @Nullable String visibilityTag,
            @Nullable ZoneInfoActor levelInfo) {
        var mask = new long[1];
        visit(model, box, nodeStack, forcedRegionTag, forcedRegion, leaf -> {
            if (!matchesVisibility(model, leaf, visibilityTag, levelInfo)) {
                return;
            }
            var zone = model.getLeaves()[leaf].zone();
            if (zone >= 0 && zone < Long.SIZE) {
                mask[0] |= 1L << zone;
            }
        });
        return mask[0];
    }

    public static long zoneMask(UBspNode[] nodes, UBspLeaf[] leaves, Box box, int[] nodeStack) {
        var mask = new long[1];
        if (nodes.length != 0 && nodeStack.length >= nodes.length) {
            visit(nodes, box, nodeStack, leaf -> {
                var zone = leaf < 0 || leaf >= leaves.length ? -1 : leaves[leaf].zone();
                if (zone >= 0 && zone < Long.SIZE) {
                    mask[0] |= 1L << zone;
                }
            });
        }
        return mask[0];
    }

    public static int[] collect(UBspNode[] nodes, UBspLeaf[] leaves, Box box, int[] nodeStack) {
        var result = new IntArrayList();
        if (nodes.length != 0 && nodeStack.length >= nodes.length) {
            visit(nodes, box, nodeStack, leaf -> {
                if (leaf >= 0 && leaf < leaves.length) {
                    result.add(leaf);
                }
            });
        }
        return result.toArray();
    }

    private static boolean matchesVisibility(
            UModel model, int leaf, @Nullable String tag, @Nullable ZoneInfoActor levelInfo) {
        if (!hasTag(tag)) {
            return true;
        }
        var leaves = model.getLeaves();
        if (leaves == null || leaf < 0 || leaf >= leaves.length) {
            return false;
        }
        var zone = leaves[leaf].zone();
        var zones = model.getZones();
        var actor = zones != null && zone >= 0 && zone < zones.length
                ? zones[zone].getZoneActor().get()
                : null;
        if (actor == null) {
            actor = levelInfo;
        }
        return actor != null && isSameTag(tag, actor.getTag());
    }

    private static void visit(
            UModel model,
            Box box,
            int[] nodeStack,
            @Nullable String forcedRegionTag,
            int forcedRegion,
            IntConsumer output) {
        var nodes = model.getNodes();
        var leaves = model.getLeaves();
        if (nodes == null || nodes.length == 0 || leaves == null || nodeStack.length < nodes.length) {
            return;
        }
        visit(nodes, box, nodeStack, leaf -> emit(model, leaf, forcedRegionTag, forcedRegion, output));
    }

    private static void visit(UBspNode[] nodes, Box box, int[] nodeStack, IntConsumer terminal) {
        var originX = (box.getMinX() + box.getMaxX()) * 0.5f;
        var originY = (box.getMinY() + box.getMaxY()) * 0.5f;
        var originZ = (box.getMinZ() + box.getMaxZ()) * 0.5f;
        var extentX = (box.getMaxX() - box.getMinX()) * 0.5f;
        var extentY = (box.getMaxY() - box.getMinY()) * 0.5f;
        var extentZ = (box.getMaxZ() - box.getMinZ()) * 0.5f;
        var top = 0;
        nodeStack[0] = 0;
        while (top >= 0) {
            var index = nodeStack[top--];
            if (index < 0 || index >= nodes.length) {
                continue;
            }
            var node = nodes[index];
            var plane = node.getPlane();
            var distance = plane.getX() * originX + plane.getY() * originY + plane.getZ() * originZ - plane.getW();
            var pushOut = Math.abs(plane.getX() * extentX)
                    + Math.abs(plane.getY() * extentY)
                    + Math.abs(plane.getZ() * extentZ);
            if (distance < pushOut) {
                if (node.getBackIndex() >= 0) {
                    nodeStack[++top] = node.getBackIndex();
                } else {
                    terminal.accept(node.getLeaf()[0]);
                }
            }
            if (-distance < pushOut) {
                if (node.getFrontIndex() >= 0) {
                    nodeStack[++top] = node.getFrontIndex();
                } else {
                    terminal.accept(node.getLeaf()[1]);
                }
            }
        }
    }

    private static void emit(UModel model, int leaf, @Nullable String tag, int region, IntConsumer output) {
        var leaves = model.getLeaves();
        if (leaf < 0 || leaf >= leaves.length) {
            return;
        }
        var zone = leaves[leaf].zone();
        if (hasTag(tag)) {
            var zones = model.getZones();
            if (zones == null || zone < 0 || zone >= zones.length) {
                return;
            }
            var actor = zones[zone].getZoneActor().get();
            if (actor == null || !isSameTag(tag, actor.getZoneTag())) {
                return;
            }
        } else if (region != 0 && zone != region) {
            return;
        }
        output.accept(leaf);
    }

    private static boolean isSameTag(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        for (var i = 0; i < left.length(); i++) {
            var a = left.charAt(i);
            var b = right.charAt(i);
            if (a >= 'A' && a <= 'Z') {
                a += 'a' - 'A';
            }
            if (b >= 'A' && b <= 'Z') {
                b += 'a' - 'A';
            }
            if (a != b) {
                return false;
            }
        }
        return true;
    }
}
