package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.Schema.Basic;
import com.rost.cruma.core.schema.Schema.Field;
import com.rost.cruma.core.schema.Schema.If;
import com.rost.cruma.core.schema.Schema.ListOf;
import com.rost.cruma.core.schema.Schema.Parts;
import com.rost.cruma.core.schema.Schema.Statement;
import com.rost.cruma.core.schema.Schema.TypeRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class SchemaKinds {
    private final Parts parts;
    private final Set<String> wanted;
    private final List<Found> found = new ArrayList<>();

    private SchemaKinds(Parts parts, Set<String> wanted) {
        this.parts = parts;
        this.wanted = wanted;
    }

    private record Found(
            String name, ValueKind kind, @Nullable Basic basic, boolean always) {}

    static @Nullable String findProblem(Schema schema, TableSpec spec) {
        var kinds = new SchemaKinds(schema.getParts(), spec.getWanted());
        kinds.found.addAll(kinds.walk(schema.getParts().body(), true, 0));
        var missing = new ArrayList<String>();
        for (var name : spec.required()) {
            var seen = false;
            for (var field : kinds.found) {
                if (field.name().equals(name)) {
                    seen = true;
                    if (!field.always()) {
                        return "its field " + name + " is read only on one side of an if, and Cruma needs it in every"
                                + " row";
                    }
                }
            }
            if (!seen) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            return (missing.size() == 1 ? "it has no field " : "it has no fields ") + String.join(", ", missing)
                    + ", which Cruma needs";
        }
        for (var field : kinds.found) {
            var allowed = spec.kinds().get(field.name());
            if (allowed != null && !allowed.isEmpty() && !fits(field, allowed)) {
                var needed = new ArrayList<String>();
                for (var kind : allowed) {
                    needed.add(kind.getWords());
                }
                return "its field " + field.name() + " is " + describe(field) + ", and Cruma needs "
                        + String.join(" or ", needed);
            }
        }
        return null;
    }

    private List<Found> walk(List<Statement> statements, boolean kept, int depth) {
        var group = new ArrayList<Found>();
        for (var statement : statements) {
            switch (statement) {
                case Field field -> walkField(field, kept, depth, group);
                case ListOf list -> walkList(list, kept, depth, group);
                case If branch -> {
                    var then = walk(branch.then(), kept, depth);
                    var otherwise = walk(branch.otherwise(), kept, depth);
                    addSides(then, otherwise, group);
                    addSides(otherwise, then, group);
                }
                default -> {}
            }
        }
        return group;
    }

    private void walkField(Field field, boolean kept, int depth, List<Found> group) {
        var name = field.name();
        var seen = kept && name != null && wanted.contains(name);
        var basic = field.type().basic();
        if (basic != null) {
            if (seen) {
                group.add(new Found(name, kindOf(basic), basic, true));
            }
            return;
        }
        if (seen) {
            group.add(new Found(name, ValueKind.STRUCT, null, true));
        }
        walkStruct(field.type(), seen, depth);
    }

    private void walkList(ListOf list, boolean kept, int depth, List<Found> group) {
        var name = list.name();
        var rows = depth == 0 && "records".equals(name);
        var seen = !rows && kept && name != null && wanted.contains(name);
        var element = list.element();
        if (element != null && element.basic() != null) {
            if (seen) {
                var kind =
                        element.basic().isText() || element.basic() == Basic.NAME ? ValueKind.TEXTS : ValueKind.NUMBERS;
                group.add(new Found(name, kind, element.basic(), true));
            }
            return;
        }
        if (seen) {
            group.add(new Found(name, ValueKind.GROUPS, null, true));
        }
        var inner = rows || seen;
        if (element != null) {
            walkStruct(element, inner, depth);
        } else if (depth < SchemaParser.MAX_DEPTH) {
            var fields = walk(list.body(), inner, depth + 1);
            if (inner) {
                found.addAll(fields);
            }
        }
    }

    private void walkStruct(TypeRef type, boolean kept, int depth) {
        var struct = parts.structs().get(type.struct());
        if (struct != null && depth < SchemaParser.MAX_DEPTH) {
            var fields = walk(struct.body(), kept, depth + 1);
            if (kept) {
                found.addAll(fields);
            }
        }
    }

    private static void addSides(List<Found> side, List<Found> other, List<Found> into) {
        var names = new HashSet<String>();
        for (var field : other) {
            if (field.always()) {
                names.add(field.name());
            }
        }
        for (var field : side) {
            into.add(new Found(
                    field.name(), field.kind(), field.basic(), field.always() && names.contains(field.name())));
        }
    }

    private static ValueKind kindOf(Basic basic) {
        if (basic.isText() || basic == Basic.NAME) {
            return ValueKind.TEXT;
        }
        return basic.whole && basic != Basic.LONG ? ValueKind.WHOLE : ValueKind.NUMBER;
    }

    private static boolean fits(Found field, Set<ValueKind> allowed) {
        return switch (field.kind()) {
            case WHOLE -> allowed.contains(ValueKind.WHOLE) || allowed.contains(ValueKind.NUMBER);
            default -> allowed.contains(field.kind());
        };
    }

    private static String describe(Found field) {
        var basic = field.basic();
        if (basic == null) {
            return field.kind().getWords();
        }
        var word = basic.word;
        if (field.kind() == ValueKind.TEXTS || field.kind() == ValueKind.NUMBERS) {
            return "a list of " + word;
        }
        var article = word.startsWith("int") || word.startsWith("fstring") || word.startsWith("ansi") ? "an " : "a ";
        return article + word;
    }
}
