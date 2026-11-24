package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.List;

public record ToolSpec(
        String name,
        String title,
        String description,
        JSONObject input,
        Access access,
        boolean writes,
        boolean destructive,
        boolean window,
        boolean graphics) {
    public static Builder named(String name, String title, String description) {
        return new Builder(name, title, description);
    }

    public JSONObject write() {
        var hints = new JSONObject();
        hints.put("title", title);
        hints.put("readOnlyHint", !writes);
        hints.put("destructiveHint", destructive);
        hints.put("openWorldHint", false);
        var tool = new JSONObject();
        tool.put("name", name);
        tool.put("title", title);
        tool.put("description", description);
        tool.put("inputSchema", input);
        tool.put("annotations", hints);
        return tool;
    }

    public void requireKnown(ToolArgs args) throws ToolProblem {
        var known = input.getJSONObject("properties").keySet();
        for (var key : args.getJson().keySet()) {
            if (known.contains(key)) {
                continue;
            }
            throw new ToolProblem(
                    known.isEmpty()
                            ? name + " takes no arguments, and the call has " + key + "."
                            : name
                                    + " has no argument "
                                    + key
                                    + ". Its arguments are "
                                    + String.join(", ", known)
                                    + ".");
        }
    }

    public static final class Builder {
        private final String name;
        private final String title;
        private final String description;
        private final JSONObject properties = new JSONObject();
        private final List<String> required = new ArrayList<>();
        private Access access = Access.SEE;
        private boolean writes;
        private boolean destructive;
        private boolean window;
        private boolean graphics;

        private Builder(String name, String title, String description) {
            this.name = name;
            this.title = title;
            this.description = description;
        }

        public Builder text(String key, String text) {
            return property(key, "string", text);
        }

        public Builder target(String text) {
            return text(
                    "target",
                    text + " A place name, tile (22_22), point (x,y,z), actor path, camera, selection or a mark (#7).");
        }

        public Builder number(String key, String text) {
            return property(key, "number", text);
        }

        public Builder integer(String key, String text) {
            return property(key, "integer", text);
        }

        public Builder flag(String key, String text) {
            return property(key, "boolean", text);
        }

        public Builder choice(String key, String text, String... choices) {
            property(key, "string", text);
            properties.getJSONObject(key).put("enum", JSONArray.of((Object[]) choices));
            return this;
        }

        public Builder list(String key, String text) {
            property(key, "array", text);
            var items = new JSONObject();
            items.put("type", "string");
            properties.getJSONObject(key).put("items", items);
            return this;
        }

        public Builder schema(String key, JSONObject schema) {
            properties.put(key, schema);
            return this;
        }

        public Builder required(String... keys) {
            required.addAll(List.of(keys));
            return this;
        }

        public Builder access(Access level) {
            access = level;
            writes |= level != Access.SEE;
            return this;
        }

        public Builder writes() {
            writes = true;
            return this;
        }

        public Builder destructive() {
            writes = true;
            destructive = true;
            return this;
        }

        public Builder forPerson() {
            window = true;
            return this;
        }

        public Builder needsGraphics() {
            graphics = true;
            return this;
        }

        public ToolSpec build() {
            var schema = new JSONObject();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
            return new ToolSpec(name, title, description, schema, access, writes, destructive, window, graphics);
        }

        private Builder property(String key, String type, String text) {
            var property = new JSONObject();
            property.put("type", type);
            property.put("description", text);
            properties.put(key, property);
            return this;
        }
    }
}
