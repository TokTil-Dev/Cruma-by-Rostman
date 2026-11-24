package com.rost.cruma.viewer.agent.session;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentFindings.Findings;
import com.rost.cruma.viewer.agent.AgentFindings.Item;
import com.rost.cruma.viewer.agent.AgentFindings.Severity;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.Target;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolGroup;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.ToolSpec;
import com.rost.cruma.viewer.agent.Tools;
import com.rost.cruma.viewer.agent.ViewerContext;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class FindingsTools implements ToolGroup {
    private static final int MOST_ITEMS = 1000;

    @FunctionalInterface
    interface Locator {
        Target locate(String target) throws ToolProblem;
    }

    static Findings read(ToolArgs args, Locator locator) throws ToolProblem {
        var title = args.getRequired("title");
        if (!(args.getJson().get("items") instanceof JSONArray array)) {
            throw new ToolProblem("items must be a list of objects with text and target.");
        }
        if (array.size() > MOST_ITEMS) {
            throw new ToolProblem("items has " + array.size() + " lines and a list holds " + MOST_ITEMS
                    + ". Send the most important ones.");
        }
        var items = new ArrayList<Item>(array.size());
        for (var index = 0; index < array.size(); index++) {
            items.add(item(array.get(index), index + 1, locator));
        }
        return new Findings(title, List.copyOf(items));
    }

    @Override
    public void register(Tools tools) {
        tools.viewer(
                ToolSpec.named(
                                "findings_show",
                                "Show findings",
                                "Puts a list of places into the AI agent panel, and a click flies the person there. A"
                                        + " list replaces one of the same title.")
                        .text("title", "The name of the list, such as Floating objects near Dion.")
                        .schema("items", items())
                        .required("title", "items")
                        .forPerson(),
                FindingsTools::show);
    }

    private static JSONObject items() {
        var text = new JSONObject();
        text.put("type", "string");
        text.put("description", "What is there, in a few words.");
        var target = new JSONObject();
        target.put("type", "string");
        target.put("description", "Where it is: a place name, tile (22_22), point (x,y,z) or camera.");
        var severity = new JSONObject();
        severity.put("type", "string");
        severity.put("description", "How serious it is. Leave it out when that does not apply.");
        severity.put(
                "enum", JSONArray.of(Severity.ERROR.getWord(), Severity.WARNING.getWord(), Severity.INFO.getWord()));
        var properties = new JSONObject();
        properties.put("text", text);
        properties.put("target", target);
        properties.put("severity", severity);
        var item = new JSONObject();
        item.put("type", "object");
        item.put("properties", properties);
        item.put("required", JSONArray.of("text", "target"));
        item.put("additionalProperties", false);
        var list = new JSONObject();
        list.put("type", "array");
        list.put("description", "The lines of the list, the most important first. At most " + MOST_ITEMS + ".");
        list.put("items", item);
        return list;
    }

    private static Step show(ToolArgs args, ViewerContext viewer) throws ToolProblem {
        if (viewer.agent().isHidden()) {
            throw new ToolProblem(
                    "This viewer has no window, so nobody would see the list. Pass viewer with one that has a window,"
                            + " see cruma_status.");
        }
        var findings = read(args, viewer::locate);
        viewer.agent().show(findings);
        var count = findings.items().size();
        var said = "Listed " + count + (count == 1 ? " place in " : " places in ") + findings.title();
        return Step.done(ToolResult.text(said + ". The AI agent panel shows it, and a click flies there."));
    }

    private static Item item(Object value, int number, Locator locator) throws ToolProblem {
        if (!(value instanceof JSONObject json)) {
            throw new ToolProblem("Line " + number + " of items must be an object with text and target.");
        }
        var line = new ToolArgs(json);
        try {
            var place = line.getRequired("target");
            var target = locator.locate(place);
            return new Item(
                    oneLine(line.getRequired("text")),
                    place,
                    target.x(),
                    target.y(),
                    target.z(),
                    severity(line.getText("severity")));
        } catch (ToolProblem problem) {
            throw new ToolProblem("Line " + number + " of items: " + problem.getMessage());
        }
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static @Nullable Severity severity(@Nullable String word) throws ToolProblem {
        if (word == null) {
            return null;
        }
        for (var severity : Severity.values()) {
            if (severity.getWord().equalsIgnoreCase(word)) {
                return severity;
            }
        }
        throw new ToolProblem("severity is error, warning or info, not \"" + word + "\".");
    }
}
