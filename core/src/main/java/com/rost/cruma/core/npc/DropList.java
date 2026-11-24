package com.rost.cruma.core.npc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DropList {
    public static final String DROP = "additional_make_multi_list";

    public static final String HERBS = "ex_item_drop_list";
    public static final String SPOIL = "corpse_make_list";
    public static final String EXTRA = "additional_make_list";

    public static final String MAKE = "item_make_list";

    public static final String ADENA = "adena";

    public static final double MINIMUM = 0.0001;

    private DropList() {}

    public record Item(String name, long min, long max, double chance) {
        public Item withName(String value) {
            return new Item(value, min, max, chance);
        }

        public Item withCount(long low, long high) {
            return new Item(name, low, high, chance);
        }

        public Item withChance(double value) {
            return new Item(name, min, max, value);
        }
    }

    public record Group(double chance, List<Item> items) {
        public Group {
            items = List.copyOf(items);
        }

        public double itemChanceSum() {
            var sum = 0.0;
            for (var item : items) {
                sum += item.chance();
            }
            return sum;
        }

        public Group withChance(double value) {
            return new Group(value, items);
        }

        public Group withItems(List<Item> value) {
            return new Group(chance, value);
        }

        public Group withItem(int index, Item item) {
            var copy = new ArrayList<>(items);
            if (index == copy.size()) {
                copy.add(item);
            } else {
                copy.set(index, item);
            }
            return new Group(chance, copy);
        }

        public Group withoutItem(int index) {
            var copy = new ArrayList<>(items);
            copy.remove(index);
            return new Group(chance, copy);
        }

        public Group normalized() {
            var sum = itemChanceSum();
            if (items.isEmpty() || sum <= 0) {
                return this;
            }
            var scaled = new ArrayList<Item>(items.size());
            var total = 0.0;
            for (var i = 0; i < items.size(); i++) {
                var item = items.get(i);
                var chance = i == items.size() - 1 ? round(100 - total) : round(item.chance() * 100 / sum);
                total += chance;
                scaled.add(item.withChance(chance));
            }
            return new Group(chance, scaled);
        }

        public Group normalizedKeeping(Set<Integer> fixed) {
            var fixedSum = 0.0;
            var freeSum = 0.0;
            var lastFree = -1;
            for (var i = 0; i < items.size(); i++) {
                if (fixed.contains(i)) {
                    fixedSum += items.get(i).chance();
                } else {
                    freeSum += items.get(i).chance();
                    lastFree = i;
                }
            }
            var target = 100 - fixedSum;
            if (freeSum <= 0 || target <= 0) {
                return normalized();
            }
            var scaled = new ArrayList<Item>(items.size());
            var total = 0.0;
            for (var i = 0; i < items.size(); i++) {
                var item = items.get(i);
                if (!fixed.contains(i) && i != lastFree) {
                    item = item.withChance(Math.max(MINIMUM, round(item.chance() * target / freeSum)));
                }
                scaled.add(item);
                total += i == lastFree ? 0 : item.chance();
            }
            scaled.set(lastFree, items.get(lastFree).withChance(Math.max(MINIMUM, round(100 - total))));
            return new Group(chance, scaled);
        }

        public Group withAddedItem(Item item, Set<Integer> fixed) {
            var left = round(100 - itemChanceSum());
            if (left >= MINIMUM) {
                return withItem(items.size(), item.withChance(left));
            }
            var keep = new HashSet<>(fixed);
            keep.add(items.size());
            return withItem(items.size(), item.withChance(round(100.0 / (items.size() + 1))))
                    .normalizedKeeping(keep);
        }

        public Group rebalanced(int left, double share) {
            var a = items.get(left);
            var b = items.get(left + 1);
            var pair = a.chance() + b.chance();
            var low = Math.min(MINIMUM, pair / 2);
            var first = round(Math.clamp(share, low, pair - low));
            var copy = new ArrayList<>(items);
            copy.set(left, a.withChance(first));
            copy.set(left + 1, b.withChance(round(pair - first)));
            return new Group(chance, copy);
        }

        public double[] expectedPieces() {
            var pieces = new double[items.size()];
            for (var i = 0; i < items.size(); i++) {
                pieces[i] = chance / 100 * DropList.expectedPieces(items.get(i));
            }
            return pieces;
        }
    }

    public record Expected(double adena, double drops) {
        public Expected times(double kills) {
            return new Expected(adena * kills, drops * kills);
        }
    }

    public static Expected expectedGroups(List<Group> groups) {
        var adena = 0.0;
        var drops = 0.0;
        for (var group : groups) {
            var pieces = group.expectedPieces();
            for (var i = 0; i < pieces.length; i++) {
                var item = group.items().get(i);
                if (item.name().equals(ADENA)) {
                    adena += pieces[i];
                } else {
                    drops += group.chance() / 100 * item.chance() / 100;
                }
            }
        }
        return new Expected(adena, drops);
    }

    public static Expected expectedItems(List<Item> items) {
        var adena = 0.0;
        var drops = 0.0;
        for (var item : items) {
            if (item.name().equals(ADENA)) {
                adena += expectedPieces(item);
            } else {
                drops += item.chance() / 100;
            }
        }
        return new Expected(adena, drops);
    }

    public static double expectedPieces(Item item) {
        return item.chance() / 100 * (item.min() + item.max()) / 2.0;
    }

    public static boolean isGrouped(String key) {
        return key.equals(DROP) || key.equals(HERBS);
    }

    public static List<Group> parseGroups(String value) {
        var groups = new ArrayList<Group>();
        for (var element : ScriptValue.list(ScriptValue.parse(value))) {
            var parts = ScriptValue.list(element);
            if (parts.size() != 2) {
                throw new IllegalArgumentException("A drop group needs {items;chance}: " + element);
            }
            groups.add(new Group(ScriptValue.number(parts.get(1)), parseItems(parts.get(0))));
        }
        return groups;
    }

    public static List<Item> parseItems(String value) {
        return parseItems(ScriptValue.parse(value));
    }

    public static String formatGroups(List<Group> groups) {
        var out = new StringBuilder("{");
        for (var i = 0; i < groups.size(); i++) {
            if (i > 0) {
                out.append(';');
            }
            var group = groups.get(i);
            out.append('{')
                    .append(formatItems(group.items()))
                    .append(';')
                    .append(ScriptValue.number(group.chance()))
                    .append('}');
        }
        return out.append('}').toString();
    }

    public static String formatItems(List<Item> items) {
        var out = new StringBuilder("{");
        for (var i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(';');
            }
            var item = items.get(i);
            out.append("{[")
                    .append(item.name())
                    .append("];")
                    .append(item.min())
                    .append(';')
                    .append(item.max())
                    .append(';')
                    .append(ScriptValue.number(item.chance()))
                    .append('}');
        }
        return out.append('}').toString();
    }

    public static double round(double chance) {
        return Math.round(chance * 10_000) / 10_000.0;
    }

    private static List<Item> parseItems(Object value) {
        var items = new ArrayList<Item>();
        for (var element : ScriptValue.list(value)) {
            var parts = ScriptValue.list(element);
            if (parts.size() != 4) {
                throw new IllegalArgumentException("An item needs {[name];min;max;chance}: " + element);
            }
            items.add(new Item(
                    ScriptValue.bracketed(parts.get(0)),
                    ScriptValue.whole(parts.get(1)),
                    ScriptValue.whole(parts.get(2)),
                    ScriptValue.number(parts.get(3))));
        }
        return items;
    }
}
