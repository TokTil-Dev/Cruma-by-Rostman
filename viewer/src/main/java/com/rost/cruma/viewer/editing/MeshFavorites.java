package com.rost.cruma.viewer.editing;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public final class MeshFavorites {
    public static final String FILE = "favourites.json";

    private final Path file;
    private final TreeSet<String> meshes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private String failure = "";

    public MeshFavorites(Path file) {
        this.file = file;
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            var root = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            var list = root == null ? null : root.getJSONArray("meshes");
            if (list != null) {
                for (var i = 0; i < list.size(); i++) {
                    var name = list.getString(i);
                    if (name != null && !name.isBlank()) {
                        meshes.add(name.strip());
                    }
                }
            }
        } catch (IOException | RuntimeException broken) {
            meshes.clear();
        }
    }

    public boolean contains(String mesh) {
        return meshes.contains(mesh);
    }

    public List<String> list() {
        return new ArrayList<>(meshes);
    }

    public int size() {
        return meshes.size();
    }

    public boolean toggle(String mesh) {
        var now = !meshes.remove(mesh);
        if (now) {
            meshes.add(mesh);
        }
        save();
        return now;
    }

    public String getFailure() {
        return failure;
    }

    private void save() {
        var root = new JSONObject();
        root.put("version", 1);
        root.put("meshes", new JSONArray(new ArrayList<Object>(meshes)));
        try {
            Files.createDirectories(file.getParent());
            var temporary = Files.createTempFile(file.getParent(), ".favourites-", ".tmp");
            try {
                Files.writeString(temporary, JSON.toJSONString(root), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            failure = "";
        } catch (IOException failed) {
            failure = "The favourites could not be saved: " + failed.getMessage();
        }
    }
}
