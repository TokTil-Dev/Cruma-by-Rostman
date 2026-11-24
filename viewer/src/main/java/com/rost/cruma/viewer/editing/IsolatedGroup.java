package com.rost.cruma.viewer.editing;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

final class IsolatedGroup {
    private IsolatedGroup() {}

    static boolean inView(@Nullable Set<String> group, String actor) {
        return group == null || group.contains(actor);
    }

    static Set<String> after(Set<String> group, Set<String> selection, Predicate<String> created) {
        if (selection.isEmpty() || group.containsAll(selection)) {
            return group;
        }
        if (Collections.disjoint(group, selection) && !selection.stream().allMatch(created)) {
            return Set.copyOf(selection);
        }
        var joined = new HashSet<>(group);
        joined.addAll(selection);
        return Set.copyOf(joined);
    }
}
