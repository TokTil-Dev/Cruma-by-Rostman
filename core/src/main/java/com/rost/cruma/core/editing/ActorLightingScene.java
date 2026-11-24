package com.rost.cruma.core.editing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

public final class ActorLightingScene {
    public static final int LIST_END = -1;

    public static final int UNRESOLVED = -2;

    private final Settings settings;
    private final List<Light> lights;
    private final int[] leafZones;
    private final int[] leafLights;
    private final int[] lightSlots;
    private final Zone[] zones;
    private final Zone levelZone;
    private final ActorLightingTrace trace;
    private final Map<String, Receiver> receivers;
    private final boolean beastLevel;
    private final Bake bake;
    private final @Nullable ActorLightingTrace beastTrace;
    private final List<Light> beastLights;
    private final List<String> beastTerrainOwners;
    private final List<String> notes;

    public ActorLightingScene(
            Settings settings,
            List<Light> lights,
            int[] leafZones,
            int[] leafLights,
            int[] lightSlots,
            @Nullable Zone[] zones,
            Zone levelZone,
            ActorLightingTrace trace,
            Map<String, Receiver> receivers,
            boolean beastLevel,
            List<String> notes) {
        this(
                settings,
                lights,
                leafZones,
                leafLights,
                lightSlots,
                zones,
                levelZone,
                trace,
                receivers,
                beastLevel,
                null,
                List.of(),
                notes);
    }

    public ActorLightingScene(
            Settings settings,
            List<Light> lights,
            int[] leafZones,
            int[] leafLights,
            int[] lightSlots,
            @Nullable Zone[] zones,
            Zone levelZone,
            ActorLightingTrace trace,
            Map<String, Receiver> receivers,
            boolean beastLevel,
            @Nullable ActorLightingTrace beastTrace,
            List<Light> beastLights,
            List<String> notes) {
        this(
                settings,
                lights,
                leafZones,
                leafLights,
                lightSlots,
                zones,
                levelZone,
                trace,
                receivers,
                beastLevel,
                beastTrace,
                beastLights,
                List.of(),
                notes);
    }

    public ActorLightingScene(
            Settings settings,
            List<Light> lights,
            int[] leafZones,
            int[] leafLights,
            int[] lightSlots,
            @Nullable Zone[] zones,
            Zone levelZone,
            ActorLightingTrace trace,
            Map<String, Receiver> receivers,
            boolean beastLevel,
            @Nullable ActorLightingTrace beastTrace,
            List<Light> beastLights,
            List<String> beastTerrainOwners,
            List<String> notes) {
        this(
                settings,
                lights,
                leafZones,
                leafLights,
                lightSlots,
                zones,
                levelZone,
                trace,
                receivers,
                Bake.NATIVE,
                beastLevel,
                beastTrace,
                beastLights,
                beastTerrainOwners,
                notes);
    }

    public ActorLightingScene(
            Settings settings,
            List<Light> lights,
            int[] leafZones,
            int[] leafLights,
            int[] lightSlots,
            @Nullable Zone[] zones,
            Zone levelZone,
            ActorLightingTrace trace,
            Map<String, Receiver> receivers,
            Bake bake,
            boolean beastLevel,
            @Nullable ActorLightingTrace beastTrace,
            List<Light> beastLights,
            List<String> beastTerrainOwners,
            List<String> notes) {
        this.bake = Objects.requireNonNull(bake);
        this.beastTerrainOwners = List.copyOf(beastTerrainOwners);
        this.beastTrace = beastTrace;
        this.beastLights = List.copyOf(beastLights);
        this.settings = Objects.requireNonNull(settings);
        this.lights = List.copyOf(lights);
        if (leafZones.length != leafLights.length) {
            throw new IllegalArgumentException("Leaf arrays differ");
        }
        this.leafZones = leafZones.clone();
        this.leafLights = leafLights.clone();
        this.lightSlots = lightSlots.clone();
        for (var slot : this.lightSlots) {
            if (slot < UNRESOLVED || slot >= this.lights.size()) {
                throw new IllegalArgumentException("Invalid light slot " + slot);
            }
        }
        this.zones = zones.clone();
        this.levelZone = Objects.requireNonNull(levelZone);
        this.trace = Objects.requireNonNull(trace);
        this.receivers = Map.copyOf(receivers);
        this.beastLevel = beastLevel;
        this.notes = List.copyOf(notes);
    }

    public record Settings(
            int environmentSlots, float traceOffset, float sunLongitude, float sunSlope, boolean reverseSlope) {
        public static final Settings CLIENT_DEFAULTS = new Settings(8, 4f, 180f, 30f, false);

        public Settings {
            if (environmentSlots < 0
                    || environmentSlots > 64
                    || !Float.isFinite(traceOffset)
                    || !Float.isFinite(sunLongitude)
                    || !Float.isFinite(sunSlope)) {
                throw new IllegalArgumentException("Invalid lighting settings");
            }
        }
    }

    public record Light(
            int reference,
            String name,
            int type,
            int effect,
            boolean specialLit,
            boolean excluded,
            boolean hidden,
            int zone,
            boolean skyZone,
            float x,
            float y,
            float z,
            float radius,
            float red,
            float green,
            float blue,
            float directionX,
            float directionY,
            float directionZ,
            int cone) {
        public Light {
            Objects.requireNonNull(name);
        }
    }

    public enum Bake {
        NATIVE,
        EARLY
    }

    public record Zone(String tag, boolean sunAffect) {
        public Zone {
            Objects.requireNonNull(tag);
        }
    }

    public record Mesh(
            String name,
            float[] positions,
            float[] normals,
            byte @Nullable [] assetColors,
            boolean useVertexColor,
            int[] triangles,
            float @Nullable [] beastUvs,
            int beastSize) {
        public Mesh(
                String name,
                float[] positions,
                float[] normals,
                byte @Nullable [] assetColors,
                boolean useVertexColor) {
            this(name, positions, normals, assetColors, useVertexColor, new int[0], null, 0);
        }

        public Mesh {
            Objects.requireNonNull(name);
            Objects.requireNonNull(triangles);
            if (positions.length != normals.length || positions.length % 3 != 0) {
                throw new IllegalArgumentException("Mesh positions and normals differ");
            }
            if (assetColors != null && assetColors.length != positions.length / 3 * 4) {
                assetColors = null;
            }
            if (triangles.length % 3 != 0) {
                throw new IllegalArgumentException("Mesh triangles need three indices each");
            }
            for (var index : triangles) {
                if (index < 0 || index >= positions.length / 3) {
                    throw new IllegalArgumentException("Mesh triangle index out of range");
                }
            }
            if (beastUvs != null && beastUvs.length != positions.length / 3 * 2) {
                beastUvs = null;
            }
        }

        public int vertexCount() {
            return positions.length / 3;
        }

        public boolean hasBeastLayout() {
            return beastUvs != null && triangles.length > 0;
        }
    }

    public record Receiver(
            String path,
            @Nullable Mesh[] lods,
            float[] localToWorld,
            float[] sphere,
            float[] bounds,
            int[] leaves,
            int caster,
            boolean eligible,
            boolean hidden,
            boolean specialLit,
            boolean staticLighting,
            String forcedRegionTag,
            int forcedRegion,
            float scaleGlow) {
        public Receiver {
            Objects.requireNonNull(path);
            Objects.requireNonNull(forcedRegionTag);
            if (lods.length != 3
                    || lods[0] == null
                    || localToWorld.length != 16
                    || sphere.length != 4
                    || bounds.length != 6) {
                throw new IllegalArgumentException("Invalid lighting receiver " + path);
            }
        }
    }

    public Settings getSettings() {
        return settings;
    }

    public List<Light> getLights() {
        return lights;
    }

    public ActorLightingTrace getTrace() {
        return trace;
    }

    public Map<String, Receiver> getReceivers() {
        return receivers;
    }

    public @Nullable Receiver getReceiver(String path) {
        return receivers.get(path);
    }

    public Bake getBake() {
        return bake;
    }

    public boolean isBeastLevel() {
        return beastLevel;
    }

    public @Nullable ActorLightingTrace getBeastTrace() {
        return beastTrace;
    }

    public List<Light> getBeastLights() {
        return beastLights;
    }

    public @Nullable ActorLightingTrace getBeastTraceWith(Map<String, TerrainHeightField> heights) {
        if (beastTrace == null || heights.isEmpty()) {
            return beastTrace;
        }
        var terrains = new ArrayList<>(beastTrace.getTerrains());
        for (var i = 0; i < terrains.size() && i < beastTerrainOwners.size(); i++) {
            var field = heights.get(beastTerrainOwners.get(i));
            if (field != null) {
                terrains.set(i, terrains.get(i).withHeights(field));
            }
        }
        return beastTrace.withTerrains(terrains);
    }

    public List<String> getBeastTerrainOwners() {
        return beastTerrainOwners;
    }

    public List<String> getNotes() {
        return notes;
    }

    int getLeafCount() {
        return leafZones.length;
    }

    int getLeafZone(int leaf) {
        return leafZones[leaf];
    }

    int getLeafLights(int leaf) {
        return leafLights[leaf];
    }

    int getLightSlotCount() {
        return lightSlots.length;
    }

    int getLightSlot(int slot) {
        return lightSlots[slot];
    }

    Zone getZone(int index) {
        var zone = index >= 0 && index < zones.length ? zones[index] : null;
        return zone == null ? levelZone : zone;
    }
}
