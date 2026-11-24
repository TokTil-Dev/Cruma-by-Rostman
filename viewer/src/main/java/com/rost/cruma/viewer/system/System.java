package com.rost.cruma.viewer.system;

import com.rost.cruma.viewer.application.WindowContext;

public interface System {
    default void start() {}

    default void stop(WindowContext windowContext) {}

    default void frameBegin(long frameTime) {}

    default void frameEnd(long frameTime) {}
}
