package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.nio.file.Path;
import java.util.Base64;
import org.jetbrains.annotations.Nullable;

public final class ToolResult {
    private static final int SUMMARY_LENGTH = 140;

    private final JSONArray content;
    private final boolean failed;
    private @Nullable Path folder;
    private @Nullable String said;

    private ToolResult(JSONArray content, boolean failed) {
        this.content = content;
        this.failed = failed;
    }

    public static ToolResult text(String text) {
        return new ToolResult(new JSONArray(), false).withText(text);
    }

    public static ToolResult data(Object value) {
        return text(JSON.toJSONString(value));
    }

    public static ToolResult failure(String reason) {
        return new ToolResult(new JSONArray(), true).withText(reason);
    }

    public static ToolResult read(JSONObject result) {
        return new ToolResult(result.getJSONArray("content"), result.getBooleanValue("isError"));
    }

    public ToolResult withText(String text) {
        var block = new JSONObject();
        block.put("type", "text");
        block.put("text", text);
        content.add(block);
        return this;
    }

    public ToolResult withImage(byte[] bytes, String mimeType) {
        var block = new JSONObject();
        block.put("type", "image");
        block.put("data", Base64.getEncoder().encodeToString(bytes));
        block.put("mimeType", mimeType);
        content.add(block);
        return this;
    }

    public ToolResult withFolder(Path written) {
        folder = written;
        return this;
    }

    public ToolResult saying(String line) {
        said = line;
        return this;
    }

    public @Nullable Path getFolder() {
        return folder;
    }

    public boolean hasFailed() {
        return failed;
    }

    public String getSummary() {
        if (said != null) {
            return said;
        }
        for (var block : content) {
            if (block instanceof JSONObject object && "text".equals(object.getString("type"))) {
                var text = object.getString("text").strip();
                if (text.startsWith("{") || text.startsWith("[")) {
                    return "";
                }
                var end = text.indexOf('\n');
                var line = end < 0 ? text : text.substring(0, end);
                return line.length() <= SUMMARY_LENGTH ? line : line.substring(0, SUMMARY_LENGTH) + "...";
            }
        }
        return "";
    }

    public JSONObject write() {
        var result = new JSONObject();
        result.put("content", content);
        result.put("isError", failed);
        return result;
    }
}
