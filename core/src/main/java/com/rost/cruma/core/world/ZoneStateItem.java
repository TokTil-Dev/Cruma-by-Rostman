package com.rost.cruma.core.world;

import com.rost.cruma.core.world.Entity.MultiTextureEntityMaterial;
import com.rost.cruma.core.world.Entity.TerrainMesh;
import com.rost.cruma.core.world.UnrealLoader.DecorationTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ZoneStateItem(Kind kind, String name, String map, ZoneRender render) {
    private static final int DRAWN_TERRAIN_LAYERS = 32;

    public enum Kind {
        EFFECT,
        FOG,
        WATER,
        SKY,
        SKY_ZONE,
        TERRAIN_LAYER,
        DECORATION
    }

    public static List<ZoneStateItem> of(UMap map) {
        var pkg = packageOf(map);
        var items = new ArrayList<ZoneStateItem>();
        var effects = new LinkedHashMap<String, ZoneRender>();
        map.spriteEmitters()
                .forEach(emitter ->
                        effects.put(emitter.actorName(), emitter.visibility().zoneRender()));
        map.meshEmitters()
                .forEach(emitter ->
                        effects.put(emitter.actorName(), emitter.visibility().zoneRender()));
        map.beamEmitters()
                .forEach(emitter ->
                        effects.put(emitter.actorName(), emitter.visibility().zoneRender()));
        addAll(items, Kind.EFFECT, pkg, effects);
        for (var index = 0; index < map.l2FogInfos().size(); index++) {
            add(
                    items,
                    Kind.FOG,
                    pkg + ".L2FogInfo#" + index,
                    pkg,
                    map.l2FogInfos().get(index).getZoneRender());
        }
        for (var index = 0; index < map.waterVolumes().size(); index++) {
            add(
                    items,
                    Kind.WATER,
                    pkg + ".WaterVolume#" + index,
                    pkg,
                    map.waterVolumes().get(index).getZoneRender());
        }
        var localSky = map.localSky();
        if (localSky != null && localSky.sky() != null) {
            var sky = localSky.sky();
            for (var layer : sky.drawLayers()) {
                if (sky.isLayerActive(layer)) {
                    add(items, Kind.SKY, layer.entity().getMeshName(), pkg, skyRender(layer));
                }
            }
        }
        if (localSky != null) {
            for (var zone : localSky.zones()) {
                if (zone.listed()) {
                    add(items, Kind.SKY_ZONE, zone.name(), pkg, zone.zoneRender());
                }
            }
        }
        var layers = new LinkedHashMap<String, ZoneRender>();
        var decorations = new LinkedHashMap<String, ZoneRender>();
        for (var entity : map.entities()) {
            var actor = entity.getSourceActor();
            var meshDecoration = entity.getMeshDecoration();
            if (DecorationTemplate.isDecorationActor(actor)) {
                decorations.put(actor, entity.getZoneRender());
            } else if (meshDecoration != null) {
                if (meshDecoration.layerRender() != null) {
                    decorations.put(actor + " decoration " + meshDecoration.layer(), meshDecoration.layerRender());
                }
            } else if (entity.getMesh() instanceof TerrainMesh terrain
                    && terrain.surface().material() instanceof MultiTextureEntityMaterial material) {
                var entries = material.multiTexture().entries();
                for (var layer = 0; layer < Math.min(DRAWN_TERRAIN_LAYERS, entries.size()); layer++) {
                    if (entries.get(layer).texture() != null) {
                        layers.put(
                                (actor == null ? pkg + ".Terrain" : actor) + " layer " + layer,
                                entries.get(layer).zoneRender());
                    }
                }
            }
        }
        for (var template : map.decorations()) {
            decorations.put(
                    DecorationTemplate.actorOf(template.getOwner(), template.getLayerIndex()),
                    template.getZoneRender());
        }
        addAll(items, Kind.TERRAIN_LAYER, pkg, layers);
        addAll(items, Kind.DECORATION, pkg, decorations);
        return List.copyOf(items);
    }

    public static int defaultOf(UMap map) {
        var state = MapEventState.AS_THE_MAP;
        for (var entity : map.entities()) {
            state = Math.max(state, entity.getZoneRender().defaultState());
        }
        for (var sound : map.ambientSounds()) {
            state = Math.max(state, sound.zoneRender().defaultState());
        }
        for (var item : of(map)) {
            state = Math.max(state, item.render().defaultState());
        }
        return state;
    }

    public static String packageOf(UMap map) {
        for (var entity : map.entities()) {
            var actor = entity.getSourceActor();
            var dot = actor == null ? -1 : actor.indexOf('.');
            if (dot > 0) {
                return actor.substring(0, dot);
            }
        }
        return map.name();
    }

    public boolean shows(ZoneStateChoice choice) {
        var chosen = render.choiceIn(choice);
        return kind == Kind.WATER && chosen == MapEventState.AS_THE_MAP || render.shows(chosen);
    }

    private static ZoneRender skyRender(USky.Layer layer) {
        var render = layer.entity().getZoneRender();
        return render.mask() == 0 || render.hidesWithoutState()
                ? render
                : new ZoneRender(~render.mask(), render.zoneStart(), true, render.map());
    }

    private static void addAll(List<ZoneStateItem> items, Kind kind, String pkg, Map<String, ZoneRender> renders) {
        renders.forEach((name, render) -> add(items, kind, name, pkg, render));
    }

    private static void add(List<ZoneStateItem> items, Kind kind, String name, String pkg, ZoneRender render) {
        if (render.mask() != 0 && render.zoneStart() != ZoneRender.NO_ZONE_STATES) {
            items.add(new ZoneStateItem(kind, name, pkg, render));
        }
    }
}
