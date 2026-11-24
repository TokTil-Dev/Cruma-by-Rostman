package com.rost.cruma.viewer.cli;

public class BooleanFlag extends BaseFlag<Boolean> {
    public BooleanFlag(String shortName, String longName) {
        super(shortName, longName, null);
    }

    @Override
    public Boolean parse(String value) {
        return Boolean.parseBoolean(value);
    }
}
