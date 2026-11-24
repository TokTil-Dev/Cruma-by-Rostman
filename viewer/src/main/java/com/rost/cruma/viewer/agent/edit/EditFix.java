package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.editing.EditingCheck;
import com.rost.cruma.viewer.editing.EditingCheck.Finding;
import com.rost.cruma.viewer.editing.EditingCheck.Kind;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.Change;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

final class EditFix {
    private EditFix() {}

    static Step start(ToolArgs args, ViewerContext viewer, EditingSession session) throws ToolProblem {
        var kind = kind(args.getText("kind"));
        var only = new HashSet<>(args.list("actors"));
        var terrain = viewer.scene().getTerrainEditing();
        var probe = session.getGroundProbe();
        terrain.updateProbe(probe);
        return EditSteps.editing(viewer, session, () -> {
            var unsaved = new ArrayList<Change>();
            for (var change : session.getChanges()) {
                if (session.isMapDirty(change.map())) {
                    unsaved.add(change);
                }
            }
            var check = EditingCheck.ofChanges(
                    session, unsaved, EditSteps.checkBounds(viewer, session, unsaved), probe.snapshot());
            var found = new ArrayList<Finding>();
            return EditSteps.after(
                    Step.onWorker(() -> {
                        found.addAll(check.apply(new AtomicBoolean()));
                        return ToolResult.text("");
                    }),
                    () -> Step.done(fix(viewer, session, chosen(found, kind, only))));
        });
    }

    static @Nullable Kind kind(@Nullable String word) throws ToolProblem {
        if (word == null) {
            return null;
        }
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "floating" -> Kind.FLOATING;
            case "outside" -> Kind.OUTSIDE;
            case "copies" -> Kind.COPIES;
            default -> throw new ToolProblem("kind must be floating, outside or copies.");
        };
    }

    static List<Finding> chosen(List<Finding> found, @Nullable Kind kind, Set<String> only) throws ToolProblem {
        var byKind = new EnumMap<Kind, List<Finding>>(Kind.class);
        for (var finding : found) {
            if (EditingCheck.fixOf(finding).isEmpty()
                    || kind != null && finding.kind() != kind
                    || !only.isEmpty() && !only.contains(finding.actor())) {
                continue;
            }
            byKind.computeIfAbsent(finding.kind(), _ -> new ArrayList<>()).add(finding);
        }
        if (byKind.size() > 1) {
            var counts = new ArrayList<String>();
            byKind.forEach((each, list) -> counts.add(each.name().toLowerCase(Locale.ROOT) + " (" + list.size() + ")"));
            throw new ToolProblem(
                    "The warnings with a fix are of several kinds, and a call fixes one kind as one Undo step. Give"
                            + " kind: "
                            + String.join(", ", counts) + ".");
        }
        return byKind.isEmpty() ? List.of() : byKind.values().iterator().next();
    }

    private static ToolResult fix(ViewerContext viewer, EditingSession session, List<Finding> chosen) {
        var data = new JSONObject();
        data.put("fixed", chosen.size());
        if (chosen.isEmpty()) {
            data.put("note", "No warning of the call has a fix. edit_changes lists the warnings, each with its fix.");
            return ToolResult.data(data);
        }
        var label = EditSteps.AI + EditingCheck.fixLabel(chosen);
        EditingCheck.fix(session, chosen, label, () -> {
            var settings = viewer.scene().getEditingSettings();
            if (settings == null || settings.moveActorsToTheirTile()) {
                session.moveToTheirTiles(EditSteps.loadedMaps(viewer, session)::contains);
            }
        });
        var actors = new ArrayList<String>();
        for (var finding : chosen) {
            actors.add(finding.actor());
        }
        data.put("kind", chosen.getFirst().kind().name().toLowerCase(Locale.ROOT));
        data.put("fix", EditingCheck.fixOf(chosen.getFirst()));
        data.put("actors", actors.subList(0, Math.min(20, actors.size())));
        data.put("step", label);
        return ToolResult.data(data);
    }
}
