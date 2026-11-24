package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.editing.ClientInstaller.Plan;
import com.rost.cruma.viewer.editing.ClientInstaller.State;
import com.rost.cruma.viewer.editing.EditorUi.Tone;
import com.rost.cruma.viewer.ui.FolderPicker;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.Motion;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jetbrains.annotations.Nullable;

public final class ClientInstallPanel {
    private static final String CONFIRM_POPUP = "Install into the client";
    private static final String RESTORE_POPUP = "Restore the client";

    private final String installLabel;
    private String warning;
    private final ImString client = new ImString(1024);
    private final FolderPicker picker = new FolderPicker(
            "Choose the client folder",
            "Choose the folder that has the Maps, Textures and StaticMeshes folders.",
            false);
    private @Nullable CompletableFuture<Runnable> task;
    private Plan pending;
    private boolean asking;
    private int @Nullable [] restoring;
    private boolean askingRestore;
    private @Nullable Path checkedExport;
    private @Nullable Path installedIn;
    private String status = "";
    private String problem = "";
    private int changes;
    private float installLeft;
    private float installTop;
    private float yesLeft;
    private float yesTop;
    private float restoreLeft;
    private float restoreTop;
    private float restoreYesLeft;
    private float restoreYesTop;

    ClientInstallPanel() {
        this("Install the last save", "");
    }

    public ClientInstallPanel(String installLabel, String warning) {
        this.installLabel = installLabel;
        this.warning = warning;
    }

    static boolean isEditedClient(@Nullable Path changed, Path source) {
        return changed != null
                && changed.toAbsolutePath()
                        .normalize()
                        .startsWith(source.toAbsolutePath().normalize());
    }

    static String restoreQuestion(int replaced, int added) {
        if (replaced == 0 && added == 0) {
            return "Put back the client files that the installation replaced?";
        }
        var parts = new ArrayList<String>();
        if (replaced > 0) {
            parts.add("put back " + EditorUi.plural(replaced, "client file", "client files"));
        }
        if (added > 0) {
            parts.add("remove " + EditorUi.plural(added, "added package", "added packages"));
        }
        var text = String.join(" and ", parts);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1) + "?";
    }

    public void setWarning(String text) {
        warning = text;
    }

    public int getChanges() {
        return changes;
    }

    public void drawClientField(Path source) {
        if (client.get().isBlank()) {
            client.set(source.toString());
        }
        if (GeodataWidgets.folderField(
                "installClient",
                "Client folder",
                client.get(),
                "The client folder to install into. By default it is the client you are editing. Use a copy of the"
                    + " client to keep the original untouched. Close the game first: a file in use cannot be replaced."
                    + " A click chooses another folder, where a path can also be typed.")) {
            picker.open(Path.of(client.get()));
        }
        var chosen = picker.draw();
        if (chosen != null) {
            client.set(chosen.toString());
        }
    }

    void askAfterWrite(Path export) {
        if (task != null || client.get().isBlank()) {
            return;
        }
        checkedExport = export;
        installedIn = null;
        check(export, Path.of(client.get()));
    }

    @Nullable
    public Path getInstalledIn() {
        poll();
        return installedIn;
    }

    public boolean isWorking() {
        return task != null;
    }

    public void drawState(@Nullable Path export, boolean busy) {
        poll();
        if (!Objects.equals(export, checkedExport)) {
            checkedExport = export;
            installedIn = export == null ? null : ClientInstaller.installedClient(export);
            status = "";
            problem = "";
        }
        if (export == null) {
            return;
        }
        if (task != null) {
            var dots = ".".repeat((int) (ImGui.getTime() * 3) % 4);
            GeodataWidgets.note(status.replace("...", "") + dots, GeodataWidgets.ACCENT);
        } else if (installedIn != null) {
            GeodataWidgets.writtenResult(
                    "installedClient",
                    status.isEmpty() ? "Installed in " + installedIn.getFileName() + "." : status,
                    GeodataWidgets.OPEN,
                    installedIn);
            GeodataWidgets.space(GeodataWidgets.ROW);
            restoreLeft = ImGui.getCursorScreenPosX();
            restoreTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.actionButton("restoreClient", Glyph.UNDO, "Restore client", -1, false)) {
                var counts = ClientInstaller.installedFiles(export);
                restoring = counts == null ? new int[2] : counts;
                askingRestore = true;
            }
            EditorUi.tip(
                    "Put back the client files that the installation replaced, and remove the packages it added, after"
                            + " asking. Refused when an installed file changed later.");
        } else {
            ImGui.beginDisabled(busy || client.get().isBlank());
            installLeft = ImGui.getCursorScreenPosX();
            installTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.actionButton("installExport", null, installLabel, -1, false)) {
                check(export, Path.of(client.get()));
            }
            ImGui.endDisabled();
            EditorUi.tip(
                    "Check the client folder, then ask before any file is copied. The files it replaces are kept for"
                            + " Restore client.");
        }
        if (!status.isEmpty() && task == null && installedIn == null) {
            EditorUi.message(Tone.SUCCESS, status);
        }
        EditorUi.message(Tone.ERROR, problem);
        drawConfirmPopup(busy);
        drawRestorePopup(export);
    }

    private void check(Path export, Path target) {
        start("Checking the client...", () -> {
            var plan = ClientInstaller.inspect(export, target);
            return () -> {
                var reason = plan.problem();
                if (reason != null) {
                    problem = reason;
                    status = "";
                } else {
                    pending = plan;
                    status = "";
                    asking = true;
                }
            };
        });
    }

    private void drawRestorePopup(Path export) {
        if (askingRestore) {
            askingRestore = false;
            ImGui.openPopup(RESTORE_POPUP);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(400, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                RESTORE_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        Motion.dialog();
        var counts = restoring;
        if (counts == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        ImGui.pushTextWrapPos(0);
        ImGui.textUnformatted(restoreQuestion(counts[0], counts[1]));
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                "The client is then as it was before the installation. The folder stays, so it can be installed"
                        + " again.");
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var width = GeodataWidgets.columnWidth(2);
        restoreYesLeft = ImGui.getCursorScreenPosX();
        restoreYesTop = ImGui.getCursorScreenPosY();
        if (HudWidgets.button("restore-yes", null, "Restore", width, HudWidgets.CONTROL_HEIGHT, true, true)) {
            ImGui.closeCurrentPopup();
            restoring = null;
            start("Restoring the client...", () -> {
                var restored = ClientInstaller.restore(export);
                return () -> {
                    installedIn = null;
                    status =
                            EditorUi.plural(restored.size(), "client file is", "client files are") + " back as before.";
                    changes++;
                };
            });
        }
        ImGui.sameLine();
        if (HudWidgets.button("restore-no", null, "Cancel", width, HudWidgets.CONTROL_HEIGHT, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)) {
            ImGui.closeCurrentPopup();
            restoring = null;
        }
        ImGui.endPopup();
    }

    private void drawConfirmPopup(boolean busy) {
        if (asking && !busy) {
            asking = false;
            ImGui.openPopup(CONFIRM_POPUP);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.max(280, Math.min(440, io.getDisplaySizeX() - 32)), 0);
        if (!ImGui.beginPopupModal(
                CONFIRM_POPUP,
                ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.AlwaysAutoResize)) {
            return;
        }
        Motion.dialog();
        var plan = pending;
        if (plan == null) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }
        var replaced = plan.items().stream()
                .filter(item -> item.state() == State.ORIGINAL)
                .count();
        var added = plan.items().size() - replaced;
        ImGui.pushTextWrapPos(0);
        if (!warning.isEmpty()) {
            GeodataWidgets.note(warning, GeodataWidgets.WARNING);
            GeodataWidgets.space(GeodataWidgets.ROW);
        }
        ImGui.textUnformatted("Copy "
                + EditorUi.plural(plan.items().size(), "package", "packages")
                + " into "
                + plan.client().getFileName()
                + "?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (replaced > 0) {
            GeodataWidgets.hint(EditorUi.plural((int) replaced, "client file is", "client files are")
                    + " replaced. The originals are kept in "
                    + plan.export().getFileName()
                    + "/"
                    + ClientInstaller.BACKUP_DIRECTORY
                    + ", and Restore client puts them back.");
        }
        if (added > 0) {
            GeodataWidgets.hint(EditorUi.plural((int) added, "new package is", "new packages are")
                    + " added. Restore client removes them.");
        }
        for (var item : plan.items()) {
            GeodataWidgets.hint("  · " + item.path());
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.note("Close the game client first. A file in use cannot be replaced.", GeodataWidgets.FAINT);
        ImGui.popTextWrapPos();
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var width = GeodataWidgets.columnWidth(2);
        yesLeft = ImGui.getCursorScreenPosX();
        yesTop = ImGui.getCursorScreenPosY();
        if (HudWidgets.button("install-yes", null, "Install", width, HudWidgets.CONTROL_HEIGHT, true, true)) {
            ImGui.closeCurrentPopup();
            pending = null;
            start("Installing...", () -> {
                var installed = ClientInstaller.install(plan);
                return () -> {
                    installedIn = plan.client();
                    status = installedText(installed.size(), plan.client());
                    changes++;
                };
            });
        }
        ImGui.sameLine();
        if (HudWidgets.button("install-no", null, "Cancel", width, HudWidgets.CONTROL_HEIGHT, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)) {
            ImGui.closeCurrentPopup();
            pending = null;
        }
        ImGui.endPopup();
    }

    private void start(String message, Work work) {
        status = message;
        problem = "";
        task = CompletableFuture.supplyAsync(() -> {
            try {
                return work.run();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void poll() {
        var running = task;
        if (running == null || !running.isDone()) {
            return;
        }
        task = null;
        try {
            running.join().run();
        } catch (CompletionException failure) {
            var cause = failure.getCause() == null ? failure : failure.getCause();
            status = "";
            problem = cause.getMessage() == null ? cause.toString() : cause.getMessage();
        }
    }

    private static String installedText(int packages, Path client) {
        return "Installed " + EditorUi.plural(packages, "package", "packages") + " in " + client.getFileName() + ".";
    }

    @FunctionalInterface
    private interface Work {
        Runnable run() throws Exception;
    }
}
