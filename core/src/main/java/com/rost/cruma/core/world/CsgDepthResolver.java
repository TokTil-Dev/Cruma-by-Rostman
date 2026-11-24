package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.Bsp.UBspNode;
import com.rost.cruma.core.world.Entity.BlendMode;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class CsgDepthResolver {
    private static final float COINCIDENT_NUDGE = 4f;
    private static final float COINCIDENT_OVERLAP_EPS = 8f;
    private static final float COINCIDENT_PLANE_EPS = 0.5f;
    private static final float CSG_DEPTH_LAYER_MAX_GAP = 8f;
    private static final float CSG_LEGACY_THIN_SLAB_MAX_GAP = 64.5f;
    private static final float CSG_DEPTH_LAYER_NORMAL_EPS = 1e-6f;
    private static final float COINCIDENT_NORMAL_DOT = 0.999f;

    record Face(
            UBspNode node,
            Vec3 normal,
            float[][] poly2d,
            Object material,
            BlendMode blend,
            float planeDistance,
            boolean depthLayerEligible,
            boolean reverseWindingEmitted) {}

    private record FaceSide(UBspNode node, boolean reversed) {}

    private record DepthEdge(FaceSide front, FaceSide back) {}

    record Resolution(
            IdentityHashMap<UBspNode, Float> nudges,
            IdentityHashMap<UBspNode, Integer> frontLayers,
            IdentityHashMap<UBspNode, Integer> backLayers,
            Set<UBspNode> legacyBackfaces) {}

    static Resolution resolve(List<Face> geos) {
        var nudges = new IdentityHashMap<UBspNode, Float>();
        var edges = new ArrayList<DepthEdge>();
        var legacyBackfaces = Collections.newSetFromMap(new IdentityHashMap<UBspNode, Boolean>());
        for (int j = 1; j < geos.size(); j++) {
            var later = geos.get(j);
            var coincidenceRank = 0;
            for (int i = 0; i < j; i++) {
                var earlier = geos.get(i);
                var normalDot = dot(earlier.normal(), later.normal());
                var sameFacing = normalDot >= COINCIDENT_NORMAL_DOT;
                var oppositeFacing = normalDot <= -COINCIDENT_NORMAL_DOT;
                if (!sameFacing && !oppositeFacing) {
                    continue;
                }
                var laterAlignedDistance = sameFacing ? later.planeDistance() : -later.planeDistance();
                var separation = Math.abs(earlier.planeDistance() - laterAlignedDistance);
                var sameAppearance =
                        hasSameAppearance(earlier.material(), earlier.blend(), later.material(), later.blend());
                if (sameAppearance) {
                    if (oppositeFacing
                            && earlier.depthLayerEligible()
                            && later.depthLayerEligible()
                            && separation > COINCIDENT_PLANE_EPS
                            && separation <= CSG_LEGACY_THIN_SLAB_MAX_GAP
                            && isSameDepthLayerAxis(earlier.normal(), later.normal())
                            && doPolygons2dOverlap(earlier.poly2d(), later.poly2d())) {
                        legacyBackfaces.add(earlier.node());
                        legacyBackfaces.add(later.node());
                    }
                    continue;
                }
                if (separation > CSG_DEPTH_LAYER_MAX_GAP) {
                    continue;
                }
                if (sameFacing && separation <= COINCIDENT_PLANE_EPS) {
                    if (doPolygons2dOverlap(earlier.poly2d(), later.poly2d())) {
                        coincidenceRank++;
                    }
                } else if (earlier.depthLayerEligible()
                        && later.depthLayerEligible()
                        && separation > COINCIDENT_PLANE_EPS
                        && isSameDepthLayerAxis(earlier.normal(), later.normal())
                        && doPolygons2dOverlap(earlier.poly2d(), later.poly2d())) {
                    addDepthEdges(edges, earlier, later, laterAlignedDistance);
                }
            }
            if (coincidenceRank > 0) {
                nudges.put(later.node(), coincidenceRank * COINCIDENT_NUDGE);
            }
        }

        return resolveCsgDepthLayers(nudges, geos.size(), edges, legacyBackfaces);
    }

    static boolean hasSameAppearance(
            @Nullable Object firstMaterial,
            BlendMode firstBlend,
            @Nullable Object secondMaterial,
            BlendMode secondBlend) {
        return firstMaterial == secondMaterial && firstBlend == secondBlend;
    }

    private static void addDepthEdges(List<DepthEdge> edges, Face earlier, Face later, float laterAlignedDistance) {
        var positiveFront = earlier.planeDistance() > laterAlignedDistance ? earlier : later;
        var positiveBack = positiveFront == earlier ? later : earlier;
        var frontPositiveReversed = dot(positiveFront.normal(), earlier.normal()) < 0f;
        var backPositiveReversed = dot(positiveBack.normal(), earlier.normal()) < 0f;
        addDepthEdgeIfEmitted(
                edges,
                new FaceSide(positiveFront.node(), frontPositiveReversed),
                positiveFront,
                new FaceSide(positiveBack.node(), backPositiveReversed),
                positiveBack);
        addDepthEdgeIfEmitted(
                edges,
                new FaceSide(positiveBack.node(), !backPositiveReversed),
                positiveBack,
                new FaceSide(positiveFront.node(), !frontPositiveReversed),
                positiveFront);
    }

    private static void addDepthEdgeIfEmitted(
            List<DepthEdge> edges, FaceSide frontFace, Face front, FaceSide backFace, Face back) {
        if ((!frontFace.reversed() || front.reverseWindingEmitted())
                && (!backFace.reversed() || back.reverseWindingEmitted())) {
            edges.add(new DepthEdge(frontFace, backFace));
        }
    }

    private static Resolution resolveCsgDepthLayers(
            IdentityHashMap<UBspNode, Float> nudges,
            int faceCount,
            List<DepthEdge> edges,
            Set<UBspNode> legacyBackfaces) {
        var layers = new HashMap<FaceSide, Integer>();
        for (int pass = 0; pass < faceCount * 2; pass++) {
            var changed = false;
            for (var edge : edges) {
                var layer = layers.getOrDefault(edge.front(), 0) + 1;
                if (layer > layers.getOrDefault(edge.back(), 0)) {
                    if (layer > Entity.CSG_DEPTH_LAYER_MAX) {
                        throw new IllegalStateException("Too many overlapping CSG depth layers");
                    }
                    layers.put(edge.back(), layer);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        var frontLayers = new IdentityHashMap<UBspNode, Integer>();
        var backLayers = new IdentityHashMap<UBspNode, Integer>();
        for (var entry : layers.entrySet()) {
            (entry.getKey().reversed() ? backLayers : frontLayers)
                    .put(entry.getKey().node(), entry.getValue());
        }
        return new Resolution(nudges, frontLayers, backLayers, legacyBackfaces);
    }

    private static float dot(Vec3 a, Vec3 b) {
        return a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ();
    }

    private static boolean isSameDepthLayerAxis(Vec3 a, Vec3 b) {
        var same = Math.abs(a.getX() - b.getX()) <= CSG_DEPTH_LAYER_NORMAL_EPS
                && Math.abs(a.getY() - b.getY()) <= CSG_DEPTH_LAYER_NORMAL_EPS
                && Math.abs(a.getZ() - b.getZ()) <= CSG_DEPTH_LAYER_NORMAL_EPS;
        var opposite = Math.abs(a.getX() + b.getX()) <= CSG_DEPTH_LAYER_NORMAL_EPS
                && Math.abs(a.getY() + b.getY()) <= CSG_DEPTH_LAYER_NORMAL_EPS
                && Math.abs(a.getZ() + b.getZ()) <= CSG_DEPTH_LAYER_NORMAL_EPS;
        return same || opposite;
    }

    private static boolean doPolygons2dOverlap(float[][] a, float[][] b) {
        return !isSeparatedOnAnyEdge(a, b) && !isSeparatedOnAnyEdge(b, a);
    }

    private static boolean isSeparatedOnAnyEdge(float[][] poly, float[][] other) {
        var n = poly.length;
        for (int i = 0; i < n; i++) {
            var x1 = poly[i][0];
            var y1 = poly[i][1];
            var x2 = poly[(i + 1) % n][0];
            var y2 = poly[(i + 1) % n][1];
            var nx = -(y2 - y1);
            var ny = x2 - x1;
            var minA = Float.MAX_VALUE;
            var maxA = -Float.MAX_VALUE;
            for (var p : poly) {
                var d = p[0] * nx + p[1] * ny;
                minA = Math.min(minA, d);
                maxA = Math.max(maxA, d);
            }
            var minB = Float.MAX_VALUE;
            var maxB = -Float.MAX_VALUE;
            for (var p : other) {
                var d = p[0] * nx + p[1] * ny;
                minB = Math.min(minB, d);
                maxB = Math.max(maxB, d);
            }
            var eps = COINCIDENT_OVERLAP_EPS * (float) Math.sqrt(nx * nx + ny * ny);
            if (maxA < minB + eps || maxB < minA + eps) {
                return true;
            }
        }
        return false;
    }
}
