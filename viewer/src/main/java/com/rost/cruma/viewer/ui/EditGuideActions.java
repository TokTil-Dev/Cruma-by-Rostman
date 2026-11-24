package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.ui.GuideHost.Edit;
import java.util.Map;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

final class EditGuideActions {
    static final String OUTSIDE = "Open Edit first: Edit on the top bar.";
    static final String NO_EDIT = "This view has no Edit. Open the world of a client from the start menu.";
    private static final String NO_WORLD = "Open a world first.";

    private static final String[][] TOOLS = {
        {"select", "Select", "Click an object in the view to select it."},
        {"place", "Place", "Choose a mesh in the Library, then click the ground."},
        {"sculpt", "Sculpt", "Open the Terrain of a map in the panel, then drag on the ground."},
        {"paint", "Paint", "Open the Terrain of a map in the panel, then choose a layer."},
        {"lights", "Lights", "Click the mark of a light in the view, or press New light."},
        {"surfaces", "Surfaces", "Click a wall or a floor of the level geometry."}
    };

    private EditGuideActions() {}

    static void addTo(Map<String, GuideAction> actions) {
        for (var tool : TOOLS) {
            add(
                    actions,
                    new NotedGuideAction(
                            "edit-tool-" + tool[0],
                            EditGuideActions::blocked,
                            host -> withEdit(
                                    host,
                                    edit -> edit.chooseTool(tool[0])
                                            ? tool[1] + " is open in the Edit panel. " + tool[2]
                                            : "Wait until the drag or the stroke ends, then try again.")));
        }
        add(
                actions,
                new NotedGuideAction(
                        "edit-open-library",
                        EditGuideActions::blocked,
                        host -> withEdit(host, edit -> {
                            edit.openLibrary();
                            return "The Library is open on the left. Type in its search field to find a mesh.";
                        })));
        add(
                actions,
                new NotedGuideAction(
                        "edit-frame-selection",
                        host -> {
                            var reason = blocked(host);
                            if (reason != null) {
                                return reason;
                            }
                            var edit = host.getEdit();
                            return edit != null && !edit.hasSelection()
                                    ? "Select an object first: click one in the view."
                                    : null;
                        },
                        host -> withEdit(host, edit -> {
                            edit.frameSelection();
                            return null;
                        })));
        add(
                actions,
                new NotedGuideAction(
                        "edit-open-save",
                        EditGuideActions::blocked,
                        host -> withEdit(host, edit -> {
                            edit.openSave();
                            return "The save review is open in the Edit panel. Nothing is saved until you press Save.";
                        })));
    }

    static @Nullable String blocked(GuideHost host) {
        if (!host.hasWorld()) {
            return NO_WORLD;
        }
        var edit = host.getEdit();
        return edit == null ? NO_EDIT : edit.getUnavailable();
    }

    private static @Nullable String withEdit(GuideHost host, Function<Edit, @Nullable String> work) {
        var edit = host.getEdit();
        return edit == null ? NO_EDIT : work.apply(edit);
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }
}
