package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDataFile.Problem;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class AreaDataLexer {
    private static final String SIGNS = "{}();=:,%";

    private final String text;
    private final List<Problem> problems;
    private int position;
    private int line = 1;

    AreaDataLexer(String text, List<Problem> problems) {
        this.text = text;
        this.problems = problems;
    }

    enum Kind {
        WORD,
        NUMBER,
        NAME,
        SIGN,
        END
    }

    record Token(Kind kind, int start, int end, int line, String text) {
        boolean is(char sign) {
            return kind == Kind.SIGN && text.charAt(0) == sign;
        }

        boolean isWord(String word) {
            return kind == Kind.WORD && text.equals(word);
        }
    }

    List<Token> scanAll() {
        var tokens = new ArrayList<Token>();
        while (true) {
            var token = scan();
            tokens.add(token);
            if (token.kind() == Kind.END) {
                return tokens;
            }
        }
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
            } else if (c == '/' && position + 1 < length && text.charAt(position + 1) == '*') {
                skipBlockComment();
            } else {
                var token = token(c);
                if (token != null) {
                    return token;
                }
            }
        }
        return new Token(Kind.END, length, length, line, "");
    }

    private void skipBlockComment() {
        var startLine = line;
        var depth = 0;
        var length = text.length();
        while (position < length) {
            var c = text.charAt(position);
            if (c == '/' && position + 1 < length && text.charAt(position + 1) == '*') {
                depth++;
                position += 2;
            } else if (c == '*' && position + 1 < length && text.charAt(position + 1) == '/') {
                depth--;
                position += 2;
                if (depth == 0) {
                    return;
                }
            } else {
                if (c == '\n') {
                    line++;
                }
                position++;
            }
        }
        problems.add(new Problem(startLine, "A /* comment has no end"));
    }

    private @Nullable Token token(char c) {
        var start = position;
        var length = text.length();
        if (c == '[') {
            var close = start + 1;
            while (close < length && text.charAt(close) != ']' && text.charAt(close) != '\n') {
                close++;
            }
            if (close < length && text.charAt(close) == ']') {
                position = close + 1;
                return new Token(Kind.NAME, start, position, line, text.substring(start, position));
            }
            problems.add(new Problem(line, "A [name] has no ]"));
            position++;
            return null;
        }
        if (isDigit(c) || c == '-' && position + 1 < length && isDigit(text.charAt(position + 1))) {
            position++;
            while (position < length && isDigit(text.charAt(position))) {
                position++;
            }
            if (position < length && text.charAt(position) == '.') {
                position++;
                while (position < length && isDigit(text.charAt(position))) {
                    position++;
                }
            }
            return new Token(Kind.NUMBER, start, position, line, text.substring(start, position));
        }
        if (isWordStart(c)) {
            position++;
            while (position < length && isWordPart(text.charAt(position))) {
                position++;
            }
            return new Token(Kind.WORD, start, position, line, text.substring(start, position));
        }
        if ((c == '@' || c == '$') && position + 1 < length && isWordPart(text.charAt(position + 1))) {
            position++;
            while (position < length && isWordPart(text.charAt(position))) {
                position++;
            }
            return new Token(Kind.NUMBER, start, position, line, text.substring(start, position));
        }
        position++;
        if (SIGNS.indexOf(c) >= 0) {
            return new Token(Kind.SIGN, start, position, line, String.valueOf(c));
        }
        problems.add(new Problem(line, "Invalid character U+%04X skipped".formatted((int) c)));
        return null;
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
