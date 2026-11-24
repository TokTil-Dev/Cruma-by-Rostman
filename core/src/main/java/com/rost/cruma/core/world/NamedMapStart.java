package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UActor;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.ULevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public record NamedMapStart(float x, float y, float z, double yawDegrees, double pitchDegrees) {
    static final float EYE_HEIGHT = 40f;

    static final float NEAR_START = 1024f;
    static final float OVERVIEW_HEIGHT = 5000f;
    static final double OVERVIEW_PITCH = -45;

    private static final float UNMOVED_RADIUS = 2048f;

    private static final double DEGREES_PER_UNIT = 360.0 / 65536.0;

    public static Optional<NamedMapStart> find(Path clientRoot, String map) {
        var archives = new UArchiveLoader(clientRoot, List.of(new USearchConfig("Maps", "unr")));
        var archive = archives.loadArchiveIfPresent(map);
        if (archive == null) {
            return Optional.empty();
        }
        try {
            var cameras = new ArrayList<Placement>();
            var starts = new ArrayList<Placement>();
            var terrains = new ArrayList<Placement>();
            for (var export : archive.getExportMap()) {
                var className = export.getClassName();
                if (className.equals("Camera") || className.equals("PlayerStart") || className.equals("TerrainInfo")) {
                    var placement = Placement.of(archive.getObjectLoader().exportActor(export));
                    (className.equals("Camera") ? cameras : className.equals("PlayerStart") ? starts : terrains)
                            .add(placement);
                }
            }
            return Optional.of(choose(cameras, starts, () -> terrains.isEmpty() ? placedActors(archive) : terrains));
        } finally {
            archives.removeArchive(map);
        }
    }

    record Placement(String name, float x, float y, float z, int pitch, int yaw) {
        static Placement of(UActor actor) {
            var location = actor.getLocation();
            var rotation = actor.getRotation();
            return new Placement(
                    actor.getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    rotation.pitch(),
                    rotation.yaw());
        }
    }

    static NamedMapStart choose(List<Placement> cameras, List<Placement> starts, Supplier<List<Placement>> middle) {
        var byNumber = Comparator.comparingLong((Placement placement) -> parseTrailingNumber(placement.name()));
        var view = cameras.stream()
                .filter(camera -> camera.pitch() != 0 || camera.yaw() != 0)
                .filter(camera -> Math.hypot(camera.x(), camera.y()) > UNMOVED_RADIUS)
                .filter(camera -> starts.isEmpty()
                        || starts.stream()
                                .anyMatch(start ->
                                        Math.hypot(camera.x() - start.x(), camera.y() - start.y()) <= NEAR_START))
                .min(byNumber);
        if (view.isPresent()) {
            var camera = view.get();
            return new NamedMapStart(
                    camera.x(), camera.y(), camera.z(), toDegrees(camera.yaw()), toDegrees(camera.pitch()));
        }
        return starts.stream()
                .min(byNumber)
                .map(start ->
                        new NamedMapStart(start.x(), start.y(), start.z() + EYE_HEIGHT, toDegrees(start.yaw()), 0))
                .orElseGet(() -> overview(middle.get()));
    }

    static NamedMapStart overview(List<Placement> placements) {
        if (placements.isEmpty()) {
            return new NamedMapStart(0, 0, OVERVIEW_HEIGHT, 0, OVERVIEW_PITCH);
        }
        return new NamedMapStart(
                median(placements.stream().mapToDouble(Placement::x).toArray()),
                median(placements.stream().mapToDouble(Placement::y).toArray()),
                median(placements.stream().mapToDouble(Placement::z).toArray()) + OVERVIEW_HEIGHT,
                0,
                OVERVIEW_PITCH);
    }

    static double toDegrees(int units) {
        var turn = Math.floorMod(units, 65536);
        return (turn >= 32768 ? turn - 65536 : turn) * DEGREES_PER_UNIT;
    }

    private static float median(double[] values) {
        Arrays.sort(values);
        var middle = values.length / 2;
        return (float) (values.length % 2 == 1 ? values[middle] : (values[middle - 1] + values[middle]) / 2);
    }

    private static List<Placement> placedActors(UArchive archive) {
        var placements = new ArrayList<Placement>();
        var exports = archive.getExportMap();
        for (var export : exports) {
            if (export.getClassName().equals("Level")
                    && archive.getObjectLoader().exportObject(export) instanceof ULevel level) {
                for (var reference : level.getActors()) {
                    if (reference.getIndex() > 0 && reference.getIndex() <= exports.size()) {
                        var placement = Placement.of(
                                archive.getObjectLoader().exportActor(exports.get(reference.getIndex() - 1)));
                        if (placement.x() != 0 || placement.y() != 0 || placement.z() != 0) {
                            placements.add(placement);
                        }
                    }
                }
            }
        }
        return placements;
    }

    private static long parseTrailingNumber(String name) {
        var end = name.length();
        var start = end;
        while (start > 0 && Character.isDigit(name.charAt(start - 1))) {
            start--;
        }
        if (start == end || end - start > 18) {
            return Long.MAX_VALUE;
        }
        return Long.parseLong(name, start, end, 10);
    }
}
