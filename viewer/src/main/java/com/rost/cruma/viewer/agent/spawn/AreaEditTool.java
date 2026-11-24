package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaSteps.describeFindings;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.document;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.isAreaDataReady;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.waitForPerson;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.PERSON_NANOS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.SHOWN_PROBLEMS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeIssues;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaChecks;
import com.rost.cruma.core.area.AreaChecks.Finding;
import com.rost.cruma.core.area.AreaDocument;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaReview;
import com.rost.cruma.core.spawn.SpawnReview.Geodata;
import com.rost.cruma.viewer.agent.AgentSteps;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaEditor.Plan;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

final class AreaEditTool {
    private AreaEditTool() {}

    static Step areaEdit(ToolArgs args, Desk desk) throws ToolProblem {
        args.getRequired("action");
        return after(() -> isAreaDataReady(desk), () -> new Editing(args, desk));
    }

    private static final class Editing implements Step {
        private final ToolArgs args;
        private final Desk desk;
        private final AtomicReference<Plan> plan = new AtomicReference<>();
        private final long deadline = System.nanoTime() + PERSON_NANOS;
        private @Nullable Step work;
        private @Nullable State planned;

        Editing(ToolArgs args, Desk desk) {
            this.args = args;
            this.desk = desk;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var areas = desk.areas();
            if (work == null) {
                if (areas.isHeld()) {
                    return waitForPerson(deadline);
                }
                var document = document(desk);
                var op = args.getRequired("action");
                if (op.equals("undo") || op.equals("redo")) {
                    return history(document, op, desk.hidden());
                }
                start(document);
            }
            if (work.step() == null) {
                return null;
            }
            work = null;
            var document = document(desk);
            if (areas.isHeld() || document.getState() != planned) {
                if (areas.isHeld()) {
                    return waitForPerson(deadline);
                }
                start(document);
                return null;
            }
            return apply(document, plan.get());
        }

        @Override
        public void cancel() {
            if (work != null) {
                work.cancel();
            }
        }

        private void start(AreaDocument document) throws ToolProblem {
            var state = document.getState();
            planned = state;
            var text = args.getText("target");
            var target = text == null ? null : desk.places().locate(text);
            var file = document.getFile();
            var types = document.getSavedTypes();
            var areas = desk.areas();
            Geodata geodata = areas.hasGeodata() ? areas::readArea : null;
            work = Step.onWorker(() -> {
                plan.set(new AreaEditor(args, state, file, types, target, geodata, areas::findClientFloor).plan());
                return ToolResult.text("planned");
            });
        }

        private ToolResult apply(AreaDocument document, Plan made) {
            var revision = document.getRevision();
            var label = AgentSteps.PREFIX + made.label();
            document.apply(label, current -> {
                var next = made.edit().apply(current);
                return next.areas().equals(current.areas()) ? current : next;
            });
            if (document.getRevision() == revision) {
                return ToolResult.text("Nothing changed: the values were already so.");
            }
            var said = made.said();
            said.put("step", label);
            var state = document.getState();
            var area = made.focus() < 0 ? null : state.area(made.focus());
            var before = made.focus() < 0 ? null : planned.area(made.focus());
            if (area != null) {
                if (before != null) {
                    var what = AreaReview.describe(before, area);
                    if (!what.isEmpty()) {
                        said.put("what", String.join(", ", what));
                    }
                }
                var findings = AreaChecks.check(state, area.index(), document.getSavedTypes());
                said.put("problems", describeFindings(findings));
            }
            said.put("unsaved", document.isModified());
            addIntroduced(said, document);
            return ToolResult.data(said);
        }
    }

    private static ToolResult history(AreaDocument document, String op, boolean hidden) throws ToolProblem {
        var undo = op.equals("undo");
        if (undo ? !document.canUndo() : !document.canRedo()) {
            throw new ToolProblem(undo ? "There is nothing to undo." : "There is nothing to redo.");
        }
        var label = undo ? document.getUndoLabel() : document.getRedoLabel();
        if (!AgentSteps.isOwn(label, hidden)) {
            throw new ToolProblem(AgentSteps.held(label));
        }
        if (undo) {
            document.undo();
        } else {
            document.redo();
        }
        var said = new JSONObject();
        said.put(undo ? "undone" : "redone", label);
        said.put("next_undo", document.getUndoLabel());
        said.put("next_redo", document.getRedoLabel());
        said.put("unsaved", document.isModified());
        return ToolResult.data(said);
    }

    private static void addIntroduced(JSONObject said, AreaDocument document) {
        var introduced =
                AreaChecks.findIntroduced(document.getSavedState(), document.getState(), document.getSavedTypes());
        var errors = new ArrayList<>(introduced.stream()
                .filter(Finding::isError)
                .map(Finding::toIssue)
                .toList());
        if (errors.isEmpty()) {
            return;
        }
        errors.sort(Comparator.comparing(issue -> issue.where()));
        said.put("errors_that_stop_save", describeIssues(errors));
        said.put("more_errors", Math.max(0, errors.size() - SHOWN_PROBLEMS));
    }
}
