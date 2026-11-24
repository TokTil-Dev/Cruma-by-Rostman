package com.rost.cruma.viewer.agent;

public final class AgentSteps {
    public static final String PREFIX = "AI: ";

    public static final int MOST_STEPS = 256;

    private AgentSteps() {}

    public static boolean isOwn(String label, boolean hidden) {
        return hidden || label.startsWith(PREFIX);
    }

    public static String held(String label) {
        return "The next step is the person's: " + label + ". Only they can take it back.";
    }
}
