package com.rost.cruma.core.npc;

import java.util.regex.Pattern;

public final class NpcSpawnRate {
    private static final Pattern TIME = Pattern.compile("(\\d+)(sec|min|hour|day)");

    private NpcSpawnRate() {}

    public static long parseSeconds(String respawn) {
        var matcher = TIME.matcher(respawn.strip());
        if (!matcher.matches()) {
            return 0;
        }
        var value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "sec" -> value;
            case "min" -> value * 60;
            case "hour" -> value * 3600;
            default -> value * 86_400;
        };
    }

    public static double killsPerHour(int total, String respawn) {
        var seconds = parseSeconds(respawn);
        return seconds <= 0 ? 0 : total * 3600.0 / seconds;
    }
}
