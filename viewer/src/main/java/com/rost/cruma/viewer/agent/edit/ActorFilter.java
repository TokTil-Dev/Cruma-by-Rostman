package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolProblem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

record ActorFilter(
        @Nullable String mesh,
        @Nullable String name,
        @Nullable String className,
        @Nullable Target place,
        float radius) {
    static @Nullable ActorFilter read(@Nullable JSONObject where, Locator locator, float defaultRadius)
            throws ToolProblem {
        if (where == null) {
            return null;
        }
        var near = where.getString("near");
        var place = near == null || near.isBlank() ? null : locator.locate(near);
        var filter = new ActorFilter(
                words(where, "mesh"), words(where, "name"), words(where, "class"), place, radius(where, defaultRadius));
        if (filter.mesh == null && filter.name == null && filter.className == null && place == null) {
            throw new ToolProblem("where is empty, and that would mean every actor. Give mesh, name, class or near.");
        }
        return filter;
    }

    @FunctionalInterface
    interface Locator {
        Target locate(String text) throws ToolProblem;
    }

    boolean matches(ActorRecord actor) {
        if (mesh != null && !lower(actor.meshSourcePath()).contains(mesh)) {
            return false;
        }
        if (name != null && !lower(actor.sourcePath()).contains(name)) {
            return false;
        }
        if (className != null && !lower(actor.className()).contains(className)) {
            return false;
        }
        if (place == null) {
            return true;
        }
        var location = actor.transform().location();
        return Math.hypot(location.x() - place.x(), location.y() - place.y()) <= radius;
    }

    List<String> maps(Collection<String> loaded) {
        if (place == null) {
            return List.copyOf(loaded);
        }
        var low = WorldTile.at(place.x() - radius, place.y() - radius);
        var high = WorldTile.at(place.x() + radius, place.y() + radius);
        var result = new ArrayList<String>();
        for (var map : loaded) {
            if (WorldTile.isMapName(map)) {
                var tile = WorldTile.fromMapName(map);
                if (tile.x() >= low.x() && tile.x() <= high.x() && tile.y() >= low.y() && tile.y() <= high.y()) {
                    result.add(map);
                }
            }
        }
        return result;
    }

    private static @Nullable String words(JSONObject where, String key) {
        var text = where.getString(key);
        return text == null || text.isBlank() ? null : text.strip().toLowerCase(Locale.ROOT);
    }

    private static float radius(JSONObject where, float fallback) throws ToolProblem {
        var value = where.getFloat("radius");
        if (value == null) {
            return fallback;
        }
        if (!(value > 0)) {
            throw new ToolProblem("radius must be above 0.");
        }
        return value;
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
