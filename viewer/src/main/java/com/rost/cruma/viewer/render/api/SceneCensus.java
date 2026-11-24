package com.rost.cruma.viewer.render.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record SceneCensus(
        int mapCount,
        int entityCount,
        int primitiveCount,
        int fxEmitterCount,
        int waterSurfaceCount,
        int reflectionChildCount,
        int maximumTerrainLayers,
        int maximumAuthoredTextureInputs,
        Map<String, Integer> materialFeatureCombinations,
        Map<String, Integer> blendModes,
        Map<String, Integer> textureFormats,
        List<String> mapNames,
        String contentFingerprint) {
    public SceneCensus {
        if (mapCount < 0
                || entityCount < 0
                || primitiveCount < 0
                || fxEmitterCount < 0
                || waterSurfaceCount < 0
                || reflectionChildCount < 0
                || maximumTerrainLayers < 0
                || maximumAuthoredTextureInputs < 0) {
            throw new IllegalArgumentException("scene census counters cannot be negative");
        }
        materialFeatureCombinations = sortedCounts(materialFeatureCombinations);
        blendModes = sortedCounts(blendModes);
        textureFormats = sortedCounts(textureFormats);
        var names = mapNames == null ? new ArrayList<String>() : new ArrayList<>(mapNames);
        names.sort(String::compareTo);
        mapNames = List.copyOf(names);
        contentFingerprint = contentFingerprint == null ? "" : contentFingerprint;
        if (mapCount != mapNames.size()) {
            throw new IllegalArgumentException("scene census map count does not match map names");
        }
    }

    public static SceneCensus empty() {
        return new SceneCensus(0, 0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), List.of(), "");
    }

    private static Map<String, Integer> sortedCounts(Map<String, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        var sorted = new TreeMap<String, Integer>();
        for (var entry : source.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().isBlank()
                    || entry.getValue() == null
                    || entry.getValue() < 0) {
                throw new IllegalArgumentException("invalid scene census count");
            }
            sorted.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
