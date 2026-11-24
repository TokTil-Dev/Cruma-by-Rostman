package com.rost.cruma.viewer.agent;

@FunctionalInterface
public interface ViewerTool {
    Step start(ToolArgs args, ViewerContext viewer) throws ToolProblem;
}
