package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public record ViewerEndpoint(
        long pid,
        int port,
        String token,
        String client,
        List<String> tiles,
        boolean hidden,
        boolean owned,
        boolean headless,
        @Nullable String version) {
    private static final String PREFIX = "viewer-";
    private static final String SUFFIX = ".json";

    public static Path folder(Path configDirectory) {
        return configDirectory.resolve("agent");
    }

    public static @Nullable String ownVersion() {
        return ViewerEndpoint.class.getPackage().getImplementationVersion();
    }

    static boolean isEndpoint(Path file) {
        var name = file.getFileName().toString();
        return name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    static ViewerEndpoint read(Path file) throws IOException {
        var json = JSON.parseObject(Files.readString(file));
        return new ViewerEndpoint(
                json.getLongValue("pid"),
                json.getIntValue("port"),
                json.getString("token"),
                json.getString("client"),
                json.getList("tiles", String.class),
                json.getBooleanValue("hidden"),
                json.getBooleanValue("owned"),
                json.getBooleanValue("headless"),
                json.getString("version"));
    }

    public String id() {
        return "v" + pid;
    }

    @Nullable
    String refuses(@Nullable String hubVersion) {
        if (version == null || hubVersion == null || version.equals(hubVersion)) {
            return null;
        }
        return "Viewer "
                + id()
                + " runs Cruma "
                + version
                + ", and the agent's program still runs Cruma "
                + hubVersion
                + ", so their tools can differ. Ask the person to restart the agent's program, or to connect Cruma in"
                + " it again.";
    }

    Path file(Path folder) {
        return folder.resolve(PREFIX + pid + SUFFIX);
    }

    void write(Path folder) throws IOException {
        Files.createDirectories(folder);
        var file = file(folder);
        var temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            var ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(ownerOnly));
        } else {
            Files.createFile(temporary);
        }
        Files.writeString(temporary, JSON.toJSONString(toJson()));
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private JSONObject toJson() {
        var json = new JSONObject();
        json.put("pid", pid);
        json.put("port", port);
        json.put("token", token);
        json.put("client", client);
        json.put("tiles", tiles);
        json.put("hidden", hidden);
        json.put("owned", owned);
        json.put("headless", headless);
        json.put("version", version);
        return json;
    }
}
