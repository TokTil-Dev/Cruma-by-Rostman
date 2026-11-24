package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationModel.Image;
import com.rost.cruma.core.export.LocationModel.Material;
import java.util.List;

record RigModel(
        String name,
        float metersPerUnit,
        float[] root,
        List<Joint> joints,
        float[] inverseBindMatrices,
        float[] positions,
        float[] normals,
        float[] uvs,
        int[] jointIndices,
        float[] weights,
        List<Primitive> primitives,
        List<Clip> clips,
        List<Image> images,
        List<Material> materials) {
    record Joint(String name, int parent, float[] translation, float[] rotation) {}

    record Primitive(int[] indices, int material) {}

    enum Path {
        ROTATION,
        TRANSLATION
    }

    record Channel(int joint, Path path, float[] times, float[] values) {}

    record Clip(String name, List<Channel> channels) {}

    int vertexCount() {
        return positions.length / 3;
    }
}
