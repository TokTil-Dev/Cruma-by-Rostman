package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.editing.EffectEditSession.EmitterInfo;
import com.rost.cruma.core.editing.EffectEditSession.PropertyInfo;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.core.editing.EffectEdits.Copy;
import com.rost.cruma.core.editing.EffectEdits.EmitterEdit;
import com.rost.cruma.core.editing.EffectEdits.Kind;
import com.rost.cruma.core.editing.EffectEdits.Knobs;
import com.rost.cruma.core.editing.EffectEdits.PropertyEdit;
import com.rost.cruma.core.npc.ClientIcons.Icon;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.core.world.EffectParts;
import com.rost.cruma.core.world.TextureQuality;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ExportSettings;
import com.rost.cruma.viewer.editing.ClientTextureIndex;
import com.rost.cruma.viewer.editing.EffectColors;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.EffectEditController.Rebuilt;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.NativeDialogs.Outcome;
import imgui.ImColor;
import imgui.ImGui;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class EffectEditor {
    static final float FACTOR_MIN = EffectEditController.FACTOR_MIN;

    static final float FACTOR_MAX = EffectEditController.FACTOR_MAX;

    private static final String UNDO_KEY =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac") ? "Cmd+Z" : "Ctrl+Z";
    private static final String DRAG_TIP = " Drag the field left or right, or double-click it to type.";
    private static final int HUE_LIMIT = EffectEditController.HUE_LIMIT;
    private static final int LIST_ROWS = 300;
    private static final int COPY_ROWS = 4;
    private static final float EYE = 26;
    private static final float THUMBNAIL = 24;
    private static final float SWATCH_WIDTH = 22;
    private static final float SWATCH_GAP = 6;
    private static final float LABEL_SHARE = .54f;
    private static final double NO_CHANGE = .005;
    private static final int EFFECT = 0xFF5CDBF1;
    private static final String RENAME_TIP = "The name of the copy's class. Type another and press Enter to rename the"
            + " copy: its edits and the skills that play it follow. Letters, digits and underscores. A name cannot end"
            + " in a digit, as the client reads that as an object's number. After an install the copy is a class of"
            + " the client and keeps its name.";
    private static final List<String> IMAGE_FILES = List.of("*.png", "*.jpg", "*.jpeg", "*.bmp", "*.dds");

    private final Host host;
    private final EffectExportDialog exportDialog;
    private final ClassSourceDialog sourceDialog = new ClassSourceDialog();
    private final FolderPicker imagePicker = FolderPicker.file(
            "Choose an image", "Pick the folder of the PNG, JPEG, BMP or DDS file, and its name below.", "");
    private final float[] factor = new float[1];
    private final int[] degrees = new int[1];
    private final float[] color = new float[3];
    private final EffectColors colors = new EffectColors();
    private final float[] propertyColor = new float[4];
    private final ImString copyName = new ImString(64);
    private final ImString newName = new ImString(64);
    private final List<String[]> renames = new ArrayList<>();
    private final ImString textureFilter = new ImString(64);
    private final ImString effectFilter = new ImString(64);
    private final ImString propertyText = new ImString(1024);
    private @Nullable EffectEditController controller;
    private @Nullable ClientTextureIndex textures;
    private @Nullable Path client;
    private @Nullable Path dataRoot;
    private @Nullable FontManager fonts;
    private @Nullable String watched;
    private boolean open;
    private @Nullable String openEmitter;
    private boolean propertiesOpen;
    private @Nullable String scrollToEmitter;
    private boolean scrollToProperties;
    private boolean copyFormOpen;
    private @Nullable String copyNameFor;
    private String copyProblem = "";
    private String problem = "";
    private @Nullable CompletableFuture<String> copying;
    private @Nullable String newNameFor;
    private String renameProblem = "";
    private @Nullable CompletableFuture<String> renaming;
    private boolean addOpen;
    private @Nullable Effect addFrom;
    private @Nullable CompletableFuture<String> adding;
    private @Nullable CompletableFuture<String> importing;
    private @Nullable CompletableFuture<String> settingObject;
    private @Nullable String imageClass;
    private @Nullable String imageEmitter;
    private Map<String, List<Skill>> usersByEffect = Map.of();
    private @Nullable List<Skill> usersOf;
    private Map<String, String> namesByKey = Map.of();
    private @Nullable List<Effect> namesOf;
    private float sizeLeft;
    private float sizeTop;
    private float colorLeft;
    private float colorTop;
    private float numberRight;
    private float numberTop;
    private float copyLeft;
    private float copyTop;
    private float nameLeft;
    private float nameTop;
    private float dropLeft;
    private float dropTop;

    EffectEditor(Host host) {
        this.host = host;
        exportDialog = new EffectExportDialog(this::clientChanged);
    }

    interface Host {
        List<Effect> getEffects();

        List<Skill> getSkills();

        String getSkillName(Skill skill);

        long getTexture(String path);

        long getPicture(String key, Icon icon);

        void choose(Effect effect);

        void reread();

        @Nullable
        EffectParts getShown();

        void pause();
    }

    static String getUndoKey() {
        return UNDO_KEY;
    }

    static double settled(double value) {
        return Math.abs(value - 1) < NO_CHANGE ? 1 : value;
    }

    static double rounded(double value) {
        return Math.rint(value * 1e4) / 1e4;
    }

    static String usersLine(List<String> names) {
        var shown = Math.min(3, names.size());
        var list = String.join(", ", names.subList(0, shown));
        var count = names.size() == 1 ? "1 skill" : names.size() + " skills";
        var more = names.size() > shown ? " and " + (names.size() - shown) + " more" : "";
        return "Used by " + count + ": " + list + more + ".";
    }

    static String simpleLine(String simpleClass) {
        return "Players with low detail effects see " + simpleClass + ", which this edit does not change.";
    }

    static @Nullable String simpleClass(String effectName, Map<String, String> names) {
        return names.get((effectName + "_simple").toLowerCase(Locale.ROOT));
    }

    static String copyTip(boolean skillPackage) {
        return skillPackage
                ? "A copy is a new effect of its own, so the skills keep the original. Skills can then be pointed at"
                        + " it."
                : "A copy is a new effect of its own. The client plays a copy only through a skill's package, which"
                        + " this client does not have, so a copy is useful as a place to try things.";
    }

    static String kindName(String emitterClass) {
        return emitterClass.endsWith("Emitter")
                ? emitterClass.substring(0, emitterClass.length() - "Emitter".length())
                : emitterClass;
    }

    static @Nullable String valueProblem(Kind kind, String text) {
        return EffectEditController.valueProblem(kind, text);
    }

    void dataRoot(@Nullable Path folder) {
        if (dataRoot == null) {
            dataRoot = folder;
        }
    }

    void dialogs(FontManager fontManager, @Nullable ExportSettings settings) {
        fonts = fontManager;
        exportDialog.setSettings(settings);
        sourceDialog.setSettings(settings);
    }

    void client(@Nullable Path root, TextureQuality quality, WorldVariant variant) {
        if (Objects.equals(root, client)) {
            return;
        }
        if (controller != null) {
            controller.close();
            controller = null;
        }
        if (textures != null) {
            textures.close();
            textures = null;
        }
        client = root;
        open = false;
        openEmitter = null;
        copyFormOpen = false;
        newNameFor = null;
        renaming = null;
        renames.clear();
        addOpen = false;
        importing = null;
        settingObject = null;
        problem = "";
        if (root != null) {
            controller = new EffectEditController(root, dataRoot, quality, variant);
        }
    }

    void installed(boolean restored) {
        if (controller != null) {
            if (restored) {
                controller.restored();
            } else {
                controller.installed();
            }
        }
        clientChanged();
    }

    void openSource(String classPath) {
        var running = controller;
        if (running == null) {
            return;
        }
        host.pause();
        var name = classPath.substring(classPath.indexOf('.') + 1) + ".uc";
        sourceDialog.open(name, running.isEdited(classPath), running.read(session -> session.getSource(classPath)));
    }

    void openSkillSource(String className) {
        var running = controller;
        if (running == null) {
            return;
        }
        host.pause();
        var dot = className.lastIndexOf('.');
        var name = dot < 0 ? className + ".uc" : className.substring(dot + 1) + ".t3d";
        var edited = !running.getEdits().skillEdit(className).isEmpty();
        sourceDialog.open(name, edited, running.read(session -> session.getSkillSource(className)));
    }

    @Nullable
    EffectEditController getController() {
        return controller;
    }

    boolean isReady() {
        return controller != null && controller.isReady();
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        open = !open;
    }

    boolean isEdited(String classPath) {
        return controller != null && controller.isEdited(classPath);
    }

    boolean isLost(Effect effect, List<Effect> listed) {
        return controller != null
                && effect.packageName().equals(EffectEdits.CUSTOM_PACKAGE)
                && !controller.getEdits().copies().containsKey(effect.path())
                && !listed.contains(effect);
    }

    void renamed(String before, String after) {
        renames.add(new String[] {before, after});
    }

    @Nullable
    Effect findRenamed(Effect lost) {
        var running = controller;
        if (running == null) {
            return null;
        }
        for (var index = renames.size() - 1; index >= 0; index--) {
            var pair = renames.get(index);
            var other = pair[0].equals(lost.path()) ? pair[1] : pair[1].equals(lost.path()) ? pair[0] : null;
            var copy = other == null ? null : running.getEdits().copies().get(other);
            if (copy != null) {
                return findEffectOf(copy);
            }
        }
        return null;
    }

    void openEffect(String path) {
        var copy = controller == null ? null : controller.getEdits().copies().get(path);
        var effect = copy == null ? null : findEffectOf(copy);
        if (effect == null) {
            var key = SkillEffects.key(path);
            for (var candidate : host.getEffects()) {
                if (SkillEffects.key(candidate.path()).equals(key)) {
                    effect = candidate;
                    break;
                }
            }
        }
        if (effect != null && controller != null) {
            host.choose(effect);
            open = true;
        }
    }

    CompletableFuture<@Nullable EffectParts> build(String classPath) {
        return Objects.requireNonNull(controller).build(classPath);
    }

    void watch(@Nullable String classPath) {
        if (Objects.equals(classPath, watched)) {
            return;
        }
        watched = classPath;
        openEmitter = null;
        propertiesOpen = false;
        copyFormOpen = false;
        addOpen = false;
        problem = "";
        if (controller != null) {
            controller.watch(classPath);
        }
    }

    @Nullable
    Rebuilt poll() {
        var running = controller;
        if (running == null) {
            return null;
        }
        running.poll();
        if (running.isDragging() && !ImGui.isMouseDown(0) && !ImGui.isAnyItemActive()) {
            running.settle();
        }
        if (copying != null && copying.isDone()) {
            finishCopy();
        }
        if (renaming != null && renaming.isDone()) {
            finishRename();
        }
        if (adding != null && adding.isDone()) {
            finishAdd();
        }
        if (importing != null && importing.isDone()) {
            finishImport();
        }
        if (settingObject != null && settingObject.isDone()) {
            finishObject();
        }
        return running.takeRebuilt();
    }

    String getSourceOf(String classPath) {
        var copy = controller == null ? null : controller.getEdits().copies().get(classPath);
        return copy == null ? classPath : copy.source();
    }

    void handleKeys() {
        var running = controller;
        var io = ImGui.getIO();
        if (running == null || io.getWantTextInput() || !ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)) {
            return;
        }
        if ((io.getKeyCtrl() || io.getKeySuper()) && ImGui.isKeyPressed(ImGuiKey.Z, false)) {
            if (io.getKeyShift()) {
                running.redo();
            } else {
                running.undo();
            }
        }
    }

    void drawNotice() {
        var running = controller;
        if (running == null) {
            return;
        }
        var images = running.getImageProblems();
        for (var text : images) {
            GeodataWidgets.note(text, GeodataWidgets.WARNING);
        }
        if (!images.isEmpty()) {
            space(GeodataWidgets.BLOCK);
        }
        drawEditProblems(running, false);
        var stale = running.getStale();
        if (stale == null) {
            return;
        }
        if (stale.edits() == null) {
            GeodataWidgets.note(
                    "The saved edits could not be read. Their file stays in " + stale.file(), GeodataWidgets.WARNING);
        } else {
            var detail = stale.changed().isEmpty()
                    ? "Their files are as they were again"
                    : String.join(", ", stale.changed()) + " changed";
            if (GeodataWidgets.callout("effect-stale", Glyph.PENCIL, "Saved edits left out", detail, "Apply")) {
                running.applyStale();
            }
            GeodataWidgets.tip(
                    "These edits were made on other files than the client has now, so they were not applied. Apply"
                        + " puts them in place of the present edits, and Undo brings those back. Their file stays in "
                            + stale.file());
        }
        space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton(
                "effect-stale-discard", Glyph.CLOSE, "Discard these edits", ImGui.getContentRegionAvailX(), false)) {
            running.discardStale();
        }
        GeodataWidgets.tip("Delete the file of these saved edits. They are offered until you apply or discard them.");
        space(GeodataWidgets.BLOCK);
    }

    void drawSkillNotice() {
        if (controller != null) {
            drawEditProblems(controller, true);
        }
    }

    boolean isFooterShown() {
        var running = controller;
        return running != null && (running.canUndo() || running.canRedo() || running.hasEdits());
    }

    void drawFooter() {
        var running = controller;
        if (running == null) {
            return;
        }
        var pressed = GeodataWidgets.undoBar(
                "effect-export",
                running.hasEdits() ? running.getSummary() : "No changes",
                running.hasEdits() ? running.getCounts() : "No changes",
                running.isUnexported() && running.hasEdits(),
                running.canUndo(),
                running.canRedo(),
                UNDO_KEY,
                running.hasEdits() ? "Discard" : null,
                "Take back every change of the effects, the copies and the skills at once. One Undo brings them all"
                        + " back.",
                "Export",
                running.hasEdits(),
                "Write the changed packages into a new folder. The client does not change until you install them.");
        switch (pressed) {
            case GeodataWidgets.BAR_UNDO -> running.undo();
            case GeodataWidgets.BAR_REDO -> running.redo();
            case GeodataWidgets.BAR_SECOND -> discardAll(running);
            case GeodataWidgets.BAR_ACTION -> exportDialog.open(running);
            default -> {}
        }
    }

    void drawDialogs() {
        exportDialog.draw(fonts);
        sourceDialog.draw(fonts);
        var image = imagePicker.draw();
        if (image != null) {
            importImage(image);
        }
    }

    void flush() {
        if (controller != null) {
            controller.flush();
        }
    }

    void drawCopies(String query, @Nullable Effect selected) {
        var running = controller;
        if (running == null || running.getEdits().copies().isEmpty()) {
            return;
        }
        var shown = new ArrayList<Copy>();
        for (var copy : running.getEdits().copies().values()) {
            if (query.isEmpty() || copy.name().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(copy);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        GeodataWidgets.section("COPIES · " + shown.size());
        space(GeodataWidgets.ROW);
        var rowHeight = SpawnPanelWidgets.listRowHeight();
        var step = rowHeight + ImGui.getStyle().getItemSpacingY();
        var scrolls = shown.size() > COPY_ROWS;
        if (scrolls) {
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
            ImGui.beginChild("##effectCopies", 0, COPY_ROWS * step, false);
            ImGui.popStyleVar();
        }
        for (var copy : shown) {
            copyRow(running, copy, query, selected, rowHeight);
        }
        if (scrolls) {
            ImGui.endChild();
        }
    }

    void draw(Effect effect) {
        var running = controller;
        if (running == null) {
            return;
        }
        var height = Math.max(200f, (float)
                Math.floor(ImGui.getContentRegionAvailY() - ImGui.getStyle().getItemSpacingY() - 8));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##effectEditor", 0, height, false);
        ImGui.popStyleVar();
        var width = ImGui.getContentRegionAvailX();
        var path = effect.path();
        var said = drawUsers(running, effect, width);
        GeodataWidgets.section("WHOLE EFFECT", said ? GeodataWidgets.SECTION : 0);
        space(GeodataWidgets.ROW);
        var edit = running.getEdits().classEdit(path);
        drawKnobs(
                "effect",
                edit.knobs(),
                width,
                knobs -> running.drag(running.getEdits()
                        .withClass(path, running.getEdits().classEdit(path).withKnobs(knobs))),
                () -> running.change(running.getEdits()
                        .withClass(path, running.getEdits().classEdit(path).withKnobs(Knobs.NONE))),
                running);
        drawColors(width);
        if (openEmitter == null) {
            drawProblems(running);
        }
        drawEmitters(running, effect, width);
        ImGui.dummy(0, GeodataWidgets.ROW);
        ImGui.endChild();
    }

    Effect findEffectOf(Copy copy) {
        for (var effect : host.getEffects()) {
            if (effect.path().equalsIgnoreCase(copy.source())) {
                return new Effect(
                        EffectEdits.CUSTOM_PACKAGE,
                        copy.name(),
                        effect.superName(),
                        effect.sprites(),
                        effect.meshes(),
                        effect.beams(),
                        effect.undrawn(),
                        false);
            }
        }
        return new Effect(EffectEdits.CUSTOM_PACKAGE, copy.name(), "Emitter", 0, 0, 0, 0, false);
    }

    private void discardAll(EffectEditController running) {
        running.change(EffectEdits.NONE);
        running.clearError();
        problem = "";
    }

    private void drawEditProblems(EffectEditController running, boolean skills) {
        for (var broken : running.getEditProblems()) {
            if (broken.skill() == skills) {
                GeodataWidgets.note(broken.text(), GeodataWidgets.WARNING);
                space(GeodataWidgets.ROW);
                if (GeodataWidgets.actionButton(
                        "edit-drop/" + broken.path(),
                        Glyph.CLOSE,
                        "Drop this edit",
                        ImGui.getContentRegionAvailX(),
                        false)) {
                    running.drop(broken);
                }
                dropLeft = ImGui.getItemRectMinX();
                dropTop = ImGui.getItemRectMinY();
                GeodataWidgets.tip("Take this edit away and keep the others. Undo brings it back.");
                space(GeodataWidgets.BLOCK);
            }
        }
    }

    private void copyRow(
            EffectEditController running, Copy copy, String query, @Nullable Effect selected, float rowHeight) {
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var width = ImGui.getContentRegionAvailX();
        var rowWidth = width - EYE - GeodataWidgets.ROW;
        if (ImGui.invisibleButton("copy/" + copy.path(), rowWidth, rowHeight)) {
            host.choose(findEffectOf(copy));
        }
        var draw = ImGui.getWindowDrawList();
        var chosen = selected != null && selected.path().equals(copy.path());
        if (ImGui.isItemHovered() || chosen) {
            draw.addRectFilled(
                    x, y, x + rowWidth, y + rowHeight, chosen ? ImColor.rgb(30, 58, 70) : GeodataWidgets.HOVER, 6f);
        }
        SpawnPanelWidgets.listRow(
                draw, x, y, rowWidth, EFFECT, copy.name(), "copy of " + nameOf(copy.source()), "", query);
        ImGui.setCursorScreenPos(x + rowWidth + GeodataWidgets.ROW, y + (rowHeight - EYE) * .5f);
        if (HudWidgets.button("copy-delete/" + copy.path(), Glyph.CLOSE, "", EYE, EYE, false, false)) {
            running.change(running.getEdits().withoutCopy(copy.path()));
        }
        GeodataWidgets.tip("Delete this copy. Undo brings it back.");
        ImGui.setCursorScreenPos(x, y + rowHeight);
        ImGui.dummy(width, 0);
    }

    private boolean drawUsers(EffectEditController running, Effect effect, float width) {
        var path = effect.path();
        if (running.getEdits().copies().containsKey(path)) {
            drawCopyName(running, effect, width);
            return true;
        }
        var users = users(effect);
        var said = false;
        if (!users.isEmpty()) {
            var names = new ArrayList<String>();
            for (var skill : users) {
                names.add(host.getSkillName(skill));
            }
            GeodataWidgets.note(usersLine(names), GeodataWidgets.MUTED, width);
            said = true;
        }
        var simple = simpleClass(effect.name(), listedNames());
        if (simple != null) {
            if (said) {
                space(GeodataWidgets.ROW);
            }
            GeodataWidgets.note(simpleLine(simple), GeodataWidgets.MUTED, width);
            said = true;
        }
        if (users.size() > 1) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "A change here changes all of them. The game can also start an effect by name, which the tool"
                            + " cannot see.",
                    GeodataWidgets.WARNING,
                    width);
        }
        var refusal = running.copyRefusal(path);
        if (refusal == null) {
            return said;
        }
        if (said) {
            space(GeodataWidgets.ROW);
        }
        if (!refusal.isEmpty()) {
            GeodataWidgets.note(refusal, GeodataWidgets.MUTED, width);
        } else if (copyFormOpen) {
            drawCopyForm(running, effect, width);
        } else {
            if (GeodataWidgets.actionButton("effect-make-copy", Glyph.COPY, "Make a copy", width, false)) {
                copyFormOpen = true;
                copyNameFor = null;
                copyProblem = "";
            }
            copyLeft = ImGui.getItemRectMinX();
            copyTop = ImGui.getItemRectMinY();
            GeodataWidgets.tip(copyTip(!Boolean.FALSE.equals(running.hasSkillPackage())));
        }
        return true;
    }

    private Map<String, String> listedNames() {
        var effects = host.getEffects();
        if (namesOf != effects) {
            namesOf = effects;
            var names = new HashMap<String, String>();
            for (var effect : effects) {
                names.put(effect.name().toLowerCase(Locale.ROOT), effect.name());
            }
            namesByKey = names;
        }
        return namesByKey;
    }

    private void drawCopyForm(EffectEditController running, Effect effect, float width) {
        var path = effect.path();
        if (!path.equals(copyNameFor)) {
            var suggested = running.suggestCopyName(path);
            if (suggested != null) {
                copyName.set(suggested);
                copyNameFor = path;
            }
        }
        GeodataWidgets.compactText("Name of the copy", "##effect-copy-name", "name_copy", copyName, width);
        GeodataWidgets.tip(
                "Letters, digits and underscores. A name cannot end in a digit, as the client reads that as an"
                        + " object's number.");
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (width - spacing) / 2;
        ImGui.beginDisabled(copying != null || copyName.get().isBlank());
        if (HudWidgets.button("effect-copy-make", null, "Copy", half, HudWidgets.CONTROL_HEIGHT, true, true)) {
            var name = copyName.get().strip();
            copyProblem = "";
            copying = running.change(session -> session.copy(path, name));
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (HudWidgets.button("effect-copy-cancel", null, "Cancel", half, HudWidgets.CONTROL_HEIGHT, false, true)) {
            copyFormOpen = false;
            copyProblem = "";
        }
        if (!copyProblem.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(copyProblem, GeodataWidgets.BLOCKED, width);
        }
    }

    private void drawCopyName(EffectEditController running, Effect effect, float width) {
        var path = effect.path();
        if (!path.equals(newNameFor)) {
            newNameFor = path;
            newName.set(effect.name());
            renameProblem = "";
        }
        ImGui.beginDisabled(renaming != null);
        var left = GeodataWidgets.compactText("Name of the copy", "##effect-copy-rename", "name_copy", newName, width);
        ImGui.endDisabled();
        nameLeft = ImGui.getItemRectMinX();
        nameTop = ImGui.getItemRectMaxY() - ImGui.getFrameHeight();
        GeodataWidgets.tip(RENAME_TIP);
        if (left) {
            var name = newName.get().strip();
            renameProblem = "";
            if (name.isEmpty() || name.equals(effect.name())) {
                newName.set(effect.name());
            } else {
                renamed(path, new Copy(name, "").path());
                renaming = running.change(session -> session.renameCopy(path, name));
            }
        }
        if (!renameProblem.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(renameProblem, GeodataWidgets.BLOCKED, width);
        }
    }

    private void finishRename() {
        var done = renaming;
        renaming = null;
        var running = controller;
        if (done == null || running == null) {
            return;
        }
        try {
            var copy = running.getEdits().copies().get(done.join());
            if (copy == null || copy.path().equals(watched)) {
                return;
            }
            var emitter = openEmitter;
            var properties = propertiesOpen;
            host.choose(findEffectOf(copy));
            openEmitter = emitter;
            propertiesOpen = properties;
        } catch (RuntimeException failure) {
            renameProblem = message(failure);
        }
    }

    private void finishCopy() {
        var done = copying;
        copying = null;
        var running = controller;
        if (done == null || running == null) {
            return;
        }
        try {
            var path = done.join();
            copyFormOpen = false;
            copyProblem = "";
            var copy = running.getEdits().copies().get(path);
            if (copy != null) {
                host.choose(findEffectOf(copy));
            }
        } catch (RuntimeException failure) {
            copyProblem = message(failure);
        }
    }

    private List<Skill> users(Effect effect) {
        var skills = host.getSkills();
        if (usersOf != skills) {
            usersOf = skills;
            var index = new HashMap<String, List<Skill>>();
            for (var skill : skills) {
                for (var phase : Phase.values()) {
                    for (var action : skill.actions(phase)) {
                        var list = index.computeIfAbsent(SkillEffects.key(action.effect()), _ -> new ArrayList<>());
                        if (!list.contains(skill)) {
                            list.add(skill);
                        }
                    }
                }
            }
            usersByEffect = index;
        }
        return usersByEffect.getOrDefault(SkillEffects.key(effect.path()), List.of());
    }

    private void drawKnobs(
            String id, Knobs knobs, float width, Consumer<Knobs> apply, Runnable reset, EffectEditController running) {
        var spacing = ImGui.getStyle().getItemSpacingX();
        var third = (width - 2 * spacing) / 3;
        GeodataWidgets.columns(
                width,
                () -> factorField(
                        "Size",
                        id + "-size",
                        knobs.size(),
                        FACTOR_MAX,
                        third,
                        knobs::withSize,
                        apply,
                        running,
                        "How large the particles are, from their start to their end. 2.00x is twice as large."),
                () -> factorField(
                        "Spread",
                        id + "-spread",
                        knobs.spread(),
                        FACTOR_MAX,
                        third,
                        knobs::withSpread,
                        apply,
                        running,
                        "How far from the middle the particles start. 2.00x covers twice the distance."),
                () -> factorField(
                        "Speed",
                        id + "-speed",
                        knobs.speed(),
                        FACTOR_MAX,
                        third,
                        knobs::withSpeed,
                        apply,
                        running,
                        "How fast the particles move, with their pull and their limits. They live as long, so they fly"
                                + " further."));
        space(GeodataWidgets.ROW);
        GeodataWidgets.columns(
                width,
                () -> factorField(
                        "Lifetime",
                        id + "-lifetime",
                        knobs.lifetime(),
                        FACTOR_MAX,
                        third,
                        knobs::withLifetime,
                        apply,
                        running,
                        "How long each particle lives, with its fades. 2.00x lives twice as long."),
                () -> factorField(
                        "Count",
                        id + "-count",
                        knobs.count(),
                        FACTOR_MAX,
                        third,
                        knobs::withCount,
                        apply,
                        running,
                        "How many particles there are, and how many start each second. A few particles change only in"
                                + " whole steps."),
                () -> factorField(
                        "Opacity",
                        id + "-opacity",
                        knobs.opacity(),
                        FACTOR_MAX,
                        third,
                        knobs::withOpacity,
                        apply,
                        running,
                        "How strongly the particles show. 0.50x shows them at half strength. An emitter never goes"
                                + " above full strength."));
        space(GeodataWidgets.ROW);
        var popup = "##" + id + "-color";
        GeodataWidgets.columns(
                width,
                () -> hueField(id, knobs, third, apply, running),
                () -> {
                    color[0] = (float) knobs.red();
                    color[1] = (float) knobs.green();
                    color[2] = (float) knobs.blue();
                    var white = knobs.red() == 1 && knobs.green() == 1 && knobs.blue() == 1;
                    if (GeodataWidgets.compactSwatch(
                            "Colour",
                            "##" + id + "-swatch",
                            color,
                            third,
                            white ? GeodataWidgets.MUTED : GeodataWidgets.WARNING)) {
                        ImGui.openPopup(popup);
                    }
                    if (id.equals("effect")) {
                        colorLeft = ImGui.getItemRectMinX();
                        colorTop = ImGui.getItemRectMaxY() - ImGui.getFrameHeight();
                    }
                    GeodataWidgets.tip(
                            "A tint that the particles' colour is multiplied by, so it can only darken. Hue changes"
                                    + " the colour itself. Click it to choose.");
                    return false;
                },
                () -> {
                    ImGui.beginGroup();
                    ImGui.textUnformatted(" ");
                    ImGui.beginDisabled(knobs.isNone());
                    if (HudWidgets.button(id + "-reset", null, "Reset", third, ImGui.getFrameHeight(), false, true)) {
                        reset.run();
                    }
                    ImGui.endDisabled();
                    ImGui.endGroup();
                    GeodataWidgets.tip("Back to no change for these fields.");
                    return false;
                });
        var left = GeodataWidgets.colorPopup(popup, color);
        if (color[0] != (float) knobs.red() || color[1] != (float) knobs.green() || color[2] != (float) knobs.blue()) {
            apply.accept(knobs.withColor(rounded(color[0]), rounded(color[1]), rounded(color[2])));
        }
        if (left) {
            running.settle();
        }
    }

    private void drawColors(float width) {
        var swatches = colors.of(host.getShown());
        if (swatches.isEmpty()) {
            return;
        }
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Plays in");
        GeodataWidgets.tip(
                "The colour each emitter plays in now: its texture, its colour over life and its multiplier as one"
                        + " colour, with your changes. Colour and Hue above change them. Colour is a tint on top of the"
                        + " file, so it is white when nothing is changed, also after an install.");
        var perRow = Math.max(1, (int) ((width + SWATCH_GAP) / (SWATCH_WIDTH + SWATCH_GAP)));
        for (var from = 0; from < swatches.size(); from += perRow) {
            var count = Math.min(perRow, swatches.size() - from);
            var rgb = new float[count][];
            var names = new String[count];
            for (var i = 0; i < count; i++) {
                var swatch = swatches.get(from + i);
                rgb[i] = new float[] {swatch.red(), swatch.green(), swatch.blue()};
                names[i] = swatch.name();
            }
            GeodataWidgets.swatchRow(
                    "##effect-colours/" + from,
                    rgb,
                    names,
                    -1,
                    swatches.size() <= perRow ? width : count * SWATCH_WIDTH + (count - 1) * SWATCH_GAP);
        }
    }

    private boolean factorField(
            String label,
            String id,
            double value,
            float highest,
            float width,
            DoubleFunction<Knobs> with,
            Consumer<Knobs> apply,
            EffectEditController running,
            String tip) {
        factor[0] = (float) value;
        var labelColor = value == 1 ? GeodataWidgets.MUTED : GeodataWidgets.WARNING;
        var left = GeodataWidgets.compactFactor(label, "##" + id, factor, width, FACTOR_MIN, highest, labelColor);
        if (id.equals("effect-size")) {
            sizeLeft = ImGui.getItemRectMinX();
            sizeTop = ImGui.getItemRectMaxY() - ImGui.getFrameHeight();
        }
        GeodataWidgets.tip(tip + DRAG_TIP);
        if (factor[0] != (float) value) {
            apply.accept(with.apply(rounded(factor[0])));
        }
        if (left) {
            var kept = settled(factor[0]);
            if (kept == 1 && factor[0] != 1) {
                apply.accept(with.apply(1));
            }
            running.settle();
        }
        return left;
    }

    private boolean hueField(String id, Knobs knobs, float width, Consumer<Knobs> apply, EffectEditController running) {
        degrees[0] = (int) Math.round(knobs.hue());
        var was = degrees[0];
        var labelColor = knobs.hue() == 0 ? GeodataWidgets.MUTED : GeodataWidgets.WARNING;
        var left = GeodataWidgets.compactDrag(
                "Hue", "##" + id + "-hue", degrees, width, 1, -HUE_LIMIT, HUE_LIMIT, "%d°", labelColor);
        GeodataWidgets.tip(
                "Turns every colour around the colour wheel, also those of the textures: 120° makes red green. 0° is"
                        + " no change."
                        + DRAG_TIP);
        if (degrees[0] != was) {
            apply.accept(knobs.withHue(degrees[0]));
        }
        if (left) {
            running.settle();
        }
        return left;
    }

    private void drawProblems(EffectEditController running) {
        for (var text : new String[] {problem, running.getError()}) {
            if (!text.isEmpty()) {
                space(GeodataWidgets.ROW);
                GeodataWidgets.note(text, GeodataWidgets.BLOCKED);
            }
        }
    }

    private void drawEmitters(EffectEditController running, Effect effect, float width) {
        var path = effect.path();
        var emitters = running.getEmitters(path);
        GeodataWidgets.section(emitters == null ? "EMITTERS" : "EMITTERS · " + emitters.size());
        space(GeodataWidgets.ROW);
        if (emitters == null) {
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    running.isBroken(path)
                            ? "The client has no such effect. Drop this edit at the top of the panel."
                            : "Reading…");
            return;
        }
        for (var emitter : emitters) {
            drawEmitterRow(running, path, emitter, width);
            if (emitter.key().equals(openEmitter)) {
                drawEmitterEditor(running, path, emitter, width);
            }
        }
        space(GeodataWidgets.ROW);
        drawAddEmitter(running, path, width);
    }

    private void drawEmitterRow(EffectEditController running, String path, EmitterInfo emitter, float width) {
        var texture = textureOf(running, path, emitter);
        var title = emitter.label().isBlank() ? emitter.key() : emitter.label();
        var detail = kindName(emitter.kind()) + (texture.isEmpty() ? "" : " · " + nameOf(texture));
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var rowWidth = width - EYE - GeodataWidgets.ROW;
        var opened = emitter.key().equals(openEmitter);
        if (emitter.key().equals(scrollToEmitter)) {
            scrollToEmitter = null;
            ImGui.setScrollHereY(0f);
        }
        if (GeodataWidgets.thumbnailRow(
                "emitter/" + emitter.key(),
                picture(running, texture),
                Glyph.SPARKLES,
                title,
                detail,
                THUMBNAIL,
                opened,
                rowWidth)) {
            openEmitter = opened ? null : emitter.key();
            scrollToEmitter = openEmitter;
            propertiesOpen = false;
        }
        var bottom = ImGui.getCursorScreenPosY();
        ImGui.setCursorScreenPos(x + rowWidth + GeodataWidgets.ROW, y + (bottom - y - EYE) * .5f);
        if (HudWidgets.button(
                "emitter-eye/" + emitter.key(),
                emitter.disabled() ? Glyph.EYE_OFF : Glyph.EYE,
                "",
                EYE,
                EYE,
                false,
                false)) {
            toggleDisabled(running, path, emitter);
        }
        GeodataWidgets.tip(
                emitter.disabled()
                        ? "This emitter is off. Click to turn it on."
                        : "Turn this emitter off: its particles do not play.");
        ImGui.setCursorScreenPos(x, bottom);
        ImGui.dummy(width, 0);
    }

    private void toggleDisabled(EffectEditController running, String path, EmitterInfo emitter) {
        var edit = running.getEdits().classEdit(path).emitter(emitter.key());
        if (!emitter.disabled()) {
            changeEmitter(running, path, emitter.key(), current -> current.withDisabled(true));
        } else if (edit.disabled()) {
            changeEmitter(running, path, emitter.key(), current -> current.withDisabled(false));
        } else {
            changeEmitter(
                    running,
                    path,
                    emitter.key(),
                    current -> current.withProperty(PropertyEdit.bool("Disabled", false)));
        }
    }

    private long picture(EffectEditController running, String texture) {
        var name = running.getEdits().textureName(texture);
        if (name == null) {
            return host.getTexture(texture);
        }
        var file = running.getEdits().textures().get(name);
        var icon = running.getPicture(file);
        return icon == null ? 0 : host.getPicture("effect-image/" + file, icon);
    }

    private void drawEmitterEditor(EffectEditController running, String path, EmitterInfo emitter, float width) {
        var key = emitter.key();
        space(GeodataWidgets.ROW);
        var edit = running.getEdits().classEdit(path).emitter(key);
        drawKnobs(
                "emitter",
                edit.knobs(),
                width,
                knobs -> {
                    var whole = running.getEdits().classEdit(path);
                    running.drag(running.getEdits()
                            .withClass(
                                    path,
                                    whole.withEmitter(key, whole.emitter(key).withKnobs(knobs))));
                },
                () -> changeEmitter(running, path, key, current -> current.withKnobs(Knobs.NONE)),
                running);
        if (!emitter.kind().contains("Mesh")) {
            space(GeodataWidgets.ROW);
            drawTexture(running, path, emitter, width);
        }
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (width - spacing) / 2;
        if (HudWidgets.button(
                "emitter-duplicate", Glyph.COPY, "Duplicate", half, HudWidgets.CONTROL_HEIGHT, false, true)) {
            adding = running.change(session -> session.duplicateEmitter(path, key));
        }
        GeodataWidgets.tip("Add a copy of this emitter to the effect, with its changes.");
        ImGui.sameLine();
        if (HudWidgets.button("emitter-remove", Glyph.CLOSE, "Remove", half, HudWidgets.CONTROL_HEIGHT, false, true)) {
            running.change(running.getEdits().withoutEmitter(path, key));
            openEmitter = null;
        }
        GeodataWidgets.tip("Take this emitter out of the effect. Undo brings it back.");
        drawProblems(running);
        var properties = running.getProperties(path, key);
        space(GeodataWidgets.SECTION);
        if (scrollToProperties) {
            scrollToProperties = false;
            ImGui.setScrollHereY(0f);
        }
        if (SpawnPanelWidgets.sectionRow(
                "emitter-properties",
                propertiesOpen,
                "ALL PROPERTIES",
                properties == null ? "" : Integer.toString(properties.size()))) {
            propertiesOpen = !propertiesOpen;
            scrollToProperties = propertiesOpen;
        }
        if (propertiesOpen && properties != null) {
            space(GeodataWidgets.ROW);
            for (var property : properties) {
                drawProperty(running, path, key, property, edit, width);
            }
        }
        space(GeodataWidgets.BLOCK);
    }

    private void drawTexture(EffectEditController running, String path, EmitterInfo emitter, float width) {
        var current = textureOf(running, path, emitter);
        ImGui.textColored(GeodataWidgets.MUTED, "Texture");
        var filter = GeodataWidgets.searchList(
                "##emitter-texture",
                current.isEmpty() ? "None" : nameOf(current),
                textureFilter,
                "Search textures by name",
                width);
        if (filter != null) {
            var imported = drawImportedTextures(running, path, emitter, filter, current);
            if (textures == null && client != null) {
                textures = new ClientTextureIndex(client);
            }
            var index = textures;
            if (index != null) {
                var rows = index.findMatching(filter, LIST_ROWS);
                for (var texture : rows) {
                    var dot = texture.indexOf('.');
                    if (GeodataWidgets.listItem(
                            "texture/" + texture,
                            texture.substring(dot + 1),
                            texture.substring(0, dot),
                            texture.equalsIgnoreCase(current))) {
                        changeEmitter(
                                running,
                                path,
                                emitter.key(),
                                edit -> edit.withProperty(PropertyEdit.object("Texture", texture)));
                    }
                }
                if (index.isReading()) {
                    ImGui.textColored(GeodataWidgets.FAINT, "Reading the client's textures…");
                } else if (rows.isEmpty() && imported == 0) {
                    ImGui.textColored(GeodataWidgets.FAINT, "No texture matches.");
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("The picture of the particles, from the client's textures.");
        space(GeodataWidgets.ROW);
        ImGui.beginDisabled(importing != null);
        if (GeodataWidgets.actionButton(
                "emitter-import-image",
                Glyph.FILE,
                importing != null ? "Reading the image…" : "Import image",
                width,
                false)) {
            chooseImage(path, emitter.key());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                "Make the picture of the particles from a PNG, JPEG, BMP or DDS file. Each side is a power of two, up"
                        + " to 4096. A copy of the file stays with the edits, and an export puts the texture in"
                        + " LineageEffect_Custom_T.utx.");
    }

    private int drawImportedTextures(
            EffectEditController running, String path, EmitterInfo emitter, String filter, String current) {
        var listed = 0;
        for (var name : running.getEdits().textures().keySet()) {
            if (filter.isEmpty() || name.toLowerCase(Locale.ROOT).contains(filter)) {
                listed++;
                var texture = EffectEdits.texturePath(name);
                if (GeodataWidgets.listItem(
                        "texture/" + texture, name, "Imported", texture.equalsIgnoreCase(current))) {
                    changeEmitter(
                            running,
                            path,
                            emitter.key(),
                            edit -> edit.withProperty(PropertyEdit.object("Texture", texture)));
                }
            }
        }
        return listed;
    }

    private void chooseImage(String path, String emitterKey) {
        imageClass = path;
        imageEmitter = emitterKey;
        if (NativeDialogs.isAvailable()) {
            var chosen = NativeDialogs.chooseFile("Choose an image", null, IMAGE_FILES, "Images");
            if (chosen.outcome() == Outcome.CHOSEN) {
                importImage(chosen.path());
            }
            return;
        }
        imagePicker.openPath("");
    }

    private void importImage(Path image) {
        var running = controller;
        var path = imageClass;
        var key = imageEmitter;
        if (running != null && path != null && key != null) {
            problem = "";
            importing = running.change(session -> session.importImage(image, path, key));
        }
    }

    private void finishImport() {
        var done = importing;
        importing = null;
        if (done == null) {
            return;
        }
        try {
            done.join();
        } catch (RuntimeException failure) {
            problem = message(failure);
        }
    }

    private void finishObject() {
        var done = settingObject;
        settingObject = null;
        if (done == null) {
            return;
        }
        try {
            done.join();
        } catch (RuntimeException failure) {
            problem = message(failure);
        }
    }

    private void clientChanged() {
        if (textures != null) {
            textures.close();
            textures = null;
        }
        host.reread();
    }

    private void drawProperty(
            EffectEditController running,
            String path,
            String key,
            PropertyInfo property,
            EmitterEdit edit,
            float width) {
        ImGui.pushID(property.path());
        var edited = false;
        for (var change : edit.properties()) {
            edited |= change.path().equalsIgnoreCase(property.path());
        }
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var labelWidth = width * LABEL_SHARE;
        ImGui.alignTextToFramePadding();
        ImGui.textColored(
                edited ? GeodataWidgets.WARNING : GeodataWidgets.MUTED,
                tailToWidth(property.path(), labelWidth - GeodataWidgets.ROW));
        GeodataWidgets.tip(property.path());
        ImGui.setCursorScreenPos(x + labelWidth, y);
        ImGui.setNextItemWidth(width - labelWidth);
        switch (property.kind()) {
            case BOOL -> {
                var on = Boolean.parseBoolean(property.value());
                if (ImGui.checkbox("##value", on)) {
                    changeEmitter(
                            running,
                            path,
                            key,
                            current -> current.withProperty(PropertyEdit.bool(property.path(), !on)));
                }
            }
            case COLOR -> drawColorProperty(running, path, key, property);
            default -> drawTextProperty(running, path, key, property);
        }
        ImGui.popID();
    }

    private void drawTextProperty(EffectEditController running, String path, String key, PropertyInfo property) {
        var flags = switch (property.kind()) {
            case BYTE, INT -> ImGuiInputTextFlags.CharsDecimal;
            case FLOAT -> ImGuiInputTextFlags.CharsScientific;
            default -> ImGuiInputTextFlags.None;
        };
        propertyText.set(property.value());
        ImGui.inputText("##value", propertyText, flags);
        if (property.kind() == Kind.BYTE) {
            numberRight = ImGui.getItemRectMaxX();
            numberTop = ImGui.getItemRectMinY();
        }
        if (ImGui.isItemDeactivatedAfterEdit()) {
            var text = propertyText.get().strip();
            var wrong = valueProblem(property.kind(), text);
            if (wrong != null) {
                problem = wrong;
            } else if (!text.equals(property.value())) {
                problem = "";
                if (property.kind() == Kind.OBJECT) {
                    settingObject = running.change(session -> {
                        session.objectEdit(path, key, property.path(), text);
                        return text;
                    });
                } else {
                    changeEmitter(
                            running,
                            path,
                            key,
                            current -> current.withProperty(new PropertyEdit(property.path(), property.kind(), text)));
                }
            }
        }
        if (ImGui.isItemActive() && !problem.isEmpty()) {
            problem = "";
        }
    }

    private void drawColorProperty(EffectEditController running, String path, String key, PropertyInfo property) {
        var parts = property.value().split(",");
        for (var i = 0; i < 4 && i < parts.length; i++) {
            propertyColor[i] = Integer.parseInt(parts[i].trim()) / 255f;
        }
        ImGui.colorEdit4(
                "##value",
                propertyColor,
                ImGuiColorEditFlags.NoInputs | ImGuiColorEditFlags.NoLabel | ImGuiColorEditFlags.AlphaBar);
        var text = Math.round(propertyColor[0] * 255)
                + ","
                + Math.round(propertyColor[1] * 255)
                + ","
                + Math.round(propertyColor[2] * 255)
                + ","
                + Math.round(propertyColor[3] * 255);
        if (!text.equals(property.value().replace(" ", ""))) {
            var whole = running.getEdits().classEdit(path);
            running.drag(running.getEdits()
                    .withClass(
                            path,
                            whole.withEmitter(
                                    key,
                                    whole.emitter(key)
                                            .withProperty(new PropertyEdit(property.path(), Kind.COLOR, text)))));
        }
        if (ImGui.isItemDeactivated()) {
            running.settle();
        }
    }

    private void drawAddEmitter(EffectEditController running, String path, float width) {
        if (!addOpen) {
            if (GeodataWidgets.actionButton("emitter-add", Glyph.PLUS, "Add emitter", width, false)) {
                addOpen = true;
                addFrom = null;
            }
            GeodataWidgets.tip("Copy an emitter of another effect into this one.");
            return;
        }
        var from = addFrom;
        var filter = GeodataWidgets.searchList(
                "##emitter-add-effect",
                from == null ? "Choose an effect" : from.name(),
                from == null ? GeodataWidgets.MUTED : GeodataWidgets.TEXT,
                effectFilter,
                "Search effects by name",
                width);
        if (filter != null) {
            var rows = 0;
            for (var effect : host.getEffects()) {
                if (rows >= LIST_ROWS) {
                    break;
                }
                if (filter.isEmpty() || effect.name().toLowerCase(Locale.ROOT).contains(filter)) {
                    rows++;
                    if (GeodataWidgets.listItem(
                            "effect/" + effect.path(),
                            effect.name(),
                            Integer.toString(effect.emitters()),
                            effect.equals(from))) {
                        addFrom = effect;
                    }
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("The effect that has the emitter.");
        var chosen = addFrom;
        if (chosen != null) {
            var emitters = running.getEmitters(chosen.path());
            if (emitters != null) {
                space(GeodataWidgets.ROW);
                for (var emitter : emitters) {
                    var texture = textureOf(running, chosen.path(), emitter);
                    var title = emitter.label().isBlank() ? emitter.key() : emitter.label();
                    if (GeodataWidgets.thumbnailRow(
                            "emitter-from/" + emitter.key(),
                            picture(running, texture),
                            Glyph.SPARKLES,
                            title,
                            kindName(emitter.kind()),
                            THUMBNAIL,
                            false,
                            width)) {
                        var fromClass = chosen.path();
                        var fromKey = emitter.key();
                        adding = running.change(session -> session.addEmitter(path, fromClass, fromKey));
                        addOpen = false;
                    }
                }
            }
        }
        space(GeodataWidgets.ROW);
        if (GeodataWidgets.actionButton("emitter-add-cancel", null, "Cancel", width, false)) {
            addOpen = false;
        }
    }

    private void finishAdd() {
        var done = adding;
        adding = null;
        if (done == null) {
            return;
        }
        try {
            openEmitter = done.join();
            scrollToEmitter = openEmitter;
            propertiesOpen = false;
        } catch (RuntimeException failure) {
            problem = message(failure);
        }
    }

    private static String nameOf(String classPath) {
        return classPath.substring(classPath.indexOf('.') + 1);
    }

    private static void changeEmitter(
            EffectEditController running, String path, String key, UnaryOperator<EmitterEdit> change) {
        var edit = running.getEdits().classEdit(path);
        running.change(running.getEdits().withClass(path, edit.withEmitter(key, change.apply(edit.emitter(key)))));
    }

    private static String textureOf(EffectEditController running, String path, EmitterInfo emitter) {
        var properties = running.getProperties(path, emitter.key());
        if (properties != null) {
            for (var property : properties) {
                if (property.path().equalsIgnoreCase("Texture")
                        && !property.value().equalsIgnoreCase("None")) {
                    return property.value();
                }
            }
        }
        return "";
    }

    private static String tailToWidth(String text, float width) {
        if (ImGui.calcTextSizeX(text) <= width) {
            return text;
        }
        var start = 0;
        while (start < text.length() && ImGui.calcTextSizeX("…" + text.substring(start)) > width) {
            start++;
        }
        return "…" + text.substring(start);
    }

    private static String message(Throwable failure) {
        var cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
