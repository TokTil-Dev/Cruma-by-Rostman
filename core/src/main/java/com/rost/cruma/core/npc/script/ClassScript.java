package com.rost.cruma.core.npc.script;

import com.rost.cruma.core.npc.script.ScriptLexer.Kind;
import com.rost.cruma.core.npc.script.ScriptLexer.Token;
import com.rost.cruma.core.npc.script.ScriptTree.Assign;
import com.rost.cruma.core.npc.script.ScriptTree.Binary;
import com.rost.cruma.core.npc.script.ScriptTree.Block;
import com.rost.cruma.core.npc.script.ScriptTree.Break;
import com.rost.cruma.core.npc.script.ScriptTree.Call;
import com.rost.cruma.core.npc.script.ScriptTree.Case;
import com.rost.cruma.core.npc.script.ScriptTree.Cast;
import com.rost.cruma.core.npc.script.ScriptTree.Constant;
import com.rost.cruma.core.npc.script.ScriptTree.Continue;
import com.rost.cruma.core.npc.script.ScriptTree.DoUntil;
import com.rost.cruma.core.npc.script.ScriptTree.Element;
import com.rost.cruma.core.npc.script.ScriptTree.Expression;
import com.rost.cruma.core.npc.script.ScriptTree.For;
import com.rost.cruma.core.npc.script.ScriptTree.Function;
import com.rost.cruma.core.npc.script.ScriptTree.If;
import com.rost.cruma.core.npc.script.ScriptTree.Member;
import com.rost.cruma.core.npc.script.ScriptTree.Return;
import com.rost.cruma.core.npc.script.ScriptTree.RotatorOf;
import com.rost.cruma.core.npc.script.ScriptTree.Run;
import com.rost.cruma.core.npc.script.ScriptTree.Self;
import com.rost.cruma.core.npc.script.ScriptTree.Skipped;
import com.rost.cruma.core.npc.script.ScriptTree.Statement;
import com.rost.cruma.core.npc.script.ScriptTree.Step;
import com.rost.cruma.core.npc.script.ScriptTree.Switch;
import com.rost.cruma.core.npc.script.ScriptTree.Unary;
import com.rost.cruma.core.npc.script.ScriptTree.Variable;
import com.rost.cruma.core.npc.script.ScriptTree.VectorOf;
import com.rost.cruma.core.npc.script.ScriptTree.While;
import com.rost.cruma.core.npc.script.ScriptTree.Word;
import com.rost.cruma.core.npc.script.ScriptValues.ClassRef;
import com.rost.cruma.core.npc.script.ScriptValues.Enum;
import com.rost.cruma.core.npc.script.ScriptValues.Name;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class ClassScript {
    private static final Set<String> MODIFIERS = Set.of(
            "const",
            "transient",
            "config",
            "globalconfig",
            "localized",
            "editinline",
            "export",
            "native",
            "private",
            "protected",
            "public",
            "input",
            "travel",
            "editconst",
            "noexport",
            "deprecated",
            "edfindable",
            "editinlineuse",
            "editinlinenew",
            "automated",
            "cache",
            "simulated",
            "static",
            "final",
            "latent",
            "iterator",
            "singular",
            "exec",
            "reliable",
            "unreliable",
            "client",
            "server",
            "demorecording",
            "optional",
            "out",
            "coerce",
            "skip",
            "noexportheader",
            "editoronly");
    private static final Map<String, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry("**", 12),
            Map.entry("*", 16),
            Map.entry("/", 16),
            Map.entry("%", 18),
            Map.entry("dot", 16),
            Map.entry("cross", 16),
            Map.entry("+", 20),
            Map.entry("-", 20),
            Map.entry("<<", 22),
            Map.entry(">>", 22),
            Map.entry(">>>", 22),
            Map.entry("<", 24),
            Map.entry(">", 24),
            Map.entry("<=", 24),
            Map.entry(">=", 24),
            Map.entry("==", 24),
            Map.entry("!=", 26),
            Map.entry("~=", 24),
            Map.entry("&", 28),
            Map.entry("|", 28),
            Map.entry("^", 28),
            Map.entry("&&", 30),
            Map.entry("^^", 30),
            Map.entry("||", 32),
            Map.entry("$", 40),
            Map.entry("@", 40));
    private static final Set<String> ASSIGNMENTS = Set.of("=", "+=", "-=", "*=", "/=", "$=", "@=");
    private static final Set<String> CASTS =
            Set.of("int", "float", "bool", "byte", "name", "string", "vector", "rotator");

    private final String name;
    private final String parent;
    private final Map<String, Variable> variables = new LinkedHashMap<>();
    private final Map<String, Function> functions = new LinkedHashMap<>();
    private final Set<String> classes = new LinkedHashSet<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<Token> tokens;
    private int at;
    private String headerName = "";
    private String headerParent = "";

    private ClassScript(String name, String text) {
        tokens = ScriptLexer.tokens(text);
        parseHeader();
        this.name = headerName.isEmpty() ? name : headerName;
        headerName = this.name;
        this.parent = headerParent;
        parseBody();
    }

    public static ClassScript parse(String name, String text) {
        return new ClassScript(name, text);
    }

    public String name() {
        return name;
    }

    public String getParent() {
        return parent;
    }

    public Set<String> getClassLiterals() {
        return Set.copyOf(classes);
    }

    public List<String> getSkipped() {
        return List.copyOf(skipped);
    }

    public boolean declares(String function) {
        return functions.containsKey(function.toLowerCase(Locale.ROOT));
    }

    @Nullable
    Function getFunction(String key) {
        return functions.get(key);
    }

    Map<String, Variable> getVariables() {
        return variables;
    }

    Map<String, Function> getFunctions() {
        return functions;
    }

    private Token peek() {
        return tokens.get(Math.min(at, tokens.size() - 1));
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(at + ahead, tokens.size() - 1));
    }

    private Token take() {
        var token = peek();
        if (token.kind() != Kind.END) {
            at++;
        }
        return token;
    }

    private boolean accept(String word) {
        if (peek().is(word)) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!accept(word)) {
            throw new Unparsed("'" + word + "' expected, found '" + peek().text() + "'");
        }
    }

    private boolean atEnd() {
        return peek().kind() == Kind.END;
    }

    private void parseHeader() {
        if (!peek().is("class")) {
            return;
        }
        take();
        headerName = take().text();
        if (accept("extends")) {
            headerParent = qualified();
        }
        while (!atEnd() && !accept(";")) {
            take();
        }
    }

    private String qualified() {
        var word = new StringBuilder(take().text());
        while (peek().is(".") && peek(1).kind() == Kind.WORD) {
            take();
            word.append('.').append(take().text());
        }
        return word.toString();
    }

    private void parseBody() {
        while (!atEnd()) {
            var start = at;
            try {
                declaration();
            } catch (RuntimeException failure) {
                skipped.add("declaration@"
                        + tokens.get(Math.min(start, tokens.size() - 1)).line() + " " + failure.getMessage());
                at = start;
                skipDeclaration();
            }
        }
    }

    private void declaration() {
        var token = peek();
        if (token.is("var")) {
            take();
            variable();
        } else if (token.is(";")) {
            take();
        } else if (token.is("defaultproperties")
                || token.is("replication")
                || token.is("cpptext")
                || token.is("structcpptext")) {
            take();
            skipped.add(token.lower() + "@" + token.line());
            skipBraces();
        } else if (token.is("state") || token.is("auto") && peek(1).is("state")) {
            skipped.add("state@" + token.line());
            skipDeclaration();
        } else if (token.is("enum") || token.is("struct") || token.is("const")) {
            skipDeclaration();
        } else if (isFunctionStart()) {
            parseFunction();
        } else {
            throw new Unparsed("unknown declaration '" + token.text() + "'");
        }
    }

    private boolean isFunctionStart() {
        for (var ahead = 0; ahead < 8; ahead++) {
            var token = peek(ahead);
            if (token.is("function")
                    || token.is("event")
                    || token.is("operator")
                    || token.is("preoperator")
                    || token.is("postoperator")
                    || token.is("delegate")) {
                return true;
            }
            if (token.kind() != Kind.WORD && !token.is("(") && !token.is(")") && token.kind() != Kind.INT) {
                return false;
            }
        }
        return false;
    }

    private void skipDeclaration() {
        while (!atEnd()) {
            var token = take();
            if (token.is(";")) {
                return;
            }
            if (token.is("{")) {
                at--;
                skipBraces();
                accept(";");
                return;
            }
        }
    }

    private void skipBraces() {
        if (!accept("{")) {
            return;
        }
        var depth = 1;
        while (!atEnd() && depth > 0) {
            var token = take();
            if (token.is("{")) {
                depth++;
            } else if (token.is("}")) {
                depth--;
            }
        }
    }

    private void skipParentheses() {
        var depth = 1;
        while (!atEnd() && depth > 0) {
            var token = take();
            if (token.is("(")) {
                depth++;
            } else if (token.is(")")) {
                depth--;
            }
        }
    }

    private void variable() {
        if (accept("(")) {
            skipParentheses();
        }
        while (MODIFIERS.contains(peek().lower())) {
            take();
        }
        if (peek().is("enum") || peek().is("struct")) {
            take();
            take();
            skipBraces();
            names("enum");
            return;
        }
        names(type());
    }

    private void names(String type) {
        do {
            var name = take();
            if (name.kind() != Kind.WORD) {
                throw new Unparsed("variable name expected");
            }
            var size = 0;
            if (accept("[")) {
                var count = take();
                size = count.kind() == Kind.INT ? Integer.parseInt(count.text()) : 1;
                expect("]");
            }
            if (peek().is("<")) {
                while (!atEnd() && !accept(">")) {
                    take();
                }
            }
            var variable = new Variable(name.text(), type, size);
            variables.put(variable.key(), variable);
        } while (accept(","));
        expect(";");
    }

    private String type() {
        var token = take();
        if (token.kind() != Kind.WORD) {
            throw new Unparsed("type expected");
        }
        var type = token.lower();
        if (peek().is(".") && peek(1).kind() == Kind.WORD) {
            take();
            type = take().lower();
        }
        if (accept("<")) {
            var depth = 1;
            while (!atEnd() && depth > 0) {
                var inner = take();
                if (inner.is("<")) {
                    depth++;
                } else if (inner.is(">")) {
                    depth--;
                } else if (inner.is(">>")) {
                    depth -= 2;
                }
            }
        }
        return type;
    }

    private void parseFunction() {
        var declaredNative = false;
        while (!peek().is("function") && !peek().is("event") && !atEnd()) {
            var word = take();
            declaredNative |= word.is("native");
            if (peek().is("(")
                    && (word.is("native")
                            || word.is("operator")
                            || word.is("preoperator")
                            || word.is("postoperator"))) {
                take();
                skipParentheses();
            }
            if (word.is("operator") || word.is("preoperator") || word.is("postoperator") || word.is("delegate")) {
                skipDeclaration();
                return;
            }
        }
        take();
        var first = take();
        var functionName = first.text();
        if (peek().is("<")) {
            at--;
            type();
            functionName = take().text();
        } else if (peek().kind() == Kind.WORD) {
            functionName = take().text();
        }
        expect("(");
        var parameters = new ArrayList<Variable>();
        while (!atEnd() && !accept(")")) {
            while (MODIFIERS.contains(peek().lower())) {
                take();
            }
            var parameterType = type();
            var parameterName = take();
            var size = 0;
            if (accept("[")) {
                size = Integer.parseInt(take().text());
                expect("]");
            }
            parameters.add(new Variable(parameterName.text(), parameterType, size));
            accept(",");
        }
        while (peek().kind() == Kind.WORD && !peek().is("function")) {
            take();
        }
        if (accept(";") || declaredNative && !peek().is("{")) {
            return;
        }
        var locals = new ArrayList<Variable>();
        var body = block(locals);
        accept(";");
        var function = new Function(functionName, List.copyOf(parameters), List.copyOf(locals), body, headerName);
        functions.put(function.key(), function);
    }

    private List<Statement> block(List<Variable> locals) {
        expect("{");
        var statements = new ArrayList<Statement>();
        while (!atEnd() && !peek().is("}")) {
            var statement = statement(locals);
            if (statement != null) {
                statements.add(statement);
            }
        }
        expect("}");
        return statements;
    }

    private @Nullable Statement statement(List<Variable> locals) {
        var start = at;
        var line = peek().line();
        try {
            return statementOrThrow(locals);
        } catch (RuntimeException failure) {
            at = start;
            var word = peek().text();
            skipStatement();
            var what = word.isEmpty() ? "statement" : word;
            skipped.add(what + "@" + line);
            return new Skipped(what, line);
        }
    }

    private void skipStatement() {
        while (!atEnd()) {
            var token = peek();
            if (token.is("}")) {
                return;
            }
            take();
            if (token.is(";")) {
                return;
            }
            if (token.is("{")) {
                at--;
                skipBraces();
                return;
            }
        }
    }

    private @Nullable Statement statementOrThrow(List<Variable> locals) {
        var token = peek();
        if (token.is("{")) {
            return new Block(block(locals));
        }
        if (token.is(";")) {
            take();
            return null;
        }
        if (token.is("local")) {
            take();
            var type = type();
            do {
                var local = take();
                var size = 0;
                if (accept("[")) {
                    size = Integer.parseInt(take().text());
                    expect("]");
                }
                locals.add(new Variable(local.text(), type, size));
            } while (accept(","));
            expect(";");
            return null;
        }
        if (token.is("if")) {
            take();
            expect("(");
            var condition = expression();
            expect(")");
            var then = orEmpty(statement(locals));
            Statement otherwise = null;
            if (accept("else")) {
                otherwise = orEmpty(statement(locals));
            }
            return new If(condition, then, otherwise);
        }
        if (token.is("for")) {
            take();
            expect("(");
            var start = peek().is(";") ? null : assignment();
            expect(";");
            var condition = peek().is(";") ? null : expression();
            expect(";");
            var step = peek().is(")") ? null : assignment();
            expect(")");
            return new For(start, condition, step, orEmpty(statement(locals)));
        }
        if (token.is("while")) {
            take();
            expect("(");
            var condition = expression();
            expect(")");
            return new While(condition, orEmpty(statement(locals)));
        }
        if (token.is("do")) {
            take();
            var body = orEmpty(statement(locals));
            expect("until");
            expect("(");
            var condition = expression();
            expect(")");
            accept(";");
            return new DoUntil(body, condition);
        }
        if (token.is("switch")) {
            return switchStatement(locals);
        }
        if (token.is("break")) {
            take();
            expect(";");
            return new Break();
        }
        if (token.is("continue")) {
            take();
            expect(";");
            return new Continue();
        }
        if (token.is("return")) {
            take();
            var value = peek().is(";") ? null : expression();
            expect(";");
            return new Return(value);
        }
        if (token.is("foreach")
                || token.is("goto")
                || token.is("assert")
                || token.is("stop")
                || token.is("case")
                || token.is("default")) {
            throw new Unparsed("no " + token.text());
        }
        var line = token.line();
        var run = assignment();
        expect(";");
        return new Run(run, line);
    }

    private Statement switchStatement(List<Variable> locals) {
        take();
        expect("(");
        var value = expression();
        expect(")");
        expect("{");
        var cases = new ArrayList<Case>();
        List<Statement> current = null;
        Expression label = null;
        var open = false;
        while (!atEnd() && !peek().is("}")) {
            if (peek().is("case") || peek().is("default")) {
                if (open) {
                    cases.add(new Case(label, List.copyOf(current)));
                }
                var isDefault = take().is("default");
                label = isDefault ? null : expression();
                expect(":");
                current = new ArrayList<>();
                open = true;
                continue;
            }
            if (!open) {
                throw new Unparsed("case expected");
            }
            var statement = statement(locals);
            if (statement != null) {
                current.add(statement);
            }
        }
        if (open) {
            cases.add(new Case(label, List.copyOf(current)));
        }
        expect("}");
        return new Switch(value, List.copyOf(cases));
    }

    private Expression assignment() {
        var target = expression();
        if (ASSIGNMENTS.contains(peek().text())) {
            var operator = take().text();
            return new Assign(operator, target, expression());
        }
        return target;
    }

    private Expression expression() {
        return binary(100);
    }

    private Expression binary(int loosest) {
        var left = unary();
        while (true) {
            var token = peek();
            var key = token.kind() == Kind.WORD ? token.lower() : token.text();
            var precedence = PRECEDENCE.get(key);
            if (precedence == null
                    || precedence > loosest
                    || token.kind() != Kind.SYMBOL && token.kind() != Kind.WORD) {
                return left;
            }
            if (token.kind() == Kind.WORD && !key.equals("dot") && !key.equals("cross")) {
                return left;
            }
            take();
            var right = binary(precedence - 1);
            left = new Binary(key, left, right);
        }
    }

    private Expression unary() {
        var token = peek();
        if (token.is("!") || token.is("-") || token.is("~") || token.is("+")) {
            take();
            var operand = unary();
            return token.is("+") ? operand : new Unary(token.text(), operand);
        }
        if (token.is("++") || token.is("--")) {
            take();
            return new Step(unary(), token.is("++") ? 1 : -1, false);
        }
        return postfix(primary());
    }

    private Expression postfix(Expression base) {
        var expression = base;
        while (true) {
            if (peek().is(".") && peek(1).kind() == Kind.WORD) {
                take();
                var member = take();
                if (peek().is("(")) {
                    expression = new Call(expression, member.text(), arguments(), false, member.line());
                } else {
                    expression = new Member(expression, member.text(), member.line());
                }
            } else if (peek().is("[")) {
                take();
                var index = expression();
                expect("]");
                expression = new Element(expression, index);
            } else if (peek().is("++") || peek().is("--")) {
                var by = take().is("++") ? 1 : -1;
                expression = new Step(expression, by, true);
            } else {
                return expression;
            }
        }
    }

    private List<Expression> arguments() {
        expect("(");
        var arguments = new ArrayList<Expression>();
        while (!atEnd() && !accept(")")) {
            if (peek().is(",")) {
                arguments.add(new Constant(null));
                take();
                continue;
            }
            arguments.add(expression());
            if (!peek().is(")")) {
                expect(",");
            }
        }
        return arguments;
    }

    private Expression primary() {
        var token = take();
        switch (token.kind()) {
            case INT -> {
                return new Constant(Integer.parseInt(token.text()));
            }
            case FLOAT -> {
                return new Constant(Float.parseFloat(token.text()));
            }
            case STRING -> {
                return new Constant(token.text());
            }
            case NAME -> {
                return new Constant(new Name(token.text()));
            }
            case END -> throw new Unparsed("unexpected end");
            default -> {
            }
        }
        if (token.is("(")) {
            var inner = expression();
            expect(")");
            return inner;
        }
        if (token.kind() != Kind.WORD) {
            throw new Unparsed("unexpected '" + token.text() + "'");
        }
        var word = token.lower();
        switch (word) {
            case "true" -> {
                return new Constant(true);
            }
            case "false" -> {
                return new Constant(false);
            }
            case "none" -> {
                return new Constant(null);
            }
            case "self" -> {
                return new Self();
            }
            case "vect", "rot" -> {
                if (peek().is("(")) {
                    var values = arguments();
                    return word.equals("vect")
                            ? new VectorOf(values.get(0), values.get(1), values.get(2))
                            : new RotatorOf(values.get(0), values.get(1), values.get(2));
                }
            }
            case "super", "global" -> {
                return superCall(token);
            }
            case "class" -> {
                return classExpression();
            }
            default -> {
            }
        }
        if (peek().is("(")) {
            var arguments = arguments();
            if (CASTS.contains(word) && arguments.size() == 1) {
                return new Cast(word, arguments.getFirst());
            }
            if (word.equals("dynamicloadobject")
                    && !arguments.isEmpty()
                    && arguments.getFirst() instanceof Constant constant
                    && constant.value() instanceof String effect
                    && effect.contains(".")) {
                classes.add(effect);
            }
            return new Call(null, token.text(), arguments, false, token.line());
        }
        if (word.startsWith("phys_")) {
            return new Constant(new Enum(token.text()));
        }
        return new Word(token.text(), token.line());
    }

    private Expression superCall(Token token) {
        if (accept("(")) {
            skipParentheses();
        }
        expect(".");
        var function = take();
        var arguments = arguments();
        return new Call(null, function.text(), arguments, token.is("super"), function.line());
    }

    private Expression classExpression() {
        if (peek().kind() == Kind.NAME) {
            var literal = take().text();
            if (literal.contains(".")) {
                classes.add(literal);
            }
            return new Constant(new ClassRef(literal));
        }
        if (accept("<")) {
            while (!atEnd() && !accept(">")) {
                take();
            }
            var arguments = arguments();
            return new Cast("class", arguments.isEmpty() ? new Constant(null) : arguments.getFirst());
        }
        throw new Unparsed("class literal expected");
    }

    private static final class Unparsed extends RuntimeException {
        Unparsed(String message) {
            super(message, null, false, false);
        }
    }

    private static Statement orEmpty(@Nullable Statement statement) {
        return statement == null ? new Block(List.of()) : statement;
    }
}
