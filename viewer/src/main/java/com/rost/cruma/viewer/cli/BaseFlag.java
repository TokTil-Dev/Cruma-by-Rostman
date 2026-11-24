package com.rost.cruma.viewer.cli;

import java.util.Optional;

abstract class BaseFlag<T> implements Flag<T> {
    private final String shortName;
    private final String longName;
    private final Optional<T> defaultValue;

    public BaseFlag(String shortName, String longName, Optional<T> defaultValue) {
        this.shortName = shortName;
        this.longName = longName;
        this.defaultValue = defaultValue;
    }

    public String getShortName() {
        return this.shortName;
    }

    public String getLongName() {
        return this.longName;
    }

    public Optional<T> getDefaultValue() {
        return this.defaultValue;
    }
}
