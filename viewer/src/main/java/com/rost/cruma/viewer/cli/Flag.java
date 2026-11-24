package com.rost.cruma.viewer.cli;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public interface Flag<T> {
    String getShortName();

    String getLongName();

    @Nullable
    Optional<T> getDefaultValue();

    T parse(String value);
}
