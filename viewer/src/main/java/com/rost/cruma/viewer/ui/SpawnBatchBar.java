package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.spawn.MakerAiCatalog;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosDocument.Batch;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnBatchEdits;
import com.rost.cruma.viewer.ui.BatchWidgets.Action;
import com.rost.cruma.viewer.ui.BatchWidgets.Confirm;
import com.rost.cruma.viewer.ui.BatchWidgets.Pick;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiComboFlags;
import imgui.type.ImString;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class SpawnBatchBar {
    private static final List<Action> ACTIONS = List.of(
            new Action("respawn", null, "Respawn ×", "Respawn times of every entry × a number, and their random part."),
            new Action("count", null, "Count ×", "NPC counts and maximum_npc × a number."),
            new Action("parameter", null, "AI parameter", "Set or remove an AI parameter of each npcmaker_ex maker."),
            new Action("ai", null, "Change AI", "Give each npcmaker_ex maker another AI."),
            new Action("delete", Glyph.CLOSE, "Delete", "Delete the makers, and the zones only they use."));

    private @Nullable Kind kind;
    private final ImString factor = new ImString("2", 32);
    private final ImString parameter = new ImString(64);
    private final ImString value = new ImString(128);
    private final ImString aiFilter = new ImString(64);
    private String ai = "";
    private boolean randomToo = true;
    private boolean totals = true;
    private boolean maximum = true;
    private boolean zonesToo = true;
    private String note = "";

    enum Kind {
        RESPAWN,
        COUNT,
        PARAMETER,
        AI,
        DELETE
    }

    boolean isBusy() {
        return kind != null;
    }

    void cancel() {
        kind = null;
    }

    boolean draw(
            NpcPosDocument document,
            Set<Integer> chosen,
            Collection<Integer> shown,
            String allLabel,
            MakerAiCatalog catalog) {
        var pick = BatchWidgets.begin(
                "spawn-batch",
                chosen.size(),
                "maker",
                "makers",
                allLabel,
                "Select every maker the list below shows now, the search results, or else every maker whose zone or"
                        + " NPC is in view. Shift-click a row selects the rows between.");
        if (pick == Pick.ALL) {
            chosen.addAll(shown);
        } else if (pick == Pick.NONE) {
            chosen.clear();
        }
        var deleted = false;
        if (kind == null) {
            var clicked = BatchWidgets.actionGrid("spawn-batch", ACTIONS);
            if (clicked >= 0) {
                kind = Kind.values()[clicked];
                note = "";
                factor.set("2");
                if (kind == Kind.AI) {
                    ai = "";
                    aiFilter.set("");
                }
            }
        } else {
            deleted = drawForm(document, chosen, catalog);
        }
        BatchWidgets.end();
        if (!note.isEmpty()) {
            GeodataWidgets.space(GeodataWidgets.ROW);
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.MUTED, note);
            ImGui.popTextWrapPos();
        }
        return deleted;
    }

    private boolean drawForm(NpcPosDocument document, Set<Integer> chosen, MakerAiCatalog catalog) {
        var current = kind;
        var valid = true;
        switch (current) {
            case RESPAWN, COUNT -> {
                ImGui.alignTextToFramePadding();
                ImGui.textColored(GeodataWidgets.MUTED, current == Kind.RESPAWN ? "Respawn ×" : "Count ×");
                ImGui.sameLine(110);
                ImGui.setNextItemWidth(80);
                ImGui.inputText("##spawn-batch-factor", factor);
                valid = factor() > 0;
                if (current == Kind.RESPAWN) {
                    if (ImGui.checkbox("Random part too##spawn-batch-random", randomToo)) {
                        randomToo = !randomToo;
                    }
                    GeodataWidgets.tip("Also scale respawn_rand, the part added or taken at random.");
                } else {
                    if (ImGui.checkbox("NPC counts##spawn-batch-totals", totals)) {
                        totals = !totals;
                    }
                    ImGui.sameLine();
                    if (ImGui.checkbox("maximum_npc##spawn-batch-max", maximum)) {
                        maximum = !maximum;
                    }
                    valid &= totals || maximum;
                }
            }
            case PARAMETER -> {
                var half = (BatchWidgets.inner() - ImGui.getStyle().getItemSpacingX()) / 2;
                GeodataWidgets.columns(
                        () -> GeodataWidgets.compactText(
                                "Parameter", "##spawn-batch-name", "such as EventName", parameter, half),
                        () -> GeodataWidgets.compactText(
                                "Value", "##spawn-batch-value", "empty removes it", value, half));
                valid = !parameter.get().strip().isEmpty();
            }
            case AI -> {
                ImGui.setNextItemWidth(BatchWidgets.inner());
                if (HudWidgets.beginCombo(
                        "##spawn-batch-ai", ai.isEmpty() ? "Choose an AI" : ai, ImGuiComboFlags.HeightLarge)) {
                    ImGui.setNextItemWidth(-1);
                    ImGui.inputTextWithHint("##spawn-batch-ai-filter", "Filter", aiFilter);
                    var filter = aiFilter.get().strip().toLowerCase(Locale.ROOT);
                    var listed = 0;
                    for (var candidate : catalog.getAis()) {
                        if (!filter.isEmpty()
                                && !candidate.name().toLowerCase(Locale.ROOT).contains(filter)) {
                            continue;
                        }
                        if (listed++ >= 300) {
                            break;
                        }
                        if (ImGui.selectable(
                                candidate.name() + "##" + candidate.name(),
                                candidate.name().equals(ai))) {
                            ai = candidate.name();
                        }
                    }
                    ImGui.endCombo();
                }
                GeodataWidgets.tip(
                        "AIs the makers of this file use. Parameters no maker gives the new AI are dropped.");
                valid = !ai.isEmpty();
            }
            case DELETE -> {
                ImGui.pushTextWrapPos(0);
                ImGui.textColored(
                        GeodataWidgets.BLOCKED,
                        String.format(
                                Locale.ROOT,
                                "Delete %,d %s and their entries?",
                                chosen.size(),
                                chosen.size() == 1 ? "maker" : "makers"));
                ImGui.popTextWrapPos();
                if (ImGui.checkbox("Also delete the zones only they use##spawn-batch-zones", zonesToo)) {
                    zonesToo = !zonesToo;
                }
            }
        }
        var confirm = BatchWidgets.applyCancelButtons(
                "spawn-batch",
                (current == Kind.DELETE ? "Delete " : "Apply to ") + chosen.size(),
                valid && !chosen.isEmpty());
        if (confirm == Confirm.CANCEL) {
            kind = null;
        } else if (confirm == Confirm.APPLY) {
            kind = null;
            return run(document, current, chosen, catalog);
        }
        return false;
    }

    private boolean run(NpcPosDocument document, Kind current, Set<Integer> chosen, MakerAiCatalog catalog) {
        var makers = List.copyOf(chosen);
        switch (current) {
            case RESPAWN -> {
                var times = SpawnBatchEdits.formatFactor(factor());
                var random = randomToo;
                apply(
                        document,
                        "Respawn " + times,
                        "respawn " + times + (random ? "" : " (not the random part)"),
                        state -> SpawnBatchEdits.scaleRespawn(state, makers, factor(), random));
            }
            case COUNT -> {
                var times = SpawnBatchEdits.formatFactor(factor());
                var scaleTotals = totals;
                var scaleMaximum = maximum;
                var what = scaleTotals && scaleMaximum
                        ? "counts and maximum_npc "
                        : scaleTotals ? "counts " : "maximum_npc ";
                apply(
                        document,
                        "Counts " + times,
                        what + times,
                        state -> SpawnBatchEdits.scaleCounts(state, makers, factor(), scaleTotals, scaleMaximum));
            }
            case PARAMETER -> {
                var name = parameter.get().strip().replace("[", "").replace("]", "");
                var text = value.get().strip();
                apply(
                        document,
                        text.isEmpty() ? "Remove " + name : name + "=" + text,
                        text.isEmpty() ? name + " removed" : name + " set to " + text,
                        state -> SpawnBatchEdits.setAiParameter(state, makers, name, text.isEmpty() ? null : text));
            }
            case AI -> {
                var chosenAi = ai;
                apply(
                        document,
                        "AI " + chosenAi,
                        "AI changed to " + chosenAi,
                        state -> SpawnBatchEdits.setAi(state, makers, chosenAi, catalog));
            }
            case DELETE -> {
                var zones = zonesToo;
                apply(
                        document,
                        "Delete",
                        zones ? "deleted with their zones" : "deleted",
                        state -> SpawnBatchEdits.delete(state, makers, zones));
                chosen.clear();
                return true;
            }
        }
        return false;
    }

    private void apply(NpcPosDocument document, String action, String noun, UnaryOperator<State> edit) {
        var before = document.getState();
        var after = edit.apply(before);
        var makers = SpawnBatchEdits.changedMakers(before, after);
        var zones = SpawnBatchEdits.changedTerritories(before, after);
        if (makers.isEmpty() && zones.isEmpty()) {
            note = "Nothing changed: no selected maker could take it.";
            return;
        }
        note = "";
        document.apply(
                action + " on " + makers.size() + (makers.size() == 1 ? " maker" : " makers"),
                unused -> after,
                new Batch(noun, makers, zones));
    }

    private double factor() {
        try {
            var parsed = Double.parseDouble(factor.get().strip().replace(',', '.'));
            return Double.isFinite(parsed) && parsed > 0 ? parsed : -1;
        } catch (NumberFormatException notNumber) {
            return -1;
        }
    }
}
