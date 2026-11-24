package com.rost.cruma.core.npc.script;

import com.rost.cruma.core.npc.script.ScriptTree.Assign;
import com.rost.cruma.core.npc.script.ScriptTree.Binary;
import com.rost.cruma.core.npc.script.ScriptTree.Block;
import com.rost.cruma.core.npc.script.ScriptTree.Break;
import com.rost.cruma.core.npc.script.ScriptTree.Call;
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
import com.rost.cruma.core.npc.script.ScriptTree.VectorOf;
import com.rost.cruma.core.npc.script.ScriptTree.While;
import com.rost.cruma.core.npc.script.ScriptTree.Word;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class ScriptCheck {
    private static final Set<String> PLAIN = Set.of(
            "int", "float", "bool", "byte", "name", "string", "vector", "rotator", "coords", "enum", "array", "class");

    private final ScriptedClass type;
    private final Set<String> found = new TreeSet<>();
    private Map<String, String> names = Map.of();

    private ScriptCheck(ScriptedClass type) {
        this.type = type;
    }

    static Set<String> unsupported(ScriptedClass type) {
        var check = new ScriptCheck(type);
        for (var script : type.getChain()) {
            for (var skipped : script.getSkipped()) {
                if (!skipped.startsWith("state@")
                        && !skipped.startsWith("replication@")
                        && !skipped.startsWith("defaultproperties@")) {
                    check.found.add("syntax:" + skipped.replaceAll("@.*", ""));
                }
            }
            for (var function : script.getFunctions().values()) {
                check.function(function);
            }
        }
        return check.found;
    }

    private void function(Function function) {
        var local = new HashMap<String, String>();
        for (var parameter : function.parameters()) {
            local.put(parameter.key(), parameter.type());
        }
        for (var variable : function.locals()) {
            local.put(variable.key(), variable.type());
        }
        names = local;
        statements(function.body());
    }

    private void statements(List<Statement> statements) {
        for (var statement : statements) {
            statement(statement);
        }
    }

    private void statement(Statement statement) {
        switch (statement) {
            case Run run -> expression(run.expression());
            case Block block -> statements(block.statements());
            case If branch -> {
                expression(branch.condition());
                statement(branch.then());
                if (branch.otherwise() != null) {
                    statement(branch.otherwise());
                }
            }
            case For loop -> {
                optional(loop.start());
                optional(loop.condition());
                optional(loop.step());
                statement(loop.body());
            }
            case While loop -> {
                expression(loop.condition());
                statement(loop.body());
            }
            case DoUntil loop -> {
                statement(loop.body());
                expression(loop.condition());
            }
            case Switch choice -> {
                expression(choice.value());
                for (var item : choice.cases()) {
                    if (item.label() != null) {
                        expression(item.label());
                    }
                    statements(item.statements());
                }
            }
            case Return back -> optional(back.value());
            case Skipped unknown -> found.add("syntax:" + unknown.what());
            case Break _, Continue _ -> {
            }
        }
    }

    private void optional(Expression expression) {
        if (expression != null) {
            expression(expression);
        }
    }

    private void expression(Expression expression) {
        switch (expression) {
            case Constant _, Self _ -> {
            }
            case VectorOf vector -> {
                expression(vector.x());
                expression(vector.y());
                expression(vector.z());
            }
            case RotatorOf rotator -> {
                expression(rotator.pitch());
                expression(rotator.yaw());
                expression(rotator.roll());
            }
            case Word word -> {
                if (!isKnown(word.key())) {
                    found.add("name:" + word.name());
                }
            }
            case Member member -> {
                expression(member.target());
                if (isEffect(member.target()) && !ScriptPawn.EFFECT_PROPERTIES.contains(member.key())) {
                    found.add("member:" + member.name());
                }
            }
            case Element element -> {
                expression(element.target());
                expression(element.index());
            }
            case Call call -> call(call);
            case Unary unary -> expression(unary.operand());
            case Step step -> expression(step.target());
            case Binary binary -> {
                expression(binary.left());
                expression(binary.right());
            }
            case Assign assignment -> {
                expression(assignment.target());
                expression(assignment.value());
            }
            case Cast cast -> expression(cast.value());
        }
    }

    private void call(Call call) {
        for (var argument : call.arguments()) {
            expression(argument);
        }
        var target = call.target();
        if (target != null && !(target instanceof Self)) {
            expression(target);
            if (isEffect(target) && !ScriptPawn.EFFECT_METHODS.contains(call.key())) {
                found.add("method:" + call.name());
            } else if (!isEffect(target)) {
                found.add("method:" + call.name());
            }
            return;
        }
        if (call.above()) {
            return;
        }
        if (!type.declares(call.name()) && !ScriptPawn.BUILTINS.contains(call.key())) {
            found.add("call:" + call.name());
        }
    }

    private boolean isKnown(String key) {
        return names.containsKey(key) || type.getVariable(key) != null || ScriptPawn.PAWN_PROPERTIES.contains(key);
    }

    private boolean isEffect(Expression expression) {
        var word = switch (expression) {
            case Word plain -> plain;
            case Element element when element.target() instanceof Word array -> array;
            default -> null;
        };
        if (word == null) {
            return false;
        }
        var local = names.get(word.key());
        if (local != null) {
            return !PLAIN.contains(local);
        }
        var variable = type.getVariable(word.key());
        return variable != null && !PLAIN.contains(variable.type());
    }
}
