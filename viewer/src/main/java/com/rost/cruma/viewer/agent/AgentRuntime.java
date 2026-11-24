package com.rost.cruma.viewer.agent;

import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.viewer.agent.AgentFindings.Findings;
import com.rost.cruma.viewer.agent.effect.EffectSteps;
import com.rost.cruma.viewer.agent.view.SeenWorld;
import com.rost.cruma.viewer.config.AgentSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

public final class AgentRuntime implements AutoCloseable {
    private static final long OWNER_ARRIVES_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long OWNER_GONE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long REST_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long STATUS_EVERY_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final Logger log = LogManager.getLogger(AgentRuntime.class);

    private final AgentActivity activity = new AgentActivity();
    private final AgentFindings findings = new AgentFindings();
    private final SeenWorld seen = new SeenWorld();
    private final EffectSteps effectSteps = new EffectSteps();
    private final AgentFrames frames;
    private final AtomicInteger connections = new AtomicInteger();
    private final boolean owned;
    private final boolean hidden;
    private volatile Access access;
    private volatile boolean enabled;
    private volatile boolean stopped;
    private volatile boolean headless;
    private volatile boolean shutdownAsked;
    private volatile JSONObject status = new JSONObject();
    private volatile long lastContactNanos = System.nanoTime();
    private volatile boolean everConnected;
    private long nextStatusNanos;
    private @Nullable AgentBridge bridge;
    private @Nullable Place place;
    private @Nullable String openProblem;
    private boolean panelWanted;

    public AgentRuntime(Access access, boolean enabled, boolean owned, boolean hidden) {
        this(access, enabled, owned, hidden, Tools.all(), GLFW::glfwPostEmptyEvent);
    }

    public AgentRuntime(Access access, boolean enabled, boolean owned, boolean hidden, Tools tools, Runnable wake) {
        this.access = access;
        this.enabled = enabled;
        this.owned = owned;
        this.hidden = hidden;
        frames = new AgentFrames(tools, this::refuses, activity, wake);
    }

    public void prepare(Path folder, String client, List<String> tiles, AgentSettings settings) {
        place = new Place(folder, client, tiles, settings);
    }

    public void start() {
        follow();
    }

    public void end() {
        close();
        if (stopped && place != null) {
            AgentStop.mark(place.folder(), false);
        }
    }

    public void setHeadless(boolean value) {
        headless = value;
    }

    public boolean isHeadless() {
        return headless;
    }

    public boolean canOpen() {
        return place != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (place != null) {
            place.settings().enabled(value);
        }
        follow();
    }

    @Override
    public void close() {
        openProblem = null;
        if (bridge != null) {
            bridge.close();
            bridge = null;
            frames.abandon();
        }
        seen.close();
    }

    public boolean isOpen() {
        return bridge != null;
    }

    public @Nullable String getOpenProblem() {
        return openProblem;
    }

    public void frame(ViewerContext viewer) {
        var now = System.nanoTime();
        if (now >= nextStatusNanos) {
            nextStatusNanos = now + STATUS_EVERY_NANOS;
            status = viewer.status();
        }
        var calls = activity.getRevision();
        frames.frame(viewer);
        if (activity.getRevision() != calls) {
            refreshStatus(viewer);
        }
    }

    public void refreshStatus(ViewerContext viewer) {
        status = viewer.status();
    }

    public boolean isBusy() {
        return frames.isBusy();
    }

    public boolean rests(boolean working) {
        return hidden && !working && !isBusy() && System.nanoTime() - lastContactNanos > REST_AFTER_NANOS;
    }

    public boolean ends() {
        if (shutdownAsked) {
            return true;
        }
        if (!owned || !hidden || connections.get() > 0) {
            return false;
        }
        var quiet = System.nanoTime() - lastContactNanos;
        return quiet > (everConnected ? OWNER_GONE_NANOS : OWNER_ARRIVES_NANOS);
    }

    AgentFrames getFrames() {
        return frames;
    }

    public SeenWorld getSeen() {
        return seen;
    }

    public EffectSteps getEffectSteps() {
        return effectSteps;
    }

    public boolean isHidden() {
        return hidden;
    }

    public boolean isOwned() {
        return owned;
    }

    JSONObject getStatus() {
        contact();
        return status;
    }

    void contact() {
        lastContactNanos = System.nanoTime();
    }

    void joined() {
        connections.incrementAndGet();
        everConnected = true;
        contact();
    }

    void left() {
        connections.decrementAndGet();
        contact();
    }

    void askShutdown() {
        shutdownAsked = true;
        GLFW.glfwPostEmptyEvent();
    }

    public AgentActivity getActivity() {
        return activity;
    }

    public AgentFindings getFindings() {
        return findings;
    }

    public void show(Findings list) {
        findings.show(list);
        panelWanted = true;
    }

    public boolean takePanelWanted() {
        var wanted = panelWanted;
        panelWanted = false;
        return wanted;
    }

    public Access getAccess() {
        return access;
    }

    public synchronized void setAccess(Access level) {
        access = level;
        if (place != null) {
            place.settings().setAccess(level.getWord());
        }
    }

    synchronized void readAccess() {
        var word = place == null ? null : place.settings().getAccess();
        if (word != null) {
            access = Access.parse(word);
        }
    }

    public boolean isStopped() {
        return stopped;
    }

    public void setStopped(boolean value) {
        stopped = value;
        if (place != null) {
            AgentStop.mark(place.folder(), value);
        }
    }

    public int getConnections() {
        return connections.get();
    }

    private @Nullable String refuses(ToolSpec spec) {
        if (headless && spec.graphics()) {
            return spec.name()
                    + " draws, and this viewer has no graphics card. Reads, edits and geodata work here. For pictures, "
                    + "call world_open with window hidden on a computer with a graphics card.";
        }
        if (bridge == null) {
            return "The person turned off Let an AI agent use this window in Cruma.";
        }
        if (stopped) {
            return "The person stopped agents in Cruma. Ask them to press Allow in the AI agent panel.";
        }
        if (!access.allows(spec.access())) {
            return spec.name()
                    + " needs "
                    + spec.access().getLabel()
                    + " access, and this viewer gives "
                    + access.getLabel()
                    + ". Ask the person to raise it in the AI agent panel of Cruma.";
        }
        return null;
    }

    private void follow() {
        if (place == null) {
            return;
        }
        if (owned || enabled) {
            open(place);
        } else {
            close();
        }
    }

    private void open(Place where) {
        if (bridge != null) {
            return;
        }
        var opened = new AgentBridge(this, where.folder(), where.client(), where.tiles());
        try {
            opened.open();
            bridge = opened;
            openProblem = null;
        } catch (IOException failed) {
            log.warn("Could not open the socket for agents", failed);
            openProblem = failed.getMessage();
        }
    }

    private record Place(Path folder, String client, List<String> tiles, AgentSettings settings) {}
}
