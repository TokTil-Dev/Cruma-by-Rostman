package com.rost.cruma.core.npc.script;

import static com.rost.cruma.core.npc.script.ScriptValues.SELF;

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
import com.rost.cruma.core.npc.script.ScriptValues.Coords;
import com.rost.cruma.core.npc.script.ScriptValues.Enum;
import com.rost.cruma.core.npc.script.ScriptValues.Name;
import com.rost.cruma.core.npc.script.ScriptValues.Rotator;
import com.rost.cruma.core.npc.script.ScriptValues.Vector;
import com.rost.cruma.core.npc.script.ScriptedClass.Default;
import com.rost.cruma.core.npc.script.ScriptedClass.Found;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ScriptPawn {
    static final Set<String> BUILTINS = Set.of(
            "spawn",
            "attachtobone",
            "attachtobonewithindex",
            "detachfrombone",
            "playanim",
            "loopanim",
            "getbonecoords",
            "rand",
            "frand",
            "min",
            "max",
            "abs",
            "fmin",
            "fmax",
            "clamp",
            "fclamp",
            "sqrt",
            "sin",
            "cos",
            "vsize",
            "normal",
            "vector2rotator",
            "rotator2vector",
            "dynamicloadobject");

    static final Set<String> EFFECT_METHODS = Set.of(
            "ndestroy",
            "destroy",
            "setsizescale",
            "setrelativelocation",
            "setrelativerotation",
            "setphysics",
            "kill",
            "setbase");

    static final Set<String> EFFECT_PROPERTIES = Set.of(
            "brelativetrail",
            "btrailersamerotation",
            "btrailerprepivot",
            "btrailernoownerdestroy",
            "relativetrailoffset",
            "trailerprepivot",
            "relativelocation",
            "relativerotation",
            "physics");

    static final Set<String> PAWN_PROPERTIES = Set.of("location", "rotation", "collisionheight", "collisionradius");

    private static final Logger log = LogManager.getLogger(ScriptPawn.class);
    private static final int MOST_STEPS = 100_000;
    private static final int DEEPEST_CALLS = 64;
    private static final float TURN_TO_RADIANS = (float) (Math.PI * 2 / 65536);
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final ScriptedClass type;
    private final Host host;
    private final Map<String, Object> fields = new HashMap<>();
    private final List<ScriptEffect> effects = new ArrayList<>();
    private int skipped;
    private int steps;
    private int depth;

    public ScriptPawn(ScriptedClass type, Host host) {
        this(type, host, List.of());
    }

    public ScriptPawn(ScriptedClass type, Host host, List<Default> saved) {
        this.type = type;
        this.host = host;
        for (var entry : type.getVariables().entrySet()) {
            var variable = entry.getValue();
            var initial = type.getDefaultValue(entry.getKey());
            if (variable.size() > 0) {
                var array = new Object[variable.size()];
                for (var i = 0; i < array.length; i++) {
                    var given = initial instanceof Object[] values ? values[i] : null;
                    array[i] = given == null ? zero(variable.type()) : coerce(variable.type(), given);
                }
                fields.put(entry.getKey(), array);
            } else {
                fields.put(entry.getKey(), initial == null ? zero(variable.type()) : coerce(variable.type(), initial));
            }
        }
        for (var value : saved) {
            var key = value.name().toLowerCase(Locale.ROOT);
            var variable = type.getVariable(key);
            if (variable == null) {
                continue;
            }
            if (variable.size() > 0 && fields.get(key) instanceof Object[] array) {
                if (value.index() >= 0 && value.index() < array.length) {
                    array[value.index()] = coerce(variable.type(), value.value());
                }
            } else if (value.index() == 0) {
                fields.put(key, coerce(variable.type(), value.value()));
            }
        }
    }

    public interface Host {
        Vector getLocation();

        Rotator getRotation();

        float getCollisionHeight();

        float getCollisionRadius();

        boolean hasBone(String bone);

        @Nullable
        Coords getBoneCoords(String bone);

        boolean canSpawn(String effectClass);

        float random();

        default boolean playAnim(String sequence, float rate, float tweenTime, int channel) {
            return false;
        }

        default boolean loopAnim(String sequence, float rate, float tweenTime, int channel) {
            return false;
        }

        default @Nullable String findBone(int index) {
            return null;
        }
    }

    static Vector forward(Rotator rotator) {
        var pitch = rotator.pitch() * TURN_TO_RADIANS;
        var yaw = rotator.yaw() * TURN_TO_RADIANS;
        return new Vector((float) (Math.cos(pitch) * Math.cos(yaw)), (float) (Math.cos(pitch) * Math.sin(yaw)), (float)
                Math.sin(pitch));
    }

    static Rotator direction(Vector vector) {
        var yaw = Math.atan2(vector.y(), vector.x()) / TURN_TO_RADIANS;
        var pitch = Math.atan2(vector.z(), Math.hypot(vector.x(), vector.y())) / TURN_TO_RADIANS;
        return new Rotator((int) Math.round(pitch) & 0xFFFF, (int) Math.round(yaw) & 0xFFFF, 0);
    }

    static Vector turn(Rotator rotator, Vector vector) {
        var pitch = rotator.pitch() * TURN_TO_RADIANS;
        var yaw = rotator.yaw() * TURN_TO_RADIANS;
        var roll = rotator.roll() * TURN_TO_RADIANS;
        var sp = (float) Math.sin(pitch);
        var cp = (float) Math.cos(pitch);
        var sy = (float) Math.sin(yaw);
        var cy = (float) Math.cos(yaw);
        var sr = (float) Math.sin(roll);
        var cr = (float) Math.cos(roll);
        var x = new Vector(cp * cy, cp * sy, sp);
        var y = new Vector(sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp);
        var z = new Vector(-(cr * sp * cy + sr * sy), cy * sr - cr * sp * sy, cr * cp);
        return x.times(vector.x()).plus(y.times(vector.y())).plus(z.times(vector.z()));
    }

    private static final class Frame {
        final int level;
        final Map<String, Object> locals = new HashMap<>();
        final Map<String, String> types = new HashMap<>();

        @Nullable
        Object result;

        Frame(int level) {
            this.level = level;
        }

        void declare(Variable variable, @Nullable Object value) {
            types.put(variable.key(), variable.type());
            if (variable.size() > 0) {
                var array = new Object[variable.size()];
                for (var i = 0; i < array.length; i++) {
                    array[i] = zero(variable.type());
                }
                locals.put(variable.key(), array);
            } else {
                locals.put(variable.key(), value == null ? zero(variable.type()) : value);
            }
        }
    }

    private static final class Unsupported extends RuntimeException {
        Unsupported(String what) {
            super(what, null, false, false);
        }
    }

    private static final class Stop extends RuntimeException {
        Stop(String why) {
            super(why, null, false, false);
        }
    }

    public void postBeginPlay() {
        event("postbeginplay");
    }

    public void postSetPawnResource() {
        event("postsetpawnresource");
    }

    public void animBegin(String sequence) {
        event("animbegin", new Name(sequence));
    }

    public void animEnd(int channel) {
        event("animend", channel);
    }

    public void trigger() {
        event("trigger");
    }

    public void tick(float seconds) {
        event("tick", seconds);
    }

    public void notifyDie() {
        event("notifydie");
    }

    public void destroyed() {
        event("destroyed");
    }

    public List<ScriptEffect> getEffects() {
        return effects;
    }

    public void forget(ScriptEffect effect) {
        effects.remove(effect);
    }

    public int getSkipped() {
        return skipped;
    }

    @Nullable
    Object getField(String name) {
        return fields.get(name.toLowerCase(Locale.ROOT));
    }

    private void event(String key, Object... arguments) {
        var found = type.find(key, 0);
        if (found == null) {
            return;
        }
        steps = 0;
        depth = 0;
        try {
            invoke(found, List.of(arguments));
        } catch (RuntimeException stop) {
            log.debug("The script of {} stopped {}: {}", type.name(), key, stop.toString());
        }
    }

    private @Nullable Object invoke(Found found, List<Object> arguments) {
        if (++depth > DEEPEST_CALLS) {
            throw new Stop("calls too deep");
        }
        try {
            var function = found.function();
            var frame = new Frame(found.level());
            for (var i = 0; i < function.parameters().size(); i++) {
                var parameter = function.parameters().get(i);
                var given = i < arguments.size() ? arguments.get(i) : null;
                frame.declare(parameter, given == null ? zero(parameter.type()) : coerce(parameter.type(), given));
            }
            for (var local : function.locals()) {
                frame.declare(local, null);
            }
            for (var statement : function.body()) {
                if (execute(statement, frame) == Flow.RETURN) {
                    break;
                }
            }
            return frame.result;
        } finally {
            depth--;
        }
    }

    private enum Flow {
        NEXT,
        BREAK,
        CONTINUE,
        RETURN
    }

    private Flow execute(Statement statement, Frame frame) {
        if (++steps > MOST_STEPS) {
            throw new Stop("too many steps");
        }
        try {
            return run(statement, frame);
        } catch (Stop stop) {
            throw stop;
        } catch (RuntimeException unsupported) {
            skip(unsupported instanceof Unsupported ? unsupported.getMessage() : "error:" + unsupported);
            return Flow.NEXT;
        }
    }

    private Flow run(Statement statement, Frame frame) {
        switch (statement) {
            case Run run -> evaluate(run.expression(), frame);
            case Block block -> {
                return runAll(block.statements(), frame);
            }
            case If branch -> {
                if (isTrue(evaluate(branch.condition(), frame))) {
                    return execute(branch.then(), frame);
                }
                if (branch.otherwise() != null) {
                    return execute(branch.otherwise(), frame);
                }
            }
            case For loop -> {
                if (loop.start() != null) {
                    evaluate(loop.start(), frame);
                }
                while (loop.condition() == null || isTrue(evaluate(loop.condition(), frame))) {
                    var flow = execute(loop.body(), frame);
                    if (flow == Flow.BREAK) {
                        break;
                    }
                    if (flow == Flow.RETURN) {
                        return flow;
                    }
                    if (loop.step() != null) {
                        evaluate(loop.step(), frame);
                    }
                    countStep();
                }
            }
            case While loop -> {
                while (isTrue(evaluate(loop.condition(), frame))) {
                    var flow = execute(loop.body(), frame);
                    if (flow == Flow.BREAK) {
                        break;
                    }
                    if (flow == Flow.RETURN) {
                        return flow;
                    }
                    countStep();
                }
            }
            case DoUntil loop -> {
                do {
                    var flow = execute(loop.body(), frame);
                    if (flow == Flow.BREAK) {
                        break;
                    }
                    if (flow == Flow.RETURN) {
                        return flow;
                    }
                    countStep();
                } while (!isTrue(evaluate(loop.condition(), frame)));
            }
            case Switch choice -> {
                return runSwitch(choice, frame);
            }
            case Break _ -> {
                return Flow.BREAK;
            }
            case Continue _ -> {
                return Flow.CONTINUE;
            }
            case Return back -> {
                frame.result = back.value() == null ? null : evaluate(back.value(), frame);
                return Flow.RETURN;
            }
            case Skipped unknown -> skip("syntax:" + unknown.what());
        }
        return Flow.NEXT;
    }

    private Flow runAll(List<Statement> statements, Frame frame) {
        for (var inner : statements) {
            var flow = execute(inner, frame);
            if (flow != Flow.NEXT) {
                return flow;
            }
        }
        return Flow.NEXT;
    }

    private Flow runSwitch(Switch choice, Frame frame) {
        var value = evaluate(choice.value(), frame);
        var start = -1;
        for (var index = 0; index < choice.cases().size() && start < 0; index++) {
            var label = choice.cases().get(index).label();
            if (label != null && isSame(value, evaluate(label, frame))) {
                start = index;
            }
        }
        for (var index = 0; index < choice.cases().size() && start < 0; index++) {
            if (choice.cases().get(index).label() == null) {
                start = index;
            }
        }
        if (start < 0) {
            return Flow.NEXT;
        }
        for (var index = start; index < choice.cases().size(); index++) {
            var flow = runAll(choice.cases().get(index).statements(), frame);
            if (flow == Flow.BREAK) {
                return Flow.NEXT;
            }
            if (flow != Flow.NEXT) {
                return flow;
            }
        }
        return Flow.NEXT;
    }

    private void countStep() {
        if (++steps > MOST_STEPS) {
            throw new Stop("too many steps");
        }
    }

    private void skip(String what) {
        skipped++;
        if (REPORTED.add(type.name() + " " + what)) {
            log.debug("The script of {} skips {}", type.name(), what);
        }
    }

    private @Nullable Object evaluate(Expression expression, Frame frame) {
        return switch (expression) {
            case Constant constant -> constant.value();
            case Self _ -> SELF;
            case VectorOf vector ->
                new Vector(
                        number(evaluate(vector.x(), frame)),
                        number(evaluate(vector.y(), frame)),
                        number(evaluate(vector.z(), frame)));
            case RotatorOf rotator ->
                new Rotator(
                        (int) number(evaluate(rotator.pitch(), frame)),
                        (int) number(evaluate(rotator.yaw(), frame)),
                        (int) number(evaluate(rotator.roll(), frame)));
            case Word word -> read(word, frame);
            case Member member -> member(evaluate(member.target(), frame), member.key());
            case Element element -> element(evaluate(element.target(), frame), evaluate(element.index(), frame));
            case Call call -> call(call, frame);
            case Unary unary -> unary(unary.operator(), evaluate(unary.operand(), frame));
            case Step step -> {
                var before = evaluate(step.target(), frame);
                var after = arithmetic("+", before, step.by());
                assign(step.target(), after, frame);
                yield step.after() ? before : after;
            }
            case Binary binary -> binary(binary, frame);
            case Assign assignment -> {
                var value = evaluate(assignment.value(), frame);
                if (!assignment.operator().equals("=")) {
                    var operator = assignment.operator().substring(0, 1);
                    value = operator.equals("$") || operator.equals("@")
                            ? text(evaluate(assignment.target(), frame))
                                    + (operator.equals("@") ? " " : "")
                                    + text(value)
                            : arithmetic(operator, evaluate(assignment.target(), frame), value);
                }
                assign(assignment.target(), value, frame);
                yield value;
            }
            case Cast cast -> cast(cast.type(), evaluate(cast.value(), frame));
        };
    }

    private @Nullable Object read(Word word, Frame frame) {
        var key = word.key();
        if (frame.locals.containsKey(key)) {
            return frame.locals.get(key);
        }
        if (fields.containsKey(key)) {
            return fields.get(key);
        }
        return pawnProperty(key);
    }

    private Object pawnProperty(String key) {
        return switch (key) {
            case "location" -> host.getLocation();
            case "rotation" -> host.getRotation();
            case "collisionheight" -> host.getCollisionHeight();
            case "collisionradius" -> host.getCollisionRadius();
            default -> throw new Unsupported("name:" + key);
        };
    }

    private @Nullable Object member(@Nullable Object target, String key) {
        return switch (target) {
            case null -> null;
            case Vector vector ->
                switch (key) {
                    case "x" -> vector.x();
                    case "y" -> vector.y();
                    case "z" -> vector.z();
                    default -> throw new Unsupported("member:" + key);
                };
            case Rotator rotator ->
                switch (key) {
                    case "pitch" -> rotator.pitch();
                    case "yaw" -> rotator.yaw();
                    case "roll" -> rotator.roll();
                    default -> throw new Unsupported("member:" + key);
                };
            case Coords coords ->
                switch (key) {
                    case "origin" -> coords.origin();
                    case "xaxis" -> coords.xAxis();
                    case "yaxis" -> coords.yAxis();
                    case "zaxis" -> coords.zAxis();
                    default -> throw new Unsupported("member:" + key);
                };
            case ScriptEffect effect ->
                switch (key) {
                    case "brelativetrail" -> effect.isRelativeTrail();
                    case "btrailersamerotation" -> effect.isTrailerSameRotation();
                    case "btrailerprepivot" -> effect.isTrailerPrePivot();
                    case "btrailernoownerdestroy" -> effect.isTrailerNoOwnerDestroy();
                    case "relativetrailoffset" -> effect.getRelativeTrailOffset();
                    case "trailerprepivot" -> effect.getTrailerPrePivotOffset();
                    case "relativelocation" -> effect.getRelativeLocation();
                    case "relativerotation" -> effect.getRelativeRotation();
                    case "physics" -> new Enum(effect.isTrailer() ? "PHYS_Trailer" : "PHYS_None");
                    default -> throw new Unsupported("member:" + key);
                };
            default -> {
                if (target == SELF) {
                    yield fields.containsKey(key) ? fields.get(key) : pawnProperty(key);
                }
                throw new Unsupported("member:" + key);
            }
        };
    }

    private @Nullable Object element(@Nullable Object target, @Nullable Object index) {
        if (!(target instanceof Object[] array)) {
            throw new Unsupported("element");
        }
        var at = (int) number(index);
        return at >= 0 && at < array.length ? array[at] : null;
    }

    private void assign(Expression target, @Nullable Object value, Frame frame) {
        switch (target) {
            case Word word -> {
                var key = word.key();
                if (frame.locals.containsKey(key)) {
                    frame.locals.put(key, coerce(frame.types.get(key), value));
                } else if (fields.containsKey(key)) {
                    var variable = type.getVariable(key);
                    fields.put(key, variable == null ? value : coerce(variable.type(), value));
                } else {
                    throw new Unsupported("set:" + key);
                }
            }
            case Element element -> {
                if (!(evaluate(element.target(), frame) instanceof Object[] array)) {
                    throw new Unsupported("element");
                }
                var at = (int) number(evaluate(element.index(), frame));
                if (at >= 0 && at < array.length) {
                    array[at] = coerce(arrayType(element.target(), frame), value);
                }
            }
            case Member member -> {
                var owner = evaluate(member.target(), frame);
                switch (owner) {
                    case null -> {
                    }
                    case ScriptEffect effect -> setEffect(effect, member.key(), value);
                    case Vector vector -> assign(member.target(), withMember(vector, member.key(), value), frame);
                    case Rotator rotator -> assign(member.target(), withMember(rotator, member.key(), value), frame);
                    default -> throw new Unsupported("set:" + member.key());
                }
            }
            default -> throw new Unsupported("set");
        }
    }

    private @Nullable String arrayType(Expression array, Frame frame) {
        if (array instanceof Word word) {
            var key = word.key();
            if (frame.types.containsKey(key)) {
                return frame.types.get(key);
            }
            var variable = type.getVariable(key);
            return variable == null ? null : variable.type();
        }
        return null;
    }

    private @Nullable Object call(Call call, Frame frame) {
        if (call.target() != null) {
            var target = evaluate(call.target(), frame);
            if (target == null) {
                return null;
            }
            if (target instanceof ScriptEffect effect) {
                return effectCall(effect, call, frame);
            }
            if (target != SELF) {
                throw new Unsupported("method:" + call.key());
            }
        }
        var key = call.key();
        if (call.above()) {
            var found = type.find(key, frame.level + 1);
            return found == null ? null : invoke(found, values(call.arguments(), frame));
        }
        var found = type.find(key, 0);
        if (found != null) {
            return invoke(found, values(call.arguments(), frame));
        }
        return builtin(key, call.arguments(), frame);
    }

    private List<Object> values(List<Expression> arguments, Frame frame) {
        var values = new ArrayList<Object>(arguments.size());
        for (var argument : arguments) {
            values.add(evaluate(argument, frame));
        }
        return values;
    }

    private @Nullable Object effectCall(ScriptEffect effect, Call call, Frame frame) {
        var arguments = values(call.arguments(), frame);
        switch (call.key()) {
            case "ndestroy", "destroy" -> effect.destroy();
            case "setsizescale" -> {
                if (effect.isStarted()) {
                    throw new Unsupported("SetSizeScale after the effect started");
                }
                effect.scale(number(first(arguments)));
            }
            case "setrelativelocation" -> {
                var offset = vector(first(arguments));
                if (effect.getBone() != null) {
                    effect.setRelativeLocation(offset);
                } else if (effect.isBased()) {
                    effect.setRelativeLocation(turn(host.getRotation(), offset));
                }
            }
            case "setrelativerotation" -> effect.setRelativeRotation(rotator(first(arguments)));
            case "setphysics" ->
                effect.setTrailer(first(arguments) instanceof Enum physics
                        && physics.text().equalsIgnoreCase("PHYS_Trailer"));
            case "kill" -> effect.kill();
            case "setbase" -> {
                var base = first(arguments);
                if (base == SELF) {
                    effect.base(true, effect.getSpawnLocation().minus(host.getLocation()));
                } else if (base == null) {
                    effect.base(false, Vector.ZERO);
                } else {
                    throw new Unsupported("SetBase on another actor");
                }
            }
            default -> throw new Unsupported("method:" + call.key());
        }
        return true;
    }

    private @Nullable Object builtin(String key, List<Expression> arguments, Frame frame) {
        var values = values(arguments, frame);
        return switch (key) {
            case "spawn" -> spawn(values);
            case "attachtobone" -> attach(values);
            case "attachtobonewithindex" -> attachByIndex(values);
            case "playanim", "loopanim" -> {
                var sequence = text(first(values));
                var rate = values.size() > 1 && values.get(1) != null ? number(values.get(1)) : 1f;
                var tween = values.size() > 2 ? number(values.get(2)) : 0f;
                var channel = values.size() > 3 ? (int) number(values.get(3)) : 0;
                yield key.equals("playanim")
                        ? host.playAnim(sequence, rate, tween, channel)
                        : host.loopAnim(sequence, rate, tween, channel);
            }
            case "detachfrombone" -> {
                if (first(values) instanceof ScriptEffect effect) {
                    effect.attach(null, false);
                }
                yield true;
            }
            case "getbonecoords" -> {
                var coords = host.getBoneCoords(text(first(values)));
                yield coords == null
                        ? new Coords(host.getLocation(), new Vector(1, 0, 0), new Vector(0, 1, 0), new Vector(0, 0, 1))
                        : coords;
            }
            case "rand" -> (int) (host.random() * Math.max(0, (int) number(first(values))));
            case "frand" -> host.random();
            case "min" -> Math.min((int) number(values.get(0)), (int) number(values.get(1)));
            case "max" -> Math.max((int) number(values.get(0)), (int) number(values.get(1)));
            case "fmin" -> Math.min(number(values.get(0)), number(values.get(1)));
            case "fmax" -> Math.max(number(values.get(0)), number(values.get(1)));
            case "abs" -> Math.abs(number(first(values)));
            case "clamp" ->
                Math.clamp((int) number(values.get(0)), (int) number(values.get(1)), (int) number(values.get(2)));
            case "fclamp" -> Math.clamp(number(values.get(0)), number(values.get(1)), number(values.get(2)));
            case "sqrt" -> (float) Math.sqrt(number(first(values)));
            case "sin" -> (float) Math.sin(number(first(values)));
            case "cos" -> (float) Math.cos(number(first(values)));
            case "vsize" -> vector(first(values)).length();
            case "normal" -> {
                var vector = vector(first(values));
                var length = vector.length();
                yield length > 0 ? vector.times(1 / length) : Vector.ZERO;
            }
            case "vector2rotator" -> direction(vector(first(values)));
            case "rotator2vector" -> forward(rotator(first(values)));
            case "dynamicloadobject" -> new ClassRef(text(first(values)));
            default -> throw new Unsupported("call:" + key);
        };
    }

    private @Nullable Object spawn(List<Object> values) {
        if (!(first(values) instanceof ClassRef effectClass) || !host.canSpawn(effectClass.name())) {
            return null;
        }
        var owner = values.size() > 1 ? values.get(1) : null;
        var location = values.size() > 3 && values.get(3) != null ? vector(values.get(3)) : host.getLocation();
        var rotation = values.size() > 4 && values.get(4) != null ? rotator(values.get(4)) : host.getRotation();
        var effect = new ScriptEffect(effectClass.name(), location, rotation, owner == SELF);
        effects.add(effect);
        return effect;
    }

    private Object attach(List<Object> values) {
        if (!(first(values) instanceof ScriptEffect effect) || values.size() < 2) {
            return false;
        }
        var bone = text(values.get(1));
        if (!host.hasBone(bone)) {
            return false;
        }
        effect.attach(bone, values.size() > 2 && isTrue(values.get(2)));
        return true;
    }

    private Object attachByIndex(List<Object> values) {
        if (!(first(values) instanceof ScriptEffect effect) || values.size() < 2) {
            return false;
        }
        var bone = host.findBone((int) number(values.get(1)));
        if (bone == null) {
            return false;
        }
        effect.attach(bone, false);
        return true;
    }

    private @Nullable Object unary(String operator, @Nullable Object value) {
        return switch (operator) {
            case "!" -> !isTrue(value);
            case "-" ->
                switch (value) {
                    case Integer number -> -number;
                    case Vector vector -> vector.times(-1);
                    case null -> 0;
                    default -> -number(value);
                };
            case "~" -> ~(int) number(value);
            default -> throw new Unsupported("operator:" + operator);
        };
    }

    private @Nullable Object binary(Binary binary, Frame frame) {
        var operator = binary.operator();
        if (operator.equals("&&")) {
            return isTrue(evaluate(binary.left(), frame)) && isTrue(evaluate(binary.right(), frame));
        }
        if (operator.equals("||")) {
            return isTrue(evaluate(binary.left(), frame)) || isTrue(evaluate(binary.right(), frame));
        }
        var left = evaluate(binary.left(), frame);
        var right = evaluate(binary.right(), frame);
        return switch (operator) {
            case "==" -> isSame(left, right);
            case "!=" -> !isSame(left, right);
            case "~=" -> text(left).equalsIgnoreCase(text(right));
            case "^^" -> isTrue(left) ^ isTrue(right);
            case "<" -> number(left) < number(right);
            case ">" -> number(left) > number(right);
            case "<=" -> number(left) <= number(right);
            case ">=" -> number(left) >= number(right);
            case "$" -> text(left) + text(right);
            case "@" -> text(left) + " " + text(right);
            case "&" -> (int) number(left) & (int) number(right);
            case "|" -> (int) number(left) | (int) number(right);
            case "^" -> (int) number(left) ^ (int) number(right);
            case "<<" -> (int) number(left) << (int) number(right);
            case ">>", ">>>" -> (int) number(left) >> (int) number(right);
            case "dot" -> {
                var a = vector(left);
                var b = vector(right);
                yield a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
            }
            case "cross" -> {
                var a = vector(left);
                var b = vector(right);
                yield new Vector(
                        a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
            }
            case "**" -> (float) Math.pow(number(left), number(right));
            default -> arithmetic(operator, left, right);
        };
    }

    private static Vector withMember(Vector vector, String key, @Nullable Object value) {
        var number = number(value);
        return switch (key) {
            case "x" -> new Vector(number, vector.y(), vector.z());
            case "y" -> new Vector(vector.x(), number, vector.z());
            case "z" -> new Vector(vector.x(), vector.y(), number);
            default -> throw new Unsupported("set:" + key);
        };
    }

    private static Rotator withMember(Rotator rotator, String key, @Nullable Object value) {
        var number = (int) number(value);
        return switch (key) {
            case "pitch" -> new Rotator(number, rotator.yaw(), rotator.roll());
            case "yaw" -> new Rotator(rotator.pitch(), number, rotator.roll());
            case "roll" -> new Rotator(rotator.pitch(), rotator.yaw(), number);
            default -> throw new Unsupported("set:" + key);
        };
    }

    private static void setEffect(ScriptEffect effect, String key, @Nullable Object value) {
        switch (key) {
            case "brelativetrail" -> effect.setRelativeTrail(isTrue(value));
            case "btrailersamerotation" -> effect.setTrailerSameRotation(isTrue(value));
            case "btrailerprepivot" -> effect.setTrailerPrePivot(isTrue(value));
            case "btrailernoownerdestroy" -> effect.setTrailerNoOwnerDestroy(isTrue(value));
            case "relativetrailoffset" -> effect.setRelativeTrailOffset(vector(value));
            case "trailerprepivot" -> effect.setTrailerPrePivotOffset(vector(value));
            default -> throw new Unsupported("set:" + key);
        }
    }

    private static Object arithmetic(String operator, @Nullable Object left, @Nullable Object right) {
        if (left instanceof Vector || right instanceof Vector) {
            return vectorArithmetic(operator, left, right);
        }
        if (left instanceof Rotator a && right instanceof Rotator b) {
            return switch (operator) {
                case "+" -> new Rotator(a.pitch() + b.pitch(), a.yaw() + b.yaw(), a.roll() + b.roll());
                case "-" -> new Rotator(a.pitch() - b.pitch(), a.yaw() - b.yaw(), a.roll() - b.roll());
                default -> throw new Unsupported("operator:rotator " + operator);
            };
        }
        if (isWhole(left) && isWhole(right)) {
            var a = (int) number(left);
            var b = (int) number(right);
            return switch (operator) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? 0 : a / b;
                case "%" -> b == 0 ? 0f : (float) (a % b);
                default -> throw new Unsupported("operator:" + operator);
            };
        }
        var a = number(left);
        var b = number(right);
        return switch (operator) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? 0f : a / b;
            case "%" -> b == 0 ? 0f : a % b;
            default -> throw new Unsupported("operator:" + operator);
        };
    }

    private static Vector vectorArithmetic(String operator, @Nullable Object left, @Nullable Object right) {
        if (left instanceof Vector a && right instanceof Vector b) {
            return switch (operator) {
                case "+" -> a.plus(b);
                case "-" -> a.minus(b);
                case "*" -> new Vector(a.x() * b.x(), a.y() * b.y(), a.z() * b.z());
                default -> throw new Unsupported("operator:vector " + operator);
            };
        }
        var vector = left instanceof Vector a ? a : (Vector) right;
        var factor = number(left instanceof Vector ? right : left);
        return switch (operator) {
            case "*" -> vector.times(factor);
            case "/" -> factor == 0 ? Vector.ZERO : vector.times(1 / factor);
            default -> throw new Unsupported("operator:vector " + operator);
        };
    }

    private static boolean isWhole(@Nullable Object value) {
        return value == null || value instanceof Integer || value instanceof Boolean;
    }

    private static boolean isSame(@Nullable Object left, @Nullable Object right) {
        if (left instanceof Number || right instanceof Number) {
            if (isWhole(left) && isWhole(right)) {
                return (int) number(left) == (int) number(right);
            }
            return number(left) == number(right);
        }
        if (left instanceof Name || right instanceof Name || left instanceof String && right instanceof String) {
            return text(left).equalsIgnoreCase(text(right));
        }
        if (left instanceof Boolean || right instanceof Boolean) {
            return isTrue(left) == isTrue(right);
        }
        if (left instanceof Enum || left instanceof Vector || left instanceof Rotator || left instanceof ClassRef) {
            return left.equals(right);
        }
        return left == right;
    }

    private static @Nullable Object cast(String type, @Nullable Object value) {
        return switch (type) {
            case "class" -> value instanceof ClassRef ? value : null;
            case "string" -> text(value);
            default -> coerce(type, value);
        };
    }

    private static @Nullable Object coerce(@Nullable String type, @Nullable Object value) {
        if (type == null) {
            return value;
        }
        return switch (type) {
            case "int", "byte" -> value instanceof Enum ? value : (int) number(value);
            case "float" -> number(value);
            case "bool" -> isTrue(value);
            case "name" -> value instanceof Name ? value : new Name(text(value));
            case "string" -> text(value);
            case "vector" -> vector(value);
            case "rotator" -> rotator(value);
            default -> value;
        };
    }

    private static @Nullable Object zero(String type) {
        return switch (type) {
            case "int", "byte" -> 0;
            case "float" -> 0f;
            case "bool" -> false;
            case "name" -> Name.NONE;
            case "string" -> "";
            case "vector" -> Vector.ZERO;
            case "rotator" -> Rotator.ZERO;
            default -> null;
        };
    }

    private static @Nullable Object first(List<Object> values) {
        return values.isEmpty() ? null : values.getFirst();
    }

    private static boolean isTrue(@Nullable Object value) {
        return switch (value) {
            case null -> false;
            case Boolean bool -> bool;
            case Number number -> number.floatValue() != 0;
            default -> true;
        };
    }

    private static float number(@Nullable Object value) {
        return switch (value) {
            case null -> 0f;
            case Number number -> number.floatValue();
            case Boolean bool -> bool ? 1f : 0f;
            default -> throw new Unsupported("number from " + value.getClass().getSimpleName());
        };
    }

    private static String text(@Nullable Object value) {
        return switch (value) {
            case null -> "None";
            case Name name -> name.text();
            case ClassRef reference -> reference.name();
            default -> String.valueOf(value);
        };
    }

    private static Vector vector(@Nullable Object value) {
        return switch (value) {
            case null -> Vector.ZERO;
            case Vector vector -> vector;
            case Rotator rotator -> forward(rotator);
            default -> throw new Unsupported("vector from " + value.getClass().getSimpleName());
        };
    }

    private static Rotator rotator(@Nullable Object value) {
        return switch (value) {
            case null -> Rotator.ZERO;
            case Rotator rotator -> rotator;
            case Vector vector -> direction(vector);
            default -> throw new Unsupported("rotator from " + value.getClass().getSimpleName());
        };
    }
}
