package com.rost.cruma.core.spawn;

import com.rost.cruma.core.schema.TableReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class TableRows {
    private TableRows() {}

    static IOException failure(TableReport report) {
        var file = report.file().getFileName();
        if (report.attempts().isEmpty()) {
            return new IOException(file + ": " + report.problem());
        }
        var failures = new ArrayList<String>();
        for (var attempt : report.attempts()) {
            failures.add(attempt.title() + ": " + attempt.reason());
        }
        return new IOException(file + " matches no known layout (" + String.join("; ", failures) + ")");
    }

    static String reference(@Nullable Object value) {
        return value instanceof String text ? NpcGrpTable.cleanReference(text) : "";
    }

    static List<String> references(@Nullable Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        var clean = true;
        for (var item : list) {
            if (!(item instanceof String text) || !text.equals(reference(text))) {
                clean = false;
                break;
            }
        }
        if (clean) {
            @SuppressWarnings("unchecked")
            var texts = (List<String>) list;
            return Collections.unmodifiableList(texts);
        }
        var references = new ArrayList<String>(list.size());
        for (var item : list) {
            references.add(reference(item));
        }
        return List.copyOf(references);
    }
}
