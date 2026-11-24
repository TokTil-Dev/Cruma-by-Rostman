package com.rost.cruma.viewer.ui;

import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class WorldGuideActions {
    private static final String NO_WORLD = "Open a world first.";

    private WorldGuideActions() {}

    static void addStartTo(Map<String, GuideAction> actions) {
        add(actions, new OpenMap());
        for (var workspace : new String[] {"explore", "inspect", "analyze"}) {
            add(actions, new ShowWorkspace(workspace));
        }
    }

    static void addViewTo(Map<String, GuideAction> actions) {
        add(actions, new OpenTool("view-open-scene", "scene", "Scene settings"));
        add(actions, new OpenTool("view-open-object", "object", "Object panel"));
        add(actions, new OpenTool("view-open-performance", "performance", "Performance"));
    }

    static void addExportTo(Map<String, GuideAction> actions) {
        add(actions, new ExportHere());
    }

    private static void add(Map<String, GuideAction> actions, GuideAction action) {
        actions.put(action.getId(), action);
    }

    private static final class OpenMap implements GuideAction {
        @Override
        public String getId() {
            return "start-open-world-map";
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            var world = host.getWorld();
            return world == null ? NO_WORLD : world.getMapUnavailable();
        }

        @Override
        public void run(GuideHost host) {
            var world = host.getWorld();
            if (world != null) {
                world.openMap();
            }
        }
    }

    private static final class ShowWorkspace implements GuideAction {
        private final String workspace;

        ShowWorkspace(String workspace) {
            this.workspace = workspace;
        }

        @Override
        public String getId() {
            return "start-mode-" + workspace;
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            var world = host.getWorld();
            if (world == null) {
                return NO_WORLD;
            }
            if (world.getWorkspace().equals(workspace)) {
                return "You are in " + workspace.substring(0, 1).toUpperCase(Locale.ROOT) + workspace.substring(1)
                        + " already.";
            }
            return world.getWorkspaceUnavailable(workspace);
        }

        @Override
        public void run(GuideHost host) {
            var world = host.getWorld();
            if (world != null) {
                world.showWorkspace(workspace);
            }
        }
    }

    private static final class OpenTool implements GuideAction {
        private final String id;
        private final String tool;
        private final String name;

        OpenTool(String id, String tool, String name) {
            this.id = id;
            this.tool = tool;
            this.name = name;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            if (host.getWorld() == null) {
                return NO_WORLD;
            }
            var missing = NotedGuideAction.withoutTool(host, tool, "This window has no " + name + ".");
            if (missing != null) {
                return missing;
            }
            return tool.equals(host.getOpenTool()) ? "It is open at the right." : null;
        }

        @Override
        public void run(GuideHost host) {
            host.openTool(tool);
        }
    }

    private static final class ExportHere implements GuideAction {
        @Override
        public String getId() {
            return "export-here";
        }

        @Override
        public @Nullable String getBlocked(GuideHost host) {
            var world = host.getWorld();
            return world == null ? NO_WORLD : world.getExportUnavailable();
        }

        @Override
        public void run(GuideHost host) {
            var world = host.getWorld();
            if (world != null) {
                world.exportHere();
            }
        }
    }
}
