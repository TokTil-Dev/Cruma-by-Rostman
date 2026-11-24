package com.rost.cruma.viewer.guide;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class GuideTopics {
    public static final Map<String, List<String>> TABS = tabs();

    public static final Map<String, String> TOPICS = topics();

    private GuideTopics() {}

    public static @Nullable String topic(String tool, @Nullable String tab) {
        if (tab != null) {
            var forTab = TOPICS.get(tool + "/" + tab);
            if (forTab != null) {
                return forTab;
            }
        }
        return TOPICS.get(tool);
    }

    public static boolean isTool(String tool) {
        return TABS.containsKey(tool);
    }

    public static boolean isTab(String tool, String tab) {
        return TABS.getOrDefault(tool, List.of()).contains(tab);
    }

    private static Map<String, List<String>> tabs() {
        var tabs = new LinkedHashMap<String, List<String>>();
        tabs.put("scene", List.of());
        tabs.put("object", List.of());
        tabs.put("performance", List.of());
        tabs.put("geodata", List.of("inspect", "route", "edit", "check"));
        tabs.put("spawns", List.of());
        tabs.put("areas", List.of());
        tabs.put("npcs", List.of());
        tabs.put("events", List.of());
        tabs.put("effects", List.of("skills"));
        tabs.put("scenes", List.of("director"));
        tabs.put("agent", List.of());
        tabs.put("edit", List.of("select", "place", "sculpt", "paint", "lights", "surfaces", "save"));
        tabs.put("director", List.of());
        tabs.put("export-location", List.of());
        tabs.put("export-npc", List.of());
        tabs.put("tile-operations", List.of());
        tabs.put("world-map", List.of());
        return Map.copyOf(tabs);
    }

    private static Map<String, String> topics() {
        var topics = new LinkedHashMap<String, String>();
        topics.put("geodata", "geodata/what-is-geodata");
        topics.put("geodata/inspect", "geodata/read-the-overlay");
        topics.put("geodata/route", "geodata/check-a-route");
        topics.put("geodata/edit", "geodata/edit-cells");
        topics.put("geodata/check", "geodata/find-problems");
        topics.put("scene", "view/scene-settings");
        topics.put("object", "view/inspect-an-object");
        topics.put("performance", "view/read-performance");
        topics.put("export-location", "export/export-a-location");
        topics.put("tile-operations", "export/tile-operations");
        topics.put("spawns", "spawns/what-is-a-spawn");
        topics.put("areas", "areas/what-is-an-area");
        topics.put("npcs", "npcs/what-is-npc-data");
        topics.put("export-npc", "npcs/export-an-npc");
        topics.put("events", "events/what-are-map-events");
        topics.put("effects", "effects/what-are-effects");
        topics.put("effects/skills", "effects/skill-casts");
        topics.put("edit", "edit/what-edit-changes");
        topics.put("edit/select", "edit/select-and-move");
        topics.put("edit/place", "edit/place-from-the-library");
        topics.put("edit/sculpt", "edit/sculpt-the-ground");
        topics.put("edit/paint", "edit/paint-the-ground");
        topics.put("edit/lights", "edit/lights");
        topics.put("edit/surfaces", "edit/surfaces-and-materials");
        topics.put("edit/save", "edit/save-and-check");
        topics.put("scenes", "scenes/what-is-a-cut-scene");
        topics.put("scenes/director", "scenes/video-director");
        topics.put("director", "scenes/video-director");
        topics.put("agent", "agent/what-agents-do");
        return Map.copyOf(topics);
    }
}
