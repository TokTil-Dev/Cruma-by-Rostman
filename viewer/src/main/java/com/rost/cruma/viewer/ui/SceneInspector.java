package com.rost.cruma.viewer.ui;

import static com.rost.cruma.viewer.ui.GeodataWidgets.space;

import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Model;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.SceneFields;
import com.rost.cruma.core.world.SceneFit;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.Speeds;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.viewer.ui.DirectorCatalog.Row;
import com.rost.cruma.viewer.ui.DirectorCatalog.Slide;
import com.rost.cruma.viewer.ui.GeodataWorkspace.Viewport;
import imgui.ImGui;
import imgui.flag.ImGuiSelectableFlags;
import imgui.type.ImString;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class SceneInspector {
    private static final float MOST_PLACE = 2_000_000;
    private static final int MOST_WHOLE = 2_000_000;
    private static final float MOST_SECONDS = 3_600;
    private static final int MOST_LEVEL = 4_095;
    private static final float MOST_COLOR = 255;
    private static final float LEAST_SPEED = 1;
    private static final float MOST_SPEED = 20_000;
    private static final double MILLIS = 1_000;
    private static final int TEXT_ROOM = 256;
    private static final String[] CAMERA_MODES = {"Special view", "Fixed camera", "Free camera"};
    private static final String[] SCREEN_KINDS = {
        "None", "Fade", "Line", "Slide start", "Slide change", "Slide move", "Slide end", "Post effect"
    };
    private static final String[] FADE_WAITS = {"None", "Animation mark", "Spawned pawn"};
    private static final String[] WINDOWS = {
        "Window 1 · Upper center",
        "Window 2 · Upper right",
        "Window 3 · Middle left",
        "Window 4 · Middle center",
        "Window 5 · Middle right",
        "Window 6 · Lower left",
        "Window 7 · Lower center",
        "Window 8 · Lower right",
        "Window 9 · Bottom center"
    };
    private static final float SCREEN_WIDTH = 1920;
    private static final float SCREEN_HEIGHT = 1080;
    private static final float LINE_WIDTH = 440;
    private static final float LINE_HEIGHT = 70;
    private static final float[][] WINDOW_PLACES = windowPlaces();
    private static final String COLOR_POPUP = "##scene-fade-colour";
    private static final String[] DIRECTIONS = {"Alpha", "Left", "Right", "Up", "Down"};
    private static final int[] LINE_SPEEDS = {0, 1, 11, 12, 13};
    private static final String[] LINE_SPEED_NAMES = {"At once", "25 a tick", "15 a tick", "25 a tick, 12", "35 a tick"
    };
    private static final String PLACE_NAME = "A place";
    private static final String NOBODY = "Nobody";
    private static final String PLAIN_MOVE = "Its walk or run";
    private static final String[] CLIP_NAMES = {"Track", "Voice line", "Sound"};
    private static final float PICTURE_SHAPE = 3f / 4;

    private final float[] number = new float[1];
    private final int[] whole = new int[1];
    private final Map<String, ImString> texts = new HashMap<>();
    private final Set<String> flipped = new HashSet<>();
    private @Nullable String textHeld;
    private @Nullable DirectorActors actors;
    private @Nullable DirectorMedia media;
    private @Nullable DirectorCatalog catalog;
    private final Map<String, float[]> places = new HashMap<>();
    private float listTop;
    private float targetTop;
    private float watchesTop;
    private float fixTop;
    private float placesLeft;
    private float placesTop;
    private float placesWidth;
    private float swatchLeft;
    private float swatchTop;

    private enum Field {
        INDEX,
        TARGET,
        NPC,
        PLACE,
        YAW,
        SKILL,
        ANIMATION,
        FLY,
        RUN,
        LOOP
    }

    void setLists(DirectorActors next, DirectorMedia sounds, DirectorCatalog names) {
        actors = next;
        media = sounds;
        catalog = names;
    }

    void draw(DirectorSession open, Key key, @Nullable Model model) {
        var document = open.getDocument();
        if (document == null) {
            return;
        }
        var item = document.items().get(key.item());
        switch (key.part()) {
            case SHOT -> drawShot(open, key, item, model);
            case SCREEN -> drawScreen(open, key, item, model);
            case MUSIC -> drawMusic(open, key, item, model);
            case ORDER -> drawOrder(open, key, item, model);
        }
    }

    private void drawShot(DirectorSession open, Key key, Item item, @Nullable Model model) {
        var shot = item.camera();
        if (shot == null) {
            return;
        }
        var has = fields(model == null ? null : model.camera());
        if (section("SHOT", "shot", false, String.format(Locale.ROOT, "%.2f s", item.seconds()))) {
            var baked = open.getBaked();
            var named = baked == null ? null : baked.getName(shot.target());
            row(() -> time(open, item));
            if (has.test("UseCamera")) {
                list(
                        open,
                        "Camera",
                        "##scene-camera",
                        shot.mode(),
                        CAMERA_MODES,
                        value -> shot(open, key, shot, "mode", value),
                        "What the shot does to the camera. Only Special view moves it.");
            }
            row(
                    () -> whole(
                            open,
                            named == null ? "Target" : "Target · " + named,
                            "##scene-target",
                            shot.target(),
                            0,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "target", value),
                            "The Index of the actor the camera looks at. Nothing stands at Index 0."),
                    () -> decimal(
                            open,
                            "Distance",
                            "##scene-dist",
                            shot.distance(),
                            1,
                            -MOST_PLACE,
                            MOST_PLACE,
                            "%.0f",
                            value -> shot(open, key, shot, "distance", value),
                            "Dist: how far the camera stands, from 220 units in front of the middle of its target."));
            row(
                    () -> whole(
                            open,
                            "Yaw",
                            "##scene-yaw",
                            shot.yaw(),
                            -MOST_WHOLE,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "yaw", value),
                            "Around the target, in degrees, as the scene stores it."),
                    () -> whole(
                            open,
                            "Pitch",
                            "##scene-pitch",
                            shot.pitch(),
                            -MOST_WHOLE,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "pitch", value),
                            "Above 0 the camera stands above the target."));
            row(
                    () -> seconds(
                            open,
                            "Time",
                            "##scene-move",
                            shot.moveSeconds(),
                            value -> shot(open, key, shot, "moveSeconds", value),
                            "How long it takes to come to these numbers."),
                    () -> seconds(
                            open,
                            "Duration",
                            "##scene-hold",
                            shot.holdSeconds(),
                            value -> shot(open, key, shot, "holdSeconds", value),
                            "How long the target holds the camera."));
            if (has.test("CameraFov")) {
                row(() -> decimal(
                        open,
                        "Field of view",
                        "##scene-fov",
                        shot.fov(),
                        .25f,
                        0,
                        170,
                        "%.1f°",
                        value -> shot(open, key, shot, "fov", value),
                        "0 keeps what the camera has."));
            }
            flag(
                    open,
                    "Bars",
                    shot.wide(),
                    value -> shot(open, key, shot, "wide", value),
                    "IsWide: the black bars above and below, until the next item.");
            flag(
                    open,
                    "Turn with the target",
                    shot.relative(),
                    value -> shot(open, key, shot, "relative", value),
                    "IsRelAngle: Yaw and Pitch turn as the target turns.");
            flag(
                    open,
                    "No flight",
                    shot.immediate(),
                    value -> shot(open, key, shot, "immediate", value),
                    "IsImmTarget: the camera does not fly to a new target.");
        }
        if (section("VIEW", "view", true, "")) {
            row(
                    () -> whole(
                            open,
                            "Yaw",
                            "##scene-relyaw",
                            shot.relYaw(),
                            -MOST_WHOLE,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "relYaw", value),
                            "RelYaw: turns the view away from the target, in degrees."),
                    () -> whole(
                            open,
                            "Pitch",
                            "##scene-relpitch",
                            shot.relPitch(),
                            -MOST_WHOLE,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "relPitch", value),
                            "RelPitch"),
                    () -> whole(
                            open,
                            "Roll",
                            "##scene-relroll",
                            shot.relRoll(),
                            -MOST_WHOLE,
                            MOST_WHOLE,
                            value -> shot(open, key, shot, "relRoll", value),
                            "RelRoll: the only roll of the camera."));
        }
    }

    private void drawScreen(DirectorSession open, Key key, Item item, @Nullable Model model) {
        var screen = item.screen();
        if (screen == null) {
            return;
        }
        var has = fields(model == null ? null : model.screen());
        if (section("SCREEN", "screen", false, String.format(Locale.ROOT, "%.2f s", item.seconds()))) {
            row(() -> time(open, item));
            var kinds = SceneFit.holdsSlides(model)
                    ? SCREEN_KINDS
                    : List.of(SCREEN_KINDS).subList(0, 3).toArray(String[]::new);
            list(
                    open,
                    "Kind",
                    "##scene-kind",
                    screen.kind().ordinal(),
                    SCREEN_KINDS[screen.kind().ordinal()],
                    kinds,
                    value -> screen(open, key, screen, "kind", ScreenKind.values()[value]),
                    "What the item shows.");
            drawLost(new Item(item.seconds(), "", null, List.of(), screen, null), model);
            switch (screen.kind()) {
                case FADE -> drawFade(open, key, screen, has);
                case MESSAGE -> drawLine(open, key, screen);
                case SLIDE_CHANGE, SLIDE_MOVE -> drawSlide(open, key, screen);
                case POST_EFFECT -> drawPost(open, key, screen);
                case SLIDE_START, SLIDE_END, NONE -> {
                }
            }
            drawWarnings(open, key);
            if (open.getChosenKey() == null || !open.getChosenKey().equals(key)) {
                return;
            }
        }
        if (section("MORE", "screen-more", true, "")) {
            row(
                    () -> whole(
                            open,
                            "Font",
                            "##scene-font",
                            screen.font(),
                            0,
                            MOST_WHOLE,
                            value -> screen(open, key, screen, "font", value),
                            "FontType, which no scene changes."),
                    () -> whole(
                            open,
                            "Background",
                            "##scene-background",
                            screen.background(),
                            0,
                            MOST_WHOLE,
                            value -> screen(open, key, screen, "background", value),
                            "BackgroundType, which no scene changes."));
        }
    }

    private void drawFade(DirectorSession open, Key key, Screen screen, Predicate<String> has) {
        row(
                () -> seconds(
                        open,
                        "To the colour",
                        "##scene-fadeout",
                        screen.fadeOut(),
                        value -> screen(open, key, screen, "fadeOut", value),
                        "FadeOutDuration."),
                () -> seconds(
                        open,
                        "Hold",
                        "##scene-hold",
                        screen.hold(),
                        value -> screen(open, key, screen, "hold", value),
                        "BlackOutDuration."),
                () -> seconds(
                        open,
                        "Back",
                        "##scene-fadein",
                        screen.fadeIn(),
                        value -> screen(open, key, screen, "fadeIn", value),
                        "FadeInDuration."));
        row(() -> drawColor(open, key, screen));
        if (has.test("DelayedType")) {
            list(
                    open,
                    "Waits for",
                    "##scene-delayed",
                    screen.delayed(),
                    FADE_WAITS,
                    value -> screen(open, key, screen, "delayed", value),
                    "DelayedType: the colour stays after its hold until a mark of an animation takes the camera.");
        }
    }

    private void drawColor(DirectorSession open, Key key, Screen screen) {
        var color = screen.color();
        var rgb = new float[] {
            (color >> 16 & 0xFF) / MOST_COLOR, (color >> 8 & 0xFF) / MOST_COLOR, (color & 0xFF) / MOST_COLOR
        };
        swatchLeft = ImGui.getCursorScreenPosX();
        swatchTop = ImGui.getCursorScreenPosY();
        if (GeodataWidgets.compactSwatch(
                "Colour", "##scene-fade-swatch", rgb, ImGui.calcItemWidth(), GeodataWidgets.MUTED)) {
            ImGui.openPopup(COLOR_POPUP);
        }
        GeodataWidgets.tip("FadeColor: the colour the screen goes to.");
        var left = GeodataWidgets.colorPopup(COLOR_POPUP, rgb);
        var next = Math.round(rgb[0] * MOST_COLOR) << 16
                | Math.round(rgb[1] * MOST_COLOR) << 8
                | Math.round(rgb[2] * MOST_COLOR);
        if (next != color) {
            screen(open, key, screen, "color", next);
        }
        if (left) {
            open.settle();
        }
    }

    private void drawLine(DirectorSession open, Key key, Screen screen) {
        var lines = catalog.getMedia();
        var own = screen.message() < 1;
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Line");
        listTop = ImGui.getCursorScreenPosY();
        var known = lines.line(screen.message());
        var missing = !own && known == null && !lines.lines().isEmpty();
        var preview = own
                ? "A line of its own"
                : known == null ? "Line " + screen.message() + (missing ? " · missing" : "") : known;
        var picked = media.drawLineList(
                "##scene-line-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                lines,
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip(
                "MsgNo: a line of SystemMsg, in the language of this client. Under 1 the client shows the text of the"
                        + " scene.");
        if (picked != null && picked.id() != screen.message()) {
            screen(open, key, screen, "message", picked.id());
            open.settle();
        }
        if (own) {
            text(
                    "Text",
                    "##scene-text",
                    screen.text(),
                    value -> {
                        screen(open, key, screen, "text", value);
                        open.settle();
                    },
                    "MsgText: the line this item shows. A # starts a new row.");
        } else if (known != null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(known, GeodataWidgets.MUTED);
        }
        drawWindows(open, key, screen);
        var speed = 0;
        for (var index = 0; index < LINE_SPEEDS.length; index++) {
            speed = LINE_SPEEDS[index] == screen.animation() ? index : speed;
        }
        list(
                open,
                "Comes and goes",
                "##scene-animation",
                speed,
                LINE_SPEED_NAMES,
                value -> screen(open, key, screen, "animation", LINE_SPEEDS[value]),
                "AnimationType: how fast the line comes and goes.");
        row(() -> seconds(
                open,
                "Hold",
                "##scene-linehold",
                screen.hold(),
                value -> screen(open, key, screen, "hold", value),
                "BlackOutDuration: how long the line stays."));
    }

    private void drawWindows(DirectorSession open, Key key, Screen screen) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Window");
        placesLeft = ImGui.getCursorScreenPosX();
        placesTop = ImGui.getCursorScreenPosY();
        placesWidth = ImGui.getContentRegionAvailX();
        var picked = GeodataWidgets.screenPlaces(
                "##scene-windows", WINDOW_PLACES, WINDOWS, screen.window() - 1, placesWidth);
        if (picked >= 0 && picked + 1 != screen.window()) {
            screen(open, key, screen, "window", picked + 1);
            open.settle();
        }
    }

    private void drawSlide(DirectorSession open, Key key, Screen screen) {
        var pictures = catalog.getMedia();
        var slide = pictures.slide(screen.slide());
        var missing =
                !screen.slide().isEmpty() && slide == null && !pictures.slides().isEmpty();
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Picture");
        if (slide != null) {
            drawPicture(slide);
        }
        listTop = ImGui.getCursorScreenPosY();
        var preview = screen.slide().isEmpty()
                ? "Choose a picture"
                : screen.slide().substring(screen.slide().lastIndexOf('.') + 1) + (missing ? " · missing" : "");
        var picked = media.drawSlideList(
                "##scene-slide-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                pictures,
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip(
                missing
                        ? "SlideTexName: this client has no such picture, so the slide shows black."
                        : "SlideTexName, a picture of LineageSlideImage.");
        if (picked != null && !picked.name().equals(screen.slide())) {
            var next = SceneFields.SCREEN.with(
                    SceneFields.SCREEN.with(screen, "slide", picked.name()), "slideWidth", picked.partWidth());
            open.editScreen(key.item(), SceneFields.SCREEN.with(next, "slideHeight", picked.partHeight()));
            open.settle();
        }
        list(
                open,
                "Direction",
                "##scene-direction",
                screen.slideDirection(),
                DIRECTIONS,
                value -> screen(open, key, screen, "slideDirection", value),
                "SlideDirection. A Change takes Alpha.");
        row(() -> seconds(
                open,
                "Time",
                "##scene-sliding",
                screen.slideSeconds(),
                value -> screen(open, key, screen, "slideSeconds", value),
                "SlidingTime."));
        row(
                () -> whole(
                        open,
                        "Width",
                        "##scene-slideu",
                        screen.slideWidth(),
                        0,
                        MOST_WHOLE,
                        value -> screen(open, key, screen, "slideWidth", value),
                        "SlideTexUSize: the part of the texture in use."),
                () -> whole(
                        open,
                        "Height",
                        "##scene-slidev",
                        screen.slideHeight(),
                        0,
                        MOST_WHOLE,
                        value -> screen(open, key, screen, "slideHeight", value),
                        "SlideTexVSize."));
        row(
                () -> whole(
                        open,
                        "Move",
                        "##scene-slidemove",
                        screen.slideMove(),
                        -MOST_WHOLE,
                        MOST_WHOLE,
                        value -> screen(open, key, screen, "slideMove", value),
                        "SlideMoveRatio, in percent of the window."),
                () -> whole(
                        open,
                        "Size",
                        "##scene-slidewnd",
                        screen.slideWindow(),
                        0,
                        MOST_WHOLE,
                        value -> screen(open, key, screen, "slideWindow", value),
                        "SlideWndRatio, in percent of the window."));
    }

    private void drawPicture(Slide slide) {
        var width = ImGui.getContentRegionAvailX();
        var height = Math.round(width * PICTURE_SHAPE);
        var texture = media.getThumbnails().getTexture(slide.name(), slide.partWidth(), slide.partHeight());
        var x = ImGui.getCursorScreenPosX();
        var y = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(x, y, x + width, y + height, GeodataWidgets.CARD, 6f);
        if (texture != 0) {
            var shape = (float) slide.partHeight() / slide.partWidth();
            var shown = Math.min(width, height / shape);
            var left = x + (width - shown) * .5f;
            var top = y + (height - shown * shape) * .5f;
            draw.addImage(texture, left, top, left + shown, top + shown * shape);
        }
        ImGui.dummy(width, height);
    }

    private void drawPost(DirectorSession open, Key key, Screen screen) {
        var rows = catalog.getMedia();
        var row = rows.table().getEffect(screen.postEffect());
        var missing = row == null && !rows.posts().isEmpty();
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Post effect");
        listTop = ImGui.getCursorScreenPosY();
        var preview = row == null
                ? "Row " + screen.postEffect() + (missing ? " · missing" : "")
                : DirectorMedia.postName(row);
        var picked = media.postList(
                "##scene-post-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                rows,
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip("PostEffectID, a row of PostEffectData.");
        if (picked != null && picked.id() != screen.postEffect()) {
            screen(open, key, screen, "postEffect", picked.id());
            open.settle();
        }
        if (row != null && !DirectorMedia.playsPost(row)) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "The Viewer does not play this kind of row, as no scene of a client names one.",
                    GeodataWidgets.MUTED);
        }
    }

    private void drawWarnings(DirectorSession open, Key key) {
        var warnings = SceneOrders.of(open.getWarnings(), key);
        fixTop = 0;
        for (var index = 0; index < warnings.size(); index++) {
            fixTop = DirectorActors.drawWarning(open, warnings.get(index), index, null);
        }
    }

    private void drawMusic(DirectorSession open, Key key, Item item, @Nullable Model model) {
        var cue = item.music();
        if (cue == null) {
            return;
        }
        var has = fields(model == null ? null : model.music());
        if (!section("MUSIC", "music", false, String.format(Locale.ROOT, "%.2f s", item.seconds()))) {
            return;
        }
        row(() -> time(open, item));
        drawClip(open, key, cue);
        drawLost(new Item(item.seconds(), "", null, List.of(), null, cue), model);
        if (has.test("Volume")) {
            row(() -> whole(
                    open,
                    "Volume",
                    "##scene-volume",
                    cue.level(),
                    0,
                    MOST_LEVEL,
                    value -> level(open, key, cue, value),
                    "From 0 to 255. The client takes 0 as full."));
        }
        row(
                () -> seconds(
                        open,
                        "Fade in",
                        "##scene-musicin",
                        cue.fadeSeconds(),
                        value -> music(open, key, cue, "fadeSeconds", value),
                        "FadeInTime."),
                () -> seconds(
                        open,
                        "Fade out",
                        "##scene-musicout",
                        cue.fadeOutSeconds(),
                        value -> music(open, key, cue, "fadeOutSeconds", value),
                        "FadeOutTime, which the viewer does not read."));
        flag(
                open,
                "Stop the map's music",
                cue.stopMusic(),
                value -> music(open, key, cue, "stopMusic", value),
                "StopMapMusic.");
        if (has.test("StopMapAmbient")) {
            places.put("##scene-stop-ambient", new float[] {ImGui.getCursorScreenPosX(), ImGui.getCursorScreenPosY()});
            flag(
                    open,
                    "Stop the map's ambient sounds",
                    cue.stopAmbient(),
                    value -> music(open, key, cue, "stopAmbient", value),
                    "StopMapAmbient: until the scene ends.");
        }
        if (has.test("StopMapSoundFx")) {
            flag(
                    open,
                    "Stop the sounds of the map's effects",
                    cue.stopEffects(),
                    value -> music(open, key, cue, "stopEffects", value),
                    "StopMapSoundFx: until the scene ends.");
        }
        flag(open, "A sound of a package", cue.sound(), value -> music(open, key, cue, "sound", value), "IsSound.");
        if (has.test("IsVoice")) {
            flag(open, "A voice line", cue.voice(), value -> music(open, key, cue, "voice", value), "IsVoice.");
        }
    }

    private void drawClip(DirectorSession open, Key key, Music cue) {
        var kind = SoundSpans.kind(cue);
        var clips = catalog.getMedia();
        var missing = !cue.name().isEmpty() && !clips.has(kind, cue.name());
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, CLIP_NAMES[kind.ordinal()]);
        listTop = ImGui.getCursorScreenPosY();
        var preview = cue.name().isEmpty() ? "Nothing" : cue.name() + (missing ? " · missing" : "");
        var picked = media.clipList(
                kind,
                "##scene-clip-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                clips,
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip("MusicName: what plays here. An item with no name only stops the map's music.");
        if (picked != null && !picked.equals(cue.name())) {
            music(open, key, cue, "name", picked);
            open.settle();
        }
        var span = open.findSoundSpan(key);
        if (span != null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(SceneTracks.clipTip(span) + ".", GeodataWidgets.MUTED);
        }
    }

    private void drawOrder(DirectorSession open, Key key, Item item, @Nullable Model model) {
        var order = item.actors().get(key.order());
        var has = fields(
                model == null || order.player() && model.pc().isEmpty()
                        ? null
                        : order.player() ? model.pc() : model.npc());
        var title = SceneTracks.orderName(order.action()).toUpperCase(Locale.ROOT);
        var shown = EnumSet.noneOf(Field.class);
        if (section(title, "order", false, String.format(Locale.ROOT, "%.2f s", item.seconds()))) {
            row(() -> time(open, item));
            shown = drawMain(open, key, order, has);
            var warnings = SceneOrders.of(open.getWarnings(), key);
            fixTop = 0;
            for (var index = 0; index < warnings.size(); index++) {
                var named =
                        rowOf(catalog.getNames().skills(), warnings.get(index).skill());
                fixTop = DirectorActors.drawWarning(
                        open, warnings.get(index), index, named == null ? null : named.name());
            }
            if (open.getChosenKey() == null || !open.getChosenKey().equals(key)) {
                return;
            }
        }
        if (shown.isEmpty()) {
            drawRaw(open, key, order, has, shown);
        } else if (section("MORE", "order-more", true, "")) {
            drawRaw(open, key, order, has, shown);
        }
        if (order.player() && section("CHARACTER", "character", false, "")) {
            drawCharacter(open, key, order, has);
        }
    }

    private EnumSet<Field> drawMain(DirectorSession open, Key key, Actor order, Predicate<String> has) {
        var shown = EnumSet.noneOf(Field.class);
        switch (order.action()) {
            case SPAWN -> {
                if (!order.player()) {
                    drawNpc(open, key, order);
                    shown.add(Field.NPC);
                }
                drawPlace(open, key, order);
                drawYaw(open, key, order);
                drawWatches(open, key, order);
                shown.addAll(EnumSet.of(Field.PLACE, Field.YAW));
                if (!order.player() && has.test("IsFly")) {
                    drawFly(open, key, order);
                    shown.add(Field.FLY);
                }
            }
            case AIRSHIP_SPAWN -> {
                drawPlace(open, key, order);
                drawYaw(open, key, order);
                shown.addAll(EnumSet.of(Field.PLACE, Field.YAW));
            }
            case MOVE, AIRSHIP_MOVE -> {
                drawPlace(open, key, order);
                shown.add(Field.PLACE);
                if (order.action() == Action.MOVE) {
                    flag(
                            open,
                            "Run",
                            order.run(),
                            value -> order(open, key, order, "run", value),
                            "IsRun: plays the run and not the walk, and goes at the speed of a run, of the NPC or 180"
                                    + " and not 80 units a second in Cruma, unless Speed says more.");
                    shown.add(Field.RUN);
                    drawMoveSequence(open, key, order);
                }
                row(() -> drawSpeed(open, key, order));
                drawWalk(open, key);
            }
            case ANIMATION -> {
                drawSequence(open, key, order);
                drawLoop(open, key, order);
                shown.addAll(EnumSet.of(Field.ANIMATION, Field.LOOP));
            }
            case SKILL -> {
                drawSkill(open, key, order);
                drawTarget(open, key, order);
                shown.addAll(EnumSet.of(Field.SKILL, Field.TARGET));
            }
            case EFFECT -> {
                drawEffect(open, key, order);
                drawOn(open, key, order);
                if (order.index() == 0) {
                    drawPlace(open, key, order);
                    shown.add(Field.PLACE);
                }
                drawLoop(open, key, order);
                shown.addAll(EnumSet.of(Field.ANIMATION, Field.INDEX, Field.LOOP));
            }
            case DESPAWN -> shown.add(Field.INDEX);
            default -> {
            }
        }
        return shown;
    }

    private void drawNpc(DirectorSession open, Key key, Actor order) {
        var name = open.getNpcName(order.npc());
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "NPC");
        listTop = ImGui.getCursorScreenPosY();
        var picked = actors.drawNpcList(
                "##scene-npc-list",
                name == null ? "NPC " + order.npc() : name,
                GeodataWidgets.TEXT,
                catalog.getNames(),
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip("NpcID: the NPC that this order spawns.");
        if (picked != null && picked.id() != order.npc()) {
            order(open, key, order, "npc", picked.id());
            open.settle();
        }
    }

    private void drawSequence(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Sequence");
        listTop = ImGui.getCursorScreenPosY();
        var sequences = open.getSequences(order.index());
        var missing = !order.animation().isEmpty() && !isHeld(sequences, order.animation());
        var preview = order.animation().isEmpty()
                ? "Choose a sequence"
                : named(open, order, order.animation()) + (missing ? " · missing" : "");
        var picked = actors.drawSequenceList(
                "##scene-sequence-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                sequences,
                ImGui.getContentRegionAvailX(),
                null,
                name -> open.getSequenceSeconds(order.index(), name));
        GeodataWidgets.tip(
                sequences == null
                        ? "The list comes with the first run of the scene, as the model is read for it."
                        : "AnimName: a sequence of the model. A name it does not have leaves it waiting. A death comes"
                                + " with what holds the body down, at its end.");
        if (picked != null && !picked.equals(order.animation())) {
            open.setSequence(key, picked);
        }
    }

    private void drawMoveSequence(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Sequence");
        listTop = ImGui.getCursorScreenPosY();
        var sequences = open.getSequences(order.index());
        var own = order.moveSequence();
        var missing = !own.isEmpty() && !isHeld(sequences, own);
        var preview = own.isEmpty() ? PLAIN_MOVE : named(open, order, own) + (missing ? " · missing" : "");
        var picked = actors.drawSequenceList(
                "##scene-move-sequence-list",
                preview,
                missing ? GeodataWidgets.WARNING : GeodataWidgets.TEXT,
                sequences,
                ImGui.getContentRegionAvailX(),
                PLAIN_MOVE,
                name -> open.getSequenceSeconds(order.index(), name));
        GeodataWidgets.tip(
                sequences == null
                        ? "The list comes with the first run of the scene, as the model is read for it."
                        : "Plays in place of the walk or run while the actor goes, such as air_glide. For a body in"
                                + " the air, turn on Fly on its spawn: it then goes to the height of this move too. The"
                                + " client has no place for it.");
        if (picked != null && !picked.equals(own)) {
            order(open, key, order, "moveSequence", picked);
            open.settle();
        }
    }

    private void drawSkill(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Skill");
        listTop = ImGui.getCursorScreenPosY();
        var named = rowOf(catalog.getNames().skills(), order.skill());
        var picked = actors.drawSkillList(
                "##scene-skill-list",
                named == null ? "Skill " + order.skill() : named.name(),
                GeodataWidgets.TEXT,
                catalog.getNames(),
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip(
                "SkillID. The list has the skills that have level 1, as the client plays no other in a scene.");
        if (picked != null && picked.id() != order.skill()) {
            order(open, key, order, "skill", picked.id());
            open.settle();
        }
    }

    private void drawTarget(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Target");
        targetTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        var shown = order.target() == 0 ? NOBODY : open.getActorName(order.target());
        if (HudWidgets.beginCombo("##scene-target-list", shown, 0)) {
            for (var actor : open.getCast()) {
                if (ImGui.selectable(
                                actor.name() + "##" + actor.index(),
                                actor.index() == order.target(),
                                ImGuiSelectableFlags.None)
                        && actor.index() != order.target()) {
                    order(open, key, order, "target", actor.index());
                    open.settle();
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip("TargetIndex: the actor it uses the skill on.");
    }

    private void drawEffect(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Effect");
        listTop = ImGui.getCursorScreenPosY();
        var preview = order.animation().isEmpty() ? "Choose an effect" : order.animation();
        var picked = actors.drawEffectList(
                "##scene-effect-list",
                preview,
                GeodataWidgets.TEXT,
                catalog.getNames(),
                ImGui.getContentRegionAvailX());
        GeodataWidgets.tip("AnimName: an effect class.");
        if (picked != null && !picked.equals(order.animation())) {
            order(open, key, order, "animation", picked);
            open.settle();
        }
    }

    private void drawOn(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "On");
        targetTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        if (HudWidgets.beginCombo(
                "##scene-effect-on", order.index() == 0 ? PLACE_NAME : open.getActorName(order.index()), 0)) {
            if (ImGui.selectable(PLACE_NAME, order.index() == 0, ImGuiSelectableFlags.None) && order.index() != 0) {
                var run = open.getBaked();
                var at = run == null
                        ? null
                        : run.place(
                                order.index(),
                                open.getDocument().items().get(key.item()).seconds());
                var moved = at == null ? order : SceneOrders.at(order, at.x(), at.y(), at.z());
                open.editOrder(key.item(), key.order(), SceneFields.ACTOR.with(moved, "index", 0));
                open.settle();
            }
            for (var actor : open.getCast()) {
                if (ImGui.selectable(
                                actor.name() + "##" + actor.index(),
                                actor.index() == order.index(),
                                ImGuiSelectableFlags.None)
                        && actor.index() != order.index()) {
                    order(open, key, order, "index", actor.index());
                    open.settle();
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "What it starts on: an actor, whom it follows, or a place. Nobody has seen in a game what the client"
                        + " does with a place.");
    }

    private void drawWalk(DirectorSession open, Key key) {
        var walk = SceneOrders.walk(open.getWalks(), key);
        if (walk == null) {
            return;
        }
        space(GeodataWidgets.ROW);
        GeodataWidgets.note(SceneTracks.walkTip(walk) + ".", GeodataWidgets.MUTED);
        if (walk.stop() > open.length()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note("The scene ends before it arrives.", GeodataWidgets.MUTED);
        } else if (walk.cut()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    String.format(Locale.ROOT, "Another order ends it at %.2f s.", walk.stop()), GeodataWidgets.MUTED);
        }
    }

    private void drawSpeed(DirectorSession open, Key key, Actor order) {
        var walk = SceneOrders.walk(open.getWalks(), key);
        var shown = (float) (walk == null ? SceneOrders.speed(order, 0, Speeds.NONE) : walk.speed());
        number[0] = shown;
        var left = GeodataWidgets.compactRange(
                "Speed",
                "##scene-speed",
                number,
                ImGui.calcItemWidth(),
                LEAST_SPEED,
                MOST_SPEED,
                "%.0f u/s",
                GeodataWidgets.MUTED);
        places.put("##scene-speed", new float[] {ImGui.getItemRectMinX(), ImGui.getItemRectMaxY()});
        if (number[0] != shown) {
            order(open, key, order, "speed", Math.max(0, (double) number[0]));
        }
        if (left) {
            var now = open.order(key);
            if (now.speed() != Math.rint(now.speed())) {
                order(open, key, now, "speed", Math.rint(now.speed()));
            }
            open.settle();
        }
        GeodataWidgets.tip(
                "Units a second in the Viewer, in a video and in Blender. A man is 50 units tall and a dragon 1500"
                    + " wide. Drag, or double-click to type: 0 goes back to the speed of the actor, and the line under"
                    + " Speed says where that comes from. The client has no place for a speed of a move.");
    }

    private void drawPlace(DirectorSession open, Key key, Actor order) {
        row(
                () -> decimal(
                        open,
                        "X",
                        "##scene-x",
                        order.x(),
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> order(open, key, order, "x", value),
                        "Location."),
                () -> decimal(
                        open,
                        "Y",
                        "##scene-y",
                        order.y(),
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> order(open, key, order, "y", value),
                        "Location."),
                () -> decimal(
                        open,
                        "Z",
                        "##scene-z",
                        order.z(),
                        1,
                        -MOST_PLACE,
                        MOST_PLACE,
                        "%.1f",
                        value -> order(open, key, order, "z", value),
                        "Location."));
    }

    private void drawYaw(DirectorSession open, Key key, Actor order) {
        row(() -> whole(
                open,
                "Yaw",
                "##scene-orderyaw",
                order.yaw(),
                -MOST_WHOLE,
                MOST_WHOLE,
                value -> order(open, key, order, "yaw", value),
                "Which way it faces, in Unreal units of 65536 a turn."));
    }

    private void drawWatches(DirectorSession open, Key key, Actor order) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, "Watches");
        watchesTop = ImGui.getCursorScreenPosY();
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        var shown = order.watches() == 0 ? NOBODY : open.getActorName(order.watches());
        if (HudWidgets.beginCombo("##scene-watches-list", shown, 0)) {
            if (ImGui.selectable(NOBODY, order.watches() == 0, ImGuiSelectableFlags.None) && order.watches() != 0) {
                order(open, key, order, "watches", 0);
                open.settle();
            }
            for (var actor : open.getCast()) {
                if (actor.index() != order.index()
                        && ImGui.selectable(
                                actor.name() + "##" + actor.index(),
                                actor.index() == order.watches(),
                                ImGuiSelectableFlags.None)
                        && actor.index() != order.watches()) {
                    order(open, key, order, "watches", actor.index());
                    open.settle();
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(
                "The actor it keeps turned to while it stands, also when that actor walks. A walk still faces its way,"
                        + " and Yaw counts while the other is not in the scene. The client has no place for it, so it"
                        + " stays in the scene file: the Viewer, a video and Blender obey it.");
        if (order.watches() != 0 && SceneOrders.entry(open.getDocument().items(), order.watches()) == null) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(
                    "No actor has Index " + order.watches() + ", so it faces its Yaw.", GeodataWidgets.WARNING);
        }
    }

    private void drawRaw(DirectorSession open, Key key, Actor order, Predicate<String> has, Set<Field> shown) {
        if (!shown.contains(Field.INDEX) && !shown.contains(Field.TARGET)) {
            row(
                    () -> whole(
                            open,
                            "Index",
                            "##scene-index",
                            order.index(),
                            0,
                            MOST_WHOLE,
                            value -> order(open, key, order, "index", value),
                            "Names the actor in later orders."),
                    () -> whole(
                            open,
                            "Target",
                            "##scene-ordertarget",
                            order.target(),
                            0,
                            MOST_WHOLE,
                            value -> order(open, key, order, "target", value),
                            "TargetIndex: the actor it uses a skill on or walks to."));
        } else if (!shown.contains(Field.INDEX)) {
            row(() -> whole(
                    open,
                    "Index",
                    "##scene-index",
                    order.index(),
                    0,
                    MOST_WHOLE,
                    value -> order(open, key, order, "index", value),
                    "Names the actor in later orders."));
        }
        if (!order.player() && !shown.contains(Field.NPC)) {
            row(() -> whole(
                    open,
                    "NPC",
                    "##scene-npc",
                    order.npc(),
                    0,
                    MOST_WHOLE,
                    value -> order(open, key, order, "npc", value),
                    "NpcID: the NPC that an order spawns."));
        }
        if (!shown.contains(Field.PLACE)) {
            drawPlace(open, key, order);
        }
        if (!shown.contains(Field.YAW)) {
            drawYaw(open, key, order);
        }
        if (!shown.contains(Field.SKILL)) {
            row(() -> whole(
                    open,
                    "Skill",
                    "##scene-skill",
                    order.skill(),
                    0,
                    MOST_WHOLE,
                    value -> order(open, key, order, "skill", value),
                    "SkillID."));
        }
        var reads = switch (order.action()) {
            case ANIMATION, EFFECT, DOOR, OTHER -> true;
            default -> false;
        };
        if (!shown.contains(Field.ANIMATION) && (reads || !order.animation().isEmpty())) {
            text(
                    "Animation",
                    "##scene-anim",
                    order.animation(),
                    value -> {
                        order(open, key, order, "animation", value);
                        open.settle();
                    },
                    reads
                            ? "AnimName: a sequence of the model, or an effect class."
                            : "AnimName. An order of this kind does not read it, in the client or here: the name was"
                                    + " left behind in the scene. An empty one is not shown.");
        }
        if (has.test("IsFly") && !shown.contains(Field.FLY)) {
            drawFly(open, key, order);
        }
        if (!shown.contains(Field.RUN)) {
            flag(open, "Run", order.run(), value -> order(open, key, order, "run", value), "IsRun.");
        }
        if (!shown.contains(Field.LOOP)) {
            drawLoop(open, key, order);
        }
        if (has.test("ImmInsight")) {
            flag(
                    open,
                    "Immediate insight",
                    order.immediate(),
                    value -> order(open, key, order, "immediate", value),
                    "ImmInsight, which the viewer does not read.");
        }
        text(
                "Note",
                "##scene-note",
                order.description(),
                value -> {
                    order(open, key, order, "description", value);
                    open.settle();
                },
                "Description.");
    }

    private void drawCharacter(DirectorSession open, Key key, Actor order, Predicate<String> has) {
        row(
                () -> whole(
                        open,
                        "Race",
                        "##scene-race",
                        order.race(),
                        0,
                        MOST_WHOLE,
                        value -> order(open, key, order, "race", value),
                        has.test("ItemSlot") && !has.test("IsMage")
                                ? "Race. 6 and 7 are the human and the orc mystic."
                                : "Race."),
                () -> whole(
                        open,
                        "Sex",
                        "##scene-sex",
                        order.sex(),
                        0,
                        MOST_WHOLE,
                        value -> order(open, key, order, "sex", value),
                        "Sex."));
        row(
                () -> whole(
                        open,
                        "Set",
                        "##scene-setitem",
                        order.setItem(),
                        0,
                        MOST_WHOLE,
                        value -> order(open, key, order, "setItem", value),
                        "SetItemNo."),
                () -> whole(
                        open,
                        "Set kind",
                        "##scene-settype",
                        order.setItemType(),
                        0,
                        MOST_WHOLE,
                        value -> order(open, key, order, "setItemType", value),
                        "SetItemType."));
        if (has.test("IsMage")) {
            flag(open, "Mage", order.mage(), value -> order(open, key, order, "mage", value), "IsMage.");
        }
        if (has.test("IsSelfPawn")) {
            flag(
                    open,
                    "The player's copy",
                    order.self(),
                    value -> order(open, key, order, "self", value),
                    "IsSelfPawn.");
        }
        flag(open, "Soulshot", order.spirit(), value -> order(open, key, order, "spirit", value), "UseSpirit.");
        for (var index = 0; index < order.visuals().size(); index++) {
            var at = index;
            var worn = order.visuals().get(index);
            row(
                    () -> whole(
                            open,
                            "Slot",
                            "##scene-visual-slot" + at,
                            worn.slot(),
                            0,
                            MOST_WHOLE,
                            value -> visual(open, key, order, at, new Visual(value, worn.item())),
                            "VisualItemType: 1 gloves, 2 chest, 3 legs, 4 feet, 5 back, 6 and 7 hair, 8 right hand, 9"
                                    + " left hand."),
                    () -> whole(
                            open,
                            "Item",
                            "##scene-visual-item" + at,
                            worn.item(),
                            0,
                            MOST_WHOLE,
                            value -> visual(open, key, order, at, new Visual(worn.slot(), value)),
                            "VisualItemID."));
        }
        if (!order.itemSlots().isEmpty()
                && section(
                        "ITEM SLOTS",
                        "slots",
                        true,
                        Integer.toString(order.itemSlots().size()))) {
            for (var first = 0; first < order.itemSlots().size(); first += 3) {
                var cells = new ArrayList<Runnable>(3);
                for (var index = first;
                        index < Math.min(first + 3, order.itemSlots().size());
                        index++) {
                    var at = index;
                    cells.add(() -> whole(
                            open,
                            "Slot " + at,
                            "##scene-slot" + at,
                            order.itemSlots().get(at),
                            -1,
                            MOST_WHOLE,
                            value -> slot(open, key, order, at, value),
                            "ItemSlot: the id of an item. The client draws slots 20 to 28: gloves, chest,"
                                    + " legs, feet, back, two hair accessories, right hand, left hand. -1 keeps"
                                    + " what Set gives."));
                }
                row(cells.toArray(Runnable[]::new));
            }
        }
    }

    private boolean section(String title, String id, boolean closedAtFirst, String note) {
        space(GeodataWidgets.ROW);
        var open = flipped.contains(id) == closedAtFirst;
        if (SpawnPanelWidgets.sectionRow("##scene-section-" + id, open, title, note)) {
            if (!flipped.remove(id)) {
                flipped.add(id);
            }
            open = !open;
        }
        return open;
    }

    private void time(DirectorSession open, Item item) {
        decimal(
                open,
                "Moment",
                "##scene-time",
                item.seconds(),
                .01f,
                0,
                MOST_SECONDS,
                "%.2f s",
                value -> open.moveKeyTo(open.onFrame(value)),
                "When the item runs. A key that leaves an item goes into the item at the new moment, or into a new one"
                        + " there.");
    }

    private void seconds(
            DirectorSession open, String label, String id, double value, DoubleConsumer changed, String tip) {
        decimal(
                open,
                label,
                id,
                value,
                .01f,
                0,
                MOST_SECONDS,
                "%.2f s",
                seconds -> changed.accept(Math.round(seconds * MILLIS) / MILLIS),
                tip);
    }

    private void decimal(
            DirectorSession open,
            String label,
            String id,
            double value,
            float speed,
            float min,
            float max,
            String format,
            DoubleConsumer changed,
            String tip) {
        number[0] = (float) value;
        var left = GeodataWidgets.compactDrag(
                label, id, number, ImGui.calcItemWidth(), speed, min, max, format, GeodataWidgets.MUTED);
        places.put(id, new float[] {ImGui.getItemRectMinX(), ImGui.getItemRectMaxY()});
        if (number[0] != (float) value) {
            changed.accept(number[0]);
        }
        if (left) {
            open.settle();
        }
        GeodataWidgets.tip(tip);
    }

    private void whole(
            DirectorSession open,
            String label,
            String id,
            int value,
            int min,
            int max,
            IntConsumer changed,
            String tip) {
        whole[0] = value;
        var width = ImGui.calcItemWidth();
        var left = GeodataWidgets.compactDrag(
                HudWidgets.truncateToWidth(label, width), id, whole, width, .25f, min, max, "%d", GeodataWidgets.MUTED);
        places.put(id, new float[] {ImGui.getItemRectMinX(), ImGui.getItemRectMaxY()});
        if (whole[0] != value) {
            changed.accept(whole[0]);
        }
        if (left) {
            open.settle();
        }
        GeodataWidgets.tip(tip);
    }

    private void text(String label, String id, String value, Consumer<String> changed, String tip) {
        space(GeodataWidgets.ROW);
        var text = texts.computeIfAbsent(id, _ -> new ImString(TEXT_ROOM));
        if (!id.equals(textHeld)) {
            text.set(value);
        }
        var left = GeodataWidgets.compactText(label, id, "", text, ImGui.getContentRegionAvailX());
        places.put(id, new float[] {ImGui.getItemRectMinX(), ImGui.getItemRectMaxY()});
        if (ImGui.isItemActive()) {
            textHeld = id;
        } else if (id.equals(textHeld)) {
            textHeld = null;
        }
        if (left && !text.get().equals(value)) {
            changed.accept(text.get());
        }
        GeodataWidgets.tip(tip);
    }

    private static float[][] windowPlaces() {
        var places = new float[WINDOWS.length][];
        var free = new Viewport(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        for (var index = 0; index < places.length; index++) {
            var place = GameSceneStage.linePlace(index + 1, free, SCREEN_WIDTH, SCREEN_HEIGHT, LINE_WIDTH, LINE_HEIGHT);
            places[index] = new float[] {
                place[0] / SCREEN_WIDTH,
                place[1] / SCREEN_HEIGHT,
                LINE_WIDTH / SCREEN_WIDTH,
                LINE_HEIGHT / SCREEN_HEIGHT
            };
        }
        return places;
    }

    private static void drawLost(Item item, @Nullable Model model) {
        var lost = SceneFit.lost(List.of(item), model);
        if (!lost.isEmpty()) {
            space(GeodataWidgets.ROW);
            GeodataWidgets.note(DirectorPanel.fitNote(lost), GeodataWidgets.WARNING);
        }
    }

    private static String named(DirectorSession open, Actor order, String sequence) {
        var length = DirectorActors.length(open.getSequenceSeconds(order.index(), sequence));
        return length.isEmpty() ? sequence : sequence + " · " + length;
    }

    private static boolean isHeld(@Nullable List<String> sequences, String name) {
        if (sequences == null) {
            return true;
        }
        for (var sequence : sequences) {
            if (sequence.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable Row rowOf(List<Row> rows, int id) {
        for (var row : rows) {
            if (row.id() == id) {
                return row;
            }
        }
        return null;
    }

    private static void drawFly(DirectorSession open, Key key, Actor order) {
        flag(
                open,
                "Fly",
                order.fly(),
                value -> order(open, key, order, "fly", value),
                "IsFly: a spawn stays at its height and not on the ground.");
    }

    private static void drawLoop(DirectorSession open, Key key, Actor order) {
        flag(open, "Loop", order.loop(), value -> order(open, key, order, "loop", value), "IsLoop.");
    }

    private static void shot(DirectorSession open, Key key, Camera shot, String field, Object value) {
        open.editShot(key.item(), SceneFields.CAMERA.with(shot, field, value));
    }

    private static void screen(DirectorSession open, Key key, Screen screen, String field, Object value) {
        open.editScreen(key.item(), SceneFields.SCREEN.with(screen, field, value));
    }

    private static void music(DirectorSession open, Key key, Music cue, String field, Object value) {
        open.editMusic(key.item(), SceneFields.MUSIC.with(cue, field, value));
    }

    private static void level(DirectorSession open, Key key, Music cue, int level) {
        open.editMusic(
                key.item(),
                SceneFields.MUSIC.with(SceneFields.MUSIC.with(cue, "level", level), "volume", Music.volumeOf(level)));
    }

    private static void order(DirectorSession open, Key key, Actor order, String field, Object value) {
        open.editOrder(key.item(), key.order(), SceneFields.ACTOR.with(order, field, value));
    }

    private static void visual(DirectorSession open, Key key, Actor order, int index, Visual visual) {
        var visuals = new ArrayList<>(order.visuals());
        visuals.set(index, visual);
        order(open, key, order, "visuals", visuals);
    }

    private static void slot(DirectorSession open, Key key, Actor order, int index, int value) {
        var slots = new ArrayList<>(order.itemSlots());
        slots.set(index, value);
        order(open, key, order, "itemSlots", slots);
    }

    private static Predicate<String> fields(@Nullable Set<String> names) {
        return names == null ? _ -> true : names::contains;
    }

    private static void row(Runnable... cells) {
        space(GeodataWidgets.ROW);
        var spacing = ImGui.getStyle().getItemSpacingX();
        var count = Math.max(2, cells.length);
        var fields = new BooleanSupplier[count];
        for (var index = 0; index < count; index++) {
            var cell = index < cells.length ? cells[index] : null;
            fields[index] = () -> {
                if (cell != null) {
                    cell.run();
                }
                return false;
            };
        }
        ImGui.pushItemWidth((ImGui.getContentRegionAvailX() - (count - 1) * spacing) / count);
        GeodataWidgets.columns(fields);
        ImGui.popItemWidth();
    }

    private static void flag(DirectorSession open, String label, boolean value, Consumer<Boolean> changed, String tip) {
        var next = HudWidgets.switchRow(label, value, true, tip);
        if (next != value) {
            changed.accept(next);
            open.settle();
        }
    }

    private static void list(
            DirectorSession open, String label, String id, int value, String[] names, IntConsumer changed, String tip) {
        var shown = value >= 0 && value < names.length ? names[value] : Integer.toString(value);
        list(open, label, id, value, shown, names, changed, tip);
    }

    private static void list(
            DirectorSession open,
            String label,
            String id,
            int value,
            String shown,
            String[] names,
            IntConsumer changed,
            String tip) {
        space(GeodataWidgets.ROW);
        ImGui.textColored(GeodataWidgets.MUTED, label);
        ImGui.setNextItemWidth(ImGui.getContentRegionAvailX());
        if (HudWidgets.beginCombo(id, shown, 0)) {
            for (var index = 0; index < names.length; index++) {
                if (ImGui.selectable(names[index], index == value, ImGuiSelectableFlags.None) && index != value) {
                    changed.accept(index);
                    open.settle();
                }
            }
            ImGui.endCombo();
        }
        GeodataWidgets.tip(tip);
    }
}
