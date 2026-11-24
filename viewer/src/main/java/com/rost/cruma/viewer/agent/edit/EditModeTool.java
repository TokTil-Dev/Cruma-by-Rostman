package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

final class EditModeTool {
    private static final long RELOAD_MILLIS = TimeUnit.MINUTES.toMillis(15);

    private EditModeTool() {}

    static Step mode(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var on = args.readFlag("on", true);
        var ui = viewer.ui();
        if (on != ui.editingPrepared) {
            if (!on && viewer.scene().doesEditingNeedSave()) {
                if (!viewer.agent().isOwned()) {
                    throw new ToolProblem(
                            "This is a window of the person, and only they drop its changes. Save them with"
                                    + " edit_export, or ask them.");
                }
                if (!args.readFlag("discard", false)) {
                    throw new ToolProblem(
                            "There are changes that are not saved. Call edit_changes to read them and edit_export to"
                                    + " save them, or edit_mode with discard: true to lose them.");
                }
                discard(viewer);
            } else {
                viewer.system().workspaceMode(on ? Mode.EDITING : Mode.EXPLORE);
            }
        }
        return Step.until(
                () -> !ui.editingReloadRequested && ui.editingPrepared == on && viewer.ready(),
                RELOAD_MILLIS,
                "The tiles did not load again in time.",
                () -> {
                    viewer.agent().refreshStatus(viewer);
                    var data = new JSONObject();
                    data.put("editing", on);
                    data.put("tiles", viewer.ui().rendering.loading.tilesLoaded);
                    return ToolResult.data(data);
                });
    }

    private static void discard(ViewerContext viewer) throws ToolProblem {
        try {
            viewer.system().discardEditing();
        } catch (IOException failed) {
            throw new ToolProblem("The recovery draft could not be dropped: " + failed.getMessage());
        }
    }
}
