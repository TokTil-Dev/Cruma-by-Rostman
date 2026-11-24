package com.rost.cruma.viewer.config;

import com.typesafe.config.ConfigException;
import java.io.IOException;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class AgentSettings {
    public static final String ENABLED = "viewer.agent.enabled";
    public static final String ACCESS = "viewer.agent.access";

    private final ConfigLoader loader;

    private AgentSettings(ConfigLoader loader) {
        this.loader = loader;
    }

    public static AgentSettings in(ConfigLoader loader) {
        return new AgentSettings(loader);
    }

    public void enabled(boolean value) {
        write(ENABLED, value);
    }

    public void setAccess(String word) {
        write(ACCESS, word);
    }

    public synchronized @Nullable String getAccess() {
        try {
            loader.reload();
            return loader.getEffectiveSettings().getString(ACCESS);
        } catch (ConfigException | ConfigurationProblem unreadable) {
            LogManager.getLogger(AgentSettings.class).debug("Could not read {}: {}", ACCESS, unreadable.toString());
            return null;
        }
    }

    private synchronized void write(String path, Object value) {
        try {
            loader.reload();
            var model = new OptionsModel(loader);
            model.set(path, value);
            if (model.hasChanges()) {
                model.save();
            }
        } catch (IOException | RuntimeException failure) {
            LogManager.getLogger(AgentSettings.class).warn("Could not save {}: {}", path, failure.toString());
        }
    }
}
