package com.rost.cruma.core.schema;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public record TableSpec(
        String id,
        String fileName,
        boolean localized,
        List<String> required,
        List<String> optional,
        Map<String, Set<ValueKind>> kinds,
        String lost,
        @Nullable RowCheck check,
        @Nullable OnlyWith onlyWith) {
    public static final int CHECKED_ROWS = 50;

    public TableSpec {
        required = List.copyOf(required);
        optional = List.copyOf(optional);
        kinds = Map.copyOf(kinds);
    }

    public TableSpec(
            String id,
            String fileName,
            boolean localized,
            List<String> required,
            List<String> optional,
            String lost,
            @Nullable RowCheck check) {
        this(id, fileName, localized, required, optional, Map.of(), lost, check, null);
    }

    public TableSpec(
            String id,
            String fileName,
            boolean localized,
            FieldKinds required,
            FieldKinds optional,
            String lost,
            @Nullable RowCheck check) {
        this(
                id,
                fileName,
                localized,
                List.copyOf(required.toMap().keySet()),
                List.copyOf(optional.toMap().keySet()),
                merge(required, optional),
                lost,
                check,
                null);
    }

    public TableSpec onlyWith(String otherFile, String reason) {
        return new TableSpec(
                id,
                fileName,
                localized,
                required,
                optional,
                kinds,
                lost,
                check,
                new OnlyWith(otherFile, reason, false));
    }

    public TableSpec neededOnlyWith(String otherFile, String reason) {
        return new TableSpec(
                id, fileName, localized, required, optional, kinds, lost, check, new OnlyWith(otherFile, reason, true));
    }

    public Set<String> getWanted() {
        var wanted = new LinkedHashSet<>(required);
        wanted.addAll(optional);
        return Set.copyOf(wanted);
    }

    private static Map<String, Set<ValueKind>> merge(FieldKinds required, FieldKinds optional) {
        var all = new LinkedHashMap<>(required.toMap());
        all.putAll(optional.toMap());
        return all;
    }

    public record OnlyWith(String fileName, String reason, boolean ifUnread) {}

    @FunctionalInterface
    public interface RowCheck {
        @Nullable
        String findProblem(List<Map<String, Object>> rows);
    }
}
