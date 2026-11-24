package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter.Feature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class McpServer {
    public interface Calls {
        Job start(String tool, @Nullable JSONObject arguments);

        ToolResult answer(Job job) throws InterruptedException;
    }

    static final String MODERN = "2026-07-28";
    static final List<String> VERSIONS = List.of(MODERN, "2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    private static final String SERVER_INFO_KEY = "io.modelcontextprotocol/serverInfo";
    private static final long LIST_LIFE_MILLIS = 3_600_000L;
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int NO_METHOD = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int UNSUPPORTED_VERSION = -32022;
    private static final Logger log = LogManager.getLogger(McpServer.class);

    private final Tools tools;
    private final Calls calls;
    private final Consumer<String> out;
    private final String version;
    private final String instructions;
    private final Map<String, Job> active = new ConcurrentHashMap<>();
    private final Object order = new Object();

    public McpServer(Tools tools, Calls calls, Consumer<String> out, String version, String instructions) {
        this.tools = tools;
        this.calls = calls;
        this.out = out;
        this.version = version;
        this.instructions = instructions;
    }

    static List<String> versions() {
        return new ArrayList<>(VERSIONS);
    }

    public void handle(String line) {
        if (line.isBlank()) {
            return;
        }
        JSONObject message;
        try {
            message = JSON.parseObject(line);
        } catch (JSONException wrong) {
            sendError(null, PARSE_ERROR, "The line is not JSON.", null);
            return;
        }
        if (message == null) {
            sendError(null, INVALID_REQUEST, "The message is empty.", null);
            return;
        }
        var method = message.getString("method");
        var id = message.get("id");
        if (method == null) {
            return;
        }
        try {
            var params = message.getJSONObject("params");
            if (id == null) {
                notification(method, params);
                return;
            }
            request(id, method, params == null ? new JSONObject() : params);
        } catch (RuntimeException failure) {
            log.error("Could not answer {}", method, failure);
            if (id != null) {
                sendError(id, -32603, "Cruma could not answer " + method + ": " + failure, null);
            }
        }
    }

    boolean isIdle() {
        return active.isEmpty();
    }

    private void notification(String method, @Nullable JSONObject params) {
        if (method.equals("notifications/cancelled") && params != null) {
            var job = active.remove(String.valueOf(params.get("requestId")));
            if (job != null) {
                job.cancel();
            }
        }
    }

    private void request(Object id, String method, JSONObject params) {
        var meta = params.getJSONObject("_meta");
        var asked = meta == null ? null : meta.getString(VERSION_KEY);
        if (asked != null && !VERSIONS.contains(asked)) {
            var data = new JSONObject();
            data.put("supported", VERSIONS);
            data.put("requested", asked);
            sendError(id, UNSUPPORTED_VERSION, "Unsupported protocol version", data);
            return;
        }
        var modern = asked != null;
        switch (method) {
            case "initialize" -> sendResult(id, initialize(params), false);
            case "server/discover" -> sendResult(id, discover(), true);
            case "ping" -> sendResult(id, new JSONObject(), modern);
            case "tools/list" -> sendResult(id, list("tools", toolObjects(), modern), modern);
            case "tools/call" -> call(id, params, modern);
            case "prompts/list" -> sendResult(id, list("prompts", Prompts.write(), modern), modern);
            case "prompts/get" -> prompt(id, params, modern);
            default -> sendError(id, NO_METHOD, "Cruma has no method " + method, null);
        }
    }

    private JSONObject initialize(JSONObject params) {
        var asked = params.getString("protocolVersion");
        var legacy = VERSIONS.subList(1, VERSIONS.size());
        var result = new JSONObject();
        result.put("protocolVersion", legacy.contains(asked) ? asked : legacy.getFirst());
        result.put("capabilities", capabilities());
        result.put("serverInfo", serverInfo());
        result.put("instructions", instructions);
        return result;
    }

    private JSONObject discover() {
        var result = new JSONObject();
        result.put("supportedVersions", VERSIONS);
        result.put("capabilities", capabilities());
        result.put("instructions", instructions);
        result.put("ttlMs", LIST_LIFE_MILLIS);
        result.put("cacheScope", "public");
        return result;
    }

    private JSONObject serverInfo() {
        var info = new JSONObject();
        info.put("name", "cruma");
        info.put("title", "Cruma");
        info.put("version", version);
        return info;
    }

    private JSONArray toolObjects() {
        var list = new JSONArray();
        for (var entry : tools.list()) {
            list.add(entry.spec().write());
        }
        return list;
    }

    private void call(Object id, JSONObject params, boolean modern) {
        var name = params.getString("name");
        if (name == null || tools.find(name) == null) {
            sendError(id, INVALID_PARAMS, "Unknown tool: " + name, null);
            return;
        }
        var arguments = params.get("arguments");
        if (arguments != null && !(arguments instanceof JSONObject)) {
            sendError(id, INVALID_PARAMS, "The arguments of " + name + " must be an object.", null);
            return;
        }
        var job = calls.start(name, (JSONObject) arguments);
        var key = String.valueOf(id);
        active.put(key, job);
        var meta = params.getJSONObject("_meta");
        var token = meta == null ? null : meta.get("progressToken");
        if (token != null) {
            var steps = new AtomicInteger();
            job.onProgress(text -> progress(key, token, steps.incrementAndGet(), text));
        }
        Thread.ofVirtual().name("cruma-agent-answer").start(() -> answer(id, key, job, modern));
    }

    private void answer(Object id, String key, Job job, boolean modern) {
        try {
            var result = calls.answer(job);
            synchronized (order) {
                if (active.remove(key) != null) {
                    sendResult(id, result.write(), modern);
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private void progress(String key, Object token, int step, String text) {
        var params = new JSONObject();
        params.put("progressToken", token);
        params.put("progress", step);
        params.put("message", text);
        var message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("method", "notifications/progress");
        message.put("params", params);
        synchronized (order) {
            if (active.containsKey(key)) {
                send(message);
            }
        }
    }

    private void prompt(Object id, JSONObject params, boolean modern) {
        var name = params.getString("name");
        var result = Prompts.get(name, params.getJSONObject("arguments"));
        if (result == null) {
            sendError(id, INVALID_PARAMS, "Unknown prompt: " + name, null);
        } else {
            sendResult(id, result, modern);
        }
    }

    private void sendResult(Object id, JSONObject result, boolean modern) {
        if (modern) {
            result.put("resultType", "complete");
            var meta = new JSONObject();
            meta.put(SERVER_INFO_KEY, serverInfo());
            result.put("_meta", meta);
        }
        var message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("result", result);
        send(message);
    }

    private void sendError(@Nullable Object id, int code, String text, @Nullable JSONObject data) {
        var error = new JSONObject();
        error.put("code", code);
        error.put("message", text);
        if (data != null) {
            error.put("data", data);
        }
        var message = new JSONObject();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("error", error);
        send(message);
    }

    private void send(JSONObject message) {
        out.accept(message.toJSONString(Feature.WriteNulls));
    }

    private static JSONObject capabilities() {
        var capabilities = new JSONObject();
        capabilities.put("tools", new JSONObject());
        capabilities.put("prompts", new JSONObject());
        return capabilities;
    }

    private static JSONObject list(String key, JSONArray items, boolean modern) {
        var result = new JSONObject();
        result.put(key, items);
        if (modern) {
            result.put("ttlMs", LIST_LIFE_MILLIS);
            result.put("cacheScope", "public");
        }
        return result;
    }
}
