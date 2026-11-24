package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;

import com.rost.cruma.core.editing.ClassSource.Source;
import com.rost.cruma.core.editing.EffectEditApplier.Context;
import com.rost.cruma.core.editing.EffectEditApplier.EmitterOrigin;
import com.rost.cruma.core.editing.EffectEditApplier.ObjectTarget;
import com.rost.cruma.core.editing.EffectEdits.AddedEmitter;
import com.rost.cruma.core.editing.EffectEdits.ClassEdit;
import com.rost.cruma.core.editing.EffectEdits.Copy;
import com.rost.cruma.core.editing.EffectEdits.EmitterRef;
import com.rost.cruma.core.editing.EffectEdits.Kind;
import com.rost.cruma.core.editing.EffectEdits.PropertyEdit;
import com.rost.cruma.core.editing.EffectTextureHue.Plan;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TaggedProperties.Property;
import com.rost.cruma.core.editing.TexturePackageWriter.PackagedTexture;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.unreal.EffectClasses;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.EffectPackages;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Layout;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.unreal.UArchive;
import com.rost.cruma.core.unreal.UArchiveLoader;
import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class EffectEditSession implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(EffectEditSession.class);
    private static final String SKILL_PACKAGE = "LineageSkillEffect";
    private static final int TAGGED_STRUCTS_FROM = 118;
    private static final String VISUAL_FILE = SkillEffects.VISUAL_PACKAGE + ".usk";
    private static final int TEXTURE_FILE_VERSION = 123;
    private static final int TEXTURE_LICENSE_VERSION = 0;

    private final Path clientRoot;
    private final Path systemDirectory;
    private final Map<String, Path> packageFiles = new HashMap<>();
    private final Map<String, ClassPackageWriter> sources = new HashMap<>();
    private final Map<String, Map<String, Integer>> classIndex = new HashMap<>();
    private final @Nullable String skillPackage;
    private @Nullable Layout skillLayout;
    private @Nullable ClassSchema schema;
    private final EffectTextureHue hue;
    private final EffectImages images = new EffectImages();
    private EffectEdits edits = EffectEdits.NONE;
    private @Nullable PackageBytes customTextures;
    private boolean customTexturesRead;
    private @Nullable List<PackagedTexture> builtTextures;
    private byte @Nullable [] builtTexturePackage;

    private EffectEditSession(Path clientRoot, Path systemDirectory, Map<String, Path> packageFiles) {
        this.clientRoot = clientRoot;
        this.systemDirectory = systemDirectory;
        this.packageFiles.putAll(packageFiles);
        skillPackage = packageFiles.containsKey(key(SKILL_PACKAGE))
                ? SKILL_PACKAGE
                : packageFiles.containsKey(key(SkillEffects.VISUAL_PACKAGE)) ? SkillEffects.VISUAL_PACKAGE : null;
        hue = new EffectTextureHue(clientRoot);
    }

    public enum Placement {
        CUSTOM_PACKAGE,
        SOURCE_PACKAGE
    }

    public record Output(
            String name, byte[] bytes, @Nullable Path source, Path target) {}

    public record EmitterInfo(String key, String kind, String label, boolean disabled) {}

    public record PropertyInfo(String path, Kind kind, String value) {}

    public record Preview(Effect effect, byte[] effectPackage, byte @Nullable [] texturePackage) {}

    public record ActionInfo(String key, Phase phase, Action action) {}

    public record EditProblem(String path, boolean skill, String text) {}

    public static EffectEditSession open(Path clientRoot) throws IOException {
        var system = child(clientRoot, "System");
        if (system == null) {
            throw new IOException("The client has no System folder: " + clientRoot);
        }
        var files = new HashMap<String, Path>();
        try (var list = Files.list(system)) {
            for (var file : list.toList()) {
                var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (name.endsWith(".u")) {
                    files.put(name.substring(0, name.length() - 2), file);
                }
            }
        }
        var animations = child(clientRoot, "Animations");
        var visuals = animations == null ? null : child(animations, VISUAL_FILE);
        if (visuals != null && !files.containsKey(key(SKILL_PACKAGE))) {
            files.put(SkillEffects.VISUAL_PACKAGE, visuals);
        }
        return new EffectEditSession(clientRoot, system, files);
    }

    public static @Nullable Path findSkillPackageFile(Path clientRoot) throws IOException {
        var system = child(clientRoot, "System");
        var classes = system == null ? null : child(system, SKILL_PACKAGE + ".u");
        if (classes != null) {
            return classes;
        }
        var animations = child(clientRoot, "Animations");
        return animations == null ? null : child(animations, VISUAL_FILE);
    }

    public static List<Path> findScriptPackageFiles(Path clientRoot) throws IOException {
        var result = new ArrayList<Path>(EffectPackages.find(clientRoot));
        var skills = findSkillPackageFile(clientRoot);
        if (skills != null) {
            result.add(skills);
        }
        return result;
    }

    public EffectEdits getEdits() {
        return edits;
    }

    public void setEdits(EffectEdits value) {
        edits = value;
    }

    public boolean hasSkillPackage() {
        return skillPackage != null;
    }

    public @Nullable Path getSkillPackageFile() {
        return skillPackage == null ? null : packageFiles.get(key(skillPackage));
    }

    public @Nullable Layout getSkillLayout() {
        if (skillPackage == null) {
            return null;
        }
        if (skillLayout == null) {
            skillLayout = isVisual() ? visualLayout() : Layout.CLASSES;
        }
        return skillLayout;
    }

    public void imageFolder(@Nullable Path folder) {
        images.setFolder(folder);
    }

    public String importImage(Path image, String classPath, String emitterKey) throws IOException {
        var file = images.store(image);
        var name = freeTextureName(image);
        var path = EffectEdits.texturePath(name);
        var edit = edits.classEdit(classPath);
        var emitter = edit.emitter(emitterKey).withProperty(PropertyEdit.object("Texture", path));
        edits = edits.withTexture(name, file).withClass(classPath, edit.withEmitter(emitterKey, emitter));
        return path;
    }

    public List<String> getImageProblems() {
        var result = new ArrayList<String>();
        for (var entry : edits.usedTextures().entrySet()) {
            var problem = images.getProblem(entry.getKey(), entry.getValue());
            if (problem != null) {
                result.add(problem);
            }
        }
        return result;
    }

    public List<EditProblem> getEditProblems() {
        var problems = new ArrayList<EditProblem>();
        build(Placement.CUSTOM_PACKAGE, problems);
        return problems;
    }

    public void objectEdit(String classPath, String emitterKey, String propertyPath, String value) throws IOException {
        if (!value.isEmpty() && !value.equalsIgnoreCase("None")) {
            context(plan()).getObject(value, propertyPath);
        }
        var edit = edits.classEdit(classPath);
        var emitter = edit.emitter(emitterKey).withProperty(PropertyEdit.object(propertyPath, value));
        edits = edits.withClass(classPath, edit.withEmitter(emitterKey, emitter));
    }

    public @Nullable Icon getPicture(String file) {
        return images.getPicture(file);
    }

    public @Nullable String copyRefusal(String classPath) throws IOException {
        var source = resolve(classPath);
        return EffectClassCopier.refusal(source.owner, source.export);
    }

    public String suggestCopyName(String classPath) throws IOException {
        var longest = 63 - "_copy_b".length();
        var whole = className(classPath);
        var base = whole.length() > longest ? whole.substring(0, longest) : whole;
        var name = base + "_copy";
        for (var suffix = 'b'; nameProblem(name, classPath) != null && suffix <= 'z'; suffix++) {
            name = base + "_copy_" + suffix;
        }
        return name;
    }

    public String copy(String classPath, String newName) throws IOException {
        var source = resolve(classPath);
        var reason = EffectClassCopier.refusal(source.owner, source.export);
        if (reason != null) {
            throw new IOException(reason);
        }
        var problem = nameProblem(newName, classPath);
        if (problem != null) {
            throw new IOException(problem);
        }
        var copy = new Copy(newName, source.path);
        edits = edits.withCopy(copy).withClass(copy.path(), edits.classEdit(classPath));
        return copy.path();
    }

    public String renameCopy(String copyPath, String newName) throws IOException {
        var copy = edits.copies().get(copyPath);
        if (copy == null) {
            throw new IOException(copyPath + " is not a copy that can be renamed");
        }
        if (copy.name().equals(newName)) {
            return copyPath;
        }
        var problem = nameProblem(newName, copyPath, copy);
        if (problem != null) {
            throw new IOException(problem);
        }
        edits = edits.withCopyRenamed(copyPath, newName);
        return new Copy(newName, copy.source()).path();
    }

    public void deleteCopy(String copyPath) {
        edits = edits.withoutCopy(copyPath);
    }

    public boolean hasClass(String classPath) {
        try {
            resolve(classPath);
            return true;
        } catch (IOException gone) {
            return false;
        }
    }

    public List<EmitterInfo> getEmitters(String classPath) throws IOException {
        var source = resolve(classPath);
        var edit = edits.classEdit(classPath);
        var result = new ArrayList<EmitterInfo>();
        for (var entry : EffectEditApplier.keys(source.owner, source.export).entrySet()) {
            if (edit.removed().stream().noneMatch(entry.getKey()::equalsIgnoreCase)) {
                result.add(info(entry.getKey(), source.owner, entry.getValue(), edit));
            }
        }
        for (var added : edit.added()) {
            try {
                var origin = origin(added.fromClass(), added.fromEmitter());
                result.add(info(added.name(), origin.owner(), origin.export(), edit));
            } catch (IOException gone) {
                log.debug("Added emitter {} of {} has no source", added.name(), classPath, gone);
            }
        }
        return result;
    }

    public String duplicateEmitter(String classPath, String emitterKey) throws IOException {
        var name = addEmitter(classPath, classPath, emitterKey);
        var edit = edits.classEdit(classPath);
        edits = edits.withClass(classPath, edit.withEmitter(name, edit.emitter(emitterKey)));
        return name;
    }

    public String addEmitter(String classPath, String fromClass, String emitterKey) throws IOException {
        var origin = origin(fromClass, emitterKey);
        var kind = origin.owner().getClassName(origin.export());
        var taken = new HashSet<String>();
        for (var emitter : getEmitters(classPath)) {
            taken.add(emitter.key().toLowerCase(Locale.ROOT));
        }
        for (var added : edits.classEdit(classPath).added()) {
            taken.add(added.name().toLowerCase(Locale.ROOT));
        }
        var source = resolve(classPath);
        for (var child : source.owner.getChildren(source.export)) {
            taken.add(source.owner.exportName(child).toLowerCase(Locale.ROOT));
        }
        var number = 0;
        while (taken.contains((kind + number).toLowerCase(Locale.ROOT))) {
            number++;
        }
        var name = kind + number;
        edits = edits.withClass(
                classPath, edits.classEdit(classPath).withAdded(new AddedEmitter(name, fromClass, emitterKey)));
        return name;
    }

    public List<PropertyInfo> getProperties(String classPath, String emitterKey) throws IOException {
        var edit = edits.classEdit(classPath);
        var origin = origin(classPath, emitterKey);
        var writer = ClassPackageWriter.open(origin.owner().getOriginal());
        var payload = writer.getPayload(origin.export());
        var tree = TaggedProperties.read(new Cursor(payload, 0, payload.length), writer);
        var context = context(plan());
        for (var property : edit.emitter(emitterKey).properties()) {
            try {
                EmitterProperties.apply(tree, property, writer, (path, name) -> {
                    if (path.isEmpty() || path.equalsIgnoreCase("None")) {
                        return 0;
                    }
                    var target = context.getObject(path, name);
                    return writer.importPath(target.path(), target.classPackage(), target.className());
                });
            } catch (IOException | RuntimeException broken) {
                log.debug("Raw edit {} of {} cannot be written", property.path(), classPath, broken);
            }
        }
        var result = new ArrayList<PropertyInfo>();
        flatten(tree, "", writer, result);
        return result;
    }

    public Preview preview(String classPath) throws IOException {
        var plan = plan();
        var built = buildPreview(classPath, plan);
        var packageName = built.target().getPackageName();
        var name = built.name();
        var body = built.target().getBody();
        Effect effect = null;
        for (var candidate : EffectClasses.list(archive(packageName, body))) {
            effect = candidate;
        }
        if (effect == null) {
            effect = new Effect(packageName, name, "Emitter", 0, 0, 0, 0, false);
        }
        return new Preview(effect, body, plan.isEmpty() ? null : previewTextures(plan));
    }

    public Source getSource(String classPath) throws IOException {
        var source = resolve(classPath);
        if (!edits.copies().containsKey(classPath) && edits.classEdit(classPath).isEmpty()) {
            return ClassSource.ofClass(source.owner, source.export, getSchema().with(source.owner));
        }
        var built = buildPreview(classPath, plan());
        var target = built.target();
        return ClassSource.ofClass(
                target,
                target.findClassExport(built.name()),
                getSchema().with(target),
                new ClassSource.Compiled(
                        source.owner, source.export, getSchema().with(source.owner)),
                edits.copies().containsKey(classPath)
                        ? target.getPackageName()
                        : ClassSource.findPackageName(source.owner));
    }

    public Source getSkillSource(String className) throws IOException {
        var edited = !edits.skillEdit(className).isEmpty();
        if (isVisual()) {
            var writer = edited ? editedSkill(className).writer() : source(skillPackage());
            return ClassSource.ofObject(
                    writer, visualExport(writer, className), getSchema().with(writer));
        }
        var owner = source(SKILL_PACKAGE);
        var writer = edited ? editedSkill(className).writer() : owner;
        var export = edited ? writer.findClassExport(className) : classExport(writer, className);
        return ClassSource.ofClass(writer, export, getSchema().with(writer), null, ClassSource.findPackageName(owner));
    }

    public Skill previewSkill(String className) throws IOException {
        return editedSkill(className).skill();
    }

    public List<ActionInfo> getActions(String className) throws IOException {
        var edited = editedSkill(className);
        var result = new ArrayList<ActionInfo>();
        if (isVisual()) {
            var skill = visualExport(edited.writer(), className);
            for (var phase : Phase.values()) {
                var actions = edited.skill().actions(phase);
                var keys = SkillVisualEditApplier.playedKeys(edited.writer(), skill, phase);
                for (var at = 0; at < Math.min(actions.size(), keys.size()); at++) {
                    result.add(new ActionInfo(keys.get(at), phase, actions.get(at)));
                }
            }
            return result;
        }
        var classExport = edited.writer().findClassExport(className);
        var data = ClassExportData.read(edited.writer().getPayload(classExport), className, edited.writer());
        for (var phase : Phase.values()) {
            var property = data.defaults.find(phase.getProperty(), edited.writer());
            if (property == null
                    || property.getArray() == null
                    || property.getArray().getObjects() == null) {
                continue;
            }
            var actions = edited.skill().actions(phase);
            var next = 0;
            for (var slot : property.getArray().getObjects()) {
                if (slot > 0 && isNamed(edited.writer(), slot) && next < actions.size()) {
                    result.add(new ActionInfo(
                            edited.keys().getOrDefault(slot, edited.writer().exportName(slot)),
                            phase,
                            actions.get(next++)));
                }
            }
        }
        return result;
    }

    public List<Output> export(Placement placement) throws IOException {
        var problems = new ArrayList<EditProblem>();
        var built = build(placement, problems);
        if (!problems.isEmpty()) {
            var texts = new ArrayList<String>();
            for (var problem : problems) {
                texts.add(problem.text());
            }
            throw new IOException(
                    String.join(" ", texts) + " Drop what cannot be used in the panel to export the rest.");
        }
        var outputs = new ArrayList<Output>();
        for (var writer : built.writers().values()) {
            if (writer.isChanged()) {
                addChanged(outputs, output(writer));
            }
        }
        if (!built.plan().isEmpty()) {
            addChanged(outputs, textureOutput(built.plan()));
        }
        return outputs;
    }

    @Override
    public void close() {
        hue.close();
        images.close();
        sources.clear();
        customTextures = null;
        customTexturesRead = false;
        builtTextures = null;
        builtTexturePackage = null;
    }

    private record Built(Map<String, ClassPackageWriter> writers, Plan plan) {}

    @FunctionalInterface
    private interface Step {
        void run() throws IOException;
    }

    private Built build(Placement placement, List<EditProblem> problems) {
        var writers = new LinkedHashMap<String, ClassPackageWriter>();
        var plan = plan();
        for (var copy : edits.copies().values()) {
            attempt(copy.path(), false, problems, () -> {
                var source = resolve(copy.source());
                var key = key(source.owner.getPackageName());
                var owner = writers.computeIfAbsent(key, _ -> open(source.owner));
                var customKey = key(EffectEdits.CUSTOM_PACKAGE);
                var target = placement == Placement.SOURCE_PACKAGE
                        ? owner
                        : writers.computeIfAbsent(customKey, _ -> customWriter(source.owner));
                place(owner, source.export, target, copy.name(), copy.path(), plan, false);
            });
        }
        for (var entry : edits.classes().entrySet()) {
            if (edits.copies().containsKey(entry.getKey())) {
                continue;
            }
            attempt(entry.getKey(), false, problems, () -> {
                var source = resolve(entry.getKey());
                requireTaggedStructs(source.owner);
                var writer = writers.computeIfAbsent(key(source.owner.getPackageName()), _ -> open(source.owner));
                EffectEditApplier.apply(
                        writer, source.export, entry.getKey(), entry.getValue(), context(plan), Map.of());
            });
        }
        for (var entry : edits.skills().entrySet()) {
            attempt(entry.getKey(), true, problems, () -> {
                var owner = source(skillPackage());
                var writer = writers.computeIfAbsent(key(skillPackage()), _ -> open(owner));
                if (isVisual()) {
                    SkillVisualEditApplier.apply(
                            writer,
                            visualExport(writer, entry.getKey()),
                            entry.getValue(),
                            path -> effectPath(path, placement),
                            getSkillLayout());
                    return;
                }
                SkillEditApplier.apply(
                        writer,
                        classExport(writer, entry.getKey()),
                        entry.getValue(),
                        path -> effectPath(path, placement),
                        Map.of());
            });
        }
        return new Built(writers, plan);
    }

    private EditedSkill editedSkill(String className) throws IOException {
        if (isVisual()) {
            var target = open(source(skillPackage()));
            SkillVisualEditApplier.apply(
                    target,
                    visualExport(target, className),
                    edits.skillEdit(className),
                    path -> path,
                    getSkillLayout());
            for (var skill : SkillEffects.listVisuals(archive(SkillEffects.VISUAL_PACKAGE, target.getBody()))) {
                if (skill.className().equalsIgnoreCase(className)) {
                    return new EditedSkill(target, skill, Map.of());
                }
            }
            throw malformed("Skill " + className + " has no effects to play");
        }
        var owner = source(SKILL_PACKAGE);
        var export = classExport(owner, className);
        var target = ClassPackageWriter.create(owner.getPackageName(), owner.getOriginal());
        var copier = new EffectClassCopier(owner, target);
        var copied = copier.copyForPreview(export, className, Set.of());
        var keys = new HashMap<Integer, String>();
        for (var entry : SkillEditApplier.keys(owner, export).entrySet()) {
            var moved = copier.getMoved(entry.getValue());
            if (moved != 0) {
                keys.put(moved, entry.getKey());
            }
        }
        SkillEditApplier.apply(target, copied, edits.skillEdit(className), path -> path, keys);
        var skills = SkillEffects.list(archive(owner.getPackageName(), target.getBody()));
        if (skills.isEmpty()) {
            throw malformed("Skill class " + className + " has no skill");
        }
        return new EditedSkill(target, skills.getFirst(), keys);
    }

    private boolean isVisual() {
        return SkillEffects.VISUAL_PACKAGE.equals(skillPackage);
    }

    private String skillPackage() throws IOException {
        if (skillPackage == null) {
            throw new IOException("The client has no skill package");
        }
        return skillPackage;
    }

    private int visualExport(ClassPackageWriter writer, String path) throws IOException {
        var export = SkillVisualEditApplier.find(writer, path);
        if (export == 0) {
            throw new IOException("The client has no skill " + path);
        }
        return export;
    }

    private Layout visualLayout() {
        var loader = new UArchiveLoader(clientRoot, List.of(new USearchConfig("System", "u")));
        try {
            return Layout.visual(loader.loadArchiveIfPresent("Engine"));
        } catch (RuntimeException failure) {
            log.warn("The Engine.u of {} could not be read for its skills", clientRoot, failure);
            return Layout.visual(null);
        } finally {
            loader.removeArchive("Engine");
        }
    }

    private ClassRef resolve(String classPath) throws IOException {
        var copy = edits.copies().get(classPath);
        var path = copy == null ? classPath : copy.source();
        var dot = path.indexOf('.');
        if (dot <= 0) {
            throw new IOException("A class path needs a package: " + path);
        }
        var owner = source(path.substring(0, dot));
        var name = path.substring(dot + 1);
        return new ClassRef(owner, classExport(owner, name), name, path);
    }

    private ClassPackageWriter source(String packageName) throws IOException {
        var key = key(packageName);
        var writer = sources.get(key);
        if (writer == null) {
            var file = packageFiles.get(key);
            if (file == null) {
                throw new IOException("The client has no package " + packageName);
            }
            writer = ClassPackageWriter.open(new PackageBytes(file, Files.readAllBytes(file)));
            sources.put(key, writer);
        }
        return writer;
    }

    private int classExport(ClassPackageWriter owner, String name) throws IOException {
        var export = classes(owner).get(key(name));
        if (export == null) {
            throw new IOException("Package " + owner.getPackageName() + " has no class " + name);
        }
        return export;
    }

    private boolean hasClass(ClassPackageWriter owner, String name) throws IOException {
        return classes(owner).containsKey(key(name));
    }

    private Map<String, Integer> classes(ClassPackageWriter owner) throws IOException {
        var classes = classIndex.get(key(owner.getPackageName()));
        if (classes == null) {
            classes = new HashMap<>();
            for (var index : owner.getChildren(0)) {
                if (owner.export(index).classIndex() == 0) {
                    classes.put(key(owner.exportName(index)), index);
                }
            }
            classIndex.put(key(owner.getPackageName()), classes);
        }
        return classes;
    }

    private String className(String classPath) throws IOException {
        var copy = edits.copies().get(classPath);
        return copy != null ? copy.name() : resolve(classPath).name;
    }

    private @Nullable String nameProblem(String name, String classPath) throws IOException {
        return nameProblem(name, classPath, null);
    }

    private @Nullable String nameProblem(String name, String classPath, @Nullable Copy renamed) throws IOException {
        var problem = EffectClassCopier.nameProblem(name);
        if (problem != null) {
            return problem;
        }
        for (var copy : edits.copies().values()) {
            if (copy != renamed && copy.name().equalsIgnoreCase(name)) {
                return "There is already a copy named " + name;
            }
        }
        var owners = new ArrayList<String>(List.of(resolve(classPath).owner.getPackageName()));
        if (packageFiles.containsKey(key(EffectEdits.CUSTOM_PACKAGE))) {
            owners.add(EffectEdits.CUSTOM_PACKAGE);
        }
        for (var owner : owners) {
            if (hasClass(source(owner), name)) {
                return "Package " + owner + " already has a class " + name;
            }
        }
        return null;
    }

    private EmitterOrigin emitterOf(EmitterRef emitter, boolean sibling) throws IOException {
        var source = resolve(emitter.classPath());
        for (var entry : EffectEditApplier.keys(source.owner, source.export).entrySet()) {
            if (entry.getKey().equalsIgnoreCase(emitter.key())) {
                return new EmitterOrigin(source.owner, entry.getValue(), sibling);
            }
        }
        throw new IOException("Class " + emitter.classPath() + " has no emitter " + emitter.key());
    }

    private EmitterOrigin origin(String classPath, String emitterKey) throws IOException {
        return emitterOf(root(classPath, emitterKey), false);
    }

    private EmitterRef root(String classPath, String emitterKey) throws IOException {
        var root = edits.origin(classPath, emitterKey);
        if (root == null) {
            throw new IOException("Added emitters of " + classPath + " form a loop");
        }
        return root;
    }

    private void place(
            ClassPackageWriter owner,
            int export,
            ClassPackageWriter target,
            String name,
            String classPath,
            Plan plan,
            boolean preview)
            throws IOException {
        requireTaggedStructs(owner);
        var edit = edits.classEdit(classPath);
        var keys = EffectEditApplier.keys(owner, export);
        var omitted = new HashSet<Integer>();
        for (var key : edit.removed()) {
            var emitter = find(keys, key);
            if (emitter == null) {
                throw new IOException("Class " + owner.exportName(export) + " has no emitter " + key);
            }
            omitted.add(emitter);
        }
        var copier = new EffectClassCopier(owner, target);
        var copied = preview ? copier.copyForPreview(export, name, omitted) : copier.copyClass(export, name, omitted);
        var moved = new HashMap<Integer, String>();
        for (var entry : keys.entrySet()) {
            var emitter = copier.getMoved(entry.getValue());
            if (emitter != 0) {
                moved.put(emitter, entry.getKey());
            }
        }
        var rest = new ClassEdit(edit.knobs(), edit.emitters(), List.of(), edit.added());
        EffectEditApplier.apply(
                target,
                copied,
                classPath,
                rest,
                context(plan),
                moved,
                omitted.isEmpty() ? null : EffectClassCopier.movedSlots(owner, export, omitted));
    }

    private PreviewBuild buildPreview(String classPath, Plan plan) throws IOException {
        var source = resolve(classPath);
        var copy = edits.copies().get(classPath);
        var packageName = copy != null ? EffectEdits.CUSTOM_PACKAGE : source.owner.getPackageName();
        var name = copy != null ? copy.name() : source.name;
        var target = ClassPackageWriter.create(packageName, source.owner.getOriginal());
        place(source.owner, source.export, target, name, classPath, plan, true);
        return new PreviewBuild(target, name);
    }

    private ClassSchema getSchema() {
        if (schema == null) {
            schema = ClassSchema.read(systemDirectory);
        }
        return schema;
    }

    private Plan plan() {
        return new Plan(edits.textures().keySet());
    }

    private Context context(Plan plan) {
        return new Context() {
            @Override
            public EmitterOrigin getEmitter(String classPath, AddedEmitter added) throws IOException {
                var root = root(added.fromClass(), added.fromEmitter());
                return emitterOf(root, root.classPath().equalsIgnoreCase(resolve(classPath).path));
            }

            @Override
            public ObjectTarget getObject(String path, String property) throws IOException {
                var name = edits.textureName(path);
                if (name != null) {
                    return new ObjectTarget(EffectEdits.texturePath(name), "Engine", "Texture");
                }
                return plan.holds(path) ? new ObjectTarget(path, "Engine", "Texture") : objectTarget(path);
            }

            @Override
            public @Nullable PropertyEdit getPrepared(PropertyEdit edit, int degrees) throws IOException {
                var name = edit.kind() == Kind.OBJECT ? edits.textureName(edit.value()) : null;
                return name == null ? edit : preparedImage(edit, name, degrees, plan);
            }

            @Override
            public @Nullable String createShiftedTexture(String texturePath, int degrees) throws IOException {
                return hue.createShifted(plan, texturePath, degrees);
            }
        };
    }

    private @Nullable PropertyEdit preparedImage(PropertyEdit edit, String name, int degrees, Plan plan)
            throws IOException {
        var file = edits.textures().get(name);
        var image = images.read(file);
        if (image == null) {
            return null;
        }
        var texture = image.renamed(name, image.levels());
        var turned = degrees == 0 || !edit.path().equalsIgnoreCase("Texture")
                ? null
                : hue.createShifted(plan, texture, file, degrees, clientCustomTextures());
        if (turned != null) {
            return PropertyEdit.object(edit.path(), turned);
        }
        plan.add(texture);
        return edit;
    }

    private String freeTextureName(Path image) throws IOException {
        var taken = new HashSet<>(clientCustomTextures());
        for (var name : edits.textures().keySet()) {
            taken.add(key(name));
        }
        var base = TexturePackageWriter.textureName(image);
        var name = base;
        for (var number = 2; taken.contains(key(name)); number++) {
            name = base + "_" + number;
        }
        return name;
    }

    private Set<String> clientCustomTextures() {
        var names = new HashSet<String>();
        var archive = hue.getArchive(EffectClasses.CUSTOM_TEXTURES);
        if (archive != null) {
            for (var export : archive.getExportMap()) {
                names.add(key(export.getObjectName()));
            }
        }
        return names;
    }

    private byte[] previewTextures(Plan plan) throws IOException {
        var textures = plan.getTextures();
        if (builtTexturePackage == null || !textures.equals(builtTextures)) {
            if (!customTexturesRead) {
                var file = customTexturesFile();
                customTextures = file == null ? null : new PackageBytes(file, Files.readAllBytes(file));
                customTexturesRead = true;
            }
            var writer = textureWriter(customTextures);
            TexturePackageWriter.addTextures(writer, textures);
            builtTextures = textures;
            builtTexturePackage = writer.getBody();
        }
        return builtTexturePackage;
    }

    private ObjectTarget objectTarget(String path) throws IOException {
        var parts = path.split("\\.");
        var archive = parts.length < 2 ? null : hue.getArchive(parts[0]);
        if (archive == null) {
            throw new IOException("The client has no package for " + path);
        }
        var name = parts[parts.length - 1];
        var exports = archive.getExportMap();
        for (var index = 1; index <= exports.size(); index++) {
            var export = exports.get(index - 1);
            if (!export.getObjectName().equalsIgnoreCase(name)
                    || export.getClassName().equals("Package")) {
                continue;
            }
            var full = new ArrayList<String>();
            for (var at = index; at > 0; at = exports.get(at - 1).getPackageIndex()) {
                full.add(exports.get(at - 1).getObjectName());
            }
            full.add(archive.getName());
            return new ObjectTarget(
                    String.join(".", full.reversed()),
                    classPackage(archive, export.getClassName()),
                    export.getClassName());
        }
        throw new IOException("Package " + parts[0] + " has no object " + name);
    }

    private ClassPackageWriter open(ClassPackageWriter source) {
        try {
            return ClassPackageWriter.open(source.getOriginal());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ClassPackageWriter customWriter(ClassPackageWriter template) {
        try {
            if (packageFiles.containsKey(key(EffectEdits.CUSTOM_PACKAGE))) {
                return open(source(EffectEdits.CUSTOM_PACKAGE));
            }
            return ClassPackageWriter.create(EffectEdits.CUSTOM_PACKAGE, template.getOriginal());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String effectPath(String path, Placement placement) {
        var copy = edits.copies().get(path);
        if (copy != null) {
            return placement == Placement.CUSTOM_PACKAGE
                    ? path
                    : copy.source().substring(0, copy.source().indexOf('.')) + "." + copy.name();
        }
        if (!hasClass(path)) {
            throw new IllegalStateException("A skill action points at a class that does not exist: " + path);
        }
        return path;
    }

    private Output output(ClassPackageWriter writer) throws IOException {
        var source = packageFiles.get(key(writer.getPackageName()));
        var target = source != null ? source : systemDirectory.resolve(writer.getPackageName() + ".u");
        return new Output(target.getFileName().toString(), writer.encode(target), source, target);
    }

    private Output textureOutput(Plan plan) throws IOException {
        var existing = customTexturesFile();
        var target = existing != null ? existing : texturesDirectory().resolve(EffectClasses.CUSTOM_TEXTURES + ".utx");
        var writer = textureWriter(existing == null ? null : new PackageBytes(existing, Files.readAllBytes(existing)));
        TexturePackageWriter.addTextures(writer, plan.getTextures());
        return new Output(target.getFileName().toString(), writer.encode(target), existing, target);
    }

    private Path texturesDirectory() throws IOException {
        var textures = child(clientRoot, "Textures");
        return textures != null ? textures : clientRoot.resolve("Textures");
    }

    private @Nullable Path customTexturesFile() throws IOException {
        return child(texturesDirectory(), EffectClasses.CUSTOM_TEXTURES + ".utx");
    }

    private EmitterInfo info(String key, ClassPackageWriter owner, int export, ClassEdit edit) throws IOException {
        var payload = owner.getPayload(export);
        var tree = TaggedProperties.read(new Cursor(payload, 0, payload.length), owner);
        var label = tree.find("Name", owner);
        var disabled = tree.find("Disabled", owner);
        var off = disabled != null && disabled.getType() == TaggedProperties.BOOL && disabled.isTrue();
        for (var property : edit.emitter(key).properties()) {
            if (property.path().equalsIgnoreCase("Disabled")) {
                off = property.kind() != Kind.REMOVE && Boolean.parseBoolean(property.value());
            }
        }
        return new EmitterInfo(
                key,
                owner.getClassName(export),
                label == null ? "" : label.getString(),
                edit.emitter(key).disabled() || off);
    }

    private static void requireTaggedStructs(ClassPackageWriter owner) throws IOException {
        if (owner.getFileVersion() < TAGGED_STRUCTS_FROM) {
            throw new IOException("Package " + owner.getPackageName() + " has file version " + owner.getFileVersion()
                    + ", where a struct has no tags, and Edit writes tagged structs only");
        }
    }

    private static void attempt(String path, boolean skill, List<EditProblem> problems, Step step) {
        try {
            step.run();
        } catch (IOException | RuntimeException failure) {
            log.debug("The edit of {} cannot be used", path, failure);
            var name = path.substring(path.indexOf('.') + 1);
            problems.add(new EditProblem(
                    path,
                    skill,
                    (skill ? "The edit of skill " : "The edit of effect ")
                            + name
                            + " cannot be used: "
                            + reason(failure)
                            + "."));
        }
    }

    private static String reason(Throwable failure) {
        for (var at = failure; at != null; at = at.getCause()) {
            if (at instanceof IOException && at.getMessage() != null) {
                return at.getMessage();
            }
            if (at instanceof IllegalStateException && at.getCause() == null) {
                return at.getMessage();
            }
        }
        return "a value in it does not fit this client";
    }

    private static void addChanged(List<Output> outputs, Output output) throws IOException {
        if (output.source() == null
                || Files.size(output.source()) != output.bytes().length
                || !Arrays.equals(Files.readAllBytes(output.source()), output.bytes())) {
            outputs.add(output);
        }
    }

    private record ClassRef(ClassPackageWriter owner, int export, String name, String path) {}

    private record EditedSkill(ClassPackageWriter writer, Skill skill, Map<Integer, String> keys) {}

    private record PreviewBuild(ClassPackageWriter target, String name) {}

    private static boolean isNamed(ClassPackageWriter writer, int action) throws IOException {
        var payload = writer.getPayload(action);
        return TaggedProperties.read(new Cursor(payload, 0, payload.length), writer)
                        .find("EffectClass", writer)
                != null;
    }

    private static @Nullable Integer find(Map<String, Integer> keys, String key) {
        for (var entry : keys.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String classPackage(UArchive archive, String className) {
        for (var imported : archive.getImportMap()) {
            if (imported.getObjectName().equalsIgnoreCase(className)
                    && "Class".equalsIgnoreCase(imported.getClassName())) {
                var root = imported;
                while (root.getPackageIndex() < 0) {
                    root = archive.getImportMap().get(-root.getPackageIndex() - 1);
                }
                return root.getObjectName();
            }
        }
        return "Engine";
    }

    private static ClassPackageWriter textureWriter(@Nullable PackageBytes existing) throws IOException {
        return existing != null
                ? ClassPackageWriter.open(existing)
                : ClassPackageWriter.create(
                        EffectClasses.CUSTOM_TEXTURES, TEXTURE_FILE_VERSION, TEXTURE_LICENSE_VERSION);
    }

    private static void flatten(
            TaggedProperties block, String prefix, ClassPackageWriter names, List<PropertyInfo> result)
            throws IOException {
        for (var property : block.getProperties()) {
            var name = prefix + names.getName(property.name());
            if (property.getArrayIndex() != 0) {
                name += "[" + property.getArrayIndex() + "]";
            }
            if (property.getStruct() != null) {
                flatten(property.getStruct(), name + ".", names, result);
            } else if (property.getArray() != null) {
                var array = property.getArray();
                if (array.getElements() != null) {
                    for (var i = 0; i < array.getElements().size(); i++) {
                        flatten(array.getElements().get(i), name + "[" + i + "].", names, result);
                    }
                } else {
                    for (var i = 0; i < array.getObjects().size(); i++) {
                        result.add(new PropertyInfo(
                                name + "[" + i + "]",
                                Kind.OBJECT,
                                names.getObjectPath(array.getObjects().get(i))));
                    }
                }
            } else {
                var value = leaf(name, property, names);
                if (value != null) {
                    result.add(value);
                }
            }
        }
    }

    private static @Nullable PropertyInfo leaf(String path, Property property, ClassPackageWriter names)
            throws IOException {
        return switch (property.getType()) {
            case TaggedProperties.BOOL -> new PropertyInfo(path, Kind.BOOL, Boolean.toString(property.isTrue()));
            case TaggedProperties.BYTE -> new PropertyInfo(path, Kind.BYTE, Integer.toString(property.getByteValue()));
            case TaggedProperties.INT -> new PropertyInfo(path, Kind.INT, Integer.toString(property.getIntValue()));
            case TaggedProperties.FLOAT -> new PropertyInfo(path, Kind.FLOAT, Float.toString(property.getFloatValue()));
            case TaggedProperties.NAME -> new PropertyInfo(path, Kind.NAME, names.getName(property.getIndex()));
            case TaggedProperties.OBJECT ->
                new PropertyInfo(path, Kind.OBJECT, names.getObjectPath(property.getIndex()));
            case TaggedProperties.STR -> new PropertyInfo(path, Kind.STR, property.getString());
            case TaggedProperties.STRUCT -> binaryStruct(path, property, names);
            default -> null;
        };
    }

    private static @Nullable PropertyInfo binaryStruct(String path, Property property, ClassPackageWriter names)
            throws IOException {
        var values = property.getBinary();
        return switch (names.getName(property.getStructName()).toLowerCase(Locale.ROOT)) {
            case "vector" ->
                new PropertyInfo(
                        path, Kind.VECTOR, values.getFloat(0) + "," + values.getFloat(4) + "," + values.getFloat(8));
            case "rotator" ->
                new PropertyInfo(
                        path, Kind.ROTATOR, values.getInt(0) + "," + values.getInt(4) + "," + values.getInt(8));
            case "color" -> {
                var raw = property.getRaw();
                yield new PropertyInfo(
                        path,
                        Kind.COLOR,
                        (raw[2] & 255) + "," + (raw[1] & 255) + "," + (raw[0] & 255) + "," + (raw[3] & 255));
            }
            default -> null;
        };
    }

    private static UArchive archive(String name, byte[] body) {
        return new UArchive(name, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), null);
    }

    private static @Nullable Path child(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (var list = Files.list(directory)) {
            for (var file : list.toList()) {
                if (file.getFileName().toString().equalsIgnoreCase(name)) {
                    return file;
                }
            }
        }
        return null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
