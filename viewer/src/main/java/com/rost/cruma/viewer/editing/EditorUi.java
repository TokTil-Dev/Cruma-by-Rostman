package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiHoveredFlags;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

public final class EditorUi {
    public static final int ERROR = GeodataWidgets.BLOCKED;
    public static final int WARNING = GeodataWidgets.WARNING;
    public static final int SUCCESS = GeodataWidgets.OPEN;
    public static final float BUTTON_HEIGHT = HudWidgets.CONTROL_HEIGHT;

    public static final String MOVERS_CANNOT_BE_EDITED = "Doors and other movers cannot be edited.";

    public static final String EFFECTS_CANNOT_BE_EDITED = "Effects cannot be edited.";

    public static final String EFFECT_PREFAB = "A prefab keeps meshes, and an effect has none.";

    public static final String CANNOT_BE_EDITED = "This object cannot be edited.";

    private static final Set<String> EDITABLE_CLASSES = Set.of(
            "StaticMeshActor",
            "MovableStaticMeshActor",
            "L2MovableStaticMeshActor",
            "Light",
            "Spotlight",
            "LineagePawn",
            "Emitter");

    private EditorUi() {}

    public enum Tone {
        INFO(HudWidgets.MUTED),
        SUCCESS(EditorUi.SUCCESS),
        WARNING(EditorUi.WARNING),
        ERROR(EditorUi.ERROR);

        final int color;

        Tone(int color) {
            this.color = color;
        }
    }

    public static void tip(String text) {
        if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled | ImGuiHoveredFlags.DelayShort)) {
            HudWidgets.showTooltip(text);
        }
    }

    public static void section(String title) {
        GeodataWidgets.section(title.toUpperCase(Locale.ROOT));
    }

    public static void firstSection(String title) {
        GeodataWidgets.section(title.toUpperCase(Locale.ROOT), GeodataWidgets.ROW);
    }

    public static void colored(int color, String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(text);
        ImGui.popStyleColor();
    }

    public static void message(Tone tone, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        GeodataWidgets.note(text, tone.color);
    }

    public static void muted(String text) {
        GeodataWidgets.hint(text);
    }

    public static boolean primaryButton(String label, float width) {
        return HudWidgets.button(label, null, visibleLabel(label), width, BUTTON_HEIGHT, true, true);
    }

    public static boolean dangerButton(String label, float width) {
        ImGui.pushStyleColor(ImGuiCol.Button, fill(GeodataWidgets.BLOCKED, .16f));
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, fill(GeodataWidgets.BLOCKED, .26f));
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, fill(GeodataWidgets.BLOCKED, .34f));
        var pressed = ImGui.button(label, width, BUTTON_HEIGHT);
        ImGui.popStyleColor(3);
        return pressed;
    }

    public static float segmentWidth(int count) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        return (ImGui.getContentRegionAvailX() - spacing * (count - 1)) / count;
    }

    static int fill(int color, float alpha) {
        return color & 0x00FFFFFF | Math.round(alpha * 255) << 24;
    }

    public static String plural(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    public static String meters(float units) {
        var meters = Math.abs(units) * .04f;
        var tenths = Math.round(meters * 10);
        var text = tenths < 100
                ? String.format(Locale.ROOT, "%.1f m", tenths / 10f)
                : String.format(Locale.ROOT, "%,d m", Math.round(meters));
        return units < 0 && tenths > 0 ? "-" + text : text;
    }

    public static String metersXyz(float x, float y, float z) {
        return "X " + meters(x) + " · Y " + meters(y) + " · Z " + meters(z);
    }

    public static String unitsAndMeters(float units) {
        return String.format(Locale.ROOT, "%,.0f units · %s", units, meters(units));
    }

    public static String[] measure(float dx, float dy, float dz) {
        var length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        var across = (float) Math.hypot(dx, dy);
        var rise = Math.round(dz);
        var parts =
                String.format(Locale.ROOT, "%,.0f across · %,d %s", across, Math.abs(rise), rise < 0 ? "down" : "up");
        return new String[] {unitsAndMeters(length), parts};
    }

    public static String compact(float value) {
        if (!Float.isFinite(value)) {
            return RelativeNumber.format(value);
        }
        var whole = Math.round((double) value);
        if (Math.abs(value - whole) <= .005) {
            return whole == 0 ? "0" : Long.toString(whole);
        }
        var text = String.format(Locale.ROOT, "%.2f", value);
        text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text.equals("-0") ? "0" : text;
    }

    public static String rowDetail(String name, float room, ToDoubleFunction<String> width) {
        if (name.isEmpty()) {
            return "";
        }
        var whole = " · " + name;
        if (width.applyAsDouble(whole) <= room) {
            return whole;
        }
        var length = name.length() - 1;
        while (length >= 3 && width.applyAsDouble(" · " + name.substring(0, length) + "…") > room) {
            length--;
        }
        return length < 3 ? "" : " · " + name.substring(0, length) + "…";
    }

    public static @Nullable String uneditable(String actorPath) {
        var type = classOf(actorPath);
        if (EDITABLE_CLASSES.contains(type)) {
            return null;
        }
        var lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("mover") || lower.contains("door")) {
            return MOVERS_CANNOT_BE_EDITED;
        }
        return lower.endsWith("emitter") ? EFFECTS_CANNOT_BE_EDITED : CANNOT_BE_EDITED;
    }

    public static String cannotEdit(String actorPath) {
        var reason = uneditable(actorPath);
        return reason == null ? CANNOT_BE_EDITED : reason;
    }

    public static boolean isPawn(String actorPath) {
        return classOf(actorPath).equals(PackageEditDocument.PAWN);
    }

    private static String classOf(String actorPath) {
        var name = actorPath.substring(actorPath.lastIndexOf('.') + 1);
        var end = name.length();
        while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
            end--;
        }
        var type = name.substring(0, end);
        return type.startsWith("Cruma") ? type.substring("Cruma".length()) : type;
    }

    private static String visibleLabel(String label) {
        var id = label.indexOf("##");
        return id < 0 ? label : label.substring(0, id);
    }
}
