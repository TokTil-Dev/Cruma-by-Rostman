package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.ClientCatalog.Combo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SkillCombos {
    private static final String[] OPENINGS = {"CastShort", "CastMid", "CastLong"};
    private static final String[] RELEASES = {"MagicNoTarget", "MagicShot", "MagicThrow"};

    static final List<Combo> BUILT_IN = builtIn();

    private SkillCombos() {}

    static String part(String written) {
        if (written == null || written.isBlank() || written.equalsIgnoreCase("none")) {
            return "";
        }
        var suffix = "animname";
        return written.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? written.substring(0, written.length() - suffix.length())
                : written;
    }

    private static List<Combo> builtIn() {
        var combos = new ArrayList<Combo>();
        var letters = "ABCDEFGHI";
        for (var opening = 0; opening < OPENINGS.length; opening++) {
            for (var release = 0; release < RELEASES.length; release++) {
                var letter = letters.charAt(opening * 3 + release);
                combos.add(
                        new Combo(String.valueOf(letter), List.of(OPENINGS[opening], "CastEnd", RELEASES[release]), 1));
                combos.add(new Combo(letter + "2", List.of(OPENINGS[opening] + "2", "CastEnd2", RELEASES[release]), 1));
            }
        }
        for (var release = 0; release < RELEASES.length; release++) {
            var letter = "JKL".charAt(release);
            combos.add(new Combo(String.valueOf(letter), List.of("CastEnd", RELEASES[release]), 0));
            combos.add(new Combo(letter + "2", List.of("CastEnd2", RELEASES[release]), 0));
        }
        combos.add(single("M", "PicItem"));
        combos.add(single("N", "SpAtk27"));
        combos.add(single("O", "AtkWait"));
        combos.add(single("P", "SpecialSocial1"));
        combos.add(single("Q", "SpecialSocial2"));
        combos.add(single("R", "SpecialSocial3"));
        var attacks = "STUVWX";
        for (var i = 0; i < attacks.length(); i++) {
            combos.add(single(String.valueOf(attacks.charAt(i)), String.format(Locale.ROOT, "SpAtk%02d", i + 1)));
        }
        combos.add(single("Y", "ShieldAtk"));
        combos.add(single("Z", "SpAtk28"));
        combos.add(attacks("Mix01", 9, 17, 24));
        combos.add(attacks("Mix02", 7, 16, 25));
        combos.add(attacks("Mix03", 7, 13, 20));
        combos.add(attacks("Mix04", 7, 12, 21));
        combos.add(attacks("Mix05", 8, 13, 22));
        combos.add(attacks("Mix06", 18));
        combos.add(attacks("Mix07", 19));
        combos.add(attacks("Mix08", 7, 14, 23));
        combos.add(attacks("Mix09", 9, 15, 26));
        combos.add(new Combo("MS01", List.of("Atk01", "Atk02", "Atk03"), 1));
        combos.add(attacks("Mix51", 1, 2));
        combos.add(attacks("Mix52", 1, 3));
        combos.add(attacks("Mix53", 5, 6));
        combos.add(attacks("Mix54", 8, 7));
        combos.add(attacks("Mix55", 8, 10));
        combos.add(attacks("Mix56", 8, 12));
        combos.add(attacks("Mix57", 13, 14, 15));
        combos.add(attacks("Mix58", 8, 9));
        combos.add(attacks("Mix59", 8, 11));
        combos.add(attacks("Mix60", 5, 19));
        combos.add(attacks("Mix61", 5, 7));
        combos.add(new Combo("DD", List.of("DashWait", "Dash"), -1));
        combos.add(new Combo("DI", List.of("DashWait", "DashImpact"), -1));
        return List.copyOf(combos);
    }

    private static Combo single(String name, String part) {
        return new Combo(name, List.of(part), -1);
    }

    private static Combo attacks(String name, int... numbers) {
        var parts = new ArrayList<String>();
        for (var number : numbers) {
            parts.add(String.format(Locale.ROOT, "SpAtk%02d", number));
        }
        return new Combo(name, List.copyOf(parts), -1);
    }
}
