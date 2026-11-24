package com.rost.cruma.core.area;

import com.rost.cruma.core.area.AreaDocument.State;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AreaSkills {
    private AreaSkills() {}

    public record Skill(String name, int uses) {}

    public static List<Skill> byUse(State state) {
        var counts = new HashMap<String, Integer>();
        for (var area : state.areas()) {
            if (area == null) {
                continue;
            }
            for (var value : area.values("skill_name")) {
                count(counts, value);
            }
            for (var list : area.values("skill_list")) {
                for (var item : list.replace("{", " ").replace("}", " ").split("[;\\s]+")) {
                    count(counts, item);
                }
            }
        }
        var skills = new ArrayList<>(counts.entrySet());
        skills.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()));
        return skills.stream()
                .map(entry -> new Skill(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static String strip(String value) {
        var name = value.strip();
        if (name.length() >= 2 && name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        return name.startsWith("@") ? name.substring(1) : name;
    }

    public static String toSetting(String name) {
        return "[" + strip(name) + "]";
    }

    private static void count(Map<String, Integer> counts, String value) {
        var name = strip(value);
        if (!name.isEmpty()) {
            counts.merge(name, 1, Integer::sum);
        }
    }
}
