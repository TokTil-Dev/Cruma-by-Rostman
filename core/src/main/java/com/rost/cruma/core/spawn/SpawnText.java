package com.rost.cruma.core.spawn;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public record SpawnText(String text, Charset charset, boolean byteOrderMark, String lineEnding) {
    public SpawnText {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(lineEnding, "lineEnding");
    }

    public static SpawnText of(String text) {
        return new SpawnText(text, StandardCharsets.UTF_16LE, true, lineEnding(text));
    }

    public static SpawnText read(Path path) throws IOException {
        return decode(Files.readAllBytes(path));
    }

    public static SpawnText decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return decode(bytes, 2, StandardCharsets.UTF_16LE, true);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return decode(bytes, 2, StandardCharsets.UTF_16BE, true);
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            return decode(bytes, 3, StandardCharsets.UTF_8, true);
        }
        var oddZeros = 0;
        var sampled = Math.min(bytes.length, 4096) & ~1;
        for (var i = 1; i < sampled; i += 2) {
            if (bytes[i] == 0) {
                oddZeros++;
            }
        }
        var utf16 = sampled > 0 && oddZeros * 4 >= sampled;
        if (utf16) {
            return decode(bytes, 0, StandardCharsets.UTF_16LE, false);
        }
        return decode(bytes, 0, isUtf8(bytes) ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1, false);
    }

    public SpawnText withText(String replacement) {
        return new SpawnText(replacement, charset, byteOrderMark, lineEnding);
    }

    public byte[] encode() {
        var body = text.getBytes(charset);
        if (!byteOrderMark) {
            return body;
        }
        byte[] mark;
        if (charset.equals(StandardCharsets.UTF_16LE)) {
            mark = new byte[] {(byte) 0xff, (byte) 0xfe};
        } else if (charset.equals(StandardCharsets.UTF_16BE)) {
            mark = new byte[] {(byte) 0xfe, (byte) 0xff};
        } else {
            mark = new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
        }
        var bytes = new byte[mark.length + body.length];
        System.arraycopy(mark, 0, bytes, 0, mark.length);
        System.arraycopy(body, 0, bytes, mark.length, body.length);
        return bytes;
    }

    private static SpawnText decode(byte[] bytes, int offset, Charset charset, boolean mark) {
        var text = new String(bytes, offset, bytes.length - offset, charset);
        return new SpawnText(text, charset, mark, lineEnding(text));
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String lineEnding(String text) {
        var newline = text.indexOf('\n');
        return newline > 0 && text.charAt(newline - 1) == '\r' ? "\r\n" : newline >= 0 ? "\n" : "\r\n";
    }
}
