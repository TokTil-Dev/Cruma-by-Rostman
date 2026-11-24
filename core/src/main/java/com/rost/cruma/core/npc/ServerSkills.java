package com.rost.cruma.core.npc;

import com.rost.cruma.core.spawn.SpawnText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class ServerSkills {
    private final Map<String, Skill> byName;
    private final List<Skill> sorted;

    private ServerSkills(Collection<Skill> skills) {
        var names = new HashMap<String, Skill>(skills.size() * 2);
        for (var skill : skills) {
            names.putIfAbsent(skill.name(), skill);
        }
        this.byName = names;
        var list = new ArrayList<>(names.values());
        list.sort(Comparator.comparingInt(Skill::id).thenComparingInt(Skill::level));
        this.sorted = List.copyOf(list);
    }

    public record Skill(String name, int id, int level, String operateType) {
        public boolean isPassive() {
            return operateType.equals("P");
        }
    }

    public static ServerSkills empty() {
        return new ServerSkills(List.of());
    }

    public static ServerSkills of(Collection<Skill> skills) {
        return new ServerSkills(skills);
    }

    public static ServerSkills read(@Nullable Path skillData, @Nullable Path skillPch) throws IOException {
        if (skillData != null) {
            return parse(SpawnText.read(skillData).text());
        }
        if (skillPch != null) {
            return fromPch(SpawnText.read(skillPch).text());
        }
        return empty();
    }

    public static ServerSkills parse(String text) {
        var skills = new ArrayList<Skill>();
        var at = 0;
        while (at < text.length()) {
            var lineEnd = text.indexOf('\n', at);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            var begin = text.indexOf("skill_begin", at);
            if (begin >= 0 && begin < lineEnd) {
                var line = text.substring(begin, lineEnd);
                var name = ServerItems.setting(line, "skill_name");
                try {
                    if (name.startsWith("[") && name.endsWith("]")) {
                        skills.add(new Skill(
                                name.substring(1, name.length() - 1),
                                Integer.parseInt(ServerItems.setting(line, "skill_id")),
                                Integer.parseInt(ServerItems.setting(line, "level")),
                                ServerItems.setting(line, "operate_type")));
                    }
                } catch (NumberFormatException notNumber) {
                }
            }
            at = lineEnd + 1;
        }
        return new ServerSkills(skills);
    }

    static ServerSkills fromPch(String text) {
        var packed = new HashMap<String, Long>();
        var wide = false;
        for (var line : text.split("\n")) {
            var open = line.indexOf('[');
            var close = line.indexOf(']', open + 1);
            var equals = line.indexOf('=', close + 1);
            if (open < 0 || close < 0 || equals < 0) {
                continue;
            }
            try {
                var value = Long.parseLong(line.substring(equals + 1).strip());
                packed.putIfAbsent(line.substring(open + 1, close), value);
                wide |= value >= 1 << 24;
            } catch (NumberFormatException ignored) {
            }
        }
        var shift = wide ? 16 : 8;
        var skills = new ArrayList<Skill>();
        for (var entry : packed.entrySet()) {
            var value = entry.getValue();
            skills.add(new Skill(entry.getKey(), (int) (value >>> shift), (int) (value & ((1L << shift) - 1)), ""));
        }
        return new ServerSkills(skills);
    }

    public @Nullable Skill byName(String name) {
        return byName.get(name);
    }

    public List<Skill> getSkills() {
        return sorted;
    }

    public int size() {
        return sorted.size();
    }
}
