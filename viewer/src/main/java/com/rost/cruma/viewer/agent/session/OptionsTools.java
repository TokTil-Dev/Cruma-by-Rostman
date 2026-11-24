package com.rost.cruma.viewer.agent.session;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.agent.Access;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.OptionsModel;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueFactory;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class OptionsTools implements ToolGroup {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MOST_LIMIT = 200;
    private static final String LOCATIONS = "viewer.camera.locations";
    private static final String AGENT = "viewer.agent";

    @Override
    public void register(Tools tools) {
        var set = new JSONObject();
        set.put("type", "object");
        set.put("description", "Settings to change by path, such as {\"viewer.world.variant\":\"classic\"}.");
        tools.hub(
                ToolSpec.named(
                                "options",
                                "Settings",
                                "Reads and changes Settings, the values of cruma.conf, and the saved places of the"
                                        + " map. A change is checked, and save writes it with Write access.")
                        .text("query", "Words of settings to list, such as fog or viewer.world.variant.")
                        .schema("set", set)
                        .text("place", "A saved place to add, change or remove, by name.")
                        .text("point", "x,y,z of the place.")
                        .text("rename", "A new name for the place.")
                        .flag("remove", "Remove the place.")
                        .flag("save", "Write the changes into cruma.conf. Needs Write.")
                        .text(
                                "client",
                                "The client whose own settings change, such as its world. The client of the settings"
                                        + " when left out.")
                        .integer("limit", "How many settings to list. " + DEFAULT_LIMIT + " when left out.")
                        .writes()
                        .build(),
                OptionsTools::options);
    }

    private static ToolResult options(ToolArgs args, AgentHub hub, Job job) throws ToolProblem {
        var directory = hub.getConfigDirectory();
        var loader = new ConfigLoader(ConfigLoader.parseOptions(new String[] {"-c", directory.toString()}));
        var asked = args.getText("client");
        var client = asked == null ? null : hub.resolveClient(asked);
        OptionsModel model;
        try {
            model = client == null ? new OptionsModel(loader) : new OptionsModel(loader, client);
        } catch (IOException | RuntimeException unreadable) {
            throw new ToolProblem("cruma.conf cannot be read: " + unreadable.getMessage()
                    + ". Ask the person to open Settings, which says what to fix.");
        }
        var answer = new JSONObject();
        if (model.getSelectedClient() != null) {
            answer.put("client", model.getSelectedClient().getFileName().toString());
        }
        var changes = new JSONArray();
        var values = args.getJson().get("set");
        if (values != null) {
            if (!(values instanceof JSONObject settings)) {
                throw new ToolProblem(
                        "set must be an object of paths and values, such as {\"viewer.world.music.volume\":0.3}.");
            }
            var entries = new ArrayList<>(settings.entrySet());
            entries.sort(Comparator.comparing(entry -> !isWorld(entry.getKey())));
            for (var entry : entries) {
                changes.add(set(model, entry.getKey().strip(), entry.getValue()));
            }
        }
        var place = args.getText("place");
        if (place != null) {
            answer.put("place", place(model, args, place));
        } else if (args.has("point") || args.has("rename") || args.has("remove")) {
            throw new ToolProblem("point, rename and remove change a saved place: give its name in place.");
        }
        var problem = model.validate();
        if (!problem.isEmpty()) {
            throw new ToolProblem("Settings refuses this change: " + problem);
        }
        if (!changes.isEmpty()) {
            answer.put("changes", changes);
        }
        if (args.readFlag("save", false)) {
            save(model, hub);
            answer.put("saved", directory.resolve(ConfigLoader.USER_FILE).toString());
            answer.put(
                    "note",
                    "A world opened from now on uses these settings. A window that is open keeps most of them until it"
                        + " opens again, and world_open gives back a viewer of yours that holds the tiles, so close it"
                        + " first with world_close.");
        } else if (model.hasChanges()) {
            answer.put("next", "Checked, not saved. Call again with save: true to write cruma.conf.");
        }
        var query = args.getText("query");
        if (query != null || (values == null && place == null)) {
            list(model, query, Math.clamp(args.getInteger("limit", DEFAULT_LIMIT), 1, MOST_LIMIT), answer);
        }
        var done = ToolResult.data(answer);
        return args.readFlag("save", false)
                ? done.withFolder(directory).saying("Saved Settings in " + ConfigLoader.USER_FILE)
                : done;
    }

    private static boolean isWorld(String asked) {
        var path = asked.strip().replace("\"", "");
        return path.equals(ClientProfiles.VARIANT) || path.equals(ClientProfiles.CAPTURE_VARIANT);
    }

    private static JSONObject set(OptionsModel model, String asked, Object value) throws ToolProblem {
        var elements = elements(asked);
        var path = ConfigUtil.joinPath(elements);
        if (touches(elements, AGENT)) {
            throw new ToolProblem(
                    "The switch and the access of AI agents stay with the person. Ask them to change it in Settings, AI"
                            + " agent.");
        }
        if (touches(elements, ClientProfiles.LIST)) {
            throw new ToolProblem(
                    "Cruma keeps client_profiles itself. Give client, and set the setting by its own path, such as"
                            + " viewer.world.variant.");
        }
        ConfigValue current;
        try {
            current = model.getValue(path);
        } catch (ConfigException missing) {
            throw new ToolProblem("There is no setting " + asked + ". Call options with query to find it.");
        }
        if (current instanceof ConfigObject) {
            throw new ToolProblem(path
                    + " is a group of settings. Set each one by its own path, such as "
                    + path
                    + "."
                    + ((ConfigObject) current)
                            .keySet().stream().sorted().findFirst().orElse("name")
                    + ".");
        }
        Object before = current.unwrapped();
        var variant = path.equals(ClientProfiles.VARIANT) || path.equals(ClientProfiles.CAPTURE_VARIANT);
        if (variant) {
            refuseMissingWorld(model, String.valueOf(value));
        }
        try {
            model.setValue(path, configValue(value));
        } catch (IllegalArgumentException refused) {
            throw new ToolProblem(path + ": " + refused.getMessage());
        }
        var change = new JSONObject();
        change.put("path", path);
        change.put("before", before);
        change.put("after", model.getValue(path).unwrapped());
        return change;
    }

    private static ConfigValue configValue(Object value) {
        return ConfigValueFactory.fromAnyRef(
                value instanceof BigDecimal number ? Double.valueOf(number.doubleValue()) : value);
    }

    private static JSONObject place(OptionsModel model, ToolArgs args, String name) throws ToolProblem {
        var places = (ConfigList) model.getValue(LOCATIONS);
        var index = -1;
        for (var at = 0; at < places.size(); at++) {
            if (((ConfigObject) places.get(at)).toConfig().getString("name").equalsIgnoreCase(name)) {
                index = at;
            }
        }
        var point = point(args.getText("point"));
        var rename = args.getText("rename");
        var answer = new JSONObject();
        try {
            if (args.readFlag("remove", false)) {
                if (index < 0) {
                    throw new ToolProblem("There is no saved place " + name + ". place_find lists them.");
                }
                model.removeLocation(index);
                answer.put("removed", name);
                return answer;
            }
            if (index < 0) {
                if (point == null) {
                    throw new ToolProblem("A new place needs point, as x,y,z.");
                }
                var added = rename == null ? name : rename;
                model.addLocation(added, point);
                answer.put("added", added.strip());
                answer.put("point", text(point));
                return answer;
            }
            var saved = ((ConfigObject) places.get(index)).toConfig();
            var position = point == null ? saved.getDoubleList("position") : point;
            var newName = rename == null ? saved.getString("name") : rename.strip();
            model.updateLocation(index, newName, position);
            answer.put("changed", saved.getString("name"));
            answer.put("name", newName);
            answer.put("point", text(position));
            return answer;
        } catch (IllegalArgumentException refused) {
            throw new ToolProblem(refused.getMessage());
        }
    }

    private static @Nullable List<Double> point(@Nullable String text) throws ToolProblem {
        if (text == null) {
            return null;
        }
        var parts = text.split(",");
        if (parts.length == 3) {
            try {
                var point = new ArrayList<Double>();
                for (var part : parts) {
                    point.add(Double.valueOf(part.strip()));
                }
                return point;
            } catch (NumberFormatException wrong) {
            }
        }
        throw new ToolProblem("point takes x,y,z in world units, such as 83336,147972,-3204.");
    }

    private static String text(List<Double> point) {
        var parts = new ArrayList<String>();
        for (var value : point) {
            parts.add(Long.toString(Math.round(value)));
        }
        return String.join(",", parts);
    }

    private static List<String> elements(String path) throws ToolProblem {
        try {
            return ConfigUtil.splitPath(path.strip());
        } catch (ConfigException bad) {
            throw new ToolProblem("There is no setting " + path + ". Call options with query to find it.");
        }
    }

    private static boolean touches(List<String> path, String guarded) {
        var guard = ConfigUtil.splitPath(guarded);
        var shared = Math.min(path.size(), guard.size());
        return path.subList(0, shared).equals(guard.subList(0, shared));
    }

    private static void save(OptionsModel model, AgentHub hub) throws ToolProblem {
        if (!hub.getAccess().allows(Access.WRITE)) {
            throw new ToolProblem(
                    "save writes cruma.conf, the person's settings, so it needs Write access. The change passed the"
                        + " check. Ask the person to raise the access in Settings, AI agent, or to make the change.");
        }
        if (!model.hasChanges()) {
            throw new ToolProblem("Nothing changes, so there is nothing to save.");
        }
        try {
            model.save();
        } catch (IOException | RuntimeException refused) {
            throw new ToolProblem("cruma.conf was not written: " + refused.getMessage());
        }
    }

    private static void list(OptionsModel model, @Nullable String query, int limit, JSONObject answer) {
        var words =
                query == null ? new String[0] : query.toLowerCase(Locale.ROOT).split("\\s+");
        var rows = new JSONArray();
        var more = 0;
        for (var field : model.getFields()) {
            var path = field.path().toLowerCase(Locale.ROOT);
            var about = field.description().isEmpty()
                    ? ""
                    : field.description().getFirst().strip();
            if (path.startsWith(AGENT)
                    || path.equals(LOCATIONS)
                    || !matches(path + " " + about.toLowerCase(Locale.ROOT), words)) {
                continue;
            }
            if (rows.size() == limit) {
                more++;
                continue;
            }
            var row = new JSONObject();
            row.put("path", field.path());
            row.put("value", model.getValue(field.path()).unwrapped());
            if (!about.isEmpty()) {
                row.put("about", about);
            }
            rows.add(row);
        }
        answer.put("settings", rows);
        if (more > 0) {
            answer.put("more", more);
        }
        answer.put("places", ((ConfigList) model.getValue(LOCATIONS)).size());
    }

    private static void refuseMissingWorld(OptionsModel model, String value) throws ToolProblem {
        WorldVariant world;
        try {
            world = WorldVariant.parse(value);
        } catch (IllegalArgumentException wrong) {
            return;
        }
        if (model.getSelectedClientWorlds().contains(world)) {
            return;
        }
        throw new ToolProblem(
                switch (world) {
                    case MAIN -> "Every client has the main world.";
                    case CLASSIC ->
                        "This client has no Classic maps, so it has no Classic world. Clients with _Classic maps, such"
                                + " as 152 and 162, have it.";
                    case CLASSIC_ADEN ->
                        "This client has no _ClassicAden tables, so it has no Essence world. Clients such as Essence"
                                + " 542 and 196 have them.";
                });
    }

    private static boolean matches(String text, String[] words) {
        for (var word : words) {
            if (!text.contains(word)) {
                return false;
            }
        }
        return true;
    }
}
