package com.rost.cruma.viewer.render;

import com.rost.cruma.core.world.AmbientSound;
import com.rost.cruma.core.world.AmbientVolume;
import com.rost.cruma.core.world.L2FogInfo;
import com.rost.cruma.core.world.MapPawn;
import com.rost.cruma.core.world.MusicVolume;
import com.rost.cruma.core.world.NativeRandom;
import com.rost.cruma.core.world.NativeStaticMeshTangentState;
import com.rost.cruma.core.world.NativeVertexLightmapState;
import com.rost.cruma.core.world.ReflectionBspModel;
import com.rost.cruma.core.world.SkyZone;
import com.rost.cruma.core.world.SkyZones;
import com.rost.cruma.core.world.WaterVolume;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.core.world.ZoneStateChoice;
import com.rost.cruma.core.world.ZoneStateItem;
import com.rost.cruma.core.world.ZoneStateMesh;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Scene {
    private final NativeRandom nativeRandom = new NativeRandom();

    private final NativeStaticMeshTangentState meshTangents = new NativeStaticMeshTangentState();

    private final NativeVertexLightmapState vertexLightmaps = new NativeVertexLightmapState();

    private final Map<String, ReflectionBspModel> reflectionBspModels = new LinkedHashMap<>();
    private long reflectionBspModelsRevision;
    private final List<WaterVolume> waterVolumes = new ArrayList<>();
    private final List<MusicVolume> musicVolumes = new ArrayList<>();
    private final List<AmbientSound> ambientSounds = new ArrayList<>();
    private long ambientSoundsRevision;
    private final List<AmbientVolume> ambientVolumes = new ArrayList<>();
    private long ambientVolumesRevision;
    private final List<L2FogInfo> l2FogInfos = new ArrayList<>();
    private final Map<String, List<ZoneStateMesh>> zoneStateMeshes = new LinkedHashMap<>();
    private long zoneStateMeshesRevision;
    private final Map<String, List<ZoneStateItem>> zoneStateItems = new LinkedHashMap<>();
    private long zoneStateItemsRevision;
    private final Map<String, List<SkyZone>> skyZones = new LinkedHashMap<>();
    private List<SkyZone> allSkyZones = List.of();
    private final Map<String, List<MapPawn>> mapPawns = new LinkedHashMap<>();
    private long mapPawnsRevision;

    public NativeRandom getNativeRandom() {
        return nativeRandom;
    }

    public NativeStaticMeshTangentState getMeshTangents() {
        return meshTangents;
    }

    public NativeVertexLightmapState getVertexLightmaps() {
        return vertexLightmaps;
    }

    public void addWaterVolumes(List<WaterVolume> volumes) {
        waterVolumes.addAll(volumes);
    }

    public void removeWaterVolumes(List<WaterVolume> volumes) {
        waterVolumes.removeAll(volumes);
    }

    public List<WaterVolume> getWaterVolumes() {
        return waterVolumes;
    }

    public WaterVolume findContainingWaterVolume(Vec3 position) {
        return findContainingWaterVolume(position, ZoneStateChoice.EMPTY);
    }

    public WaterVolume findContainingWaterVolume(Vec3 position, ZoneStateChoice zoneState) {
        var tile = WorldTile.at(position.getX(), position.getY());
        WaterVolume selected = null;
        var priority = -1_000_000;
        for (var volume : waterVolumes) {
            if (volume.getOwnerTile() != null && !volume.getOwnerTile().equals(tile)) {
                continue;
            }
            if (!volume.isWaterInZoneState(zoneState)) {
                continue;
            }
            if (volume.getPriority() > priority && volume.contains(position)) {
                selected = volume;
                priority = volume.getPriority();
            }
        }
        return selected;
    }

    public void addMusicVolumes(List<MusicVolume> volumes) {
        musicVolumes.addAll(volumes);
    }

    public void removeMusicVolumes(List<MusicVolume> volumes) {
        musicVolumes.removeAll(volumes);
    }

    public List<MusicVolume> getMusicVolumes() {
        return musicVolumes;
    }

    public void addAmbientSounds(List<AmbientSound> sounds) {
        if (ambientSounds.addAll(sounds)) {
            ambientSoundsRevision++;
        }
    }

    public void removeAmbientSounds(List<AmbientSound> sounds) {
        if (ambientSounds.removeAll(sounds)) {
            ambientSoundsRevision++;
        }
    }

    public List<AmbientSound> getAmbientSounds() {
        return ambientSounds;
    }

    public long getAmbientSoundsRevision() {
        return ambientSoundsRevision;
    }

    public void addAmbientVolumes(List<AmbientVolume> volumes) {
        if (ambientVolumes.addAll(volumes)) {
            ambientVolumesRevision++;
        }
    }

    public void removeAmbientVolumes(List<AmbientVolume> volumes) {
        if (ambientVolumes.removeAll(volumes)) {
            ambientVolumesRevision++;
        }
    }

    public List<AmbientVolume> getAmbientVolumes() {
        return ambientVolumes;
    }

    public long getAmbientVolumesRevision() {
        return ambientVolumesRevision;
    }

    public void addL2FogInfos(List<L2FogInfo> infos) {
        l2FogInfos.addAll(infos);
    }

    public void removeL2FogInfos(List<L2FogInfo> infos) {
        l2FogInfos.removeAll(infos);
    }

    public List<L2FogInfo> getL2FogInfos() {
        return l2FogInfos;
    }

    public void setZoneStateMeshes(String map, List<ZoneStateMesh> meshes) {
        var previous = meshes.isEmpty() ? zoneStateMeshes.remove(map) : zoneStateMeshes.put(map, List.copyOf(meshes));
        if (previous != null || !meshes.isEmpty()) {
            zoneStateMeshesRevision++;
        }
    }

    public void removeZoneStateMeshes(String map) {
        if (zoneStateMeshes.remove(map) != null) {
            zoneStateMeshesRevision++;
        }
    }

    public List<ZoneStateMesh> getZoneStateMeshes() {
        var meshes = new ArrayList<ZoneStateMesh>();
        zoneStateMeshes.values().forEach(meshes::addAll);
        return meshes;
    }

    public long getZoneStateMeshesRevision() {
        return zoneStateMeshesRevision;
    }

    public void setZoneStateItems(String map, List<ZoneStateItem> items) {
        var previous = items.isEmpty() ? zoneStateItems.remove(map) : zoneStateItems.put(map, List.copyOf(items));
        if (previous != null || !items.isEmpty()) {
            zoneStateItemsRevision++;
        }
    }

    public void removeZoneStateItems(String map) {
        if (zoneStateItems.remove(map) != null) {
            zoneStateItemsRevision++;
        }
    }

    public List<ZoneStateItem> getZoneStateItems() {
        var items = new ArrayList<ZoneStateItem>();
        zoneStateItems.values().forEach(items::addAll);
        return items;
    }

    public long getZoneStateItemsRevision() {
        return zoneStateItemsRevision;
    }

    public void setSkyZones(String map, List<SkyZone> zones) {
        var previous = zones.isEmpty() ? skyZones.remove(map) : skyZones.put(map, List.copyOf(zones));
        if (previous != null || !zones.isEmpty()) {
            collectSkyZones();
        }
    }

    public void removeSkyZones(String map) {
        if (skyZones.remove(map) != null) {
            collectSkyZones();
        }
    }

    public List<SkyZone> getSkyZones() {
        return allSkyZones;
    }

    public void setMapPawns(String map, List<MapPawn> pawns) {
        var previous = pawns.isEmpty() ? mapPawns.remove(map) : mapPawns.put(map, List.copyOf(pawns));
        if (previous != null || !pawns.isEmpty()) {
            mapPawnsRevision++;
        }
    }

    public void removeMapPawns(String map) {
        if (mapPawns.remove(map) != null) {
            mapPawnsRevision++;
        }
    }

    public Map<String, List<MapPawn>> getMapPawns() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mapPawns));
    }

    public long getMapPawnsRevision() {
        return mapPawnsRevision;
    }

    public void addReflectionBspModels(List<ReflectionBspModel> models) {
        for (var model : models) {
            if (reflectionBspModels.putIfAbsent(model.getId(), model) == null) {
                reflectionBspModelsRevision++;
            }
        }
    }

    public void removeReflectionBspModels(List<ReflectionBspModel> models) {
        for (var model : models) {
            var resident = reflectionBspModels.get(model.getId());
            if (resident != null && (resident == model || resident.equals(model))) {
                reflectionBspModels.remove(model.getId());
                reflectionBspModelsRevision++;
            }
        }
    }

    public Map<String, ReflectionBspModel> getReflectionBspModels() {
        return reflectionBspModels;
    }

    public long getReflectionBspModelsRevision() {
        return reflectionBspModelsRevision;
    }

    private void collectSkyZones() {
        var all = new ArrayList<SkyZone>();
        skyZones.values().forEach(all::addAll);
        allSkyZones = List.copyOf(all);
    }
}
