package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.Motion;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class EditingRecoveryPanel {
    private static final String TITLE = "Recover the Edit session";

    private boolean details;
    private String error = "";

    public void draw(EditingRecovery recovery, Runnable returnToViewer) {
        if (recovery == null) {
            return;
        }
        var available = recovery.getAvailable();
        if (available == null) {
            return;
        }
        ImGui.openPopup(TITLE);
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(480, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                TITLE,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        Motion.dialog();
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted(
                available.supported()
                        ? "Recover the changes that were not saved?"
                        : "This draft is from another version of Cruma");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                available.supported()
                        ? "An earlier Edit session stopped before its changes were saved."
                        : "This version cannot open it. The draft is kept until you discard it.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.value(
                "Draft",
                EditorUi.plural(available.maps(), "map", "maps") + " · saved "
                        + DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                .withZone(ZoneId.systemDefault())
                                .format(available.savedAt()));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                "Recover restores placements, materials, Terrain heights and the Undo history. The source maps must"
                        + " still be the ones the draft was made from.");
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var width = GeodataWidgets.columnWidth(3);
        ImGui.beginDisabled(!available.supported());
        if (HudWidgets.button("recover-yes", Glyph.UNDO, "Recover", width, HudWidgets.CONTROL_HEIGHT, true, true)) {
            try {
                recovery.recover();
                error = "";
                ImGui.closeCurrentPopup();
            } catch (IOException failure) {
                error = failure.getMessage();
            }
        }
        GeodataWidgets.tip("Continue where the earlier session stopped.");
        ImGui.endDisabled();
        ImGui.sameLine();
        if (EditorUi.dangerButton("Discard##recover-discard", width)) {
            try {
                recovery.discardAvailable();
                error = "";
                ImGui.closeCurrentPopup();
            } catch (IOException failure) {
                error = failure.getMessage();
            }
        }
        GeodataWidgets.tip("Delete the draft and start a clean Edit session. Saved folders stay.");
        ImGui.sameLine();
        if (HudWidgets.button("recover-later", null, "Back to Viewer", width, HudWidgets.CONTROL_HEIGHT, false, true)) {
            ImGui.closeCurrentPopup();
            returnToViewer.run();
        }
        GeodataWidgets.tip(
                "Leave Edit and decide later. The draft is kept, and the next time you open Edit it is offered again.");
        GeodataWidgets.space(GeodataWidgets.ROW);
        details = GeodataWidgets.header("Details");
        if (details) {
            GeodataWidgets.hint("Revision " + available.revision() + " · " + available.bytes() / 1024 + " KiB");
            GeodataWidgets.hint(available.directory().toString());
            GeodataWidgets.hint(
                    "Recovery checks map fingerprints and imported assets. If the newest write is incomplete, it keeps"
                            + " the previous valid generation.");
        }
        if (!recovery.getError().isEmpty()) {
            GeodataWidgets.note(recovery.getError(), GeodataWidgets.BLOCKED);
        }
        if (!error.isEmpty()) {
            GeodataWidgets.note(error, GeodataWidgets.BLOCKED);
        }
        ImGui.endPopup();
    }
}
