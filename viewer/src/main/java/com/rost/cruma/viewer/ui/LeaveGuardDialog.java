package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import java.util.List;

final class LeaveGuardDialog {
    private LeaveGuardDialog() {}

    enum Answer {
        NONE,
        SAVE,
        DISCARD,
        CANCEL
    }

    static Answer draw(String title, String message, List<Issue> errors) {
        if (!ImGui.isPopupOpen(title)) {
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(380, 0);
        var answer = Answer.NONE;
        if (ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            Motion.dialog();
            ImGui.textWrapped(message);
            if (!errors.isEmpty()) {
                ImGui.pushTextWrapPos(0);
                ImGui.textColored(GeodataWidgets.BLOCKED, "It cannot be saved until these are fixed:");
                ImGui.popTextWrapPos();
                SaveReviewDialog.drawIssues(errors, GeodataWidgets.BLOCKED);
            }
            ImGui.dummy(0, 6);
            ImGui.beginDisabled(!errors.isEmpty());
            var save = GeodataWidgets.rowButton("Save", 0, 3);
            ImGui.endDisabled();
            if (save) {
                answer = Answer.SAVE;
                ImGui.closeCurrentPopup();
            }
            if (GeodataWidgets.rowButton("Discard", 1, 3)) {
                answer = Answer.DISCARD;
                ImGui.closeCurrentPopup();
            }
            if (GeodataWidgets.rowButton("Cancel", 2, 3) || GeodataWidgets.wasClickedOutside()) {
                answer = Answer.CANCEL;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
        return answer;
    }
}
