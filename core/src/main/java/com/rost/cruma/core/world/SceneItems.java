package com.rost.cruma.core.world;

import com.rost.cruma.core.world.GameScenes.Actor;
import com.rost.cruma.core.world.GameScenes.Camera;
import com.rost.cruma.core.world.GameScenes.Item;
import com.rost.cruma.core.world.GameScenes.Music;
import com.rost.cruma.core.world.GameScenes.Screen;
import com.rost.cruma.core.world.GameScenes.ScreenKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;

public final class SceneItems {
    private SceneItems() {}

    public enum Part {
        SHOT("shot"),
        SCREEN("screen"),
        MUSIC("music"),
        ORDER("order");

        private final String text;

        Part(String text) {
            this.text = text;
        }
    }

    public record Key(Part part, int item, int order) implements Comparable<Key> {
        private static final Comparator<Key> ORDER =
                Comparator.comparingInt(Key::item).thenComparing(Key::part).thenComparingInt(Key::order);

        public static @Nullable Key of(String id) {
            var pieces = id.split("/");
            if (pieces.length != 3) {
                return null;
            }
            for (var part : Part.values()) {
                if (part.text.equals(pieces[0])) {
                    return new Key(part, Integer.parseInt(pieces[1]), Integer.parseInt(pieces[2]));
                }
            }
            return null;
        }

        public String id() {
            return part.text + "/" + item + "/" + order;
        }

        @Override
        public int compareTo(Key other) {
            return ORDER.compare(this, other);
        }
    }

    public record Moved(List<Item> items, SortedSet<Key> keys) {}

    public record Bars(double from, double to) {}

    public static double seconds(List<Item> items, Key key) {
        return holds(items, key) ? items.get(key.item()).seconds() : Double.NaN;
    }

    public static boolean holds(List<Item> items, Key key) {
        if (key.item() < 0 || key.item() >= items.size()) {
            return false;
        }
        var item = items.get(key.item());
        return switch (key.part()) {
            case SHOT -> item.camera() != null;
            case SCREEN -> item.screen() != null;
            case MUSIC -> item.music() != null;
            case ORDER -> key.order() >= 0 && key.order() < item.actors().size();
        };
    }

    public static boolean isWideAt(List<Item> items, double seconds) {
        var wide = false;
        for (var item : items) {
            if (item.seconds() > seconds) {
                break;
            }
            wide = item.camera() != null && item.camera().wide();
        }
        return wide;
    }

    public static List<Bars> bars(List<Item> items, double length) {
        var spans = new ArrayList<Bars>();
        var from = Double.NaN;
        for (var item : items) {
            var wide = isWide(item);
            if (wide && Double.isNaN(from)) {
                from = item.seconds();
            } else if (!wide && !Double.isNaN(from)) {
                spans.add(new Bars(from, item.seconds()));
                from = Double.NaN;
            }
        }
        if (!Double.isNaN(from)) {
            spans.add(new Bars(from, length));
        }
        return spans;
    }

    public static List<Item> withBars(
            List<Item> items, double from, double to, boolean on, double reach, double length) {
        var low = Math.max(0, Math.min(from, to));
        var high = Math.min(length, Math.max(from, to));
        if (high - low < reach) {
            return items;
        }
        var after = isWideAt(items, high);
        var next = new ArrayList<Item>(items.size() + 2);
        for (var item : items) {
            var seconds = item.seconds();
            next.add(seconds >= low - reach && seconds < high - reach ? withWide(item, on) : item);
        }
        if (!isNear(next, low, reach)) {
            insert(next, new Item(low, "", Camera.blank(on), List.of(), null, null));
        }
        if (high < length - reach && !isNear(next, high, reach)) {
            insert(next, new Item(high, "", Camera.blank(after), List.of(), null, null));
        }
        return tidied(next);
    }

    public static Moved withNewShot(List<Item> items, double seconds, Camera shot, double reach) {
        var slots = slots(items);
        var part = new Carried(Part.SHOT, seconds, shot, null);
        place(slots, part, reach);
        return result(slots, List.of(part));
    }

    public static Moved withNewScreens(List<Item> items, double seconds, List<Screen> screens, double reach) {
        var slots = slots(items);
        var parts = new ArrayList<Carried>(screens.size());
        for (var screen : screens) {
            var part = new Carried(Part.SCREEN, seconds, screen, null);
            if (parts.isEmpty()) {
                place(slots, part, reach);
            } else {
                placeAfter(slots, parts.getLast().into, part);
            }
            parts.add(part);
        }
        return result(slots, parts);
    }

    public static Moved withNewMusic(List<Item> items, double seconds, Music music, double reach) {
        var slots = slots(items);
        var part = new Carried(Part.MUSIC, seconds, music, null);
        place(slots, part, reach);
        return result(slots, List.of(part));
    }

    public static boolean areSlidesOpenAt(List<Item> items, double seconds) {
        var open = false;
        for (var item : items) {
            if (item.seconds() > seconds) {
                break;
            }
            var screen = item.screen();
            if (screen != null && screen.kind() == ScreenKind.SLIDE_START) {
                open = true;
            } else if (screen != null && screen.kind() == ScreenKind.SLIDE_END) {
                open = false;
            }
        }
        return open;
    }

    public static List<Item> withNewOrder(List<Item> items, int index, Actor actor) {
        var slots = slots(items);
        slots.get(index).put(new Carried(Part.ORDER, 0, null, new Order(actor)));
        return result(slots, List.of()).items();
    }

    public static Moved withNewOrder(List<Item> items, double seconds, Actor actor, double reach) {
        var slots = slots(items);
        var part = new Carried(Part.ORDER, seconds, null, new Order(actor));
        place(slots, part, reach);
        return result(slots, List.of(part));
    }

    public static int freeIndex(List<Item> items) {
        return freeIndex(items, List.of());
    }

    public static int freeIndex(List<Item> items, List<CameraPath> paths) {
        var taken = new TreeSet<Integer>();
        for (var item : items) {
            for (var order : item.actors()) {
                taken.add(order.index());
                taken.add(order.target());
                taken.add(order.watches());
            }
            if (item.camera() != null) {
                taken.add(item.camera().target());
            }
        }
        for (var path : paths) {
            for (var key : path.getKeys()) {
                taken.add(key.lookAt());
            }
        }
        var index = 1;
        while (taken.contains(index)) {
            index++;
        }
        return index;
    }

    public static double shift(List<Item> items, Collection<Key> keys, double seconds, double length) {
        var earliest = Double.POSITIVE_INFINITY;
        var latest = 0.0;
        for (var key : keys) {
            var at = seconds(items, key);
            if (!Double.isNaN(at)) {
                earliest = Math.min(earliest, at);
                latest = Math.max(latest, at);
            }
        }
        if (earliest == Double.POSITIVE_INFINITY) {
            return 0;
        }
        return Math.clamp(seconds, -earliest, Math.max(-earliest, length - latest));
    }

    public static Moved move(List<Item> items, Collection<Key> keys, double seconds, double reach) {
        var slots = slots(items);
        var carried = new ArrayList<Carried>();
        for (var key : new TreeSet<>(keys).descendingSet()) {
            if (holds(items, key)) {
                carried.addFirst(
                        take(slots.get(key.item()), key, items.get(key.item()).seconds() + seconds));
            }
        }
        for (var part : carried) {
            place(slots, part, reach);
        }
        return result(slots, carried);
    }

    public static List<Item> remove(List<Item> items, Collection<Key> keys) {
        var slots = slots(items);
        for (var key : new TreeSet<>(keys).descendingSet()) {
            if (holds(items, key)) {
                take(slots.get(key.item()), key, 0);
            }
        }
        return result(slots, List.of()).items();
    }

    public static List<Item> withShot(List<Item> items, int index, Camera shot) {
        var old = items.get(index);
        return with(
                items,
                index,
                new Item(
                        old.seconds(),
                        old.description(),
                        shot,
                        old.actors(),
                        old.screen(),
                        old.music(),
                        old.playRate(),
                        old.source()));
    }

    public static List<Item> withScreen(List<Item> items, int index, Screen screen) {
        var old = items.get(index);
        return with(
                items,
                index,
                new Item(
                        old.seconds(),
                        old.description(),
                        old.camera(),
                        old.actors(),
                        screen,
                        old.music(),
                        old.playRate(),
                        old.source()));
    }

    public static List<Item> withMusic(List<Item> items, int index, Music music) {
        var old = items.get(index);
        return with(
                items,
                index,
                new Item(
                        old.seconds(),
                        old.description(),
                        old.camera(),
                        old.actors(),
                        old.screen(),
                        music,
                        old.playRate(),
                        old.source()));
    }

    public static List<Item> withOrder(List<Item> items, int index, int order, Actor actor) {
        var old = items.get(index);
        var actors = new ArrayList<>(old.actors());
        actors.set(order, actor);
        return with(
                items,
                index,
                new Item(
                        old.seconds(),
                        old.description(),
                        old.camera(),
                        actors,
                        old.screen(),
                        old.music(),
                        old.playRate(),
                        old.source()));
    }

    private static boolean isWide(Item item) {
        return item.camera() != null && item.camera().wide();
    }

    private static Item withWide(Item item, boolean on) {
        var shot = item.camera();
        if (shot == null ? !on : shot.wide() == on) {
            return item;
        }
        return new Item(
                item.seconds(),
                item.description(),
                shot == null ? Camera.blank(true) : shot.withWide(on),
                item.actors(),
                item.screen(),
                item.music(),
                item.playRate(),
                item.source());
    }

    private static boolean isNear(List<Item> items, double seconds, double reach) {
        for (var item : items) {
            if (Math.abs(item.seconds() - seconds) <= reach) {
                return true;
            }
        }
        return false;
    }

    private static void insert(List<Item> items, Item item) {
        var after = 0;
        while (after < items.size() && items.get(after).seconds() <= item.seconds()) {
            after++;
        }
        items.add(after, item);
    }

    private static List<Item> tidied(List<Item> items) {
        var kept = new ArrayList<Item>(items.size());
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var inner = index > 0 && index < items.size() - 1;
            var repeats = inner
                    && item.quiet()
                    && item.source().isEmpty()
                    && item.description().isEmpty()
                    && isWide(item) == isWide(kept.getLast());
            if (!repeats) {
                kept.add(item);
            }
        }
        return kept;
    }

    private static List<Item> with(List<Item> items, int index, Item item) {
        var next = new ArrayList<>(items);
        next.set(index, item);
        return next;
    }

    private static List<Slot> slots(List<Item> items) {
        var slots = new ArrayList<Slot>(items.size());
        for (var item : items) {
            slots.add(new Slot(item));
        }
        return slots;
    }

    private static Carried take(Slot slot, Key key, double seconds) {
        slot.left = true;
        return switch (key.part()) {
            case SHOT -> {
                var shot = slot.camera;
                slot.camera = Camera.blank(shot.wide());
                yield new Carried(key.part(), seconds, shot, null);
            }
            case SCREEN -> {
                var screen = slot.screen;
                slot.screen = null;
                yield new Carried(key.part(), seconds, screen, null);
            }
            case MUSIC -> {
                var music = slot.music;
                slot.music = null;
                yield new Carried(key.part(), seconds, music, null);
            }
            case ORDER -> {
                var order = slot.orders.remove(key.order());
                yield new Carried(key.part(), seconds, null, order);
            }
        };
    }

    private static void place(List<Slot> slots, Carried part, double reach) {
        Slot taken = null;
        Slot free = null;
        var nearest = Double.POSITIVE_INFINITY;
        for (var slot : slots) {
            var distance = Math.abs(slot.seconds - part.seconds);
            if (distance > reach) {
                continue;
            }
            taken = slot;
            if (slot.takes(part.part) && distance <= nearest) {
                nearest = distance;
                free = slot;
            }
        }
        if (free == null) {
            var seconds = taken == null ? part.seconds : taken.seconds;
            free = new Slot(seconds, areSlotsWideAt(slots, seconds));
            var after = 0;
            while (after < slots.size() && slots.get(after).seconds <= seconds) {
                after++;
            }
            slots.add(after, free);
        }
        free.put(part);
        part.into = free;
    }

    private static void placeAfter(List<Slot> slots, Slot before, Carried part) {
        var free = new Slot(before.seconds, before.isWide());
        slots.add(slots.indexOf(before) + 1, free);
        free.put(part);
        part.into = free;
    }

    private static boolean areSlotsWideAt(List<Slot> slots, double seconds) {
        var wide = false;
        for (var slot : slots) {
            if (slot.seconds > seconds) {
                break;
            }
            wide = slot.isWide();
        }
        return wide;
    }

    private static Moved result(List<Slot> slots, List<Carried> carried) {
        var kept = new ArrayList<Slot>(slots.size());
        for (var index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            var wideBefore = !kept.isEmpty() && kept.getLast().isWide();
            var last = index == slots.size() - 1;
            if (last || !slot.isEmptied() || slot.isWide() != wideBefore) {
                kept.add(slot);
            }
        }
        var items = new ArrayList<Item>(kept.size());
        for (var slot : kept) {
            items.add(slot.getItem());
        }
        var keys = new TreeSet<Key>();
        for (var part : carried) {
            var at = kept.indexOf(part.into);
            if (at >= 0) {
                keys.add(new Key(part.part, at, part.order == null ? 0 : part.into.orders.indexOf(part.order)));
            }
        }
        return new Moved(items, keys);
    }

    private static final class Carried {
        final Part part;
        final double seconds;
        final @Nullable Object value;
        final @Nullable Order order;

        @Nullable
        Slot into;

        Carried(Part part, double seconds, @Nullable Object value, @Nullable Order order) {
            this.part = part;
            this.seconds = seconds;
            this.value = value;
            this.order = order;
        }
    }

    private static final class Order {
        final Actor actor;

        Order(Actor actor) {
            this.actor = actor;
        }
    }

    private static final class Slot {
        final double seconds;
        final String description;
        final double playRate;
        final String source;
        final List<Order> orders = new ArrayList<>();

        @Nullable
        Camera camera;

        @Nullable
        Screen screen;

        @Nullable
        Music music;

        boolean left;

        Slot(Item item) {
            seconds = item.seconds();
            description = item.description();
            playRate = item.playRate();
            source = item.source();
            camera = item.camera();
            screen = item.screen();
            music = item.music();
            for (var actor : item.actors()) {
                orders.add(new Order(actor));
            }
        }

        Slot(double seconds, boolean wide) {
            this.seconds = seconds;
            description = "";
            playRate = 0;
            source = "";
            camera = Camera.blank(wide);
        }

        boolean isWide() {
            return camera != null && camera.wide();
        }

        boolean takes(Part part) {
            return switch (part) {
                case SHOT -> camera == null || camera.blank();
                case SCREEN -> screen == null;
                case MUSIC -> music == null;
                case ORDER -> true;
            };
        }

        void put(Carried part) {
            switch (part.part) {
                case SHOT -> camera = (Camera) part.value;
                case SCREEN -> screen = (Screen) part.value;
                case MUSIC -> music = (Music) part.value;
                case ORDER -> orders.add(place(part.order.actor.player()), part.order);
            }
        }

        boolean isEmptied() {
            return left
                    && source.isEmpty()
                    && description.isEmpty()
                    && orders.isEmpty()
                    && screen == null
                    && music == null
                    && (camera == null || camera.blank());
        }

        Item getItem() {
            var actors = new ArrayList<Actor>(orders.size());
            for (var order : orders) {
                actors.add(order.actor);
            }
            return new Item(seconds, description, camera, actors, screen, music, playRate, source);
        }

        private int place(boolean player) {
            if (player) {
                return orders.size();
            }
            var at = 0;
            while (at < orders.size() && !orders.get(at).actor.player()) {
                at++;
            }
            return at;
        }
    }
}
