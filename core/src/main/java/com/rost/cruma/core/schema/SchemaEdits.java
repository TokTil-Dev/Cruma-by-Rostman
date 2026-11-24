package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.Schema.Edit;
import com.rost.cruma.core.schema.Schema.Field;
import com.rost.cruma.core.schema.Schema.If;
import com.rost.cruma.core.schema.Schema.ListOf;
import com.rost.cruma.core.schema.Schema.Parts;
import com.rost.cruma.core.schema.Schema.Statement;
import com.rost.cruma.core.schema.Schema.Struct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SchemaEdits {
    private SchemaEdits() {}

    private record Match(Statement statement, List<String> path, List<Side> sides) {}

    private record Side(If branch, boolean then) {}

    private record Tree(List<Statement> body, Map<String, Struct> structs) {}

    static Parts apply(Parts base, Parts like) {
        var structs = new LinkedHashMap<>(base.structs());
        for (var struct : like.structs().values()) {
            if (structs.containsKey(struct.name())) {
                throw struct.where()
                        .error("struct " + struct.name() + " is in \"" + base.title()
                                + "\" already. Change its fields with after, before, replace or remove, as in after "
                                + struct.name() + ".field");
            }
            structs.put(struct.name(), struct);
        }
        var tree = new Tree(base.body(), structs);
        for (var edit : like.edits()) {
            tree = apply(tree, edit, base.title());
        }
        var constants = new LinkedHashMap<>(base.constants());
        constants.putAll(like.constants());
        return new Parts(
                like.kind(),
                like.target(),
                like.title(),
                like.like(),
                like.likeWhere(),
                Collections.unmodifiableMap(constants),
                tree.body(),
                Collections.unmodifiableMap(tree.structs()),
                List.of());
    }

    private static Tree apply(Tree tree, Edit edit, String baseTitle) {
        var matches = new ArrayList<Match>();
        var all = new LinkedHashSet<String>();
        find(tree.body(), List.of(), edit.path(), matches, all);
        for (var struct : tree.structs().values()) {
            find(struct.body(), List.of(struct.name()), edit.path(), matches, all);
        }
        var anchor = String.join(".", edit.path());
        var head = edit.kind().word + " \"" + anchor + "\": ";
        if (matches.isEmpty()) {
            throw edit.where()
                    .error(head + "\"" + baseTitle + "\" has no field of this name."
                            + Suggestions.suggest(anchor, all));
        }
        if (matches.size() > 1 && !areOnOtherSides(matches)) {
            var paths = new ArrayList<String>();
            for (var match : matches) {
                paths.add(String.join(".", match.path()));
            }
            var shown = new ArrayList<String>();
            for (var i = 0; i < matches.size(); i++) {
                var path = paths.get(i);
                shown.add(
                        paths.indexOf(path) != paths.lastIndexOf(path)
                                ? path + " (line "
                                        + matches.get(i).statement().where().line() + ")"
                                : path);
            }
            throw edit.where()
                    .error(head + matches.size() + " fields have this name: " + String.join(", ", shown)
                            + ". Write the one you mean");
        }
        var targets = Collections.<Statement>newSetFromMap(new IdentityHashMap<>());
        for (var match : matches) {
            targets.add(match.statement());
        }
        var body = rewrite(tree.body(), targets, edit);
        var structs = new LinkedHashMap<String, Struct>();
        for (var struct : tree.structs().values()) {
            structs.put(
                    struct.name(), new Struct(struct.where(), struct.name(), rewrite(struct.body(), targets, edit)));
        }
        return new Tree(body, structs);
    }

    private static boolean areOnOtherSides(List<Match> matches) {
        for (var i = 0; i < matches.size(); i++) {
            for (var j = i + 1; j < matches.size(); j++) {
                if (!areOnOtherSides(matches.get(i).sides(), matches.get(j).sides())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean areOnOtherSides(List<Side> first, List<Side> second) {
        for (var side : first) {
            for (var other : second) {
                if (side.branch() == other.branch() && side.then() != other.then()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void find(
            List<Statement> statements, List<String> prefix, List<String> anchor, List<Match> into, Set<String> all) {
        find(statements, prefix, List.of(), anchor, into, all);
    }

    private static void find(
            List<Statement> statements,
            List<String> prefix,
            List<Side> sides,
            List<String> anchor,
            List<Match> into,
            Set<String> all) {
        for (var statement : statements) {
            switch (statement) {
                case Field field -> {
                    if (field.name() != null) {
                        check(statement, append(prefix, field.name()), sides, anchor, into, all);
                    }
                }
                case ListOf list -> {
                    var path = prefix;
                    if (list.name() != null) {
                        path = append(prefix, list.name());
                        check(statement, path, sides, anchor, into, all);
                    }
                    find(list.body(), path, List.of(), anchor, into, all);
                }
                case If branch -> {
                    find(branch.then(), prefix, append(sides, new Side(branch, true)), anchor, into, all);
                    find(branch.otherwise(), prefix, append(sides, new Side(branch, false)), anchor, into, all);
                }
                default -> {}
            }
        }
    }

    private static void check(
            Statement statement,
            List<String> path,
            List<Side> sides,
            List<String> anchor,
            List<Match> into,
            Set<String> all) {
        all.add(path.getLast());
        all.add(String.join(".", path));
        if (path.size() >= anchor.size()
                && path.subList(path.size() - anchor.size(), path.size()).equals(anchor)) {
            into.add(new Match(statement, path, sides));
        }
    }

    private static <T> List<T> append(List<T> prefix, T last) {
        var path = new ArrayList<>(prefix);
        path.add(last);
        return path;
    }

    private static List<Statement> rewrite(List<Statement> statements, Set<Statement> targets, Edit edit) {
        var out = new ArrayList<Statement>();
        for (var statement : statements) {
            if (targets.contains(statement)) {
                switch (edit.kind()) {
                    case AFTER -> {
                        out.add(statement);
                        out.addAll(edit.body());
                    }
                    case BEFORE -> {
                        out.addAll(edit.body());
                        out.add(statement);
                    }
                    case REPLACE -> out.addAll(edit.body());
                    case REMOVE -> {}
                }
                continue;
            }
            out.add(
                    switch (statement) {
                        case ListOf list
                        when !list.body().isEmpty() ->
                            new ListOf(
                                    list.where(),
                                    list.count(),
                                    list.element(),
                                    rewrite(list.body(), targets, edit),
                                    list.name());
                        case If branch ->
                            new If(
                                    branch.where(),
                                    branch.condition(),
                                    rewrite(branch.then(), targets, edit),
                                    rewrite(branch.otherwise(), targets, edit));
                        default -> statement;
                    });
        }
        return List.copyOf(out);
    }
}
