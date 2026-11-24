package com.rost.cruma.viewer.agent.edit;

import static com.rost.cruma.viewer.agent.edit.EditSteps.limit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingCheck;
import com.rost.cruma.viewer.editing.EditingCheck.Finding;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import com.rost.cruma.viewer.editing.EditingSession.ChangeKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class EditChangesTool {
    private EditChangesTool() {}

    static Step changes(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var limit = limit(args);
        var terrain = viewer.scene().getTerrainEditing();
        var controller = terrain.getController();
        var probe = session.getGroundProbe();
        terrain.updateProbe(probe);
        return EditSteps.editing(viewer, session, () -> {
            var unsaved = new ArrayList<Change>();
            for (var change : session.getChanges()) {
                if (session.isMapDirty(change.map())) {
                    unsaved.add(change);
                }
            }
            var kinds = new EnumMap<ChangeKind, Integer>(ChangeKind.class);
            var maps = new TreeMap<String, Integer>();
            var rows = new JSONArray();
            for (var change : unsaved) {
                change.kinds().forEach(kind -> kinds.merge(kind, 1, Integer::sum));
                maps.merge(change.map(), 1, Integer::sum);
                if (rows.size() < limit) {
                    var row = new JSONObject();
                    row.put("actor", change.path());
                    row.put("what", change.detail());
                    row.put("at", EditSteps.point(change.location()));
                    rows.add(row);
                }
            }
            var data = new JSONObject();
            data.put("unsaved", viewer.scene().doesEditingNeedSave());
            data.put("changes", unsaved.size());
            data.put("by_kind", kinds);
            data.put("by_map", maps);
            data.put("list", rows);
            if (unsaved.size() > rows.size()) {
                data.put("more", unsaved.size() - rows.size());
            }
            var changedTerrains = new JSONArray();
            for (var document : controller.getDocuments()) {
                if (document.isDirty() || document.hasSourceChanges()) {
                    changedTerrains.add(document.getOwner());
                }
            }
            if (!changedTerrains.isEmpty()) {
                data.put("terrain", changedTerrains.getFirst());
                data.put("terrains", changedTerrains);
            }
            var known = EditSteps.checkBounds(viewer, session, unsaved);
            var check = EditingCheck.ofChanges(session, unsaved, known, probe.snapshot());
            var warnings = new JSONArray();
            return EditSteps.after(
                    Step.onWorker(() -> {
                        var cancelled = new AtomicBoolean();
                        for (Finding finding : check.apply(cancelled)) {
                            var warning = new JSONObject();
                            warning.put("kind", finding.kind().name());
                            warning.put("title", finding.title());
                            warning.put("detail", finding.detail());
                            if (!finding.actor().isEmpty()) {
                                warning.put("actor", finding.actor());
                            }
                            var fix = EditingCheck.fixOf(finding);
                            if (!fix.isEmpty()) {
                                warning.put("fix", fix);
                            }
                            warnings.add(warning);
                        }
                        return ToolResult.text("");
                    }),
                    () -> {
                        data.put("warnings", warnings.subList(0, Math.min(limit, warnings.size())));
                        data.put("warning_count", warnings.size());
                        return Step.done(ToolResult.data(data));
                    });
        });
    }
}
