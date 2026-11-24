package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class ToolArgs {
    private final JSONObject values;

    public ToolArgs(@Nullable JSONObject values) {
        this.values = values == null ? new JSONObject() : values;
    }

    public JSONObject getJson() {
        return values;
    }

    public boolean has(String name) {
        return values.get(name) != null;
    }

    public @Nullable String getText(String name) throws ToolProblem {
        var value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new ToolProblem(name + " must be text.");
        }
        return text.isBlank() ? null : text.strip();
    }

    public String getText(String name, String fallback) throws ToolProblem {
        var text = getText(name);
        return text == null ? fallback : text;
    }

    public String getRequired(String name) throws ToolProblem {
        var text = getText(name);
        if (text == null) {
            throw new ToolProblem(name + " is missing.");
        }
        return text;
    }

    public @Nullable Double getNumber(String name) throws ToolProblem {
        var value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text.strip());
            } catch (NumberFormatException wrong) {
                throw new ToolProblem(name + " must be a number, not \"" + text + "\".");
            }
        }
        throw new ToolProblem(name + " must be a number.");
    }

    public double getNumber(String name, double fallback) throws ToolProblem {
        var number = getNumber(name);
        return number == null ? fallback : number;
    }

    public int getInteger(String name, int fallback) throws ToolProblem {
        var number = getNumber(name);
        return number == null ? fallback : (int) Math.round(number);
    }

    public boolean readFlag(String name, boolean fallback) throws ToolProblem {
        var value = values.get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text && (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(text);
        }
        throw new ToolProblem(name + " must be true or false.");
    }

    public List<String> list(String name) throws ToolProblem {
        var value = values.get(name);
        var list = new ArrayList<String>();
        if (value == null) {
            return list;
        }
        if (value instanceof JSONArray array) {
            for (var item : array) {
                list.add(String.valueOf(item).strip());
            }
        } else if (value instanceof String text) {
            for (var item : text.split(",")) {
                if (!item.isBlank()) {
                    list.add(item.strip());
                }
            }
        } else {
            throw new ToolProblem(name + " must be a list of texts.");
        }
        return list;
    }

    public ToolArgs without(String name) {
        var copy = new JSONObject(values);
        copy.remove(name);
        return new ToolArgs(copy);
    }
}
