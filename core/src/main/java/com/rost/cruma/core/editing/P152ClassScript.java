package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import java.io.IOException;

final class P152ClassScript {
    private final ReferenceCheck references;
    private final Cursor cursor;
    private final int limit;
    private int memoryBytes;

    private P152ClassScript(ReferenceCheck references, Cursor cursor, int limit) {
        this.references = references;
        this.cursor = cursor;
        this.limit = limit;
    }

    @FunctionalInterface
    interface ReferenceCheck {
        void check(int reference) throws IOException;
    }

    static void skip(Cursor cursor, int scriptMemoryBytes, ReferenceCheck references) throws IOException {
        if (scriptMemoryBytes < 0 || scriptMemoryBytes > 1024 * 1024) {
            throw new IOException("Invalid compiled class script memory size");
        }
        var reader = new P152ClassScript(references, cursor, scriptMemoryBytes);
        while (reader.memoryBytes < scriptMemoryBytes) {
            reader.skipExpression(0, false);
        }
    }

    private int skipExpression(int depth, boolean parameter) throws IOException {
        if (depth >= 64) {
            throw new IOException("Excessive compiled class expression nesting");
        }
        consume(1);
        var token = cursor.readU8();
        if (token >= 0x70) {
            while (skipExpression(depth + 1, true) != 0x16) {
            }
            return token;
        }
        switch (token) {
            case 0x01 -> {
                consume(4);
                references.check(cursor.readIndex());
            }
            case 0x16 -> {
                if (!parameter) {
                    throw new IOException("Unexpected compiled class parameter terminator");
                }
            }
            case 0x18 -> {
                consume(2);
                cursor.readU16();
                skipExpression(depth + 1, false);
            }
            case 0x24 -> {
                consume(1);
                cursor.readU8();
            }
            case 0x2a, 0x2d -> {
            }
            case 0x2e -> {
                consume(4);
                references.check(cursor.readIndex());
                skipExpression(depth + 1, false);
            }
            case 0x39 -> {
                consume(1);
                cursor.readU8();
                skipExpression(depth + 1, false);
            }
            default ->
                throw new IOException("Unaudited compiled class expression token 0x" + Integer.toHexString(token));
        }
        return token;
    }

    private void consume(int count) throws IOException {
        if (count > limit - memoryBytes) {
            throw new IOException("Compiled class expression exceeds script memory size");
        }
        memoryBytes += count;
    }
}
