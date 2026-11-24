package com.rost.cruma.core.schema;

import org.jetbrains.annotations.Nullable;

public record TraceField(
        String path,
        String type,
        int offset,
        int size,
        @Nullable Object value,
        String where) {}
