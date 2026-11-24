package com.rost.cruma.viewer.agent;

import com.rost.cruma.viewer.agent.ToolSpec.Builder;
import com.rost.cruma.viewer.agent.edit.ClientWriteTools;
import com.rost.cruma.viewer.agent.edit.EditTools;
import com.rost.cruma.viewer.agent.effect.EffectTools;
import com.rost.cruma.viewer.agent.export.ExportTools;
import com.rost.cruma.viewer.agent.film.FilmTools;
import com.rost.cruma.viewer.agent.geo.GeoTools;
import com.rost.cruma.viewer.agent.session.FindingsTools;
import com.rost.cruma.viewer.agent.session.GuideTools;
import com.rost.cruma.viewer.agent.session.OptionsTools;
import com.rost.cruma.viewer.agent.session.SessionTools;
import com.rost.cruma.viewer.agent.spawn.AreaTools;
import com.rost.cruma.viewer.agent.spawn.SpawnTools;
import com.rost.cruma.viewer.agent.view.ViewTools;
import com.rost.cruma.viewer.agent.view.WorldTools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class Tools {
    public sealed interface Entry {
        ToolSpec spec();
    }

    public record OnHub(ToolSpec spec, HubTool tool) implements Entry {}

    public record OnViewer(
            ToolSpec spec, ViewerTool tool, @Nullable HubTool alone) implements Entry {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public static Tools all() {
        var tools = new Tools();
        for (var group : List.of(
                new SessionTools(),
                new OptionsTools(),
                new GuideTools(),
                new ViewTools(),
                new FindingsTools(),
                new ExportTools(),
                new GeoTools(),
                new SpawnTools(),
                new AreaTools(),
                new FilmTools(),
                new ClientWriteTools(),
                new EditTools(),
                new WorldTools(),
                new EffectTools())) {
            group.register(tools);
        }
        return tools;
    }

    public void hub(ToolSpec spec, HubTool tool) {
        add(new OnHub(spec, tool));
    }

    public void viewer(Builder spec, ViewerTool tool) {
        viewer(spec, tool, null);
    }

    public void viewer(Builder spec, ViewerTool tool, @Nullable HubTool alone) {
        add(new OnViewer(
                spec.text("viewer", "The viewer id from cruma_status. Without it, the viewer used last.")
                        .build(),
                tool,
                alone));
    }

    public @Nullable Entry find(String name) {
        return entries.get(name);
    }

    public List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    private void add(Entry entry) {
        if (entries.putIfAbsent(entry.spec().name(), entry) != null) {
            throw new IllegalArgumentException(
                    "Two tools are named " + entry.spec().name());
        }
    }
}
