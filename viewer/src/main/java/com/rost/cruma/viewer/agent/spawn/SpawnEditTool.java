package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.PERSON_NANOS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.SHOWN_PROBLEMS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.area;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeIssues;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.document;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.spawnsReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.waitForPerson;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.spawn.NpcDataTable;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.NpcPosDocument.State;
import com.rost.cruma.core.spawn.SpawnChecks;
import com.rost.cruma.core.spawn.SpawnReview.Geodata;
import com.rost.cruma.viewer.agent.AgentSteps;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnEditor.Plan;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

final class SpawnEditTool {
    private SpawnEditTool() {}

    static Step spawnEdit(ToolArgs args, Desk desk) throws ToolProblem {
        args.getRequired("action");
        return after(() -> spawnsReady(desk), () -> new Editing(args, desk));
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
            var spawns = desk.spawns();
            if (work == null) {
                if (spawns.isHeld()) {
                    return waitForPerson(deadline);
                }
                var document = document(desk);
                var op = args.getRequired("action");
                if (op.equals("undo") || op.equals("redo")) {
                    return history(document, op, desk.hidden());
                }
                if (op.equals("bookmark") || op.equals("unbookmark")) {
                    return SpawnLists.bookmark(args, desk, document.getState(), op.equals("bookmark"));
                }
                start(document);
            }
            if (work.step() == null) {
                return null;
            }
            work = null;
            var document = document(desk);
            if (spawns.isHeld() || document.getState() != planned) {
                return waitForPerson(deadline);
            }
            return apply(document, plan.get());
        }

        @Override
        public void cancel() {
            if (work != null) {
                work.cancel();
            }
        }

        private void start(NpcPosDocument document) throws ToolProblem {
            var state = document.getState();
            planned = state;
            var text = args.getText("target");
            var target = text == null ? null : desk.places().locate(text);
            var area = target != null && args.getRequired("action").equals("batch") ? area(args, target) : null;
            var npcs = desk.spawns().getSource().getNpcs();
            var spawns = desk.spawns();
            Geodata geodata = spawns.hasGeodata() ? spawns::readArea : null;
            work = Step.onWorker(() -> {
                plan.set(new SpawnEditor(args, state, npcs, target, area, geodata, spawns::findClientFloor).plan());
                return ToolResult.text("planned");
            });
        }

        private ToolResult apply(NpcPosDocument document, Plan made) {
            var revision = document.getRevision();
            var label = "AI: " + made.label();
            document.apply(
                    label,
                    current -> {
                        var next = made.edit().apply(current);
                        return next.makers().equals(current.makers())
                                        && next.territories().equals(current.territories())
                                ? current
                                : next;
                    },
                    made.batch());
            if (document.getRevision() == revision) {
                return ToolResult.text("Nothing changed: the values were already so.");
            }
            var said = made.said();
            said.put("step", label);
            said.put("unsaved", document.isModified());
            addProblems(said, document, desk.spawns().getSource().getNpcs());
            return ToolResult.data(said);
        }
    }

    private static ToolResult history(NpcPosDocument document, String op, boolean hidden) throws ToolProblem {
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

    private static void addProblems(JSONObject said, NpcPosDocument document, NpcDataTable npcs) {
        var issues = SpawnChecks.findIntroduced(document.getSavedState(), document.getState(), npcs);
        if (issues.isEmpty()) {
            return;
        }
        var sorted = new ArrayList<>(issues);
        sorted.sort(Comparator.comparing(issue -> !issue.isError()));
        said.put("problems", describeIssues(sorted));
        said.put("more_problems", Math.max(0, issues.size() - SHOWN_PROBLEMS));
    }
}
