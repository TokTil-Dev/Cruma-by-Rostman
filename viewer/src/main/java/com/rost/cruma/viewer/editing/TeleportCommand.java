package com.rost.cruma.viewer.editing;

final class TeleportCommand {
    private TeleportCommand() {}

    static String coordinates(float x, float y, float z) {
        return whole(x) + " " + whole(y) + " " + whole(z);
    }

    static String format(String template, float x, float y, float z) {
        return template.replace("%x", whole(x))
                .replace("%y", whole(y))
                .replace("%z", whole(z))
                .strip();
    }

    private static String whole(float value) {
        return Long.toString(Math.round((double) value));
    }
}
