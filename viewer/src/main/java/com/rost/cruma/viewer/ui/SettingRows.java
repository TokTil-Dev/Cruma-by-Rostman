package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.config.AgentSettings;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.config.InterfaceScale;
import com.rost.cruma.viewer.config.OptionsModel;
import com.rost.cruma.viewer.config.UserPaths;
import com.rost.cruma.viewer.render.bgfx.BgfxRendererOptions;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValueType;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiComboFlags;
import imgui.type.ImString;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class SettingRows {
    static final String INTERFACE_SCALE = "interface.scale";
    static final String CAMERA_START = "viewer.camera.start";
    static final String LOCATIONS = "viewer.camera.locations";
    static final String CAPTURE_FOLDER = "capture.output.directory";
    static final String RADAR_FILE = "capture.output.radar.file";

    private static final float CONTROL = 160;
    private static final float TEXT_FIELD = 220;

    private final OptionsModel model;
    private final Path configDirectory;
    private final List<String> renderers;
    private final Map<String, ImString> text;
    private final Map<String, String> invalid;
    private final Map<String, float[]> places;
    private final Runnable changed;
    private final FolderPicker geodataFolder = new FolderPicker();
    private final FolderPicker scriptFolder = FolderPicker.scripts();
    private final FolderPicker screenshotPicker = new FolderPicker(
            "Choose screenshot folder", "Select the folder for F12 screenshots. Each one is a new file in it.", false);
    private final FolderPicker exportPicker = new FolderPicker(
            "Choose export folder", "Select the folder that Export location and Export NPC start in.", false);
    private final FolderPicker capturePicker = new FolderPicker(
            "Choose output folder", "Select the folder that Capture map images writes its images into.", false);
    private final FolderPicker scriptsPicker = new FolderPicker(
            "Choose script folder",
            "Select the folder that the scripts of effects and skills go into. Each package gets a folder in it.",
            false);
    private final FolderPicker folderPicker =
            new FolderPicker("Choose folder", "Select the folder for this setting.", false);
    private String folderPickerPath = "";
    private int automaticScalePercent = 100;

    SettingRows(
            OptionsModel model,
            Path configDirectory,
            Map<String, ImString> text,
            Map<String, String> invalid,
            Map<String, float[]> places,
            Runnable changed) {
        this.model = model;
        this.configDirectory = configDirectory;
        this.text = text;
        this.invalid = invalid;
        this.places = places;
        this.changed = changed;
        renderers = BgfxRendererOptions.available();
    }

    static void choose(OptionsModel model, String path, String option) {
        if (path.equals(INTERFACE_SCALE)) {
            var scale = InterfaceScale.parse(option);
            model.set(path, scale.isAutomatic() ? scale.key() : Integer.valueOf(scale.percent()));
            return;
        }
        model.set(path, option);
        if (path.endsWith("optimizations.profile") && !option.equals("custom")) {
            String prefix = path.substring(0, path.lastIndexOf('.') + 1);
            for (String feature : List.of("fill_reflection", "gpu_visibility", "indirect_submission")) {
                model.set(prefix + feature, option.equals("optimized"));
            }
            model.set(prefix + "parallel_encoders", false);
        }
    }

    static String choiceKey(String value) {
        String normalized = value.strip().replace('-', '_').toLowerCase(Locale.ROOT);
        return normalized.equals("jpeg") ? "jpg" : normalized;
    }

    List<String> getRenderers() {
        return renderers;
    }

    void setAutomaticScalePercent(int percent) {
        automaticScalePercent = percent;
    }

    InterfaceScale getInterfaceScale() {
        try {
            return InterfaceScale.parse(
                    model.getValue(INTERFACE_SCALE).unwrapped().toString());
        } catch (IllegalArgumentException invalidScale) {
            return InterfaceScale.AUTOMATIC;
        }
    }

    void drawRow(String path) {
        var info = LaunchOptionCatalog.presentation(path);
        var value = model.getValue(path);
        var inactive = LaunchOptionCatalog.inactiveReason(path, model);
        var tooltip = (inactive.isEmpty() ? "" : inactive + "\n\n") + describe(path) + "\n\n" + path;
        ImGui.pushID(path);
        var top = ImGui.getCursorScreenPosY();
        if (value.valueType() == ConfigValueType.BOOLEAN) {
            var on = Boolean.TRUE.equals(value.unwrapped());
            var next = GeodataWidgets.settingSwitchRow(path, info.label(), "", on, inactive.isEmpty(), tooltip);
            if (next != on) {
                setBoolean(path, next);
            }
        } else if (isWide(path)) {
            drawWideField(path, info.label(), inactive, tooltip);
        } else {
            ImGui.beginDisabled(!inactive.isEmpty());
            var hovered = drawControlRow(path, info.label(), value.valueType());
            ImGui.endDisabled();
            if (hovered) {
                HudWidgets.showTooltip(tooltip);
            }
        }
        markRow("row/" + path, top);
        drawInvalid(path);
        ImGui.popID();
    }

    void drawPickers() {
        setPicked(ClientProfiles.GEODATA_PATH, geodataFolder.draw());
        setPicked(ClientProfiles.SCRIPT_PATH, scriptFolder.draw());
        setPicked(OptionsModel.SCREENSHOT_FOLDER, screenshotPicker.draw());
        setPicked(ExportSettings.FOLDER, exportPicker.draw());
        setPicked(ExportSettings.SCRIPTS_FOLDER, scriptsPicker.draw());
        setPicked(CAPTURE_FOLDER, capturePicker.draw());
        setPicked(folderPickerPath, folderPicker.draw());
    }

    void openPicker(String path, String typed) {
        var picker = path.equals(ClientProfiles.GEODATA_PATH)
                ? geodataFolder
                : path.equals(ClientProfiles.SCRIPT_PATH) ? scriptFolder : folderPicker;
        folderPickerPath = path;
        try {
            picker.open(typed.isBlank() ? null : existingFolder(UserPaths.resolve(typed, configDirectory)));
        } catch (RuntimeException invalidPath) {
            picker.open(null);
        }
    }

    ImString getText(String path) {
        return text.computeIfAbsent(
                path, ignored -> new ImString(model.getValue(path).unwrapped().toString(), 4096));
    }

    void setText(String path, String value) {
        model.set(path, value);
        invalid.remove(path);
        changed.run();
    }

    void setFolder(String path, Path folder) {
        getText(path).set(folder.toString());
        setText(path, folder.toString());
    }

    void drawInvalid(String path) {
        if (invalid.containsKey(path)) {
            GeodataWidgets.note(invalid.get(path), GeodataWidgets.BLOCKED);
        }
    }

    String describe(String path) {
        String description = LaunchOptionCatalog.presentation(path).description();
        if (path.endsWith(".renderer")
                && !renderers.contains(model.getValue(path).unwrapped().toString())) {
            return "The saved backend is unavailable in this installation. Choose Automatic or a supported backend.\n\n"
                    + description;
        }
        return description;
    }

    void mark(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
    }

    void markRow(String name, float top) {
        var x = ImGui.getCursorScreenPosX();
        places.put(name, new float[] {x, top, x + ImGui.getContentRegionAvailX(), ImGui.getCursorScreenPosY()});
    }

    private boolean drawControlRow(String path, String label, ConfigValueType type) {
        if (path.equals(ClientProfiles.VARIANT)
                && model.getSelectedClientWorlds().size() < 2) {
            var note = "Main, this client has no other world";
            GeodataWidgets.beginSettingRow(label, "", ImGui.calcTextSizeX(note));
            var x = ImGui.getCursorScreenPosX();
            var y = ImGui.getCursorScreenPosY();
            ImGui.getWindowDrawList()
                    .addText(
                            x,
                            y + (HudWidgets.CONTROL_HEIGHT - ImGui.getFontSize()) * .5f - 1,
                            ImGui.getColorU32i(GeodataWidgets.MUTED),
                            note);
            return GeodataWidgets.endSettingRow();
        }
        var choices = choices(path);
        if (choices != null && LaunchOptionCatalog.isSegmented(path)) {
            return drawSegmentedRow(path, label, choices);
        }
        if (choices != null) {
            return drawChoiceRow(path, label, choices);
        }
        return drawInputRow(path, label, type);
    }

    private boolean drawSegmentedRow(String path, String label, List<String> choices) {
        var current = choiceKey(model.getValue(path).unwrapped().toString());
        var labels = new String[choices.size()];
        for (var index = 0; index < labels.length; index++) {
            labels[index] = choiceLabel(path, choices.get(index));
        }
        GeodataWidgets.beginSettingRow(label, "", GeodataWidgets.fittedSegmentedWidth(labels));
        ImGui.setCursorScreenPos(ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY() + 1);
        var picked = GeodataWidgets.fittedSegmented(path, labels, choices.indexOf(current));
        mark("field/" + path);
        if (picked >= 0 && !choices.get(picked).equals(current)) {
            pick(path, choices.get(picked));
        }
        var hovered = GeodataWidgets.endSettingRow();
        if (path.endsWith(".castle_sides") && !choices.contains(current)) {
            GeodataWidgets.note("Mixed: the Map events tool gave the castles different sides.", GeodataWidgets.MUTED);
        }
        return hovered;
    }

    private boolean drawChoiceRow(String path, String label, List<String> choices) {
        var value = model.getValue(path).unwrapped().toString();
        var current = path.equals(INTERFACE_SCALE) ? getInterfaceScale().key() : choiceKey(value);
        var preview = choiceLabel(path, current)
                + (path.endsWith(".renderer") && !renderers.contains(current) ? " (unavailable)" : "");
        var room = CONTROL - 2 * ImGui.getStyle().getFramePaddingX() - ImGui.getFrameHeight();
        GeodataWidgets.beginSettingRow(label, "", CONTROL);
        ImGui.setNextItemWidth(CONTROL);
        if (HudWidgets.beginCombo(
                "##value", HudWidgets.truncateToWidth(preview, room), ImGuiComboFlags.HeightRegular)) {
            for (var option : choices) {
                if (ImGui.selectable(choiceLabel(path, option), current.equals(option))) {
                    pick(path, option);
                }
            }
            ImGui.endCombo();
        }
        mark("field/" + path);
        return GeodataWidgets.endSettingRow();
    }

    private void pick(String path, String option) {
        choose(model, path, option);
        if (path.equals("capture.output.radar.format")) {
            text.remove(RADAR_FILE);
        }
        changed.run();
    }

    private boolean drawInputRow(String path, String label, ConfigValueType type) {
        var number = type == ConfigValueType.NUMBER;
        var width = number ? CONTROL : TEXT_FIELD;
        GeodataWidgets.beginSettingRow(label, "", width);
        var input = getText(path);
        ImGui.setNextItemWidth(width);
        if (ImGui.inputText("##value", input)) {
            try {
                if (number) {
                    var parsed =
                            ConfigFactory.parseString("value=" + input.get()).getValue("value");
                    if (parsed.valueType() != ConfigValueType.NUMBER) {
                        throw new IllegalArgumentException();
                    }
                    model.setValue(path, parsed);
                } else {
                    model.set(path, input.get());
                }
                invalid.remove(path);
                changed.run();
            } catch (RuntimeException failure) {
                invalid.put(path, "Enter a number.");
            }
        }
        mark("field/" + path);
        return GeodataWidgets.endSettingRow();
    }

    private void drawWideField(String path, String label, String inactive, String tooltip) {
        ImGui.beginDisabled(!inactive.isEmpty());
        ImGui.alignTextToFramePadding();
        ImGui.pushStyleColor(ImGuiCol.Text, HudWidgets.TEXT);
        ImGui.textUnformatted(label);
        ImGui.popStyleColor();
        var labelHovered = ImGui.isItemHovered();
        if (path.equals(CAMERA_START)) {
            drawCameraStart(path);
        } else if (path.equals(OptionsModel.SCREENSHOT_FOLDER)
                || path.equals(ExportSettings.FOLDER)
                || path.equals(ExportSettings.SCRIPTS_FOLDER)
                || path.equals(CAPTURE_FOLDER)) {
            drawFolderSetting(path);
        } else {
            var input = getText(path);
            var before = input.get();
            markField(path);
            var field =
                    GeodataWidgets.folderInput(path, "Folder", input, describe(path), ImGui.getContentRegionAvailX());
            if (!input.get().equals(before)) {
                setText(path, input.get());
            }
            if (field.action()) {
                openPicker(path, input.get());
            }
        }
        ImGui.endDisabled();
        if (labelHovered) {
            HudWidgets.showTooltip(tooltip);
        }
    }

    private void drawFolderSetting(String path) {
        var input = getText(path);
        var before = input.get();
        markField(path);
        var left = ImGui.getCursorScreenPosX();
        var top = ImGui.getCursorScreenPosY();
        var field = GeodataWidgets.folderInput(
                path,
                path.equals(CAPTURE_FOLDER) ? "Folder" : "Default folder",
                input,
                describe(path),
                ImGui.getContentRegionAvailX());
        if (!input.get().equals(before)) {
            setText(path, input.get());
        }
        ImGui.setCursorScreenPos(left, top + ImGui.getFrameHeight() + 4);
        var blank = input.get().isBlank();
        try {
            var folder = switch (path) {
                case ExportSettings.FOLDER -> model.getExportFolder();
                case ExportSettings.SCRIPTS_FOLDER -> model.getScriptsFolder();
                case CAPTURE_FOLDER -> model.getCaptureFolder();
                default -> model.getScreenshotFolder();
            };
            if (field.action()) {
                var picker = switch (path) {
                    case ExportSettings.FOLDER -> exportPicker;
                    case ExportSettings.SCRIPTS_FOLDER -> scriptsPicker;
                    case CAPTURE_FOLDER -> capturePicker;
                    default -> screenshotPicker;
                };
                picker.open(existingFolder(folder));
            }
            var caption = (blank ? "Default: " : "Saved in ") + folder;
            ImGui.textColored(
                    GeodataWidgets.FAINT, HudWidgets.truncateToWidth(caption, ImGui.getContentRegionAvailX()));
            GeodataWidgets.tip(folder.toString());
        } catch (RuntimeException invalidPath) {
            ImGui.textColored(GeodataWidgets.FAINT, "Not a valid folder");
        }
    }

    private void drawCameraStart(String path) {
        var input = getText(path);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX() - HudWidgets.CONTROL_HEIGHT - GeodataWidgets.ROW);
        if (ImGui.inputText("##value", input)) {
            model.set(path, input.get());
            changed.run();
        }
        mark("field/" + path);
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.setNextItemWidth(HudWidgets.CONTROL_HEIGHT);
        if (HudWidgets.beginCombo("##places", "", ImGuiComboFlags.NoPreview | ImGuiComboFlags.HeightRegular)) {
            for (var item : (ConfigList) model.getValue(LOCATIONS)) {
                var name = ((ConfigObject) item).toConfig().getString("name");
                if (ImGui.selectable(name)) {
                    input.set(name);
                    model.set(path, name);
                    changed.run();
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("Pick a named location.");
    }

    private void setPicked(String path, @Nullable Path chosen) {
        if (chosen != null && !path.isEmpty()) {
            setFolder(path, chosen);
        }
    }

    private void setBoolean(String path, boolean on) {
        model.set(path, on);
        if (path.contains(".optimizations.")) {
            var prefix = path.substring(0, path.lastIndexOf('.') + 1);
            model.set(prefix + "profile", "custom");
            if (path.endsWith("gpu_visibility") && !on) {
                model.set(prefix + "indirect_submission", false);
            }
            if (path.endsWith("indirect_submission") && on) {
                model.set(prefix + "gpu_visibility", true);
            }
        }
        changed.run();
    }

    private void markField(String path) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        places.put("field/" + path, new float[] {x, y, x + ImGui.getContentRegionAvailX(), y + ImGui.getFrameHeight()});
    }

    private String choiceLabel(String path, String value) {
        if (path.equals(INTERFACE_SCALE)) {
            return value.equals("auto") ? "Automatic (" + automaticScalePercent + "%)" : value + "%";
        }
        if (path.equals(ClientProfiles.VARIANT)) {
            return WorldVariant.parse(value).getLabel();
        }
        return choiceLabel(value);
    }

    private @Nullable List<String> choices(String path) {
        if (path.equals(ClientProfiles.VARIANT)) {
            var keys = new ArrayList<String>();
            for (var world : model.getSelectedClientWorlds()) {
                keys.add(world.getKey());
            }
            return keys;
        }
        if (path.endsWith("geodata.focus_mode")) {
            return List.of("camera", "mouse");
        }
        if (path.endsWith("geodata.display.mode")) {
            return List.of("exact_grid", "contours", "surface");
        }
        if (path.equals(INTERFACE_SCALE)) {
            var scales = new ArrayList<String>();
            scales.add("auto");
            InterfaceScale.PRESETS.forEach(percent -> scales.add(percent.toString()));
            var current = getInterfaceScale().key();
            if (!scales.contains(current)) {
                scales.add(current);
            }
            return scales;
        }
        if (path.endsWith(".renderer")) {
            return renderers;
        }
        if (path.endsWith(".algorithm")) {
            return List.of("native", "bilinear", "fsr1");
        }
        if (path.endsWith("upscaler.preset")) {
            return List.of("native", "ultra_quality", "quality", "balanced", "performance", "custom");
        }
        if (path.endsWith("optimizations.profile")) {
            return List.of("parity", "optimized", "custom");
        }
        if (path.endsWith(".emitter_distance_mode")) {
            return List.of("authored", "custom");
        }
        if (path.endsWith(".time_windows")) {
            return List.of("clock", "always", "off");
        }
        if (path.endsWith(".castle_sides")) {
            return List.of("both", "light", "dark", "none");
        }
        if (path.equals(ExportSettings.SCENE_FORMAT)) {
            return List.of("blender", "mp4");
        }
        if (path.equals(AgentSettings.ACCESS)) {
            return List.of(Access.SEE.getWord(), Access.EDIT.getWord(), Access.WRITE.getWord());
        }
        if (path.equals(ExportSettings.VIDEO_SIZE)) {
            return List.of("1280x720", "1920x1080", "2560x1440", "3840x2160");
        }
        if (path.equals("capture.output.format") || path.equals("capture.output.radar.format")) {
            return List.of("png", "jpg");
        }
        return null;
    }

    private static boolean isWide(String path) {
        return (LaunchOptionCatalog.isFolder(path) && !path.equals(RADAR_FILE)) || path.equals(CAMERA_START);
    }

    private static @Nullable Path existingFolder(Path folder) {
        var existing = folder;
        while (existing != null && !Files.isDirectory(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    private static String choiceLabel(String value) {
        return switch (value) {
            case "auto" -> "Automatic";
            case "optimized" -> "Optimized";
            case "parity" -> "Compatibility";
            case "fsr1" -> "FSR 1";
            case "direct3d11" -> "Direct3D 11";
            case "direct3d12" -> "Direct3D 12";
            case "ultra_quality" -> "Ultra quality";
            case "png" -> "PNG";
            case "jpg", "jpeg" -> "JPEG";
            case "mp4" -> "MP4";
            case "authored" -> "From client data";
            case "exact_grid" -> "Exact cell grid";
            case "contours" -> "Contours";
            case "surface" -> "Merged surfaces";
            case "clock" -> "By clock";
            default -> Character.toUpperCase(value.charAt(0)) + value.substring(1);
        };
    }
}
