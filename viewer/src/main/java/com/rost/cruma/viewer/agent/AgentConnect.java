package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.launch.OwnProgram;
import java.util.List;
import java.util.regex.Pattern;
import org.lwjgl.system.Platform;

public record AgentConnect(String claude, String json) {
    public static final String CONFIG_PROPERTY = "cruma.agent.config";

    private static final Pattern PLAIN_POSIX = Pattern.compile("[A-Za-z0-9_@%+=:,./-]+");
    private static final Pattern PLAIN_WINDOWS = Pattern.compile("[A-Za-z0-9_@+=:,./\\\\-]+");

    public static AgentConnect ofThisRun() {
        var folder = System.getProperty(CONFIG_PROPERTY);
        var arguments = folder == null ? List.of("mcp") : List.of("mcp", "--config", folder);
        return of(OwnProgram.command(arguments), Platform.get() == Platform.WINDOWS);
    }

    static AgentConnect of(List<String> command, boolean windows) {
        var line = new StringBuilder("claude mcp add --scope user cruma --");
        for (var part : command) {
            line.append(' ').append(windows ? quoteWindows(part) : quotePosix(part));
        }
        var server = new JSONObject();
        server.put("command", command.getFirst());
        server.put("args", command.subList(1, command.size()));
        var servers = new JSONObject();
        servers.put("cruma", server);
        var settings = new JSONObject();
        settings.put("mcpServers", servers);
        return new AgentConnect(line.toString(), settings.toJSONString());
    }

    private static String quotePosix(String part) {
        if (PLAIN_POSIX.matcher(part).matches()) {
            return part;
        }
        return '"'
                + part.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("$", "\\$")
                        .replace("`", "\\`")
                + '"';
    }

    private static String quoteWindows(String part) {
        if (PLAIN_WINDOWS.matcher(part).matches()) {
            return part;
        }
        return '"' + part.replace("\"", "\\\"") + '"';
    }
}
