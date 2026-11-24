package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.unreal.UTerrain.TerrainCoords;
import com.rost.cruma.core.unreal.UTerrain.TextureMapAxis;
import com.rost.cruma.core.unreal.UTerrain.ULayer;
import glm_.vec3.Vec3;

public record TerrainLayerMapping(
        float uScale,
        float vScale,
        float uPan,
        float vPan,
        int axis,
        float textureRotation,
        int pitch,
        int yaw,
        int roll) {
    public static final float FULL_TURN = 65536;

    public TerrainLayerMapping {
        if (!Float.isFinite(uScale)
                || !Float.isFinite(vScale)
                || !Float.isFinite(uPan)
                || !Float.isFinite(vPan)
                || !Float.isFinite(textureRotation)
                || axis < 0
                || axis > 2) {
            throw new IllegalArgumentException("Invalid Terrain layer mapping");
        }
    }

    public static TerrainLayerMapping of(ULayer layer) {
        var rotation = layer.getLayerRotation();
        return new TerrainLayerMapping(
                layer.getUScale(),
                layer.getVScale(),
                layer.getUPan(),
                layer.getVPan(),
                layer.getTextureMapAxis(),
                layer.getTextureRotation(),
                rotation.pitch(),
                rotation.yaw(),
                rotation.roll());
    }

    public static TerrainCoords coords(Coords coords) {
        return new TerrainCoords(
                vector(coords.origin()), vector(coords.xAxis()), vector(coords.yAxis()), vector(coords.zAxis()));
    }

    public TerrainLayerMapping withScale(float u, float v) {
        return new TerrainLayerMapping(u, v, uPan, vPan, axis, textureRotation, pitch, yaw, roll);
    }

    public TerrainLayerMapping withPan(float u, float v) {
        return new TerrainLayerMapping(uScale, vScale, u, v, axis, textureRotation, pitch, yaw, roll);
    }

    public TerrainLayerMapping withRotation(float units) {
        return new TerrainLayerMapping(uScale, vScale, uPan, vPan, axis, units, pitch, yaw, roll);
    }

    public TerrainCoords textureCoords(TerrainCoords toWorld, TerrainCoords toHeightMap) {
        var coords = TerrainCoords.identity()
                .inverseRotate(0f, textureRotation, 0f)
                .times(toHeightMap.transpose())
                .divideTextureScale(uScale, vScale);
        var pan = toWorld.transformVector(new Vec3(uPan * uScale, vPan * vScale, 0f));
        coords = coords.plusOrigin(pan).mapAxis(TextureMapAxis.fromValue(axis));
        return coords.times(TerrainCoords.identity().inverseRotate(pitch, yaw, roll));
    }

    public float[] projector(TerrainCoords toWorld, TerrainCoords toHeightMap) {
        var texture = textureCoords(toWorld, toHeightMap);
        var worldOrigin = toWorld.origin();
        var worldX = toWorld.xAxis();
        var worldY = toWorld.yAxis();
        var worldZ = toWorld.zAxis();
        var worldOffsetX = -dot(worldOrigin, worldX);
        var worldOffsetY = -dot(worldOrigin, worldY);
        var worldOffsetZ = -dot(worldOrigin, worldZ);
        var origin = texture.origin();
        var u = texture.xAxis();
        var v = texture.yAxis();
        return new float[] {
            worldX.getX() * u.getX() + worldY.getX() * u.getY() + worldZ.getX() * u.getZ(),
            worldX.getY() * u.getX() + worldY.getY() * u.getY() + worldZ.getY() * u.getZ(),
            worldX.getZ() * u.getX() + worldY.getZ() * u.getY() + worldZ.getZ() * u.getZ(),
            repeatPhase((worldOffsetX - origin.getX()) * (double) u.getX()
                    + (worldOffsetY - origin.getY()) * (double) u.getY()
                    + (worldOffsetZ - origin.getZ()) * (double) u.getZ()),
            worldX.getX() * v.getX() + worldY.getX() * v.getY() + worldZ.getX() * v.getZ(),
            worldX.getY() * v.getX() + worldY.getY() * v.getY() + worldZ.getY() * v.getZ(),
            worldX.getZ() * v.getX() + worldY.getZ() * v.getY() + worldZ.getZ() * v.getZ(),
            repeatPhase((worldOffsetX - origin.getX()) * (double) v.getX()
                    + (worldOffsetY - origin.getY()) * (double) v.getY()
                    + (worldOffsetZ - origin.getZ()) * (double) v.getZ())
        };
    }

    public float[] matrix(TerrainCoords toWorld, TerrainCoords toHeightMap) {
        var texture = textureCoords(toWorld, toHeightMap);
        var x = texture.xAxis();
        var y = texture.yAxis();
        var z = texture.zAxis();
        var origin = texture.origin().times(-1f);
        return new float[] {
            x.getX(),
            y.getX(),
            z.getX(),
            0,
            x.getY(),
            y.getY(),
            z.getY(),
            0,
            x.getZ(),
            y.getZ(),
            z.getZ(),
            0,
            dot(x, origin),
            dot(y, origin),
            dot(z, origin),
            1
        };
    }

    private static Vec3 vector(Vector value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static float dot(Vec3 a, Vec3 b) {
        return a.getX() * b.getX() + a.getY() * b.getY() + a.getZ() * b.getZ();
    }

    private static float repeatPhase(double offset) {
        return (float) (offset - Math.floor(offset));
    }
}
