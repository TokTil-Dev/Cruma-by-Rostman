package com.rost.cruma.viewer.config;

public final class ConfigurationProblem extends RuntimeException {
    public ConfigurationProblem(String message) {
        super(message);
    }

    public ConfigurationProblem(String message, Throwable cause) {
        super(message, cause);
    }
}
