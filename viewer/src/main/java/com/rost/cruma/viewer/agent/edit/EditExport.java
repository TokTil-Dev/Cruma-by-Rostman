package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.ClientInstaller;
import com.rost.cruma.viewer.editing.EditingExportCoordinator;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.ui.WorkspaceLayout.Mode;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

public final class EditExport implements Step {
    private static final long WORK_NANOS = TimeUnit.MINUTES.toNanos(30);

    private final ViewerContext viewer;
    private final EditingSession session;
    private final EditingExportCoordinator exports;
    private final String action;
    private final @Nullable Path folder;
    private final long deadline = System.nanoTime() + WORK_NANOS;
    private Stage stage = Stage.BEGIN;
    private @Nullable Step installing;
    private int installed;
    private final JSONObject lighting;

    private EditExport(ViewerContext viewer, EditingSession session, String action, @Nullable Path folder) {
        this.viewer = viewer;
        this.session = session;
        this.action = action;
        this.folder = folder;
        exports = session.getExportCoordinator();
        lighting = lightingOf(session);
    }

    private enum Stage {
        BEGIN,
        VALIDATING,
        SAVING,
        INSTALLING,
        RELOADING
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var asked = args.getText("action", "check").toLowerCase(Locale.ROOT);
        var action = asked.equals("validate") ? "check" : asked;
        if (!List.of("check", "fix", "save", "install", "restore").contains(action)) {
            throw new ToolProblem("action must be check, fix, save, install or restore.");
        }
        if (action.equals("fix") || args.has("lighting")) {
            requireEdit(viewer.agent().getAccess(), action.equals("fix") ? "fix" : "lighting");
        }
        if (action.equals("fix")) {
            return EditFix.start(args, viewer, session);
        }
        if (!action.equals("check")) {
            requireWrite(viewer.agent().getAccess(), action);
        }
        setLighting(args, session);
        var folder = folder(args.getText("folder"));
        if (action.equals("save")) {
            requireSaveFolder(folder, session.getClientRoot());
        }
        return EditSteps.editing(viewer, session, () -> new EditExport(viewer, session, action, folder));
    }

    static void requireWrite(Access access, String action) throws ToolProblem {
        if (!access.allows(Access.WRITE)) {
            throw new ToolProblem("edit_export " + action + " needs Write access, and this viewer gives "
                    + access.getLabel() + ". Ask the person to raise it in the AI agent panel of Cruma.");
        }
    }

    static void requireEdit(Access access, String what) throws ToolProblem {
        if (!access.allows(Access.EDIT)) {
            throw new ToolProblem(
                    "edit_export " + what + " changes the session and needs Edit access, and this viewer gives "
                            + access.getLabel() + ". Ask the person to raise it in the AI agent panel of Cruma.");
        }
    }

    static JSONObject lighting() {
        var properties = new JSONObject();
        for (var option : List.of("all", "bsp", "terrain", "keep_beast")) {
            var flag = new JSONObject();
            flag.put("type", "boolean");
            properties.put(option, flag);
        }
        var schema = new JSONObject();
        schema.put("type", "object");
        schema.put(
                "description",
                "The options of the save, kept for the session: rebuild all lighting, all BSP lightmaps, all Terrain"
                        + " lighting, and keep the neighbour Beast bake. Needs Edit.");
        schema.put("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    public static int restore(Path export, Path client) throws ToolProblem, IOException {
        var installed = ClientInstaller.installedClient(export);
        if (installed == null) {
            throw new ToolProblem("This save is not installed in a client, so there is nothing to restore.");
        }
        if (!installed
                .toAbsolutePath()
                .normalize()
                .equals(client.toAbsolutePath().normalize())) {
            throw new ToolProblem("This save is installed in " + installed
                    + ", not in the client of this viewer. Open that client to restore it.");
        }
        return ClientInstaller.restore(export).size();
    }

    static void requireSaveFolder(@Nullable Path folder, Path client) throws ToolProblem {
        if (folder == null) {
            throw new ToolProblem("folder is missing: name a new folder for the save.");
        }
        if (folder.startsWith(client.toAbsolutePath().normalize())) {
            throw new ToolProblem(
                    "A save goes into a folder of its own, not into the client. Use the action install for the"
                            + " client.");
        }
    }

    public static int install(Path export, Path client) throws ToolProblem, IOException {
        var plan = ClientInstaller.inspect(export, client);
        var problem = plan.problem();
        if (problem != null) {
            throw new ToolProblem(problem);
        }
        return ClientInstaller.install(plan).size();
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        if (System.nanoTime() > deadline) {
            throw new ToolProblem("The export did not end in time.");
        }
        return switch (stage) {
            case BEGIN -> begin();
            case VALIDATING -> exports.isBusy() ? null : validated();
            case SAVING -> exports.isBusy() ? null : saved();
            case INSTALLING -> installing();
            case RELOADING -> reloaded();
        };
    }

    @Override
    public void cancel() {
        if (exports.isBusy()) {
            exports.cancel();
        }
        if (installing != null) {
            installing.cancel();
        }
    }

    private @Nullable ToolResult begin() throws ToolProblem {
        if (action.equals("install") || action.equals("restore")) {
            return beginInstall();
        }
        if (!exports.hasExportableChanges()) {
            throw new ToolProblem("There is nothing to save: no actor or Terrain differs from the client.");
        }
        if (action.equals("save") && exports.isCurrent()) {
            return beginSave();
        }
        try {
            exports.beginPrepare();
        } catch (IOException failed) {
            throw new ToolProblem(failed.getMessage());
        }
        stage = Stage.VALIDATING;
        return null;
    }

    private @Nullable ToolResult validated() throws ToolProblem {
        requireNoError();
        var plan = exports.getPlan();
        if (plan == null) {
            throw new ToolProblem("The check ended with no result.");
        }
        if (action.equals("check")) {
            var data = write(plan);
            data.put("options", lighting);
            return ToolResult.data(data);
        }
        return beginSave();
    }

    private @Nullable ToolResult beginSave() throws ToolProblem {
        var plan = exports.getPlan();
        if (!plan.isValid()) {
            throw new ToolProblem("The changes cannot be saved: " + String.join(" ", plan.getErrors()));
        }
        try {
            exports.beginSave(folder);
        } catch (IOException failed) {
            throw new ToolProblem(failed.getMessage());
        }
        stage = Stage.SAVING;
        return null;
    }

    private ToolResult saved() throws ToolProblem {
        requireNoError();
        var written = exports.getLastOutput();
        if (!exports.hasPublished() || written == null) {
            throw new ToolProblem("The save ended without writing the folder.");
        }
        var data = new JSONObject();
        data.put("saved", true);
        data.put("folder", written.toString());
        data.put("unsaved", viewer.scene().doesEditingNeedSave());
        return ToolResult.data(data).withFolder(written);
    }

    private @Nullable ToolResult beginInstall() throws ToolProblem {
        var restore = action.equals("restore");
        var export = folder != null ? folder : exports.getLastOutput();
        if (export == null) {
            throw new ToolProblem("There is no save to " + action + ". "
                    + (restore
                            ? "Name the saved folder that was installed."
                            : "Call edit_export with action save first, or name a saved folder."));
        }
        if (restore && ClientInstaller.installedClient(export) == null) {
            throw new ToolProblem("This save is not installed in a client, so there is nothing to restore.");
        }
        if (viewer.scene().doesEditingNeedSave()) {
            throw new ToolProblem("There are changes that are not saved, and " + (restore ? "a restore" : "an install")
                    + " reads the client again. Save them first.");
        }
        var client = session.getClientRoot();
        installing = Step.onWorker(() -> {
            installed = restore ? restore(export, client) : install(export, client);
            return ToolResult.text("");
        });
        stage = Stage.INSTALLING;
        return null;
    }

    private @Nullable ToolResult installing() throws ToolProblem {
        if (installing.step() == null) {
            return null;
        }
        var ui = viewer.ui();
        ui.workspaceMode = Mode.EDITING;
        ui.editingReloadRequested = true;
        stage = Stage.RELOADING;
        return null;
    }

    private @Nullable ToolResult reloaded() {
        var ui = viewer.ui();
        if (ui.editingReloadRequested || !viewer.ready()) {
            return null;
        }
        var data = new JSONObject();
        data.put(action.equals("restore") ? "restored" : "installed", installed);
        data.put("client", session.getClientRoot().toString());
        return ToolResult.data(data);
    }

    private void requireNoError() throws ToolProblem {
        var error = exports.getError();
        if (!error.isEmpty()) {
            throw new ToolProblem(error);
        }
    }

    private static void setLighting(ToolArgs args, EditingSession session) throws ToolProblem {
        var value = args.getJson().get("lighting");
        if (value == null) {
            return;
        }
        if (!(value instanceof JSONObject json)) {
            throw new ToolProblem("lighting must be an object with all, bsp, terrain or keep_beast.");
        }
        var options = new ToolArgs(json);
        session.rebuildAllLighting(options.readFlag("all", session.isRebuildAllLighting()));
        session.rebuildAllBspLightmaps(options.readFlag("bsp", session.isRebuildAllBspLightmaps()));
        session.rebuildAllTerrainLighting(options.readFlag("terrain", session.isRebuildAllTerrainLighting()));
        session.keepNeighborBeast(options.readFlag("keep_beast", session.isKeepNeighborBeast()));
    }

    private static JSONObject lightingOf(EditingSession session) {
        var data = new JSONObject();
        data.put("all", session.isRebuildAllLighting());
        data.put("bsp", session.isRebuildAllBspLightmaps());
        data.put("terrain", session.isRebuildAllTerrainLighting());
        data.put("keep_beast", session.isKeepNeighborBeast());
        return data;
    }

    private static @Nullable Path folder(@Nullable String text) throws ToolProblem {
        if (text == null) {
            return null;
        }
        try {
            var path = Path.of(text);
            if (!path.isAbsolute()) {
                throw new ToolProblem("folder must be a full path.");
            }
            return path.normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("folder is not a path: " + wrong.getMessage());
        }
    }

    private static JSONObject write(Plan plan) {
        var data = new JSONObject();
        data.put("valid", plan.isValid());
        data.put("errors", plan.getErrors());
        data.put("notes", plan.getNotes());
        var packages = new JSONArray();
        for (var item : plan.getPackages()) {
            packages.add(item.name());
        }
        data.put("packages", packages);
        data.put("lighting", plan.getLighting());
        return data;
    }
}
