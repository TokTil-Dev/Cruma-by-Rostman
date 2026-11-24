package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.export.NpcExport.Race;
import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Fix;
import com.rost.cruma.core.world.SceneOrders.Warning;
import com.rost.cruma.viewer.spawn.SceneCharacter;
import com.rost.cruma.viewer.ui.CharacterForm.Limits;
import com.rost.cruma.viewer.ui.DirectorCatalog.Names;
import com.rost.cruma.viewer.ui.DirectorCatalog.Row;
import com.rost.cruma.viewer.ui.DirectorSession.Placing;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.type.ImString;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.ToDoubleFunction;
import org.jetbrains.annotations.Nullable;

final class DirectorActors {
    static final double AIRSHIP_LIFT = 1_000;
    private static final int FILTER_ROOM = 64;
    private static final float TRACK_WIDTH = 28;
    private static final float TRACK_HEIGHT = 24;
    private static final float TRACK_LIFT = 4;

    private final CharacterForm form = new CharacterForm("director-character");
    private final ImString npcFilter = new ImString(FILTER_ROOM);
    private final ImString skillFilter = new ImString(FILTER_ROOM);
    private final ImString effectFilter = new ImString(FILTER_ROOM);
    private final ImString showFilter = new ImString(FILTER_ROOM);
    private final ImString sequenceFilter = new ImString(FILTER_ROOM);
    private boolean limited;
    private boolean slots;
    private IntConsumer showTrack = _ -> {};
    private float cancelTop;
    private float ordersLeft;
    private float moveTop;
    private float trackLeft;
    private float trackTop;
    private float listsTop;
    private float listWidth;

    static String length(double seconds) {
        return Double.isNaN(seconds) ? "" : String.format(Locale.ROOT, "%.2f s", seconds);
    }

    static float drawWarning(DirectorSession open, Warning warning, int id, @Nullable String skill) {
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(text(open, warning), GeodataWidgets.WARNING);
        if (warning.fix() == Fix.NONE) {
            return 0;
        }
        space(GeodataWidgets.ROW);
        var top = ImGui.getCursorScreenPosY();
        if (HudWidgets.button(
                "director-fix/" + id, null, action(warning, skill), ImGui.getContentRegionAvailX(), 28, false, true)) {
            open.fix(warning);
        }
        return top;
    }

    static String text(DirectorSession open, Warning warning) {
        return switch (warning.problem()) {
            case ACTOR_GONE ->
                open.getActorName(open.order(warning.key()).index())
                        + " does not stand in the scene here, so the client does nothing.";
            case TARGET_GONE ->
                "The target does not stand in the scene here, and the client stays silent unless both do.";
            case NO_LEVEL_ONE -> "This skill has no level 1, and the client plays nothing else in a scene.";
            case NO_SEQUENCE -> missing(open.order(warning.key()));
            case FADE_DROPPED -> "Another fade runs here, and the client leaves this one out.";
            case NO_WINDOW -> "The client has no such window, so this line shows nowhere. Choose one below.";
            case SAME_PLACE ->
                open.getActorName(open.order(warning.key()).index())
                        + " stands in "
                        + open.getActorName(warning.other())
                        + String.format(
                                Locale.ROOT, " from %.2f s on, so the two go through each other.", warning.moment());
        };
    }

    static String action(Warning warning, @Nullable String skill) {
        return switch (warning.fix()) {
            case MOVE_ORDER -> String.format(Locale.ROOT, "Move it to %.2f s", warning.moment());
            case CAST_ON_SELF -> "Cast on itself";
            case USE_SKILL -> "Use " + (skill == null ? "skill " + warning.skill() : skill);
            case REMOVE_ORDER -> warning.key().part() == Part.SCREEN ? "Delete this fade" : "Delete this order";
            case MOVE_ASIDE -> "Move it aside";
            case NONE -> "";
        };
    }

    static Limits limits(@Nullable Model model) {
        var pc = model == null ? Set.<String>of() : model.pc();
        var mages = SceneCharacter.hasMages(pc);
        return new Limits(
                SceneCharacter.hasClothes(pc), false, race -> (mages || !isMage(race)) && SceneCharacter.canHold(race));
    }

    void onTrack(IntConsumer show) {
        showTrack = show;
    }

    boolean drawOrders(DirectorSession open, DirectorCatalog catalog, Key key) {
        moveTop = 0;
        var actor = open.getActorOf(key);
        if (actor.index() == 0 && !actor.player() || open.getPlacing() != null) {
            return false;
        }
        var ship = actor.action() == Action.AIRSHIP_SPAWN;
        var name = open.getActorName(actor.index());
        GeodataWidgets.section("ACTOR");
        space(GeodataWidgets.ROW);
        var nameLeft = ImGui.getCursorScreenPosX();
        var nameTop = ImGui.getCursorScreenPosY();
        var room = ImGui.getContentRegionAvailX();
        ImGui.textColored(
                GeodataWidgets.TEXT, HudWidgets.truncateToWidth(name, room - TRACK_WIDTH - GeodataWidgets.ROW));
        GeodataWidgets.tip("New orders go in at the playhead, and each is a key you can move on the actor's track.");
        trackLeft = nameLeft + room - TRACK_WIDTH;
        trackTop = nameTop - TRACK_LIFT;
        ImGui.setCursorScreenPos(trackLeft, trackTop);
        if (HudWidgets.button("director/track", Glyph.FOCUS, "", TRACK_WIDTH, TRACK_HEIGHT, false, true)) {
            showTrack.accept(actor.index());
        }
        GeodataWidgets.tip("Shows the track of this actor in the tray.");
        space(GeodataWidgets.ROW);
        ordersLeft = ImGui.getCursorScreenPosX();
        moveTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("Move", 0, 2)) {
            if (actor.fly()) {
                open.placeAt(SceneOrders.move(actor, 0, 0, 0, false), name, open.getMiddleAt(actor));
            } else {
                open.place(SceneOrders.move(actor, 0, 0, 0, false), name, ship ? AIRSHIP_LIFT : 0);
            }
        }
        GeodataWidgets.tip("Press in the world where the actor goes.");
        if (GeodataWidgets.rowButton("Despawn", 1, 2)) {
            open.addOrder(SceneOrders.despawn(actor));
        }
        GeodataWidgets.tip("The actor leaves the scene at the playhead.");
        if (ship) {
            return true;
        }
        space(GeodataWidgets.ROW);
        listsTop = ImGui.getCursorScreenPosY();
        var spacing = ImGui.getStyle().getItemSpacingX();
        listWidth = (ImGui.getContentRegionAvailX() - 2 * spacing) / 3;
        var names = catalog.getNames();
        var sequences = open.getSequences(actor.index());
        var sequence = drawSequenceList(
                "##director-add-animation",
                "Animation",
                GeodataWidgets.TEXT,
                sequences,
                listWidth,
                null,
                each -> open.getSequenceSeconds(actor.index(), each));
        GeodataWidgets.tip(
                sequences == null
                        ? "The list comes with the first run of the scene, as the model is read for it."
                        : "Plays a sequence of the model at the playhead. A death comes with what holds the body down,"
                                + " at its end.");
        if (sequence != null) {
            open.addAnimation(actor, sequence);
        }
        ImGui.sameLine();
        var skill = drawSkillList("##director-add-skill", "Skill", GeodataWidgets.TEXT, names, listWidth);
        GeodataWidgets.tip(
                "Casts a skill at the playhead, at first on the actor itself. The client plays only skills that have"
                        + " level 1.");
        if (skill != null) {
            open.addOrder(SceneOrders.skill(actor, skill.id(), actor.index()));
        }
        ImGui.sameLine();
        var effect = drawEffectList("##director-add-actor-effect", "Effect", GeodataWidgets.TEXT, names, listWidth);
        GeodataWidgets.tip("Starts an effect class on the actor at the playhead.");
        if (effect != null) {
            open.addOrder(SceneOrders.effect(actor, effect));
        }
        return true;
    }

    @Nullable
    Row drawNpcList(String id, String preview, int color, Names names, float width) {
        ImGui.beginDisabled(!names.read());
        var filter = GeodataWidgets.searchList(id, preview, color, npcFilter, "Search NPCs by name or number", width);
        Row chosen = null;
        if (filter != null) {
            for (var row : DirectorCatalog.matching(names.npcs(), filter)) {
                var note = row.note().isEmpty() ? Integer.toString(row.id()) : row.note();
                if (GeodataWidgets.listItem(Integer.toString(row.id()), row.name(), note, false)) {
                    chosen = row;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    Row drawSkillList(String id, String preview, int color, Names names, float width) {
        ImGui.beginDisabled(!names.read());
        var filter =
                GeodataWidgets.searchList(id, preview, color, skillFilter, "Search skills by name or number", width);
        Row chosen = null;
        if (filter != null) {
            for (var row : DirectorCatalog.matching(names.skills(), filter)) {
                if (GeodataWidgets.listItem(Integer.toString(row.id()), row.name(), row.note(), false)) {
                    chosen = row;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    Row showList(String id, String preview, int color, Names names, boolean can, float width) {
        ImGui.beginDisabled(!can || names.shows().isEmpty());
        var filter =
                GeodataWidgets.searchList(id, preview, color, showFilter, "Search skills by name or number", width);
        Row chosen = null;
        if (filter != null) {
            for (var row : DirectorCatalog.matching(names.shows(), filter)) {
                if (GeodataWidgets.listItem(Integer.toString(row.id()), row.name(), row.note(), false)) {
                    chosen = row;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    String drawEffectList(String id, String preview, int color, Names names, float width) {
        ImGui.beginDisabled(names.effects().isEmpty());
        var filter = GeodataWidgets.searchList(id, preview, color, effectFilter, "Search effects by name", width);
        String chosen = null;
        if (filter != null) {
            for (var name : DirectorCatalog.matchingNames(names.effects(), filter)) {
                if (GeodataWidgets.listItem(name, name, "", false)) {
                    chosen = name;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    @Nullable
    String drawSequenceList(
            String id,
            String preview,
            int color,
            @Nullable List<String> sequences,
            float width,
            @Nullable String none,
            ToDoubleFunction<String> seconds) {
        ImGui.beginDisabled(sequences == null);
        var filter = GeodataWidgets.searchList(id, preview, color, sequenceFilter, "Search sequences by name", width);
        String chosen = null;
        if (filter != null) {
            if (none != null && filter.isEmpty() && GeodataWidgets.listItem("##none", none, "", false)) {
                chosen = "";
            }
            for (var name : DirectorCatalog.matchingNames(
                    sequences == null ? List.of() : sequences, filter, Integer.MAX_VALUE)) {
                if (GeodataWidgets.listItem(name, name, length(seconds.applyAsDouble(name)), false)) {
                    chosen = name;
                }
            }
            ImGui.endCombo();
        }
        ImGui.endDisabled();
        return chosen;
    }

    void drawPlacing(DirectorSession open, Placing placing) {
        cancelTop = 0;
        GeodataWidgets.section("PLACE");
        GeodataWidgets.tip("A press in the world gives the new thing its place.");
        space(GeodataWidgets.ROW);
        var what = placing.point()
                ? "Press on the floor in the world where " + placing.name() + " stands."
                : placing.turns()
                        ? "Press in the world to place " + placing.name() + ". A drag sets the way it faces."
                        : "Press in the world where "
                                + (placing.order().action() == Action.EFFECT
                                        ? placing.name() + " starts."
                                        : placing.name() + " goes.");
        GeodataWidgets.hint(what);
        space(GeodataWidgets.ROW);
        cancelTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.rowButton("Cancel placing", 0, 1)) {
            open.cancelPlacing();
        }
        GeodataWidgets.tip("Goes back with nothing placed.", "Esc");
        if (open.isReadingFloors()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note("Reading the floors of this place.", GeodataWidgets.MUTED);
        } else if (placing.lost()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note("The pointer has no floor under it here.", GeodataWidgets.MUTED);
        }
    }

    boolean drawForm(DirectorCatalog catalog, @Nullable Model model) {
        if (!limited) {
            limited = true;
            form.setLimit(limits(model));
        }
        var pc = model == null ? Set.<String>of() : model.pc();
        slots = SceneCharacter.hasSlots(pc);
        catalog.read(form);
        form.draw(false, ImGui.getContentRegionAvailX());
        if (model != null && pc.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint(SceneCharacter.NO_PLAYERS);
        } else if (!SceneCharacter.hasClothes(pc)) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.hint("The scenes of this client keep no clothes, so the character stands in its bare body.");
        }
        return form.getLook() != null;
    }

    void placeCharacter(DirectorSession open) {
        var look = form.getLook();
        if (look != null) {
            open.place(SceneCharacter.order(look.face(), look.figure(), look.items(), slots), "the character", 0);
        }
    }

    private static String missing(Actor order) {
        return order.action() == Action.ANIMATION
                ? "Missing: this model has no " + order.animation() + ", so the actor goes on waiting."
                : "Missing: this model has no " + order.moveSequence() + ", so the actor walks or runs.";
    }

    private static boolean isMage(Race race) {
        return race.face().startsWith("Magic.") || race.face().startsWith("Shaman.");
    }
}
