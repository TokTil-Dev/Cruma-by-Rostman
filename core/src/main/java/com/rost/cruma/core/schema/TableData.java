package com.rost.cruma.core.schema;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record TableData(
        List<Map<String, Object>> rows,
        Map<String, Object> values,
        Map<String, Object> constants,
        @Nullable Schema schema,
        TableReport report) {}
