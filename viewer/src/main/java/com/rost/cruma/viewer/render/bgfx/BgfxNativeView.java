package com.rost.cruma.viewer.render.bgfx;

import com.rost.cruma.core.world.BspVisibilityVolumes.View;
import com.rost.cruma.core.world.NativeLightScene;
import com.rost.cruma.core.world.NativeViewMatrices;
import com.rost.cruma.core.world.NativeWaterReflection;
import com.rost.cruma.viewer.render.Camera;
import glm_.vec3.Vec3;
import glm_.vec4.Vec4;
import java.nio.FloatBuffer;
import java.util.List;

final class BgfxNativeView {
    private final NativeViewMatrices matrices;
    private final Vec3 origin;
    private final int renderMode;
    private final float near;
    private final int suppliedZone;

    private BgfxNativeView(NativeViewMatrices matrices, Vec3 origin, int renderMode, float near, int suppliedZone) {
        this.matrices = matrices;
        this.origin = new Vec3(origin);
        this.renderMode = renderMode;
        this.near = near;
        this.suppliedZone = suppliedZone;
    }

    static BgfxNativeView main(Camera camera) {
        return new BgfxNativeView(
                new NativeViewMatrices(toArray(camera.getViewMatrix()), toArray(camera.getProjectionMatrix())),
                camera.getPosition(),
                camera.isOrtho() ? 13 : 0,
                camera.getProjectionNear(),
                -1);
    }

    static BgfxNativeView reflected(Camera camera, NativeWaterReflection reflection, int viewZone) {
        if (viewZone < 0 || viewZone >= Long.SIZE) {
            throw new IllegalArgumentException("reflection view zone must be in [0, 63]");
        }
        var parent = new NativeViewMatrices(
                toArray(camera.getViewMatrix()), toArray(camera.getReflectionProjectionMatrix()));
        return new BgfxNativeView(
                parent.reflected(reflection),
                reflection.reflect(camera.getPosition()),
                camera.isOrtho() ? 13 : 0,
                camera.getReflectionNear(),
                viewZone);
    }

    Vec3 getOrigin() {
        return new Vec3(origin);
    }

    int getRenderMode() {
        return renderMode;
    }

    NativeViewMatrices getMatrices() {
        return matrices;
    }

    List<Vec4> getFrustum() {
        return matrices.getFrustum(renderMode, near, origin);
    }

    View getVisibility(NativeLightScene scene, boolean precaching, boolean antiPortalClipping) {
        var zone = suppliedZone >= 0 ? suppliedZone : scene.pointRegion(origin).zone();
        return new View(
                node -> scene.renderBound(scene.getNode(node).getRenderBound()),
                scene.getZoneCount(),
                zone,
                precaching,
                precaching ? List.of() : getFrustum(),
                antiPortalClipping) {
            @Override
            public void visitSurface(int node, int[] farSources) {
                scene.visitPortalSurfaces(this, origin, node);
            }
        };
    }

    private static float[] toArray(FloatBuffer source) {
        var result = new float[16];
        for (var i = 0; i < result.length; i++) {
            result[i] = source.get(i);
        }
        return result;
    }
}
