package com.rost.cruma.viewer.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class GuideActions {
    private static final Map<String, GuideAction> ALL = build();

    private GuideActions() {}

    public static Map<String, GuideAction> getAll() {
        return ALL;
    }

    public static void resetAll() {
        for (var action : ALL.values()) {
            action.reset();
        }
    }

    public static @Nullable GuideAction get(String id) {
        return ALL.get(id);
    }

    private static Map<String, GuideAction> build() {
        var actions = new LinkedHashMap<String, GuideAction>();
        GeodataGuideActions.addTo(actions);
        WorldGuideActions.addStartTo(actions);
        WorldGuideActions.addViewTo(actions);
        WorldGuideActions.addExportTo(actions);
        SpawnGuideActions.addTo(actions);
        AreaGuideActions.addTo(actions);
        NpcGuideActions.addTo(actions);
        EventGuideActions.addTo(actions);
        EffectGuideActions.addTo(actions);
        EditGuideActions.addTo(actions);
        SceneGuideActions.addTo(actions);
        AgentGuideActions.addTo(actions);
        return Collections.unmodifiableMap(actions);
    }
}
