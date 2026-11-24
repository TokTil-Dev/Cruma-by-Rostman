package com.rost.cruma.viewer;

import com.rost.cruma.viewer.application.WorldViewer.Pose;
import com.rost.cruma.viewer.config.Config;
import com.rost.cruma.viewer.config.Config.Viewer;
import com.rost.cruma.viewer.config.Config.Viewer.Camera;
import com.rost.cruma.viewer.config.Config.Viewer.Camera.Location;
import com.rost.cruma.viewer.config.Config.Viewer.Editing;
import com.rost.cruma.viewer.launch.MenuStart;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

record SettingsReload(
        @Nullable MenuStart start, List<String> maps, boolean effects, Mode mode, Pose camera, Editing editing) {
    static final String RESUMED = "Where the last session closed";

    SettingsReload {
        maps = List.copyOf(maps);
    }

    interface SettingsSource {
        Config load();

        Config forClient(Path client, String tiles, @Nullable String place);
    }

    record Next(Config config, Viewer viewer, List<String> maps, boolean effects, Mode mode, String note) {}

    record Outcome(@Nullable Next next, String notice) {}

    Outcome rebuild(SettingsSource settings, Predicate<Path> isFolder, boolean runHasMenu) {
        var menu = runHasMenu || start != null;
        Config config;
        try {
            config = start == null ? settings.load() : settings.forClient(start.client(), start.tiles(), start.place());
        } catch (RuntimeException failure) {
            if (!menu) {
                throw failure;
            }
            var reason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return new Outcome(null, "Settings are saved, but the world could not load again with them. " + reason);
        }
        var fresh = config.viewer();
        var client = fresh.world().clientPath();
        if (menu && (client == null || client.toString().isBlank() || !isFolder.test(client))) {
            return new Outcome(
                    null, "Settings are saved, but the client folder " + client + " is gone. Choose a client here.");
        }
        var resumed = new Camera(
                new Location(RESUMED, camera.x(), camera.y(), camera.z()),
                camera.yaw(),
                camera.pitch(),
                fresh.camera().locations(),
                fresh.camera().mouseSensitivity());
        var world = fresh.world().covering(maps);
        var nextEditing = fresh.editing();
        var note = "";
        if (mode == Mode.EDITING && changesRecovery(nextEditing)) {
            nextEditing = keepRecovery(nextEditing);
            note = "Edit keeps the recovery settings of the last session until the next start, so it offers its"
                    + " draft: recovery " + (editing.recoveryEnabled() ? "on" : "off") + " in "
                    + editing.recoveryDirectory() + ".";
        }
        var viewer = new Viewer(world, resumed, nextEditing, fresh.director(), fresh.agent());
        return new Outcome(new Next(config, viewer, maps, effects, mode, note), "");
    }

    private boolean changesRecovery(Editing next) {
        return next.recoveryEnabled() != editing.recoveryEnabled()
                || !next.recoveryDirectory().equals(editing.recoveryDirectory());
    }

    private Editing keepRecovery(Editing next) {
        return new Editing(
                editing.recoveryEnabled(),
                next.autosaveDebounceMillis(),
                next.checkpointCommands(),
                next.retainedGenerations(),
                next.maximumMegabytes(),
                editing.recoveryDirectory(),
                next.moveActorsToTheirTile(),
                next.welcomeShown(),
                next.teleportCommand());
    }
}
