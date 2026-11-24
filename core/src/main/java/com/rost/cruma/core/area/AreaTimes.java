package com.rost.cruma.core.area;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class AreaTimes {
    private static final Pattern PART = Pattern.compile(
            "(\\d+)\\s*(seconds?|secs?|s|minutes?|mins?|m|hours?|h|days?|d)?", Pattern.CASE_INSENSITIVE);

    private AreaTimes() {}

    public static long parse(String text) {
        var value = text.strip();
        return value.isEmpty() ? -1 : AreaValues.parseSeconds(value);
    }

    public static @Nullable String normalize(String typed) {
        var text = typed.strip();
        if (text.isEmpty()) {
            return null;
        }
        var parts = new ArrayList<String>();
        var matcher = PART.matcher(text);
        var at = 0;
        while (at < text.length()) {
            if (!matcher.find(at) || matcher.start() != at) {
                return null;
            }
            var unit = matcher.group(2) == null ? "" : unitOf(matcher.group(2).toLowerCase(Locale.ROOT));
            parts.add(Long.parseLong(matcher.group(1)) + unit);
            at = matcher.end();
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }
        var written = String.join(" ", parts);
        return parse(written) < 0 ? null : written;
    }

    public static String describe(long seconds) {
        if (seconds <= 0) {
            return "0 sec";
        }
        var parts = new ArrayList<String>();
        long[] sizes = {86400, 3600, 60, 1};
        String[] units = {"day", "h", "min", "sec"};
        var rest = seconds;
        for (var i = 0; i < sizes.length; i++) {
            if (rest >= sizes[i]) {
                var count = rest / sizes[i];
                rest %= sizes[i];
                parts.add(count + " " + (i == 0 && count > 1 ? "days" : units[i]));
            }
        }
        return String.join(" ", parts);
    }

    private static String unitOf(String word) {
        return switch (word.charAt(0)) {
            case 's' -> "sec";
            case 'm' -> "min";
            case 'h' -> "hour";
            default -> "day";
        };
    }
}
