package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentSteps;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingTimeline;
import java.util.List;

final class EditHistoryTool {
    private EditHistoryTool() {}

    static Step history(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var timeline = session.getTimeline();
        var action = args.getText("action", "list");
        var limit = limit(args);
        return switch (action) {
            case "list" ->
                EditSteps.editing(viewer, session, () -> {
                    timeline.sync();
                    return Step.done(ToolResult.data(historyAnswer(timeline, limit)));
                });
            case "undo", "redo" -> {
                var undo = action.equals("undo");
                var left = new int[] {Math.clamp(args.getInteger("count", 1), 1, AgentSteps.MOST_STEPS)};
                var taken = new JSONArray();
                var held = new String[1];
                var hidden = viewer.agent().isHidden();
                yield EditSteps.editing(viewer, session, () -> () -> {
                    if (EditSteps.busy(viewer, session) != null) {
                        return null;
                    }
                    timeline.sync();
                    var steps = undo ? timeline.getDone() : timeline.getUndone();
                    if (left[0] > 0
                            && !steps.isEmpty()
                            && !AgentSteps.isOwn(steps.getFirst().label(), hidden)) {
                        held[0] = steps.getFirst().label();
                        left[0] = 0;
                    }
                    if (left[0] > 0 && !steps.isEmpty()) {
                        var step = undo ? timeline.undo() : timeline.redo();
                        if (step != null) {
                            left[0]--;
                            taken.add(step.label());
                        }
                        return null;
                    }
                    var data = historyAnswer(timeline, limit);
                    data.put(undo ? "undone" : "redone", taken);
                    if (held[0] != null) {
                        data.put("held", AgentSteps.held(held[0]));
                    }
                    return ToolResult.data(data);
                });
            }
            default -> throw new ToolProblem("action must be list, undo or redo.");
        };
    }

    private static JSONObject historyAnswer(EditingTimeline timeline, int limit) {
        var data = new JSONObject();
        data.put("can_undo", labels(timeline.getDone(), limit));
        data.put("can_redo", labels(timeline.getUndone(), limit));
        return data;
    }

    private static JSONArray labels(List<EditingTimeline.Step> steps, int limit) {
        var labels = new JSONArray();
        for (var step : steps.subList(0, Math.min(limit, steps.size()))) {
            labels.add(step.label());
        }
        return labels;
    }
}
