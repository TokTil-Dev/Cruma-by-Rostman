package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Action;
import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Visual;
import com.rost.cruma.core.world.SceneItems.Key;
import com.rost.cruma.core.world.SceneItems.Moved;
import com.rost.cruma.core.world.SceneItems.Part;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.Nullable;

public final class SceneOrders {
    public static final double WALK_SPEED = 80;

    public static final double RUN_SPEED = 180;

    public static final double AIRSHIP_SPEED = 330;

    private static final double STANDS_STILL = 1;
    private static final int TURN = 0x10000;
    private static final int SHIP = 1;
    private static final String DEATH = "death";
    private static final String WAIT = "wait";
    private static final String STAND = "stand";
    private static final Set<String> STANCES = Set.of("wait", "atkwait", "walk", "run");
    private static final String SHIELD = "shield";
    private static final String ONE_HAND = "1hs";
    private static final String SOCIAL = "social";
    private static final double ASIDE_ROOM = 1;
    private static final double SAME_MOMENT = 1e-9;

    private SceneOrders() {}

    public interface Skills {
        Skills ANY = new Skills() {
            @Override
            public boolean hasLevelOne(int skill) {
                return true;
            }

            @Override
            public int getTwin(int skill) {
                return 0;
            }

            @Override
            public boolean takesBody(int skill) {
                return false;
            }
        };

        boolean hasLevelOne(int skill);

        int getTwin(int skill);

        boolean takesBody(int skill);
    }

    public interface Sequences {
        Sequences ANY = (_, _) -> true;

        boolean has(int index, String sequence);
    }

    public interface Bodies {
        Bodies NONE = _ -> null;

        @Nullable
        Size of(int index);
    }

    public interface Speeds {
        Speeds NONE = _ -> null;

        @Nullable
        NpcSpeed of(int npc);
    }

    public record NpcSpeed(double walk, double run, boolean server) {
        private double of(boolean running) {
            return running ? run : walk;
        }
    }

    public enum SpeedSource {
        ORDER,
        SERVER,
        CLIENT,
        CRUMA
    }

    public record Size(double radius, double halfHeight) {}

    public enum Problem {
        ACTOR_GONE,
        TARGET_GONE,
        NO_LEVEL_ONE,
        NO_SEQUENCE,
        FADE_DROPPED,
        NO_WINDOW,
        SAME_PLACE
    }

    public enum Fix {
        NONE,
        MOVE_ORDER,
        CAST_ON_SELF,
        USE_SKILL,
        REMOVE_ORDER,
        MOVE_ASIDE
    }

    public record Warning(Key key, Problem problem, Fix fix, double moment, int skill, int other, double x, double y) {
        public Warning(Key key, Problem problem, Fix fix, double moment, int skill) {
            this(key, problem, fix, moment, skill, 0, 0, 0);
        }
    }

    public record Stay(int index, double from, double to, boolean player) {}

    public record Walk(
            Key key,
            int index,
            double start,
            double arrival,
            double stop,
            double fromX,
            double fromY,
            double fromZ,
            double toX,
            double toY,
            double toZ,
            boolean flies,
            boolean run,
            double speed,
            SpeedSource source) {
        public boolean own() {
            return source == SpeedSource.ORDER;
        }

        public double seconds() {
            return arrival - start;
        }

        public boolean cut() {
            return stop < arrival;
        }

        private Walk stoppedAt(double moment) {
            return new Walk(
                    key,
                    index,
                    start,
                    arrival,
                    Math.min(arrival, moment),
                    fromX,
                    fromY,
                    fromZ,
                    toX,
                    toY,
                    toZ,
                    flies,
                    run,
                    speed,
                    source);
        }
    }

    public static Actor spawn(int npc, int index, double x, double y, double z, int yaw) {
        return order(
                false, Action.SPAWN, npc, index, 0, x, y, z, yaw, 0, "", false, false, false, 0, 0, false, List.of());
    }

    public static Actor airship(int index, double x, double y, double z, int yaw) {
        return order(
                false,
                Action.AIRSHIP_SPAWN,
                SHIP,
                index,
                0,
                x,
                y,
                z,
                yaw,
                0,
                "",
                false,
                false,
                false,
                0,
                0,
                false,
                List.of());
    }

    public static Actor character(
            int index, double x, double y, double z, int yaw, int race, int sex, boolean mage, List<Visual> visuals) {
        return order(
                true, Action.SPAWN, 0, index, 0, x, y, z, yaw, 0, "", false, false, false, race, sex, mage, visuals);
    }

    public static Actor move(Actor actor, double x, double y, double z, boolean run) {
        var ship = actor.action() == Action.AIRSHIP_SPAWN;
        return order(
                actor.player(),
                ship ? Action.AIRSHIP_MOVE : Action.MOVE,
                0,
                actor.index(),
                0,
                x,
                y,
                z,
                0,
                0,
                "",
                false,
                run,
                false,
                0,
                0,
                false,
                List.of());
    }

    public static Actor animation(Actor actor, String sequence, boolean loop) {
        return order(
                actor.player(),
                Action.ANIMATION,
                0,
                actor.index(),
                0,
                0,
                0,
                0,
                0,
                0,
                sequence,
                false,
                false,
                loop,
                0,
                0,
                false,
                List.of());
    }

    public static Actor skill(Actor actor, int skill, int target) {
        return order(
                actor.player(),
                Action.SKILL,
                0,
                actor.index(),
                target,
                0,
                0,
                0,
                0,
                skill,
                "",
                false,
                false,
                false,
                0,
                0,
                false,
                List.of());
    }

    public static Actor effect(Actor actor, String name) {
        return order(
                actor.player(),
                Action.EFFECT,
                0,
                actor.index(),
                0,
                0,
                0,
                0,
                0,
                0,
                name,
                false,
                false,
                false,
                0,
                0,
                false,
                List.of());
    }

    public static Actor effectAt(String name, double x, double y, double z) {
        return order(false, Action.EFFECT, 0, 0, 0, x, y, z, 0, 0, name, false, false, false, 0, 0, false, List.of());
    }

    public static Actor despawn(Actor actor) {
        return order(
                actor.player(),
                Action.DESPAWN,
                0,
                actor.index(),
                0,
                0,
                0,
                0,
                0,
                0,
                "",
                false,
                false,
                false,
                0,
                0,
                false,
                List.of());
    }

    public static double speed(Actor order, int npc, Speeds speeds) {
        return pace(order, npc, speeds).speed();
    }

    public static int npc(Actor entry) {
        return entry.player() || entry.action() == Action.AIRSHIP_SPAWN ? 0 : entry.npc();
    }

    public static @Nullable String deathWait(String sequence, List<String> names) {
        var lower = sequence.toLowerCase(Locale.ROOT);
        if (!lower.contains(DEATH) || lower.contains(DEATH + WAIT) || lower.contains(STAND)) {
            return null;
        }
        var same = lower.replace(DEATH, DEATH + WAIT);
        String plain = null;
        for (var name : names) {
            if (name.equalsIgnoreCase(same)) {
                return name;
            }
            if (name.equalsIgnoreCase(DEATH + WAIT)) {
                plain = name;
            }
        }
        return plain;
    }

    public static List<Item> withDeathWait(
            List<Item> items,
            Actor actor,
            String sequence,
            double moment,
            double ends,
            List<String> names,
            double length,
            double frame) {
        var wait = deathWait(sequence, names);
        if (wait == null
                || Double.isNaN(ends)
                || ends >= length - frame / 2
                || hasOrdersBetween(items, actor.index(), moment, ends, frame)) {
            return items;
        }
        return SceneItems.withNewOrder(items, ends, animation(actor, wait, true), frame / 2)
                .items();
    }

    public static List<String> byWeapon(List<String> names, String waiting) {
        var weapons = weapons(names);
        var stance = weaponOf(waiting, weapons);
        if (stance == null) {
            return names;
        }
        var fitting = new ArrayList<String>();
        var free = new ArrayList<String>();
        var other = new ArrayList<String>();
        for (var name : names) {
            var weapon = weaponOf(name, weapons);
            (weapon == null ? free : fits(weapon, stance) ? fitting : other).add(name);
        }
        var sorted = new ArrayList<String>(names.size());
        for (var group : List.of(fitting, free, other)) {
            group.sort(String.CASE_INSENSITIVE_ORDER);
            sorted.addAll(group);
        }
        return sorted;
    }

    public static Actor at(Actor order, double x, double y, double z) {
        var fields = SceneFields.ACTOR;
        return fields.with(fields.with(fields.with(order, "x", x), "y", y), "z", z);
    }

    public static Actor facing(Actor order, int yaw) {
        return SceneFields.ACTOR.with(order, "yaw", yaw);
    }

    public static int yaw(double dx, double dy) {
        return Math.floorMod(Math.round(Math.atan2(dy, dx) * TURN / (2 * Math.PI)), TURN);
    }

    public static boolean hasPlace(Actor order) {
        return switch (order.action()) {
            case SPAWN, AIRSHIP_SPAWN, TELEPORT, MOVE, AIRSHIP_MOVE -> true;
            case SELF -> order.placed();
            case EFFECT -> order.index() == 0 || order.placed();
            default -> false;
        };
    }

    public static @Nullable Actor entry(List<Item> items, int index) {
        for (var item : items) {
            for (var order : item.actors()) {
                if (order.index() == index && order.action().enters() && order.stands()) {
                    return order;
                }
            }
        }
        return null;
    }

    public static List<Integer> indexes(List<Item> items) {
        var indexes = new ArrayList<Integer>();
        var seen = new HashSet<Integer>();
        for (var item : items) {
            for (var order : item.actors()) {
                if (order.action().enters() && order.stands() && seen.add(order.index())) {
                    indexes.add(order.index());
                }
            }
        }
        return indexes;
    }

    public static Map<Integer, Actor> entries(List<Item> items) {
        var entries = new HashMap<Integer, Actor>();
        for (var item : items) {
            for (var order : item.actors()) {
                if (order.action().enters() && order.stands()) {
                    entries.putIfAbsent(order.index(), order);
                }
            }
        }
        return entries;
    }

    public static List<Stay> stays(List<Item> items, double length) {
        var open = new HashMap<Integer, Stay>();
        var stays = new ArrayList<Stay>();
        var moment = 0.0;
        for (var item : items) {
            moment = Math.max(moment, item.seconds());
            for (var order : item.actors()) {
                var stay = open.get(order.index());
                if (order.action().enters() && order.stands()) {
                    open.putIfAbsent(order.index(), new Stay(order.index(), moment, moment, order.player()));
                } else if (leaves(order) && stay != null) {
                    stays.add(new Stay(stay.index(), stay.from(), moment, stay.player()));
                    open.remove(order.index());
                }
            }
        }
        for (var stay : open.values()) {
            stays.add(new Stay(stay.index(), stay.from(), Math.max(length, moment), stay.player()));
        }
        return stays;
    }

    public static List<Walk> walks(List<Item> items, IntPredicate takesBody) {
        return walks(items, takesBody, Speeds.NONE);
    }

    public static List<Walk> walks(List<Item> items, IntPredicate takesBody, Speeds speeds) {
        var walkers = new HashMap<Integer, Walker>();
        var walks = new ArrayList<Walk>();
        var moment = 0.0;
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            moment = Math.max(moment, item.seconds());
            for (var at = 0; at < item.actors().size(); at++) {
                var order = item.actors().get(at);
                var walker = walkers.get(order.index());
                switch (order.action()) {
                    case SPAWN, AIRSHIP_SPAWN, SELF -> {
                        if (order.stands()) {
                            stop(walker, walks, moment);
                            walkers.put(order.index(), new Walker(order));
                        }
                    }
                    case TELEPORT -> {
                        stop(walker, walks, moment);
                        if (walker != null) {
                            walker.to(order.x(), order.y(), order.z());
                        } else if (order.stands()) {
                            walkers.put(order.index(), new Walker(order));
                        }
                    }
                    case DESPAWN, TELEPORT_BACK -> {
                        stop(walker, walks, moment);
                        walkers.remove(order.index());
                    }
                    case MOVE, AIRSHIP_MOVE -> {
                        if (walker != null) {
                            stop(walker, walks, moment);
                            var target = order.player() ? walkers.get(order.target()) : null;
                            walks.add(walker.go(new Key(Part.ORDER, place, at), order, target, moment, speeds));
                            walker.walk = walks.size() - 1;
                        }
                    }
                    case ANIMATION -> stop(walker, walks, moment);
                    case SKILL -> {
                        if (walkers.containsKey(order.target()) && takesBody.test(order.skill())) {
                            stop(walker, walks, moment);
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return walks;
    }

    public static @Nullable Walk walk(List<Walk> walks, Key key) {
        for (var walk : walks) {
            if (walk.key().equals(key)) {
                return walk;
            }
        }
        return null;
    }

    public static List<Warning> warnings(
            List<Item> items, double length, double step, Skills skills, Sequences sequences) {
        var stays = stays(items, length);
        var warnings = new ArrayList<Warning>();
        var standing = new HashSet<Integer>();
        var moment = 0.0;
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            moment = Math.max(moment, item.seconds());
            for (var at = 0; at < item.actors().size(); at++) {
                var order = item.actors().get(at);
                var key = new Key(Part.ORDER, place, at);
                if (order.action().enters() && order.stands()) {
                    standing.add(order.index());
                } else if (leaves(order)) {
                    standing.remove(order.index());
                } else {
                    check(key, order, moment, standing, stays, step, skills, sequences, warnings);
                }
            }
        }
        return warnings;
    }

    public static List<Warning> crowded(
            List<Item> items, double length, double step, IntPredicate takesBody, Bodies bodies, Speeds speeds) {
        var rests = rests(items, length, walks(items, takesBody, speeds), bodies);
        rests.sort(Comparator.comparingDouble(Rest::getFrom));
        var warnings = new LinkedHashMap<Key, Warning>();
        for (var late = 1; late < rests.size(); late++) {
            var rest = rests.get(late);
            for (var early = 0; early < late && !warnings.containsKey(rest.key); early++) {
                var other = rests.get(early);
                if (other.index != rest.index
                        && other.to > rest.from + step
                        && rest.to > rest.from + step
                        && rest.isInside(other)) {
                    warnings.put(rest.key, rest.checkAside(other));
                }
            }
        }
        return new ArrayList<>(warnings.values());
    }

    public static List<Warning> of(List<Warning> warnings, Key key) {
        var own = new ArrayList<Warning>(1);
        for (var warning : warnings) {
            if (warning.key().equals(key)) {
                own.add(warning);
            }
        }
        return own;
    }

    public static Moved fixed(List<Item> items, Warning warning, double reach) {
        var key = warning.key();
        return switch (warning.fix()) {
            case NONE -> new Moved(items, new TreeSet<>(Set.of(key)));
            case MOVE_ORDER ->
                SceneItems.move(
                        items,
                        Set.of(key),
                        warning.moment() - items.get(key.item()).seconds(),
                        reach);
            case CAST_ON_SELF -> {
                var order = orderOf(items, key);
                yield same(items, key, SceneFields.ACTOR.with(order, "target", order.index()));
            }
            case USE_SKILL -> same(items, key, SceneFields.ACTOR.with(orderOf(items, key), "skill", warning.skill()));
            case REMOVE_ORDER -> new Moved(SceneItems.remove(items, Set.of(key)), new TreeSet<>());
            case MOVE_ASIDE -> {
                var order = orderOf(items, key);
                yield same(items, key, at(order, warning.x(), warning.y(), order.z()));
            }
        };
    }

    private static Set<String> weapons(List<String> names) {
        var weapons = new HashSet<String>();
        for (var name : names) {
            var parts = name.toLowerCase(Locale.ROOT).split("_");
            if (parts.length == 3 && STANCES.contains(parts[0])) {
                weapons.add(parts[1]);
            }
        }
        return weapons;
    }

    private static @Nullable String weaponOf(String name, Set<String> weapons) {
        var parts = name.toLowerCase(Locale.ROOT).split("_");
        for (var at = 1; at < parts.length - 1 && !parts[0].equals(SOCIAL); at++) {
            if (weapons.contains(parts[at])) {
                return parts[at];
            }
        }
        return null;
    }

    private static boolean fits(String weapon, String stance) {
        return weapon.equals(stance) || stance.equals(SHIELD) && weapon.equals(ONE_HAND);
    }

    private static boolean hasOrdersBetween(List<Item> items, int index, double after, double upTo, double frame) {
        for (var item : items) {
            if (item.seconds() > after + SAME_MOMENT && item.seconds() <= upTo + frame / 2) {
                for (var order : item.actors()) {
                    if (order.index() == index) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Actor orderOf(List<Item> items, Key key) {
        return items.get(key.item()).actors().get(key.order());
    }

    private static Moved same(List<Item> items, Key key, Actor order) {
        return new Moved(SceneItems.withOrder(items, key.item(), key.order(), order), new TreeSet<>(Set.of(key)));
    }

    private static void check(
            Key key,
            Actor order,
            double moment,
            Set<Integer> standing,
            List<Stay> stays,
            double step,
            Skills skills,
            Sequences sequences,
            List<Warning> warnings) {
        var here = standing.contains(order.index()) || order.player() && order.index() == 0;
        switch (order.action()) {
            case MOVE, AIRSHIP_MOVE -> {
                if (!here) {
                    warnings.add(
                            gone(key, order, Problem.ACTOR_GONE, moment, stays, step, Fix.REMOVE_ORDER, order.index()));
                } else if (!order.moveSequence().isEmpty() && !sequences.has(order.index(), order.moveSequence())) {
                    warnings.add(new Warning(key, Problem.NO_SEQUENCE, Fix.NONE, moment, 0));
                }
            }
            case ANIMATION -> {
                if (!here) {
                    warnings.add(
                            gone(key, order, Problem.ACTOR_GONE, moment, stays, step, Fix.REMOVE_ORDER, order.index()));
                } else if (!order.animation().isEmpty() && !sequences.has(order.index(), order.animation())) {
                    warnings.add(new Warning(key, Problem.NO_SEQUENCE, Fix.NONE, moment, 0));
                }
            }
            case EFFECT -> {
                if (!here && order.index() != 0 && !order.placed()) {
                    warnings.add(
                            gone(key, order, Problem.ACTOR_GONE, moment, stays, step, Fix.REMOVE_ORDER, order.index()));
                }
            }
            case SKILL -> {
                if (!here) {
                    var both = gone(
                            key,
                            order,
                            Problem.ACTOR_GONE,
                            moment,
                            stays,
                            step,
                            Fix.REMOVE_ORDER,
                            order.index(),
                            order.target());
                    warnings.add(
                            both.fix() == Fix.MOVE_ORDER
                                    ? both
                                    : gone(
                                            key,
                                            order,
                                            Problem.ACTOR_GONE,
                                            moment,
                                            stays,
                                            step,
                                            Fix.REMOVE_ORDER,
                                            order.index()));
                } else if (!standing.contains(order.target())) {
                    warnings.add(gone(
                            key,
                            order,
                            Problem.TARGET_GONE,
                            moment,
                            stays,
                            step,
                            Fix.CAST_ON_SELF,
                            order.index(),
                            order.target()));
                }
                if (!skills.hasLevelOne(order.skill())) {
                    var twin = skills.getTwin(order.skill());
                    warnings.add(new Warning(
                            key, Problem.NO_LEVEL_ONE, twin == 0 ? Fix.REMOVE_ORDER : Fix.USE_SKILL, moment, twin));
                }
            }
            default -> {
            }
        }
    }

    private static Warning gone(
            Key key,
            Actor order,
            Problem problem,
            double moment,
            List<Stay> stays,
            double step,
            Fix otherwise,
            int... indexes) {
        var nearest = nearest(moment, order.player(), stays, step, indexes);
        return Double.isNaN(nearest)
                ? new Warning(key, problem, otherwise, moment, 0)
                : new Warning(key, problem, Fix.MOVE_ORDER, nearest, 0);
    }

    private static double nearest(double moment, boolean player, List<Stay> stays, double step, int... indexes) {
        var windows = List.of(new double[] {0, Double.POSITIVE_INFINITY});
        for (var index : indexes) {
            var next = new ArrayList<double[]>();
            for (var window : windows) {
                for (var stay : stays) {
                    var from = Math.max(window[0], stay.from() + (stay.player() && !player ? step : 0));
                    var to = Math.min(window[1], stay.to());
                    if (stay.index() == index && from <= to) {
                        next.add(new double[] {from, to});
                    }
                }
            }
            windows = next;
        }
        var best = Double.NaN;
        for (var window : windows) {
            var last = window[1] - step;
            if (window[0] <= last) {
                var near = Math.clamp(moment, window[0], last);
                if (Double.isNaN(best) || Math.abs(near - moment) < Math.abs(best - moment)) {
                    best = near;
                }
            }
        }
        return best;
    }

    private static List<Rest> rests(List<Item> items, double length, List<Walk> walks, Bodies bodies) {
        var rests = new ArrayList<Rest>();
        var open = new HashMap<Integer, Rest>();
        var moment = 0.0;
        for (var place = 0; place < items.size(); place++) {
            var item = items.get(place);
            moment = Math.max(moment, item.seconds());
            for (var at = 0; at < item.actors().size(); at++) {
                var order = item.actors().get(at);
                var size = bodies.of(order.index());
                if (order.action().enters() && order.stands() && size != null) {
                    var old = open.put(
                            order.index(),
                            new Rest(
                                    new Key(Part.ORDER, place, at),
                                    order.index(),
                                    moment,
                                    moment,
                                    order.x(),
                                    order.y(),
                                    order.z(),
                                    size));
                    if (old != null) {
                        old.split(moment, walks, rests);
                    }
                } else if (leaves(order) && open.containsKey(order.index())) {
                    open.remove(order.index()).split(moment, walks, rests);
                }
            }
        }
        for (var rest : open.values()) {
            rest.split(Math.max(length, moment), walks, rests);
        }
        return rests;
    }

    private static Pace pace(Actor order, int npc, Speeds speeds) {
        if (order.speed() > 0) {
            return new Pace(order.speed(), SpeedSource.ORDER);
        }
        if (order.action() == Action.AIRSHIP_MOVE) {
            return new Pace(AIRSHIP_SPEED, SpeedSource.CRUMA);
        }
        var known = npc > 0 ? speeds.of(npc) : null;
        if (known != null && known.of(order.run()) > STANDS_STILL) {
            return new Pace(known.of(order.run()), known.server() ? SpeedSource.SERVER : SpeedSource.CLIENT);
        }
        return new Pace(order.run() ? RUN_SPEED : WALK_SPEED, SpeedSource.CRUMA);
    }

    private static boolean leaves(Actor order) {
        return order.action() == Action.DESPAWN || order.action() == Action.TELEPORT_BACK;
    }

    private static void stop(@Nullable Walker walker, List<Walk> walks, double moment) {
        if (walker != null && walker.walk >= 0) {
            var walk = walks.get(walker.walk);
            walks.set(walker.walk, walk.stoppedAt(moment));
            walker.settle(walk, moment);
            walker.walk = -1;
        }
    }

    private static Actor order(
            boolean player,
            Action action,
            int npc,
            int index,
            int target,
            double x,
            double y,
            double z,
            int yaw,
            int skill,
            String animation,
            boolean fly,
            boolean run,
            boolean loop,
            int race,
            int sex,
            boolean mage,
            List<Visual> visuals) {
        return new Actor(
                player, action, npc, index, target, x, y, z, yaw, skill, animation, fly, run, loop, false, race, sex,
                mage, false, 0, 0, visuals);
    }

    private static final class Rest {
        final Key key;
        final int index;
        final double from;
        final double to;
        final double x;
        final double y;
        final double z;
        final Size size;

        Rest(Key key, int index, double from, double to, double x, double y, double z, Size size) {
            this.key = key;
            this.index = index;
            this.from = from;
            this.to = to;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
        }

        double getFrom() {
            return from;
        }

        void split(double end, List<Walk> walks, List<Rest> rests) {
            var since = from;
            var key = this.key;
            var nowX = x;
            var nowY = y;
            var nowZ = z;
            for (var walk : walks) {
                if (walk.index() == index && walk.start() >= since && walk.start() < end) {
                    if (walk.start() > since) {
                        rests.add(new Rest(key, index, since, walk.start(), nowX, nowY, nowZ, size));
                    }
                    var done = walk.arrival() > walk.start()
                            ? Math.min(1, (walk.stop() - walk.start()) / (walk.arrival() - walk.start()))
                            : 1;
                    since = Math.min(walk.stop(), end);
                    key = walk.key();
                    nowX = Walker.mix(walk.fromX(), walk.toX(), done);
                    nowY = Walker.mix(walk.fromY(), walk.toY(), done);
                    nowZ = Walker.mix(walk.fromZ(), walk.toZ(), done);
                }
            }
            if (end > since) {
                rests.add(new Rest(key, index, since, end, nowX, nowY, nowZ, size));
            }
        }

        boolean isInside(Rest other) {
            return Math.hypot(x - other.x, y - other.y) < Math.max(size.radius(), other.size.radius())
                    && Math.abs(z - other.z) < size.halfHeight() + other.size.halfHeight();
        }

        Warning checkAside(Rest other) {
            var dx = x - other.x;
            var dy = y - other.y;
            var apart = Math.hypot(dx, dy);
            var reach = size.radius() + other.size.radius() + ASIDE_ROOM;
            return new Warning(
                    key,
                    Problem.SAME_PLACE,
                    Fix.MOVE_ASIDE,
                    from,
                    0,
                    other.index,
                    other.x + (apart == 0 ? reach : dx / apart * reach),
                    other.y + (apart == 0 ? 0 : dy / apart * reach));
        }
    }

    private record Pace(double speed, SpeedSource source) {}

    private static final class Walker {
        double x;
        double y;
        double z;
        int walk = -1;
        final boolean air;
        final int npc;

        Walker(Actor order) {
            to(order.x(), order.y(), order.z());
            air = order.fly();
            npc = npc(order);
        }

        void to(double placeX, double placeY, double placeZ) {
            x = placeX;
            y = placeY;
            z = placeZ;
        }

        void settle(Walk walk, double moment) {
            var done = walk.arrival() > walk.start()
                    ? Math.min(1, (moment - walk.start()) / (walk.arrival() - walk.start()))
                    : 1;
            to(
                    mix(walk.fromX(), walk.toX(), done),
                    mix(walk.fromY(), walk.toY(), done),
                    mix(walk.fromZ(), walk.toZ(), done));
        }

        Walk go(Key key, Actor order, @Nullable Walker target, double moment, Speeds speeds) {
            var flies = air || order.action() == Action.AIRSHIP_MOVE;
            var toX = target == null ? order.x() : target.x;
            var toY = target == null ? order.y() : target.y;
            var toZ = target == null ? order.z() : target.z;
            var dx = toX - x;
            var dy = toY - y;
            var dz = toZ - z;
            var way = flies ? Math.sqrt(dx * dx + dy * dy + dz * dz) : Math.hypot(dx, dy);
            var pace = pace(order, npc, speeds);
            var arrival = moment + way / pace.speed();
            return new Walk(
                    key,
                    order.index(),
                    moment,
                    arrival,
                    arrival,
                    x,
                    y,
                    z,
                    toX,
                    toY,
                    toZ,
                    flies,
                    order.run(),
                    pace.speed(),
                    pace.source());
        }

        private static double mix(double from, double to, double share) {
            return from + (to - from) * share;
        }
    }
}
