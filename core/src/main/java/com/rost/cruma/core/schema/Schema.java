package com.rost.cruma.core.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class Schema {
    static final Map<String, Basic> TYPE_WORDS = typeWords();

    private final Parts parts;
    private final String source;
    private final @Nullable Path path;
    private final boolean builtIn;
    private final String text;
    private final boolean resolved;
    private final Set<String> names;
    private final boolean gameNames;
    private final int fieldCount;
    private final @Nullable Pattern filePattern;
    private volatile @Nullable Resolution resolution;
    final Map<Set<String>, SchemaReader> readers = new ConcurrentHashMap<>();

    private Schema(Parts parts, String source, @Nullable Path path, boolean builtIn, String text, boolean resolved) {
        this.parts = parts;
        this.source = source;
        this.path = path;
        this.builtIn = builtIn;
        this.text = text;
        this.resolved = resolved;
        if (resolved) {
            var facts = SchemaCompiler.check(parts);
            this.names = facts.names();
            this.gameNames = facts.gameNames();
            this.fieldCount = facts.fields();
        } else {
            this.names = Set.of();
            this.gameNames = false;
            this.fieldCount = countStatements(parts);
        }
        this.filePattern = parts.kind() == Kind.FILE ? SchemaParser.globPattern(parts.target()) : null;
    }

    public enum Kind {
        FILE,
        CLASS
    }

    public static Schema parse(String text, String source) {
        return parse(text, source, null, false);
    }

    public static Schema read(Path file) throws IOException {
        var source = file.toString();
        if (Files.size(file) > SchemaParser.MAX_BYTES) {
            throw new SchemaException(source, 0, "the file is over 1 MB, so it is not a schema");
        }
        return parse(SchemaParser.decode(Files.readAllBytes(file)), source, file, false);
    }

    static Schema parse(String text, String source, @Nullable Path path, boolean builtIn) {
        var parts = SchemaParser.parse(text, source);
        return new Schema(parts, source, path, builtIn, text, parts.like() == null);
    }

    public Kind getKind() {
        return parts.kind();
    }

    public String getTarget() {
        return parts.target();
    }

    public String getTitle() {
        return parts.title();
    }

    public @Nullable String getLike() {
        return parts.like();
    }

    public Map<String, Object> getConstants() {
        return parts.constants();
    }

    public String getSource() {
        return source;
    }

    public @Nullable Path getPath() {
        return path;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public String getText() {
        return text;
    }

    public boolean isResolved() {
        return resolved;
    }

    public Set<String> getNames() {
        return names;
    }

    public boolean needsGameDataNames() {
        return gameNames;
    }

    public boolean matchesFile(String fileName) {
        return filePattern != null && filePattern.matcher(fileName).matches();
    }

    public Schema resolve(List<Schema> others) {
        if (resolved) {
            return this;
        }
        var last = resolution;
        if (last != null && last.isAmong(others)) {
            if (last.failure() != null) {
                throw last.failure();
            }
            return last.schema();
        }
        try {
            return resolve(others, new ArrayList<>());
        } catch (SchemaException failure) {
            resolution = new Resolution(List.copyOf(others), null, failure);
            throw failure;
        }
    }

    @Override
    public String toString() {
        return source + " (" + parts.title() + ")";
    }

    Parts getParts() {
        return parts;
    }

    int getFieldCount() {
        return fieldCount;
    }

    private Schema resolve(List<Schema> others, List<Schema> chain) {
        if (resolved) {
            return this;
        }
        var last = resolution;
        if (last != null && last.schema() != null && last.isAmong(others)) {
            return last.schema();
        }
        var like = parts.like();
        var where = parts.likeWhere();
        if (chain.contains(this)) {
            var titles = new ArrayList<String>();
            for (var schema : chain.subList(chain.indexOf(this), chain.size())) {
                titles.add(schema.getTitle());
            }
            titles.add(getTitle());
            throw where.error(
                    "the likes go in a circle: " + String.join(" > ", titles) + ". A schema cannot be like itself");
        }
        if (chain.size() >= SchemaParser.MAX_DEPTH) {
            throw where.error("the likes go deeper than 32 schemas");
        }
        Schema base = null;
        for (var other : others) {
            if (other != this && other.getTitle().equalsIgnoreCase(like)) {
                base = other;
                break;
            }
        }
        if (base == null) {
            var titles = new ArrayList<String>();
            for (var other : others) {
                if (other != this) {
                    titles.add(other.getTitle());
                }
            }
            var known = titles.isEmpty()
                    ? "There is no other schema of this table"
                    : "The titles are: " + String.join(", ", titles);
            throw where.error("no schema of this table has the title \"" + like + "\". " + known);
        }
        if (base.getKind() != getKind()) {
            throw where.error("\"" + base.getTitle() + "\" is a schema of a " + kindWord(base.getKind())
                    + ", and this one of a " + kindWord(getKind()));
        }
        chain.add(this);
        var resolvedBase = base.resolve(others, chain);
        chain.removeLast();
        var edited = SchemaEdits.apply(resolvedBase.getParts(), parts);
        try {
            var schema = new Schema(edited, source, path, builtIn, text, true);
            resolution = new Resolution(List.copyOf(others), schema, null);
            return schema;
        } catch (SchemaException broken) {
            if (broken.getSource().equals(source)) {
                throw broken;
            }
            throw where.error("the changes break \"" + resolvedBase.getTitle() + "\": " + broken.getMessage());
        }
    }

    private static Map<String, Basic> typeWords() {
        var words = new LinkedHashMap<String, Basic>();
        for (var type : Basic.values()) {
            words.put(type.word, type);
        }
        words.put("UCHAR", Basic.BYTE);
        words.put("UBYTE", Basic.BYTE);
        words.put("SHORT", Basic.SHORT);
        words.put("USHORT", Basic.USHORT);
        words.put("INT", Basic.INT);
        words.put("UINT", Basic.UINT);
        words.put("LONG", Basic.LONG);
        words.put("FLOAT", Basic.FLOAT);
        words.put("DOUBLE", Basic.DOUBLE);
        words.put("CNTR", Basic.COMPACT);
        words.put("UNICODE", Basic.STRING);
        words.put("ASCF", Basic.FSTRING);
        words.put("MAP_INT", Basic.NAME);
        return Collections.unmodifiableMap(words);
    }

    private static int countStatements(Parts parts) {
        var count = countStatements(parts.body());
        for (var struct : parts.structs().values()) {
            count += countStatements(struct.body());
        }
        for (var edit : parts.edits()) {
            count += 1 + countStatements(edit.body());
        }
        return count;
    }

    private static int countStatements(List<Statement> statements) {
        var count = statements.size();
        for (var statement : statements) {
            switch (statement) {
                case ListOf list -> count += countStatements(list.body());
                case If branch -> count += countStatements(branch.then()) + countStatements(branch.otherwise());
                default -> {}
            }
        }
        return count;
    }

    private static String kindWord(Kind kind) {
        return kind == Kind.FILE ? "table file" : "class";
    }

    private record Resolution(
            List<Schema> pool,
            @Nullable Schema schema,
            @Nullable SchemaException failure) {
        boolean isAmong(List<Schema> others) {
            if (others.size() != pool.size()) {
                return false;
            }
            for (var i = 0; i < pool.size(); i++) {
                if (others.get(i) != pool.get(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    enum Basic {
        BYTE("byte", 1, 1, true),
        SHORT("short", 2, 2, true),
        USHORT("ushort", 2, 2, true),
        INT("int", 4, 4, true),
        UINT("uint", 4, 4, true),
        LONG("long", 8, 8, true),
        FLOAT("float", 4, 4, false),
        DOUBLE("double", 8, 8, false),
        COMPACT("compact", -1, 1, true),
        STRING("string", -1, 4, false),
        FSTRING("fstring", -1, 1, false),
        ANSI("ansi", -1, 4, false),
        NAME("name", 4, 4, false);

        final String word;
        final int size;
        final int least;
        final boolean whole;

        Basic(String word, int size, int least, boolean whole) {
            this.word = word;
            this.size = size;
            this.least = least;
            this.whole = whole;
        }

        boolean isText() {
            return this == STRING || this == FSTRING || this == ANSI;
        }
    }

    record Where(String source, int line) {
        SchemaException error(String reason) {
            return new SchemaException(source, line, reason);
        }
    }

    record TypeRef(@Nullable Basic basic, @Nullable String struct, String word) {}

    sealed interface Statement permits Field, ListOf, Skip, If, UseNames {
        Where where();
    }

    record Field(Where where, TypeRef type, @Nullable String name) implements Statement {}

    record ListOf(
            Where where,
            Count count,
            @Nullable TypeRef element,
            List<Statement> body,
            @Nullable String name) implements Statement {}

    record Skip(Where where, int bytes) implements Statement {}

    record If(Where where, Condition condition, List<Statement> then, List<Statement> otherwise) implements Statement {}

    record UseNames(Where where, List<String> path) implements Statement {}

    sealed interface Count permits FixedCount, ReadCount, FieldCount {}

    record FixedCount(int value) implements Count {}

    record ReadCount(Basic type, String word) implements Count {}

    record FieldCount(String name) implements Count {}

    sealed interface Condition permits Compare, Both, Either {}

    record Compare(Where where, String name, Operator operator, long value) implements Condition {}

    record Both(Condition left, Condition right) implements Condition {}

    record Either(Condition left, Condition right) implements Condition {}

    enum Operator {
        EQUAL("=="),
        NOT_EQUAL("!="),
        LESS("<"),
        LESS_OR_EQUAL("<="),
        GREATER(">"),
        GREATER_OR_EQUAL(">=");

        final String word;

        Operator(String word) {
            this.word = word;
        }

        boolean test(long left, long right) {
            return switch (this) {
                case EQUAL -> left == right;
                case NOT_EQUAL -> left != right;
                case LESS -> left < right;
                case LESS_OR_EQUAL -> left <= right;
                case GREATER -> left > right;
                case GREATER_OR_EQUAL -> left >= right;
            };
        }
    }

    record Struct(Where where, String name, List<Statement> body) {}

    record Edit(Where where, EditKind kind, List<String> path, List<Statement> body) {}

    enum EditKind {
        AFTER("after"),
        BEFORE("before"),
        REPLACE("replace"),
        REMOVE("remove");

        final String word;

        EditKind(String word) {
            this.word = word;
        }
    }

    record Parts(
            Kind kind,
            String target,
            String title,
            @Nullable String like,
            Where likeWhere,
            Map<String, Object> constants,
            List<Statement> body,
            Map<String, Struct> structs,
            List<Edit> edits) {}
}
