package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SpawnNames {
    static final double NEAR = 20_000;

    private static final Pattern NUMBERED = Pattern.compile("(.*?)(\\d+)");

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_]+");

    private SpawnNames() {}

    public static boolean isValid(String name) {
        return VALID.matcher(name).matches();
    }

    public static String newZoneName(State state, double x, double y) {
        var used = new HashSet<String>();
        SpawnTerritory nearest = null;
        var nearestDistance = NEAR;
        for (var zone : state.territories()) {
            if (zone == null) {
                continue;
            }
            used.add(zone.name());
            if (zone.domain() || !NUMBERED.matcher(zone.name()).matches()) {
                continue;
            }
            var distance = Math.hypot(zone.centerX() - x, zone.centerY() - y);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = zone;
            }
        }
        String stem;
        int digits;
        if (nearest != null) {
            var match = NUMBERED.matcher(nearest.name());
            match.matches();
            stem = match.group(1);
            digits = match.group(2).length();
        } else {
            var tileX = (int) Math.floor(x / 32768) + 20;
            var tileY = (int) Math.floor(y / 32768) + 18;
            stem = String.format(Locale.ROOT, "zone_%02d%02d_", tileX, tileY);
            digits = 2;
        }
        var highest = 0;
        for (var name : used) {
            if (name.startsWith(stem)) {
                var rest = name.substring(stem.length());
                if (!rest.isEmpty() && rest.chars().allMatch(Character::isDigit) && rest.length() <= 9) {
                    highest = Math.max(highest, Integer.parseInt(rest));
                }
            }
        }
        for (var number = highest + 1; ; number++) {
            var name = stem + String.format(Locale.ROOT, "%0" + digits + "d", number);
            if (!used.contains(name)) {
                return name;
            }
        }
    }

    public static String newMakerName(State state, String zone) {
        var used = new HashSet<String>();
        for (var maker : state.makers()) {
            if (maker != null) {
                used.add(maker.name());
            }
        }
        for (var number = 1; ; number++) {
            var name = zone + "m" + number;
            if (!used.contains(name)) {
                return name;
            }
        }
    }
}
