package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.ScriptFunction.ARRAY_ELEMENT;
import static com.rost.cruma.core.editing.ScriptFunction.ASSERT;
import static com.rost.cruma.core.editing.ScriptFunction.BOOL_VARIABLE;
import static com.rost.cruma.core.editing.ScriptFunction.BYTE_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.CASE;
import static com.rost.cruma.core.editing.ScriptFunction.CLASS_CONTEXT;
import static com.rost.cruma.core.editing.ScriptFunction.CONTEXT;
import static com.rost.cruma.core.editing.ScriptFunction.DEFAULT_VARIABLE;
import static com.rost.cruma.core.editing.ScriptFunction.DYNAMIC_CAST;
import static com.rost.cruma.core.editing.ScriptFunction.DYN_ARRAY_ELEMENT;
import static com.rost.cruma.core.editing.ScriptFunction.DYN_ARRAY_INSERT;
import static com.rost.cruma.core.editing.ScriptFunction.DYN_ARRAY_LENGTH;
import static com.rost.cruma.core.editing.ScriptFunction.DYN_ARRAY_REMOVE;
import static com.rost.cruma.core.editing.ScriptFunction.EAT_STRING;
import static com.rost.cruma.core.editing.ScriptFunction.FALSE;
import static com.rost.cruma.core.editing.ScriptFunction.FINAL_FUNCTION;
import static com.rost.cruma.core.editing.ScriptFunction.FLOAT_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_DELEGATE;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_EVENT;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_EXEC;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_FINAL;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_ITERATOR;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_LATENT;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_NATIVE;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_OPERATOR;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_PRE_OPERATOR;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_PRIVATE;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_PROTECTED;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_SIMULATED;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_SINGULAR;
import static com.rost.cruma.core.editing.ScriptFunction.FUNC_STATIC;
import static com.rost.cruma.core.editing.ScriptFunction.GLOBAL_FUNCTION;
import static com.rost.cruma.core.editing.ScriptFunction.INSTANCE_VARIABLE;
import static com.rost.cruma.core.editing.ScriptFunction.INT_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.INT_CONST_BYTE;
import static com.rost.cruma.core.editing.ScriptFunction.INT_ONE;
import static com.rost.cruma.core.editing.ScriptFunction.INT_ZERO;
import static com.rost.cruma.core.editing.ScriptFunction.ITERATOR;
import static com.rost.cruma.core.editing.ScriptFunction.ITERATOR_NEXT;
import static com.rost.cruma.core.editing.ScriptFunction.ITERATOR_POP;
import static com.rost.cruma.core.editing.ScriptFunction.JUMP;
import static com.rost.cruma.core.editing.ScriptFunction.JUMP_IF_NOT;
import static com.rost.cruma.core.editing.ScriptFunction.LET;
import static com.rost.cruma.core.editing.ScriptFunction.LET_BOOL;
import static com.rost.cruma.core.editing.ScriptFunction.LOCAL_VARIABLE;
import static com.rost.cruma.core.editing.ScriptFunction.META_CAST;
import static com.rost.cruma.core.editing.ScriptFunction.NAME_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.NATIVE;
import static com.rost.cruma.core.editing.ScriptFunction.NATIVE_PARM;
import static com.rost.cruma.core.editing.ScriptFunction.NEW;
import static com.rost.cruma.core.editing.ScriptFunction.NOTHING;
import static com.rost.cruma.core.editing.ScriptFunction.NO_OBJECT;
import static com.rost.cruma.core.editing.ScriptFunction.OBJECT_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.PRIMITIVE_CAST;
import static com.rost.cruma.core.editing.ScriptFunction.RETURN;
import static com.rost.cruma.core.editing.ScriptFunction.ROTATION_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.SELF;
import static com.rost.cruma.core.editing.ScriptFunction.SKIP;
import static com.rost.cruma.core.editing.ScriptFunction.STRING_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.STRUCT_CMP_EQ;
import static com.rost.cruma.core.editing.ScriptFunction.STRUCT_CMP_NE;
import static com.rost.cruma.core.editing.ScriptFunction.STRUCT_MEMBER;
import static com.rost.cruma.core.editing.ScriptFunction.SWITCH;
import static com.rost.cruma.core.editing.ScriptFunction.TRUE;
import static com.rost.cruma.core.editing.ScriptFunction.UNICODE_STRING_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.VECTOR_CONST;
import static com.rost.cruma.core.editing.ScriptFunction.VIRTUAL_FUNCTION;

import com.rost.cruma.core.editing.ClassSchema.Field;
import com.rost.cruma.core.editing.ClassSchema.Signature;
import com.rost.cruma.core.editing.ScriptFunction.Node;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class FunctionSource {
    private static final String INDENT = "    ";
    private static final int ATOM = 0;
    private static final int NEW_PRECEDENCE = 1;
    private static final int MOST_FIELDS = 256;

    private static final int BYTE_TO_INT = 0x3A;
    private static final int BYTE_TO_FLOAT = 0x3C;
    private static final int INT_TO_FLOAT = 0x3F;
    private static final java.util.Set<Integer> NUMBERS = java.util.Set.of(0x3C, 0x3D, 0x3F, 0x43, 0x44);

    private static final Map<Integer, String> CASTS = Map.ofEntries(
            Map.entry(0x39, "vector"),
            Map.entry(0x3A, "int"),
            Map.entry(0x3B, "bool"),
            Map.entry(0x3C, "float"),
            Map.entry(0x3D, "byte"),
            Map.entry(0x3E, "bool"),
            Map.entry(0x3F, "float"),
            Map.entry(0x40, "byte"),
            Map.entry(0x41, "int"),
            Map.entry(0x42, "float"),
            Map.entry(0x43, "byte"),
            Map.entry(0x44, "int"),
            Map.entry(0x45, "bool"),
            Map.entry(0x47, "bool"),
            Map.entry(0x48, "bool"),
            Map.entry(0x49, "byte"),
            Map.entry(0x4A, "int"),
            Map.entry(0x4B, "bool"),
            Map.entry(0x4C, "float"),
            Map.entry(0x4D, "vector"),
            Map.entry(0x4E, "rotator"),
            Map.entry(0x4F, "bool"),
            Map.entry(0x50, "rotator"),
            Map.entry(0x51, "bool"),
            Map.entry(0x52, "string"),
            Map.entry(0x53, "string"),
            Map.entry(0x54, "string"),
            Map.entry(0x55, "string"),
            Map.entry(0x56, "string"),
            Map.entry(0x57, "string"),
            Map.entry(0x58, "string"),
            Map.entry(0x59, "string"));

    @FunctionalInterface
    interface Objects {
        String describe(int reference) throws IOException;
    }

    private final ClassPackageWriter writer;
    private final ClassSchema schema;
    private final Objects objects;
    private final String className;
    private final @Nullable String parentName;
    private final String functionName;
    private final ScriptFunction function;
    private final Map<Integer, Integer> statementAt = new HashMap<>();
    private StringBuilder out = new StringBuilder();
    private @Nullable Field result;

    private FunctionSource(
            ClassPackageWriter writer,
            ClassSchema schema,
            Objects objects,
            int classExport,
            String functionName,
            ScriptFunction function)
            throws IOException {
        this.writer = writer;
        this.schema = schema;
        this.objects = objects;
        this.className = writer.exportName(classExport);
        var parent = writer.export(classExport).superIndex();
        this.parentName = parent == 0 ? null : writer.getObjectName(parent);
        this.functionName = functionName;
        this.function = function;
        for (var i = 0; i < function.statements.size(); i++) {
            statementAt.put(function.statements.get(i).at(), i);
        }
        statementAt.put(function.scriptSize, function.statements.size());
    }

    record Compiled(int export, String name, int line) {}

    static List<Compiled> list(ClassPackageWriter writer, int classExport) throws IOException {
        var result = new ArrayList<Compiled>();
        for (var child : writer.getChildren(classExport)) {
            if (writer.getClassName(child).equalsIgnoreCase("Function")) {
                var function = ScriptFunction.read(writer.getPayload(child), writer);
                result.add(new Compiled(child, writer.exportName(child), function == null ? 0 : function.line));
            }
        }
        result.sort((a, b) ->
                a.line() != b.line() ? Integer.compare(a.line(), b.line()) : Integer.compare(a.export(), b.export()));
        return result;
    }

    static @Nullable String write(
            ClassPackageWriter writer, int classExport, int functionExport, ClassSchema schema, Objects objects) {
        try {
            var function = ScriptFunction.read(writer.getPayload(functionExport), writer);
            if (function == null) {
                return null;
            }
            var source = new FunctionSource(
                    writer, schema, objects, classExport, writer.exportName(functionExport), function);
            source.writeFunction();
            return source.out.toString();
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void writeFunction() throws IOException {
        var parameters = new ArrayList<String>();
        var locals = new ArrayList<String[]>();
        var returned = "";
        var at = function.children;
        for (var steps = 0; at > 0; steps++) {
            if (steps >= MOST_FIELDS) {
                throw PackageBytes.malformed("Too many fields in " + functionName);
            }
            var property = ScriptProperty.read(writer, at);
            if (property == null) {
                throw PackageBytes.malformed("Unreadable field of " + functionName);
            }
            var declared = typeOf(property) + " " + property.name()
                    + (property.arrayDim() > 1 ? "[" + property.arrayDim() + "]" : "");
            if (property.is(ScriptProperty.CPF_RETURN_PARM)) {
                result = ClassSchema.field(writer, at);
                returned = typeOf(property) + " ";
            } else if (property.is(ScriptProperty.CPF_PARM)) {
                var words = new StringBuilder();
                if (property.is(ScriptProperty.CPF_OPTIONAL_PARM)) {
                    words.append("optional ");
                }
                if (property.is(ScriptProperty.CPF_OUT_PARM)) {
                    words.append("out ");
                }
                if (property.is(ScriptProperty.CPF_COERCE_PARM)) {
                    words.append("coerce ");
                }
                parameters.add(words + declared);
            } else {
                locals.add(new String[] {
                    typeOf(property), property.name() + (property.arrayDim() > 1 ? "[" + property.arrayDim() + "]" : "")
                });
            }
            at = property.next();
        }
        out.append(header())
                .append(returned)
                .append(functionName)
                .append('(')
                .append(String.join(", ", parameters))
                .append(')')
                .append(function.is(FUNC_CONST) ? " const" : "")
                .append('\n');
        out.append("{\n");
        for (var i = 0; i < locals.size(); ) {
            var type = locals.get(i)[0];
            var names = new ArrayList<String>();
            while (i < locals.size() && locals.get(i)[0].equals(type)) {
                names.add(locals.get(i++)[1]);
            }
            out.append(INDENT)
                    .append("local ")
                    .append(type)
                    .append(' ')
                    .append(String.join(", ", names))
                    .append(";\n");
        }
        if (!locals.isEmpty() && !function.statements.isEmpty()) {
            out.append('\n');
        }
        block(0, function.statements.size(), INDENT, Scope.NONE);
        out.append("}\n");
    }

    private String header() {
        var words = new StringBuilder();
        if (function.is(FUNC_NATIVE)) {
            words.append(function.nativeIndex == 0 ? "native " : "native(" + function.nativeIndex + ") ");
        }
        if (function.is(FUNC_PRIVATE)) {
            words.append("private ");
        }
        if (function.is(FUNC_PROTECTED)) {
            words.append("protected ");
        }
        if (function.is(FUNC_STATIC)) {
            words.append("static ");
        }
        if (function.is(FUNC_SIMULATED)) {
            words.append("simulated ");
        }
        if (function.is(FUNC_FINAL)) {
            words.append("final ");
        }
        if (function.is(FUNC_SINGULAR)) {
            words.append("singular ");
        }
        if (function.is(FUNC_LATENT)) {
            words.append("latent ");
        }
        if (function.is(FUNC_ITERATOR)) {
            words.append("iterator ");
        }
        if (function.is(FUNC_EXEC)) {
            words.append("exec ");
        }
        words.append(function.is(FUNC_DELEGATE) ? "delegate " : function.is(FUNC_EVENT) ? "event " : "function ");
        return words.toString();
    }

    private void block(int from, int to, String indent, Scope scope) throws IOException {
        var i = from;
        while (i < to) {
            var doEnd = findDoEnd(i, to);
            if (doEnd >= 0) {
                i = doLoop(i, doEnd, indent);
                continue;
            }
            var forEnd = forLoop(i, to, indent);
            if (forEnd >= 0) {
                i = forEnd;
                continue;
            }
            var statement = function.statements.get(i);
            switch (statement.token()) {
                case JUMP_IF_NOT -> i = condition(i, to, indent, scope);
                case JUMP -> {
                    line(indent, jump(statement, scope));
                    i++;
                }
                case SWITCH -> i = switchStatement(i, to, indent, scope);
                case ITERATOR -> i = foreach(i, to, indent);
                case ITERATOR_NEXT -> {
                    var next = i + 1 < to ? function.statements.get(i + 1) : null;
                    if (next == null || next.token() != JUMP || next.value() != scope.iteratorEnd()) {
                        throw PackageBytes.malformed("A foreach step out of its loop");
                    }
                    line(indent, "continue;");
                    i += 2;
                }
                case ITERATOR_POP -> {
                    var next = i + 1 < to ? function.statements.get(i + 1) : null;
                    if (next == null || next.token() != RETURN || scope.iteratorEnd() < 0) {
                        throw PackageBytes.malformed("A foreach left out of its loop");
                    }
                    i++;
                }
                case RETURN -> {
                    var value = statement.kid(0);
                    if (value.token() != NOTHING) {
                        line(
                                indent,
                                "return "
                                        + expression(value, values(result), Place.ASSIGNED)
                                                .text() + ";");
                    } else if (i != function.statements.size() - 1) {
                        line(indent, "return;");
                    }
                    i++;
                }
                case ASSERT -> {
                    line(indent, "assert(" + expression(statement.kid(0), null).text() + ");");
                    i++;
                }
                case NOTHING -> i++;
                default -> {
                    line(indent, statement(statement) + ";");
                    i++;
                }
            }
        }
    }

    private int condition(int at, int to, String indent, Scope scope) throws IOException {
        var jump = function.statements.get(at);
        var end = index(jump.value());
        if (end <= at || end > to) {
            throw PackageBytes.malformed("A condition that jumps out of its block");
        }
        var test = expression(jump.kid(0), null).text();
        var last = end - 1 > at ? function.statements.get(end - 1) : null;
        if (last != null && last.token() == JUMP && last.value() == jump.at()) {
            var step = end - 2 > at ? function.statements.get(end - 2) : null;
            if (step != null
                    && isStep(step)
                    && hasJumpTo(step.at(), at + 1, end - 2)
                    && !reachesBack(last.at(), at + 1, end - 2)) {
                line(indent, "for (; " + test + "; " + statement(step) + ")");
                loopBody(at + 1, end - 2, indent, new Scope(jump.value(), step.at(), -1));
            } else {
                line(indent, "while (" + test + ")");
                loopBody(at + 1, end - 1, indent, new Scope(jump.value(), last.at(), -1));
            }
            return end;
        }
        if (last != null && last.token() == JUMP && last.value() > jump.value() && !leaves(end - 1, at, scope)) {
            var elseEnd = index(last.value());
            if (elseEnd > to) {
                throw PackageBytes.malformed("An else that ends out of its block");
            }
            line(indent, "if (" + test + ")");
            loopBody(at + 1, end - 1, indent, scope);
            var otherwise = render(end, elseEnd, indent, scope);
            if (isLoneIf(otherwise, indent)) {
                out.append(indent).append("else ").append(otherwise.substring(indent.length()));
            } else {
                line(indent, "else");
                line(indent, "{");
                otherwise
                        .lines()
                        .forEach(text -> out.append(INDENT).append(text).append('\n'));
                line(indent, "}");
            }
            return elseEnd;
        }
        line(indent, "if (" + test + ")");
        loopBody(at + 1, end, indent, scope);
        return end;
    }

    private boolean leaves(int jumpIndex, int at, Scope scope) {
        var target = function.statements.get(jumpIndex).value();
        if (target == scope.breakAt()) {
            return true;
        }
        if (target == scope.continueAt()) {
            return jumpIndex == at + 1;
        }
        return target == scope.iteratorEnd()
                && jumpIndex - 1 > at
                && function.statements.get(jumpIndex - 1).token() == ITERATOR_NEXT;
    }

    private String render(int from, int to, String indent, Scope scope) throws IOException {
        var written = out;
        out = new StringBuilder();
        try {
            block(from, to, indent, scope);
            return out.toString();
        } finally {
            out = written;
        }
    }

    private void loopBody(int from, int to, String indent, Scope scope) throws IOException {
        line(indent, "{");
        block(from, to, indent + INDENT, scope);
        line(indent, "}");
    }

    private int forLoop(int at, int to, String indent) throws IOException {
        var statements = function.statements;
        var init = statements.get(at);
        if ((init.token() != LET && init.token() != LET_BOOL) || at + 1 >= to) {
            return -1;
        }
        var jump = statements.get(at + 1);
        if (jump.token() != JUMP_IF_NOT) {
            return -1;
        }
        var end = statementAt.get(jump.value());
        if (end == null || end <= at + 4 || end > to) {
            return -1;
        }
        var back = statements.get(end - 1);
        var step = statements.get(end - 2);
        if (back.token() != JUMP
                || back.value() != jump.at()
                || !changes(step, init.kid(0))
                || reachesBack(back.at(), at + 2, end - 2)) {
            return -1;
        }
        line(
                indent,
                "for (" + statement(init) + "; " + expression(jump.kid(0), null).text() + "; " + statement(step) + ")");
        loopBody(at + 2, end - 2, indent, new Scope(jump.value(), step.at(), -1));
        return end;
    }

    private boolean isStep(Node step) {
        return switch (step.token()) {
            case LET, LET_BOOL -> true;
            case NATIVE -> {
                var signature = schema.findNative(step.value());
                yield signature != null
                        && signature.is(FUNC_OPERATOR)
                        && !step.kids().isEmpty();
            }
            default -> false;
        };
    }

    private boolean changes(Node step, Node variable) {
        if (!isStep(step) || !isSame(step.kid(0), variable)) {
            return false;
        }
        return step.token() == NATIVE || contains(step.kid(1), variable);
    }

    private boolean reachesBack(int backAt, int from, int to) {
        for (var i = from; i < to; i++) {
            var statement = function.statements.get(i);
            if ((statement.token() == JUMP || statement.token() == JUMP_IF_NOT) && statement.value() == backAt) {
                return true;
            }
        }
        return false;
    }

    private boolean hasJumpTo(int offset, int from, int to) {
        for (var i = from; i < to; i++) {
            var statement = function.statements.get(i);
            if (statement.token() == JUMP && statement.value() == offset) {
                return true;
            }
        }
        return false;
    }

    private int findDoEnd(int at, int to) {
        var start = function.statements.get(at).at();
        for (var i = to - 1; i > at; i--) {
            var statement = function.statements.get(i);
            if (statement.token() == JUMP_IF_NOT && statement.value() == start) {
                return i;
            }
        }
        return -1;
    }

    private int doLoop(int at, int until, String indent) throws IOException {
        var test = function.statements.get(until);
        line(indent, "do");
        loopBody(at, until, indent, new Scope(offsetOf(until + 1), test.at(), -1));
        line(indent, "until (" + expression(test.kid(0), null).text() + ");");
        return until + 1;
    }

    private int switchStatement(int at, int to, String indent, Scope scope) throws IOException {
        var statements = function.statements;
        var subject = statements.get(at).kid(0);
        var cases = new ArrayList<Integer>();
        var at2 = at + 1;
        while (true) {
            if (at2 >= to || statements.get(at2).token() != CASE) {
                throw PackageBytes.malformed("A switch without its cases");
            }
            cases.add(at2);
            var next = statements.get(at2).value();
            if (next == 0xFFFF) {
                break;
            }
            var nextIndex = index(next);
            if (nextIndex <= at2 || nextIndex > to) {
                throw PackageBytes.malformed("A case that jumps out of its switch");
            }
            if (nextIndex == to || statements.get(nextIndex).token() != CASE) {
                break;
            }
            at2 = nextIndex;
        }
        var lastCase = cases.getLast();
        var last = statements.get(lastCase);
        int end;
        if (last.value() != 0xFFFF) {
            end = index(last.value());
        } else {
            end = -1;
            for (var i = cases.getFirst(); i < lastCase && end < 0; i++) {
                var statement = statements.get(i);
                if (statement.token() == JUMP
                        && statement.value() > last.at()
                        && statement.value() != scope.breakAt()
                        && statement.value() != scope.continueAt()) {
                    end = index(statement.value());
                }
            }
            for (var i = lastCase + 1; i < to && end < 0; i++) {
                var statement = statements.get(i);
                if (statement.token() == JUMP && statement.value() == offsetOf(i + 1)) {
                    end = i + 1;
                }
            }
            if (end < 0) {
                end = to;
            }
        }
        if (end > to) {
            throw PackageBytes.malformed("A switch that ends out of its block");
        }
        var inner = new Scope(offsetOf(end), scope.continueAt(), scope.iteratorEnd());
        var values = valuesOf(subject);
        line(indent, "switch (" + expression(subject, null).text() + ")");
        line(indent, "{");
        for (var k = 0; k < cases.size(); k++) {
            var caseIndex = cases.get(k);
            var node = statements.get(caseIndex);
            var bodyEnd = k + 1 < cases.size() ? cases.get(k + 1) : end;
            var body = render(caseIndex + 1, bodyEnd, indent + INDENT + INDENT, inner);
            if (node.value() == 0xFFFF && body.isEmpty()) {
                continue;
            }
            line(
                    indent + INDENT,
                    node.value() == 0xFFFF
                            ? "default:"
                            : "case " + expression(node.kid(0), values).text() + ":");
            out.append(body);
        }
        line(indent, "}");
        return end;
    }

    private int foreach(int at, int to, String indent) throws IOException {
        var iterator = function.statements.get(at);
        var pop = index(iterator.value());
        if (pop <= at + 1
                || pop >= to
                || function.statements.get(pop).token() != ITERATOR_POP
                || function.statements.get(pop - 1).token() != ITERATOR_NEXT) {
            throw PackageBytes.malformed("A foreach without its end");
        }
        line(indent, "foreach " + expression(iterator.kid(0), null).text());
        loopBody(at + 1, pop - 1, indent, new Scope(iterator.value(), -1, iterator.value()));
        return pop + 1;
    }

    private record Scope(int breakAt, int continueAt, int iteratorEnd) {
        static final Scope NONE = new Scope(-1, -1, -1);
    }

    private int offsetOf(int index) {
        return index < function.statements.size()
                ? function.statements.get(index).at()
                : function.scriptSize;
    }

    private int index(int offset) throws IOException {
        var index = statementAt.get(offset);
        if (index == null) {
            throw PackageBytes.malformed("A jump into a statement");
        }
        return index;
    }

    private String statement(Node node) throws IOException {
        if (node.token() == LET || node.token() == LET_BOOL) {
            var target = node.kid(0);
            return expression(target, null).text() + " = "
                    + expression(node.kid(1), valuesOf(target), Place.ASSIGNED).text();
        }
        return expression(node, null).text();
    }

    private record Text(String text, int precedence) {
        static Text atom(String text) {
            return new Text(text, ATOM);
        }
    }

    private enum Place {
        ARGUMENT,
        COERCED,
        ASSIGNED,
        OPERAND,
        MEMBER,
        OTHER
    }

    private Text expression(Node node, @Nullable List<String> values) throws IOException {
        return expression(node, values, Place.OTHER);
    }

    private Text expression(Node node, @Nullable List<String> values, Place place) throws IOException {
        return switch (node.token()) {
            case LOCAL_VARIABLE, INSTANCE_VARIABLE, NATIVE_PARM -> Text.atom(writer.getObjectName(node.reference()));
            case DEFAULT_VARIABLE -> Text.atom("default." + writer.getObjectName(node.reference()));
            case BOOL_VARIABLE, SKIP, EAT_STRING -> expression(node.kid(0), values, place);
            case SELF -> Text.atom("self");
            case NO_OBJECT -> Text.atom("None");
            case TRUE -> Text.atom("True");
            case FALSE -> Text.atom("False");
            case INT_ZERO -> number(0, values);
            case INT_ONE -> number(1, values);
            case INT_CONST, INT_CONST_BYTE, BYTE_CONST -> number(node.value(), values);
            case FLOAT_CONST -> Text.atom(ClassSource.number(Float.intBitsToFloat(node.value())));
            case STRING_CONST, UNICODE_STRING_CONST -> Text.atom(ClassSource.quote(ClassSource.korean(node.text())));
            case NAME_CONST -> {
                var name = writer.getName(node.reference());
                yield Text.atom(name.equalsIgnoreCase("None") ? "''" : "'" + name + "'");
            }
            case OBJECT_CONST -> Text.atom(object(node.reference()));
            case VECTOR_CONST -> Text.atom("vect(" + floats(node) + ")");
            case ROTATION_CONST ->
                Text.atom("rot(" + node.kid(0).value() + "," + node.kid(1).value() + ","
                        + node.kid(2).value() + ")");
            case DYNAMIC_CAST ->
                Text.atom(writer.getObjectName(node.reference()) + "("
                        + expression(node.kid(0), null).text() + ")");
            case META_CAST ->
                Text.atom("class<" + writer.getObjectName(node.reference()) + ">("
                        + expression(node.kid(0), null).text() + ")");
            case PRIMITIVE_CAST -> cast(node, values, place);
            case STRUCT_MEMBER -> Text.atom(owner(node.kid(0)) + "." + writer.getObjectName(node.reference()));
            case DYN_ARRAY_ELEMENT, ARRAY_ELEMENT ->
                Text.atom(
                        owner(node.kid(1)) + "[" + expression(node.kid(0), null).text() + "]");
            case DYN_ARRAY_LENGTH -> Text.atom(owner(node.kid(0)) + ".Length");
            case CONTEXT -> {
                var member = expression(node.kid(1), values, Place.MEMBER).text();
                yield Text.atom(isOuterMember(node) ? member : owner(node.kid(0)) + "." + member);
            }
            case CLASS_CONTEXT -> {
                var member = node.kid(1);
                var call = member.token() == VIRTUAL_FUNCTION || member.token() == FINAL_FUNCTION;
                yield Text.atom(owner(node.kid(0)) + "." + (call ? "static." : "")
                        + expression(member, values, Place.MEMBER).text());
            }
            case VIRTUAL_FUNCTION -> {
                var name = writer.getName(node.reference());
                yield Text.atom(call(name, node.kids(), schema.findFunction(className, name)));
            }
            case GLOBAL_FUNCTION -> {
                var name = writer.getName(node.reference());
                yield Text.atom("Global." + call(name, node.kids(), schema.findFunction(className, name)));
            }
            case FINAL_FUNCTION -> finalCall(node);
            case NATIVE -> nativeCall(node);
            case STRUCT_CMP_EQ, STRUCT_CMP_NE -> {
                var operator = node.token() == STRUCT_CMP_EQ ? "==" : "!=";
                yield new Text(
                        side(node.kid(0), null, 24, false) + " " + operator + " " + side(node.kid(1), null, 24, true),
                        24);
            }
            case NEW -> newObject(node);
            case DYN_ARRAY_INSERT, DYN_ARRAY_REMOVE ->
                Text.atom(owner(node.kid(0)) + (node.token() == DYN_ARRAY_INSERT ? ".Insert(" : ".Remove(")
                        + expression(node.kid(1), null).text() + ", "
                        + expression(node.kid(2), null).text() + ")");
            case LET, LET_BOOL -> new Text(statement(node), Integer.MAX_VALUE);
            default -> throw PackageBytes.malformed("No text for script token 0x" + Integer.toHexString(node.token()));
        };
    }

    private String object(int reference) throws IOException {
        if (reference == 0) {
            return "None";
        }
        if (writer.getClassOf(reference).equalsIgnoreCase("Class")) {
            return "class'" + writer.getObjectName(reference) + "'";
        }
        return objects.describe(reference);
    }

    private Text newObject(Node node) throws IOException {
        var parts = new ArrayList<String>();
        for (var i = 0; i < 3; i++) {
            var argument = node.kid(i);
            parts.add(
                    argument.token() == NOTHING
                            ? ""
                            : expression(argument, null).text());
        }
        while (!parts.isEmpty() && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        var head = parts.isEmpty() ? "new " : "new(" + String.join(", ", parts) + ") ";
        return new Text(head + owner(node.kid(3)), NEW_PRECEDENCE);
    }

    private String floats(Node node) {
        var parts = new ArrayList<String>();
        for (var kid : node.kids()) {
            parts.add(ClassSource.number(Float.intBitsToFloat(kid.value())));
        }
        return String.join(",", parts);
    }

    private Text cast(Node node, @Nullable List<String> values, Place place) throws IOException {
        var code = node.value();
        var inner = node.kid(0);
        if (code == BYTE_TO_INT) {
            return expression(inner, values, place);
        }
        if (NUMBERS.contains(code)) {
            var given = place == Place.ARGUMENT || place == Place.COERCED || place == Place.ASSIGNED;
            var literal = isWholeNumber(inner) && (code == INT_TO_FLOAT || code == BYTE_TO_FLOAT);
            if (given || literal) {
                return expression(inner, values);
            }
        }
        if (place == Place.COERCED && "string".equals(CASTS.get(code))) {
            return expression(inner, null);
        }
        var type = CASTS.get(code);
        if (type == null) {
            throw PackageBytes.malformed("Unknown conversion 0x" + Integer.toHexString(code));
        }
        return Text.atom(type + "(" + expression(node.kid(0), null).text() + ")");
    }

    private boolean isOuterMember(Node context) throws IOException {
        var owner = context.kid(0);
        if (owner.token() != INSTANCE_VARIABLE
                || !writer.getObjectName(owner.reference()).equalsIgnoreCase("Outer")) {
            return false;
        }
        var member = context.kid(1);
        if (member.token() == BOOL_VARIABLE) {
            member = member.kid(0);
        }
        return switch (member.token()) {
            case VIRTUAL_FUNCTION -> schema.findFunction(className, writer.getName(member.reference())) == null;
            case INSTANCE_VARIABLE -> {
                var name = writer.getObjectName(member.reference());
                yield schema.find(className, name) == null && !isOwn(outerOf(member.reference()));
            }
            case FINAL_FUNCTION -> {
                var name = writer.getObjectName(member.reference());
                yield schema.findFunction(className, name) == null && !isOwn(outerOf(member.reference()));
            }
            case NATIVE -> {
                var signature = schema.findNative(member.value());
                yield signature != null
                        && !isOwn(signature.owner())
                        && schema.findFunction(className, signature.name()) == null;
            }
            default -> false;
        };
    }

    private String typeOf(ScriptProperty property) throws IOException {
        var type = property.type(writer);
        if (!property.kind().equals("StructProperty") || property.first() == 0) {
            return type;
        }
        var outer = property.first() < 0
                ? writer.importEntry(property.first()).outer()
                : writer.export(property.first()).outer();
        if (outer == 0 || !writer.getClassOf(outer).equalsIgnoreCase("Class")) {
            return type;
        }
        var owner = writer.getObjectName(outer);
        return owner.equalsIgnoreCase("Object") || isOwn(owner) ? type : owner + "." + type;
    }

    private String outerOf(int reference) throws IOException {
        return writer.getObjectName(
                reference < 0
                        ? writer.importEntry(reference).outer()
                        : writer.export(reference).outer());
    }

    private boolean isOwn(String owner) {
        return schema.isA(className, owner);
    }

    private String owner(Node node) throws IOException {
        var text = expression(node, null);
        return text.precedence() == ATOM ? text.text() : "(" + text.text() + ")";
    }

    private Text finalCall(Node node) throws IOException {
        var reference = node.reference();
        var name = writer.getObjectName(reference);
        var outer = reference < 0
                ? writer.importEntry(reference).outer()
                : writer.export(reference).outer();
        var owner = writer.getObjectName(outer);
        var signature = schema.findFunction(owner, name);
        boolean parent;
        if (signature != null) {
            parent = !signature.is(FUNC_FINAL | FUNC_STATIC | FUNC_PRIVATE) && !owner.equalsIgnoreCase(className);
        } else {
            parent = name.equalsIgnoreCase(functionName) && !owner.equalsIgnoreCase(className);
        }
        if (!parent) {
            return Text.atom(call(name, node.kids(), signature));
        }
        var nearest = parentName == null ? null : schema.findFunction(parentName, name);
        var prefix = nearest == null || nearest.owner().equalsIgnoreCase(owner) ? "Super." : "Super(" + owner + ").";
        return Text.atom(prefix + call(name, node.kids(), signature));
    }

    private Text nativeCall(Node node) throws IOException {
        var signature = schema.findNative(node.value());
        if (signature == null) {
            throw PackageBytes.malformed("Unknown native function " + node.value());
        }
        if (!signature.is(FUNC_OPERATOR)) {
            return Text.atom(call(signature.name(), node.kids(), signature));
        }
        var precedence = signature.precedence();
        var kids = node.kids();
        if (kids.size() == 2) {
            var left = kids.get(0);
            var right = kids.get(1);
            return new Text(
                    side(left, valuesOf(right), precedence, false, operand(signature, 0))
                            + " " + signature.name() + " "
                            + side(right, valuesOf(left), precedence, true, operand(signature, 1)),
                    precedence);
        }
        if (kids.size() == 1) {
            var operand = expression(kids.getFirst(), parameter(signature, 0), operand(signature, 0));
            var text = operand.precedence() == ATOM ? operand.text() : "(" + operand.text() + ")";
            return Text.atom(signature.is(FUNC_PRE_OPERATOR) ? signature.name() + text : text + signature.name());
        }
        throw PackageBytes.malformed("An operator with " + kids.size() + " operands");
    }

    private String side(Node node, @Nullable List<String> values, int precedence, boolean right) throws IOException {
        return side(node, values, precedence, right, Place.OPERAND);
    }

    private String side(Node node, @Nullable List<String> values, int precedence, boolean right, Place place)
            throws IOException {
        var text = expression(node, values, place);
        var brackets = text.precedence() > precedence || right && text.precedence() == precedence;
        return brackets ? "(" + text.text() + ")" : text.text();
    }

    private String call(String name, List<Node> arguments, @Nullable Signature signature) throws IOException {
        var parts = new ArrayList<String>();
        for (var i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            parts.add(
                    argument.token() == NOTHING
                            ? ""
                            : expression(
                                            argument,
                                            parameter(signature, i),
                                            signature != null && signature.isCoerced(i)
                                                    ? Place.COERCED
                                                    : Place.ARGUMENT)
                                    .text());
        }
        while (!parts.isEmpty() && parts.getLast().isEmpty()) {
            parts.removeLast();
        }
        var text = new StringBuilder(name).append('(');
        for (var i = 0; i < parts.size(); i++) {
            if (i > 0) {
                text.append(',');
            }
            if (!parts.get(i).isEmpty()) {
                text.append(i > 0 ? " " : "").append(parts.get(i));
            }
        }
        return text.append(')').toString();
    }

    private @Nullable List<String> valuesOf(Node node) throws IOException {
        return switch (node.token()) {
            case LOCAL_VARIABLE, INSTANCE_VARIABLE, DEFAULT_VARIABLE, NATIVE_PARM, STRUCT_MEMBER ->
                propertyValues(node.reference());
            case BOOL_VARIABLE, SKIP -> valuesOf(node.kid(0));
            case CONTEXT, CLASS_CONTEXT -> valuesOf(node.kid(1));
            case PRIMITIVE_CAST -> node.value() == BYTE_TO_INT ? valuesOf(node.kid(0)) : null;
            case VIRTUAL_FUNCTION -> {
                var signature = schema.findFunction(className, writer.getName(node.reference()));
                yield signature == null ? null : values(signature.result());
            }
            case NATIVE -> {
                var signature = schema.findNative(node.value());
                yield signature == null || signature.is(FUNC_OPERATOR) ? null : values(signature.result());
            }
            default -> null;
        };
    }

    private @Nullable List<String> propertyValues(int reference) throws IOException {
        if (reference > 0) {
            return values(ClassSchema.field(writer, reference));
        }
        if (reference < 0) {
            var owner = writer.getObjectName(writer.importEntry(reference).outer());
            return values(schema.find(owner, writer.getObjectName(reference)));
        }
        return null;
    }

    private void line(String indent, String text) {
        out.append(indent).append(text).append('\n');
    }

    private static Text number(int value, @Nullable List<String> values) {
        return Text.atom(
                values != null && value >= 0 && value < values.size() ? values.get(value) : Integer.toString(value));
    }

    private static @Nullable List<String> parameter(@Nullable Signature signature, int index) {
        return signature == null || index >= signature.parameters().size()
                ? null
                : signature.parameters().get(index).values();
    }

    private static @Nullable List<String> values(@Nullable Field field) {
        return field == null ? null : field.values();
    }

    private static String jump(Node statement, Scope scope) throws IOException {
        if (statement.value() == scope.breakAt()) {
            return "break;";
        }
        if (statement.value() == scope.continueAt()) {
            return "continue;";
        }
        throw PackageBytes.malformed("A jump that is no if, else, loop or switch");
    }

    private static boolean isLoneIf(String text, String indent) {
        var lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().startsWith(indent + "if (")) {
            return false;
        }
        for (var i = 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.startsWith(indent + " ")) {
                continue;
            }
            var own = line.substring(Math.min(indent.length(), line.length()));
            if (!own.equals("{") && !own.equals("}") && !own.equals("else") && !own.startsWith("else if (")) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(Node tree, Node part) {
        if (isSame(tree, part)) {
            return true;
        }
        for (var kid : tree.kids()) {
            if (contains(kid, part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSame(Node a, Node b) {
        if (a.token() != b.token()
                || a.value() != b.value()
                || a.reference() != b.reference()
                || !java.util.Objects.equals(a.text(), b.text())
                || a.kids().size() != b.kids().size()) {
            return false;
        }
        for (var i = 0; i < a.kids().size(); i++) {
            if (!isSame(a.kid(i), b.kid(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWholeNumber(Node node) {
        return switch (node.token()) {
            case INT_CONST, INT_CONST_BYTE, INT_ZERO, INT_ONE, BYTE_CONST -> true;
            default -> false;
        };
    }

    private static Place operand(Signature signature, int index) {
        if (signature.isCoerced(index)) {
            return Place.COERCED;
        }
        return index == 1 && signature.isOut(0) ? Place.ASSIGNED : Place.OPERAND;
    }
}
