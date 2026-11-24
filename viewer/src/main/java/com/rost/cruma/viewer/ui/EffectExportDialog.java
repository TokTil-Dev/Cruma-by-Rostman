package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.editing.EffectEditSession.Placement;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.ClientInstaller;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.EffectEditController.PreparedExport;
import com.rost.cruma.viewer.ui.GeodataWidgets.FooterButton;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jetbrains.annotations.Nullable;

final class EffectExportDialog {
    static final String TITLE = "Export effects";
    static final String NAME = "Effects";

    private static final float WIDTH = 560;
    private static final float BODY_HEIGHT = 430;
    private static final String[] PLACEMENTS = {EffectEdits.CUSTOM_PACKAGE + ".u", "LineageEffect.u"};
    private static final String[] PLACEMENT_TIPS = {
        "All copies go into a small file of their own. The export replaces no large file for them.",
        "Each copy goes into the package of its effect. This rewrites a large file, which every player then needs."
    };
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    private final ImString folder = new ImString(4096);
    private final FolderPicker folderPicker = new FolderPicker(
            "Choose export folder", "Select the folder that gets the export. A dated folder is made in it.", false);
    private final Runnable afterInstall;
    private @Nullable ExportSettings settings;
    private @Nullable EffectEditController controller;
    private boolean opening;
    private boolean shown;
    private Placement placement = Placement.CUSTOM_PACKAGE;
    private @Nullable CompletableFuture<PreparedExport> preparing;
    private @Nullable PreparedExport prepared;
    private @Nullable CompletableFuture<List<Path>> publishing;
    private volatile boolean stopped;
    private @Nullable Path written;
    private int writtenFiles;
    private @Nullable CompletableFuture<Integer> installing;
    private boolean installed;
    private @Nullable CompletableFuture<Integer> restoring;
    private int restoredFiles;
    private String problem = "";

    EffectExportDialog(Runnable afterInstall) {
        this.afterInstall = afterInstall;
    }

    static String filesText(int count) {
        return count == 1 ? "1 file" : count + " files";
    }

    static String size(long bytes) {
        if (bytes >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1e6);
        }
        return bytes >= 1_000 ? String.format(Locale.ROOT, "%d KB", Math.round(bytes / 1e3)) : bytes + " bytes";
    }

    static String restoredText(int files) {
        return "Restored " + filesText(files) + ". The client is as it was before the install.";
    }

    static String importsNote(int count) {
        return count == 1
                ? "1 import was already broken in the client's own package. The export leaves it as it was."
                : count
                        + " imports were already broken in the client's own packages. The export leaves them as they"
                        + " were.";
    }

    static Path newFolder(@Nullable ExportSettings settings, String name) {
        if (settings != null) {
            settings.refresh();
            return settings.getFolder().resolve(stamped(name));
        }
        return ExportSettings.defaultFolder(Path.of(System.getProperty("user.home")))
                .resolve(stamped(name));
    }

    static Path parentOf(String typedFolder, @Nullable ExportSettings settings, String name) {
        try {
            var typed = Path.of(typedFolder);
            return typed.getParent() == null ? typed : typed.getParent();
        } catch (InvalidPathException invalid) {
            return newFolder(settings, name).getParent();
        }
    }

    static String stamped(String name) {
        return name + " " + STAMP.format(LocalDateTime.now());
    }

    static String message(Throwable failure) {
        var cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    void setSettings(@Nullable ExportSettings exportSettings) {
        settings = exportSettings;
    }

    void open(EffectEditController edits) {
        controller = edits;
        written = null;
        writtenFiles = 0;
        installed = false;
        restoredFiles = 0;
        problem = "";
        folder.set(newFolder(settings, NAME).toString());
        prepare();
        opening = true;
    }

    boolean isOpen() {
        return opening || shown;
    }

    void draw(@Nullable FontManager fonts) {
        if (controller == null || fonts == null) {
            return;
        }
        if (opening) {
            opening = false;
            ImGui.openPopup(TITLE);
        }
        var io = ImGui.getIO();
        var body = Math.min(BODY_HEIGHT, io.getDisplaySizeY() - 32 - GeodataWidgets.DIALOG_CHROME);
        var width = Math.min(WIDTH + 2 * GeodataWidgets.DIALOG_SIDE, io.getDisplaySizeX() - 32);
        ImGui.setNextWindowPos(io.getDisplaySizeX() * .5f, io.getDisplaySizeY() * .5f, ImGuiCond.Always, .5f, .5f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, GeodataWidgets.DIALOG_SIDE, GeodataWidgets.DIALOG_EDGE);
        var visible = GeodataWidgets.beginDialog(TITLE, width, body + GeodataWidgets.DIALOG_CHROME);
        ImGui.popStyleVar();
        shown = visible;
        if (!visible) {
            return;
        }
        Motion.dialog();
        poll();
        var typing = ImGui.isAnyItemActive();
        if (GeodataWidgets.dialogTitle(fonts, TITLE, "close-export-effects") && !isBusy()) {
            ImGui.closeCurrentPopup();
        }
        var top = ImGui.getCursorPosY();
        GeodataWidgets.beginDialogPanel("##effect-export-body", width - 2 * GeodataWidgets.DIALOG_SIDE, body);
        drawBody();
        GeodataWidgets.endDialogPanel();
        GeodataWidgets.dialogRule(top + body);
        drawFooter();
        if (!isBusy() && (!typing && GeodataWidgets.isEscapePressed() || GeodataWidgets.wasClickedOutside())) {
            ImGui.closeCurrentPopup();
        }
        var chosen = folderPicker.draw();
        if (chosen != null) {
            folder.set(chosen.resolve(stamped(NAME)).toString());
        }
        ImGui.endPopup();
    }

    private boolean isBusy() {
        return preparing != null || publishing != null || installing != null || restoring != null;
    }

    private void prepare() {
        problem = "";
        prepared = null;
        stopped = false;
        var running = controller;
        if (running != null) {
            preparing = running.prepareExport(placement);
        }
    }

    private void poll() {
        if (preparing != null && preparing.isDone()) {
            var done = preparing;
            preparing = null;
            try {
                prepared = done.join();
            } catch (CompletionException | CancellationException failure) {
                problem = message(failure);
            }
        }
        if (publishing != null && publishing.isDone()) {
            var done = publishing;
            publishing = null;
            try {
                var files = done.join();
                writtenFiles = files.size();
                written = Path.of(folder.get());
                rememberFolder();
            } catch (CompletionException | CancellationException failure) {
                problem = message(failure);
            }
        }
        if (installing != null && installing.isDone()) {
            var done = installing;
            installing = null;
            try {
                done.join();
                installed = true;
                restoredFiles = 0;
                if (controller != null) {
                    controller.installed();
                }
                afterInstall.run();
            } catch (CompletionException | CancellationException failure) {
                problem = message(failure);
            }
        }
        if (restoring != null && restoring.isDone()) {
            var done = restoring;
            restoring = null;
            try {
                restoredFiles = done.join();
                installed = false;
                if (controller != null) {
                    controller.restored();
                }
                afterInstall.run();
            } catch (CompletionException | CancellationException failure) {
                problem = message(failure);
            }
        }
    }

    private void drawBody() {
        var inner = ImGui.getContentRegionAvailX();
        if (written != null) {
            drawResult();
            GeodataWidgets.section("FILES");
        } else {
            GeodataWidgets.section("FILES", 0);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var ready = prepared;
        if (preparing != null) {
            if (GeodataWidgets.progress(
                    "effect-export-prepare",
                    "Building the files",
                    "Reading the packages and checking what they use",
                    -1,
                    "Stop")) {
                stopped = true;
                preparing.cancel(false);
                preparing = null;
                ImGui.closeCurrentPopup();
            }
        } else if (ready != null) {
            for (var item : ready.plan().getPackages()) {
                drawFile(item.path(), ready.plan().getSize(item), item.origin(), inner);
            }
            for (var error : ready.plan().getErrors()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.note(error, GeodataWidgets.BLOCKED);
            }
            var notes = ready.plan().getNotes();
            if (!notes.isEmpty()) {
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint(importsNote(notes.size()));
                GeodataWidgets.tip(String.join("\n", notes.subList(0, Math.min(12, notes.size()))));
            }
            if (controller != null) {
                for (var text : controller.getImageProblems()) {
                    GeodataWidgets.space(GeodataWidgets.ROW);
                    GeodataWidgets.note(text, GeodataWidgets.WARNING);
                }
            }
        }
        if (written == null) {
            if (controller != null && !controller.getEdits().copies().isEmpty()) {
                GeodataWidgets.section("PUT COPIES IN");
                GeodataWidgets.space(GeodataWidgets.ROW);
                drawPlacement(inner);
            }
            GeodataWidgets.section("FOLDER");
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.pathField(
                    "effect-export-folder",
                    "Folder to write",
                    folder,
                    "A new folder outside the game client. System and Textures folders and a manifest go into it. The"
                            + " client does not change.")) {
                folderPicker.open(parentOf(folder.get(), settings, NAME));
            }
        }
        if (publishing != null) {
            GeodataWidgets.space(GeodataWidgets.BLOCK);
            if (GeodataWidgets.progress("effect-export-write", "Writing the files", folder.get(), -1, "Stop")) {
                stopped = true;
            }
        }
        if (!problem.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(problem, GeodataWidgets.BLOCKED);
        }
    }

    private void drawPlacement(float width) {
        var left = ImGui.getCursorScreenPosX();
        var picked = GeodataWidgets.segmented("effect-export-placement", PLACEMENTS, placement.ordinal(), width);
        if (ImGui.isItemHovered()) {
            GeodataWidgets.tip(PLACEMENT_TIPS[GeodataWidgets.hoveredSegment(left, width, PLACEMENTS.length)]);
        }
        if (picked >= 0 && picked != placement.ordinal() && !isBusy()) {
            placement = Placement.values()[picked];
            prepare();
        }
    }

    private void drawResult() {
        var path = written;
        if (path == null) {
            return;
        }
        GeodataWidgets.section("RESULT", 0);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.writtenResult(
                "effect-export-written",
                "Wrote " + filesText(writtenFiles) + " and a manifest.",
                GeodataWidgets.OPEN,
                path);
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint("Players need every file of the folder, in the same folders of their client.");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        if (installing != null) {
            GeodataWidgets.note("Installing into the client…", GeodataWidgets.ACCENT);
        } else if (restoring != null) {
            GeodataWidgets.note("Restoring the client…", GeodataWidgets.ACCENT);
        } else if (installed) {
            GeodataWidgets.note(
                    "Installed. The client's packages now hold these edits, and the replaced files are kept in the"
                            + " folder.",
                    GeodataWidgets.OPEN);
            GeodataWidgets.space(GeodataWidgets.ROW);
            if (GeodataWidgets.actionButton("effect-export-restore-button", Glyph.UNDO, "Restore client", -1, false)) {
                restore(path);
            }
            GeodataWidgets.tip(
                    "Put back the client files that the install replaced, and remove the packages it added. Refused"
                            + " when an installed file changed later. The saved edits do not come back, but the export"
                            + " folder still holds the changed files.");
        } else {
            if (restoredFiles > 0) {
                GeodataWidgets.note(restoredText(restoredFiles), GeodataWidgets.OPEN);
                GeodataWidgets.space(GeodataWidgets.ROW);
                GeodataWidgets.hint(
                        "The install cleared the saved edits, so they do not come back. The export folder still holds"
                                + " the changed files.");
                GeodataWidgets.space(GeodataWidgets.ROW);
            }
            if (GeodataWidgets.actionButton("effect-export-install-button", null, "Install into client", -1, false)) {
                install(path);
            }
            GeodataWidgets.tip(
                    "Copy these files into the client you are editing. The files it replaces are kept in the folder"
                            + " under client-backup. Close the game first: a file in use cannot be replaced.");
        }
    }

    private void install(Path path) {
        var running = controller;
        if (running == null) {
            return;
        }
        problem = "";
        var client = running.getClientRoot();
        installing = CompletableFuture.supplyAsync(() -> {
            try {
                var plan = ClientInstaller.inspect(path, client);
                var reason = plan.problem();
                if (reason != null) {
                    throw new IOException(reason);
                }
                return ClientInstaller.install(plan).size();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void restore(Path path) {
        problem = "";
        restoring = CompletableFuture.supplyAsync(() -> {
            try {
                return ClientInstaller.restore(path).size();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void drawFooter() {
        var buttons = new ArrayList<FooterButton>();
        var ready = prepared;
        var canWrite = ready != null
                && ready.plan().isValid()
                && written == null
                && !isBusy()
                && !folder.get().isBlank();
        if (written == null) {
            buttons.add(new FooterButton(
                    "effect-export-write-button",
                    null,
                    "Export",
                    96,
                    true,
                    canWrite,
                    "Write the files into the new folder. The client does not change."));
        }
        buttons.add(new FooterButton(
                "effect-export-done", null, written == null ? "Cancel" : "Done", 96, false, !isBusy(), ""));
        var status = statusText();
        var pressed =
                GeodataWidgets.footer(status, written != null ? GeodataWidgets.OPEN : GeodataWidgets.MUTED, buttons);
        if (pressed < 0) {
            return;
        }
        if (buttons.get(pressed).id().equals("effect-export-done")) {
            ImGui.closeCurrentPopup();
        } else {
            write();
        }
    }

    private String statusText() {
        if (written != null) {
            return "Exported";
        }
        var ready = prepared;
        if (preparing != null || ready == null) {
            return "";
        }
        var bytes = 0L;
        for (var item : ready.plan().getPackages()) {
            bytes += ready.plan().getSize(item);
        }
        return filesText(ready.plan().getPackages().size()) + " · " + size(bytes);
    }

    private void write() {
        var ready = prepared;
        var running = controller;
        if (ready == null || running == null) {
            return;
        }
        problem = "";
        stopped = false;
        try {
            publishing = running.publish(ready, Path.of(folder.get()), () -> stopped);
        } catch (InvalidPathException invalid) {
            problem = "This folder cannot be used: " + invalid.getReason();
        }
    }

    private void drawFile(String path, long bytes, String origin, float width) {
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var right = size(bytes) + " · " + (origin.equals("replaced") ? "replaces the client's file" : "new");
        var rightWidth = ImGui.calcTextSizeX(right);
        draw.addText(
                x, y, GeodataWidgets.TEXT, HudWidgets.truncateToWidth(path, width - rightWidth - GeodataWidgets.BLOCK));
        draw.addText(x + width - rightWidth, y, GeodataWidgets.MUTED, right);
        ImGui.dummy(width, ImGui.getFontSize());
    }

    private void rememberFolder() {
        var parent = written == null ? null : written.getParent();
        if (settings != null && parent != null) {
            settings.setFolder(parent);
        }
    }
}
