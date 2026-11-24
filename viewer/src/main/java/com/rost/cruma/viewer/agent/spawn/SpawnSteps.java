package com.rost.cruma.viewer.agent.spawn;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.npc.NpcDataDocument;
import com.rost.cruma.core.npc.NpcDataReview;
import com.rost.cruma.core.spawn.NpcPosDocument;
import com.rost.cruma.core.spawn.SpawnArea;
import com.rost.cruma.core.spawn.SpawnChecks.Issue;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.Target.Kind;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.SpawnTools.Desk;
import com.rost.cruma.viewer.npc.NpcDataSource;
import com.rost.cruma.viewer.spawn.SpawnSource;
import com.rost.cruma.viewer.spawn.SpawnSource.Phase;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class SpawnSteps {
    static final long PERSON_NANOS = TimeUnit.SECONDS.toNanos(60);
    static final int SHOWN_PROBLEMS = 10;

    private static final int DEFAULT_LIMIT = 30;
    private static final int MOST_LIMIT = 200;
    private static final double DEFAULT_RADIUS = 4000;
    private static final double MOST_RADIUS = 28000;
    private static final double TILE_HALF = 16384;
    private static final long LOAD_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final String NO_FOLDER =
            "No server script folder is set for this client. The person chooses it in Settings, on the page of the"
                    + " client, or in the NPC Spawns panel. In cruma.conf it is script_path in the client's entry of"
                    + " client_profiles.";
    private static final String HELD =
            "The person is dragging in NPC Spawns or has its review open. Call again when they are done.";

    private SpawnSteps() {}

    @FunctionalInterface
    interface Ready {
        boolean check() throws ToolProblem;
    }

    @FunctionalInterface
    interface Next {
        Step start() throws ToolProblem;
    }

    static void checkScripts(ToolArgs args, Desk desk) throws ToolProblem {
        checkScripts(args, desk.scriptPath());
    }

    static void checkScripts(ToolArgs args, @Nullable Path scriptPath) throws ToolProblem {
        var asked = args.getText("scripts");
        if (asked == null) {
            return;
        }
        Path wanted;
        try {
            wanted = SpawnSource.resolveFolder(Path.of(asked));
        } catch (InvalidPathException wrong) {
            wanted = null;
        }
        var own = scriptPath == null ? null : SpawnSource.resolveFolder(scriptPath);
        if (wanted == null || !wanted.equals(own)) {
            throw new ToolProblem("scripts applies only while no viewer is open, and this viewer reads "
                    + (own == null ? "no script folder" : own.toString())
                    + ". Leave scripts out to read that, or close the viewers you opened with world_close.");
        }
    }

    static Step after(Ready ready, Next next) {
        var deadline = System.nanoTime() + LOAD_NANOS;
        return new Step() {
            private @Nullable Step rest;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (rest == null) {
                    if (!ready.check()) {
                        if (System.nanoTime() > deadline) {
                            throw new ToolProblem("The server files are still loading. Call again in a moment.");
                        }
                        return null;
                    }
                    rest = next.start();
                }
                return rest.step();
            }

            @Override
            public void cancel() {
                if (rest != null) {
                    rest.cancel();
                }
            }
        };
    }

    static boolean spawnsReady(Desk desk) throws ToolProblem {
        var source = desk.spawns().getSource();
        if (source.getPhase() == Phase.EMPTY) {
            if (desk.scriptPath() == null) {
                throw new ToolProblem(NO_FOLDER);
            }
            source.open(desk.scriptPath());
            return false;
        }
        if (source.getPhase() == Phase.FAILED) {
            throw new ToolProblem("npcpos.txt could not be read: " + source.getMessage()
                    + " The person can choose another folder in NPC Spawns.");
        }
        return source.getPhase() == Phase.READY && source.getDocument() != null;
    }

    static boolean namesReady(Desk desk) throws ToolProblem {
        return spawnsReady(desk) && !desk.spawns().getNpcData().isCatalogLoading();
    }

    static boolean areNpcsReady(Desk desk) throws ToolProblem {
        if (!namesReady(desk)) {
            return false;
        }
        var npcs = desk.spawns().getNpcData();
        if (npcs.getPhase() == NpcDataSource.Phase.FAILED) {
            throw new ToolProblem("npcdata.txt could not be read: " + npcs.getMessage());
        }
        return npcs.getPhase() == NpcDataSource.Phase.READY && npcs.getDocument() != null;
    }

    static NpcPosDocument document(Desk desk) throws ToolProblem {
        var document = desk.spawns().getSource().getDocument();
        if (document == null) {
            throw new ToolProblem("npcpos.txt was closed. Call again.");
        }
        return document;
    }

    static int limit(ToolArgs args) throws ToolProblem {
        return Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, MOST_LIMIT);
    }

    static String point(double x, double y, double z) {
        return Math.round(x) + "," + Math.round(y) + "," + Math.round(z);
    }

    static NpcDataDocument npcDocument(Desk desk) throws ToolProblem {
        var document = desk.spawns().getNpcData().getDocument();
        if (document == null) {
            throw new ToolProblem("npcdata.txt was closed. Call again.");
        }
        return document;
    }

    static JSONObject describeChanges(NpcDataSource data, NpcDataDocument document, int limit) {
        var state = document.getState();
        var result = new JSONObject();
        result.put("unsaved", document.isModified());
        var changes = NpcDataReview.collectChanges(document, state, name -> itemLabel(data, name));
        result.put("changes", changes.size());
        var sections = new JSONArray();
        var left = limit;
        for (var section : NpcDataReview.groupIntoSections(changes, document.getBatchesSinceSave())) {
            var list = new JSONArray();
            for (var change : section.changes()
                    .subList(0, Math.max(0, Math.min(left, section.changes().size())))) {
                var item = new JSONObject();
                item.put("npc", change.npc());
                item.put("line", change.line());
                item.put("setting", change.key());
                item.put("what", change.details());
                list.add(item);
            }
            left -= list.size();
            var item = new JSONObject();
            item.put(
                    "title",
                    section.title().isEmpty() ? section.changes().getFirst().npc() : section.title());
            item.put("count", section.changes().size());
            item.put("list", list);
            sections.add(item);
        }
        result.put("sections", sections);
        result.put("more", Math.max(0, changes.size() - limit));
        var issues = new ArrayList<>(NpcDataReview.findIssues(document, state, data.getItems(), data.getSkills()));
        issues.sort(Comparator.comparing((NpcDataReview.Issue issue) -> !issue.error()));
        result.put("errors", issues.stream().filter(NpcDataReview.Issue::error).count());
        if (!issues.isEmpty()) {
            result.put("problems", NpcEditor.describeIssues(issues));
        }
        var pch = data.getPchChange();
        if (pch != null && !pch.isEmpty()) {
            result.put(
                    "npc_pch",
                    "+"
                            + pch.addedLines().size()
                            + " lines, -"
                            + pch.removedNames().size()
                            + " lines");
        }
        if (data.hasChangedOnDisk()) {
            result.put(
                    "changed_on_disk", "npcdata.txt changed on disk since it was read. A save would overwrite that.");
        }
        return result;
    }

    static @Nullable SpawnArea area(ToolArgs args, Desk desk) throws ToolProblem {
        var text = args.getText("target");
        return text == null ? null : area(args, desk.places().locate(text));
    }

    static SpawnArea area(ToolArgs args, Target target) throws ToolProblem {
        if (target.kind() == Kind.TILE && !args.has("radius")) {
            return SpawnArea.box(
                    target.x() - TILE_HALF, target.y() - TILE_HALF, target.x() + TILE_HALF, target.y() + TILE_HALF);
        }
        var radius = args.getNumber("radius", DEFAULT_RADIUS);
        if (radius <= 0 || radius > MOST_RADIUS) {
            throw new ToolProblem("radius must be above 0 and at most " + Math.round(MOST_RADIUS)
                    + ", as one read holds nine geodata regions. Check a larger area tile by tile.");
        }
        return SpawnArea.around(target.x(), target.y(), radius);
    }

    static @Nullable ToolResult waitForPerson(long deadline) throws ToolProblem {
        if (System.nanoTime() > deadline) {
            throw new ToolProblem(HELD);
        }
        return null;
    }

    static JSONArray describeIssues(List<Issue> issues) {
        var list = new JSONArray();
        for (var issue : issues.subList(0, Math.min(SHOWN_PROBLEMS, issues.size()))) {
            var item = new JSONObject();
            item.put("severity", issue.isError() ? "error" : "warning");
            item.put("where", issue.where());
            item.put("message", issue.message());
            list.add(item);
        }
        return list;
    }

    private static String itemLabel(NpcDataSource data, String name) {
        var known = data.getItems().byName(name);
        var client = known == null ? null : data.getCatalog().getItem(known.id());
        return client == null || client.name().isBlank() ? name : client.name();
    }
}
