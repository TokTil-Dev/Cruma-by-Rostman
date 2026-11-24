package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.spawn.SpawnChanges.Change;
import com.rost.cruma.core.spawn.SpawnChanges.Section;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.viewer.spawn.SpawnSource;
import imgui.ImFont;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiWindowFlags;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class SaveReviewDialog {
    static final int LISTED_ISSUES = 8;

    private static final float BLOCK = GeodataWidgets.BLOCK;
    private static final float ROW = GeodataWidgets.ROW;
    private static final int HUNKS = 300;

    private final String id;
    private final String fileName;
    private final String breaks;

    private final Set<String> open = new HashSet<>();

    private boolean backup = true;

    SaveReviewDialog(String id, String fileName, String breaks) {
        this.id = id;
        this.fileName = fileName;
        this.breaks = breaks;
    }

    record Content(
            List<Change> changes,
            List<Section> sections,
            int removed,
            int added,
            boolean changedOnDisk,
            List<Issue> errors,
            List<Issue> warnings) {}

    enum Answer {
        NONE,
        CANCEL,
        SAVE
    }

    static List<Issue> filterErrors(List<Issue> issues) {
        return issues.stream().filter(Issue::isError).toList();
    }

    static void drawIssues(List<Issue> issues, int color) {
        ImGui.pushTextWrapPos(0);
        for (var i = 0; i < Math.min(issues.size(), LISTED_ISSUES); i++) {
            var issue = issues.get(i);
            text(GeodataWidgets.MUTED, issue.where() + " ·");
            ImGui.sameLine(0, 6);
            text(color, issue.message());
        }
        if (issues.size() > LISTED_ISSUES) {
            text(GeodataWidgets.FAINT, String.format(Locale.ROOT, "%,d more.", issues.size() - LISTED_ISSUES));
        }
        ImGui.popTextWrapPos();
    }

    boolean isBackup() {
        return backup;
    }

    void foldAll() {
        open.clear();
    }

    Answer draw(@Nullable Content content, @Nullable ImFont mono, Clock clock) {
        if (content == null) {
            return Answer.NONE;
        }
        var title = "Save " + fileName + "##" + id + "-review";
        if (!ImGui.isPopupOpen(title)) {
            ImGui.openPopup(title);
        }
        var io = ImGui.getIO();
        ImGui.setNextWindowPos(io.getDisplaySizeX() / 2, io.getDisplaySizeY() / 2, ImGuiCond.Always, .5f, .5f);
        ImGui.setNextWindowSize(Math.min(940, io.getDisplaySizeX() - 80), Math.min(660, io.getDisplaySizeY() - 80));
        if (!ImGui.beginPopupModal(title, ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoResize)) {
            return Answer.NONE;
        }
        Motion.dialog();
        var answer = Answer.NONE;
        var changes = content.changes().size();
        text(
                GeodataWidgets.MUTED,
                String.format(
                        Locale.ROOT,
                        "%,d %s · %,d %s removed · %,d added",
                        changes,
                        changes == 1 ? "change" : "changes",
                        content.removed(),
                        content.removed() == 1 ? "line" : "lines",
                        content.added()));
        if (content.changedOnDisk()) {
            GeodataWidgets.space(ROW);
            ImGui.pushTextWrapPos(0);
            text(
                    GeodataWidgets.WARNING,
                    fileName + " changed on disk since it was read. Saving replaces those "
                            + "edits with the ones below; keep the backup to have them.");
            ImGui.popTextWrapPos();
        }
        var blocked = !content.errors().isEmpty();
        if (blocked) {
            GeodataWidgets.space(ROW);
            text(GeodataWidgets.BLOCKED, "Fix these before saving: the server cannot read them, or " + breaks + ".");
            drawIssues(content.errors(), GeodataWidgets.BLOCKED);
        }
        if (!content.warnings().isEmpty()) {
            GeodataWidgets.space(ROW);
            text(GeodataWidgets.WARNING, "Worth a look; Save still works:");
            drawIssues(content.warnings(), GeodataWidgets.WARNING);
        }
        GeodataWidgets.space(BLOCK);
        ImGui.beginChild("##" + id + "-review-diff", 0, -88, true, ImGuiWindowFlags.HorizontalScrollbar);
        if (mono != null) {
            ImGui.pushFont(mono, 14f);
        }
        var drawn = 0;
        for (var section : content.sections()) {
            if (drawn >= HUNKS) {
                break;
            }
            if (section.batch() != null) {
                if (mono != null) {
                    ImGui.popFont();
                }
                var unfolded = open.contains(section.title());
                var label = section.title()
                        + " · "
                        + section.changes().size()
                        + (section.changes().size() == 1 ? " line" : " lines");
                if (HudWidgets.disclosure(id + "-review-batch/" + section.title(), label, unfolded)) {
                    if (!open.remove(section.title())) {
                        open.add(section.title());
                    }
                }
                GeodataWidgets.tip(unfolded ? "Fold the lines again." : "Show the line of each maker it changed.");
                if (mono != null) {
                    ImGui.pushFont(mono, 14f);
                }
                GeodataWidgets.space(BLOCK);
                if (!unfolded) {
                    drawn++;
                    continue;
                }
            }
            for (var change : section.changes()) {
                if (drawn++ >= HUNKS) {
                    break;
                }
                if (section.batch() != null) {
                    ImGui.indent(14);
                }
                drawChange(change, mono);
                if (section.batch() != null) {
                    ImGui.unindent(14);
                }
                GeodataWidgets.space(BLOCK);
            }
        }
        if (drawn > HUNKS) {
            text(GeodataWidgets.FAINT, "More changes are not shown.");
        }
        if (mono != null) {
            ImGui.popFont();
        }
        ImGui.endChild();
        GeodataWidgets.space(BLOCK);
        if (ImGui.checkbox("Back up the current file first", backup)) {
            backup = !backup;
        }
        text(
                GeodataWidgets.FAINT,
                backup
                        ? "As " + SpawnSource.backupName(fileName, LocalDateTime.now(clock)) + " next to it"
                        : "No copy: the file is replaced");
        var buttons = 2 * 96 + 8;
        ImGui.sameLine(ImGui.getWindowContentRegionMaxX() - buttons);
        if (HudWidgets.button(id + "-review-cancel", null, "Cancel", 96, 28, false, true)
                || ImGui.isKeyPressed(ImGuiKey.Escape)
                || GeodataWidgets.wasClickedOutside()) {
            answer = Answer.CANCEL;
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine(0, 8);
        ImGui.beginDisabled(blocked);
        if (HudWidgets.button(id + "-review-save", null, "Save", 96, 28, true, true)
                || !blocked && (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter))) {
            answer = Answer.SAVE;
            ImGui.closeCurrentPopup();
        }
        ImGui.endDisabled();
        if (blocked) {
            GeodataWidgets.tip("Fix the problems listed above first.");
        } else {
            GeodataWidgets.tip("Write the changes to " + fileName + ".", "Enter");
        }
        ImGui.endPopup();
        return answer;
    }

    private static void drawChange(Change change, @Nullable ImFont mono) {
        if (mono != null) {
            ImGui.popFont();
        }
        text(
                GeodataWidgets.FAINT,
                !change.isAddition()
                        ? "Line " + change.line()
                        : change.line() == 0 ? "At the start" : "After line " + change.line());
        ImGui.sameLine(0, 8);
        if (!change.where().isEmpty()) {
            text(GeodataWidgets.MUTED, change.where() + " ·");
            ImGui.sameLine(0, 6);
        }
        text(HudWidgets.TEXT, change.what());
        if (mono != null) {
            ImGui.pushFont(mono, 14f);
        }
        var draw = ImGui.getWindowDrawList();
        for (var line : change.lines()) {
            var color = line.removed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN;
            var dim = (color & 0x00FFFFFF) | 0x99000000;
            var mark = (color & 0x00FFFFFF) | 0x38000000;
            var whole = line.spans().size() == 1 && line.spans().getFirst().changed();
            text(color, line.removed() ? "-" : "+");
            for (var span : line.spans()) {
                ImGui.sameLine(0, span == line.spans().getFirst() ? ImGui.calcTextSizeX(" ") : 0);
                var shown = span.text().replace('\t', ' ');
                if (span.changed() && !whole) {
                    var x = ImGui.getCursorScreenPosX();
                    var y = ImGui.getCursorScreenPosY();
                    draw.addRectFilled(
                            x - 1, y, x + ImGui.calcTextSizeX(shown) + 1, y + ImGui.getTextLineHeight(), mark, 2f);
                }
                text(span.changed() ? color : dim, shown);
            }
        }
    }

    private static void text(int color, String value) {
        ImGui.pushStyleColor(ImGuiCol.Text, color);
        ImGui.textUnformatted(value);
        ImGui.popStyleColor();
    }
}
