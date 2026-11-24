package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class Prompts {
    private record Prompt(
            String name, String title, String description, String argument, String argumentText, String words) {}

    private static final List<Prompt> ALL = List.of(
            new Prompt(
                    "look_around",
                    "Look around",
                    "Look at a place from all sides and describe it.",
                    "place",
                    "A place, such as Giran Castle Town",
                    """
                    Open {place} in a hidden viewer and look at it from all sides, by day and at night. \
                    Tell me what stands there, and what looks wrong: holes, black or pink textures, objects in the air.\
                    """),
            new Prompt(
                    "survey_area",
                    "Survey an area",
                    "Look for problems in an area and list them in Cruma.",
                    "place",
                    "A place, such as Dion Castle Town",
                    """
                    Survey {place} in a hidden viewer for problems: objects in the air or deep in the ground, holes in \
                    the map, missing textures, and geodata that does not match the ground. Look at each suspect before \
                    you name it. Put what you find into Cruma with findings_show, so I can click through it.\
                    """),
            new Prompt(
                    "fly_over",
                    "Fly over",
                    "Make a short film of a flight over a place.",
                    "place",
                    "A place, such as Oren Castle Town",
                    """
                    Make a 30 second film of a flight over {place} at sunset with the video director. Show me a sheet \
                    of frames before you render it.\
                    """),
            new Prompt(
                    "check_spawns",
                    "Check spawns",
                    "Check the NPC spawns of an area against geodata.",
                    "place",
                    "A place, such as Giran Castle Town",
                    """
                    Check the NPC spawns around {place} against geodata and the map. Put what is wrong into Cruma with \
                    findings_show, and tell me what you would change. Change nothing until I say so.\
                    """));

    private Prompts() {}

    static JSONArray write() {
        var list = new JSONArray();
        for (var prompt : ALL) {
            var argument = new JSONObject();
            argument.put("name", prompt.argument());
            argument.put("description", prompt.argumentText());
            argument.put("required", true);
            var item = new JSONObject();
            item.put("name", prompt.name());
            item.put("title", prompt.title());
            item.put("description", prompt.description());
            item.put("arguments", JSONArray.of(argument));
            list.add(item);
        }
        return list;
    }

    static @Nullable JSONObject get(@Nullable String name, @Nullable JSONObject arguments) {
        for (var prompt : ALL) {
            if (prompt.name().equals(name)) {
                var value = arguments == null ? null : arguments.getString(prompt.argument());
                var content = new JSONObject();
                content.put("type", "text");
                content.put(
                        "text",
                        prompt.words()
                                .replace(
                                        "{" + prompt.argument() + "}",
                                        value == null || value.isBlank() ? "the place I am looking at" : value));
                var message = new JSONObject();
                message.put("role", "user");
                message.put("content", content);
                var result = new JSONObject();
                result.put("description", prompt.description());
                result.put("messages", JSONArray.of(message));
                return result;
            }
        }
        return null;
    }
}
