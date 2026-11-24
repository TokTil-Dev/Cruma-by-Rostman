package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.editing.ClassSource.Source;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jetbrains.annotations.Nullable;

final class ClassSourceDialog {
    private static final String POPUP = "##class-source";
    private static final float WIDTH = 880;
    private static final float BODY_HEIGHT = 560;
    private static final float FOLDER_FIELD = 50;

    private final FolderPicker folderPicker = new FolderPicker(
            "Choose script folder",
            "Select the folder that gets the scripts. Each package gets a folder in it.",
            false);
    private @Nullable ExportSettings settings;
    private @Nullable Path chosenFolder;
    private boolean opening;
    private boolean shown;
    private String title = "";
    private boolean edited;
    private @Nullable CompletableFuture<Source> reading;
    private @Nullable Source source;
    private ImString text = new ImString(1);
    private @Nullable CompletableFuture<Path> saving;
    private @Nullable Path saved;
    private boolean copied;
    private String problem = "";
    private final float[] textBox = new float[4];
    private final float[] keepBox = new float[4];

    static Path defaultFolder(@Nullable ExportSettings settings) {
        if (settings != null) {
            settings.refresh();
            return settings.getScriptsFolder();
        }
        return ExportSettings.defaultFolder(Path.of(System.getProperty("user.home")))
                .resolve(ExportSettings.SCRIPTS_NAME);
    }

    static String linesText(String text) {
        var lines =
                text.isEmpty() ? 0 : (int) text.chars().filter(c -> c == '\n').count();
        return lines == 1 ? "1 line" : lines + " lines";
    }

    static String aboutText(Source source, boolean edited) {
        var file = source.packageName() + (source.fileName().endsWith(".uc") ? ".u" : ".usk");
        return file + (edited ? " · with your edits" : " · as the client has it");
    }

    static String unreadText(int count) {
        return count == 1
                ? "1 part could not be written out, such as a value in a layout that Cruma does not read. A comment in"
                        + " the text names it."
                : count + " parts could not be written out, such as a value in a layout that Cruma does not read. A"
                        + " comment in the text names each.";
    }

    void setSettings(@Nullable ExportSettings exportSettings) {
        settings = exportSettings;
    }

    void open(String name, boolean edited, CompletableFuture<Source> text) {
        title = name;
        this.edited = edited;
        reading = text;
        source = null;
        this.text = new ImString(1);
        saving = null;
        saved = null;
        copied = false;
        problem = "";
        opening = true;
        if (settings != null) {
            settings.refresh();
        }
    }

    boolean isOpen() {
        return opening || shown;
    }

    void draw(@Nullable FontManager fonts) {
        if (fonts == null) {
            return;
        }
        if (opening) {
            opening = false;
            ImGui.openPopup(POPUP);
        }
        var io = ImGui.getIO();
        var body = Math.min(BODY_HEIGHT, io.getDisplaySizeY() - 32 - GeodataWidgets.DIALOG_CHROME);
        var width = Math.min(WIDTH + 2 * GeodataWidgets.DIALOG_SIDE, io.getDisplaySizeX() - 32);
        ImGui.setNextWindowPos(io.getDisplaySizeX() * .5f, io.getDisplaySizeY() * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        var visible = GeodataWidgets.beginDialog(POPUP, width, body + GeodataWidgets.DIALOG_CHROME);
        ImGui.popStyleVar();
        shown = visible;
        if (!visible) {
            return;
        }
        poll(fonts);
        var typing = ImGui.isAnyItemActive();
        if (GeodataWidgets.dialogTitle(fonts, title, "close-class-source", "effects/view-a-script") && saving == null) {
            ImGui.closeCurrentPopup();
        }
        var top = ImGui.getCursorPosY();
        drawBody(fonts, width - 2 * GeodataWidgets.DIALOG_SIDE, body);
        GeodataWidgets.dialogRule(top + body);
        drawFooter();
        if (saving == null && (!typing && GeodataWidgets.isEscapePressed() || GeodataWidgets.wasClickedOutside())) {
            ImGui.closeCurrentPopup();
        }
        var picked = folderPicker.draw();
        if (picked != null) {
            chosenFolder = picked;
            saved = null;
        }
        ImGui.endPopup();
    }

    private Path getFolder() {
        if (chosenFolder != null) {
            return chosenFolder;
        }
        return settings != null ? settings.getScriptsFolder() : defaultFolder(null);
    }

    private boolean canKeep(Path folder) {
        return settings != null && !folder.equals(settings.getStandardScriptsFolder());
    }

    private void poll(FontManager fonts) {
        if (reading != null && reading.isDone()) {
            var done = reading;
            reading = null;
            try {
                var read = done.join();
                source = read;
                title = read.fileName();
                text = new ImString(read.text());
                fonts.useLetters(read.text());
            } catch (CompletionException | CancellationException failure) {
                problem = EffectExportDialog.message(failure);
            }
        }
        if (saving != null && saving.isDone()) {
            var done = saving;
            saving = null;
            try {
                saved = done.join();
            } catch (CompletionException | CancellationException failure) {
                problem = EffectExportDialog.message(failure);
            }
        }
    }

    private void drawBody(FontManager fonts, float width, float height) {
        var top = ImGui.getCursorPosY();
        var ready = source;
        ImGui.textColored(
                GeodataWidgets.MUTED, ready == null ? "" : HudWidgets.truncateToWidth(aboutText(ready, edited), width));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (ready != null && ready.unread() > 0) {
            GeodataWidgets.note(unreadText(ready.unread()), GeodataWidgets.WARNING, width);
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        var folder = getFolder();
        var keep = canKeep(folder) ? ImGui.getFrameHeight() + GeodataWidgets.ROW : 0;
        var bottom = FOLDER_FIELD + keep;
        var textHeight = Math.max(80, top + height - bottom - GeodataWidgets.BLOCK - ImGui.getCursorPosY());
        if (ready == null) {
            ImGui.textColored(
                    GeodataWidgets.MUTED,
                    reading != null ? "Reading the class…" : problem.isEmpty() ? "" : "The text could not be made.");
            if (!problem.isEmpty()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.note(problem, GeodataWidgets.BLOCKED, width);
            }
            ImGui.setCursorPosY(top + height - bottom);
        } else {
            ImGui.pushFont(fonts.getMonoFont(), 14f);
            ImGui.inputTextMultiline("##class-source-text", text, width, textHeight, ImGuiInputTextFlags.ReadOnly);
            textBox[0] = ImGui.getItemRectMinX();
            textBox[1] = ImGui.getItemRectMinY();
            textBox[2] = ImGui.getItemRectMaxX();
            textBox[3] = ImGui.getItemRectMaxY();
            ImGui.popFont();
            GeodataWidgets.space(GeodataWidgets.BLOCK);
        }
        if (GeodataWidgets.folderField(
                "class-source-folder",
                "Save to",
                folder.toString(),
                "Save puts the file into a folder of its package, such as LineageEffect/Classes, as UnrealEd lays out"
                        + " scripts. The client does not change.")) {
            folderPicker.open(folder);
        }
        if (keep > 0 && settings != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            var kept = folder.equals(settings.getScriptsFolder());
            var clicked = ImGui.checkbox("Use this folder next time##class-source-keep", kept);
            keepBox[0] = ImGui.getItemRectMinX();
            keepBox[1] = ImGui.getItemRectMinY();
            keepBox[2] = ImGui.getItemRectMaxX();
            keepBox[3] = ImGui.getItemRectMaxY();
            if (clicked) {
                if (kept) {
                    chosenFolder = folder;
                    settings.setScriptsFolder(null);
                } else {
                    settings.setScriptsFolder(folder);
                }
            }
            GeodataWidgets.tip("Save starts in this folder from now on, and so do the scripts of AI agents. Clear it to"
                    + " go back to Scripts in the export folder. Settings shows it as Effect scripts folder.");
        }
    }

    private void drawFooter() {
        var ready = source;
        var buttons = new ArrayList<FooterButton>();
        if (saved != null) {
            buttons.add(new FooterButton("class-source-show", Glyph.FOLDER, "Show folder", 132, false));
        }
        buttons.add(new FooterButton(
                "class-source-copy",
                Glyph.COPY,
                "Copy",
                96,
                false,
                ready != null,
                "Copy the whole text. To copy a part, select it in the text."));
        buttons.add(new FooterButton(
                "class-source-save",
                null,
                "Save",
                96,
                true,
                ready != null && saving == null,
                "Write the text into a file in the folder under it. The client does not change."));
        buttons.add(new FooterButton("class-source-done", null, "Done", 96, false, saving == null, ""));
        var color = !problem.isEmpty() && ready != null
                ? GeodataWidgets.BLOCKED
                : saved != null ? GeodataWidgets.OPEN : GeodataWidgets.MUTED;
        var pressed = GeodataWidgets.footer(statusText(), color, buttons);
        if (pressed < 0 || ready == null && !buttons.get(pressed).id().equals("class-source-done")) {
            return;
        }
        switch (buttons.get(pressed).id()) {
            case "class-source-show" -> {
                if (saved != null) {
                    FileManager.tryShow(saved);
                }
            }
            case "class-source-copy" -> {
                ImGui.setClipboardText(ready.text());
                copied = true;
                problem = "";
            }
            case "class-source-save" -> save();
            default -> ImGui.closeCurrentPopup();
        }
    }

    private String statusText() {
        var ready = source;
        if (!problem.isEmpty() && ready != null) {
            return problem;
        }
        if (saved != null) {
            return "Saved " + saved.getFileName();
        }
        if (saving != null) {
            return "Saving…";
        }
        if (ready == null) {
            return "";
        }
        return copied ? "Copied " + linesText(ready.text()) : linesText(ready.text());
    }

    private void save() {
        var ready = source;
        if (ready == null) {
            return;
        }
        problem = "";
        saved = null;
        var file = getFolder().resolve(ready.getRelativePath());
        var bytes = ready.toFileBytes();
        saving = CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
                return file;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }
}
