package com.rost.cruma.viewer.editing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class ActorGroups {
    private final LinkedHashMap<String, LinkedHashSet<String>> groups = new LinkedHashMap<>();
    private @Nullable String entered;
    private long revision;

    long getRevision() {
        return revision;
    }

    Map<String, List<String>> getStored() {
        var result = new LinkedHashMap<String, List<String>>();
        groups.forEach((name, members) -> result.put(name, List.copyOf(members)));
        return Collections.unmodifiableMap(result);
    }

    void restore(Map<String, List<String>> stored) {
        groups.clear();
        stored.forEach((name, members) -> {
            if (!name.isBlank() && !members.isEmpty()) {
                groups.put(name, new LinkedHashSet<>(members));
            }
        });
        entered = null;
        revision++;
    }

    List<String> getNames(Predicate<String> exists) {
        var result = new ArrayList<String>();
        groups.forEach((name, members) -> {
            if (members.stream().anyMatch(exists)) {
                result.add(name);
            }
        });
        return result;
    }

    Set<String> getMembers(String name, Predicate<String> exists) {
        var stored = groups.get(name);
        if (stored == null) {
            return Set.of();
        }
        var result = new LinkedHashSet<String>();
        for (var path : stored) {
            if (exists.test(path)) {
                result.add(path);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    @Nullable
    String findGroupOf(String actor) {
        for (var entry : groups.entrySet()) {
            if (entry.getValue().contains(actor)) {
                return entry.getKey();
            }
        }
        return null;
    }

    String nextName() {
        for (var number = 1; ; number++) {
            var name = "Group " + number;
            if (!containsName(name)) {
                return name;
            }
        }
    }

    String create(String name, Collection<String> members) {
        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            trimmed = nextName();
        }
        if (members.size() < 2) {
            throw new IllegalArgumentException("Select two or more objects to group them.");
        }
        if (containsName(trimmed)) {
            throw new IllegalArgumentException("A group named " + trimmed + " exists already. Choose another name.");
        }
        for (var group : groups.values()) {
            group.removeAll(members);
        }
        groups.values().removeIf(Set::isEmpty);
        groups.put(trimmed, new LinkedHashSet<>(members));
        entered = null;
        revision++;
        return trimmed;
    }

    void ungroup(String name) {
        if (groups.remove(name) != null) {
            if (name.equals(entered)) {
                entered = null;
            }
            revision++;
        }
    }

    void rename(String name, String next) {
        var trimmed = next.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A group needs a name.");
        }
        if (!groups.containsKey(name) || trimmed.equals(name)) {
            return;
        }
        if (!trimmed.equalsIgnoreCase(name) && containsName(trimmed)) {
            throw new IllegalArgumentException("A group named " + trimmed + " exists already. Choose another name.");
        }
        var renamed = new LinkedHashMap<String, LinkedHashSet<String>>();
        groups.forEach((key, members) -> renamed.put(key.equals(name) ? trimmed : key, members));
        groups.clear();
        groups.putAll(renamed);
        if (name.equals(entered)) {
            entered = trimmed;
        }
        revision++;
    }

    void removeDeleted(Collection<String> deleted, Predicate<String> exists) {
        var changed = false;
        for (var group : groups.values()) {
            changed |= group.removeAll(deleted);
        }
        changed |= groups.values().removeIf(members -> members.stream().noneMatch(exists));
        if (changed) {
            revision++;
        }
    }

    void forget(Collection<String> created) {
        var changed = false;
        for (var group : groups.values()) {
            changed |= group.removeAll(created);
        }
        changed |= groups.values().removeIf(Set::isEmpty);
        if (changed) {
            revision++;
        }
    }

    void moved(Map<String, String> paths) {
        var changed = false;
        for (var group : groups.values()) {
            for (var entry : paths.entrySet()) {
                if (!entry.getKey().equals(entry.getValue()) && group.contains(entry.getKey())) {
                    changed |= group.add(entry.getValue());
                }
            }
        }
        if (changed) {
            revision++;
        }
    }

    Set<String> click(String actor, Set<String> selection, Predicate<String> exists) {
        var group = findGroupOf(actor);
        if (group == null) {
            entered = null;
            return Set.of(actor);
        }
        var members = getMembers(group, exists);
        if (group.equals(getEntered(selection, exists)) || selection.equals(members)) {
            entered = group;
            return Set.of(actor);
        }
        entered = null;
        return members;
    }

    @Nullable
    String getEntered(Set<String> selection, Predicate<String> exists) {
        if (entered == null) {
            return null;
        }
        var members = getMembers(entered, exists);
        if (selection.isEmpty() || !members.containsAll(selection)) {
            entered = null;
        }
        return entered;
    }

    @Nullable
    Set<String> leave(Set<String> selection, Predicate<String> exists) {
        var group = getEntered(selection, exists);
        if (group == null) {
            return null;
        }
        entered = null;
        return getMembers(group, exists);
    }

    void enter(String name) {
        entered = groups.containsKey(name) ? name : null;
    }

    @Nullable
    String findSelectedWhole(Set<String> selection, Predicate<String> exists) {
        if (selection.size() < 2) {
            return null;
        }
        for (var name : groups.keySet()) {
            if (getMembers(name, exists).equals(selection)) {
                return name;
            }
        }
        return null;
    }

    private boolean containsName(String name) {
        for (var key : groups.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
