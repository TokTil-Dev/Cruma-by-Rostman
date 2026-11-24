package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.ROW;

import com.rost.cruma.viewer.agent.AgentActivity.Entry;
import com.rost.cruma.viewer.agent.AgentConnect;
import com.rost.cruma.viewer.agent.AgentFindings.Findings;
import com.rost.cruma.viewer.agent.AgentFindings.Item;
import com.rost.cruma.viewer.agent.AgentFindings.Severity;
import com.rost.cruma.viewer.agent.AgentRuntime;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.ImGuiListClipper;
import imgui.flag.ImGuiStyleVar;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

final class AgentPanel implements AutoCloseable {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final float STOP_WIDTH = 76;
    private static final float SCROLL_BAR_ROOM = 8;
    private static final int LEAST_ROWS = 2;

    private final AgentRuntime agent;
    private final Consumer<Item> fly;
    private final FontManager fonts;
    private final ImGuiListClipper findingClipper = new ImGuiListClipper();
    private final ImGuiListClipper callClipper = new ImGuiListClipper();
    private final Set<String> foldedLists = new HashSet<>();
    private boolean foldsChanged;
    private long findingsRead = -1;
    private long callsRead = -1;
    private List<FindingRow> findingRows = List.of();
    private List<CallRow> callRows = List.of();
    private @Nullable Item chosen;
    private @Nullable AgentConnect connect;

    AgentPanel(AgentRuntime agent, Consumer<Item> fly, FontManager fonts) {
        this.agent = agent;
        this.fly = fly;
        this.fonts = fonts;
    }

    @Override
    public void close() {
        findingClipper.destroy();
        callClipper.destroy();
    }

    private record FindingRow(Findings list, @Nullable Item item, String detail, int color) {}

    private record CallRow(Entry entry, String clock, boolean folder) {}

    void draw() {
        readFindings();
        readCalls();
        drawSwitch();
        GeodataWidgets.space(ROW);
        drawState();
        GeodataWidgets.section("ACCESS");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/access-level");
        GeodataWidgets.space(ROW);
        var current = agent.getAccess();
        var level = AgentControls.access("agent-access", current);
        if (level != current) {
            agent.setAccess(level);
        }
        if (agent.getConnections() == 0 && findingRows.isEmpty() && callRows.isEmpty()) {
            drawConnect();
        } else {
            drawLists();
        }
    }

    private void drawSwitch() {
        var fixed = agent.isOwned() || !agent.canOpen();
        var value = agent.isOwned() || agent.isEnabled();
        var tip = agent.isOwned()
                ? "An agent opened this window, so it stays connected. Stop refuses its calls."
                : agent.canOpen()
                        ? "An agent sees your selection and your camera, and you see what it does."
                        : "This run keeps no settings, so agents stay out.";
        var next = HudWidgets.switchRow(AgentControls.SWITCH, value, !fixed, tip);
        if (!fixed && next != value) {
            agent.setEnabled(next);
        }
    }

    private void drawState() {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var height = HudWidgets.CONTROL_HEIGHT;
        var stopped = agent.isStopped();
        var count = agent.getConnections();
        var color = stopped
                ? GeodataWidgets.WARNING
                : count > 0 ? GeodataWidgets.OPEN : agent.isOpen() ? GeodataWidgets.MUTED : GeodataWidgets.FAINT;
        var top = y + (height - ImGui.getFontSize()) * .5f;
        var draw = ImGui.getWindowDrawList();
        draw.addCircleFilled(x + 4, HudWidgets.textMiddle(top), 4.5f, color, 14);
        draw.addText(
                x + 14,
                top,
                stopped || count > 0 ? HudWidgets.TEXT : GeodataWidgets.MUTED,
                HudWidgets.truncateToWidth(state(stopped, count), width - STOP_WIDTH - 22));
        ImGui.setCursorScreenPos(x + width - STOP_WIDTH, y);
        ImGui.beginDisabled(!stopped && !agent.isOpen());
        if (HudWidgets.button(
                "agent-stop",
                stopped ? Glyph.PLAY : Glyph.STOP,
                stopped ? "Allow" : "Stop",
                STOP_WIDTH,
                height,
                stopped,
                true)) {
            agent.setStopped(!stopped);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(stopped ? "Let agents call again." : "Refuse all calls until you press Allow.");
        ImGui.setCursorScreenPos(x, y + height);
        ImGui.dummy(width, 0);
        var problem = agent.getOpenProblem();
        if (problem != null) {
            GeodataWidgets.space(ROW);
            GeodataWidgets.note("Agents cannot reach this window: " + problem, GeodataWidgets.BLOCKED);
        }
    }

    private String state(boolean stopped, int count) {
        if (stopped) {
            return "Stopped · calls are refused";
        }
        if (count > 0) {
            return count == 1 ? "1 agent connected" : count + " agents connected";
        }
        return agent.isOpen() ? "No agent connected" : "Agents are kept out";
    }

    private void drawConnect() {
        if (connect == null) {
            connect = AgentConnect.ofThisRun();
        }
        GeodataWidgets.section("CONNECT");
        ImGui.sameLine(0, 4);
        HudWidgets.helpMark("terms/mcp");
        GeodataWidgets.space(ROW);
        GeodataWidgets.hint("Add Cruma to your agent's program once. It starts Cruma by itself.");
        GeodataWidgets.space(ROW);
        AgentControls.connect("agent-connect", connect, fonts.getMonoFont());
    }

    private void drawLists() {
        var step = SpawnPanelWidgets.listRowHeight() + ImGui.getStyle().getItemSpacingY();
        var least = LEAST_ROWS * step;
        if (!findingRows.isEmpty()) {
            GeodataWidgets.section("FINDINGS");
            GeodataWidgets.space(ROW);
            var room = ImGui.getContentRegionAvailY() - GeodataWidgets.SECTION - ImGui.getFontSize() - ROW;
            var share = Math.max(room * .5f, room - Math.max(callRows.size(), 1) * step);
            var height = Math.min(findingRows.size() * step, Math.max(least, share));
            beginList("##agentFindings", height);
            drawFindings();
            ImGui.endChild();
        }
        GeodataWidgets.section("CALLS");
        GeodataWidgets.space(ROW);
        if (callRows.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "No calls yet.");
            return;
        }
        beginList("##agentCalls", Math.max(least, ImGui.getContentRegionAvailY()));
        drawCalls();
        ImGui.endChild();
    }

    private void readFindings() {
        var findings = agent.getFindings();
        var revision = findings.getRevision();
        if (revision == findingsRead && !foldsChanged) {
            return;
        }
        var lists = findings.getLists();
        if (revision != findingsRead && !lists.isEmpty()) {
            foldedLists.remove(lists.getFirst().title());
        }
        findingsRead = revision;
        foldsChanged = false;
        findingRows = rows(lists);
    }

    private List<FindingRow> rows(List<Findings> lists) {
        var rows = new ArrayList<FindingRow>();
        for (var list : lists) {
            rows.add(new FindingRow(list, null, counts(list), 0));
            if (!foldedLists.contains(list.title())) {
                for (var item : list.items()) {
                    rows.add(new FindingRow(list, item, detail(item), color(item.severity())));
                }
            }
        }
        return rows;
    }

    private void drawFindings() {
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        findingClipper.begin(findingRows.size(), rowHeight + ImGui.getStyle().getItemSpacingY());
        while (findingClipper.step()) {
            for (var i = findingClipper.getDisplayStart(); i < findingClipper.getDisplayEnd(); i++) {
                var row = findingRows.get(i);
                if (row.item() == null) {
                    drawHeader(row, rowHeight);
                } else {
                    drawItem(i, row, rowHeight);
                }
            }
        }
        findingClipper.end();
    }

    private void drawHeader(FindingRow row, float rowHeight) {
        var title = row.list().title();
        var open = !foldedLists.contains(title);
        if (SpawnPanelWidgets.groupRow("agent-list/" + title, open, title, row.detail(), "", "", rowHeight)) {
            if (!foldedLists.remove(title)) {
                foldedLists.add(title);
            }
            foldsChanged = true;
        }
        GeodataWidgets.tip(open ? "Fold this list." : "Show this list.");
    }

    private void drawItem(int index, FindingRow row, float rowHeight) {
        var item = row.item();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        if (ImGui.selectable("##agent-finding/" + index, item == chosen, 0, width, rowHeight)) {
            chosen = item;
            fly.accept(item);
        }
        GeodataWidgets.tip(item.text() + "\nClick to fly there.");
        SpawnPanelWidgets.statusRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                row.color(),
                item.text(),
                row.detail(),
                GeodataWidgets.MUTED,
                "");
    }

    private void readCalls() {
        var activity = agent.getActivity();
        var revision = activity.getRevision();
        if (revision == callsRead) {
            return;
        }
        callsRead = revision;
        var rows = new ArrayList<CallRow>();
        for (var entry : activity.getEntries()) {
            var clock = CLOCK.format(entry.time());
            rows.add(new CallRow(entry, clock, false));
            if (entry.folder() != null) {
                rows.add(new CallRow(entry, clock, true));
            }
        }
        callRows = rows;
    }

    private void drawCalls() {
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        callClipper.begin(callRows.size(), rowHeight + ImGui.getStyle().getItemSpacingY());
        while (callClipper.step()) {
            for (var i = callClipper.getDisplayStart(); i < callClipper.getDisplayEnd(); i++) {
                var row = callRows.get(i);
                if (row.folder()) {
                    drawFolder(i, row, rowHeight);
                } else {
                    drawCall(i, row, rowHeight);
                }
            }
        }
        callClipper.end();
    }

    private static void beginList(String id, float height) {
        var width = ImGui.getContentRegionAvailX();
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.setNextWindowContentSize(width, 0);
        ImGui.beginChild(id, width + SCROLL_BAR_ROOM, height, false);
        ImGui.popStyleVar();
    }

    private static String counts(Findings list) {
        var total = list.items().size();
        var errors = 0;
        var warnings = 0;
        for (var item : list.items()) {
            errors += item.severity() == Severity.ERROR ? 1 : 0;
            warnings += item.severity() == Severity.WARNING ? 1 : 0;
        }
        var text = new StringBuilder(total + (total == 1 ? " place" : " places"));
        if (errors > 0) {
            text.append(" · ").append(errors).append(errors == 1 ? " error" : " errors");
        }
        if (warnings > 0) {
            text.append(" · ").append(warnings).append(warnings == 1 ? " warning" : " warnings");
        }
        return text.toString();
    }

    private static String detail(Item item) {
        var severity = item.severity();
        if (severity == null) {
            return item.place();
        }
        var word = severity.getWord();
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1) + " · " + item.place();
    }

    private static int color(@Nullable Severity severity) {
        return severity == null ? GeodataWidgets.MUTED : FindingWidgets.Severity.valueOf(severity.name()).color;
    }

    private static void drawCall(int index, CallRow row, float rowHeight) {
        var entry = row.entry();
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        ImGui.invisibleButton("##agent-call/" + index, width, rowHeight);
        GeodataWidgets.tip(entry.tool() + "\n" + entry.summary());
        SpawnPanelWidgets.statusRow(
                ImGui.getWindowDrawList(),
                x,
                y,
                width,
                entry.failed() ? GeodataWidgets.BLOCKED : GeodataWidgets.OPEN,
                entry.tool(),
                entry.summary(),
                entry.failed() ? GeodataWidgets.BLOCKED : GeodataWidgets.MUTED,
                row.clock());
    }

    private static void drawFolder(int index, CallRow row, float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        ImGui.setCursorScreenPos(
                x + SpawnPanelWidgets.listRowTextLeft(), y + (rowHeight - HudWidgets.CONTROL_HEIGHT) * .5f);
        GeodataWidgets.showFolder("agent-folder/" + index, row.entry().folder());
        ImGui.setCursorScreenPos(x, y + rowHeight);
        ImGui.dummy(width, 0);
    }
}
