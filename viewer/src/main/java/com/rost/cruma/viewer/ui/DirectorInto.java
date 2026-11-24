package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.editing.LobbyEdits;
import com.rost.cruma.core.editing.LobbySceneCheck;
import com.rost.cruma.core.editing.LobbySceneCheck.Change;
import com.rost.cruma.core.editing.LobbySceneCheck.SceneChange;
import com.rost.cruma.core.editing.SceneCheck;
import com.rost.cruma.core.editing.SceneCheck.PathError;
import com.rost.cruma.core.editing.SceneClassWriter.Loss;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.ClientInstallPanel;
import com.rost.cruma.viewer.editing.SceneInto;
import com.rost.cruma.viewer.editing.SceneInto.Prepared;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.type.ImString;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class DirectorInto {
    static final String SCENE_WARNING =
            "This changes System/LineageSceneInfo.u of the client. No client has run such a file yet, so nobody knows"
                    + " if the game takes it. Use a copy of the client. Restore client puts the file back.";

    private static final int MOST_ROWS = 8;
    private static final String RESTORE_FIRST =
            "A file of this panel stands in the client. Press Restore client first, so that a new file starts from the"
                    + " client's own file.";

    private final ImString folder = new ImString(4096);
    private final ImString targetFilter = new ImString(64);
    private final FolderPicker folderPicker = new FolderPicker(
            "Choose export folder", "Select the folder that gets the files. A dated folder is made in it.", false);
    private final ClientInstallPanel install = new ClientInstallPanel("Install into the client", SCENE_WARNING);
    private @Nullable ExportSettings settings;
    private Predicate<String> mapEdits = _ -> false;
    private Runnable clientChanged = () -> {};
    private Work work = new ClientWork(Path.of("."), WorldVariant.MAIN);
    private volatile List<String> classes = List.of();
    private boolean open;
    private @Nullable CompletableFuture<Prepared> checking;
    private @Nullable Prepared prepared;
    private @Nullable CompletableFuture<List<Path>> writing;
    private @Nullable Path writingTo;
    private volatile boolean stopped;
    private @Nullable Path written;
    private @Nullable SceneDocument writtenOf;
    private int writtenFiles;
    private int installChanges;
    private String problem = "";
    private float cardLeft;
    private float cardTop;
    private float targetTop;
    private float checkTop;
    private float clientViewTop;
    private float writeTop;
    private float folderTop;

    interface Work {
        Path client();

        Prepared scene(SceneDocument document, String target) throws IOException;

        Prepared lobby(SceneDocument document, boolean changedInEdit) throws IOException;
    }

    static String editNote(String map) {
        return "The map " + map
                + " has changes in Edit too. One export cannot hold two files of one map, so Cruma refuses this. Save"
                + " or drop the changes in Edit first.";
    }

    static String mapWarning(SceneDocument document) {
        return "This changes the map " + document.map()
                + " of the client. No client has run such a map yet, so nobody knows if the game takes it. Use a copy"
                + " of the client. Restore client puts the file back.";
    }

    static String lengthNote(SceneCheck check) {
        var plays = String.format(Locale.ROOT, "Plays %.1f s", check.seconds());
        var original = String.format(Locale.ROOT, "%.1f s", check.originalSeconds());
        return check.runsLonger()
                ? plays
                        + ", and "
                        + check.scene()
                        + " "
                        + original
                        + ". play_time of ScenePlayerData.dat may cut it, and nobody knows if the client reads that"
                        + " number."
                : plays + ", no longer than the " + original + " of " + check.scene() + ".";
    }

    static String lossLine(Loss loss) {
        return String.format(
                Locale.ROOT, "%.2f s · %s %s: %s", loss.seconds(), loss.part(), loss.field(), loss.value());
    }

    static String errorLine(PathError error) {
        return String.format(
                Locale.ROOT,
                "%d steps a second: %.0f units and %.2f° at worst",
                error.rate(),
                error.units(),
                error.degrees());
    }

    static String worstError(List<PathError> errors) {
        var units = 0.0;
        var degrees = 0.0;
        var fov = 0.0;
        var roll = 0.0;
        var start = 0.0;
        for (var error : errors) {
            units = Math.max(units, error.units());
            degrees = Math.max(degrees, error.degrees());
            fov = Math.max(fov, error.fovDegrees());
            roll = Math.max(roll, error.rollDegrees());
            start = Math.max(start, error.startDegrees());
        }
        var text = String.format(
                Locale.ROOT,
                "Off the path by %.0f units and %.2f° at worst, by %.1f° of field of view and by %.1f° of roll.",
                units,
                degrees,
                fov,
                roll);
        return start <= degrees
                ? text
                : text
                        + String.format(
                                Locale.ROOT,
                                " The first frames look up to %.0f° away, as the camera takes its Rel angles a frame"
                                        + " after a new target.",
                                start);
    }

    static String reachNote(List<String> tags) {
        var shown = String.join(", ", tags.subList(0, Math.min(tags.size(), MOST_ROWS)));
        var more = tags.size() > MOST_ROWS ? " and " + (tags.size() - MOST_ROWS) + " more" : "";
        return (tags.size() == 1
                        ? "This also changes 1 other scene: "
                        : "This also changes " + tags.size() + " other scenes: ")
                + shown
                + more
                + ".";
    }

    static String sceneLine(SceneChange change) {
        return String.format(
                Locale.ROOT,
                "%s: %.2f s to %.2f s%s",
                change.tag(),
                change.before().seconds(),
                change.after().seconds(),
                change.asked() ? "" : ", through a shared object");
    }

    static String changeLine(Change change) {
        var name = change.object();
        var dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(dot + 1) : name)
                + " "
                + change.field()
                + ": "
                + change.before()
                + " to "
                + change.after();
    }

    void setClient(Path root, WorldVariant variant) {
        work = new ClientWork(root, variant);
    }

    void setHost(ExportSettings exports, Predicate<String> edits, Runnable changed) {
        settings = exports;
        mapEdits = edits;
        clientChanged = changed;
    }

    void setClasses(List<String> names) {
        classes = List.copyOf(names);
    }

    void draw(DirectorSession session) {
        var document = session.getDocument();
        if (document == null) {
            return;
        }
        poll();
        var map = !document.map().isEmpty();
        space(GeodataWidgets.SECTION);
        cardLeft = ImGui.getCursorScreenPosX();
        cardTop = ImGui.getCursorScreenPosY();
        var color = cardColor(document);
        open = GeodataWidgets.disclosureCard(
                "director-into",
                Glyph.FILE,
                map ? "Into the map" : "Into the client",
                cardDetail(document),
                color,
                open);
        GeodataWidgets.tip(
                map
                        ? "Writes this copy back into the file of its map."
                        : "Writes this scene over a scene of the client, as a file.");
        if (!open) {
            return;
        }
        space(GeodataWidgets.ROW);
        if (map) {
            drawMap(session, document);
        } else {
            drawClient(session, document);
        }
        var chosen = folderPicker.draw();
        if (chosen != null) {
            folder.set(
                    chosen.resolve(EffectExportDialog.stamped(document.name())).toString());
        }
    }

    private int cardColor(SceneDocument document) {
        var ready = prepared;
        if (ready == null || !ready.document().equals(document)) {
            return GeodataWidgets.MUTED;
        }
        return ready.canWrite() ? GeodataWidgets.OPEN : GeodataWidgets.WARNING;
    }

    private String cardDetail(SceneDocument document) {
        if (!document.map().isEmpty()) {
            return document.basedOn().isEmpty()
                    ? "Only a copy of a scene of the map"
                    : document.basedOn() + " of " + document.map();
        }
        return hasClass(document.target()) ? "Replaces " + document.target() : "Pick a scene to replace";
    }

    private void drawClient(DirectorSession session, SceneDocument document) {
        if (classes.isEmpty()) {
            GeodataWidgets.hint(
                    session.getMap().isEmpty()
                            ? "This client has no cut scenes, so a scene has no place to go. A number of its own is"
                                    + " not offered."
                            : "This scene stands in the world, and the Viewer reads the cut scenes of the client"
                                    + " there. Open it in the world to take it into the client.");
            return;
        }
        drawTarget(session, document);
        var known = hasClass(document.target());
        space(GeodataWidgets.ROW);
        drawCheck(
                session,
                document,
                known,
                known
                        ? "Writes nothing. It says what the file would hold, what the client lacks and how far shots"
                                + " stay from the path."
                        : "Pick the scene it replaces first.");
        var ready = fresh(document);
        if (ready != null && ready.check() != null) {
            drawSceneCheck(ready, ready.check());
        }
        drawAsClient(session, document, ready);
        drawOutput(document, ready, SCENE_WARNING);
    }

    private void drawTarget(DirectorSession session, SceneDocument document) {
        targetTop = ImGui.getCursorScreenPosY();
        if (document.replaces().isEmpty() && hasClass(document.basedOn())) {
            GeodataWidgets.value("Replaces", document.basedOn());
            GeodataWidgets.tip(
                    "A copy goes over the scene it comes from, with its name and its number. A new number is not"
                            + " offered.");
            return;
        }
        ImGui.textColored(GeodataWidgets.MUTED, "Replaces");
        targetTop = ImGui.getCursorScreenPosY();
        var target = document.target();
        var filter = GeodataWidgets.searchList(
                "##director-into-target",
                hasClass(target) ? target : "Pick a scene of the client",
                targetFilter,
                "Search scenes by name",
                ImGui.getContentRegionAvailX());
        if (filter != null) {
            var words = filter.trim().toLowerCase(Locale.ROOT);
            for (var name : classes) {
                if (name.toLowerCase(Locale.ROOT).contains(words)
                        && GeodataWidgets.listItem(name, name, "", name.equals(target))) {
                    session.setReplaces(name);
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "The scene of the client that this one goes over. It keeps that name and that number, as there are no"
                        + " new numbers.");
    }

    private boolean hasClass(String name) {
        for (var known : classes) {
            if (known.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void drawMap(DirectorSession session, SceneDocument document) {
        if (document.basedOn().isEmpty()) {
            GeodataWidgets.hint(
                    "The game picks a scene of a lobby by its Tag, so a new scene would never play. This one can go"
                            + " into a video.");
            return;
        }
        var edits = mapEdits.test(document.map());
        if (edits) {
            GeodataWidgets.note(editNote(document.map()), GeodataWidgets.WARNING);
            space(GeodataWidgets.ROW);
        }
        drawCheck(
                session,
                document,
                !edits,
                edits
                        ? "Save or drop the changes in Edit first."
                        : "Writes nothing. It says which scenes of the map change, and what cannot be written.");
        var ready = fresh(document);
        if (ready != null && ready.lobby() != null) {
            drawLobbyCheck(ready, ready.lobby(), edits);
        }
        drawOutput(document, ready, mapWarning(document));
    }

    private void drawCheck(DirectorSession session, SceneDocument document, boolean can, String tip) {
        var running = checking;
        if (running != null) {
            if (GeodataWidgets.progress(
                    "director-into-check", "Checking", "Writing the file in memory and reading it back", -1, "Stop")) {
                running.cancel(false);
                checking = null;
            }
            return;
        }
        checkTop = ImGui.getCursorScreenPosY();
        var installed = install.getInstalledIn() != null;
        ImGui.beginDisabled(!can || installed);
        if (GeodataWidgets.actionButton(
                "director-into-check-button", null, "Check", ImGui.getContentRegionAvailX(), fresh(document) == null)) {
            check(session.getDocument());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(installed ? RESTORE_FIRST : tip);
        if (installed) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint(RESTORE_FIRST);
        }
        if (prepared != null && fresh(document) == null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("The scene changed after this check, so check it again.");
        }
        if (!problem.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(problem, GeodataWidgets.BLOCKED);
        }
    }

    private void check(SceneDocument document) {
        problem = "";
        var running = work;
        var edits = mapEdits.test(document.map());
        checking = CompletableFuture.supplyAsync(() -> {
            try {
                return document.map().isEmpty()
                        ? running.scene(document, document.target())
                        : running.lobby(document, edits);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private @Nullable Prepared fresh(SceneDocument document) {
        var ready = prepared;
        return ready != null && ready.document().equals(document) ? ready : null;
    }

    private void drawSceneCheck(Prepared ready, SceneCheck check) {
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(lengthNote(check), check.runsLonger() ? GeodataWidgets.WARNING : GeodataWidgets.MUTED);
        if (!ready.keptObjects()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("This scene comes from another file or class, so all its items go in as new objects.");
        }
        var lost = new ArrayList<String>(check.lost().size());
        for (var loss : check.lost()) {
            lost.add(lossLine(loss));
        }
        rows(
                "WHAT THE FILE WILL NOT HOLD",
                lost,
                GeodataWidgets.MUTED,
                "Fields and orders this client has no place for, and shots that a path takes the place of.");
        var lacks = new ArrayList<String>(check.lacks().size());
        for (var lack : check.lacks()) {
            lacks.add(String.format(Locale.ROOT, "%.2f s · %s", lack.seconds(), SceneInto.text(lack)));
        }
        rows(
                "WHAT THE CLIENT LACKS",
                lacks,
                GeodataWidgets.WARNING,
                "The scene asks for these, and this client does not have them. The client leaves such an order out.");
        var marks = new ArrayList<String>(check.marks().size());
        for (var mark : check.marks()) {
            marks.add(String.format(Locale.ROOT, "%.2f s · %s of NPC %d", mark.seconds(), mark.sequence(), mark.npc()));
        }
        rows(
                "MARKS THAT STILL RUN",
                marks,
                GeodataWidgets.WARNING,
                "A mark in these animations takes the camera in the client, also from the shots of a path, as the"
                        + " client has no path.");
        for (var note : ready.notes()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(note, GeodataWidgets.WARNING);
        }
    }

    private void rows(String title, List<String> rows, int color, String tip) {
        if (rows.isEmpty()) {
            return;
        }
        GeodataWidgets.section(title + " · " + rows.size());
        GeodataWidgets.tip(tip);
        space(GeodataWidgets.ROW);
        var shown = Math.min(rows.size(), MOST_ROWS);
        for (var index = 0; index < shown; index++) {
            GeodataWidgets.note(rows.get(index), color);
        }
        if (rows.size() > shown) {
            GeodataWidgets.hint("And " + (rows.size() - shown) + " more.");
            GeodataWidgets.tip(String.join("\n", rows.subList(shown, Math.min(rows.size(), shown + 3 * MOST_ROWS))));
        }
    }

    private void drawAsClient(DirectorSession session, SceneDocument document, @Nullable Prepared ready) {
        clientViewTop = 0;
        if (document.path() == null) {
            return;
        }
        GeodataWidgets.section("AS THE CLIENT PLAYS");
        GeodataWidgets.tip(
                "A path has no form in the client. It goes in as shots around an invisible NPC, about two a second, in"
                        + " whole numbers.");
        space(GeodataWidgets.ROW);
        clientViewTop = ImGui.getCursorScreenPosY();
        var on = HudWidgets.switchRow(
                "As the client plays",
                session.isAsClient(),
                true,
                "Shows the shots in place of the smooth path. A video keeps the smooth path. This changes nothing in"
                        + " the scene.");
        if (on != session.isAsClient()) {
            session.setAsClient(on);
        }
        var check = ready == null ? null : ready.check();
        if (check == null || check.path().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("Check says how far the shots stay from the path.");
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(worstError(check.path()), GeodataWidgets.MUTED);
        for (var error : check.path()) {
            GeodataWidgets.hint(errorLine(error));
        }
        GeodataWidgets.tip(
                "Measured with Cruma's reading of the client's camera, at three rates, as the rate of the game is not"
                        + " known. These are not numbers from the client.");
    }

    private void drawLobbyCheck(Prepared ready, LobbyEdits edits, boolean inEdit) {
        var check = edits.check();
        var reached = check.alsoChanged();
        if (!reached.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(reachNote(reached), GeodataWidgets.WARNING);
            GeodataWidgets.tip(
                    "Scenes share the objects of the map, and no object can be copied, as that would be a new actor."
                            + " So a change reaches each scene on that object.");
        }
        var refused = new ArrayList<>(edits.refused());
        for (var refusal : check.refused()) {
            refused.add(refusal.reason());
        }
        rows("REFUSED", refused, GeodataWidgets.BLOCKED, "With one of these nothing is written.");
        for (var note : edits.notes()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(note, GeodataWidgets.WARNING);
        }
        for (var note : ready.notes()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(note, GeodataWidgets.WARNING);
        }
        if (!edits.stays().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint(
                    "Stays in the video, as a map has no place for it: " + String.join(", ", edits.stays()) + ".");
        }
        if (refused.isEmpty() && edits.edits().isEmpty() && !inEdit) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("This copy holds the numbers of the map, so there is nothing to write.");
            return;
        }
        var scenes = new ArrayList<String>(check.scenes().size());
        for (var change : check.scenes()) {
            scenes.add(sceneLine(change));
        }
        rows(
                "SCENES THAT CHANGE",
                scenes,
                GeodataWidgets.MUTED,
                "How long each scene takes by its own time, before and after.");
        var changes = new ArrayList<String>(check.changes().size());
        for (var change : check.changes()) {
            changes.add(changeLine(change));
        }
        rows(
                "NUMBERS THAT CHANGE",
                changes,
                GeodataWidgets.MUTED,
                "The tags of the map, with what they hold and what they will hold.");
        if (!check.changes().isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint(placeNote(check));
        }
    }

    private void drawOutput(SceneDocument document, @Nullable Prepared ready, String warning) {
        writeTop = 0;
        var can = ready != null && ready.canWrite();
        if (ready != null && ready.plan() != null) {
            for (var error : ready.plan().getErrors()) {
                space(GeodataWidgets.ROW);
                GeodataWidgets.note(error, GeodataWidgets.BLOCKED);
            }
        }
        var path = written;
        if (can && !document.equals(writtenOf) || writing != null) {
            GeodataWidgets.section("FOLDER");
            GeodataWidgets.tip("Where the file goes first. The client does not change.");
            space(GeodataWidgets.ROW);
            if (folder.get().isBlank()) {
                folder.set(
                        EffectExportDialog.newFolder(settings, document.name()).toString());
            }
            folderTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.pathField(
                    "director-into-folder",
                    "Folder to write",
                    folder,
                    "A new folder outside the game client. It gets the file in the folders of the client, and a"
                            + " manifest.")) {
                folderPicker.open(EffectExportDialog.parentOf(folder.get(), settings, document.name()));
            }
            space(GeodataWidgets.ROW);
            drawWrite(can);
        }
        if (path == null) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.writtenResult(
                "director-into-written",
                "Wrote " + EffectExportDialog.filesText(writtenFiles)
                        + " and a manifest. The file reads back in Cruma, and the client has not changed.",
                GeodataWidgets.OPEN,
                path);
        GeodataWidgets.section("CLIENT");
        GeodataWidgets.tip("Nobody has run such a file in a game.");
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(warning, GeodataWidgets.WARNING);
        space(GeodataWidgets.ROW);
        install.setWarning(warning);
        install.drawClientField(work.client());
        space(GeodataWidgets.ROW);
        install.drawState(path, writing != null);
        if (install.getChanges() != installChanges) {
            installChanges = install.getChanges();
            prepared = null;
            clientChanged.run();
        }
    }

    private void drawWrite(boolean can) {
        var running = writing;
        if (running != null) {
            if (GeodataWidgets.progress("director-into-write", "Writing the file", folder.get(), -1, "Stop")) {
                stopped = true;
            }
            return;
        }
        writeTop = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!can || folder.get().isBlank());
        if (GeodataWidgets.actionButton(
                "director-into-write-button", null, "Write the file", ImGui.getContentRegionAvailX(), true)) {
            write();
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                can ? "Writes the file into the new folder. The client does not change." : "Check the scene first.");
    }

    private void write() {
        var ready = prepared;
        if (ready == null || ready.service() == null || ready.plan() == null) {
            return;
        }
        problem = "";
        stopped = false;
        Path target;
        try {
            target = Path.of(folder.get());
        } catch (InvalidPathException invalid) {
            problem = "This folder cannot be used: " + invalid.getReason();
            return;
        }
        var service = ready.service();
        var plan = ready.plan();
        writingTo = target;
        writing = CompletableFuture.supplyAsync(() -> {
            try {
                return service.publish(plan, target, () -> stopped);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void poll() {
        if (checking != null && checking.isDone()) {
            var done = checking;
            checking = null;
            try {
                prepared = done.join();
            } catch (CompletionException | CancellationException failure) {
                problem = EffectExportDialog.message(failure);
            }
        }
        if (writing != null && writing.isDone()) {
            var done = writing;
            writing = null;
            try {
                writtenFiles = done.join().size();
                written = writingTo;
                writtenOf = prepared == null ? null : prepared.document();
                folder.set("");
                var parent = written == null ? null : written.getParent();
                if (settings != null && parent != null) {
                    settings.setFolder(parent);
                }
            } catch (CompletionException | CancellationException failure) {
                problem = EffectExportDialog.message(failure);
            }
        }
    }

    private record ClientWork(Path client, WorldVariant variant) implements Work {
        @Override
        public Prepared scene(SceneDocument document, String target) throws IOException {
            return SceneInto.scene(client, variant, document, target);
        }

        @Override
        public Prepared lobby(SceneDocument document, boolean changedInEdit) throws IOException {
            return SceneInto.lobby(client, document, changedInEdit);
        }
    }

    private static String placeNote(LobbySceneCheck check) {
        return check.grown() == 0
                ? "All objects keep their size, so the file keeps its size."
                : check.grown()
                        + (check.grown() == 1 ? " object needs" : " objects need")
                        + " a new tag, so the file grows and its tables are written again.";
    }
}
