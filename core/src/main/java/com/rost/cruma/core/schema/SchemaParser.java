package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.Schema.Both;
import com.rost.cruma.core.schema.Schema.Condition;
import com.rost.cruma.core.schema.Schema.Count;
import com.rost.cruma.core.schema.Schema.Edit;
import com.rost.cruma.core.schema.Schema.EditKind;
import com.rost.cruma.core.schema.Schema.Either;
import com.rost.cruma.core.schema.Schema.Field;
import com.rost.cruma.core.schema.Schema.FieldCount;
import com.rost.cruma.core.schema.Schema.FixedCount;
import com.rost.cruma.core.schema.Schema.If;
import com.rost.cruma.core.schema.Schema.Kind;
import com.rost.cruma.core.schema.Schema.ListOf;
import com.rost.cruma.core.schema.Schema.Operator;
import com.rost.cruma.core.schema.Schema.Parts;
import com.rost.cruma.core.schema.Schema.ReadCount;
import com.rost.cruma.core.schema.Schema.Skip;
import com.rost.cruma.core.schema.Schema.Statement;
import com.rost.cruma.core.schema.Schema.Struct;
import com.rost.cruma.core.schema.Schema.TypeRef;
import com.rost.cruma.core.schema.Schema.UseNames;
import com.rost.cruma.core.schema.Schema.Where;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

final class SchemaParser {
    static final int MAX_BYTES = 1 << 20;
    static final int MAX_DEPTH = 32;
    static final int MAX_COUNT = 2_000_000;
    static final int MAX_NAME = 64;

    private static final List<String> KEYWORDS = List.of(
            "format", "file", "class", "title", "like", "set", "struct", "list", "skip", "if", "else", "use", "after",
            "before", "replace", "remove");
    private static final List<String> HEAD_WORDS = List.of("format", "file", "class", "title", "like", "set");

    private final String source;
    private final List<Raw> raws;
    private final Map<String, Integer> headLines = new HashMap<>();
    private final Map<String, Object> constants = new LinkedHashMap<>();
    private final Map<String, Struct> structs = new LinkedHashMap<>();
    private final List<Edit> edits = new ArrayList<>();
    private int next;
    private boolean started;
    private @Nullable Kind kind;
    private @Nullable String target;
    private @Nullable String title;
    private @Nullable String like;
    private Where likeWhere;

    private SchemaParser(String source, List<Raw> raws) {
        this.source = source;
        this.raws = raws;
        this.likeWhere = new Where(source, 0);
    }

    private record Raw(int line, String text, char end) {}

    private enum TokenKind {
        WORD,
        NUMBER,
        SYMBOL
    }

    private record Token(String text, TokenKind kind) {
        boolean isWord() {
            return kind == TokenKind.WORD;
        }

        boolean isSymbol(String symbol) {
            return kind == TokenKind.SYMBOL && text.equals(symbol);
        }
    }

    static Parts parse(String text, String source) {
        if (text.length() > MAX_BYTES) {
            throw new SchemaException(source, 0, "the text is over 1 MB, so it is not a schema");
        }
        var clean = text.startsWith("﻿") ? text.substring(1) : text;
        return new SchemaParser(source, split(clean)).parseAll();
    }

    static String decode(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }

    static Pattern globPattern(String glob) {
        var regex = new StringBuilder();
        var start = 0;
        for (var star = glob.indexOf('*'); star >= 0; star = glob.indexOf('*', start)) {
            regex.append(Pattern.quote(glob.substring(start, star))).append(".*");
            start = star + 1;
        }
        regex.append(Pattern.quote(glob.substring(start)));
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private Parts parseAll() {
        var body = new ArrayList<Statement>();
        while (next < raws.size()) {
            var raw = raws.get(next++);
            if (!raw.text().isEmpty()) {
                statement(raw, 0, body);
            } else if (raw.end() == '{') {
                throw at(raw).error("a { stands at the end of the line that opens the block");
            }
            if (raw.end() == '}') {
                throw at(raw).error("this } closes no block");
            }
        }
        if (!started) {
            throw new SchemaException(source, 0, "the schema is empty. It starts with the line format 1");
        }
        if (kind == null || target == null) {
            throw new SchemaException(
                    source, 0, "the schema has no file line, so it names no table. Add one, as in file npcgrp.dat");
        }
        if (title == null) {
            throw new SchemaException(source, 0, "the schema has no title line. Add one, as in title My Server");
        }
        if (like != null && !body.isEmpty()) {
            throw body.getFirst()
                    .where()
                    .error("a schema with like changes \"" + like
                            + "\" only with after, before, replace and remove. Put this line into one of them");
        }
        if (like == null && !edits.isEmpty()) {
            var edit = edits.getFirst();
            throw edit.where()
                    .error(edit.kind().word + " changes the schema that the like line names, and this schema has no"
                            + " like line");
        }
        return new Parts(
                kind,
                target,
                title,
                like,
                likeWhere,
                Collections.unmodifiableMap(constants),
                List.copyOf(body),
                Collections.unmodifiableMap(structs),
                List.copyOf(edits));
    }

    private void statement(Raw raw, int depth, List<Statement> into) {
        var where = at(raw);
        if (raw.end() == '\n' && next < raws.size()) {
            var following = raws.get(next);
            if (following.text().isEmpty() && following.end() == '{') {
                throw at(following).error("a { stands at the end of the line that opens the block");
            }
        }
        var text = raw.text();
        var head = firstWord(text);
        if (head.isEmpty()) {
            throw where.error("a statement starts with a word, and \"" + text + "\" does not");
        }
        if (!started && !head.equals("format")) {
            throw where.error("a schema starts with the line format 1");
        }
        var top = depth == 0;
        if (HEAD_WORDS.contains(head)) {
            if (!top) {
                throw where.error(head + " belongs at the top of the schema, outside blocks");
            }
            noBlock(raw, where, head + " line");
            headLine(head, text.substring(head.length()), where);
            started = true;
            return;
        }
        started = true;
        switch (head) {
            case "struct" -> {
                if (!top) {
                    throw where.error("a struct is written at the top of the schema, outside blocks");
                }
                struct(raw, where, depth);
            }
            case "list" -> into.add(list(raw, where, depth));
            case "skip" -> into.add(skip(raw, where));
            case "if" -> into.add(ifStatement(raw, where, depth));
            case "else" -> throw where.error("else must follow the } of an if");
            case "use" -> {
                if (!top) {
                    throw where.error("use names belongs at the top of the schema, outside blocks");
                }
                into.add(useNames(raw, where));
            }
            case "after", "before", "replace", "remove" -> {
                if (!top) {
                    throw where.error(head + " belongs at the top of the schema, outside blocks");
                }
                edits.add(edit(raw, where, head, depth));
            }
            default -> into.add(field(raw, where));
        }
    }

    private void headLine(String head, String afterHead, Where where) {
        if (!afterHead.isEmpty() && !Character.isWhitespace(afterHead.charAt(0))) {
            throw where.error("write a space after " + head);
        }
        var rest = afterHead.strip();
        switch (head) {
            case "format" -> {
                if (started) {
                    throw where.error("format 1 is the first line of the schema, and it comes only once");
                }
                if (!rest.matches("-?\\d{1,9}")) {
                    throw where.error("format takes a number: format 1");
                }
                if (Integer.parseInt(rest) != 1) {
                    throw where.error("this Cruma reads schemas of format 1, and this one is format " + rest
                            + ". A newer Cruma may read it");
                }
            }
            case "file", "class" -> {
                once("file", where);
                if (rest.isEmpty() || rest.chars().anyMatch(Character::isWhitespace)) {
                    throw where.error(head + " takes one name, as in "
                            + (head.equals("file") ? "file npcgrp.dat" : "class Engine.Mover"));
                }
                kind = head.equals("file") ? Kind.FILE : Kind.CLASS;
                target = rest;
            }
            case "title" -> {
                once("title", where);
                if (rest.isEmpty()) {
                    throw where.error("title takes the name people read, as in title My Server");
                }
                title = rest;
            }
            case "like" -> {
                once("like", where);
                if (rest.isEmpty()) {
                    throw where.error("like takes the title of another schema, as in like Interlude and Gracia");
                }
                like = rest;
                likeWhere = where;
            }
            default -> set(rest, where);
        }
    }

    private void once(String head, Where where) {
        var earlier = headLines.get(head.equals("class") ? "file" : head);
        if (earlier != null) {
            var what = head.equals("file") || head.equals("class") ? "a file or class line" : "a " + head + " line";
            throw where.error("the schema has " + what + " at line " + earlier + " already. Keep one");
        }
        headLines.put(head, where.line());
    }

    private void set(String rest, Where where) {
        var name = firstWord(rest);
        var value = rest.substring(name.length()).strip();
        if (name.isEmpty() || value.isEmpty() || !Character.isWhitespace(rest.charAt(name.length()))) {
            throw where.error("set takes a name and a value, as in set bodyParts 1");
        }
        checkName(name, where);
        if (constants.containsKey(name)) {
            throw where.error("the value " + name + " is set twice. Keep one");
        }
        constants.put(name, constant(value));
    }

    private void struct(Raw raw, Where where, int depth) {
        var tokens = tokens(raw.text(), where);
        if (tokens.size() != 2 || !tokens.get(1).isWord()) {
            throw where.error("struct takes one name and a block: struct Look {");
        }
        var name = tokens.get(1).text();
        if (raw.end() != '{') {
            throw where.error("struct " + name + " needs a block: struct " + name + " {");
        }
        if (!Character.isUpperCase(name.charAt(0))) {
            throw where.error("a struct name starts with a capital letter, as in "
                    + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
        for (var word : Schema.TYPE_WORDS.keySet()) {
            if (word.equalsIgnoreCase(name)) {
                throw where.error(name + " is a type word. Give the struct another name");
            }
        }
        var earlier = structs.get(name);
        if (earlier != null) {
            throw where.error("struct " + name + " is written at line "
                    + earlier.where().line() + " already. Give one of them another name");
        }
        var body = block(depth + 1, where);
        structs.put(name, new Struct(where, name, body));
    }

    private ListOf list(Raw raw, Where where, int depth) {
        var tokens = tokens(raw.text(), where);
        if (tokens.size() < 2) {
            throw where.error("list takes a count and what repeats, as in list uint string textures");
        }
        var count = count(tokens.get(1), where);
        var rest = tokens.subList(2, tokens.size());
        for (var token : rest) {
            if (!token.isWord()) {
                throw where.error("unexpected \"" + token.text() + "\" in the list line");
            }
        }
        if (raw.end() == '{') {
            if (rest.size() > 1) {
                throw where.error("a list of groups takes a count and a name: list uint decorations {. \""
                        + rest.get(1).text() + "\" is one word too many");
            }
            var name = rest.isEmpty() ? null : rest.getFirst().text();
            var body = block(depth + 1, where);
            return new ListOf(where, count, null, body, name);
        }
        if (rest.isEmpty()) {
            throw where.error("list takes what repeats after the count, as in list uint string textures, or a"
                    + " group of fields in { }");
        }
        if (rest.size() > 2) {
            throw where.error("unexpected \"" + rest.get(2).text()
                    + "\": a list of single values is the count, the type and the name");
        }
        var element = type(rest.getFirst().text(), where);
        var name = rest.size() == 2 ? rest.get(1).text() : null;
        return new ListOf(where, count, element, List.of(), name);
    }

    private Count count(Token token, Where where) {
        if (token.kind() == TokenKind.NUMBER) {
            var value = wholeNumber(token.text(), where);
            if (value < 0) {
                throw where.error("a list count is 0 or more, and " + token.text() + " is below 0");
            }
            if (value > MAX_COUNT) {
                throw where.error("a list count is at most 2 000 000, and " + token.text() + " is more");
            }
            return new FixedCount((int) value);
        }
        if (!token.isWord()) {
            throw where.error("a list count is a number, a type or the name of a field read before, and \""
                    + token.text() + "\" is none of these");
        }
        var type = Schema.TYPE_WORDS.get(token.text());
        if (type == null) {
            return new FieldCount(token.text());
        }
        if (!type.whole) {
            throw where.error("a count is read as byte, short, ushort, int, uint, long or compact, and " + token.text()
                    + " is none of these");
        }
        return new ReadCount(type, token.text());
    }

    private Skip skip(Raw raw, Where where) {
        noBlock(raw, where, "skip");
        var tokens = tokens(raw.text(), where);
        if (tokens.size() != 2 || tokens.get(1).kind() != TokenKind.NUMBER) {
            throw where.error("skip takes a number of bytes, as in skip 24");
        }
        var bytes = wholeNumber(tokens.get(1).text(), where);
        if (bytes < 0 || bytes > Integer.MAX_VALUE) {
            throw where.error("skip takes 0 or more bytes, up to 2 147 483 647");
        }
        return new Skip(where, (int) bytes);
    }

    private If ifStatement(Raw raw, Where where, int depth) {
        var tokens = tokens(raw.text(), where);
        if (raw.end() != '{') {
            throw where.error("if needs a block: if handness == 5 {");
        }
        var conditions = new ConditionParser(tokens.subList(1, tokens.size()), where);
        var condition = conditions.parseAll();
        var then = block(depth + 1, where);
        List<Statement> otherwise = List.of();
        if (next < raws.size() && firstWord(raws.get(next).text()).equals("else")) {
            var elseRaw = raws.get(next++);
            var elseWhere = at(elseRaw);
            if (!elseRaw.text().equals("else")) {
                throw elseWhere.error("else stands alone. For another condition, write else { if ... }");
            }
            if (elseRaw.end() != '{') {
                throw elseWhere.error("else needs a block: } else {");
            }
            otherwise = block(depth + 1, elseWhere);
        }
        return new If(where, condition, then, otherwise);
    }

    private UseNames useNames(Raw raw, Where where) {
        noBlock(raw, where, "use names");
        var tokens = tokens(raw.text(), where);
        if (tokens.size() < 3 || !tokens.get(1).text().equals("names")) {
            throw where.error("use takes names and a list, as in use names strings.text");
        }
        var path = path(tokens.subList(2, tokens.size()), where, "use names");
        if (path.size() > 2) {
            throw where.error(
                    "use names takes a list, or a list and a field of its groups, as in use names" + " strings.text");
        }
        return new UseNames(where, path);
    }

    private Edit edit(Raw raw, Where where, String head, int depth) {
        var kindOfEdit = EditKind.valueOf(head.toUpperCase(Locale.ROOT));
        var tokens = tokens(raw.text(), where);
        var path = path(tokens.subList(1, tokens.size()), where, head);
        if (kindOfEdit == EditKind.REMOVE) {
            noBlock(raw, where, "remove");
            return new Edit(where, kindOfEdit, path, List.of());
        }
        if (raw.end() != '{') {
            throw where.error(head + " needs a block of fields: " + head + " " + String.join(".", path) + " {");
        }
        return new Edit(where, kindOfEdit, path, block(depth + 1, where));
    }

    private Field field(Raw raw, Where where) {
        var tokens = tokens(raw.text(), where);
        var typeToken = tokens.getFirst();
        var type = type(typeToken.text(), where);
        if (raw.end() == '{') {
            throw where.error("\"" + raw.text()
                    + "\" opens a block, but only struct, list, if, else, after, before and replace do");
        }
        if (tokens.size() > 2) {
            throw where.error(
                    "unexpected \"" + tokens.get(2).text() + "\" after the field name. A field is a type, then a name");
        }
        String name = null;
        if (tokens.size() == 2) {
            if (!tokens.get(1).isWord()) {
                throw where.error("\"" + tokens.get(1).text()
                        + "\" is not a field name: a name is made of letters, digits and _, and starts with a"
                        + " letter");
            }
            name = tokens.get(1).text();
        }
        return new Field(where, type, name);
    }

    private TypeRef type(String word, Where where) {
        var basic = Schema.TYPE_WORDS.get(word);
        if (basic != null) {
            return new TypeRef(basic, null, word);
        }
        if (Character.isUpperCase(word.charAt(0))) {
            return new TypeRef(null, word, word);
        }
        var candidates = new ArrayList<String>(Schema.TYPE_WORDS.keySet());
        candidates.addAll(KEYWORDS);
        candidates.addAll(structs.keySet());
        throw where.error("unknown type \"" + word + "\"." + Suggestions.suggest(word, candidates));
    }

    private List<Statement> block(int depth, Where opener) {
        if (depth > MAX_DEPTH) {
            throw opener.error("blocks nest deeper than 32 levels");
        }
        var statements = new ArrayList<Statement>();
        while (next < raws.size()) {
            var raw = raws.get(next++);
            if (!raw.text().isEmpty()) {
                statement(raw, depth, statements);
            } else if (raw.end() == '{') {
                throw at(raw).error("a { stands at the end of the line that opens the block");
            }
            if (raw.end() == '}') {
                return List.copyOf(statements);
            }
        }
        throw opener.error("the block opened here has no }");
    }

    private Where at(Raw raw) {
        return new Where(source, raw.line());
    }

    private static void noBlock(Raw raw, Where where, String what) {
        if (raw.end() == '{') {
            throw where.error(what + " opens no block");
        }
    }

    private static List<String> path(List<Token> tokens, Where where, String head) {
        var path = new ArrayList<String>();
        for (var i = 0; i < tokens.size(); i++) {
            var token = tokens.get(i);
            if (i % 2 == 0 ? !token.isWord() : !token.isSymbol(".")) {
                throw where.error(
                        head + " takes the name of a field, or the way to it with dots, as in " + "records.speed");
            }
            if (i % 2 == 0) {
                path.add(token.text());
            }
        }
        if (path.isEmpty() || tokens.size() % 2 == 0) {
            throw where.error(head + " takes the name of a field, or the way to it with dots, as in records.speed");
        }
        return List.copyOf(path);
    }

    private static Object constant(String value) {
        if (value.matches("-?\\d{1,18}")) {
            var number = Long.parseLong(value);
            return number == (int) number ? (Object) (int) number : (Object) number;
        }
        if (value.matches("-?\\d{1,18}\\.\\d{1,18}")) {
            return Double.parseDouble(value);
        }
        return value;
    }

    private static long wholeNumber(String text, Where where) {
        if (!text.matches("-?\\d{1,18}")) {
            throw where.error("\"" + text + "\" is not a whole number");
        }
        return Long.parseLong(text);
    }

    private static String firstWord(String text) {
        var end = 0;
        while (end < text.length() && isNamePart(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end);
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static void checkName(String name, Where where) {
        if (name.length() > MAX_NAME) {
            throw where.error("the name \"" + name.substring(0, 16) + "...\" has " + name.length()
                    + " letters, and a name has at most 64");
        }
    }

    private static List<Token> tokens(String text, Where where) {
        var tokens = new ArrayList<Token>();
        var i = 0;
        while (i < text.length()) {
            var c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            var start = i;
            var number =
                    Character.isDigit(c) || c == '-' && i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
            if (number) {
                i++;
                while (i < text.length()
                        && (Character.isDigit(text.charAt(i))
                                || text.charAt(i) == '.'
                                        && i + 1 < text.length()
                                        && Character.isDigit(text.charAt(i + 1)))) {
                    i++;
                }
                if (i < text.length() && isNamePart(text.charAt(i))) {
                    while (i < text.length() && isNamePart(text.charAt(i))) {
                        i++;
                    }
                    throw where.error("\"" + text.substring(start, i)
                            + "\" is not a name: a name is made of letters, digits and _, and starts with a letter");
                }
                tokens.add(new Token(text.substring(start, i), TokenKind.NUMBER));
            } else if (Character.isLetter(c) || c == '_') {
                while (i < text.length() && isNamePart(text.charAt(i))) {
                    i++;
                }
                checkName(text.substring(start, i), where);
                tokens.add(new Token(text.substring(start, i), TokenKind.WORD));
            } else if (i + 1 < text.length() && List.of("==", "!=", "<=", ">=").contains(text.substring(i, i + 2))) {
                tokens.add(new Token(text.substring(i, i + 2), TokenKind.SYMBOL));
                i += 2;
            } else if ("()<>.".indexOf(c) >= 0) {
                tokens.add(new Token(String.valueOf(c), TokenKind.SYMBOL));
                i++;
            } else {
                throw where.error("unexpected character \"" + c + "\"" + (c == '=' ? ". Write == to compare" : ""));
            }
        }
        return tokens;
    }

    private static List<Raw> split(String text) {
        var raws = new ArrayList<Raw>();
        var buffer = new StringBuilder();
        var line = 1;
        var startLine = 0;
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '#') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\n' || c == '{' || c == '}') {
                var statement = buffer.toString().strip();
                if (!statement.isEmpty() || c != '\n') {
                    raws.add(new Raw(statement.isEmpty() ? line : startLine, statement, c));
                }
                buffer.setLength(0);
                startLine = 0;
                if (c == '\n') {
                    line++;
                }
                continue;
            }
            if (startLine == 0 && !Character.isWhitespace(c)) {
                startLine = line;
            }
            buffer.append(c);
        }
        var statement = buffer.toString().strip();
        if (!statement.isEmpty()) {
            raws.add(new Raw(startLine, statement, '\n'));
        }
        return raws;
    }

    private static final class ConditionParser {
        private static final int MAX_COMPARISONS = 64;

        private final List<Token> tokens;
        private final Where where;
        private int at;
        private int brackets;
        private int comparisons;

        ConditionParser(List<Token> tokens, Where where) {
            this.tokens = tokens;
            this.where = where;
        }

        Condition parseAll() {
            if (tokens.isEmpty()) {
                throw where.error("if takes a condition, as in if handness == 5 {");
            }
            var condition = parseEither();
            if (at < tokens.size()) {
                throw where.error("unexpected \"" + tokens.get(at).text()
                        + "\" in the condition. Join comparisons with and or or");
            }
            return condition;
        }

        private Condition parseEither() {
            var left = parseBoth();
            while (at < tokens.size() && isJoin("or")) {
                at++;
                left = new Either(left, parseBoth());
            }
            return left;
        }

        private Condition parseBoth() {
            var left = parseAtom();
            while (at < tokens.size() && isJoin("and")) {
                at++;
                left = new Both(left, parseAtom());
            }
            return left;
        }

        private Condition parseAtom() {
            if (at >= tokens.size()) {
                throw where.error("the condition ends too early. Compare a field with a number, as in handness == 5");
            }
            var token = tokens.get(at);
            if (token.isSymbol("(")) {
                if (++brackets > MAX_DEPTH) {
                    throw where.error("brackets of a condition nest deeper than 32 levels");
                }
                at++;
                var inner = parseEither();
                brackets--;
                if (at >= tokens.size() || !tokens.get(at).isSymbol(")")) {
                    throw where.error("a ( in the condition has no )");
                }
                at++;
                return inner;
            }
            if (!token.isWord()) {
                throw where.error("a condition compares a field with a number, as in handness == 5, and \""
                        + token.text() + "\" is no field name");
            }
            if (++comparisons > MAX_COMPARISONS) {
                throw where.error("a condition has at most 64 comparisons");
            }
            if (at + 2 >= tokens.size()) {
                throw where.error("the comparison of " + token.text()
                        + " is not finished. Compare it with a number, as in " + token.text() + " == 5");
            }
            var operatorToken = tokens.get(at + 1);
            Operator operator = null;
            for (var candidate : Operator.values()) {
                if (operatorToken.isSymbol(candidate.word)) {
                    operator = candidate;
                }
            }
            if (operator == null) {
                throw where.error("\"" + operatorToken.text() + "\" is no comparison. Use ==, !=, <, <=, > or >=");
            }
            var valueToken = tokens.get(at + 2);
            if (valueToken.kind() != TokenKind.NUMBER || valueToken.text().contains(".")) {
                throw where.error("a condition compares " + token.text() + " with a whole number, and \""
                        + valueToken.text() + "\" is none");
            }
            at += 3;
            return new Schema.Compare(where, token.text(), operator, wholeNumber(valueToken.text(), where));
        }

        private boolean isJoin(String word) {
            var token = tokens.get(at);
            var compared = at + 1 < tokens.size()
                    && tokens.get(at + 1).kind() == TokenKind.SYMBOL
                    && !tokens.get(at + 1).isSymbol("(")
                    && !tokens.get(at + 1).isSymbol(")");
            return token.isWord() && token.text().equals(word) && !compared;
        }
    }
}
