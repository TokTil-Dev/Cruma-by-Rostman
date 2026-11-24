package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.PERSON_NANOS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.SHOWN_PROBLEMS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.areNpcsReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeChanges;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeIssues;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.document;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.npcDocument;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.spawnsReady;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.waitForPerson;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.NpcDataReview;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.core.spawn.SpawnReview;
import com.rost.cruma.core.spawn.SpawnReview.Geodata;
import com.rost.cruma.core.spawn.SpawnReview.Input;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.npc.NpcDataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

final class SpawnSaveTool {
    private static final class NpcSaving implements Step {
        private final Desk desk;
        private final int limit;
        private final long deadline = System.nanoTime() + PERSON_NANOS;
        private @Nullable JSONObject review;

        NpcSaving(Desk desk, int limit) {
            this.desk = desk;
            this.limit = limit;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var data = desk.spawns().getNpcData();
            if (review != null) {
                return data.isSaving() ? null : answer(data);
            }
            if (desk.spawns().isNpcHeld() || data.isSaving()) {
                return waitForPerson(deadline);
            }
            var document = npcDocument(desk);
            if (!document.isModified()) {
                return ToolResult.text("Nothing to save: npcdata.txt has no changes.");
            }
            var errors =
                    NpcDataReview.findIssues(document, document.getState(), data.getItems(), data.getSkills()).stream()
                            .filter(NpcDataReview.Issue::error)
                            .toList();
            if (!errors.isEmpty()) {
                var text = new StringBuilder("Not saved: the edits have ")
                        .append(errors.size())
                        .append(errors.size() == 1 ? " problem" : " problems")
                        .append(" the server cannot read. Fix them with npc_edit, or undo them, and save again.");
                for (var issue : errors.subList(0, Math.min(SHOWN_PROBLEMS, errors.size()))) {
                    text.append('\n')
                            .append(issue.npc())
                            .append(' ')
                            .append(issue.key())
                            .append(": ")
                            .append(issue.message());
                }
                throw new ToolProblem(text.toString());
            }
            if (data.hasChangedOnDisk()) {
                throw new ToolProblem(
                        "Not saved: npcdata.txt changed on disk since it was read, and a save would overwrite that."
                                + " Ask the person: they can save from the NPCs panel, or read the file again.");
            }
            review = describeChanges(data, document, limit);
            data.save(true);
            return null;
        }

        private ToolResult answer(NpcDataSource data) throws ToolProblem {
            if (data.isMessageError()) {
                throw new ToolProblem(data.getMessage());
            }
            var file = data.getFile();
            var result = new JSONObject();
            result.put("saved", String.valueOf(file));
            result.put("note", data.getMessage());
            result.putAll(review);
            result.remove("unsaved");
            var written = ToolResult.data(result);
            return file == null
                    ? written
                    : written.withFolder(file.toAbsolutePath().getParent());
        }
    }

    private static final class Saving implements Step {
        private final Desk desk;
        private final int limit;
        private final AtomicReference<SpawnReview> review = new AtomicReference<>();
        private final long deadline = System.nanoTime() + PERSON_NANOS;
        private @Nullable Step work;
        private @Nullable Input input;
        private boolean writing;

        Saving(Desk desk, int limit) {
            this.desk = desk;
            this.limit = limit;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var spawns = desk.spawns();
            var source = spawns.getSource();
            if (writing) {
                return source.isSaving() ? null : answer();
            }
            if (work == null) {
                if (spawns.isHeld() || source.isSaving()) {
                    return waitForPerson(deadline);
                }
                var document = document(desk);
                if (!document.isModified()) {
                    return ToolResult.text("Nothing to save: npcpos.txt has no changes.");
                }
                var taken = Input.of(document, source.getNpcs());
                Geodata geodata = spawns.hasGeodata() ? spawns::readArea : null;
                input = taken;
                work = Step.onWorker(() -> {
                    review.set(SpawnReview.of(taken, geodata));
                    return ToolResult.text("reviewed");
                });
            }
            if (work.step() == null) {
                return null;
            }
            work = null;
            var document = document(desk);
            if (spawns.isHeld() || document.getState() != input.state()) {
                return waitForPerson(deadline);
            }
            var found = review.get();
            if (!found.errors().isEmpty()) {
                throw new ToolProblem(refusal(found.errors()));
            }
            if (source.hasChangedOnDisk()) {
                throw new ToolProblem(
                        "Not saved: npcpos.txt changed on disk since it was read, and a save would overwrite that. Ask"
                                + " the person: they can save from the panel, or read the file again.");
            }
            source.save(true);
            writing = true;
            return null;
        }

        @Override
        public void cancel() {
            if (work != null) {
                work.cancel();
            }
        }

        private ToolResult answer() throws ToolProblem {
            var source = desk.spawns().getSource();
            if (source.isMessageError()) {
                throw new ToolProblem(source.getMessage());
            }
            var found = review.get();
            var file = source.getPositionsFile();
            var lines = new JSONObject();
            lines.put("added", found.added());
            lines.put("removed", found.removed());
            var result = new JSONObject();
            result.put("saved", String.valueOf(file));
            result.put("note", source.getMessage());
            result.put("lines", lines);
            result.put("changes", found.changes().size());
            var sections = new JSONArray();
            var left = limit;
            for (var section : found.sections()) {
                var changes = new JSONArray();
                for (var change : section.changes()
                        .subList(0, Math.min(left, section.changes().size()))) {
                    var item = new JSONObject();
                    item.put("line", change.line());
                    item.put("where", change.where());
                    item.put("what", change.what());
                    changes.add(item);
                }
                left -= changes.size();
                var item = new JSONObject();
                item.put("title", section.title());
                item.put("count", section.changes().size());
                item.put("list", changes);
                sections.add(item);
            }
            result.put("sections", sections);
            result.put("more", Math.max(0, found.changes().size() - limit));
            if (!found.warnings().isEmpty()) {
                result.put("warnings", describeIssues(found.warnings()));
            }
            var written = ToolResult.data(result);
            return file == null
                    ? written
                    : written.withFolder(file.toAbsolutePath().getParent());
        }

        private static String refusal(List<Issue> errors) {
            var text = new StringBuilder("Not saved: the edits broke ")
                    .append(errors.size())
                    .append(errors.size() == 1 ? " thing" : " things")
                    .append(". Fix them with spawn_edit, or undo them, and save again.");
            for (var issue : errors.subList(0, Math.min(SHOWN_PROBLEMS, errors.size()))) {
                text.append('\n').append(issue.where()).append(": ").append(issue.message());
            }
            return text.toString();
        }
    }

    private SpawnSaveTool() {}

    static Step spawnSave(ToolArgs args, Desk desk) throws ToolProblem {
        var limit = limit(args);
        var file = args.getText("file", "npcpos");
        return switch (file) {
            case "npcpos" -> after(() -> spawnsReady(desk), () -> new Saving(desk, limit));
            case "npcdata" -> after(() -> areNpcsReady(desk), () -> new NpcSaving(desk, limit));
            default -> throw new ToolProblem("file is npcpos or npcdata.");
        };
    }
}
