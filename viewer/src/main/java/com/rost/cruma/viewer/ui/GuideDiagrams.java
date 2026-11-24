package com.rost.cruma.viewer.ui;

import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImGui;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class GuideDiagrams {
    static final float LINE_GAP = 8;

    private static final Map<String, GuideDiagram> ALL = build();
    private static @Nullable FontManager fonts;

    private GuideDiagrams() {}

    public static Map<String, GuideDiagram> getAll() {
        return ALL;
    }

    public static @Nullable GuideDiagram get(String id) {
        return ALL.get(id);
    }

    public static void useFonts(FontManager fonts) {
        GuideDiagrams.fonts = fonts;
    }

    public static void forgetFonts(FontManager fonts) {
        if (GuideDiagrams.fonts == fonts) {
            GuideDiagrams.fonts = null;
        }
    }

    public static void label(ImDrawList draw, float x, float y, int size, int color, String text) {
        draw.addText(font(size), size, Math.round(x), Math.round(y), color, text);
    }

    public static void centered(ImDrawList draw, float x, float y, int size, int color, String text) {
        label(draw, x - labelWidth(size, text) * .5f, y, size, color, text);
    }

    public static void rightAligned(ImDrawList draw, float x, float y, int size, int color, String text) {
        label(draw, x - labelWidth(size, text), y, size, color, text);
    }

    public static float labelWidth(int size, String text) {
        return font(size).calcTextSizeA(size, Float.MAX_VALUE, 0, text).x;
    }

    private static Map<String, GuideDiagram> build() {
        var diagrams = new LinkedHashMap<String, GuideDiagram>();
        GeodataDiagrams.addTo(diagrams);
        StartDiagrams.addTo(diagrams);
        ViewDiagrams.addTo(diagrams);
        ExportDiagrams.addTo(diagrams);
        SpawnDiagrams.addTo(diagrams);
        AreaDiagrams.addTo(diagrams);
        NpcDiagrams.addTo(diagrams);
        EventDiagrams.addTo(diagrams);
        EffectDiagrams.addTo(diagrams);
        EditDiagrams.addTo(diagrams);
        SceneDiagrams.addTo(diagrams);
        AgentDiagrams.addTo(diagrams);
        return Collections.unmodifiableMap(diagrams);
    }

    private static ImFont font(int size) {
        var manager = fonts;
        var font = manager == null ? null : manager.getTextFont(size);
        return font != null ? font : ImGui.getFont();
    }
}
