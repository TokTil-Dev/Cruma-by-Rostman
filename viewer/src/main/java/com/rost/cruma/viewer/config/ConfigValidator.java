package com.rost.cruma.viewer.config;

import com.rost.cruma.viewer.config.ConfigMigrations.Removal;
import com.rost.cruma.viewer.config.ConfigMigrations.Rename;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class ConfigValidator {
    private final TreeSet<String> knownPaths;
    private final Map<String, Rename> renames;
    private final Map<String, Removal> removals;

    public ConfigValidator(Config defaults) {
        this(defaults, ConfigMigrations.getRenames(), ConfigMigrations.getRemovals());
    }

    ConfigValidator(Config defaults, Map<String, Rename> renames, Map<String, Removal> removals) {
        this.knownPaths = new TreeSet<>();
        collectLeafPaths(defaults.root(), "", knownPaths);
        this.renames = renames;
        this.removals = removals;
    }

    public List<String> findProblems(Config user) {
        var userPaths = new TreeSet<String>();
        collectLeafPaths(user.root(), "", userPaths);
        var problems = new ArrayList<String>();
        for (var path : userPaths) {
            if (isKnown(path) || ConfigMigrations.isAcceptedAlias(path)) {
                continue;
            }
            var rename = renames.get(path);
            if (rename != null) {
                problems.add(path + " was renamed to " + rename.newPath() + " in " + rename.sinceVersion());
                continue;
            }
            var removal = removalOf(path);
            if (removal != null) {
                if (removal.ignored()) {
                    continue;
                }
                problems.add(path + " was removed in " + removal.sinceVersion() + ": " + removal.reason());
                continue;
            }
            var nearest = nearestKnown(path);
            problems.add("unknown key " + path + (nearest == null ? "" : " (did you mean " + nearest + "?)"));
        }
        return problems;
    }

    private Removal removalOf(String path) {
        for (var key = path; ; key = key.substring(0, key.lastIndexOf('.'))) {
            var removal = removals.get(key);
            if (removal != null || key.indexOf('.') < 0) {
                return removal;
            }
        }
    }

    private boolean isKnown(String path) {
        if (knownPaths.contains(path)) {
            return true;
        }
        for (var known = knownPaths.floor(path); known != null; known = knownPaths.lower(known)) {
            if (path.startsWith(known + ".")) {
                return true;
            }
            if (!path.startsWith(known.substring(0, Math.min(known.length(), 1)))) {
                break;
            }
        }
        return false;
    }

    private String nearestKnown(String path) {
        var bestDistance = Integer.MAX_VALUE;
        String best = null;
        for (var known : knownPaths) {
            var distance = editDistance(path, known);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = known;
            }
        }
        return best != null && bestDistance <= Math.max(2, path.length() / 3) ? best : null;
    }

    private static void collectLeafPaths(ConfigObject object, String prefix, TreeSet<String> into) {
        for (var entry : object.entrySet()) {
            var path = prefix.isEmpty() ? quote(entry.getKey()) : prefix + "." + quote(entry.getKey());
            ConfigValue value = entry.getValue();
            if (value instanceof ConfigObject child && !child.isEmpty()) {
                collectLeafPaths(child, path, into);
            } else {
                into.add(path);
            }
        }
    }

    private static String quote(String key) {
        return key.contains(".") ? "\"" + key + "\"" : key;
    }

    private static int editDistance(String a, String b) {
        var previous = new int[b.length() + 1];
        var current = new int[b.length() + 1];
        for (var j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (var i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (var j = 1; j <= b.length(); j++) {
                var cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
