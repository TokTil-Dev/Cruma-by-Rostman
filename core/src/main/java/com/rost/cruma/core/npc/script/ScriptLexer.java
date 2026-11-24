package com.rost.cruma.core.npc.script;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ScriptLexer {
    private static final String[] OPERATORS = {
        ">>>", "<<", ">>", "<=", ">=", "==", "!=", "~=", "&&", "||", "^^", "++", "--", "+=", "-=", "*=", "/=", "$=",
        "@=", "**"
    };

    private final String text;
    private final List<Token> tokens = new ArrayList<>();
    private int at;
    private int line = 1;

    private ScriptLexer(String text) {
        this.text = text;
    }

    enum Kind {
        WORD,
        INT,
        FLOAT,
        STRING,
        NAME,
        SYMBOL,
        END
    }

    record Token(Kind kind, String text, int line) {
        boolean is(String word) {
            return (kind == Kind.WORD || kind == Kind.SYMBOL) && text.equalsIgnoreCase(word);
        }

        String lower() {
            return text.toLowerCase(Locale.ROOT);
        }
    }

    static List<Token> tokens(String text) {
        var lexer = new ScriptLexer(text);
        lexer.run();
        return lexer.tokens;
    }

    private void run() {
        var lineStart = true;
        while (at < text.length()) {
            var c = text.charAt(at);
            if (c == '\n') {
                line++;
                at++;
                lineStart = true;
                continue;
            }
            if (Character.isWhitespace(c) || c < ' ') {
                at++;
                continue;
            }
            if (lineStart && c == '#') {
                skipLine();
                continue;
            }
            lineStart = false;
            if (c == '/' && next(1) == '/') {
                skipLine();
            } else if (c == '/' && next(1) == '*') {
                skipBlockComment();
            } else if (Character.isLetter(c) || c == '_') {
                word();
            } else if (Character.isDigit(c) || c == '.' && Character.isDigit(next(1))) {
                number();
            } else if (c == '"') {
                quoted('"', Kind.STRING);
            } else if (c == '\'') {
                quoted('\'', Kind.NAME);
            } else {
                symbol();
            }
        }
        tokens.add(new Token(Kind.END, "", line));
    }

    private char next(int ahead) {
        var index = at + ahead;
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private void skipLine() {
        while (at < text.length() && text.charAt(at) != '\n') {
            at++;
        }
    }

    private void skipBlockComment() {
        at += 2;
        while (at < text.length() && !(text.charAt(at) == '*' && next(1) == '/')) {
            if (text.charAt(at) == '\n') {
                line++;
            }
            at++;
        }
        at = Math.min(text.length(), at + 2);
    }

    private void word() {
        var start = at;
        while (at < text.length() && (Character.isLetterOrDigit(text.charAt(at)) || text.charAt(at) == '_')) {
            at++;
        }
        tokens.add(new Token(Kind.WORD, text.substring(start, at), line));
    }

    private void number() {
        var start = at;
        if (text.charAt(at) == '0' && (next(1) == 'x' || next(1) == 'X')) {
            at += 2;
            while (at < text.length() && Character.digit(text.charAt(at), 16) >= 0) {
                at++;
            }
            tokens.add(new Token(
                    Kind.INT, Integer.toString((int) Long.parseLong(text.substring(start + 2, at), 16)), line));
            return;
        }
        var decimal = false;
        while (at < text.length()) {
            var c = text.charAt(at);
            if (Character.isDigit(c)) {
                at++;
            } else if (c == '.' && !decimal) {
                decimal = true;
                at++;
            } else if ((c == 'e' || c == 'E')
                    && decimal
                    && (Character.isDigit(next(1)) || next(1) == '-' || next(1) == '+')) {
                at += 2;
            } else {
                break;
            }
        }
        var digits = text.substring(start, at);
        if (at < text.length() && (text.charAt(at) == 'f' || text.charAt(at) == 'F')) {
            at++;
            decimal = true;
        }
        if (digits.endsWith(".")) {
            digits = digits + "0";
        }
        if (digits.startsWith(".")) {
            digits = "0" + digits;
        }
        tokens.add(new Token(decimal ? Kind.FLOAT : Kind.INT, digits, line));
    }

    private void quoted(char quote, Kind kind) {
        at++;
        var value = new StringBuilder();
        while (at < text.length() && text.charAt(at) != quote && text.charAt(at) != '\n') {
            var c = text.charAt(at);
            if (c == '\\' && at + 1 < text.length()) {
                at++;
                c = text.charAt(at);
            }
            value.append(c);
            at++;
        }
        at = Math.min(text.length(), at + 1);
        tokens.add(new Token(kind, value.toString(), line));
    }

    private void symbol() {
        for (var operator : OPERATORS) {
            if (text.startsWith(operator, at)) {
                tokens.add(new Token(Kind.SYMBOL, operator, line));
                at += operator.length();
                return;
            }
        }
        tokens.add(new Token(Kind.SYMBOL, String.valueOf(text.charAt(at)), line));
        at++;
    }
}
