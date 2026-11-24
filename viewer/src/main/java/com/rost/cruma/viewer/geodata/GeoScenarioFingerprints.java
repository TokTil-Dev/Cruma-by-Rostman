package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataSnapshot;
import com.rost.cruma.core.geodata.GeoTile;
import com.rost.cruma.viewer.geodata.GeoScenario.Fingerprints;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map.Entry;
import java.util.TreeMap;

public final class GeoScenarioFingerprints {
    private GeoScenarioFingerprints() {}

    public static Fingerprints capture(GeoDataSnapshot snapshot, String graphFingerprint) {
        return capture(snapshot, graphFingerprint, false);
    }

    public static Fingerprints capture(GeoDataSnapshot snapshot, String graphFingerprint, boolean editedPreview) {
        var regions = new TreeMap<String, String>();
        for (var entry : snapshot.getFingerprints().entrySet()) {
            regions.put(entry.getKey().x() + "_" + entry.getKey().y(), entry.getValue());
        }
        return new Fingerprints(of(snapshot), GeoScenario.ALGORITHM, graphFingerprint, regions, editedPreview);
    }

    public static String of(GeoDataSnapshot snapshot) {
        var entries = new ArrayList<>(snapshot.getFingerprints().entrySet());
        entries.sort(Entry.comparingByKey(Comparator.comparingInt(GeoTile::x).thenComparingInt(GeoTile::y)));
        var content = new StringBuilder();
        for (var entry : entries) {
            content.append(entry.getKey().x())
                    .append('_')
                    .append(entry.getKey().y())
                    .append(':')
                    .append(entry.getValue())
                    .append('\n');
        }
        return hash(content.toString());
    }

    public static String hash(String content) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
