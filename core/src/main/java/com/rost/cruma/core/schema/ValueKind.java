package com.rost.cruma.core.schema;

public enum ValueKind {
    WHOLE("a whole number"),
    NUMBER("a number"),
    TEXT("a text"),
    TEXTS("a list of texts"),
    NUMBERS("a list of numbers"),
    STRUCT("a struct"),
    GROUPS("a list of groups");

    private final String words;

    ValueKind(String words) {
        this.words = words;
    }

    public String getWords() {
        return words;
    }
}
