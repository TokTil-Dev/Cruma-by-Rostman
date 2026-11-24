package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.viewer.render.Camera.Snapshot;
import java.util.Map;
import java.util.Objects;

public record GeoScenario(
        int version,
        String id,
        String name,
        String sourceAlias,
        Fingerprints fingerprints,
        Camera camera,
        Endpoint a,
        Endpoint b,
        Profile profile,
        Expected expected) {
    public static final int VERSION = 1;
    public static final String ALGORITHM = "l2off-ground-projectile-path-v2";

    public GeoScenario {
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported geodata scenario version: " + version);
        }
        requireId(id);
        requireId(sourceAlias);
        if (name == null || name.isBlank() || name.length() > 256) {
            throw new IllegalArgumentException("Invalid scenario name");
        }
        Objects.requireNonNull(fingerprints);
        Objects.requireNonNull(camera);
        Objects.requireNonNull(a);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(expected);
        if (profile.kind() != Kind.REACHABILITY) {
            Objects.requireNonNull(b);
        }
    }

    public enum Kind {
        MOVEMENT,
        SIGHT,
        PATH,
        REACHABILITY
    }

    public record Fingerprints(
            String data, String algorithm, String graph, Map<String, String> regions, boolean editedPreview) {
        public Fingerprints {
            requireHash(data, false);
            requireHash(graph, true);
            requireId(algorithm);
            regions = Map.copyOf(regions);
            for (var entry : regions.entrySet()) {
                if (!entry.getKey().matches("(1[0-9]|2[0-9])_(1[0-9]|2[0-6])")) {
                    throw new IllegalArgumentException("Invalid region identifier");
                }
                requireHash(entry.getValue(), false);
            }
        }

        public Fingerprints(String data, String algorithm, String graph) {
            this(data, algorithm, graph, Map.of(), false);
        }

        public Fingerprints(String data, String algorithm, String graph, Map<String, String> regions) {
            this(data, algorithm, graph, regions, false);
        }
    }

    public record Camera(GeoPoint position, double yaw, double pitch, Snapshot state) {
        public Camera(GeoPoint position, double yaw, double pitch) {
            this(position, yaw, pitch, null);
        }

        public Camera {
            Objects.requireNonNull(position);
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                throw new IllegalArgumentException("Invalid camera rotation");
            }
        }
    }

    public record Layer(int x, int y, int index, int height) {
        public Layer {
            if ((x & 15) != 0 || (y & 15) != 0 || index < 0) {
                throw new IllegalArgumentException("Invalid selected layer");
            }
        }
    }

    public record Endpoint(GeoPoint point, Layer selectedLayer) {
        public Endpoint {
            Objects.requireNonNull(point);
        }
    }

    public record Profile(
            Kind kind,
            String movementMode,
            double distance,
            int maxSteps,
            int maxTrace,
            int radius,
            int maxNodes,
            int maxPathVisits,
            int maxWaypoints,
            int maxMillis) {
        public Profile(
                Kind kind,
                String movementMode,
                double distance,
                int maxSteps,
                int maxTrace,
                int radius,
                int maxNodes,
                int maxPathVisits,
                int maxWaypoints) {
            this(kind, movementMode, distance, maxSteps, maxTrace, radius, maxNodes, maxPathVisits, maxWaypoints, 5000);
        }

        public Profile {
            Objects.requireNonNull(kind);
            requireId(movementMode);
            if (!Double.isFinite(distance)
                    || distance < 0
                    || distance > 10_000_000
                    || maxSteps < 1
                    || maxSteps > 1_000_000
                    || maxTrace < 0
                    || maxTrace > 100_000
                    || radius < 16
                    || radius > 32768
                    || maxNodes < 1
                    || maxNodes > 1_000_000
                    || maxPathVisits < 0
                    || maxPathVisits > 100_000
                    || maxWaypoints < 1
                    || maxWaypoints > 4096
                    || maxMillis < 1
                    || maxMillis > 600_000) {
                throw new IllegalArgumentException("Invalid scenario limits");
            }
        }

        public static Profile defaults(Kind kind) {
            return new Profile(kind, "GROUND", 8192, 65536, 8192, 1024, 100_000, 1000, 256);
        }
    }

    public record Expected(
            boolean success,
            boolean complete,
            String status,
            GeoPoint finalPosition,
            double traveledDistance,
            String digest,
            double tolerance,
            Layer resolvedA,
            Layer resolvedB) {
        public Expected(
                boolean success,
                boolean complete,
                String status,
                GeoPoint finalPosition,
                double traveledDistance,
                String digest,
                double tolerance) {
            this(success, complete, status, finalPosition, traveledDistance, digest, tolerance, null, null);
        }

        public Expected {
            requireId(status);
            requireHash(digest, true);
            if (!Double.isFinite(traveledDistance)
                    || traveledDistance < 0
                    || !Double.isFinite(tolerance)
                    || tolerance < 0
                    || tolerance > 16) {
                throw new IllegalArgumentException("Invalid expectation");
            }
        }
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Expected a portable identifier");
        }
    }

    private static void requireHash(String value, boolean optional) {
        if (value == null || !(optional && value.isEmpty()) && !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected a SHA-256 fingerprint");
        }
    }
}
