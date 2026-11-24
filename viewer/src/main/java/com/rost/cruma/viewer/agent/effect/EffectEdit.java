package com.rost.cruma.viewer.agent.effect;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.ClassSource;
import com.rost.cruma.core.editing.ClassSource.Source;
import com.rost.cruma.core.editing.EffectEditSession;
import com.rost.cruma.core.editing.EffectEditSession.Placement;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.core.editing.EffectEdits.Copy;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditExport;
import com.rost.cruma.viewer.agent.effect.EffectChanges.ActionFields;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.EffectEditController.PreparedExport;
import com.rost.cruma.viewer.editing.EffectEditController.SessionCall;
import com.rost.cruma.viewer.ui.EffectsPanel;
import com.rost.cruma.viewer.ui.SkillCasting;
import com.rost.cruma.viewer.ui.ToolWindows.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.Nullable;

final class EffectEdit implements Step {
    private static final List<String> ACTIONS = List.of(
            "read", "script", "scripts", "change", "add", "remove", "copy", "rename", "undo", "redo", "drop", "discard",
            "export", "install", "restore");
    private static final int LEFT_OUT_SHOWN = 20;
    private static final int SCRIPT_CHARS = 100_000;
    private static final long READY_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long WORK_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final int PROPERTIES_SHOWN = 40;

    private final ViewerContext viewer;
    private final ToolArgs args;
    private final String action;
    private final long started = System.nanoTime();
    private @Nullable Step work;

    private EffectEdit(ViewerContext viewer, ToolArgs args, String action) {
        this.viewer = viewer;
        this.args = args;
        this.action = action;
    }

    @FunctionalInterface
    private interface Then<T> {
        ToolResult with(T value) throws ToolProblem;
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var action = args.getText("action", "read").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be one of " + String.join(", ", ACTIONS) + ".");
        }
        var access = viewer.agent().getAccess();
        var needed = switch (action) {
            case "read" -> Access.SEE;
            case "script", "scripts", "export" -> args.has("folder") ? Access.WRITE : Access.SEE;
            case "install", "restore" -> Access.WRITE;
            default -> Access.EDIT;
        };
        if (!access.allows(needed)) {
            throw new ToolProblem("effect_edit "
                    + action
                    + (args.has("folder") ? " into a folder of your own" : "")
                    + " needs "
                    + needed.getLabel()
                    + " access, and this viewer gives "
                    + access.getLabel()
                    + ". Ask the person to raise it in the AI agent panel of Cruma.");
        }
        if (action.equals("change") || action.equals("add") || action.equals("remove")) {
            if (args.has("skill")) {
                EffectChanges.actionFields(args.getJson().getJSONObject("set"));
            } else {
                EffectChanges.fields(args.getJson().getJSONObject("set"));
            }
        }
        viewer.system().getWorkspace().tools.open(Tool.EFFECTS);
        return new EffectEdit(viewer, args, action);
    }

    static String discard(EffectEditController controller, EffectSteps steps) throws ToolProblem {
        if (!controller.hasEdits()) {
            throw new ToolProblem("There is nothing to discard: no effect or skill is edited.");
        }
        var before = controller.getEdits();
        var counts = controller.getCounts();
        controller.change(EffectEdits.NONE);
        controller.clearError();
        steps.made(before, controller.getEdits());
        return counts;
    }

    @Override
    public @Nullable ToolResult step() throws ToolProblem {
        var waited = System.nanoTime() - started;
        if (work == null) {
            var panel = viewer.system().getEffects();
            var controller = panel.getEditController();
            if (controller == null
                    || !controller.isReady()
                    || panel.isListing()
                    || !EffectPlay.areTablesRead(panel, waited)) {
                if (waited > READY_NANOS) {
                    throw new ToolProblem("The Effects tool did not get ready in time. Call again in a moment.");
                }
                return null;
            }
            if (!panel.getListProblem().isEmpty()) {
                throw new ToolProblem(panel.getListProblem());
            }
            if (!List.of("read", "script", "scripts", "export").contains(action)) {
                if (controller.getStale() != null) {
                    throw new ToolProblem(
                            "Saved edits of an earlier run wait in the Effects tool, as files of the client changed"
                                    + " since. Ask the person to Apply or Discard them there first.");
                }
                if (controller.isDragging()) {
                    if (waited > READY_NANOS) {
                        throw new ToolProblem(
                                "The person is still dragging a field of the Effects tool. Call again when they let"
                                        + " go.");
                    }
                    return null;
                }
            }
            work = begin(panel, controller);
        }
        if (waited > WORK_NANOS) {
            throw new ToolProblem("effect_edit " + action + " did not end in time. read tells what the edits are now.");
        }
        return work.step();
    }

    @Override
    public void cancel() {
        if (work != null) {
            work.cancel();
        }
    }

    private Step begin(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        return switch (action) {
            case "read" -> read(panel, controller);
            case "script" -> script(panel, controller);
            case "scripts" -> scripts(controller);
            case "change" -> change(panel, controller);
            case "add" -> add(panel, controller);
            case "remove" -> remove(panel, controller);
            case "copy" -> copy(panel, controller);
            case "rename" -> rename(panel, controller);
            case "undo" -> Step.done(undo(controller));
            case "redo" -> Step.done(redo(controller));
            case "discard" -> Step.done(discard(controller));
            case "drop" -> drop(panel, controller);
            case "export" -> export(panel, controller);
            default -> install(panel, controller, action.equals("restore"));
        };
    }

    private Step read(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        if (args.has("skill")) {
            var skill = skill(panel);
            var name = skill.className();
            var edited = controller.getEdits().skills().containsKey(name);
            return after(controller.read(session -> EffectChanges.readSkill(session, name)), data -> {
                data.put("skill", skill.skillId());
                data.put("name", panel.getCasting().getSkillName(skill));
                data.put("edited", edited);
                data.put("can_change", EffectChanges.ACTION_FIELDS);
                return ToolResult.data(data);
            });
        }
        if (!args.has("effect")) {
            return status(panel, controller);
        }
        var effect = effect(panel);
        var path = effect.path();
        var emitter = args.getText("emitter");
        var query = args.getText("query");
        var limit = Math.clamp(args.getInteger("limit", PROPERTIES_SHOWN), 1, 200);
        var copy = controller.getEdits().copies().get(path);
        return after(
                controller.read(session -> EffectChanges.readEffect(session, path, emitter, query, limit)), data -> {
                    data.put("edited", controller.isEdited(path));
                    if (copy != null) {
                        data.put("copy_of", copy.source());
                    }
                    var users = users(panel, path);
                    if (!users.isEmpty()) {
                        data.put("used_by", users);
                    }
                    data.put("can_change", EffectChanges.EFFECT_FIELDS);
                    return ToolResult.data(data);
                });
    }

    private Step script(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var folder = args.readFlag("save", args.has("folder")) ? scriptFolder(panel, controller) : null;
        if (args.has("skill")) {
            var name = skill(panel).className();
            var edited = !controller.getEdits().skillEdit(name).isEmpty();
            return after(
                    controller.read(session -> saved(session.getSkillSource(name), folder)),
                    source -> script(source, edited, folder));
        }
        if (!args.has("effect")) {
            throw new ToolProblem("script needs an effect or a skill.");
        }
        var path = effect(panel).path();
        var edited = controller.isEdited(path);
        return after(
                controller.read(session -> saved(session.getSource(path), folder)),
                source -> script(source, edited, folder));
    }

    private Step scripts(EffectEditController controller) throws ToolProblem {
        var client = controller.getClientRoot();
        List<Path> files;
        try {
            files = EffectEditSession.findScriptPackageFiles(client);
        } catch (IOException failure) {
            throw new ToolProblem("The packages of the client could not be listed: " + failure.getMessage());
        }
        if (files.isEmpty()) {
            throw new ToolProblem("The client has no package of effects or skills.");
        }
        var system = files.stream()
                .filter(file ->
                        file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".u"))
                .map(Path::getParent)
                .findFirst()
                .orElse(null);
        var wanted = args.getText("package");
        if (wanted != null) {
            var names = files.stream().map(EffectEdit::packageName).toList();
            files = files.stream()
                    .filter(file -> packageName(file).equalsIgnoreCase(wanted))
                    .toList();
            if (files.isEmpty()) {
                throw new ToolProblem(
                        "The client has no package " + wanted + ". It has " + String.join(", ", names) + ".");
            }
        }
        var folder = scriptFolder(viewer.system().getEffects(), controller);
        var packages = files;
        return Step.onWorker(() -> {
            var written = new ArrayList<ClassSource.Written>();
            try {
                for (var file : packages) {
                    written.add(ClassSource.writePackage(file, system, folder, Thread.currentThread()::isInterrupted));
                }
            } catch (IOException failure) {
                throw new ToolProblem("The scripts could not be written: " + failure.getMessage());
            }
            return scriptsAnswer(written, folder);
        });
    }

    private Path scriptFolder(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var folder = args.has("folder") ? folder(args.getRequired("folder")) : panel.getScriptFolder();
        if (folder.startsWith(controller.getClientRoot().toAbsolutePath().normalize())) {
            throw new ToolProblem("Scripts go into a folder of their own, not into the client.");
        }
        return folder;
    }

    private Step status(EffectsPanel panel, EffectEditController controller) {
        var edits = controller.getEdits();
        var steps = viewer.agent().getEffectSteps();
        var data = new JSONObject();
        data.put("client", controller.getClientRoot().toString());
        data.put("edits", controller.getCounts().isEmpty() ? "none" : controller.getCounts());
        var effects = new ArrayList<String>();
        for (var path : edits.classes().keySet()) {
            if (!edits.copies().containsKey(path)) {
                effects.add(path);
            }
        }
        data.put("effects", effects);
        var copies = new JSONObject();
        for (var copy : edits.copies().values()) {
            copies.put(copy.path(), copy.source());
        }
        data.put("copies", copies);
        data.put("skills", new ArrayList<>(edits.skills().keySet()));
        data.put("unexported", controller.hasEdits() && controller.isUnexported());
        data.put("your_undo_steps", steps.countUndoable(edits));
        var stale = controller.getStale();
        if (stale != null) {
            data.put(
                    "waiting",
                    "Saved edits of an earlier run, made on files that changed since: " + stale.changed()
                            + ". The person applies or discards them.");
        }
        if (!controller.getError().isEmpty()) {
            data.put("error", controller.getError());
        }
        var images = controller.getImageProblems();
        if (!images.isEmpty()) {
            data.put("images", images);
        }
        if (steps.getLastExport() != null) {
            data.put("last_export", steps.getLastExport().toString());
        }
        data.put("effects_listed", panel.getEffectList().size());
        data.put("skills_listed", panel.getSkillList().size());
        return after(controller.read(EffectEditSession::getEditProblems), problems -> {
            if (!problems.isEmpty()) {
                var texts = new ArrayList<String>();
                for (var problem : problems) {
                    texts.add(problem.text());
                }
                data.put("problems", texts);
            }
            return ToolResult.data(data);
        });
    }

    private Step change(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var set = args.getJson().getJSONObject("set");
        if (args.has("skill")) {
            var skill = skill(panel).className();
            var fields = EffectChanges.actionFields(set);
            if (fields.effect() != null) {
                fields = withEffect(fields, effect(panel, fields.effect()).path());
            }
            var resolved = fields;
            var key = args.getText("key");
            return changing(controller, session -> EffectChanges.action(session, skill, key, resolved), skill);
        }
        var path = effect(panel).path();
        var fields = EffectChanges.fields(set);
        var properties = new LinkedHashMap<String, String>();
        var raw = args.getJson().getJSONObject("properties");
        if (raw != null) {
            for (var entry : raw.entrySet()) {
                properties.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        if (!fields.knobs()
                && fields.on() == null
                && fields.texture() == null
                && fields.image() == null
                && properties.isEmpty()) {
            throw new ToolProblem("set or properties names nothing to change. set takes "
                    + String.join(", ", EffectChanges.EFFECT_FIELDS) + ".");
        }
        var emitter = args.getText("emitter");
        return changing(controller, session -> EffectChanges.effect(session, path, emitter, fields, properties), path);
    }

    private Step add(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var set = args.getJson().getJSONObject("set");
        if (args.has("skill")) {
            var skill = skill(panel).className();
            var fields = EffectChanges.actionFields(set);
            if (fields.phase() == null || fields.effect() == null) {
                throw new ToolProblem("add on a skill takes set with phase and effect, as Add effect asks for them.");
            }
            var phase = fields.phase();
            var effect = effect(panel, fields.effect()).path();
            return changing(
                    controller,
                    session -> List.of("action " + EffectChanges.addAction(session, skill, phase, effect) + " in "
                            + SkillCasting.phaseName(phase)),
                    skill);
        }
        var path = effect(panel).path();
        var emitter = required(
                "emitter",
                "add on an effect copies an emitter: name it with emitter, and the effect it comes from with set.from."
                        + " Without from it duplicates an emitter of this effect.");
        var from = EffectChanges.fields(set).from();
        var source = from == null ? path : effect(panel, from).path();
        return changing(
                controller,
                session -> {
                    var key = EffectChanges.emitter(session, source, emitter).key();
                    var name = source.equals(path)
                            ? session.duplicateEmitter(path, key)
                            : session.addEmitter(path, source, key);
                    return List.of("emitter " + name);
                },
                path);
    }

    private Step remove(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        if (args.has("skill")) {
            var skill = skill(panel).className();
            var key = required("key", "remove on a skill takes the key of an action, from read.");
            var phase = EffectChanges.actionFields(args.getJson().getJSONObject("set"))
                    .phase();
            return changing(
                    controller,
                    session -> List.of("action " + EffectChanges.removeAction(session, skill, key, phase)),
                    skill);
        }
        var path = effect(panel).path();
        var emitter = args.getText("emitter");
        if (emitter != null) {
            return changing(
                    controller,
                    session -> List.of("emitter " + EffectChanges.removeEmitter(session, path, emitter)),
                    path);
        }
        if (!controller.getEdits().copies().containsKey(path)) {
            throw new ToolProblem("remove takes an emitter of the effect, or a copy that is not installed. " + path
                    + " is no such copy.");
        }
        return changing(controller, session -> List.of("copy " + EffectChanges.removeCopy(session, path)), path);
    }

    private Step copy(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        if (args.has("skill")) {
            var skill = skill(panel).className();
            var key = required(
                    "key",
                    "copy on a skill makes a copy of the effect of an action: name the action with key, from read.");
            return changing(
                    controller, session -> List.of("copy " + EffectChanges.copyForSkill(session, skill, key)), skill);
        }
        var path = effect(panel).path();
        var name = args.getText("name");
        return changing(
                controller,
                session -> List.of("copy " + session.copy(path, name != null ? name : session.suggestCopyName(path))),
                path);
    }

    private Step rename(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var path = effect(panel).path();
        var copy = controller.getEdits().copies().get(path);
        if (copy == null) {
            throw new ToolProblem("rename takes a copy that is not installed. " + path + " is no such copy.");
        }
        var name = required("name", "rename takes the new name of the copy in name.");
        panel.copyRenamed(path, new Copy(name, copy.source()).path());
        return changing(controller, session -> List.of("copy " + session.renameCopy(path, name)), path);
    }

    private Step changing(EffectEditController controller, SessionCall<List<String>> call, String subject) {
        var before = controller.getEdits();
        return after(controller.change(call), said -> {
            var steps = viewer.agent().getEffectSteps();
            var now = controller.getEdits();
            var data = new JSONObject();
            if (now.equals(before)) {
                data.put("changed", List.of());
                data.put("note", "Nothing changed: the edits already had these values.");
            } else {
                steps.made(before, now);
                data.put("changed", said);
            }
            data.put("edits", controller.getCounts());
            data.put("your_undo_steps", steps.countUndoable(now));
            return ToolResult.data(data).saying("Changed " + subject + " in the Effects tool, one Undo step");
        });
    }

    private ToolResult undo(EffectEditController controller) throws ToolProblem {
        var steps = viewer.agent().getEffectSteps();
        if (!steps.canUndo(controller.getEdits()) || !controller.canUndo()) {
            throw new ToolProblem(
                    steps.countUndoable(controller.getEdits()) == 0 && controller.canUndo()
                            ? "The last step of the effect edits is not yours, or you made none: Undo stops at a step"
                                    + " of the person. Ask them, or change the value back."
                            : "There is no step of yours to take back.");
        }
        controller.undo();
        steps.undid();
        var data = new JSONObject();
        data.put("undone", true);
        data.put("edits", controller.getCounts());
        data.put("your_undo_steps", steps.countUndoable(controller.getEdits()));
        return ToolResult.data(data).saying("Took back a step of the effect edits");
    }

    private ToolResult discard(EffectEditController controller) throws ToolProblem {
        if (!viewer.agent().isOwned()) {
            throw new ToolProblem(
                    "This is a window of the person, and only they discard its effect edits. Take back your own steps"
                            + " with undo, or ask them.");
        }
        var data = new JSONObject();
        data.put("discarded", discard(controller, viewer.agent().getEffectSteps()));
        data.put("your_undo_steps", viewer.agent().getEffectSteps().countUndoable(controller.getEdits()));
        return ToolResult.data(data).saying("Discarded the effect edits, one Undo step");
    }

    private ToolResult redo(EffectEditController controller) throws ToolProblem {
        var steps = viewer.agent().getEffectSteps();
        if (!steps.canRedo(controller.getEdits()) || !controller.canRedo()) {
            throw new ToolProblem(
                    "There is no step of yours to do again: Redo follows your own Undo only while nothing changed"
                            + " after it.");
        }
        var expected = steps.getRedone();
        controller.redo();
        var data = new JSONObject();
        data.put("redone", controller.getEdits().equals(expected));
        data.put("edits", controller.getCounts());
        data.put("your_undo_steps", steps.countUndoable(controller.getEdits()));
        return ToolResult.data(data).saying("Did a step of the effect edits again");
    }

    private Step drop(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        var skill = args.has("skill");
        var path = skill ? skill(panel).className() : effect(panel).path();
        return after(controller.read(EffectEditSession::getEditProblems), problems -> {
            var problem = EffectChanges.problemOf(problems, path, skill);
            if (problem == null) {
                throw new ToolProblem("The edit of " + path
                        + " can be used, so there is nothing to drop. read without effect or skill lists the"
                        + " edits that cannot.");
            }
            var before = controller.getEdits();
            controller.drop(problem);
            viewer.agent().getEffectSteps().made(before, controller.getEdits());
            var data = new JSONObject();
            data.put("dropped", problem.text());
            data.put("edits", controller.getCounts());
            return ToolResult.data(data).saying("Dropped an edit that cannot be used");
        });
    }

    private Step export(EffectsPanel panel, EffectEditController controller) throws ToolProblem {
        if (!controller.hasEdits()) {
            throw new ToolProblem("There is nothing to export: no effect or skill is edited.");
        }
        var placement = switch (args.getText("copies", "custom")) {
            case "custom" -> Placement.CUSTOM_PACKAGE;
            case "source" -> Placement.SOURCE_PACKAGE;
            default ->
                throw new ToolProblem(
                        "copies must be custom, for LineageEffect_Custom.u, or source, for the package of each"
                                + " effect.");
        };
        var folder = args.has("folder") ? folder(args.getRequired("folder")) : panel.getNewExportFolder();
        var client = controller.getClientRoot();
        if (folder.startsWith(client)) {
            throw new ToolProblem(
                    "An export goes into a folder of its own, not into the client. install puts it into the client.");
        }
        if (Files.exists(folder)) {
            throw new ToolProblem("The folder " + folder + " is there already. An export goes into a new folder.");
        }
        var preparing = controller.prepareExport(placement);
        var written = new CompletableFuture<PreparedExport>();
        return new Step() {
            private @Nullable CompletableFuture<List<Path>> publishing;
            private @Nullable PreparedExport prepared;

            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                if (publishing == null) {
                    if (!preparing.isDone()) {
                        return null;
                    }
                    prepared = taken(preparing);
                    var plan = prepared.plan();
                    if (!plan.isValid()) {
                        throw new ToolProblem("The edits cannot be exported: " + String.join(" ", plan.getErrors()));
                    }
                    publishing = controller.publish(prepared, folder, written::isCancelled);
                    return null;
                }
                if (!publishing.isDone()) {
                    return null;
                }
                taken(publishing);
                viewer.agent().getEffectSteps().setLastExport(folder);
                var plan = prepared.plan();
                var files = new JSONArray();
                for (var item : plan.getPackages()) {
                    var file = new JSONObject();
                    file.put("path", item.path());
                    file.put("bytes", plan.getSize(item));
                    file.put("replaces", item.origin().equals("replaced") ? "the client's file" : "nothing, it is new");
                    files.add(file);
                }
                var data = new JSONObject();
                data.put("folder", folder.toString());
                data.put("files", files);
                if (!plan.getNotes().isEmpty()) {
                    data.put(
                            "note",
                            plan.getNotes().size()
                                    + " imports were already broken in the client's own packages. The export leaves"
                                    + " them as they were.");
                }
                var images = controller.getImageProblems();
                if (!images.isEmpty()) {
                    data.put("images", images);
                }
                data.put(
                        "next",
                        "install puts it into the client, which keeps the files it replaces for restore. Players need"
                                + " every file of the folder.");
                return ToolResult.data(data).withFolder(folder).saying("Exported the effect edits to " + folder);
            }

            @Override
            public void cancel() {
                written.cancel(false);
                preparing.cancel(false);
            }
        };
    }

    private Step install(EffectsPanel panel, EffectEditController controller, boolean restore) throws ToolProblem {
        var steps = viewer.agent().getEffectSteps();
        var export = args.has("folder") ? folder(args.getRequired("folder")) : steps.getLastExport();
        if (export == null) {
            throw new ToolProblem(
                    restore
                            ? "Name the exported folder that was installed, with folder."
                            : "Export first, or name an exported folder with folder.");
        }
        if (controller.hasEdits() && controller.isUnexported()) {
            throw new ToolProblem("There are edits that no export holds, and " + (restore ? "a restore" : "an install")
                    + " ends the edits. Export them first, or take them back.");
        }
        var client = controller.getClientRoot();
        var working = Step.onWorker(() -> {
            try {
                var count = restore ? EditExport.restore(export, client) : EditExport.install(export, client);
                var data = new JSONObject();
                data.put(restore ? "restored" : "installed", count);
                data.put("client", client.toString());
                data.put("export", export.toString());
                return ToolResult.data(data)
                        .withFolder(export)
                        .saying((restore ? "Restored the client from " : "Installed effect edits into the client from ")
                                + export);
            } catch (IOException failed) {
                throw new ToolProblem(failed.getMessage());
            }
        });
        return new Step() {
            @Override
            public @Nullable ToolResult step() throws ToolProblem {
                var result = working.step();
                if (result != null) {
                    panel.installed(restore);
                    steps.cleared();
                }
                return result;
            }

            @Override
            public void cancel() {
                working.cancel();
            }
        };
    }

    private Effect effect(EffectsPanel panel) throws ToolProblem {
        return effect(
                panel,
                required(
                        "effect",
                        "Name the effect with effect, as LineageEffect.at_shock_blast_ta or a copy, or the skill with"
                                + " skill."));
    }

    private Skill skill(EffectsPanel panel) throws ToolProblem {
        if (panel.getSkillList().isEmpty()) {
            throw new ToolProblem("This client has no skill package, so it has no skills to edit. Skills need"
                    + " System/LineageSkillEffect.u, or Animations/skill.usk from Chronicle 3 to Interlude. C1"
                    + " and C2 have neither: they keep the effects of their skills in the code of Engine.dll.");
        }
        return EffectLookup.skill(panel.getSkillList(), panel.getCasting()::getSkillName, args.getRequired("skill"))
                .skill();
    }

    private String required(String name, String why) throws ToolProblem {
        var value = args.getText(name);
        if (value == null) {
            throw new ToolProblem(why);
        }
        return value;
    }

    private static Effect effect(EffectsPanel panel, String words) throws ToolProblem {
        var listed = new ArrayList<>(panel.copyList());
        listed.addAll(panel.getEffectList());
        return EffectLookup.effect(listed, words);
    }

    private static List<String> users(EffectsPanel panel, String path) {
        var key = SkillEffects.key(path);
        var names = new ArrayList<String>();
        for (var skill : panel.getSkillList()) {
            var plays = false;
            for (var phase : Phase.values()) {
                for (var action : skill.actions(phase)) {
                    plays |= SkillEffects.key(action.effect()).equals(key);
                }
            }
            if (plays) {
                names.add(skill.skillId() + " " + panel.getCasting().getSkillName(skill));
            }
        }
        return names;
    }

    private static ActionFields withEffect(ActionFields fields, String effect) {
        return new ActionFields(
                effect,
                fields.onTarget(),
                fields.multi(),
                fields.place(),
                fields.bone(),
                fields.offset(),
                fields.relative(),
                fields.scale(),
                fields.delay(),
                fields.phase(),
                fields.flight());
    }

    private static String packageName(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static Source saved(Source source, @Nullable Path folder) throws IOException {
        if (folder != null) {
            var file = folder.resolve(source.getRelativePath());
            Files.createDirectories(file.getParent());
            Files.write(file, source.toFileBytes());
        }
        return source;
    }

    private static ToolResult scriptsAnswer(List<ClassSource.Written> written, Path folder) {
        var data = new JSONObject();
        data.put("folder", folder.toString());
        var packages = new JSONArray();
        var files = 0;
        var leftOut = new ArrayList<String>();
        for (var item : written) {
            var row = new JSONObject();
            row.put("package", item.packageName());
            row.put("files", item.files());
            row.put("left_out", item.unreadFiles().size());
            packages.add(row);
            files += item.files();
            leftOut.addAll(item.unreadFiles());
        }
        data.put("packages", packages);
        data.put("files", files);
        if (!leftOut.isEmpty()) {
            data.put("left_out", leftOut.size() > LEFT_OUT_SHOWN ? leftOut.subList(0, LEFT_OUT_SHOWN) : leftOut);
            data.put(
                    "left_out_note",
                    leftOut.size()
                            + " files left out parts, such as a value in a layout that Cruma does not read. A comment"
                            + " in each names them.");
        }
        data.put(
                "note",
                "The scripts as the client has them. script with save writes one class or skill with its edits.");
        return ToolResult.data(data).saying("Wrote " + files + " scripts into " + folder);
    }

    private static Path folder(String text) throws ToolProblem {
        try {
            var path = Path.of(text);
            if (!path.isAbsolute()) {
                throw new ToolProblem("folder must be a full path.");
            }
            return path.normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("folder is not a path: " + wrong.getMessage());
        }
    }

    private static ToolResult script(Source source, boolean edited, @Nullable Path folder) {
        var data = new JSONObject();
        data.put("file", source.getRelativePath().toString().replace('\\', '/'));
        if (folder != null) {
            data.put("saved", folder.resolve(source.getRelativePath()).toString());
        }
        data.put("edited", edited);
        data.put("unread", source.unread());
        var text = source.text();
        if (text.length() > SCRIPT_CHARS) {
            data.put("text", text.substring(0, SCRIPT_CHARS));
            data.put("cut", "The text has " + text.length() + " characters. The first " + SCRIPT_CHARS + " are here.");
        } else {
            data.put("text", text);
        }
        return ToolResult.data(data);
    }

    private static <T> Step after(CompletableFuture<T> future, Then<T> then) {
        return () -> future.isDone() ? then.with(taken(future)) : null;
    }

    private static <T> T taken(CompletableFuture<T> future) throws ToolProblem {
        try {
            return future.join();
        } catch (CompletionException | CancellationException failed) {
            var cause = failed.getCause() != null ? failed.getCause() : failed;
            throw new ToolProblem(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }
    }
}
