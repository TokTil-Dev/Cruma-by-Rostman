package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.NativeStaticMeshDraw;
import com.rost.cruma.core.world.NativeStaticMeshLod;
import com.rost.cruma.core.world.NativeStaticMeshLod.Actor;
import com.rost.cruma.core.world.NativeStaticMeshTangentState;
import java.util.HashMap;
import java.util.Map;

final class BgfxNativeMeshDraws {
    private final NativeStaticMeshTangentState resources;

    BgfxNativeMeshDraws(NativeStaticMeshTangentState resources) {
        this.resources = resources;
    }

    record Draw(boolean visible, boolean useTangent, boolean useVertexColor) {
        Draw(boolean visible, boolean useTangent) {
            this(visible, useTangent, false);
        }
    }

    View open(BgfxNativeView view, boolean forceBase) {
        return open(view, forceBase, false);
    }

    View open(BgfxNativeView view, boolean forceBase, boolean isolated) {
        return open(view, forceBase, isolated, 1f);
    }

    View open(BgfxNativeView view, boolean forceBase, boolean isolated, float impostorDistanceRatio) {
        return open(view, forceBase, isolated, impostorDistanceRatio, false);
    }

    View open(BgfxNativeView view, boolean forceBase, boolean isolated, float impostorDistanceRatio, boolean capture) {
        var origin = view.getOrigin();
        return new View(
                new NativeStaticMeshLod.View(origin.getX(), origin.getY(), true, 0, view.getRenderMode()),
                forceBase,
                isolated,
                impostorDistanceRatio,
                capture);
    }

    final class View {
        private final NativeStaticMeshLod.View view;
        private final boolean forceBase;
        private final boolean isolated;
        private final float impostorDistanceRatio;
        private final boolean capture;
        private final Map<Long, Draw> draws = new HashMap<>();

        private View(
                NativeStaticMeshLod.View view,
                boolean forceBase,
                boolean isolated,
                float impostorDistanceRatio,
                boolean capture) {
            this.view = view;
            this.forceBase = forceBase;
            this.isolated = isolated;
            this.impostorDistanceRatio = impostorDistanceRatio;
            this.capture = capture;
        }

        Draw prepare(long placement, NativeStaticMeshDraw draw, float actorX, float actorY) {
            var previous = draws.get(placement);
            if (previous != null) {
                return previous;
            }
            var selected = NativeStaticMeshLod.select(
                    draw.lod().withImpostorDistanceRatio(impostorDistanceRatio),
                    view,
                    new Actor(actorX, actorY, false, draw.ignoredRange(), draw.rangeForIgnoredRange()));
            if (capture) {
                selected = 0;
            } else if (isolated) {
                selected = draw.level();
            } else if (forceBase && selected >= 0) {
                selected = 0;
            }
            var decision = resources.enter(draw, selected, true, true, 0);
            var result = new Draw(
                    decision.visible(), decision.useTangent(), draw.selected().useVertexColor());
            draws.put(placement, result);
            return result;
        }
    }
}
