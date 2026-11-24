package com.rost.cruma.viewer.ui.aside;

import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.launch.StartPlaces;
import com.rost.cruma.viewer.ui.CardWidgets;
import com.rost.cruma.viewer.ui.FontManager;
import com.rost.cruma.viewer.ui.GeodataWidgets;
import com.rost.cruma.viewer.ui.HudWidgets;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Library;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Phase;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Theme;
import com.rost.cruma.viewer.ui.aside.MenuMusic.Track;
import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

public final class RadioAside implements StartAside {
    private static final float PAD = CardWidgets.PAD;
    private static final int TITLE_SIZE = 20;
    private static final float DISC_MOST = 190;
    private static final float ROW_HEIGHT = 26;
    private static final float PLAY_SIZE = HudWidgets.ROUND_LARGE;
    private static final float TURN_SPEED = 1.1f;
    private static final int DISC = ImColor.rgb(13, 18, 23);
    private static final int GROOVE = ImColor.rgba(255, 255, 255, 12);
    private static final int SHINE = ImColor.rgba(255, 255, 255, 16);
    private static final int LABEL = ImColor.rgba(92, 219, 241, 52);

    private @Nullable MenuMusic music;
    private float turn;

    private @Nullable Object settingsSeen;
    private StartPlaces places = new StartPlaces(List.of());

    private final MapFollower follower = new MapFollower();
    private @Nullable Track scrolledTo;

    private final Map<String, float[]> placesDrawn = new TreeMap<>();
    private String shownState = "";
    private String timeTip = "";

    @Override
    public String getLabel() {
        return "Radio";
    }

    public void setMusic(MenuMusic shared) {
        music = shared;
    }

    @Override
    public boolean isAnimating() {
        return music != null && music.isPlaying();
    }

    @Override
    public void draw(Context context, float width, float height) {
        placesDrawn.clear();
        var left = ImGui.getCursorScreenPosX();
        var client = context.client();
        var shared = music;
        if (shared == null) {
            shownState = "no music";
            return;
        }
        var playing = shared.getCurrent();
        if (playing != null
                && shared.getPhase() != Phase.WAITING
                && (client == null || !playing.file().startsWith(client.root()))) {
            shared.stop();
        }
        if (client == null) {
            shared.tick(List.of());
            shownState = "no client";
            CardWidgets.emptyCard(
                    placesDrawn,
                    left,
                    width,
                    "No client chosen",
                    "Choose a client in the list at the left to hear its music.",
                    null,
                    "radio.empty");
            return;
        }
        follow(context.loader());
        var library = shared.getLibrary(client);
        if (library.loading()) {
            shared.tick(List.of());
            shownState = "loading";
            CardWidgets.emptyCard(placesDrawn, left, width, "Reading the music folder…", "", null, "radio.empty");
            return;
        }
        if (library.tracks().isEmpty()) {
            shared.tick(List.of());
            shownState = "no tracks";
            CardWidgets.emptyCard(
                    placesDrawn, left, width, "No music", library.problem(), client.root(), "radio.empty");
            return;
        }
        var tracks = library.tracks();
        var choice = choiceTile(context.tiles());
        var choiceTheme = choice == null ? null : shared.getTheme(client, choice, places.findPlacesIn(choice));
        showFirst(shared, tracks, choiceTheme);
        follower.follow(shared, client, context.pointed(), choice, places, System.nanoTime());
        shared.tick(tracks);
        if (shared.isPlaying()) {
            turn += ImGui.getIO().getDeltaTime() * TURN_SPEED;
        }
        shownState = shared.getPhase().name().toLowerCase(java.util.Locale.ROOT);
        drawPanel(context.fonts(), shared, library, shared.hasThemes(client), left, width, height);
    }

    @Override
    public void close() {
        if (music != null) {
            music.close();
        }
        follower.reset();
        placesDrawn.clear();
    }

    private void drawPanel(
            FontManager fonts,
            MenuMusic shared,
            Library library,
            boolean themes,
            float left,
            float width,
            float height) {
        var top = ImGui.getCursorScreenPosY();
        var draw = ImGui.getWindowDrawList();
        draw.addRectFilled(left, top, left + width, top + height, HudWidgets.BACKGROUND, HudWidgets.PANEL_RADIUS);
        draw.addRect(left, top, left + width, top + height, GeodataWidgets.DIVIDER, HudWidgets.PANEL_RADIUS, 0, 1);
        placesDrawn.put("radio", new float[] {left, top, left + width, top + height});
        var inner = width - 2 * PAD;
        var x = left + PAD;
        var y = top + PAD - 2;
        draw.addText(x, y, GeodataWidgets.MUTED, "RADIO");
        var summary = summary(library.tracks());
        draw.addText(x + inner - ImGui.calcTextSizeX(summary), y, GeodataWidgets.FAINT, summary);
        y += ImGui.getFontSize() + 16;
        var current = shared.getCurrent();
        var size = Math.min(DISC_MOST, inner * .5f);
        drawDisc(draw, left + width * .5f, y + size * .5f, size * .5f, shared.isPlaying());
        placesDrawn.put(
                "radio.disc", new float[] {left + (width - size) * .5f, y, left + (width + size) * .5f, y + size});
        y += size + 16;
        var title = current == null ? "" : HudWidgets.truncateToWidth(current.name(), inner);
        var font = fonts.getHeadingFont(TITLE_SIZE);
        if (font != null) {
            ImGui.pushFont(font, TITLE_SIZE);
            title = HudWidgets.truncateToWidth(current == null ? "" : current.name(), inner);
            var titleWidth = ImGui.calcTextSizeX(title);
            ImGui.popFont();
            draw.addText(font, TITLE_SIZE, left + (width - titleWidth) * .5f, y, HudWidgets.TEXT, title);
        } else {
            draw.addText(left + (width - ImGui.calcTextSizeX(title)) * .5f, y, HudWidgets.TEXT, title);
        }
        y += TITLE_SIZE + 8;
        var line = HudWidgets.truncateToWidth(lineOf(shared, current), inner);
        var lineColor = shared.getPhase() == Phase.FAILED || shared.getPhase() == Phase.NO_OUTPUT
                ? GeodataWidgets.WARNING
                : GeodataWidgets.MUTED;
        draw.addText(left + (width - ImGui.calcTextSizeX(line)) * .5f, y, lineColor, line);
        y += ImGui.getFontSize() + 18;
        drawTime(draw, shared, current, x, y, inner);
        y += 30;
        drawControls(shared, library.tracks(), left + width * .5f, y);
        y += PLAY_SIZE + 14;
        if (themes) {
            if (switchRow(draw, "Follow the map", shared.isFollowing(), x, y, inner)) {
                shared.setFollowing(!shared.isFollowing());
                follower.reset();
            }
            placesDrawn.put("radio.follow", new float[] {x, y, x + inner, y + 30});
            y += 30 + 10;
        }
        draw.addText(x, y, GeodataWidgets.MUTED, "TRACKS");
        y += ImGui.getFontSize() + 8;
        drawList(shared, library.tracks(), x, y, inner, top + height - PAD - y);
    }

    private void drawDisc(ImDrawList draw, float centerX, float centerY, float radius, boolean playing) {
        draw.addCircleFilled(centerX, centerY, radius, DISC, 64);
        draw.addCircle(centerX, centerY, radius, GeodataWidgets.DIVIDER, 64, 1);
        for (var groove = radius - 6; groove > radius * .42f; groove -= 5) {
            draw.addCircle(centerX, centerY, groove, GROOVE, 64, 1);
        }
        for (var side = 0; side < 2; side++) {
            var start = turn + side * (float) Math.PI;
            draw.pathArcTo(centerX, centerY, radius * .72f, start, start + .5f, 16);
            draw.pathStroke(SHINE, 0, radius * .5f);
        }
        var label = radius * .36f;
        draw.addCircleFilled(centerX, centerY, label, LABEL, 48);
        draw.addCircle(centerX, centerY, label, playing ? HudWidgets.ACCENT : GeodataWidgets.MUTED, 48, 1.5f);
        var markX = centerX + (float) Math.cos(turn) * label * .6f;
        var markY = centerY + (float) Math.sin(turn) * label * .6f;
        draw.addCircleFilled(markX, markY, 3, playing ? HudWidgets.ACCENT : GeodataWidgets.MUTED, 12);
        draw.addCircleFilled(centerX, centerY, 4, HudWidgets.BACKGROUND, 12);
    }

    private void drawTime(ImDrawList draw, MenuMusic shared, @Nullable Track current, float x, float y, float inner) {
        var length = current == null ? Double.NaN : current.seconds();
        var position = shared.getPosition();
        var start = MenuMusic.clock(position);
        var end = MenuMusic.clock(length);
        var textTop = y + (20 - ImGui.getFontSize()) * .5f;
        draw.addText(x, textTop, GeodataWidgets.MUTED, start);
        draw.addText(x + inner - ImGui.calcTextSizeX(end), textTop, GeodataWidgets.MUTED, end);
        var barLeft = x + ImGui.calcTextSizeX("00:00") + 12;
        var barRight = x + inner - ImGui.calcTextSizeX("00:00") - 12;
        var barY = y + 10;
        draw.addRectFilled(barLeft, barY - 2, barRight, barY + 2, GeodataWidgets.DIVIDER, 2f);
        var share = Double.isNaN(length) || length <= 0 ? 0 : (float) Math.clamp(position / length, 0, 1);
        if (share > 0) {
            draw.addRectFilled(
                    barLeft, barY - 2, barLeft + (barRight - barLeft) * share, barY + 2, HudWidgets.ACCENT, 2f);
        }
        draw.addCircleFilled(barLeft + (barRight - barLeft) * share, barY, 5, HudWidgets.ACCENT, 12);
        ImGui.setCursorScreenPos(barLeft - 6, y);
        timeTip = shared.getPhase() == Phase.PLAYING
                ? "Click to play from that point."
                : "Click to choose where the track starts when you press play.";
        if (ImGui.invisibleButton("##radio-time", barRight - barLeft + 12, 20) && !Double.isNaN(length)) {
            var at = Math.clamp((ImGui.getIO().getMousePosX() - barLeft) / (barRight - barLeft), 0, 1);
            shared.seek(at * length);
        }
        if (ImGui.isItemHovered() && !Double.isNaN(length)) {
            HudWidgets.showTooltip(timeTip);
        }
        placesDrawn.put("radio.time", new float[] {barLeft, y, barRight, y + 20});
    }

    private void drawControls(MenuMusic shared, List<Track> tracks, float centerX, float y) {
        var side = HudWidgets.CONTROL_HEIGHT;
        var gap = 22f;
        var playLeft = centerX - PLAY_SIZE * .5f;
        ImGui.setCursorScreenPos(playLeft - gap - side, y + (PLAY_SIZE - side) * .5f);
        if (HudWidgets.iconButton("radio-previous", Glyph.STEP_BACK, false, HudWidgets.TEXT)) {
            shared.setFollowing(false);
            shared.step(tracks, -1);
        }
        GeodataWidgets.tip("The track before this one.");
        placesDrawn.put("radio.previous", rectOfItem());
        ImGui.setCursorScreenPos(playLeft, y);
        var playing = shared.getPhase() == Phase.PLAYING || shared.getPhase() == Phase.STARTING;
        if (HudWidgets.roundButton(
                "radio-play", playing ? Glyph.PAUSE : Glyph.PLAY, true, playing ? "Pause" : "Play")) {
            shared.toggle();
        }
        placesDrawn.put("radio.play", new float[] {playLeft, y, playLeft + PLAY_SIZE, y + PLAY_SIZE});
        ImGui.setCursorScreenPos(playLeft + PLAY_SIZE + gap, y + (PLAY_SIZE - side) * .5f);
        if (HudWidgets.iconButton("radio-next", Glyph.STEP, false, HudWidgets.TEXT)) {
            shared.setFollowing(false);
            shared.step(tracks, 1);
        }
        GeodataWidgets.tip("The track after this one.");
        placesDrawn.put("radio.next", rectOfItem());
    }

    private void drawList(MenuMusic shared, List<Track> tracks, float x, float y, float inner, float room) {
        ImGui.setCursorScreenPos(x, y);
        var listHeight = Math.max(ROW_HEIGHT, room);
        ImGui.beginChild("##radio-tracks", inner, listHeight, 0, ImGuiWindowFlags.NoBackground);
        var current = shared.getCurrent();
        var draw = ImGui.getWindowDrawList();
        for (var index = 0; index < tracks.size(); index++) {
            var track = tracks.get(index);
            var rowX = ImGui.getCursorScreenPosX();
            var rowY = ImGui.getCursorScreenPosY();
            var rowWidth = ImGui.getContentRegionAvailX();
            var chosen = current != null && current.file().equals(track.file());
            if (ImGui.invisibleButton("##track" + index, rowWidth, ROW_HEIGHT)) {
                shared.setFollowing(false);
                shared.play(track, null);
            }
            var hovered = ImGui.isItemHovered();
            if (chosen || hovered) {
                draw.addRectFilled(rowX, rowY, rowX + rowWidth, rowY + ROW_HEIGHT, HudWidgets.ROW_HOVER, 4f);
            }
            if (chosen) {
                draw.addRectFilled(rowX, rowY + 3, rowX + 2, rowY + ROW_HEIGHT - 3, HudWidgets.ACCENT);
                if (scrolledTo != track) {
                    ImGui.setScrollHereY(.3f);
                    scrolledTo = track;
                }
            }
            var textY = rowY + (ROW_HEIGHT - ImGui.getFontSize()) * .5f;
            var note = chosen ? noteOf(shared.getPhase()) : MenuMusic.clock(track.seconds());
            var noteWidth = ImGui.calcTextSizeX(note);
            draw.addText(
                    rowX + 10,
                    textY,
                    HudWidgets.TEXT,
                    HudWidgets.truncateToWidth(track.name(), rowWidth - noteWidth - 30));
            draw.addText(
                    rowX + rowWidth - 10 - noteWidth, textY, chosen ? HudWidgets.ACCENT : GeodataWidgets.FAINT, note);
            if (hovered) {
                HudWidgets.showTooltip(track.file().getFileName() + " · click to play");
            }
            if (ImGui.isRectVisible(rowX, rowY, rowX + rowWidth, rowY + ROW_HEIGHT)) {
                placesDrawn.put(
                        "radio.track/" + track.stem(), new float[] {rowX, rowY, rowX + rowWidth, rowY + ROW_HEIGHT});
            }
        }
        ImGui.endChild();
        placesDrawn.put("radio.tracks", new float[] {x, y, x + inner, y + listHeight});
    }

    private void follow(ConfigLoader loader) {
        var settings = loader.getEffectiveSettings();
        if (settings == settingsSeen) {
            return;
        }
        settingsSeen = settings;
        try {
            places = new StartPlaces(loader.load().viewer().camera().locations());
        } catch (RuntimeException unreadable) {
            places = new StartPlaces(List.of());
        }
    }

    private @Nullable String choiceTile(List<String> tiles) {
        if (tiles.isEmpty()) {
            return null;
        }
        var naming = places.findNamingPlace(tiles);
        return naming == null ? tiles.getFirst() : StartPlaces.tileOf(naming);
    }

    private static void showFirst(MenuMusic shared, List<Track> tracks, @Nullable Theme choiceTheme) {
        var current = shared.getCurrent();
        var ours = current != null
                && tracks.stream().anyMatch(track -> track.file().equals(current.file()));
        if (!ours) {
            if (current != null && shared.getPhase() != Phase.WAITING) {
                shared.replace(tracks.getFirst());
            }
            shared.show(tracks.getFirst(), null);
        }
        if (choiceTheme != null
                && choiceTheme.track() != null
                && shared.getPhase() == Phase.WAITING
                && shared.getCurrentTheme() == null) {
            shared.show(choiceTheme.track(), choiceTheme);
        }
    }

    private static String lineOf(MenuMusic shared, @Nullable Track current) {
        if (current == null) {
            return "";
        }
        var theme = shared.getCurrentTheme();
        return switch (shared.getPhase()) {
            case FAILED -> "This track cannot be played.";
            case NO_OUTPUT -> "This computer gives no sound output.";
            default ->
                theme != null && !theme.place().isEmpty()
                        ? "Theme of " + theme.place()
                        : theme != null
                                ? "Largest music zone of tile " + theme.tile()
                                : shared.getPhase() == Phase.WAITING
                                        ? "Press play to listen."
                                        : "music/" + current.file().getFileName();
        };
    }

    private static String noteOf(Phase phase) {
        return switch (phase) {
            case PLAYING, STARTING -> "playing";
            case PAUSED -> "paused";
            case FAILED -> "cannot play";
            case NO_OUTPUT -> "no sound";
            case WAITING -> "chosen";
        };
    }

    private static String summary(List<Track> tracks) {
        var seconds = 0.0;
        for (var track : tracks) {
            if (!Double.isNaN(track.seconds())) {
                seconds += track.seconds();
            }
        }
        var minutes = Math.round(seconds / 60);
        var length = minutes >= 60 ? minutes / 60 + " h " + minutes % 60 + " min" : minutes + " min";
        return tracks.size() + (tracks.size() == 1 ? " track · " : " tracks · ") + length;
    }

    private static boolean switchRow(ImDrawList draw, String label, boolean on, float x, float y, float inner) {
        ImGui.setCursorScreenPos(x, y);
        var pressed = ImGui.invisibleButton("##radio-follow", inner, 30);
        if (ImGui.isItemHovered()) {
            draw.addRectFilled(x - 6, y, x + inner + 6, y + 30, HudWidgets.ROW_HOVER, 4f);
            HudWidgets.showTooltip(
                    "Plays the music the game plays on the tile under the pointer, or on the chosen tiles.");
        }
        draw.addText(x, y + (30 - ImGui.getFontSize()) * .5f, HudWidgets.TEXT, label);
        HudWidgets.drawSwitch(draw, x + inner - 30, y + 7, on, true);
        return pressed;
    }

    private static float[] rectOfItem() {
        return new float[] {
            ImGui.getItemRectMinX(), ImGui.getItemRectMinY(), ImGui.getItemRectMaxX(), ImGui.getItemRectMaxY()
        };
    }
}
