package com.rost.cruma.core.schema;

import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public record ReadResult(
        Map<String, Object> values,
        List<Map<String, Object>> rows,
        Map<String, Object> constants,
        ReadState state,
        int rowCount,
        @Nullable ReadFailure failure) {}
