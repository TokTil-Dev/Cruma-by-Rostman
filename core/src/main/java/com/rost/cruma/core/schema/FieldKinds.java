package com.rost.cruma.core.schema;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class FieldKinds {
    private final Map<String, Set<ValueKind>> kinds = new LinkedHashMap<>();

    private FieldKinds() {}

    public static FieldKinds of(ValueKind kind, String... names) {
        return new FieldKinds().and(kind, names);
    }

    public static FieldKinds none() {
        return new FieldKinds();
    }

    public FieldKinds and(ValueKind kind, String... names) {
        for (var name : names) {
            kinds.computeIfAbsent(name, unused -> EnumSet.noneOf(ValueKind.class))
                    .add(kind);
        }
        return this;
    }

    public Map<String, Set<ValueKind>> toMap() {
        var copy = new LinkedHashMap<String, Set<ValueKind>>();
        kinds.forEach((name, set) -> copy.put(name, Collections.unmodifiableSet(EnumSet.copyOf(set))));
        return Collections.unmodifiableMap(copy);
    }
}
