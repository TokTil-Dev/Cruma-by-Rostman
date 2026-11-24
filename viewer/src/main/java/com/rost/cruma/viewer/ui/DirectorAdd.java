package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.SceneFit;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneScreens;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.viewer.ui.DirectorCatalog.Media;
import com.rost.cruma.viewer.ui.DirectorCatalog.Names;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class DirectorAdd {
    private static final String MENU = "##director-add-menu";
    private static final float MENU_WIDTH = 236;
    private static final float LABEL_WIDTH = 64;
    private static final String READING = "Reading what the client has.";
    private static final float IN_SIGHT = 80;

    private final DirectorActors actors;
    private final DirectorMedia media;
    private final DirectorQuickStart quick = new DirectorQuickStart();
    private @Nullable Row pending;
    private boolean opening;
    private boolean placed;
    private int of;
    private int escapedAt = -1;
    private float buttonLeft;
    private float buttonTop;
    private final Map<String, float[]> rows = new HashMap<>();
    private float listTop;
    private float cancelTop;

    enum Row {
        KEY(
                "Camera key",
                Glyph.CAMERA,
                "K",
                false,
                "Puts a key of the path at the playhead with what the camera shows."),
        SHOT(
                "New shot",
                Glyph.SLICE,
                "C",
                false,
                "Ends the shot at the playhead and starts a new one with what the camera shows. The camera jumps"
                        + " there."),
        ORBIT(
                "Orbit…",
                Glyph.ORBIT,
                "",
                false,
                "The camera circles an actor or a point at the distance and the height it has now, looking at it. A"
                        + " new shot at the end of the path."),
        FLY_TO(
                "Fly to…",
                Glyph.ROUTE,
                "",
                false,
                "A flight from the view now to a view you fly to. A new shot at the end of the path."),
        REVEAL(
                "Reveal…",
                Glyph.ZOOM_OUT,
                "",
                false,
                "The camera pulls back from the view now and rises, so the shot ends wide over the place. A new shot"
                        + " at the end of the path."),
        NPC("NPC…", Glyph.CARD, "", true, "An NPC of the client. Choose it, then press in the world where it stands."),
        CHARACTER("Character…", Glyph.NPC, "", false, "A player character of the race you choose."),
        AIRSHIP(
                "Airship",
                Glyph.CLOUD,
                "",
                false,
                "The airship of the scenes. Press in the world under the place where it flies."),
        SKILL(
                "Skill on a place…",
                Glyph.PLACE,
                "",
                true,
                "A skill at a place you press in the world. An invisible NPC of the client casts it there on itself."),
        EFFECT("Effect on a place…", Glyph.SPARKLES, "", true, "An effect class at a place you press in the world."),
        MUSIC("Music…", Glyph.AUDIO, "", true, "A track of the music folder. It plays until the next track."),
        VOICE("Voice…", Glyph.AUDIO, "", true, "A voice line, in the client's own language. Voice lines overlap."),
        SOUND("Sound…", Glyph.WAVES, "", true, "A sound of a package, such as AmbSound3.airship_arrive_02."),
        FADE(
                "Fade",
                Glyph.SUN,
                "",
                false,
                "To a colour and back, in three times. The client leaves a new fade out while one runs."),
        LINE("Line", Glyph.PENCIL, "", false, "A line of text, of its own or of the client's table."),
        SLIDE(
                "Slide…",
                Glyph.LAYERS,
                "",
                true,
                "A picture of LineageSlideImage. It stands on black, over the world, until the slides end."),
        POST("Post effect…", Glyph.SLIDERS, "", true, "A row of PostEffectData, such as a motion blur."),
        END_SLIDES(
                "End slides", Glyph.CLOSE, "", false, "Closes the slides at the playhead, and the world shows again.");

        private final String label;
        private final Glyph glyph;
        private final String key;
        private final boolean list;
        private final String tip;

        Row(String label, Glyph glyph, String key, boolean list, String tip) {
            this.label = label;
            this.glyph = glyph;
            this.key = key;
            this.list = list;
            this.tip = tip;
        }

        String getLabel() {
            return label;
        }

        String getTip() {
            return tip;
        }

        boolean asks() {
            return list || this == CHARACTER || isQuickStart();
        }

        boolean isQuickStart() {
            return this == ORBIT || this == FLY_TO || this == REVEAL;
        }

        boolean startsGroup() {
            return this == ORBIT || this == NPC || this == MUSIC || this == FADE;
        }

        String getTitle() {
            return switch (this) {
                case NPC -> "ADD NPC";
                case SKILL -> "ADD SKILL ON A PLACE";
                case EFFECT -> "ADD EFFECT ON A PLACE";
                case MUSIC -> "ADD MUSIC";
                case VOICE -> "ADD VOICE";
                case SOUND -> "ADD SOUND";
                case SLIDE -> "ADD SLIDE";
                case POST -> "ADD POST EFFECT";
                case ORBIT -> "ORBIT";
                case FLY_TO -> "FLY TO";
                case REVEAL -> "REVEAL";
                default -> "ADD";
            };
        }
    }

    record Offer(
            @Nullable String noCut,
            @Nullable String noQuickStart,
            Names names,
            Media media,
            @Nullable Model model,
            boolean modelRead,
            boolean aimsAtPoint,
            boolean slidesOpen) {}

    DirectorAdd(DirectorActors actors, DirectorMedia media) {
        this.actors = actors;
        this.media = media;
    }

    static @Nullable String off(Row row, Offer offer) {
        var media = offer.media();
        var reading = media == Media.NONE;
        return switch (row) {
            case KEY, AIRSHIP, FADE, LINE -> null;
            case SHOT -> offer.noCut();
            case ORBIT, FLY_TO, REVEAL -> offer.noQuickStart();
            case NPC -> offer.names().read() ? null : "Reading the client's NPCs.";
            case CHARACTER -> offer.modelRead() ? null : READING;
            case SKILL ->
                !offer.aimsAtPoint()
                        ? "This client has no invisible NPC in its scenes to cast it."
                        : offer.names().shows().isEmpty() ? "Reading the client's skills." : null;
            case EFFECT -> offer.names().effects().isEmpty() ? "Reading the client's effects." : null;
            case MUSIC -> media.tracks().isEmpty() ? reading ? READING : "This client has no music." : null;
            case VOICE ->
                !SceneFit.holdsVoices(offer.model())
                        ? "This client has no field for voice lines."
                        : media.voices().isEmpty() ? reading ? READING : "This client has no voice lines." : null;
            case SOUND -> media.sounds().isEmpty() ? reading ? READING : "This client has no sounds." : null;
            case SLIDE ->
                !SceneFit.holdsSlides(offer.model())
                        ? "This client has no field for slides."
                        : media.slides().isEmpty() ? reading ? READING : "This client has no slides." : null;
            case POST ->
                !SceneFit.holdsPostEffects(offer.model())
                        ? "This client has no field for post effects."
                        : media.posts().isEmpty()
                                ? reading ? READING : "This client has no rows of PostEffectData."
                                : null;
            case END_SLIDES ->
                !SceneFit.holdsSlides(offer.model())
                        ? "This client has no field for slides."
                        : offer.slidesOpen() ? null : "No slides stand open at the playhead.";
        };
    }

    @Nullable
    Row getPending() {
        return pending;
    }

    boolean takeEscape(DirectorSession open) {
        var taken = pending != null || escapedAt == ImGui.getFrameCount();
        close(open);
        return taken;
    }

    void drawButton(
            DirectorSession open,
            DirectorCatalog catalog,
            @Nullable Model model,
            boolean modelRead,
            float width,
            float height) {
        forgetOtherScene(open);
        buttonLeft = ImGui.getCursorScreenPosX();
        buttonTop = ImGui.getCursorScreenPosY();
        if (HudWidgets.button(
                "director/add", Glyph.PLUS, width < LABEL_WIDTH ? "" : "Add", width, height, false, true)) {
            ImGui.openPopup(MENU);
        }
        GeodataWidgets.tip("Adds a key, an actor, a sound or what shows over the world, at the playhead.");
        var rules = 4;
        var tall = HudWidgets.menuHeight(Row.values().length, rules);
        if (!HudWidgets.beginMenuAt(MENU, MENU_WIDTH, tall, buttonLeft, buttonTop - tall - GeodataWidgets.ROW)) {
            rows.clear();
            return;
        }
        if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
            ImGui.closeCurrentPopup();
            escapedAt = ImGui.getFrameCount();
        }
        var offer = new Offer(
                open.getNoCut(),
                open.getNoQuickStart(),
                catalog.getNames(),
                catalog.getMedia(),
                model,
                modelRead,
                open.canAimAtPoint(),
                open.areSlidesOpen());
        for (var row : Row.values()) {
            if (row.startsGroup()) {
                HudWidgets.menuRule();
            }
            var off = off(row, offer);
            rows.put(
                    row.getLabel(),
                    new float[] {ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY(), off == null ? 1 : 0});
            if (HudWidgets.menuRow("director-add/" + row.name(), row.glyph, row.getLabel(), row.key, off == null)) {
                choose(open, row);
            }
            GeodataWidgets.tip(off == null ? row.tip : off);
        }
        HudWidgets.endMenu();
    }

    void drawPending(DirectorSession open, DirectorCatalog catalog, @Nullable Model model) {
        listTop = 0;
        cancelTop = 0;
        forgetOtherScene(open);
        var placing = open.getPlacing();
        var y = ImGui.getCursorScreenPosY();
        var hidden = y < ImGui.getWindowPosY() || y + IN_SIGHT > ImGui.getWindowPosY() + ImGui.getWindowHeight();
        if (hidden && (placing != null && !placed || pending != null && opening)) {
            ImGui.setScrollHereY(0);
        }
        placed = placing != null;
        if (placing != null) {
            if (!placing.point()) {
                close(open);
            }
            actors.drawPlacing(open, placing);
            return;
        }
        var row = pending;
        if (row == null) {
            return;
        }
        var escaped = ImGui.isKeyPressed(ImGuiKey.Escape, false);
        if (row.isQuickStart()) {
            listTop = ImGui.getCursorScreenPosY();
            opening = false;
            if (quick.draw(open, row)) {
                close(open);
            }
        } else if (row == Row.CHARACTER) {
            listTop = ImGui.getCursorScreenPosY();
            drawCharacter(open, catalog, model);
        } else {
            GeodataWidgets.section(row.getTitle());
            space(GeodataWidgets.ROW);
            listTop = ImGui.getCursorScreenPosY();
            var width = ImGui.getContentRegionAvailX();
            if (opening) {
                GeodataWidgets.openSearchList(listId(row));
            }
            opening = false;
            if (drawList(open, catalog, row, width)) {
                pending = null;
                return;
            }
            space(GeodataWidgets.ROW);
            cancelTop = ImGui.getCursorScreenPosY();
            if (GeodataWidgets.rowButton("Cancel", 0, 1)) {
                pending = null;
            }
            GeodataWidgets.tip("Goes back with nothing added.", "Esc");
        }
        if (escaped && pending != null) {
            close(open);
            escapedAt = ImGui.getFrameCount();
        }
    }

    void ask(DirectorSession open, Row row) {
        choose(open, row);
    }

    private boolean drawList(DirectorSession open, DirectorCatalog catalog, Row row, float width) {
        var names = catalog.getNames();
        var clips = catalog.getMedia();
        var id = listId(row);
        switch (row) {
            case NPC -> {
                var npc = actors.drawNpcList(id, "Choose an NPC", GeodataWidgets.MUTED, names, width);
                GeodataWidgets.tip(
                        "Choose an NPC, then press in the world where it stands. A drag from there sets the way it"
                                + " faces.");
                if (npc != null) {
                    open.place(SceneOrders.spawn(npc.id(), 0, 0, 0, 0, 0), npc.name(), 0);
                }
                return npc != null;
            }
            case SKILL -> {
                var cast = actors.showList(id, "Choose a skill", GeodataWidgets.MUTED, names, true, width);
                GeodataWidgets.tip(
                        "A skill by its name, then a press in the world gives its place. A drag sets the way its"
                                + " caster faces.");
                if (cast != null) {
                    open.placeSkill(cast.id(), cast.name());
                }
                return cast != null;
            }
            case EFFECT -> {
                var effect = actors.drawEffectList(id, "Choose an effect", GeodataWidgets.MUTED, names, width);
                GeodataWidgets.tip("An effect class, then a press in the world gives its place.");
                if (effect != null) {
                    open.place(SceneOrders.effectAt(effect, 0, 0, 0), effect, 0);
                }
                return effect != null;
            }
            case MUSIC, VOICE, SOUND -> {
                var kind = row == Row.MUSIC ? Kind.TRACK : row == Row.VOICE ? Kind.VOICE : Kind.SOUND;
                var clip = media.clipList(
                        kind,
                        id,
                        row == Row.MUSIC
                                ? "Choose a track"
                                : row == Row.VOICE ? "Choose a voice line" : "Choose a sound",
                        GeodataWidgets.MUTED,
                        clips,
                        width);
                GeodataWidgets.tip(row.tip);
                if (clip != null) {
                    open.addMusic(
                            switch (kind) {
                                case TRACK -> SoundSpans.track(clip);
                                case VOICE -> SoundSpans.voice(clip);
                                case SOUND -> SoundSpans.sound(clip);
                            });
                }
                return clip != null;
            }
            case SLIDE -> {
                var slide = media.drawSlideList(id, "Choose a slide", GeodataWidgets.MUTED, clips, width);
                GeodataWidgets.tip(row.tip);
                if (slide != null) {
                    open.addSlide(slide.name(), slide.partWidth(), slide.partHeight());
                }
                return slide != null;
            }
            case POST -> {
                var post = media.postList(id, "Choose a row", GeodataWidgets.MUTED, clips, width);
                GeodataWidgets.tip(row.tip);
                if (post != null) {
                    open.addScreen(SceneScreens.postEffect(post.id()));
                }
                return post != null;
            }
            default -> {
                return true;
            }
        }
    }

    private void drawCharacter(DirectorSession open, DirectorCatalog catalog, @Nullable Model model) {
        opening = false;
        var ready = actors.drawForm(catalog, model);
        space(GeodataWidgets.ROW);
        cancelTop = ImGui.getCursorScreenPosY();
        ImGui.beginDisabled(!ready);
        if (GeodataWidgets.rowButton("Place this character", 0, 2) && ready) {
            pending = null;
            actors.placeCharacter(open);
        }
        ImGui.endDisabled();
        GeodataWidgets.tip("Then press in the world where it stands. A drag from there sets the way it faces.");
        if (GeodataWidgets.rowButton("Cancel", 1, 2)) {
            pending = null;
        }
        GeodataWidgets.tip("Goes back with nothing added.", "Esc");
    }

    private void choose(DirectorSession open, Row row) {
        if (open.getPlacing() != null) {
            open.cancelPlacing();
        }
        close(open);
        if (row.asks()) {
            pending = row;
            opening = true;
            if (row.isQuickStart()) {
                quick.begin(open);
            }
            return;
        }
        switch (row) {
            case KEY -> open.setKey();
            case SHOT -> open.cut();
            case AIRSHIP -> open.place(SceneOrders.airship(0, 0, 0, 0, 0), "the airship", DirectorActors.AIRSHIP_LIFT);
            case FADE -> open.addScreen(SceneScreens.fade());
            case LINE -> open.addScreen(SceneScreens.line(DirectorMedia.NEW_LINE));
            case END_SLIDES -> open.addScreen(SceneScreens.slideEnd());
            default -> {
            }
        }
    }

    private void forgetOtherScene(DirectorSession open) {
        if (open.getOpened() != of) {
            of = open.getOpened();
            close(open);
        }
    }

    private void close(DirectorSession open) {
        if (pending != null && pending.isQuickStart()) {
            quick.end(open);
        }
        pending = null;
    }

    private static String listId(Row row) {
        return "##director-add-" + row.name();
    }
}
