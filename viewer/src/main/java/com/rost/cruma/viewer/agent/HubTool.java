package com.rost.cruma.viewer.agent;

@FunctionalInterface
public interface HubTool {
    ToolResult call(ToolArgs args, AgentHub hub, Job job) throws ToolProblem, InterruptedException;
}
