package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoFormat;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.NativeDialogs.Outcome;
import com.rost.cruma.viewer.ui.NativeDialogs.Result;
import imgui.ImGui;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class FolderPicker {
    private final String title;
    private final String hint;
    private final boolean geodata;
    private final boolean scripts;
    private String scriptSummary = "";
    private final ImString location = new ImString(4096);
    private final boolean file;
    private final ImString fileName = new ImString(512);
    private final String defaultName;
    private Path current;
    private List<Path> children = List.of();
    private String error = "";
    private long regions;
    private @Nullable Path nativeChoice;

    private boolean opening;

    public FolderPicker() {
        this(
                "Choose geodata folder",
                "Select the folder containing tile files such as 22_22_conv.dat or 22_22.l2j.",
                true);
    }

    public FolderPicker(String title, String hint, boolean geodata) {
        this(title, hint, geodata, false);
    }

    private FolderPicker(String title, String hint, boolean geodata, boolean scripts) {
        this(title, hint, geodata, scripts, false, "");
    }

    private FolderPicker(
            String title, String hint, boolean geodata, boolean scripts, boolean file, String defaultName) {
        this.title = title;
        this.hint = hint;
        this.geodata = geodata;
        this.scripts = scripts;
        this.file = file;
        this.defaultName = defaultName;
    }

    public static FolderPicker file(String title, String hint, String defaultName) {
        return new FolderPicker(title, hint, false, false, true, defaultName);
    }

    public static FolderPicker scripts() {
        return new FolderPicker(
                "Choose script folder",
                "Select the server script folder with npcpos.txt and npcdata.txt, or a folder that contains it.",
                false,
                true);
    }

    public void openPath(String typed) {
        Path initial = null;
        try {
            initial = typed.isBlank() ? null : Path.of(typed).toAbsolutePath().normalize();
        } catch (RuntimeException invalid) {
        }
        if (file) {
            fileName.set(
                    initial != null && initial.getFileName() != null && !Files.isDirectory(initial)
                            ? initial.getFileName().toString()
                            : defaultName);
            initial = initial == null ? null : Files.isDirectory(initial) ? initial : initial.getParent();
        }
        open(initial != null && Files.isDirectory(initial) ? initial : null);
    }

    public void open(@Nullable Path initial) {
        var system = file ? Result.UNAVAILABLE : NativeDialogs.chooseFolder(title, initial);
        if (system.outcome() == Outcome.CHOSEN) {
            nativeChoice = system.path();
            return;
        }
        if (system.outcome() == Outcome.CANCELLED) {
            return;
        }
        navigate(initial == null ? Path.of(System.getProperty("user.home")) : initial);
        opening = true;
    }

    public @Nullable Path draw() {
        if (nativeChoice != null) {
            var chosen = nativeChoice;
            nativeChoice = null;
            return chosen;
        }
        if (opening) {
            opening = false;
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowSize(Math.min(660, io.getDisplaySizeX() - 32), Math.min(500, io.getDisplaySizeY() - 32));
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        if (!ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            return null;
        }
        Motion.dialog();
        ImGui.textWrapped(hint);
        if (GeodataWidgets.actionField(
                        "folder-path",
                        "Folder path",
                        location,
                        "Type or paste a folder.",
                        Glyph.GO,
                        "Open this folder",
                        true)
                .action()) {
            try {
                navigate(Path.of(location.get()));
            } catch (RuntimeException invalid) {
                error = "Invalid folder path.";
            }
        }
        if (ImGui.button("Home")) {
            navigate(Path.of(System.getProperty("user.home")));
        }
        ImGui.sameLine();
        ImGui.beginDisabled(current == null || current.getParent() == null);
        if (ImGui.button("Up") && current != null) {
            navigate(current.getParent());
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        ImGui.setNextItemWidth(140);
        if (HudWidgets.beginCombo("##folder-drives", "Drives", 0)) {
            for (var root : FileSystems.getDefault().getRootDirectories()) {
                if (ImGui.selectable(root.toString())) {
                    navigate(root);
                }
            }
            if (Files.isDirectory(Path.of("/Volumes")) && ImGui.selectable("/Volumes")) {
                navigate(Path.of("/Volumes"));
            }
            ImGui.endCombo();
        }
        ImGui.beginChild(
                "##folder-list",
                0,
                ImGui.getContentRegionAvailY() - (file ? 140 : 90),
                ImGuiChildFlags.Borders | ImGuiChildFlags.NavFlattened,
                0);
        for (var child : children) {
            if (ImGui.selectable(child.getFileName().toString() + "/")) {
                navigate(child);
                break;
            }
        }
        if (children.isEmpty()) {
            ImGui.textDisabled(current == null ? "Folder unavailable" : "No subfolders");
        }
        ImGui.endChild();
        if (current != null && scripts) {
            ImGui.textWrapped(scriptSummary);
        }
        if (file) {
            ImGui.textColored(HudWidgets.MUTED, "File name");
            ImGui.setNextItemWidth(-1);
            ImGui.inputText("##folder-file-name", fileName);
        }
        if (current != null && geodata) {
            ImGui.textWrapped(
                    regions > 0
                            ? regions + (regions == 1 ? " region file found." : " region files found.")
                            : "No *_conv.dat or *.l2j files in this folder. You can still save it and add data later.");
        }
        ImGui.textWrapped(error);
        Path selected = null;
        ImGui.setCursorPosY(ImGui.getWindowHeight() - 44);
        if (ImGui.button("Cancel", 100, 28) || GeodataWidgets.wasClickedOutside()) {
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        ImGui.beginDisabled(location.get().isBlank() || file && fileName.get().isBlank());
        if (ImGui.button(file ? "Choose" : "Select folder", 140, 28)) {
            selected = select(location.get());
            if (selected != null && file) {
                selected = selected.resolve(fileName.get().strip());
            }
            if (selected != null) {
                ImGui.closeCurrentPopup();
            }
        }
        ImGui.endDisabled();
        ImGui.endPopup();
        return selected;
    }

    @Nullable
    Path select(String input) {
        try {
            navigate(Path.of(input));
        } catch (RuntimeException invalid) {
            current = null;
            children = List.of();
            error = "Invalid folder path.";
        }
        return current;
    }

    private void navigate(Path path) {
        location.set(path.toString());
        try {
            var target = path.toRealPath();
            try (var entries = Files.list(target)) {
                var contents = entries.toList();
                children = contents.stream()
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(
                                child -> child.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                        .toList();
                regions = contents.stream()
                        .filter(file ->
                                GeoFormat.parse(file.getFileName().toString()) != null && Files.isRegularFile(file))
                        .count();
            }
            current = target;
            if (scripts) {
                scriptSummary = scriptSummary(target);
            }
            location.set(target.toString());
            error = "";
        } catch (IOException | RuntimeException failure) {
            current = null;
            regions = 0;
            children = List.of();
            error = "Cannot open this folder. Check the path and permissions.";
        }
    }

    private static String scriptSummary(Path folder) {
        var resolved = SpawnSource.resolveFolder(folder);
        var positions = resolved != null && SpawnSource.find(resolved, SpawnSource.POSITIONS) != null;
        if (!positions) {
            return "No npcpos.txt here or in a script subfolder.";
        }
        var where = resolved.equals(folder) ? "" : " in " + folder.relativize(resolved);
        var npcs = SpawnSource.find(resolved, SpawnSource.NPCS) != null;
        return "npcpos.txt"
                + (npcs ? " and npcdata.txt" : "")
                + " found"
                + where
                + "."
                + (npcs ? "" : " Without npcdata.txt NPC levels and shapes are unknown.");
    }
}
