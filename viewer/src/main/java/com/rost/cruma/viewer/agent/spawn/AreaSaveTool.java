package com.rost.cruma.viewer.agent.spawn;

import static com.rost.cruma.viewer.agent.spawn.AreaSteps.document;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.isAreaDataReady;
import static com.rost.cruma.viewer.agent.spawn.AreaSteps.waitForPerson;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.PERSON_NANOS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.SHOWN_PROBLEMS;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.after;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.describeIssues;
import static com.rost.cruma.viewer.agent.spawn.SpawnSteps.limit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.area.AreaDataFile;
import com.rost.cruma.core.area.AreaDocument.State;
import com.rost.cruma.core.area.AreaReview;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

final class AreaSaveTool {
    private AreaSaveTool() {}

    static Step areaSave(ToolArgs args, Desk desk) throws ToolProblem {
        var limit = limit(args);
        return after(() -> isAreaDataReady(desk), () -> new Saving(desk, limit));
    }

    private static final class Saving implements Step {
        private final Desk desk;
        private final int limit;
        private final AtomicReference<AreaReview> review = new AtomicReference<>();
        private final long deadline = System.nanoTime() + PERSON_NANOS;
        private @Nullable Step work;
        private @Nullable State reviewed;
        private boolean writing;

        Saving(Desk desk, int limit) {
            this.desk = desk;
            this.limit = limit;
        }

        @Override
        public @Nullable ToolResult step() throws ToolProblem {
            var areas = desk.areas();
            var source = areas.getSource();
            if (writing) {
                return source.isSaving() ? null : answer();
            }
            if (work == null) {
                if (areas.isHeld() || source.isSaving()) {
                    return waitForPerson(deadline);
                }
                var document = document(desk);
                if (!document.isModified()) {
                    return ToolResult.text("Nothing to save: " + AreaDataFile.FILE_NAME + " has no changes.");
                }
                var file = document.getFile();
                var saved = document.getSavedState();
                var now = document.getState();
                reviewed = now;
                work = Step.onWorker(() -> {
                    review.set(AreaReview.of(file, saved, now));
                    return ToolResult.text("reviewed");
                });
            }
            if (work.step() == null) {
                return null;
            }
            work = null;
            var document = document(desk);
            if (areas.isHeld() || document.getState() != reviewed) {
                return null;
            }
            var found = review.get();
            if (!found.errors().isEmpty()) {
                throw new ToolProblem(refusal(found.errors()));
            }
            if (source.hasChangedOnDisk()) {
                throw new ToolProblem("Not saved: " + AreaDataFile.FILE_NAME + " changed on disk since it was read,"
                        + " and a save would overwrite that. Ask the person: they can save from the Areas panel, or"
                        + " read the file again.");
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
            var source = desk.areas().getSource();
            if (source.isMessageError()) {
                throw new ToolProblem(source.getMessage());
            }
            var found = review.get();
            var file = source.getFile();
            var lines = new JSONObject();
            lines.put("added", found.added());
            lines.put("removed", found.removed());
            var result = new JSONObject();
            result.put("saved", String.valueOf(file));
            result.put("note", source.getMessage());
            result.put("lines", lines);
            var areas = new JSONArray();
            for (var change :
                    found.areas().subList(0, Math.min(limit, found.areas().size()))) {
                var item = new JSONObject();
                item.put("area", SpawnEditor.number(change.index()));
                item.put("name", change.name());
                item.put("what", change.summary());
                areas.add(item);
            }
            result.put("areas", found.areas().size());
            result.put("list", areas);
            var changes = new JSONArray();
            for (var change :
                    found.changes().subList(0, Math.min(limit, found.changes().size()))) {
                var item = new JSONObject();
                item.put("line", change.line());
                item.put("where", change.where());
                item.put("what", change.what());
                changes.add(item);
            }
            result.put("changes", found.changes().size());
            result.put("lines_changed", changes);
            result.put(
                    "more",
                    Math.max(0, Math.max(found.areas().size(), found.changes().size()) - limit));
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
                    .append(". Fix them with area_edit, or undo them, and save again.");
            for (var issue : errors.subList(0, Math.min(SHOWN_PROBLEMS, errors.size()))) {
                text.append('\n').append(issue.where()).append(": ").append(issue.message());
            }
            return text.toString();
        }
    }
}
