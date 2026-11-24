package com.rost.cruma.core.env;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public record EnvSetup(
        String skyBox, String hazeRing, List<String> clouds, float slopeSunAngle, float shadowTickInterval) {
    public EnvSetup(String skyBox, String hazeRing, List<String> clouds) {
        this(skyBox, hazeRing, clouds, 0f, -1f);
    }

    public EnvSetup(String skyBox, String hazeRing, List<String> clouds, float slopeSunAngle) {
        this(skyBox, hazeRing, clouds, slopeSunAngle, -1f);
    }

    public EnvSetup {
        skyBox = skyBox == null ? "" : skyBox.trim();
        hazeRing = hazeRing == null ? "" : hazeRing.trim();
        clouds = List.copyOf(clouds);
    }

    public List<String> activeSkyMaterials() {
        var result = new LinkedHashMap<String, String>();
        add(result, skyBox);
        add(result, hazeRing);
        for (var cloud : clouds) {
            add(result, cloud);
        }
        return List.copyOf(result.values());
    }

    private static void add(LinkedHashMap<String, String> result, String name) {
        if (name != null && !name.isBlank()) {
            result.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
        }
    }
}
