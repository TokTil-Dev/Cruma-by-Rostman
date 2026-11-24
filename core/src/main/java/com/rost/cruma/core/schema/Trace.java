package com.rost.cruma.core.schema;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record Trace(
        int row, List<TraceField> fields, @Nullable ReadFailure failure) {}
