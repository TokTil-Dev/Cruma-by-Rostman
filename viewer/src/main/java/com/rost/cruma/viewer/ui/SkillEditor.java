package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.editing.EffectEditSession.ActionInfo;
import com.rost.cruma.core.editing.EffectEdits;
import com.rost.cruma.core.editing.EffectEdits.ActionEdit;
import com.rost.cruma.core.editing.EffectEdits.AddedAction;
import com.rost.cruma.core.unreal.EffectClasses.Effect;
import com.rost.cruma.core.unreal.SkillEffects;
import com.rost.cruma.core.unreal.SkillEffects.Action;
import com.rost.cruma.core.unreal.SkillEffects.Layout;
import com.rost.cruma.core.unreal.SkillEffects.Phase;
import com.rost.cruma.core.unreal.SkillEffects.Skill;
import com.rost.cruma.viewer.editing.EffectEditController;
import com.rost.cruma.viewer.editing.SkillActions;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.TrackView.Bar;
import com.rost.cruma.viewer.ui.TrackView.Track;
import imgui.ImGui;
import imgui.flag.ImGuiComboFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jetbrains.annotations.Nullable;

final class SkillEditor {
    private static final String DRAG_TIP = " Drag the field left or right, or double-click it to type.";
    private static final int LIST_ROWS = 300;

    private final Host host;
    private final TrackView tracks = new TrackView("skill-tracks");
    private final float[] number = new float[1];
    private final ImString effectFilter = new ImString(64);
    private final ImString addFilter = new ImString(64);
    private final ImString boneName = new ImString(64);
    private boolean open;
    private @Nullable String editing;
    private @Nullable String chosenKey;
    private @Nullable Phase chosenPhase;
    private float heldDelay;
    private boolean heldMoved;
    private boolean heldTwice;
    private boolean revealAction;
    private boolean boneHeld;
    private boolean addOpen;
    private Phase addPhase = Phase.CASTING;
    private @Nullable String addEffect;
    private @Nullable CompletableFuture<String> copying;
    private String problem = "";
    private @Nullable List<Effect> keyedFrom;
    private Map<String, Effect> byKey = Map.of();

    SkillEditor(Host host) {
        this.host = host;
    }

    interface Host {
        @Nullable
        EffectEditController getController();

        List<Effect> getEffects();

        void openEffect(String path);
    }

    boolean isOpen() {
        return open;
    }

    void toggle() {
        open = !open;
    }

    void forget() {
        open = false;
        editing = null;
        keyedFrom = null;
        chosenKey = null;
        chosenPhase = null;
        addOpen = false;
        problem = "";
    }

    boolean isEdited(Skill skill) {
        var running = host.getController();
        return running != null && running.getEdits().skills().containsKey(skill.className());
    }

    boolean knows(String effectPath) {
        var running = host.getController();
        return effects().containsKey(SkillEffects.key(effectPath))
                || running != null && running.getEdits().copies().containsKey(effectPath);
    }

    void poll() {
        var done = copying;
        if (done == null || !done.isDone()) {
            return;
        }
        copying = null;
        try {
            done.join();
            problem = "";
        } catch (RuntimeException failure) {
            problem = message(failure);
        }
    }

    void draw(Skill skill, @Nullable SkillShow show) {
        var running = host.getController();
        if (running == null) {
            return;
        }
        var name = skill.className();
        if (!name.equals(editing)) {
            editing = name;
            chosenKey = null;
            chosenPhase = null;
            addOpen = false;
            problem = "";
        }
        var height = Math.max(200f, (float)
                Math.floor(ImGui.getContentRegionAvailY() - ImGui.getStyle().getItemSpacingY() - 8));
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.beginChild("##skillEditor", 0, height, false);
        ImGui.popStyleVar();
        var width = ImGui.getContentRegionAvailX();
        var actions = running.getActions(name);
        var client = running.getClientActions(name);
        if (actions == null || client == null || show == null) {
            ImGui.textColored(
                    GeodataWidgets.FAINT,
                    running.isBroken(name) ? "This edit cannot be read. Drop it at the top of the panel." : "Reading…");
        } else {
            drawTracks(running, name, actions, client, show, width);
            drawFlight(running, name, actions, show, width);
            drawProblems(running, width);
            var chosen = chosen(actions);
            if (chosen != null) {
                drawAction(running, name, chosen, clientOf(client, chosen.key()), width);
            }
            space(GeodataWidgets.SECTION);
            drawAdd(running, name, actions, width);
        }
        ImGui.dummy(0, GeodataWidgets.ROW);
        ImGui.endChild();
    }

    private void drawTracks(
            EffectEditController running,
            String name,
            List<ActionInfo> actions,
            List<ActionInfo> client,
            SkillShow show,
            float width) {
        GeodataWidgets.section("ACTIONS · " + actions.size(), 0);
        space(GeodataWidgets.ROW);
        if (actions.isEmpty()) {
            ImGui.textColored(GeodataWidgets.FAINT, "This skill plays no effect.");
            return;
        }
        var edit = running.getEdits().skillEdit(name);
        var rows = new ArrayList<Track>();
        for (var phase : Phase.values()) {
            var bars = new ArrayList<Bar>();
            for (var info : actions) {
                if (info.phase() == phase) {
                    bars.add(bar(info, SkillActions.merged(info.action(), edit.action(info.key())), show));
                }
            }
            if (!bars.isEmpty()) {
                rows.add(new Track(EffectsPanel.phaseName(phase), bars));
            }
        }
        var gesture = tracks.draw(rows, show.length(), show.getShownTime(), width);
        if (gesture == null) {
            return;
        }
        var split = gesture.bar().indexOf('/');
        var phase = Phase.valueOf(gesture.bar().substring(0, split));
        var key = gesture.bar().substring(split + 1);
        var held = infoOf(actions, key, phase);
        if (held == null) {
            return;
        }
        var merged = SkillActions.merged(held.action(), edit.action(key));
        switch (gesture.kind()) {
            case PRESSED -> {
                chosenKey = key;
                chosenPhase = phase;
                heldDelay = merged.delay();
                heldMoved = false;
                heldTwice = ImGui.isMouseDoubleClicked(0);
            }
            case DRAGGED -> {
                if (!layout(running, name).delay()) {
                    return;
                }
                heldMoved = true;
                var delay = TrackView.moved(heldDelay, gesture.seconds());
                running.drag(edited(running, name, key, clientOf(client, key), fields -> fields.withSpawnDelay(delay)));
            }
            case RELEASED -> {
                if (heldTwice && !heldMoved && knows(merged.effect())) {
                    host.openEffect(merged.effect());
                }
                heldTwice = false;
                running.settle();
                revealAction = !heldMoved;
            }
        }
    }

    private Bar bar(ActionInfo info, Action action, SkillShow show) {
        var start = show.startSeconds(info.phase(), action);
        var known = knows(action.effect());
        var parts = show.getParts().get(SkillEffects.key(action.effect()));
        var channel = info.phase() == Phase.CHANNELING;
        var life = channel ? show.getChannelLife(action) : show.getLife(info.phase(), action);
        var seconds = parts == null
                ? -1f
                : Math.min(
                        SkillActions.seconds(parts, life, channel && show.isChannelLifeFixed()),
                        SkillShow.LONGEST_SECONDS);
        var label = nameOf(action.effect()) + (known ? "" : " (missing)");
        var tip = known
                ? String.format(
                        Locale.ROOT,
                        "%s\n"
                                + "Starts at %.2f s, with a delay of %.2f s. Click to edit it. Drag it to change the"
                                + " delay. Double-click to open the effect itself in Effects.",
                        nameOf(action.effect()),
                        start,
                        Math.max(0f, action.delay()))
                : nameOf(action.effect())
                        + "\nThe client has no effect of this name, so this action plays nothing. Click to edit it.";
        var chosen = info.key().equals(chosenKey) && info.phase() == chosenPhase;
        return new Bar(info.phase().name() + "/" + info.key(), start, seconds, label, tip, chosen, !known);
    }

    private void drawFlight(
            EffectEditController running, String name, List<ActionInfo> actions, SkillShow show, float width) {
        var shot = false;
        for (var info : actions) {
            shot |= info.phase() == Phase.SHOT;
        }
        if (!shot) {
            return;
        }
        space(GeodataWidgets.ROW);
        var edit = running.getEdits().skillEdit(name);
        var client = show.getSkill().flyingTime();
        var value = edit.flyingTime() != null ? edit.flyingTime() : client;
        var third = third(width);
        var fieldWidth = Math.max(third, ImGui.calcTextSizeX("Flight time"));
        number[0] = value;
        var left = GeodataWidgets.compactDrag(
                "Flight time",
                "##skill-flight",
                number,
                fieldWidth,
                .01f,
                0,
                SkillActions.LONGEST_FLIGHT,
                "%.2f s",
                edit.flyingTime() != null ? GeodataWidgets.WARNING : GeodataWidgets.MUTED);
        GeodataWidgets.tip(
                "How long the game expects the shot to fly. The shot leaves that long before the hit time, so a"
                        + " longer time leaves earlier. A projectile then flies as fast as its effect, and can arrive"
                        + " before or after the hit time."
                        + DRAG_TIP);
        if (number[0] != value) {
            var kept = number[0] == client ? null : Float.valueOf(number[0]);
            running.drag(running.getEdits()
                    .withSkill(name, running.getEdits().skillEdit(name).withFlyingTime(kept)));
        }
        if (left) {
            running.settle();
        }
    }

    private void drawProblems(EffectEditController running, float width) {
        for (var text : new String[] {problem, running.getError()}) {
            if (!text.isEmpty()) {
                space(GeodataWidgets.ROW);
                GeodataWidgets.note(text, GeodataWidgets.BLOCKED, width);
            }
        }
    }

    private void drawAction(
            EffectEditController running, String name, ActionInfo chosen, @Nullable Action client, float width) {
        var key = chosen.key();
        var fields = running.getEdits().skillEdit(name).action(key);
        var action = SkillActions.merged(chosen.action(), fields);
        GeodataWidgets.section("ACTION");
        space(GeodataWidgets.ROW);
        drawEffect(running, name, chosen, client, action, width);
        if (revealAction) {
            revealAction = false;
            if (ImGui.getCursorScreenPosY() > ImGui.getWindowPosY() + ImGui.getWindowHeight()) {
                ImGui.setScrollHereY(1f);
            }
        }
        space(GeodataWidgets.ROW);
        drawOnAndPlace(running, name, key, client, action, width);
        if (action.attachOn() == SkillEffects.AM_BONE_SPECIFIED) {
            space(GeodataWidgets.ROW);
            drawBone(running, name, key, client, action, width);
        }
        space(GeodataWidgets.ROW);
        drawOffset(running, name, key, client, action, width);
        space(GeodataWidgets.ROW);
        drawSwitches(running, name, key, client, action, width);
        space(GeodataWidgets.ROW);
        drawNumbers(running, name, chosen, client, action, width);
    }

    private void drawEffect(
            EffectEditController running,
            String name,
            ActionInfo chosen,
            @Nullable Action client,
            Action action,
            float width) {
        var key = chosen.key();
        ImGui.textColored(
                labelColor(client, client != null && !client.effect().equalsIgnoreCase(action.effect())), "Effect");
        var label = nameOf(action.effect()) + (knows(action.effect()) ? "" : " (missing)");
        var side = ImGui.getFrameHeight();
        var spacing = ImGui.getStyle().getItemSpacingX();
        var filter = GeodataWidgets.searchList(
                "##skill-action-effect", label, effectFilter, "Search effects by name", width - 3 * (side + spacing));
        if (filter != null) {
            var picked = effectRows("skill-action", running, filter, action.effect());
            ImGui.endCombo();
            if (picked != null) {
                running.change(edited(running, name, key, client, edit -> edit.withEffectClass(picked)));
            }
        }
        GeodataWidgets.tip("What this action plays: any effect of the client, or a copy.");
        ImGui.sameLine();
        var known = knows(action.effect());
        ImGui.beginDisabled(!known);
        if (HudWidgets.button("skill-action-open-effect", Glyph.PENCIL, "", side, side, false, true)) {
            host.openEffect(action.effect());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                !known
                        ? "The client has no effect of this name, so there is nothing to open."
                        : running.getEdits().copies().containsKey(action.effect())
                                ? "Edit this copy in Effects: its emitters, colours and sizes. Skills brings you back."
                                : "Edit this effect in Effects: its emitters, colours and sizes. A change there shows"
                                        + " in every skill that plays it. A double click on its bar does the same."
                                        + " Skills brings you back.");
        ImGui.sameLine();
        drawCopy(running, name, key, action, side);
        ImGui.sameLine();
        if (HudWidgets.button("skill-action-remove", Glyph.TRASH, "", side, side, false, true)) {
            var edit = running.getEdits().skillEdit(name);
            running.change(running.getEdits().withSkill(name, SkillActions.removed(edit, key, chosen.phase())));
            chosenKey = null;
            chosenPhase = null;
        }
        GeodataWidgets.tip("Remove this action from the skill. Undo brings it back.");
    }

    private @Nullable String effectRows(
            String id, EffectEditController running, String filter, @Nullable String current) {
        String picked = null;
        var rows = 0;
        for (var copy : running.getEdits().copies().values()) {
            if (filter.isEmpty() || copy.name().toLowerCase(Locale.ROOT).contains(filter)) {
                rows++;
                if (GeodataWidgets.listItem(
                        id + "/" + copy.path(), copy.name(), "copy", copy.path().equals(current))) {
                    picked = copy.path();
                }
            }
        }
        for (var effect : host.getEffects()) {
            if (rows >= LIST_ROWS) {
                break;
            }
            if (filter.isEmpty() || effect.name().toLowerCase(Locale.ROOT).contains(filter)) {
                rows++;
                if (GeodataWidgets.listItem(
                        id + "/" + effect.path(),
                        effect.name(),
                        effect.packageName(),
                        effect.path().equalsIgnoreCase(current))) {
                    picked = effect.path();
                }
            }
        }
        if (rows == 0) {
            ImGui.textColored(GeodataWidgets.FAINT, "No effect matches.");
        }
        return picked;
    }

    private void drawCopy(EffectEditController running, String name, String key, Action action, float side) {
        var path = action.effect();
        var copied = running.getEdits().copies().containsKey(path);
        var effect = copied ? null : effects().get(SkillEffects.key(path));
        var refusal = effect == null ? null : running.copyRefusal(effect.path());
        var free = refusal != null && refusal.isEmpty();
        ImGui.beginDisabled(!free || copying != null);
        if (HudWidgets.button("skill-action-copy", Glyph.COPY, "", side, side, false, true) && effect != null) {
            makeCopy(running, name, key, effect.path());
        }
        ImGui.endDisabled();
        GeodataWidgets.tip(
                copied
                        ? "This action plays a copy already, so only this skill changes with it."
                        : effect == null
                                ? "The client has no effect of this name, so there is nothing to copy."
                                : refusal == null
                                        ? "Reading…"
                                        : !refusal.isEmpty()
                                                ? refusal
                                                : "Make a copy for this skill: a new effect of its own that only this"
                                                        + " action plays, so other skills keep the original. One Undo"
                                                        + " takes it back.");
    }

    private void makeCopy(EffectEditController running, String name, String key, String source) {
        problem = "";
        copying = running.change(session -> {
            var copy = session.copy(source, session.suggestCopyName(source));
            var edits = session.getEdits();
            var edit = edits.skillEdit(name);
            session.setEdits(
                    edits.withSkill(name, edit.withAction(key, edit.action(key).withEffectClass(copy))));
            return copy;
        });
    }

    private void drawOnAndPlace(
            EffectEditController running,
            String name,
            String key,
            @Nullable Action client,
            Action action,
            float width) {
        var half = half(width);
        GeodataWidgets.columns(
                width,
                () -> {
                    ImGui.beginGroup();
                    ImGui.textColored(
                            labelColor(client, client != null && client.onTarget() != action.onTarget()), "On");
                    var picked = GeodataWidgets.segmented(
                            "skill-action-on", new String[] {"Target", "Caster"}, action.onTarget() ? 0 : 1, half);
                    ImGui.endGroup();
                    GeodataWidgets.tip(
                            "Who the effect plays on. A projectile of the shot on the caster flies to the target.");
                    if (picked >= 0 && picked == 0 != action.onTarget()) {
                        running.change(edited(running, name, key, client, edit -> edit.withSpawnOnTarget(picked == 0)));
                    }
                    return false;
                },
                () -> {
                    ImGui.beginGroup();
                    ImGui.textColored(
                            labelColor(client, client != null && client.attachOn() != action.attachOn()), "Place");
                    ImGui.setNextItemWidth(half);
                    if (HudWidgets.beginCombo(
                            "##skill-action-attach",
                            SkillActions.attachName(action.attachOn()),
                            ImGuiComboFlags.HeightLarge)) {
                        for (var point : SkillActions.ATTACH_POINTS) {
                            if (!layout(running, name).places().contains(point.value())) {
                                continue;
                            }
                            if (ImGui.selectable(point.name(), point.value() == action.attachOn())) {
                                running.change(
                                        edited(running, name, key, client, edit -> edit.withAttachOn(point.value())));
                            }
                        }
                        ImGui.endCombo();
                    }
                    ImGui.endGroup();
                    GeodataWidgets.tip(
                            "Where on the body the effect stands. A hand, an arm, the chest and a bone move with the"
                                    + " figure. The rest stand at the middle of the body.");
                    return false;
                });
    }

    private void drawBone(
            EffectEditController running,
            String name,
            String key,
            @Nullable Action client,
            Action action,
            float width) {
        if (!boneHeld && !boneName.get().equals(action.bone())) {
            boneName.set(action.bone());
        }
        var left = GeodataWidgets.compactText("Bone name", "##skill-action-bone", "Bip01 R Hand", boneName, width);
        boneHeld = ImGui.isItemActive();
        GeodataWidgets.tip(
                "The bone of the figure's skeleton, such as Bip01 Head. A figure without it plays the effect at the"
                        + " middle of its body.");
        if (left) {
            var bone = boneName.get().strip();
            running.change(edited(running, name, key, client, edit -> edit.withAttachBoneName(bone)));
        }
    }

    private void drawSwitches(
            EffectEditController running,
            String name,
            String key,
            @Nullable Action client,
            Action action,
            float width) {
        GeodataWidgets.columns(
                width,
                () -> {
                    var relative = action.relativeToCylinder();
                    if (ImGui.checkbox("Body units##skill-action-relative", relative)) {
                        running.change(
                                edited(running, name, key, client, edit -> edit.withRelativeToCylinder(!relative)));
                    }
                    GeodataWidgets.tip(
                            "On, X, Y and Z count the body's radius across and its half height up, so Z -1 is the"
                                    + " feet. Off, they count units of the world.");
                    return false;
                },
                () -> {
                    if (ImGui.checkbox("Multi target##skill-action-multi", action.onMultiTarget())) {
                        running.change(edited(
                                running, name, key, client, edit -> edit.withOnMultiTarget(!action.onMultiTarget())));
                    }
                    GeodataWidgets.tip(
                            "The game plays this effect on every target of the skill. The tool has one target, so it"
                                    + " plays once.");
                    return false;
                });
    }

    private void drawOffset(
            EffectEditController running,
            String name,
            String key,
            @Nullable Action client,
            Action action,
            float width) {
        var third = third(width);
        var offset = action.offset();
        var values = new float[] {offset.getX(), offset.getY(), offset.getZ()};
        var labels = new String[] {"X", "Y", "Z"};
        var speed = action.relativeToCylinder() ? .01f : .5f;
        var format = action.relativeToCylinder() ? "%.2f" : "%.1f";
        var changed = client != null && !client.offset().equals(offset);
        var fields = new BooleanSupplier[3];
        for (var axis = 0; axis < 3; axis++) {
            var index = axis;
            fields[axis] = () -> drawNumber(
                    running,
                    true,
                    labels[index],
                    "##skill-action-offset-" + index,
                    values[index],
                    third,
                    speed,
                    -SkillActions.FURTHEST_OFFSET,
                    SkillActions.FURTHEST_OFFSET,
                    format,
                    labelColor(client, changed),
                    "How far from its place the effect stands, along this axis of the body.",
                    value -> {
                        var next = values.clone();
                        next[index] = value;
                        running.drag(edited(
                                running,
                                name,
                                key,
                                client,
                                edit -> edit.withOffset(List.of(next[0], next[1], next[2]))));
                    });
        }
        GeodataWidgets.columns(width, fields);
    }

    private void drawNumbers(
            EffectEditController running,
            String name,
            ActionInfo chosen,
            @Nullable Action client,
            Action action,
            float width) {
        var third = third(width);
        var key = chosen.key();
        var layout = layout(running, name);
        GeodataWidgets.columns(
                width,
                () -> drawNumber(
                        running,
                        layout.scale(),
                        "Scale",
                        "##skill-action-scale",
                        action.scale(),
                        third,
                        .01f,
                        0,
                        SkillActions.LARGEST_SCALE,
                        "%.2f",
                        labelColor(client, client != null && client.scale() != action.scale()),
                        layout.scale()
                                ? "ScaleSize, how large the effect plays: this times the radius of the body it plays"
                                        + " on, over 9. So 1 is the class's own size on a human, and larger on a wide"
                                        + " monster. 0 means the class's own size on every body."
                                : "This client keeps its skills in skill.usk, whose actions have no ScaleSize. An"
                                        + " effect plays at the size of its class.",
                        value -> running.drag(edited(running, name, key, client, edit -> edit.withScaleSize(value)))),
                () -> drawNumber(
                        running,
                        layout.delay(),
                        "Delay",
                        "##skill-action-delay",
                        Math.max(0f, action.delay()),
                        third,
                        .01f,
                        0,
                        SkillActions.LONGEST_DELAY,
                        "%.2f s",
                        labelColor(client, client != null && client.delay() != action.delay()),
                        layout.delay()
                                ? "How long after the start of its phase the effect starts. Its bar moves with this"
                                        + " field."
                                : "The Engine.u of this client has no SpawnDelay: an effect starts with its phase.",
                        value -> running.drag(edited(running, name, key, client, edit -> edit.withSpawnDelay(value)))),
                () -> {
                    ImGui.beginGroup();
                    ImGui.textColored(GeodataWidgets.MUTED, "Phase");
                    ImGui.setNextItemWidth(third);
                    if (HudWidgets.beginCombo(
                            "##skill-action-move",
                            EffectsPanel.phaseName(chosen.phase()),
                            ImGuiComboFlags.HeightLarge)) {
                        for (var phase : Phase.values()) {
                            if (!layout.phases().contains(phase) && phase != chosen.phase()) {
                                continue;
                            }
                            if (ImGui.selectable(EffectsPanel.phaseName(phase), phase == chosen.phase())
                                    && phase != chosen.phase()) {
                                var edit = running.getEdits().skillEdit(name);
                                running.change(running.getEdits()
                                        .withSkill(name, SkillActions.moved(edit, key, chosen.phase(), phase)));
                                chosenPhase = phase;
                            }
                        }
                        ImGui.endCombo();
                    }
                    ImGui.endGroup();
                    GeodataWidgets.tip("The phase of the cast this action plays in: "
                            + EffectsPanel.phaseName(chosen.phase()) + ". Choose another to move it there.");
                    return false;
                });
    }

    private boolean drawNumber(
            EffectEditController running,
            boolean enabled,
            String label,
            String id,
            float value,
            float width,
            float speed,
            float min,
            float max,
            String format,
            int labelColor,
            String tip,
            Consumer<Float> apply) {
        number[0] = value;
        ImGui.beginDisabled(!enabled);
        var left = GeodataWidgets.compactDrag(label, id, number, width, speed, min, max, format, labelColor);
        ImGui.endDisabled();
        GeodataWidgets.tip(enabled ? tip + DRAG_TIP : tip);
        if (number[0] != value) {
            apply.accept(number[0]);
        }
        if (left) {
            running.settle();
        }
        return left;
    }

    private void drawAdd(EffectEditController running, String name, List<ActionInfo> actions, float width) {
        if (!addOpen) {
            if (GeodataWidgets.actionButton("skill-add", Glyph.PLUS, "Add effect", width, false)) {
                addOpen = true;
                addEffect = null;
                if (chosenPhase != null) {
                    addPhase = chosenPhase;
                }
            }
            GeodataWidgets.tip("Add an action to any phase of the cast, also one with no actions yet.");
            return;
        }
        ImGui.textColored(GeodataWidgets.MUTED, "Phase");
        ImGui.setNextItemWidth(width);
        if (HudWidgets.beginCombo("##skill-add-phase", EffectsPanel.phaseName(addPhase), ImGuiComboFlags.HeightLarge)) {
            for (var phase : Phase.values()) {
                if (!layout(running, name).phases().contains(phase)) {
                    continue;
                }
                if (ImGui.selectable(EffectsPanel.phaseName(phase), phase == addPhase)) {
                    addPhase = phase;
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("The phase of the cast the new action plays in.");
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Effect");
        var chosen = addEffect;
        var filter = GeodataWidgets.searchList(
                "##skill-add-effect",
                chosen == null ? "Choose an effect" : nameOf(chosen),
                chosen == null ? GeodataWidgets.MUTED : GeodataWidgets.TEXT,
                addFilter,
                "Search effects by name",
                width);
        if (filter != null) {
            var picked = effectRows("skill-add", running, filter, chosen);
            if (picked != null) {
                addEffect = picked;
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("What the new action plays: any effect of the client, or a copy.");
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var half = (width - spacing) / 2;
        ImGui.beginDisabled(addEffect == null);
        if (HudWidgets.button("skill-add-make", null, "Add", half, HudWidgets.CONTROL_HEIGHT, true, true)) {
            add(running, name, actions);
        }
        ImGui.endDisabled();
        ImGui.sameLine();
        if (HudWidgets.button("skill-add-cancel", null, "Cancel", half, HudWidgets.CONTROL_HEIGHT, false, true)) {
            addOpen = false;
        }
    }

    private void add(EffectEditController running, String name, List<ActionInfo> actions) {
        var path = addEffect;
        if (path == null) {
            return;
        }
        var edit = running.getEdits().skillEdit(name);
        var key = SkillActions.freeName(actions, edit, name);
        var onCaster = addPhase == Phase.CASTING || addPhase == Phase.CHANNELING || addPhase == Phase.PRESHOT;
        var fields = ActionEdit.NONE.withEffectClass(path).withSpawnOnTarget(!onCaster);
        running.change(running.getEdits()
                .withSkill(name, edit.withAdded(new AddedAction(key, addPhase)).withAction(key, fields)));
        chosenKey = key;
        chosenPhase = addPhase;
        addOpen = false;
    }

    private @Nullable ActionInfo chosen(List<ActionInfo> actions) {
        return chosenKey == null || chosenPhase == null ? null : infoOf(actions, chosenKey, chosenPhase);
    }

    private Map<String, Effect> effects() {
        var listed = host.getEffects();
        if (keyedFrom != listed) {
            keyedFrom = listed;
            var keyed = new HashMap<String, Effect>();
            for (var effect : listed) {
                keyed.putIfAbsent(SkillEffects.key(effect.path()), effect);
            }
            byKey = keyed;
        }
        return byKey;
    }

    private static Layout layout(EffectEditController running, String name) {
        var known = running.getSkillLayout();
        return known != null ? known : name.indexOf('.') >= 0 ? Layout.visual(null) : Layout.CLASSES;
    }

    private static EffectEdits edited(
            EffectEditController running,
            String name,
            String key,
            @Nullable Action client,
            UnaryOperator<ActionEdit> change) {
        var edit = running.getEdits().skillEdit(name);
        var fields = SkillActions.withoutClientValues(change.apply(edit.action(key)), client);
        return running.getEdits().withSkill(name, edit.withAction(key, fields));
    }

    private static @Nullable ActionInfo infoOf(List<ActionInfo> actions, String key, Phase phase) {
        for (var info : actions) {
            if (info.key().equals(key) && info.phase() == phase) {
                return info;
            }
        }
        return null;
    }

    private static @Nullable Action clientOf(List<ActionInfo> client, String key) {
        for (var info : client) {
            if (info.key().equals(key)) {
                return info.action();
            }
        }
        return null;
    }

    private static int labelColor(@Nullable Action client, boolean changed) {
        return client != null && changed ? GeodataWidgets.WARNING : GeodataWidgets.MUTED;
    }

    private static float half(float width) {
        return (width - ImGui.getStyle().getItemSpacingX()) / 2;
    }

    private static float third(float width) {
        return (width - 2 * ImGui.getStyle().getItemSpacingX()) / 3;
    }

    private static String nameOf(String classPath) {
        return classPath.substring(classPath.indexOf('.') + 1);
    }

    private static String message(Throwable failure) {
        var cause = failure.getCause() != null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
