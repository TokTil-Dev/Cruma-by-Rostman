package com.rost.cruma.viewer.ui;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class ToolWindows {
    public static final float RAIL_WIDTH = 44f;
    public static final float BUTTON_HEIGHT = 36f;
    public static final float BUTTON_STEP = 44f;
    public static final float ICON_SIZE = 22f;
    public static final float RAIL_PADDING = 8f;
    private Tool active;
    private final Set<Tool> absent = EnumSet.of(Tool.SCENES, Tool.AGENT);
    private final Set<Tool> hidden = EnumSet.noneOf(Tool.class);

    public enum Tool {
        SCENE,
        OBJECT,
        PERFORMANCE,
        GEODATA,
        SPAWNS,
        AREAS,
        NPCS,
        EVENTS,
        EFFECTS,
        SCENES,
        AGENT
    }

    public void remove(Tool... tools) {
        absent.addAll(List.of(tools));
        closeWhenGone();
    }

    public void add(Tool tool) {
        absent.remove(tool);
    }

    public void hide(Tool... tools) {
        hidden.addAll(List.of(tools));
        closeWhenGone();
    }

    public void show(Tool... tools) {
        List.of(tools).forEach(hidden::remove);
    }

    public List<Tool> getShown() {
        return Arrays.stream(Tool.values()).filter(this::onRail).toList();
    }

    public boolean isHidden(Tool tool) {
        return hidden.contains(tool) && !absent.contains(tool);
    }

    public boolean isOpen(Tool tool) {
        return active == tool;
    }

    public boolean isOpen() {
        return active != null;
    }

    public void open(Tool tool) {
        if (onRail(tool)) {
            active = tool;
        }
    }

    public void toggle(Tool tool) {
        if (onRail(tool)) {
            active = active == tool ? null : tool;
        }
    }

    public void close() {
        active = null;
    }

    private boolean onRail(Tool tool) {
        return !absent.contains(tool) && !hidden.contains(tool);
    }

    private void closeWhenGone() {
        if (active != null && !onRail(active)) {
            active = null;
        }
    }
}
