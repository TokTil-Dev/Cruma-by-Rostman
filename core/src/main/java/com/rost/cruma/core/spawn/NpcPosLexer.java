package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosFile.Problem;
import java.util.List;

final class NpcPosLexer {
    private final String text;
    private final List<Problem> problems;
    private int position;
    private int line = 1;
    private Token peeked;

    NpcPosLexer(String text, List<Problem> problems) {
        this.text = text;
        this.problems = problems;
    }

    enum Kind {
        WORD,
        INTEGER,
        FLOAT,
        NAME,
        PRECOMPILED,
        PUNCT,
        END
    }

    record Token(Kind kind, int start, int end, int line, String text) {
        boolean is(char punct) {
            return kind == Kind.PUNCT && text.charAt(0) == punct;
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }

        int intValue() {
            try {
                return (int) Long.parseLong(text);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    String getText() {
        return text;
    }

    Token peek() {
        if (peeked == null) {
            peeked = scan();
        }
        return peeked;
    }

    Token next() {
        var token = peek();
        peeked = null;
        return token;
    }

    private Token scan() {
        var length = text.length();
        while (position < length) {
            var c = text.charAt(position);
            if (c == '\n') {
                line++;
                position++;
            } else if (c == ' ' || c == '\t' || c == '\r' || c == '﻿') {
                position++;
            } else if (c == '/' && position + 1 < length && text.charAt(position + 1) == '/') {
                while (position < length && text.charAt(position) != '\n') {
                    position++;
                }
            } else {
                return token(c);
            }
        }
        return new Token(Kind.END, length, length, line, "");
    }

    private Token token(char c) {
        var start = position;
        var length = text.length();
        if (c == '[') {
            var close = start + 1;
            while (close < length && text.charAt(close) != ']' && text.charAt(close) != '[') {
                close++;
            }
            if (close < length && text.charAt(close) == ']') {
                position = close + 1;
                return new Token(Kind.NAME, start, position, line, text.substring(start + 1, close));
            }
            position++;
            return new Token(Kind.PUNCT, start, position, line, "[");
        }
        if (isDigit(c) || c == '-' && position + 1 < length && isDigit(text.charAt(position + 1))) {
            position++;
            while (position < length && isDigit(text.charAt(position))) {
                position++;
            }
            var kind = Kind.INTEGER;
            if (position < length && text.charAt(position) == '.') {
                kind = Kind.FLOAT;
                position = endOfDigits(position + 1);
                if (position < length && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                    var exponent = position + 1;
                    if (exponent < length && (text.charAt(exponent) == '+' || text.charAt(exponent) == '-')) {
                        exponent++;
                    }
                    if (exponent < length && isDigit(text.charAt(exponent))) {
                        position = endOfDigits(exponent);
                    }
                }
            }
            return new Token(kind, start, position, line, text.substring(start, position));
        }
        if (isWordStart(c)) {
            position++;
            while (position < length && isWordPart(text.charAt(position))) {
                position++;
            }
            return new Token(Kind.WORD, start, position, line, text.substring(start, position));
        }
        if (c == '@') {
            position++;
            if (position < length && text.charAt(position) == '"') {
                var close = text.indexOf('"', position + 1);
                position = close < 0 ? length : close + 1;
            } else {
                while (position < length && isWordPart(text.charAt(position))) {
                    position++;
                }
            }
            return new Token(Kind.PRECOMPILED, start, position, line, text.substring(start, position));
        }
        position++;
        if ("%(),;={}]".indexOf(c) >= 0) {
            return new Token(Kind.PUNCT, start, position, line, String.valueOf(c));
        }
        problems.add(new Problem(line, "Invalid character U+%04X skipped".formatted((int) c)));
        return scan();
    }

    private int endOfDigits(int from) {
        var end = from;
        while (end < text.length() && isDigit(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWordStart(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
    }

    private static boolean isWordPart(char c) {
        return isWordStart(c) || isDigit(c);
    }
}
