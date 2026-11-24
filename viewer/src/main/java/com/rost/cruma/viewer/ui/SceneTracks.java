package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneItems;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Part;
import com.rost.cruma.core.world.SceneOrders;
import com.rost.cruma.core.world.SceneOrders.SpeedSource;
import com.rost.cruma.core.world.SceneOrders.Walk;
import com.rost.cruma.core.world.ShotSpans;
import com.rost.cruma.core.world.SoundSpans;
import com.rost.cruma.core.world.SoundSpans.Clips;
import com.rost.cruma.core.world.SoundSpans.Kind;
import com.rost.cruma.viewer.ui.HudWidgets.Glyph;
import com.rost.cruma.viewer.ui.KeyTimeline.Fold;
import com.rost.cruma.viewer.ui.KeyTimeline.Lane;
import com.rost.cruma.viewer.ui.KeyTimeline.Mark;
import com.rost.cruma.viewer.ui.KeyTimeline.Span;
import imgui.ImColor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class SceneTracks {
    static final String DOORS = "doors";

    private static final int STAND_SPAN = ImColor.rgba(92, 219, 241, 46);
    private static final int BAR_SPAN = ImColor.rgba(164, 180, 195, 70);
    private static final int SOUND_SPAN = ImColor.rgba(164, 180, 195, 46);
    private static final int HOLD_SPAN = ImColor.rgba(92, 219, 241, 36);
    private static final int TURN_SPAN = ImColor.rgba(92, 219, 241, 110);
    private static final int FLIGHT_SPAN = ImColor.rgba(231, 238, 246, 110);
    private static final int MARK_SPAN = ImColor.rgba(164, 180, 195, 60);
    private static final int WALK_SPAN = ImColor.rgba(231, 238, 246, 90);
    private static final String ACTOR = "actor/";
    private static final String CAMERA = "Camera";
    private static final String BARS = "bars/";
    private static final String MOVES = "Moves";
    private static final int MOST_BANDS = 3;

    private SceneTracks() {}

    record Orders(List<Walk> walks, Set<Key> warned, IntFunction<@Nullable String> npcNames, int helper) {
        static final Orders NONE = new Orders(List.of(), Set.of(), _ -> null);

        Orders(List<Walk> walks, Set<Key> warned, IntFunction<@Nullable String> npcNames) {
            this(walks, warned, npcNames, 0);
        }
    }

    static List<Lane> lanes(
            SceneDocument document,
            List<Mark> pathKeys,
            @Nullable BakedScene baked,
            Set<Integer> open,
            Predicate<Key> chosen) {
        return lanes(document, pathKeys, baked, open, chosen, -1);
    }

    static List<Lane> lanes(
            SceneDocument document,
            List<Mark> pathKeys,
            @Nullable BakedScene baked,
            Set<Integer> open,
            Predicate<Key> chosen,
            int chosenBars) {
        return lanes(document, pathKeys, baked, open, chosen, chosenBars, Orders.NONE);
    }

    static List<Lane> lanes(
            SceneDocument document,
            List<Mark> pathKeys,
            @Nullable BakedScene baked,
            Set<Integer> open,
            Predicate<Key> chosen,
            int chosenBars,
            Orders orders) {
        return lanes(document, pathKeys, List.of(), baked, open, chosen, chosenBars, orders);
    }

    static List<Lane> lanes(
            SceneDocument document,
            List<Mark> pathKeys,
            List<Span> pathShots,
            @Nullable BakedScene baked,
            Set<Integer> open,
            Predicate<Key> chosen,
            int chosenBars,
            Orders orders) {
        var items = document.items();
        var camera = new ArrayList<>(pathKeys);
        var screen = new ArrayList<Mark>();
        var music = new ArrayList<Mark>();
        var voice = new ArrayList<Mark>();
        var sound = new ArrayList<Mark>();
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var shot = item.camera();
            if (shot != null) {
                if (shot.blank()) {
                    camera.add(Mark.quiet("blank/" + index, item.seconds()));
                } else {
                    camera.add(mark(Part.SHOT, index, 0, item, chosen, shotTip(shot, item, baked)));
                }
            }
            if (item.screen() != null) {
                var warned = orders.warned().contains(new Key(Part.SCREEN, index, 0));
                var tip = screenTip(item.screen(), item) + (warned ? ". It shows nothing as it stands" : "");
                var mark = mark(Part.SCREEN, index, 0, item, chosen, tip);
                screen.add(warned ? mark.warning() : mark);
            }
            var cue = item.music();
            if (cue != null) {
                var lane = cue.voice() ? voice : cue.sound() ? sound : music;
                lane.add(mark(Part.MUSIC, index, 0, item, chosen, musicTip(cue, item)));
            }
        }
        var lanes = new ArrayList<Lane>();
        lanes.add(lane(CAMERA, camera, document.path() != null ? pathShots : cameraSpans(document, baked)));
        lanes.add(new Lane(
                "Bars", "Bars", List.of(), bars(items, document.seconds(), chosenBars), Fold.NONE, false, false, true));
        lanes.add(lane("Screen", screen, List.of()));
        var clips = SoundSpans.of(items, document.seconds(), baked == null ? Clips.NONE : baked::clipSeconds);
        lanes.add(lane("Music", music, clipSpans(clips, Kind.TRACK)));
        lanes.add(lane("Voice", voice, clipSpans(clips, Kind.VOICE)));
        lanes.add(lane("Sound", sound, clipSpans(clips, Kind.SOUND)));
        addActors(lanes, document, baked, open, chosen, orders);
        return lanes;
    }

    static int barsOf(String spanId) {
        return spanId.startsWith(BARS) ? Integer.parseInt(spanId.substring(BARS.length())) : -1;
    }

    static String laneOf(int index) {
        return ACTOR + index;
    }

    static List<Lane> matching(List<Lane> lanes, String filter) {
        if (filter.isEmpty()) {
            return lanes;
        }
        var kept = new ArrayList<Lane>();
        var found = false;
        for (var lane : lanes) {
            if (!lane.child()) {
                found = lane.id().startsWith(ACTOR) && names(lane, filter);
            }
            if (found || lane.id().equals(CAMERA)) {
                kept.add(lane);
            }
        }
        return kept;
    }

    static int actors(List<Lane> lanes) {
        var actors = 0;
        for (var lane : lanes) {
            actors += lane.id().startsWith(ACTOR) && !lane.child() ? 1 : 0;
        }
        return actors;
    }

    static int actorOf(String laneId) {
        return laneId.startsWith(ACTOR) ? Integer.parseInt(laneId.substring(ACTOR.length())) : -1;
    }

    static String nameOf(Actor entry, IntFunction<@Nullable String> npcNames) {
        if (entry.player()) {
            return "Character " + entry.index();
        }
        if (entry.action() == Action.AIRSHIP_SPAWN) {
            return "Airship " + entry.index();
        }
        var name = npcNames.apply(entry.npc());
        return name == null ? "NPC " + entry.npc() + " " + entry.index() : name + " " + entry.index();
    }

    static boolean castsOnAPlace(Actor entry, int helper) {
        return helper > 0 && entry.npc() == helper && !entry.description().isEmpty();
    }

    static String nameOf(Actor entry, IntFunction<@Nullable String> npcNames, int helper) {
        return castsOnAPlace(entry, helper) ? entry.description() + " " + entry.index() : nameOf(entry, npcNames);
    }

    static String orderName(Action action) {
        return switch (action) {
            case SPAWN -> "Spawn";
            case DESPAWN -> "Despawn";
            case TELEPORT -> "Teleport";
            case TELEPORT_BACK -> "Teleport back";
            case SELF -> "Self";
            case MOVE -> "Move";
            case SKILL -> "Skill";
            case ATTACK -> "Attack";
            case ANIMATION -> "Animation";
            case AIRSHIP_SPAWN -> "Airship";
            case AIRSHIP_MOVE -> "Airship move";
            case ABNORMAL -> "Abnormal effect";
            case DOOR -> "Door";
            case EFFECT -> "Effect";
            case SELECT_TARGET -> "Select target";
            case EQUIP -> "Equip";
            case OTHER -> "Order";
        };
    }

    static String clipTip(SoundSpans.Span clip) {
        var played = clip.end() - clip.start();
        return clip.cut()
                ? String.format(Locale.ROOT, "Plays %.1f of its %.1f s", played, clip.clip())
                : String.format(Locale.ROOT, "Plays %.1f s", played);
    }

    static String walkTip(Walk walk) {
        return switch (walk.source()) {
            case ORDER ->
                String.format(
                        Locale.ROOT,
                        "%.1f s at %.0f units a second, the speed of this move. A video plays this, and the client has"
                                + " no place for it",
                        walk.seconds(),
                        walk.speed());
            case SERVER ->
                String.format(
                        Locale.ROOT,
                        "%.1f s at %.0f units a second, the speed this NPC has in npcdata.txt of the server",
                        walk.seconds(),
                        walk.speed());
            case CLIENT ->
                String.format(
                        Locale.ROOT,
                        "%.1f s at %.0f units a second, the speed this NPC has in npcgrp of the client",
                        walk.seconds(),
                        walk.speed());
            case CRUMA -> {
                var what = walk.flies()
                        ? walk.speed() == SceneOrders.AIRSHIP_SPEED ? "An airship flies" : "It flies"
                        : walk.run() ? "An actor runs" : "An actor walks";
                yield String.format(
                        Locale.ROOT,
                        "About %.1f s. %s %.0f units a second in Cruma, while no file gives this one a speed",
                        walk.seconds(),
                        what,
                        walk.speed());
            }
        };
    }

    private static boolean names(Lane lane, String filter) {
        return (lane.label() + " " + lane.id().substring(ACTOR.length()))
                .toLowerCase(Locale.ROOT)
                .contains(filter);
    }

    private static Lane lane(String name, List<Mark> marks, List<Span> spans) {
        return new Lane(name, name, marks, spans, Fold.NONE, false, false);
    }

    private static Mark mark(Part part, int index, int order, Item item, Predicate<Key> chosen, String tip) {
        var key = new Key(part, index, order);
        return new Mark(key.id(), item.seconds(), chosen.test(key), true, tip);
    }

    private static String shotTip(Camera shot, Item item, @Nullable BakedScene baked) {
        var target = baked == null ? null : baked.getName(shot.target());
        return at("Shot on " + (target == null ? "Index " + shot.target() : target), item);
    }

    private static String screenTip(Screen screen, Item item) {
        return at(
                switch (screen.kind()) {
                    case FADE -> "Fade";
                    case MESSAGE ->
                        "Line"
                                + (screen.message() >= 1
                                        ? " " + screen.message()
                                        : screen.text().isEmpty() ? "" : ": " + screen.text());
                    case SLIDE_START -> "Slides start";
                    case SLIDE_CHANGE -> "Slide " + screen.slide();
                    case SLIDE_MOVE -> "Slide moves " + screen.slide();
                    case SLIDE_END -> "Slides end";
                    case POST_EFFECT -> "Post effect " + screen.postEffect();
                    case NONE -> "Screen";
                },
                item);
    }

    private static String musicTip(Music cue, Item item) {
        return at(cue.name().isEmpty() ? "Stops the map's music" : cue.name(), item);
    }

    private static String at(String what, Item item) {
        return String.format(Locale.ROOT, "%s at %.2f s", what, item.seconds());
    }

    private static List<Span> bars(List<Item> items, double length, int chosen) {
        var spans = new ArrayList<Span>();
        var bars = SceneItems.bars(items, length);
        for (var index = 0; index < bars.size(); index++) {
            var span = bars.get(index);
            spans.add(new Span(
                    span.from(),
                    span.to(),
                    BAR_SPAN,
                    BARS + index,
                    String.format(
                            Locale.ROOT, "Bars from %.2f to %.2f s. Drag an end to move it", span.from(), span.to()),
                    null,
                    index == chosen));
        }
        return spans;
    }

    private static List<Span> cameraSpans(SceneDocument document, @Nullable BakedScene baked) {
        var spans = new ArrayList<Span>();
        if (baked != null) {
            for (var mark : baked.getMarkSpans()) {
                spans.add(new Span(
                        mark[0],
                        mark[1],
                        MARK_SPAN,
                        "",
                        "A mark of an animation holds the camera here, so it has no numbers to change. Make keys turns"
                                + " it into a path.",
                        Glyph.LOCK,
                        false));
            }
        }
        var items = document.items();
        for (var shot : ShotSpans.of(items, document.seconds())) {
            spans.add(new Span(
                    shot.start(),
                    shot.holdEnd(),
                    HOLD_SPAN,
                    String.format(Locale.ROOT, "Holds until %.2f s", shot.holdEnd())));
            if (shot.turnEnd() > shot.start()) {
                spans.add(new Span(
                        shot.start(),
                        shot.turnEnd(),
                        TURN_SPAN,
                        String.format(Locale.ROOT, "Turns until %.2f s", shot.turnEnd())));
            }
            if (shot.flies() && baked != null) {
                var landing = baked.findLanding(items.get(shot.item()).camera(), shot.start(), shot.holdEnd());
                spans.add(new Span(
                        shot.start(),
                        landing,
                        FLIGHT_SPAN,
                        String.format(Locale.ROOT, "Flies to its new target until %.2f s", landing)));
            }
        }
        return spans;
    }

    private static List<Span> clipSpans(List<SoundSpans.Span> clips, Kind kind) {
        var spans = new ArrayList<Span>();
        var bandEnds = new ArrayList<Double>();
        var bands = new ArrayList<Integer>();
        for (var clip : clips) {
            if (clip.kind() == kind) {
                var band = 0;
                while (band < bandEnds.size() && band < MOST_BANDS - 1 && bandEnds.get(band) > clip.start()) {
                    band++;
                }
                if (band == bandEnds.size()) {
                    bandEnds.add(clip.end());
                } else {
                    bandEnds.set(band, Math.max(bandEnds.get(band), clip.end()));
                }
                bands.add(band);
                spans.add(new Span(clip.start(), clip.end(), SOUND_SPAN, clipTip(clip)));
            }
        }
        for (var index = 0; index < spans.size(); index++) {
            spans.set(index, spans.get(index).inBand(bands.get(index), bandEnds.size()));
        }
        return spans;
    }

    private static void addActors(
            List<Lane> lanes,
            SceneDocument document,
            @Nullable BakedScene baked,
            Set<Integer> open,
            Predicate<Key> chosen,
            Orders orders) {
        var actors = new LinkedHashMap<Integer, Track>();
        var entries = SceneOrders.entries(document.items());
        var doors = new ArrayList<Mark>();
        var items = document.items();
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            for (var place = 0; place < item.actors().size(); place++) {
                var order = item.actors().get(place);
                var key = new Key(Part.ORDER, index, place);
                var plain = mark(Part.ORDER, index, place, item, chosen, orderTip(order, item));
                var mark = orders.warned().contains(key) ? plain.warning() : plain;
                if (order.action() == Action.DOOR) {
                    doors.add(mark);
                } else {
                    actors.computeIfAbsent(order.index(), _ -> new Track(order)).add(order, item, mark);
                }
            }
        }
        for (var actor : actors.entrySet()) {
            var index = actor.getKey();
            var track = actor.getValue();
            var opened = open.contains(index);
            var walked = walks(orders.walks(), index, document.seconds());
            var spans = new ArrayList<>(track.getSpans(document.seconds()));
            spans.addAll(walked);
            lanes.add(new Lane(
                    ACTOR + index,
                    label(index, entries.getOrDefault(index, track.first), baked, orders),
                    track.marks,
                    spans,
                    opened ? Fold.OPEN : Fold.CLOSED,
                    true,
                    false));
            if (opened) {
                for (var kind : track.kinds.entrySet()) {
                    lanes.add(new Lane(
                            ACTOR + index + "/" + kind.getKey(),
                            kind.getKey(),
                            kind.getValue(),
                            kind.getKey().equals(MOVES) ? walked : List.of(),
                            Fold.NONE,
                            true,
                            true));
                }
            }
        }
        if (!doors.isEmpty()) {
            lanes.add(new Lane(DOORS, "Doors", doors, List.of(), Fold.NONE, true, false));
        }
    }

    private static String orderTip(Actor order, Item item) {
        var name = orderName(order.action());
        var detail = switch (order.action()) {
            case ANIMATION, EFFECT, DOOR -> order.animation();
            case SKILL -> "skill " + order.skill();
            case SPAWN, AIRSHIP_SPAWN -> order.player() ? "" : "NPC " + order.npc();
            default -> "";
        };
        return at(detail.isEmpty() ? name : name + " " + detail, item);
    }

    private static List<Span> walks(List<Walk> walks, int index, double length) {
        var spans = new ArrayList<Span>();
        for (var walk : walks) {
            if (walk.index() == index && walk.stop() > walk.start()) {
                var end = Math.min(walk.stop(), length);
                spans.add(
                        walk.source() == SpeedSource.CRUMA
                                ? Span.guess(walk.start(), end, WALK_SPAN, walkTip(walk))
                                : new Span(walk.start(), end, WALK_SPAN, walkTip(walk)));
            }
        }
        return spans;
    }

    private static String label(int index, Actor first, @Nullable BakedScene baked, Orders orders) {
        if (castsOnAPlace(first, orders.helper())) {
            return nameOf(first, orders.npcNames(), orders.helper());
        }
        var name = baked == null ? null : baked.getName(index);
        if (name != null) {
            return name;
        }
        if (first.action() == Action.EFFECT && index == 0) {
            return "On a place";
        }
        if (first.player()) {
            return first.action().enters() ? nameOf(first, orders.npcNames()) : "Player " + index;
        }
        return first.npc() > 0 ? nameOf(first, orders.npcNames()) : "Index " + index;
    }

    private static String kind(Action action) {
        return switch (action) {
            case SPAWN, DESPAWN, TELEPORT, TELEPORT_BACK, SELF, AIRSHIP_SPAWN -> "Stands";
            case MOVE, AIRSHIP_MOVE -> MOVES;
            case ANIMATION -> "Animations";
            case SKILL, ATTACK -> "Skills";
            case EFFECT, ABNORMAL -> "Effects";
            default -> "Other";
        };
    }

    private static final class Track {
        final Actor first;
        final List<Mark> marks = new ArrayList<>();
        final Map<String, List<Mark>> kinds = new LinkedHashMap<>();
        final List<double[]> stands = new ArrayList<>();
        double since = Double.NaN;

        Track(Actor first) {
            this.first = first;
        }

        void add(Actor order, Item item, Mark mark) {
            marks.add(mark);
            kinds.computeIfAbsent(kind(order.action()), _ -> new ArrayList<>()).add(mark);
            var action = order.action();
            if (action.enters() && order.stands() && (action != Action.TELEPORT || Double.isNaN(since))) {
                end(item.seconds());
                since = item.seconds();
            } else if (action == Action.DESPAWN || action == Action.TELEPORT_BACK) {
                end(item.seconds());
            }
        }

        List<Span> getSpans(double length) {
            var spans = new ArrayList<Span>(stands.size() + 1);
            for (var stand : stands) {
                spans.add(new Span(stand[0], stand[1], STAND_SPAN));
            }
            if (!Double.isNaN(since)) {
                spans.add(new Span(since, length, STAND_SPAN));
            }
            return spans;
        }

        private void end(double seconds) {
            if (!Double.isNaN(since)) {
                stands.add(new double[] {since, seconds});
                since = Double.NaN;
            }
        }
    }
}
