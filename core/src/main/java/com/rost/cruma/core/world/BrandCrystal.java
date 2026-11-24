package com.rost.cruma.core.world;

import com.rost.cruma.core.npc.ClientIcons;
import com.rost.cruma.core.unreal.UMaterial;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UStaticMesh;
import com.rost.cruma.core.world.Entity.UvAnimation;
import com.rost.cruma.core.world.LoadingScene.Picture;
import org.jetbrains.annotations.Nullable;

public record BrandCrystal(
        float[] vertices, Picture body, @Nullable Picture sheen, UvAnimation sheenTurn) {
    public static final String MESH = "cruma_s.g_05";

    private static final int VERTEX_FLOATS = 8;

    public static @Nullable BrandCrystal read(UnrealLoader loader) {
        try {
            var mesh = loader.findStaticMesh(MESH);
            return mesh == null ? null : of(mesh);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static @Nullable BrandCrystal of(UStaticMesh mesh) {
        var streams = mesh.getUvStream();
        var materials = mesh.getMaterials();
        var surfaces = mesh.getSurfaces();
        if (streams.length == 0 || materials.length == 0 || surfaces.length == 0) {
            return null;
        }
        var material = materials[0].getMaterial().get();
        var shader = material instanceof UShader found ? found : null;
        var diffuse = shader == null ? material : shader.getDiffuse().get();
        var body = picture(diffuse, shader == null ? null : shader.getOpacity().get());
        if (body == null) {
            return null;
        }
        var specular = shader == null ? null : shader.getSpecular().get();
        var sheen = specular == null ? null : picture(specular, null);
        var vertices = triangles(mesh, surfaces[0]);
        if (vertices.length == 0) {
            return null;
        }
        return new BrandCrystal(
                vertices, body, sheen, sheen == null ? UvAnimation.NONE : UnrealLoader.layerAnimationOf(specular));
    }

    private static @Nullable Picture picture(@Nullable UMaterial material, @Nullable UMaterial opacity) {
        var texture = UnrealLoader.firstTextureOf(material);
        var icon = texture == null ? null : ClientIcons.decodeIcon(texture);
        if (icon == null) {
            return null;
        }
        var rgba = icon.rgba().clone();
        var opacityTexture = UnrealLoader.firstTextureOf(opacity);
        var alpha = opacityTexture == null || opacityTexture == texture ? null : ClientIcons.decodeIcon(opacityTexture);
        var sameSize = alpha != null && alpha.width() == icon.width() && alpha.height() == icon.height();
        for (var pixel = 0; pixel < rgba.length / 4; pixel++) {
            if (opacityTexture == null) {
                rgba[pixel * 4 + 3] = (byte) 255;
            } else if (sameSize) {
                rgba[pixel * 4 + 3] = alpha.rgba()[pixel * 4 + 3];
            }
        }
        return new Picture(icon.width(), icon.height(), rgba);
    }

    private static float[] triangles(UStaticMesh mesh, UStaticMesh.Surface surface) {
        var points = mesh.getVertexStream().getVertices();
        var indices = mesh.getIndexStream().getIndices();
        var uvs = mesh.getUvStream()[0].getUvs();
        var count = surface.getTriangleMax() * 3;
        if (count == 0 || surface.getFirstIndex() + count > indices.length) {
            return new float[0];
        }
        var min = new float[] {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        var max = new float[] {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (var corner = 0; corner < count; corner++) {
            var index = indices[surface.getFirstIndex() + corner];
            if (index < 0 || index >= points.length || index >= uvs.length) {
                return new float[0];
            }
            var point = points[index].getLocation();
            for (var axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], point.get(axis));
                max[axis] = Math.max(max[axis], point.get(axis));
            }
        }
        var half = Math.max((max[0] - min[0]) / 2, Math.max((max[1] - min[1]) / 2, (max[2] - min[2]) / 2));
        if (!(half > 0)) {
            return new float[0];
        }
        var out = new float[count * VERTEX_FLOATS];
        for (var corner = 0; corner < count; corner++) {
            var index = indices[surface.getFirstIndex() + corner];
            var point = points[index].getLocation();
            var normal = points[index].getNormal();
            var at = corner * VERTEX_FLOATS;
            for (var axis = 0; axis < 3; axis++) {
                out[at + axis] = (point.get(axis) - (min[axis] + max[axis]) / 2) / half;
                out[at + 3 + axis] = normal.get(axis);
            }
            out[at + 6] = uvs[index].getX();
            out[at + 7] = uvs[index].getY();
        }
        return out;
    }
}
