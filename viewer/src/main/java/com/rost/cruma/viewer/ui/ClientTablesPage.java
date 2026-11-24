package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.schema.TableReport;
import com.rost.cruma.core.schema.TableReport.Outcome;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.schema.Trace;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.ClientTables.Check;
import com.rost.cruma.viewer.launch.ClientTables.Key;
import com.rost.cruma.viewer.launch.ClientTables.TraceLine;
import com.rost.cruma.viewer.launch.FileManager;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiChildFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

public final class ClientTablesPage {
    public static final String TITLE = LaunchOptionCatalog.TABLES;

    private static final int TRACE_ROWS = 12;
    private static final float TYPE_WIDTH = 104;
    private static final float BYTE_WIDTH = 64;
    private static final float CHEVRON = 24;
    private static final String CREATE_POPUP = "##tables-create-client-folder";
    private static final ExecutorService saver = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "cruma-save-schemas");
        thread.setDaemon(true);
        return thread;
    });

    private final Path configDirectory;
    private final Map<String, float[]> places;
    private final Set<String> open = new HashSet<>();
    private final Map<String, TraceView> traces = new HashMap<>();
    private final FolderPicker savePicker =
            new FolderPicker("Save built-in schemas", "Choose the folder to write the built-in schemas into.", false);
    private final ImGuiListClipper clipper = new ImGuiListClipper();
    private final LatestAnswer<Key, Check> last = new LatestAnswer<>(ClientTables::findLast, null);
    private final LatestAnswer<WorldVariant, Key> keys;
    private @Nullable Key shownKey;
    private boolean checkAsked;
    private @Nullable Check tracedCheck;
    private boolean wasChecking;
    private @Nullable CompletableFuture<Saved> saving;
    private @Nullable Saved saved;

    private record Saved(String text, int color, @Nullable Path folder) {}

    private static final class TraceView {
        final ImInt row;
        final String title;
        final String source;
        final LatestAnswer<TraceQuestion, Trace> answer;

        TraceView(int row, String title, String source) {
            this.row = new ImInt(row);
            this.title = title;
            this.source = source;
            answer = new LatestAnswer<>(
                    question -> TableSchemas.trace(
                            question.spec(),
                            question.key().system(),
                            question.key().variant(),
                            question.title(),
                            question.source(),
                            question.row()),
                    null);
        }
    }

    private record TraceQuestion(Key key, TableSpec spec, String title, String source, int row) {}

    public ClientTablesPage(Path client, Path configDirectory, Map<String, float[]> places) {
        this.configDirectory = configDirectory;
        this.places = places;
        keys = new LatestAnswer<>(variant -> Key.of(client, variant), null);
    }

    public String describeState(WorldVariant variant) {
        var key = keys.get(variant);
        if (key == null) {
            return "…";
        }
        var running = ClientTables.getRunning(key);
        if (running != null) {
            var file = running.getFile();
            return "Checking " + (file.isEmpty() ? "" : file + " · ")
                    + Math.min(running.getDone() + 1, running.getTotal()) + " of " + running.getTotal() + "…";
        }
        if (ClientTables.isQueued(key)) {
            return "Waiting for the check of another client…";
        }
        var shown = findShown(key);
        return shown == null ? "…" : shown.describeCounts();
    }

    public boolean isChecking(WorldVariant variant) {
        var key = keys.get(variant);
        return key == null || ClientTables.getRunning(key) != null || ClientTables.isQueued(key);
    }

    public void checkAgain(WorldVariant variant) {
        var key = keys.get(variant);
        if (key != null) {
            traces.clear();
            ClientTables.start(key);
        }
    }

    public void draw(WorldVariant variant, FontManager fonts) {
        var key = keys.get(variant);
        if (key == null) {
            GeodataWidgets.space(GeodataWidgets.BLOCK);
            ImGui.textColored(GeodataWidgets.FAINT, "…");
            return;
        }
        follow(key);
        var shown = findShown(key);
        drawSummary(key, shown);
        GeodataWidgets.section("TABLES");
        var reports = shown == null ? List.<TableReport>of() : shown.reports();
        for (var spec : Tables.ALL) {
            var report = reportOf(reports, spec);
            drawTable(key, spec, report, fonts);
        }
        if (shown != null
                && (!shown.schemaProblems().isEmpty() || !shown.keyProblems().isEmpty())) {
            drawProblems(key, shown);
        }
        drawFolders(key);
        var chosen = savePicker.draw();
        if (chosen != null) {
            saveBuiltIn(chosen);
        }
    }

    private void follow(Key key) {
        if (!key.equals(shownKey)) {
            shownKey = key;
            checkAsked = false;
            traces.clear();
            tracedCheck = ClientTables.getChecked(key);
        }
        if (!checkAsked && ClientTables.getChecked(key) == null && !isChecking(key)) {
            ClientTables.start(key);
        }
        checkAsked = true;
        var checking = isChecking(key);
        var result = ClientTables.getChecked(key);
        if ((checking && !wasChecking) || result != tracedCheck) {
            traces.clear();
            tracedCheck = result;
        }
        wasChecking = checking;
    }

    private @Nullable Check findShown(Key key) {
        var checked = ClientTables.getChecked(key);
        return checked != null ? checked : last.get(key);
    }

    private void drawSummary(Key key, @Nullable Check shown) {
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var running = ClientTables.getRunning(key);
        var top = ImGui.getCursorScreenPosY();
        if (isChecking(key)) {
            GeodataWidgets.status(describeState(key.variant()), 0);
        } else if (shown == null) {
            GeodataWidgets.status("…", 0);
        } else {
            var troubled = shown.countTroubled() > 0;
            GeodataWidgets.status(
                    shown.describeCounts(),
                    troubled ? GeodataWidgets.WARNING : shown.complete() ? GeodataWidgets.OPEN : 0);
            if (!shown.complete()) {
                GeodataWidgets.tip("The tables read since Cruma started. Check again reads every table.");
            }
        }
        var left = ImGui.getItemRectMinX();
        var y = ImGui.getItemRectMaxY() + 2;
        if (running != null) {
            var draw = ImGui.getWindowDrawList();
            var width = ImGui.getContentRegionAvailX();
            var fraction = running.getDone() / (float) running.getTotal();
            draw.addRectFilled(left, y, left + width, y + 2, GeodataWidgets.DIVIDER, 1f);
            draw.addRectFilled(left, y, left + width * fraction, y + 2, GeodataWidgets.ACCENT, 1f);
        }
        markRow("tables/summary", top);
    }

    private void drawTable(Key key, TableSpec spec, @Nullable TableReport report, FontManager fonts) {
        var file = report == null
                ? ClientTables.missingName(spec)
                : report.file().getFileName().toString();
        var id = spec.id();
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        if (report != null && report.state() == State.NO_FILE) {
            drawMissing(spec, file, x, y, width);
            return;
        }
        var height = SpawnPanelWidgets.listRowHeight();
        var opens = report != null && !report.attempts().isEmpty();
        var isOpen = opens && open.contains(id);
        if (ImGui.invisibleButton("##table/" + id, width, height) && opens) {
            if (!open.remove(id)) {
                open.add(id);
            }
            isOpen = !isOpen;
        }
        var hovered = ImGui.isItemHovered();
        if (hovered && opens) {
            draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.HOVER, 6f);
        }
        places.put("tables/row/" + id, new float[] {x, y, x + width, y + height});
        var state = report == null ? null : report.state();
        var detail = report == null ? "" : ClientTables.describe(report, key.system());
        var stateWord = report == null ? "…" : ClientTables.describeState(state);
        SpawnPanelWidgets.statusRow(
                draw,
                x,
                y,
                width - (opens ? CHEVRON : 0),
                dotOf(state),
                file,
                detail,
                state == State.NOT_READ || state == State.PARTIAL ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                stateWord);
        if (opens) {
            HudWidgets.glyph(
                    draw,
                    isOpen ? Glyph.CHEVRON : Glyph.CHEVRON_RIGHT,
                    x + width - CHEVRON + 2,
                    Math.round(y + (height - 16) * .5f),
                    16,
                    hovered ? HudWidgets.TEXT : GeodataWidgets.MUTED);
        }
        if (hovered) {
            HudWidgets.showTooltip(tipOf(key, spec, report, opens, isOpen));
        }
        if (isOpen) {
            drawAttempts(key, spec, report, fonts);
        }
    }

    private void drawMissing(TableSpec spec, String file, float x, float y, float width) {
        var height = ImGui.getFontSize() + 10;
        ImGui.invisibleButton("##table/" + spec.id(), width, height);
        var draw = ImGui.getWindowDrawList();
        var left = x + SpawnPanelWidgets.listRowTextLeft();
        var right = x + width - (SpawnPanelWidgets.listRowTextLeft() - 14);
        var textTop = y + (height - ImGui.getFontSize()) * .5f - 1;
        draw.addCircleFilled(left - 10, HudWidgets.textMiddle(textTop), 4.5f, GeodataWidgets.FAINT, 14);
        var word = ClientTables.describeState(State.NO_FILE);
        var wordWidth = ImGui.calcTextSizeX(word);
        draw.addText(right - wordWidth, textTop, GeodataWidgets.FAINT, word);
        draw.addText(left, textTop, GeodataWidgets.MUTED, cut(file, right - wordWidth - left - GeodataWidgets.BLOCK));
        places.put("tables/row/" + spec.id(), new float[] {x, y, x + width, y + height});
        if (ImGui.isItemHovered()) {
            HudWidgets.showTooltip("This client has no such table. Without it: " + lowerFirst(spec.lost()));
        }
    }

    private void drawAttempts(Key key, TableSpec spec, TableReport report, FontManager fonts) {
        var id = spec.id();
        var top = ImGui.getCursorScreenPosY();
        var indent = SpawnPanelWidgets.listRowTextLeft();
        ImGui.indent(indent);
        GeodataWidgets.section("SCHEMAS IN THE ORDER CRUMA TRIED THEM", GeodataWidgets.ROW);
        for (var attempt : report.attempts()) {
            var color = ClientTables.isUsed(report, attempt)
                    ? HudWidgets.TEXT
                    : attempt.outcome() == Outcome.SKIPPED ? GeodataWidgets.FAINT : GeodataWidgets.MUTED;
            GeodataWidgets.note(
                    ClientTables.shortenPaths(ClientTables.describe(attempt, key.system()), key.system()), color);
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var closest = ClientTables.findClosest(report);
        var trace = traces.get(id);
        if (closest == null) {
            ImGui.textColored(GeodataWidgets.FAINT, "No schema could be tried, so there is no row to show.");
        } else if (trace == null) {
            var label = "Show row";
            if (HudWidgets.button(
                    "tables-show-row/" + id,
                    null,
                    label,
                    ImGui.calcTextSizeX(label) + 32,
                    HudWidgets.CONTROL_HEIGHT,
                    false,
                    true)) {
                traces.put(
                        id,
                        new TraceView(
                                Math.max(-1, ClientTables.findFailingRow(closest)), closest.title(), closest.source()));
            }
            mark("tables/show-row/" + id);
            GeodataWidgets.tip("Shows every field of a row as " + closest.title() + " · "
                    + ClientTables.describeSource(closest.source(), key.system())
                    + " reads it, with its type, its byte and its value, and the field where it stopped.");
        } else {
            drawTrace(key, spec, trace, fonts);
        }
        ImGui.unindent(indent);
        GeodataWidgets.space(GeodataWidgets.ROW);
        markRow("tables/open/" + id, top);
    }

    private void drawTrace(Key key, TableSpec spec, TraceView view, FontManager fonts) {
        var id = spec.id();
        var row = view.row;
        ImGui.alignTextToFramePadding();
        ImGui.textColored(GeodataWidgets.MUTED, "Row");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        ImGui.setNextItemWidth(GeodataWidgets.INTEGER_WIDTH);
        if (ImGui.inputInt("##tables-row/" + id, row, 1, 10)) {
            row.set(Math.max(-1, row.get()));
        }
        mark("tables/row-field/" + id);
        GeodataWidgets.tip("The row to show, from 0. -1 shows the fields outside the rows, such as the count of rows"
                + " and the bytes after the last row.");
        ImGui.sameLine(0, GeodataWidgets.BLOCK);
        ImGui.alignTextToFramePadding();
        var source = ClientTables.describeSource(view.source, key.system());
        ImGui.textColored(
                GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(
                        "by " + view.title + (source.isEmpty() ? "" : " · " + source), ImGui.getContentRegionAvailX()));
        var trace = view.answer.get(new TraceQuestion(key, spec, view.title, view.source, row.get()));
        GeodataWidgets.space(GeodataWidgets.ROW);
        if (trace == null || trace.row() != row.get()) {
            ImGui.textColored(GeodataWidgets.FAINT, "…");
            return;
        }
        var lines = ClientTables.listTraceLines(trace);
        if (lines.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No field: the table has no such row.");
        } else {
            drawTraceTable(id, lines, fonts);
        }
        var failure = trace.failure();
        if (failure != null) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "Stopped at " + ClientTables.shortenPaths(failure.describe(), key.system()),
                    GeodataWidgets.BLOCKED);
        }
    }

    private void drawTraceTable(String id, List<TraceLine> lines, FontManager fonts) {
        var width = ImGui.getContentRegionAvailX();
        var line = ImGui.getFontSize() + 6;
        var draw = ImGui.getWindowDrawList();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var pathWidth = Math.max(120, (width - TYPE_WIDTH - BYTE_WIDTH) * .5f);
        var columns =
                new float[] {8, 8 + pathWidth, 8 + pathWidth + TYPE_WIDTH, 8 + pathWidth + TYPE_WIDTH + BYTE_WIDTH};
        draw.addRectFilled(x, y, x + width, y + line, GeodataWidgets.CARD, 6f);
        var titles = new String[] {"Field", "Type", "Byte", "Value"};
        for (var index = 0; index < titles.length; index++) {
            draw.addText(x + columns[index], y + 3, GeodataWidgets.MUTED, titles[index]);
        }
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, ImGui.getStyle().getItemSpacingX(), 0);
        ImGui.dummy(width, line);
        var height = Math.min(lines.size(), TRACE_ROWS) * line;
        ImGui.beginChild("##tables-trace/" + id, width, height, ImGuiChildFlags.None, ImGuiWindowFlags.None);
        var inner = ImGui.getContentRegionAvailX();
        ImGui.pushFont(fonts.getMonoFont(), 14f);
        clipper.begin(lines.size(), line);
        while (clipper.step()) {
            for (var index = clipper.getDisplayStart(); index < clipper.getDisplayEnd(); index++) {
                var field = lines.get(index);
                var left = ImGui.getCursorScreenPosX();
                var top = ImGui.getCursorScreenPosY();
                ImGui.invisibleButton("##tables-trace-line/" + id + "/" + index, inner, line);
                var rowDraw = ImGui.getWindowDrawList();
                if (field.failed()) {
                    rowDraw.addRectFilled(left, top, left + inner, top + line, GeodataWidgets.HOVER, 4f);
                }
                var color = field.failed() ? GeodataWidgets.BLOCKED : HudWidgets.TEXT;
                var muted = field.failed() ? GeodataWidgets.BLOCKED : GeodataWidgets.MUTED;
                var text = top + 3;
                var offset = Integer.toString(field.offset());
                rowDraw.addText(left + columns[0], text, color, cut(field.path(), columns[1] - columns[0] - 8));
                rowDraw.addText(left + columns[1], text, muted, cut(field.type(), columns[2] - columns[1] - 8));
                rowDraw.addText(left + columns[2], text, muted, offset);
                rowDraw.addText(left + columns[3], text, color, cut(field.value(), inner - columns[3] - 8));
                if (ImGui.isItemHovered()) {
                    HudWidgets.showTooltip(field.path() + (field.type().isEmpty() ? "" : "\n" + field.type())
                            + "\nByte " + offset + (field.value().isEmpty() ? "" : "\n" + field.value()));
                }
            }
        }
        clipper.end();
        ImGui.popFont();
        ImGui.endChild();
        ImGui.popStyleVar();
        places.put("tables/trace/" + id, new float[] {x, y, x + width, y + line + height});
    }

    private void drawProblems(Key key, Check shown) {
        GeodataWidgets.section("PROBLEMS");
        var top = ImGui.getCursorScreenPosY();
        for (var problem : shown.schemaProblems()) {
            GeodataWidgets.note(ClientTables.shortenPaths(problem, key.system()), GeodataWidgets.WARNING);
            if (ImGui.isItemHovered()) {
                HudWidgets.showTooltip(problem);
            }
        }
        for (var problem : shown.keyProblems()) {
            GeodataWidgets.note(ClientTables.shortenPaths(problem, key.system()), GeodataWidgets.WARNING);
            if (ImGui.isItemHovered()) {
                HudWidgets.showTooltip(problem);
            }
        }
        markRow("tables/problems", top);
    }

    private void drawFolders(Key key) {
        GeodataWidgets.section("SCHEMA FOLDERS");
        var clientFolder = TableSchemas.getClientFolder(key.system());
        var userFolder = ClientTables.userFolderOf(configDirectory);
        var openClient = "Open schemas folder";
        var openUser = "Open my schemas folder";
        var save = "Save built-in schemas";
        var saveWidth = widthOf(save);
        var buttonWidth = Math.max(
                Math.max(widthOf(openClient), widthOf(openUser)) + 26,
                saveWidth + GeodataWidgets.ROW + HudWidgets.CONTROL_HEIGHT);
        var top = ImGui.getCursorScreenPosY();
        GeodataWidgets.beginSettingRow(
                "This client",
                "The cruma/schemas folder beside system. A server can ship it with its client.",
                buttonWidth);
        var buttonLeft = ImGui.getCursorScreenPosX();
        var buttonTop = ImGui.getCursorScreenPosY();
        if (HudWidgets.button(
                "tables-open-client", Glyph.FOLDER, openClient, buttonWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            if (Files.isDirectory(clientFolder)) {
                FileManager.tryOpenFolder(clientFolder);
            } else {
                ImGui.openPopup(CREATE_POPUP);
            }
        }
        mark("tables/open-client");
        GeodataWidgets.tip(clientFolder.toString());
        GeodataWidgets.endSettingRow();
        drawCreateQuestion(clientFolder, buttonLeft + buttonWidth, buttonTop + HudWidgets.CONTROL_HEIGHT);
        markRow("tables/client-folder", top);
        top = ImGui.getCursorScreenPosY();
        GeodataWidgets.beginSettingRow(
                "Every client",
                "The schemas folder of Cruma's settings, also for keys.txt of tables locked with other keys.",
                buttonWidth);
        if (HudWidgets.button(
                "tables-open-user", Glyph.FOLDER, openUser, buttonWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            openUserFolder(userFolder);
        }
        mark("tables/open-user");
        GeodataWidgets.tip(userFolder.toString());
        GeodataWidgets.endSettingRow();
        markRow("tables/user-folder", top);
        top = ImGui.getCursorScreenPosY();
        saveWidth = buttonWidth - GeodataWidgets.ROW - HudWidgets.CONTROL_HEIGHT;
        var busy = saving != null && !saving.isDone();
        GeodataWidgets.beginSettingRow(
                "Built-in schemas",
                "Cruma's own, to read and to start a schema like one of them. Saving replaces the copies in "
                        + ClientTables.BUILT_IN_FOLDER + ".",
                buttonWidth);
        ImGui.beginDisabled(busy);
        if (HudWidgets.button("tables-save-built-in", null, save, saveWidth, HudWidgets.CONTROL_HEIGHT, false, true)) {
            saveBuiltIn(configDirectory.resolve(ClientTables.BUILT_IN_FOLDER));
        }
        mark("tables/save-built-in");
        GeodataWidgets.tip("Writes the built-in schemas into " + ClientTables.BUILT_IN_FOLDER
                + " beside your schemas folder, in place of the copies there. Cruma does not read them from there.");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        if (HudWidgets.iconButton("tables-save-built-in-to", Glyph.FOLDER, false, HudWidgets.MUTED)) {
            savePicker.open(Files.isDirectory(configDirectory) ? configDirectory : null);
        }
        ImGui.endDisabled();
        mark("tables/save-built-in-to");
        GeodataWidgets.tip("Save built-in schemas into another folder…");
        GeodataWidgets.endSettingRow();
        markRow("tables/built-in", top);
        drawSaved(busy);
    }

    private void drawSaved(boolean busy) {
        var future = saving;
        if (future != null && future.isDone()) {
            saved = future.join();
            saving = null;
        }
        var shown = busy ? new Saved("Saving the built-in schemas…", GeodataWidgets.MUTED, null) : saved;
        if (shown == null) {
            return;
        }
        GeodataWidgets.space(GeodataWidgets.ROW);
        var resultTop = ImGui.getCursorScreenPosY();
        GeodataWidgets.writtenResult("tables-saved-folder", shown.text(), shown.color(), shown.folder());
        markRow(busy ? "tables/saving" : "tables/saved", resultTop);
    }

    private void drawCreateQuestion(Path folder, float right, float bottom) {
        if (!HudWidgets.beginPopupBelow(CREATE_POPUP, 360, right, bottom)) {
            return;
        }
        var top = ImGui.getCursorScreenPosY();
        var left = ImGui.getCursorScreenPosX();
        ImGui.textColored(HudWidgets.TEXT, "Create cruma/schemas in the client folder?");
        GeodataWidgets.space(GeodataWidgets.ROW);
        GeodataWidgets.hint(
                "Cruma makes this empty folder beside system and opens it. The schemas and the keys.txt you put there"
                        + " serve this client only.");
        GeodataWidgets.space(GeodataWidgets.BLOCK);
        var make = "Create and open";
        var cancel = "Cancel";
        var makeWidth = widthOf(make);
        var cancelWidth = widthOf(cancel);
        var row = ImGui.getContentRegionAvailX();
        ImGui.setCursorPosX(ImGui.getCursorPosX() + row - makeWidth - cancelWidth - GeodataWidgets.ROW);
        if (HudWidgets.button("tables-create-cancel", null, cancel, cancelWidth, HudWidgets.CONTROL_HEIGHT, false, true)
                || GeodataWidgets.isEscapePressed()) {
            ImGui.closeCurrentPopup();
        }
        mark("tables/create-cancel");
        ImGui.sameLine(0, GeodataWidgets.ROW);
        if (HudWidgets.button("tables-create-make", null, make, makeWidth, HudWidgets.CONTROL_HEIGHT, true, false)) {
            try {
                Files.createDirectories(folder);
                FileManager.tryOpenFolder(folder);
            } catch (IOException | RuntimeException failure) {
                saved = new Saved(
                        "Cannot create " + folder + ": " + failure.getMessage(), GeodataWidgets.BLOCKED, null);
            }
            ImGui.closeCurrentPopup();
        }
        mark("tables/create-make");
        places.put("tables/create", new float[] {left, top, left + row, ImGui.getItemRectMaxY()});
        ImGui.endPopup();
    }

    private void openUserFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            FileManager.tryOpenFolder(folder);
        } catch (IOException | RuntimeException failure) {
            saved = new Saved("Cannot create " + folder + ": " + failure.getMessage(), GeodataWidgets.BLOCKED, null);
        }
    }

    private void saveBuiltIn(Path folder) {
        var refused = ClientTables.findSaveProblem(folder);
        if (refused != null) {
            saved = new Saved(refused, GeodataWidgets.BLOCKED, null);
            return;
        }
        saved = null;
        saving = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        var written = TableSchemas.saveBuiltIn(folder);
                        return new Saved(
                                "Saved " + written.size() + " schemas into " + folder.getFileName() + ".",
                                GeodataWidgets.OPEN,
                                folder);
                    } catch (IOException | RuntimeException failure) {
                        return new Saved(
                                "Cannot write into " + folder + ": " + failure.getMessage(),
                                GeodataWidgets.BLOCKED,
                                null);
                    }
                },
                saver);
    }

    private void mark(String name) {
        places.put(name, new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        });
    }

    private void markRow(String name, float top) {
        var x = ImGui.getCursorScreenPosX();
        places.put(name, new float[] {x, top, x + ImGui.getContentRegionAvailX(), ImGui.getCursorScreenPosY()});
    }

    private static boolean isChecking(Key key) {
        return ClientTables.getRunning(key) != null || ClientTables.isQueued(key);
    }

    private static @Nullable TableReport reportOf(List<TableReport> reports, TableSpec spec) {
        for (var report : reports) {
            if (report.table().equals(spec.id())) {
                return report;
            }
        }
        return null;
    }

    private static int dotOf(@Nullable State state) {
        if (state == null) {
            return GeodataWidgets.FAINT;
        }
        return switch (state) {
            case READ -> GeodataWidgets.OPEN;
            case PARTIAL, NOT_READ -> GeodataWidgets.WARNING;
            case NOT_USED, NO_FILE -> GeodataWidgets.FAINT;
        };
    }

    private static String tipOf(Key key, TableSpec spec, @Nullable TableReport report, boolean opens, boolean isOpen) {
        if (report == null) {
            return "Not read yet. Check again reads it.";
        }
        var unopened = ClientTables.describeOpenProblem(report);
        if (!unopened.isEmpty()) {
            return "No schema was tried. " + unopened + "\n\n"
                    + ClientTables.shortenPaths(report.problem(), key.system()) + "\n\nWithout it: "
                    + lowerFirst(spec.lost());
        }
        var source = ClientTables.describeSource(report.schemaSource(), key.system());
        var text = switch (report.state()) {
            case READ -> "Read whole by " + report.schemaTitle() + " · " + source + ".";
            case PARTIAL -> "Read up to a damaged tail by " + report.schemaTitle() + " · " + source + ".";
            case NOT_READ -> "No schema read it. " + spec.lost();
            case NOT_USED -> report.problem() + otherTable(spec);
            case NO_FILE -> "This client has no such table. Without it: " + lowerFirst(spec.lost());
        };
        return opens ? text + (isOpen ? " Click to fold." : " Click to see the schemas Cruma tried.") : text;
    }

    private static String otherTable(TableSpec spec) {
        var onlyWith = spec.onlyWith();
        if (onlyWith == null) {
            return "";
        }
        return onlyWith.ifUnread()
                ? " It is a fault only in a client with " + onlyWith.fileName() + "."
                : " Cruma uses it only with " + onlyWith.fileName() + ", and no schema was tried.";
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static float widthOf(String label) {
        return Math.max(64, ImGui.calcTextSizeX(label) + 32);
    }

    private static String cut(String text, float width) {
        return HudWidgets.truncateToWidth(text, Math.max(8, width));
    }
}
