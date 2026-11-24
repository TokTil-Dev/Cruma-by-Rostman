package com.rost.cruma.viewer.render.api;

import java.util.Arrays;

public record SceneUploadStats(
        int entityMeshCacheSize,
        int staticMeshCacheSize,
        int textureCacheSize,
        int namedTextureCacheSize,
        int textureArrayFamilyCount,
        int textureArrayLayerCount,
        long textureArrayVramBytes,
        int textureArrayDualPathCount,
        boolean textureArrayMultiUnitEnabled,
        int textureArrayMultiUnitBuckets,
        int textureArrayMultiUnitFamilies,
        int textureArrayMultiUnitGroups,
        int[] textureArrayMultiUnitBucketFamilies,
        int csgLightmapArrayCount,
        int csgLightmapLayerCount,
        long csgLightmapVramBytes) {
    public SceneUploadStats {
        textureArrayMultiUnitBucketFamilies = textureArrayMultiUnitBucketFamilies == null
                ? new int[8]
                : Arrays.copyOf(textureArrayMultiUnitBucketFamilies, textureArrayMultiUnitBucketFamilies.length);
    }

    @Override
    public int[] textureArrayMultiUnitBucketFamilies() {
        return Arrays.copyOf(textureArrayMultiUnitBucketFamilies, textureArrayMultiUnitBucketFamilies.length);
    }
}
