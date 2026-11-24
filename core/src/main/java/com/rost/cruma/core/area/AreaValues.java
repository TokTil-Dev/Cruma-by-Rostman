package com.rost.cruma.core.area;

import com.rost.cruma.core.spawn.TerritoryPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class AreaValues {
    private static final String NUMBER = "(-?\\d+(?:\\.\\d*)?(?:[eE][+-]?\\d+)?)";
    private static final Pattern WATER = Pattern.compile("\\{\\s*Min\\s*:\\s*\\(\\s*"
            + NUMBER
            + "\\s+"
            + NUMBER
            + "\\s+"
            + NUMBER
            + "\\s*\\)\\s*Max\\s*:\\s*\\(\\s*"
            + NUMBER
            + "\\s+"
            + NUMBER
            + "\\s+"
            + NUMBER
            + "\\s*\\)\\s*}");
    private static final Pattern TIME = Pattern.compile("(\\d+)(sec|min|hour|day)?");

    private AreaValues() {}

    record Node(@Nullable String atom, List<Node> items) {
        boolean isList() {
            return atom == null;
        }
    }

    static @Nullable Node parseTree(String value) {
        var stack = new ArrayList<List<Node>>();
        stack.add(new ArrayList<>());
        var atom = new StringBuilder();
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c == '{' || c == '}' || c == ';' || Character.isWhitespace(c)) {
                if (!atom.isEmpty()) {
                    stack.getLast().add(new Node(atom.toString(), List.of()));
                    atom.setLength(0);
                }
                if (c == '{') {
                    stack.add(new ArrayList<>());
                } else if (c == '}') {
                    if (stack.size() < 2) {
                        return null;
                    }
                    var items = stack.removeLast();
                    stack.getLast().add(new Node(null, List.copyOf(items)));
                }
            } else {
                atom.append(c);
            }
        }
        if (!atom.isEmpty()) {
            stack.getLast().add(new Node(atom.toString(), List.of()));
        }
        if (stack.size() != 1) {
            return null;
        }
        var top = stack.getFirst();
        return top.size() == 1 ? top.getFirst() : new Node(null, List.copyOf(top));
    }

    static @Nullable List<int[]> parseTuples(String value, int arity) {
        var tree = parseTree(value);
        if (tree == null || !tree.isList()) {
            return null;
        }
        return parseTuples(tree, arity);
    }

    static @Nullable List<TerritoryPoint> parsePoints(String value) {
        var tuples = parseTuples(value, 4);
        return tuples == null ? null : toPoints(tuples);
    }

    static @Nullable List<List<TerritoryPoint>> parsePolygons(String value) {
        var tree = parseTree(value);
        if (tree == null || !tree.isList()) {
            return null;
        }
        var polygons = new ArrayList<List<TerritoryPoint>>();
        for (var item : tree.items()) {
            if (!item.isList()) {
                return null;
            }
            var tuples = parseTuples(item, 4);
            if (tuples == null) {
                return null;
            }
            polygons.add(toPoints(tuples));
        }
        return polygons;
    }

    static @Nullable List<AreaPoint> parsePlaces(String value) {
        var tuples = parseTuples(value, 3);
        if (tuples == null) {
            return null;
        }
        var places = new ArrayList<AreaPoint>(tuples.size());
        for (var tuple : tuples) {
            places.add(new AreaPoint(tuple[0], tuple[1], tuple[2]));
        }
        return places;
    }

    static @Nullable WaterBox parseWaterBox(String value) {
        var matcher = WATER.matcher(value.strip());
        if (!matcher.matches()) {
            return null;
        }
        var numbers = new double[6];
        for (var i = 0; i < 6; i++) {
            numbers[i] = Double.parseDouble(matcher.group(i + 1));
        }
        return new WaterBox(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5]);
    }

    static int @Nullable [] parseInts(String value) {
        var tree = parseTree(value);
        if (tree == null) {
            return null;
        }
        var atoms = tree.isList() ? tree.items() : List.of(tree);
        var numbers = new int[atoms.size()];
        for (var i = 0; i < atoms.size(); i++) {
            var number = atoms.get(i).isList() ? null : parseNumber(atoms.get(i).atom());
            if (number == null) {
                return null;
            }
            numbers[i] = (int) (double) number;
        }
        return numbers;
    }

    static long parseSeconds(String value) {
        var parts = value.strip().split("\\s+");
        var total = 0L;
        for (var part : parts) {
            var matcher = TIME.matcher(part);
            if (!matcher.matches()) {
                return -1;
            }
            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
            var unit = matcher.group(2);
            total += amount
                    * (unit == null || unit.equals("sec")
                            ? 1
                            : switch (unit) {
                                case "min" -> 60;
                                case "hour" -> 3600;
                                default -> 86400;
                            });
        }
        return total;
    }

    static @Nullable Double parseNumber(@Nullable String atom) {
        if (atom == null || atom.isEmpty()) {
            return null;
        }
        var c = atom.charAt(0);
        if (!(c == '-' || c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Double.parseDouble(atom);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable List<int[]> parseTuples(Node list, int arity) {
        var tuples = new ArrayList<int[]>();
        for (var item : list.items()) {
            if (!item.isList() || item.items().size() != arity) {
                return null;
            }
            var tuple = new int[arity];
            for (var i = 0; i < arity; i++) {
                var number = parseNumber(item.items().get(i).atom());
                if (number == null) {
                    return null;
                }
                tuple[i] = (int) (double) number;
            }
            tuples.add(tuple);
        }
        return tuples;
    }

    private static List<TerritoryPoint> toPoints(List<int[]> tuples) {
        var points = new ArrayList<TerritoryPoint>(tuples.size());
        for (var tuple : tuples) {
            points.add(new TerritoryPoint(tuple[0], tuple[1], tuple[2], tuple[3]));
        }
        return points;
    }
}
