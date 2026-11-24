package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.TexturePackageWriter;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import com.rost.cruma.viewer.agent.edit.EditSteps.Picked;
import com.rost.cruma.viewer.editing.EditingSession;
import com.rost.cruma.viewer.editing.EditingSession.MaterialChoice;
import com.rost.cruma.viewer.editing.MaterialSlots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class MaterialEdit {
    private static final List<String> ACTIONS = List.of("read", "set", "import", "dependency");
    private static final String PACKAGE = "CrumaTextures";
    private static final int MOST_MAPS = 25;

    private MaterialEdit() {}

    static void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "materials_edit",
                                "Edit materials",
                                "Reads the material slots of actors, or gives a slot a material, from the eyedropper"
                                        + " or an imported image, as one Undo step. dependency adds a package.")
                        .choice("action", "read when left out.", ACTIONS.toArray(String[]::new))
                        .list("actors", "Actor paths, or selection.")
                        .flag("same_mesh", "Also every actor of the loaded maps with the mesh of these actors.")
                        .integer("slot", "The material slot. 0 when left out.")
                        .text("material", "For set, a material path, or default for the mesh's own.")
                        .text("from", "For set, the eyedropper: the actor whose same slot gives the material.")
                        .text(
                                "file",
                                "A .utx that holds material, or for read lists its materials. For import an image, for"
                                        + " dependency a package.")
                        .text("name", "For import, Package.Texture. CrumaTextures and the file name when left out.")
                        .access(Access.EDIT),
                MaterialEdit::start);
    }

    static Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        var session = EditSteps.session(viewer);
        var action = args.getText("action", "read").toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new ToolProblem("action must be read, set, import or dependency.");
        }
        var file = file(args.getText("file"));
        if (action.equals("dependency")) {
            var packageFile = required(file, "file is missing: name the package to add.");
            return EditSteps.editing(viewer, session, () -> Step.done(dependency(session, packageFile)));
        }
        if (action.equals("read") && file != null && !args.has("actors")) {
            var most = EditSteps.limit(args);
            return Step.onWorker(() -> materialsOf(file, most));
        }
        if (args.list("actors").isEmpty()) {
            throw new ToolProblem("Name the actors: give actors, paths such as 22_22.StaticMeshActor12, or selection.");
        }
        var slot = args.getInteger("slot", 0);
        if (slot < 0 || slot > 63) {
            throw new ToolProblem("slot must be between 0 and 63.");
        }
        var limit = EditSteps.limit(args);
        var sameMesh = args.readFlag("same_mesh", false);
        var picked = new Picked();
        return EditSteps.editing(
                viewer,
                session,
                () -> EditSteps.pick(args, viewer, session, null, picked, () -> {
                    if (!sameMesh) {
                        return Step.done(act(
                                action,
                                args,
                                viewer,
                                session,
                                picked.actors.keySet(),
                                picked.refused,
                                slot,
                                file,
                                limit));
                    }
                    var maps = EditSteps.loadedMaps(viewer, session);
                    if (maps.size() > MOST_MAPS) {
                        throw new ToolProblem(
                                "same_mesh would read " + maps.size() + " maps. Name the actors instead.");
                    }
                    return EditSteps.opening(session, maps, () -> {
                        var actors = new LinkedHashSet<>(picked.actors.keySet());
                        actors.addAll(sameMesh(session, maps, picked.actors.keySet()));
                        return Step.done(act(action, args, viewer, session, actors, picked.refused, slot, file, limit));
                    });
                }));
    }

    private static ToolResult act(
            String action,
            ToolArgs args,
            ViewerContext viewer,
            EditingSession session,
            Collection<String> actors,
            Refusals refused,
            int slot,
            @Nullable Path file,
            int limit)
            throws ToolProblem {
        return switch (action) {
            case "read" -> read(viewer, session, actors, refused, limit);
            case "set" -> set(session, actors, refused, slot, choice(args, viewer, session, slot, file), limit);
            default -> {
                var image = required(file, "file is missing: name the image to import.");
                var name = args.getText("name");
                var packageName = PACKAGE;
                var texture = TexturePackageWriter.textureName(image);
                if (name != null) {
                    var dot = name.indexOf('.');
                    packageName = dot > 0 ? name.substring(0, dot) : PACKAGE;
                    texture = dot > 0 ? name.substring(dot + 1) : name;
                }
                if (actors.isEmpty()) {
                    throw new ToolProblem("Name the actors whose slot takes the image.");
                }
                var choice = session.importImage(image, packageName, texture);
                yield set(session, actors, refused, slot, choice, limit)
                        .withText("Imported " + choice.path() + " from " + image.getFileName() + ".");
            }
        };
    }

    private static Set<String> sameMesh(EditingSession session, Collection<String> maps, Collection<String> actors) {
        var meshes = new LinkedHashSet<String>();
        for (var actor : actors) {
            var mesh = session.getActor(actor).meshSourcePath();
            if (!mesh.isBlank()) {
                meshes.add(mesh.toLowerCase(Locale.ROOT));
            }
        }
        var result = new LinkedHashSet<String>();
        var hidden = session.getHiddenActors();
        for (var map : maps) {
            for (var actor : session.getActorsOf(map)) {
                if (meshes.contains(actor.meshSourcePath().toLowerCase(Locale.ROOT))
                        && !hidden.contains(actor.sourcePath())) {
                    result.add(actor.sourcePath());
                }
            }
        }
        return result;
    }

    private static @Nullable MaterialChoice choice(
            ToolArgs args, ViewerContext viewer, EditingSession session, int slot, @Nullable Path file)
            throws ToolProblem {
        var from = args.getText("from");
        var material = args.getText("material");
        if (from != null && material != null) {
            throw new ToolProblem("Give material or from, not both.");
        }
        if (from != null) {
            String taken;
            try {
                taken = MaterialSlots.taken(
                        session,
                        from,
                        slot,
                        List.of(),
                        each -> scene(viewer, from).get(each));
            } catch (IllegalArgumentException unknown) {
                throw new ToolProblem(from + ": " + EditSteps.notEditable(from));
            }
            if (taken == null) {
                throw new ToolProblem(EditSteps.subject(List.of(from)) + " has no material in slot " + slot
                        + " that Cruma can take.");
            }
            return new MaterialChoice(taken, session.getPackageFile(taken));
        }
        if (material == null) {
            throw new ToolProblem(
                    "Say which material: material, a path such as Package.Texture or default, or from, an actor.");
        }
        if (material.equalsIgnoreCase("default")) {
            return null;
        }
        if (material.indexOf('.') <= 0) {
            throw new ToolProblem(
                    "material must be a path such as Package.Texture. assets_find lists the materials of the client.");
        }
        return new MaterialChoice(material, file);
    }

    private static ToolResult set(
            EditingSession session,
            Collection<String> actors,
            Refusals refused,
            int slot,
            @Nullable MaterialChoice choice,
            int limit) {
        var chosen = new ArrayList<String>();
        var locked = session.getLockedActors();
        for (var actor : actors) {
            if (locked.contains(actor)) {
                refused.add("It is locked. Unlock it first.", actor);
            } else {
                chosen.add(actor);
            }
        }
        var data = new JSONObject();
        var label = EditSteps.AI + "Material of " + EditSteps.subject(chosen);
        var before = new LinkedHashMap<String, List<Integer>>();
        chosen.forEach(actor -> before.put(actor, session.getActor(actor).skinReferences()));
        if (!chosen.isEmpty()) {
            ActorActions.inOneStep(session, label, () -> session.setMaterials(chosen, slot, choice, label));
        }
        var changed = 0;
        for (var entry : before.entrySet()) {
            if (!session.getActor(entry.getKey()).skinReferences().equals(entry.getValue())) {
                changed++;
            }
        }
        if (changed > 0) {
            data.put("step", label);
        }
        data.put("slot", slot);
        data.put("material", choice == null ? "Mesh default" : choice.path());
        data.put("changed", changed);
        data.put("had_it_already", chosen.size() - changed);
        data.put("could_not", refused.getCount());
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        data.put("actors", chosen.subList(0, Math.min(limit, chosen.size())));
        return ToolResult.data(data);
    }

    private static List<String> scene(ViewerContext viewer, String actor) {
        var result = new ArrayList<String>();
        for (var object : viewer.backend().getEditorActorObjects(Set.of(actor))) {
            var slot = object.surfaceOrdinal();
            while (result.size() <= slot) {
                result.add("");
            }
            result.set(slot, object.materialName());
        }
        while (result.size() < 64) {
            result.add("");
        }
        return result;
    }

    private static ToolResult read(
            ViewerContext viewer, EditingSession session, Collection<String> actors, Refusals refused, int limit) {
        var rows = new JSONArray();
        for (var actor : actors) {
            if (rows.size() == limit) {
                break;
            }
            var record = session.getActor(actor);
            var overrides = new ArrayList<@Nullable String>();
            for (var reference : record.skinReferences()) {
                overrides.add(reference == 0 ? null : session.getMaterialName(actor, reference));
            }
            var drawn = scene(viewer, actor);
            var meshMaterials = new ArrayList<String>();
            for (var i = 0; i < drawn.size(); i++) {
                meshMaterials.add(i < overrides.size() && overrides.get(i) != null ? "" : drawn.get(i));
            }
            while (!meshMaterials.isEmpty()
                    && meshMaterials.getLast().isEmpty()
                    && meshMaterials.size() > overrides.size()) {
                meshMaterials.removeLast();
            }
            var slots = new JSONArray();
            for (var slot : MaterialSlots.of(meshMaterials, overrides)) {
                var row = new JSONObject();
                row.put("slot", slot.index());
                row.put("name", slot.name());
                row.put("draws", slot.override() != null ? slot.override() : drawn.get(slot.index()));
                row.put("own", slot.override() != null);
                slots.add(row);
            }
            var row = new JSONObject();
            row.put("actor", actor);
            row.put("mesh", record.meshSourcePath());
            row.put("slots", slots);
            rows.add(row);
        }
        var data = new JSONObject();
        data.put("actors", rows);
        if (actors.size() > rows.size()) {
            data.put("more", actors.size() - rows.size());
        }
        if (refused.getCount() > 0) {
            data.put("reasons", refused.write());
        }
        return ToolResult.data(data);
    }

    private static ToolResult materialsOf(Path file, int limit) throws ToolProblem {
        if (!Files.isRegularFile(file)) {
            throw new ToolProblem("There is no file " + file + ".");
        }
        try {
            var names = new JSONArray();
            var materials = PackageEditDocument.inspectMaterials(file);
            for (var material : materials.subList(0, Math.min(limit, materials.size()))) {
                names.add(material.sourcePath());
            }
            var data = new JSONObject();
            data.put("file", file.toString());
            data.put("count", materials.size());
            data.put("materials", names);
            data.put("use", "materials_edit set with material and this file gives one to a slot.");
            return ToolResult.data(data);
        } catch (IOException | RuntimeException unreadable) {
            throw new ToolProblem(file.getFileName() + " cannot be read as a package: " + unreadable.getMessage());
        }
    }

    private static ToolResult dependency(EditingSession session, Path file) throws ToolProblem {
        if (!Files.isRegularFile(file)) {
            throw new ToolProblem("There is no file " + file + ".");
        }
        var label = EditSteps.AI + "Add package " + file.getFileName();
        ActorActions.inOneStep(session, label, () -> session.addDependency(file));
        var data = new JSONObject();
        data.put("added", file.toString());
        data.put("step", label);
        return ToolResult.data(data);
    }

    private static @Nullable Path file(@Nullable String text) throws ToolProblem {
        if (text == null) {
            return null;
        }
        try {
            var path = Path.of(text);
            if (!path.isAbsolute()) {
                throw new ToolProblem("file must be a full path.");
            }
            return path.normalize();
        } catch (InvalidPathException wrong) {
            throw new ToolProblem("file is not a path: " + wrong.getMessage());
        }
    }

    private static Path required(@Nullable Path file, String problem) throws ToolProblem {
        if (file == null) {
            throw new ToolProblem(problem);
        }
        return file;
    }
}
