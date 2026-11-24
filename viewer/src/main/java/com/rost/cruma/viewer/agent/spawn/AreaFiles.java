package com.rost.cruma.viewer.agent.spawn;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.viewer.agent.AgentHub;
import com.rost.cruma.viewer.agent.HubTool;
import com.rost.cruma.viewer.agent.Job;
import com.rost.cruma.viewer.agent.Step;
import com.rost.cruma.viewer.agent.ToolArgs;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.agent.ToolResult;
import com.rost.cruma.viewer.agent.spawn.AreaTools.Desk;
import com.rost.cruma.viewer.spawn.AreaAccess;
import com.rost.cruma.viewer.spawn.AreaSource;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

final class AreaFiles {
    private static @Nullable AreaFiles last;

    private final Path folder;
    private final AreaSource source = new AreaSource();

    private AreaFiles(Path folder) {
        this.folder = folder;
        source.open(folder);
    }

    @FunctionalInterface
    interface Call {
        Step start(ToolArgs args, Desk desk) throws ToolProblem;
    }

    static HubTool alone(Call call) {
        return (args, hub, job) -> run(args, hub, job, call);
    }

    private Desk desk(AgentHub hub) {
        var access = new AreaAccess() {
            @Override
            public AreaSource getSource() {
                return source;
            }

            @Override
            public boolean isHeld() {
                return false;
            }

            @Override
            public boolean hasGeodata() {
                return false;
            }

            @Override
            public @Nullable GeoDataAccess readArea(double minX, double minY, double maxX, double maxY) {
                return null;
            }

            @Override
            public double findClientFloor(double x, double y, double z) {
                return Double.NaN;
            }
        };
        return new Desk(access, folder, text -> SpawnFiles.locate(hub, text), true, hub.getAccess());
    }

    private static ToolResult run(ToolArgs args, AgentHub hub, Job job, Call call)
            throws ToolProblem, InterruptedException {
        var files = files(args, hub);
        synchronized (files) {
            job.setProgress("Reading the script folder " + files.folder);
            var step = call.start(args, files.desk(hub));
            while (true) {
                files.source.poll();
                var result = step.step();
                if (result != null) {
                    return result;
                }
                if (job.isCancelled()) {
                    step.cancel();
                    throw new InterruptedException();
                }
                Thread.sleep(2);
            }
        }
    }

    private static synchronized AreaFiles files(ToolArgs args, AgentHub hub) throws ToolProblem {
        var asked = args.getText("scripts");
        var folder = asked == null ? null : SpawnFiles.folder(asked);
        for (var known : hub.getClients()) {
            var configured = SpawnFiles.configured(hub, known);
            if (configured != null && (folder == null || configured.equals(folder))) {
                folder = configured;
                break;
            }
        }
        if (folder == null) {
            throw new ToolProblem(
                    "No viewer is open and no script folder is known. Pass scripts with the server's script folder, or"
                            + " call world_open.");
        }
        if (last == null || !last.folder.equals(folder) || last.source.hasChangedOnDisk()) {
            if (last != null) {
                var old = last;
                synchronized (old) {
                    old.source.close();
                }
            }
            last = new AreaFiles(folder);
        }
        return last;
    }
}
