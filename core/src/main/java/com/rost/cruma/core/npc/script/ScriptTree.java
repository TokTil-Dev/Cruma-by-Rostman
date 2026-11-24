package com.rost.cruma.core.npc.script;

import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class ScriptTree {
    private ScriptTree() {}

    record Variable(String name, String type, int size) {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    record Function(String name, List<Variable> parameters, List<Variable> locals, List<Statement> body, String owner) {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    sealed interface Expression {}

    record Constant(@Nullable Object value) implements Expression {}

    record Self() implements Expression {}

    record VectorOf(Expression x, Expression y, Expression z) implements Expression {}

    record RotatorOf(Expression pitch, Expression yaw, Expression roll) implements Expression {}

    record Word(String name, int line) implements Expression {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    record Member(Expression target, String name, int line) implements Expression {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    record Element(Expression target, Expression index) implements Expression {}

    record Call(@Nullable Expression target, String name, List<Expression> arguments, boolean above, int line)
            implements Expression {
        String key() {
            return name.toLowerCase(Locale.ROOT);
        }
    }

    record Unary(String operator, Expression operand) implements Expression {}

    record Step(Expression target, int by, boolean after) implements Expression {}

    record Binary(String operator, Expression left, Expression right) implements Expression {}

    record Assign(String operator, Expression target, Expression value) implements Expression {}

    record Cast(String type, Expression value) implements Expression {}

    sealed interface Statement {}

    record Run(Expression expression, int line) implements Statement {}

    record Block(List<Statement> statements) implements Statement {}

    record If(
            Expression condition, Statement then, @Nullable Statement otherwise) implements Statement {}

    record For(
            @Nullable Expression start,
            @Nullable Expression condition,
            @Nullable Expression step,
            Statement body) implements Statement {}

    record While(Expression condition, Statement body) implements Statement {}

    record DoUntil(Statement body, Expression condition) implements Statement {}

    record Case(@Nullable Expression label, List<Statement> statements) {}

    record Switch(Expression value, List<Case> cases) implements Statement {}

    record Break() implements Statement {}

    record Continue() implements Statement {}

    record Return(@Nullable Expression value) implements Statement {}

    record Skipped(String what, int line) implements Statement {}
}
