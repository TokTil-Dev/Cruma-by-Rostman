package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImColor;
import imgui.ImGui;
import java.nio.file.Path;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class SaveFooter {
    static final float HEIGHT = 46;

    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";

    private final String id;
    private final String fileName;

    SaveFooter(String id, String fileName) {
        this.id = id;
        this.fileName = fileName;
    }

    record Status(
            boolean canUndo,
            boolean canRedo,
            String undoLabel,
            String redoLabel,
            boolean modified,
            boolean saving,
            int problems,
            String message,
            boolean messageIsError,
            @Nullable Path savedFile) {
        boolean isShown() {
            return modified || canUndo || canRedo || saving;
        }
    }

    enum Action {
        NONE,
        UNDO,
        REDO,
        DISCARD,
        SAVE
    }

    Action draw(Status status) {
        var action = Action.NONE;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY() + 4;
        var width = ImGui.getContentRegionAvailX();
        var modified = status.modified();
        draw.addRectFilled(
                x, y, x + width, y + HEIGHT - 6, modified ? ImColor.rgba(255, 200, 90, 24) : GeodataWidgets.CARD, 8f);
        if (modified) {
            draw.addRect(x, y, x + width, y + HEIGHT - 6, ImColor.rgba(255, 200, 90, 80), 8, 0, 1);
        }
        ImGui.setCursorScreenPos(x + 4, y + (HEIGHT - 6 - 26) * .5f);
        if (GeodataWidgets.iconButton(id + "-undo", Glyph.UNDO, status.canUndo() && !status.saving())) {
            action = Action.UNDO;
        }
        GeodataWidgets.tip(
                status.canUndo() ? "Undo " + status.undoLabel().toLowerCase(Locale.ROOT) : "Nothing to undo", UNDO_KEY);
        ImGui.sameLine(0, 2);
        if (GeodataWidgets.iconButton(id + "-redo", Glyph.REDO, status.canRedo() && !status.saving())) {
            action = Action.REDO;
        }
        GeodataWidgets.tip(
                status.canRedo() ? "Redo " + status.redoLabel().toLowerCase(Locale.ROOT) : "Nothing to redo",
                "Shift+" + UNDO_KEY);
        var textX = ImGui.getItemRectMaxX() + 8;
        var problems = modified ? status.problems() : 0;
        var text = status.saving()
                ? "Saving…"
                : problems > 0
                        ? problems == 1 ? "1 problem" : problems + " problems"
                        : modified
                                ? "Unsaved"
                                : status.message().isBlank() || status.messageIsError()
                                        ? "All changes saved"
                                        : status.message();
        var justSaved = !modified
                && !status.saving()
                && !status.messageIsError()
                && status.message().startsWith("Saved")
                && status.savedFile() != null;
        var textRight = x + width - (justSaved ? 176 : 142);
        draw.pushClipRect(textX, y, textRight, y + HEIGHT, true);
        draw.addText(
                textX,
                y + (HEIGHT - 6 - ImGui.getFontSize()) * .5f,
                problems > 0 ? GeodataWidgets.BLOCKED : modified ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                text);
        draw.popClipRect();
        if (textX + ImGui.calcTextSizeX(text) > textRight
                && ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(textX, y, textRight, y + HEIGHT - 6)) {
            HudWidgets.showTooltip(text);
        }
        if (justSaved) {
            ImGui.setCursorScreenPos(x + width - 168, y + (HEIGHT - 6 - 26) * .5f);
            if (GeodataWidgets.iconButton(id + "-show-saved", Glyph.FOLDER, true)) {
                FileManager.tryShow(status.savedFile());
            }
            GeodataWidgets.tip("Show the saved file and its backup: " + status.savedFile());
        }
        ImGui.setCursorScreenPos(x + width - 138, y + (HEIGHT - 6 - 28) * .5f);
        ImGui.beginDisabled(!modified || status.saving());
        if (HudWidgets.button(id + "-discard", null, "Discard", 66, 28, false, false)) {
            ImGui.openPopup(id + "-discard-confirm");
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Go back to " + fileName + " as last saved. Undo brings the changes back.");
        if (ImGui.beginPopup(id + "-discard-confirm")) {
            ImGui.textUnformatted("Discard all unsaved changes?");
            ImGui.textColored(GeodataWidgets.FAINT, "Undo brings them back.");
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (HudWidgets.button(id + "-discard-yes", null, "Discard", 96, 28, true, true)) {
                action = Action.DISCARD;
                ImGui.closeCurrentPopup();
            }
            ImGui.sameLine();
            if (HudWidgets.button(id + "-discard-no", null, "Keep", 96, 28, false, true)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        ImGui.setCursorScreenPos(x + width - 68, y + (HEIGHT - 6 - 28) * .5f);
        ImGui.beginDisabled(!modified || status.saving());
        if (HudWidgets.button(id + "-save", null, "Save…", 64, 28, modified, true)) {
            action = Action.SAVE;
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Shows the changed lines, then writes " + fileName + ". Only the edited lines change.",
                UNDO_KEY.replace("Z", "S"));
        ImGui.setCursorScreenPos(x, y + HEIGHT - 6);
        ImGui.dummy(width, 0);
        return action;
    }
}
