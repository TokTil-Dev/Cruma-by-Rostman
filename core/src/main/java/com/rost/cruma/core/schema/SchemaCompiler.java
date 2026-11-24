package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.Program.BothTest;
import com.rost.cruma.core.schema.Program.CompareTest;
import com.rost.cruma.core.schema.Program.CountSpec;
import com.rost.cruma.core.schema.Program.EitherTest;
import com.rost.cruma.core.schema.Program.Group;
import com.rost.cruma.core.schema.Program.GroupOp;
import com.rost.cruma.core.schema.Program.IfOp;
import com.rost.cruma.core.schema.Program.ListOp;
import com.rost.cruma.core.schema.Program.MergedSkipOp;
import com.rost.cruma.core.schema.Program.Op;
import com.rost.cruma.core.schema.Program.Ref;
import com.rost.cruma.core.schema.Program.RowsOp;
import com.rost.cruma.core.schema.Program.ScalarOp;
import com.rost.cruma.core.schema.Program.SkipOp;
import com.rost.cruma.core.schema.Program.Test;
import com.rost.cruma.core.schema.Schema.Basic;
import com.rost.cruma.core.schema.Schema.Both;
import com.rost.cruma.core.schema.Schema.Compare;
import com.rost.cruma.core.schema.Schema.Condition;
import com.rost.cruma.core.schema.Schema.Count;
import com.rost.cruma.core.schema.Schema.Either;
import com.rost.cruma.core.schema.Schema.Field;
import com.rost.cruma.core.schema.Schema.FieldCount;
import com.rost.cruma.core.schema.Schema.FixedCount;
import com.rost.cruma.core.schema.Schema.If;
import com.rost.cruma.core.schema.Schema.Kind;
import com.rost.cruma.core.schema.Schema.ListOf;
import com.rost.cruma.core.schema.Schema.Parts;
import com.rost.cruma.core.schema.Schema.ReadCount;
import com.rost.cruma.core.schema.Schema.Skip;
import com.rost.cruma.core.schema.Schema.Statement;
import com.rost.cruma.core.schema.Schema.Struct;
import com.rost.cruma.core.schema.Schema.TypeRef;
import com.rost.cruma.core.schema.Schema.UseNames;
import com.rost.cruma.core.schema.Schema.Where;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class SchemaCompiler {
    static final int MAX_FIELDS = 10_000;

    private static final List<String> CONTEXT_NAMES = List.of("version", "licensee");

    private final Parts parts;
    private final @Nullable Set<String> wanted;
    private final Set<String> referenced = new HashSet<>();
    private final Set<String> names = new LinkedHashSet<>();
    private final List<String> structPath = new ArrayList<>();
    private final Map<String, ListOp> topLists = new HashMap<>();
    private int fields;
    private boolean ownNames;
    private boolean gameNames;
    private boolean hasRows;

    private SchemaCompiler(Parts parts, @Nullable Set<String> wanted) {
        this.parts = parts;
        this.wanted = wanted;
    }

    record Facts(Set<String> names, boolean gameNames, int fields) {}

    static Facts check(Parts parts) {
        var compiler = new SchemaCompiler(parts, null);
        compiler.compileAll();
        compiler.names.addAll(parts.constants().keySet());
        return new Facts(Set.copyOf(compiler.names), compiler.gameNames, compiler.fields);
    }

    static Program compile(Parts parts, Set<String> wanted) {
        var top = new SchemaCompiler(parts, wanted).compileAll();
        return new Program(top, parts.kind() == Kind.CLASS ? CONTEXT_NAMES : List.of());
    }

    private Group compileAll() {
        checkStructs();
        collectReferences(parts.body());
        for (var struct : parts.structs().values()) {
            collectReferences(struct.body());
        }
        var scope = new Scope(null, true);
        var ops = compileGroup(parts.body(), scope, 0, false);
        if (parts.kind() == Kind.FILE && !hasRows) {
            throw new SchemaException(
                    parts.likeWhere().source(),
                    0,
                    "the schema has no rows. The rows are the list named records, as in list uint records {");
        }
        return toGroup(ops, scope);
    }

    private Op[] compileGroup(List<Statement> statements, Scope scope, int depth, boolean inRows) {
        var ops = new ArrayList<Op>();
        for (var statement : statements) {
            switch (statement) {
                case Field field -> ops.add(compileField(field, scope, depth, inRows));
                case ListOf list -> ops.add(compileList(list, scope, depth, inRows));
                case Skip skip -> {
                    countField(skip.where());
                    ops.add(new SkipOp(skip.where(), skip.bytes()));
                }
                case If branch -> ops.add(compileIf(branch, scope, depth, inRows));
                case UseNames use -> useNames(use, scope);
            }
        }
        return merge(ops);
    }

    private Op compileField(Field field, Scope scope, int depth, boolean inRows) {
        var where = field.where();
        countField(where);
        var type = field.type();
        var name = field.name();
        var basic = type.basic();
        if (basic != null) {
            var slot = name != null && basic.whole && referenced.contains(name) ? scope.allocateSlot(name) : -1;
            var key = keepName(scope, name);
            var op = new ScalarOp(
                    where, labelOf(name, type.word()), basic, type.word(), key, slot, readsOwnNames(basic));
            declare(scope, name, where, basic, "a " + basic.word + " field");
            return op;
        }
        var struct = findStruct(type, where);
        var key = keepName(scope, name);
        var inner = new Scope(scope, key != null);
        var group = compileStruct(struct, inner, depth, inRows, where);
        declare(scope, name, where, null, "a " + struct.name() + " struct");
        return new GroupOp(where, labelOf(name, struct.name()), struct.name(), key, group, leastOf(group.ops()));
    }

    private Op compileList(ListOf list, Scope scope, int depth, boolean inRows) {
        var where = list.where();
        countField(where);
        var count = compileCount(list.count(), scope, where);
        var name = list.name();
        var rows = scope.parent == null && "records".equals(name) && parts.kind() == Kind.FILE;
        var limit = inRows ? Program.ROW_LIST_LIMIT : Program.LIST_LIMIT;
        var label = name != null ? name : "(list)";
        var element = list.element();
        ListOp op;
        if (element != null && element.basic() != null) {
            if (rows) {
                throw where.error("records is the list of rows, so its items are groups of fields, as in list uint"
                        + " records {");
            }
            var basic = element.basic();
            var single = new ScalarOp(where, label, basic, element.word(), null, -1, readsOwnNames(basic));
            op = new ListOp(where, label, count, limit, basic.least, keepName(scope, name), single, null, false);
        } else {
            var key = rows ? null : keepName(scope, name);
            var inner = new Scope(scope, rows || key != null);
            Group group;
            if (element != null) {
                group = compileStruct(findStruct(element, where), inner, depth, inRows || rows, where);
            } else {
                checkDepth(depth, where);
                group = toGroup(compileGroup(list.body(), inner, depth + 1, inRows || rows), inner);
            }
            var least = leastOf(group.ops());
            if (rows) {
                hasRows = true;
                op = new RowsOp(where, count, least, group);
            } else {
                op = new ListOp(where, label, count, limit, least, key, null, group, false);
            }
        }
        declare(scope, name, where, null, "a list");
        if (scope.parent == null && name != null) {
            topLists.put(name, op);
        }
        return op;
    }

    private Op compileIf(If branch, Scope scope, int depth, boolean inRows) {
        var where = branch.where();
        countField(where);
        checkDepth(depth, where);
        var test = compileTest(branch.condition(), scope);
        var before = new HashMap<>(scope.visible);
        var then = compileGroup(branch.then(), scope, depth + 1, inRows);
        var afterThen = scope.visible;
        scope.visible = new HashMap<>(before);
        var otherwise = compileGroup(branch.otherwise(), scope, depth + 1, inRows);
        for (var entry : afterThen.entrySet()) {
            scope.visible.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new IfOp(where, test, then, otherwise, Math.min(leastOf(then), leastOf(otherwise)));
    }

    private Group compileStruct(Struct struct, Scope inner, int depth, boolean inRows, Where use) {
        checkDepth(depth, use);
        if (structPath.contains(struct.name())) {
            throw use.error("the struct " + struct.name() + " holds itself, and a struct cannot hold itself");
        }
        structPath.add(struct.name());
        try {
            return toGroup(compileGroup(struct.body(), inner, depth + 1, inRows), inner);
        } catch (SchemaException mistake) {
            if (mistake.getSource().equals(use.source()) && mistake.getLine() == use.line()
                    || mistake.getReason().endsWith(")") && mistake.getReason().contains(" (in the struct ")) {
                throw mistake;
            }
            throw new SchemaException(
                    mistake.getSource(),
                    mistake.getLine(),
                    mistake.getReason() + " (in the struct " + struct.name() + " used at line " + use.line() + ")");
        } finally {
            structPath.removeLast();
        }
    }

    private CountSpec compileCount(Count count, Scope scope, Where where) {
        return switch (count) {
            case FixedCount fixed -> new CountSpec(fixed.value(), null, null, String.valueOf(fixed.value()));
            case ReadCount read -> new CountSpec(0, read.type(), null, read.word());
            case FieldCount field -> new CountSpec(0, null, resolve(field.name(), scope, where, true), field.name());
        };
    }

    private Test compileTest(Condition condition, Scope scope) {
        return switch (condition) {
            case Compare compare ->
                new CompareTest(
                        resolve(compare.name(), scope, compare.where(), false), compare.operator(), compare.value());
            case Both both -> new BothTest(compileTest(both.left(), scope), compileTest(both.right(), scope));
            case Either either -> new EitherTest(compileTest(either.left(), scope), compileTest(either.right(), scope));
        };
    }

    private Ref resolve(String name, Scope scope, Where where, boolean count) {
        var up = 0;
        var known = new ArrayList<String>();
        for (var at = scope; at != null; at = at.parent, up++) {
            var declared = at.visible.get(name);
            if (declared != null) {
                if (declared.type() == null || !declared.type().whole) {
                    throw where.error(
                            count
                                    ? "the count \"" + name + "\" is " + declared.what()
                                            + ". Only a whole number can be a count"
                                    : "the condition uses \"" + name + "\", which is " + declared.what()
                                            + ". Only a whole number can be compared");
                }
                var slot = at.slots.get(name);
                if (slot == null) {
                    throw new IllegalStateException("no slot for " + name);
                }
                return new Ref(up, slot, -1, name);
            }
            known.addAll(at.visible.keySet());
        }
        if (parts.kind() == Kind.CLASS) {
            var context = CONTEXT_NAMES.indexOf(name);
            if (context >= 0) {
                return new Ref(0, -1, context, name);
            }
            known.addAll(CONTEXT_NAMES);
        }
        var suggestion = Suggestions.suggest(name, known);
        if (count) {
            throw where.error("the count \"" + name + "\" is not a field read before." + suggestion);
        }
        throw where.error("the condition uses \"" + name + "\", which is not a field read before." + suggestion);
    }

    private void useNames(UseNames use, Scope scope) {
        var where = use.where();
        var path = use.path();
        var list = scope.parent == null ? topLists.get(path.getFirst()) : null;
        if (list == null) {
            throw where.error("use names takes a list read before, and \"" + path.getFirst() + "\" is none."
                    + Suggestions.suggest(path.getFirst(), topLists.keySet()));
        }
        if (path.size() == 1) {
            if (list.single == null || !list.single.type.isText()) {
                throw where.error("use names takes a list of texts, or a list of groups and their text field, as in"
                        + " use names strings.text");
            }
            list.single.collect = true;
        } else {
            var field = list.group == null ? null : findText(list.group.ops(), path.get(1));
            if (field == null) {
                throw where.error("the groups of " + path.getFirst() + " have no text field \"" + path.get(1) + "\"");
            }
            field.collect = true;
        }
        ownNames = true;
    }

    private boolean readsOwnNames(Basic basic) {
        if (basic != Basic.NAME) {
            return false;
        }
        if (!ownNames) {
            gameNames = true;
        }
        return ownNames;
    }

    private @Nullable String keepName(Scope scope, @Nullable String name) {
        if (!scope.kept || name == null || wanted == null || !wanted.contains(name)) {
            return null;
        }
        scope.keptKeys.add(name);
        return name;
    }

    private void declare(Scope scope, @Nullable String name, Where where, @Nullable Basic type, String what) {
        if (name == null) {
            return;
        }
        names.add(name);
        var earlier = scope.visible.get(name);
        if (earlier != null) {
            var place = earlier.where().source().equals(where.source())
                    ? "line " + earlier.where().line()
                    : earlier.where().source() + ":" + earlier.where().line();
            throw where.error("the field \"" + name + "\" is already in this group, at " + place
                    + ". Give one of them another name");
        }
        scope.visible.put(name, new Declared(where, type, what));
    }

    private Struct findStruct(TypeRef type, Where where) {
        var struct = parts.structs().get(type.struct());
        if (struct == null) {
            var candidates = new ArrayList<String>(Schema.TYPE_WORDS.keySet());
            candidates.addAll(parts.structs().keySet());
            throw where.error("unknown type \"" + type.word() + "\"." + Suggestions.suggest(type.word(), candidates));
        }
        return struct;
    }

    private void countField(Where where) {
        if (++fields > MAX_FIELDS) {
            throw where.error("the schema has more than 10 000 fields once its structs are put in place");
        }
    }

    private void checkStructs() {
        var done = new HashSet<String>();
        for (var struct : parts.structs().values()) {
            visitStruct(struct, done, new ArrayList<>());
        }
    }

    private void visitStruct(Struct struct, Set<String> done, List<String> path) {
        if (done.contains(struct.name())) {
            return;
        }
        if (path.contains(struct.name())) {
            var circle = new ArrayList<>(path.subList(path.indexOf(struct.name()), path.size()));
            circle.add(struct.name());
            throw struct.where()
                    .error("the struct " + struct.name() + " holds itself: " + String.join(" > ", circle)
                            + ". A struct cannot hold itself");
        }
        if (path.size() >= SchemaParser.MAX_DEPTH) {
            throw struct.where().error("structs nest deeper than 32 levels");
        }
        path.add(struct.name());
        var used = new ArrayList<Map.Entry<TypeRef, Where>>();
        collectStructTypes(struct.body(), used);
        for (var entry : used) {
            visitStruct(findStruct(entry.getKey(), entry.getValue()), done, path);
        }
        path.removeLast();
        done.add(struct.name());
    }

    private void collectReferences(List<Statement> statements) {
        for (var statement : statements) {
            switch (statement) {
                case ListOf list -> {
                    if (list.count() instanceof FieldCount(var name)) {
                        referenced.add(name);
                    }
                    collectReferences(list.body());
                }
                case If branch -> {
                    collectNames(branch.condition(), referenced);
                    collectReferences(branch.then());
                    collectReferences(branch.otherwise());
                }
                default -> {}
            }
        }
    }

    private static void collectStructTypes(List<Statement> statements, List<Map.Entry<TypeRef, Where>> into) {
        for (var statement : statements) {
            switch (statement) {
                case Field field -> {
                    if (field.type().struct() != null) {
                        into.add(Map.entry(field.type(), field.where()));
                    }
                }
                case ListOf list -> {
                    if (list.element() != null && list.element().struct() != null) {
                        into.add(Map.entry(list.element(), list.where()));
                    }
                    collectStructTypes(list.body(), into);
                }
                case If branch -> {
                    collectStructTypes(branch.then(), into);
                    collectStructTypes(branch.otherwise(), into);
                }
                default -> {}
            }
        }
    }

    private static void collectNames(Condition condition, Set<String> into) {
        switch (condition) {
            case Compare compare -> into.add(compare.name());
            case Both both -> {
                collectNames(both.left(), into);
                collectNames(both.right(), into);
            }
            case Either either -> {
                collectNames(either.left(), into);
                collectNames(either.right(), into);
            }
        }
    }

    private static void checkDepth(int depth, Where where) {
        if (depth + 1 > SchemaParser.MAX_DEPTH) {
            throw where.error("fields nest deeper than 32 levels");
        }
    }

    private static @Nullable ScalarOp findText(Op[] ops, String name) {
        for (var op : ops) {
            if (op instanceof ScalarOp scalar && scalar.type.isText() && scalar.label.equals(name)) {
                return scalar;
            }
            if (op instanceof IfOp branch) {
                var found = findText(branch.then, name);
                if (found == null) {
                    found = findText(branch.otherwise, name);
                }
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String labelOf(@Nullable String name, String type) {
        return name != null ? name : "(" + type + ")";
    }

    private static Group toGroup(Op[] ops, Scope scope) {
        return new Group(ops, scope.slots.size(), scope.keptKeys.size());
    }

    private static long leastOf(Op[] ops) {
        var least = 0L;
        for (var op : ops) {
            least = Math.min(1L << 40, least + op.least);
        }
        return least;
    }

    private static Op[] merge(List<Op> ops) {
        var merged = new ArrayList<Op>();
        var run = new ArrayList<Op>();
        var bytes = 0L;
        for (var op : ops) {
            var size = plainSize(op);
            if (size >= 0 && bytes + size <= Integer.MAX_VALUE) {
                run.add(op);
                bytes += size;
                continue;
            }
            flush(merged, run, bytes);
            run.clear();
            bytes = 0;
            if (size >= 0) {
                run.add(op);
                bytes = size;
            } else {
                merged.add(op);
            }
        }
        flush(merged, run, bytes);
        return merged.toArray(Op[]::new);
    }

    private static void flush(List<Op> merged, List<Op> run, long bytes) {
        if (run.size() == 1) {
            merged.add(run.getFirst());
        } else if (run.size() > 1) {
            merged.add(new MergedSkipOp((int) bytes, run.toArray(Op[]::new)));
        }
    }

    private static int plainSize(Op op) {
        if (op instanceof ScalarOp scalar
                && scalar.key == null
                && scalar.slot < 0
                && !scalar.collect
                && scalar.type.size > 0
                && scalar.type != Basic.NAME) {
            return scalar.type.size;
        }
        if (op instanceof SkipOp skip) {
            return skip.bytes;
        }
        return -1;
    }

    private record Declared(Where where, @Nullable Basic type, String what) {}

    private static final class Scope {
        private final @Nullable Scope parent;
        private final boolean kept;
        private final Map<String, Integer> slots = new HashMap<>();
        private final Set<String> keptKeys = new HashSet<>();
        private Map<String, Declared> visible = new HashMap<>();

        private Scope(@Nullable Scope parent, boolean kept) {
            this.parent = parent;
            this.kept = kept;
        }

        private int allocateSlot(String name) {
            return slots.computeIfAbsent(name, unused -> slots.size());
        }
    }
}
