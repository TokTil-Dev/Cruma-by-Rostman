package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.AI;
import static com.rost.cruma.viewer.agent.edit.EditSteps.filter;
import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;
import static com.rost.cruma.viewer.agent.edit.EditSteps.pick;
import static com.rost.cruma.viewer.agent.edit.EditSteps.subject;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditSteps.Picked;
import java.util.ArrayList;

final class ActorDeleteTool {
    private ActorDeleteTool() {}

    static Step delete(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var filter = filter(args, viewer, session);
        var limit = limit(args);
        var preview = args.readFlag("preview", false);
        var picked = new Picked();
        return EditSteps.editing(
                viewer,
                session,
                () -> pick(args, viewer, session, filter, picked, () -> {
                    var chosen = new ArrayList<String>();
                    for (var path : picked.actors.keySet()) {
                        var reason = session.deleteRestriction(path);
                        if (reason.isEmpty()) {
                            chosen.add(path);
                        } else {
                            picked.refused.add(reason, path);
                        }
                    }
                    var data = new JSONObject();
                    data.put(preview ? "would_delete" : "deleted", chosen.size());
                    data.put("could_not", picked.refused.getCount());
                    if (picked.refused.getCount() > 0) {
                        data.put("reasons", picked.refused.write());
                    }
                    data.put("actors", chosen.subList(0, Math.min(limit, chosen.size())));
                    if (chosen.size() > limit) {
                        data.put("more", chosen.size() - limit);
                    }
                    if (!preview && !chosen.isEmpty()) {
                        var label = AI + "Delete " + subject(chosen);
                        session.deleteActors(chosen, label);
                        data.put("step", label);
                    }
                    return Step.done(ToolResult.data(data));
                }));
    }
}
