package com.rost.cruma.core.world;

import java.util.LinkedHashMap;
import java.util.List;

public record ZoneStateMesh(String actor, String map, ZoneRender render, int looks) {
    public ZoneStateMesh(String actor, String map, ZoneRender render) {
        this(actor, map, render, 0);
    }

    public static List<ZoneStateMesh> of(String map, List<Entity> entities) {
        var meshes = new LinkedHashMap<String, ZoneStateMesh>();
        for (var entity : entities) {
            var actor = entity.getSourceActor();
            var render = entity.getActorZoneRender();
            var looks = entity.getZoneLook() == null ? 0 : entity.getZoneLook().looks();
            if (actor == null
                    || render.mask() == 0 && looks == 0
                    || UnrealLoader.DecorationTemplate.isDecorationActor(actor)) {
                continue;
            }
            var seen = meshes.get(actor);
            if (seen == null) {
                meshes.put(actor, new ZoneStateMesh(actor, map, render, looks));
            } else if ((seen.looks() | looks) != seen.looks()) {
                meshes.put(actor, new ZoneStateMesh(actor, map, seen.render(), seen.looks() | looks));
            }
        }
        return List.copyOf(meshes.values());
    }

    public int mask() {
        return render.mask();
    }

    public int zoneStart() {
        return render.zoneStart();
    }

    public boolean shows(MapEventState state) {
        return render.shows(state);
    }
}
