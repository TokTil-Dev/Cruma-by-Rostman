package com.rost.cruma.core.npc;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class ServerAi implements AutoCloseable {
    private static final int MOST_STEPS = 100_000;
    private static final Pattern NUMBER = Pattern.compile("-?[0-9.]+");
    private static final Pattern FUNCTION = Pattern.compile("func\\[(.*)]");
    private static final Pattern HEADER = Pattern.compile("class\\s+\\d+\\s+(\\S+)\\s*:\\s*(\\S+)");
    private static final byte[] CLASS = "\nclass ".getBytes(StandardCharsets.UTF_16LE);
    private static final String NO_PARENT = "(null)";

    private final ByteBuffer file;
    private final Map<String, Place> places;
    private final Map<String, Script> scripts = new HashMap<>();
    private final @Nullable Arena mapping;

    private ServerAi(ByteBuffer file, Map<String, Place> places, @Nullable Arena mapping) {
        this.file = file;
        this.places = places;
        this.mapping = mapping;
    }

    public interface Calls {
        @Nullable
        Object call(String function, List<@Nullable Object> arguments);

        default @Nullable Object getParameter(String name) {
            return null;
        }
    }

    public static final class Slots {
        private final Map<String, Object> values = new HashMap<>();

        public Slots set(String name, @Nullable Object value) {
            if (value == null) {
                values.remove(name);
            } else {
                values.put(name, value);
            }
            return this;
        }

        public @Nullable Object get(String name) {
            return values.get(name);
        }
    }

    private record Place(String parent, int start, int end) {}

    private record Script(Map<String, Handler> handlers, Map<String, Object> parameters) {}

    private record Handler(List<Line> lines, Map<String, Integer> labels, Map<String, String> strings) {}

    private record Line(String name, String argument, String note) {}

    private record Field(Slots owner, String name) {}

    private record Named(String name) {}

    private record StackTop() {}

    public static ServerAi open(Path file) throws IOException {
        var mapping = Arena.ofShared();
        try (var channel = FileChannel.open(file)) {
            var bytes = channel.map(MapMode.READ_ONLY, 0, channel.size(), mapping)
                    .asByteBuffer()
                    .order(ByteOrder.LITTLE_ENDIAN);
            return new ServerAi(bytes, findClasses(bytes), mapping);
        } catch (IOException | RuntimeException failure) {
            mapping.close();
            throw failure;
        }
    }

    public static ServerAi of(String text) {
        var bytes = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_16LE)).order(ByteOrder.LITTLE_ENDIAN);
        return new ServerAi(bytes, findClasses(bytes), null);
    }

    @Override
    public void close() {
        if (mapping != null) {
            mapping.close();
        }
    }

    public boolean has(String className) {
        return places.containsKey(className);
    }

    public boolean handles(String className, String event) {
        return owner(className, event) != null;
    }

    public void run(String className, String event, Slots frame, Calls calls) {
        var owner = owner(className, event);
        if (owner != null) {
            new Run(className, owner, event, frame, calls).run();
        }
    }

    private @Nullable String owner(String className, String event) {
        var name = className;
        for (var parents = 0; parents < places.size() && name != null; parents++) {
            if (script(name).handlers().containsKey(event)) {
                return name;
            }
            name = parent(name);
        }
        return null;
    }

    private @Nullable Object parameter(String className, String name) {
        var at = className;
        for (var parents = 0; parents < places.size() && at != null; parents++) {
            var value = script(at).parameters().get(name);
            if (value != null) {
                return value;
            }
            at = parent(at);
        }
        return null;
    }

    private @Nullable String parent(String className) {
        var place = places.get(className);
        return place == null || place.parent().equals(NO_PARENT) ? null : place.parent();
    }

    private Script script(String className) {
        return scripts.computeIfAbsent(className, name -> {
            var place = places.get(name);
            return place == null ? new Script(Map.of(), Map.of()) : parse(text(file, place.start(), place.end()));
        });
    }

    private static Map<String, Place> findClasses(ByteBuffer file) {
        var starts = new ArrayList<Integer>();
        var last = file.limit() - CLASS.length;
        var first = CLASS[0];
        for (var at = 0; at <= last; at += 2) {
            if (file.get(at) == first && matches(file, at)) {
                starts.add(at + 2);
            }
        }
        var places = new HashMap<String, Place>(starts.size() * 2);
        for (var i = 0; i < starts.size(); i++) {
            var start = starts.get(i);
            var end = i + 1 < starts.size() ? starts.get(i + 1) : file.limit();
            var header = HEADER.matcher(text(file, start, Math.min(end, start + 512)));
            if (header.lookingAt()) {
                places.put(header.group(1), new Place(header.group(2), start, end));
            }
        }
        return places;
    }

    private static boolean matches(ByteBuffer file, int at) {
        for (var i = 1; i < CLASS.length; i++) {
            if (file.get(at + i) != CLASS[i]) {
                return false;
            }
        }
        return true;
    }

    private static String text(ByteBuffer file, int start, int end) {
        var bytes = new byte[end - start];
        file.get(start, bytes);
        return new String(bytes, StandardCharsets.UTF_16LE);
    }

    private static Script parse(String text) {
        var handlers = new HashMap<String, Handler>();
        var parameters = new HashMap<String, Object>();
        var inParameters = false;
        String name = null;
        List<Line> lines = new ArrayList<>();
        Map<String, Integer> labels = new HashMap<>();
        Map<String, String> strings = new HashMap<>();
        for (var raw : text.split("\r?\n")) {
            var line = raw.strip();
            if (line.startsWith("parameter_define_")) {
                inParameters = line.endsWith("begin");
            } else if (inParameters) {
                readParameter(line, parameters);
            } else if (line.startsWith("handler_end")) {
                if (name != null) {
                    handlers.put(name, new Handler(lines, labels, strings));
                }
                name = null;
            } else if (line.startsWith("handler ")) {
                name = note(line);
                lines = new ArrayList<>();
                labels = new HashMap<>();
                strings = new HashMap<>();
            } else if (name != null && !line.isEmpty() && !line.startsWith("\"") && !line.startsWith("variable_")) {
                read(line, lines, labels, strings);
            }
        }
        return new Script(handlers, parameters);
    }

    private static void readParameter(String line, Map<String, Object> parameters) {
        var parts = line.split("\\s+", 3);
        if (parts.length == 3) {
            if (parts[0].equals("string")) {
                parameters.put(parts[1], parts[2].replace("\"", ""));
            } else if (NUMBER.matcher(parts[2]).matches()) {
                parameters.put(parts[1], Double.valueOf(parts[2]));
            }
        }
    }

    private static void read(String line, List<Line> lines, Map<String, Integer> labels, Map<String, String> strings) {
        var dot = line.indexOf('.');
        if (line.charAt(0) == 'S' && dot > 0 && line.indexOf('"') > dot) {
            strings.put(line.substring(0, dot), line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')));
        } else if (line.charAt(0) == 'L' && line.indexOf(' ') < 0) {
            labels.put(line, lines.size());
        } else {
            var parts = code(line).split("\\s+");
            lines.add(new Line(parts[0], parts.length > 1 ? parts[1] : "", note(line)));
        }
    }

    private static String code(String line) {
        var note = line.indexOf("//");
        return note < 0 ? line : line.substring(0, note).strip();
    }

    private static String note(String line) {
        var note = line.indexOf("//");
        return note < 0 ? "" : line.substring(note + 2).strip();
    }

    private final class Run {
        private final String npcClass;
        private final String owner;
        private final String event;
        private final Slots frame;
        private final Calls calls;
        private final Handler handler;
        private final List<@Nullable Object> stack = new ArrayList<>();
        private final Set<Integer> unknownTests = new HashSet<>();

        private Run(String npcClass, String owner, String event, Slots frame, Calls calls) {
            this.npcClass = npcClass;
            this.owner = owner;
            this.event = event;
            this.frame = frame;
            this.calls = calls;
            handler = script(owner).handlers().get(event);
        }

        private void run() {
            var lines = handler.lines();
            var at = 0;
            for (var steps = 0; steps < MOST_STEPS && at < lines.size(); steps++) {
                var line = lines.get(at);
                switch (line.name()) {
                    case "jump" -> {
                        at = label(line);
                        continue;
                    }
                    case "branch_false" -> {
                        if (!holds(pop(), at)) {
                            at = label(line);
                            continue;
                        }
                    }
                    case "branch_true" -> {
                        if (Boolean.TRUE.equals(truth(pop()))) {
                            at = label(line);
                            continue;
                        }
                    }
                    case "exit_handler" -> {
                        return;
                    }
                    case "call_super" -> callSuper();
                    case "add" -> add(lines.get(at - 1));
                    case "func_call" -> call(line, at + 1 < lines.size() ? lines.get(at + 1) : line);
                    default -> step(line);
                }
                at++;
            }
        }

        private boolean holds(@Nullable Object test, int at) {
            var known = truth(test);
            return known == null ? unknownTests.add(at) : known;
        }

        private int label(Line line) {
            return handler.labels()
                    .getOrDefault(line.argument(), handler.lines().size());
        }

        private void callSuper() {
            var parent = parent(owner);
            var next = parent == null ? null : owner(parent, event);
            if (next != null) {
                new Run(npcClass, next, event, frame, calls).run();
            }
        }

        private void step(Line line) {
            switch (line.name()) {
                case "push_event" -> stack.add(frame);
                case "push_const" -> stack.add(Double.valueOf(line.argument()));
                case "push_string" -> stack.add(handler.strings().get(line.argument()));
                case "push_reg_sp" -> stack.add(new StackTop());
                case "push_parameter" -> {
                    var own = calls.getParameter(line.argument());
                    stack.add(own == null ? parameter(npcClass, line.argument()) : own);
                }
                case "push_property" -> stack.add(null);
                case "fetch_i", "fetch_i4", "fetch_d", "fetch_f" -> fetch();
                case "assign", "assign4" -> assign();
                case "shift_sp" -> shift(Integer.parseInt(line.argument()));
                case "add_string" -> {
                    pop();
                    pop();
                    stack.add(null);
                }
                case "negate" -> {
                    var value = number(pop());
                    stack.add(value == null ? null : Double.valueOf(-value));
                }
                case "not" -> {
                    var value = truth(pop());
                    stack.add(value == null ? null : flag(!value));
                }
                default -> sum(line.name());
            }
        }

        private void add(Line offset) {
            var right = pop();
            var left = pop();
            if (left instanceof Slots owner) {
                stack.add(new Field(owner, offset.note()));
            } else {
                stack.add(sum("add", number(left), number(right)));
            }
        }

        private void fetch() {
            var top = pop();
            if (top instanceof Field field) {
                var value = field.owner().get(field.name());
                stack.add(value == null ? new Named(field.name()) : value);
            } else if (top instanceof StackTop) {
                stack.add(stack.getLast());
            } else {
                stack.add(top);
            }
        }

        private void assign() {
            var value = pop();
            var target = pop();
            if (target instanceof Field field) {
                field.owner().set(field.name(), value instanceof Named ? null : value);
            }
            pop();
            stack.add(value);
        }

        private void shift(int by) {
            for (var i = 0; i < -by; i++) {
                pop();
            }
            for (var i = 0; i < by; i++) {
                stack.add(null);
            }
        }

        private void call(Line line, Line next) {
            var matcher = FUNCTION.matcher(line.note());
            var name = matcher.find() ? matcher.group(1) : "";
            var at = stack.size() - 1;
            while (at >= 0
                    && !(stack.get(at) instanceof Named named && named.name().equals(name))) {
                at--;
            }
            if (at < 0 && next.name().equals("shift_sp")) {
                at = stack.size() - 1 + Integer.parseInt(next.argument());
            }
            var arguments = new ArrayList<@Nullable Object>();
            for (var i = at + 1; i < stack.size(); i++) {
                var argument = stack.get(i);
                arguments.add(argument instanceof Named ? null : argument);
            }
            var result = calls.call(name, arguments);
            if (at >= 0) {
                stack.set(at, result);
            } else {
                stack.add(result);
            }
        }

        private void sum(String name) {
            var right = pop();
            var left = pop();
            if (left instanceof String one && right instanceof String other) {
                stack.add(text(name, one, other));
            } else {
                stack.add(sum(name, number(left), number(right)));
            }
        }

        private @Nullable Double text(String name, String left, String right) {
            return switch (name) {
                case "equal" -> flag(left.equals(right));
                case "not_equal" -> flag(!left.equals(right));
                default -> null;
            };
        }

        private @Nullable Double sum(String name, @Nullable Double left, @Nullable Double right) {
            if (left == null || right == null) {
                return null;
            }
            return switch (name) {
                case "add" -> left + right;
                case "sub" -> left - right;
                case "mul" -> left * right;
                case "div" -> right == 0 ? null : Double.valueOf(left / right);
                case "mod" -> right == 0 ? null : Double.valueOf(left % right);
                case "equal" -> flag(left.equals(right));
                case "not_equal" -> flag(!left.equals(right));
                case "less" -> flag(left < right);
                case "less_equal" -> flag(left <= right);
                case "greater" -> flag(left > right);
                case "greater_equal" -> flag(left >= right);
                case "and" -> flag(left != 0 && right != 0);
                case "or" -> flag(left != 0 || right != 0);
                case "bit_and" -> (double) (left.longValue() & right.longValue());
                case "bit_or" -> (double) (left.longValue() | right.longValue());
                default -> null;
            };
        }

        private @Nullable Object pop() {
            return stack.isEmpty() ? null : stack.removeLast();
        }

        private static Double flag(boolean value) {
            return value ? 1.0 : 0.0;
        }

        private static @Nullable Double number(@Nullable Object value) {
            return value instanceof Double number ? number : null;
        }

        private static @Nullable Boolean truth(@Nullable Object value) {
            return value instanceof Double number ? Boolean.valueOf(number != 0) : null;
        }
    }
}
