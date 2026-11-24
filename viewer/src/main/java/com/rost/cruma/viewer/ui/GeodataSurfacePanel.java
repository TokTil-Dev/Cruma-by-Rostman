package com.rost.cruma.viewer.ui;

import com.rost.cruma.core.geodata.GeoConnectedSurface;
import com.rost.cruma.core.geodata.GeoConnectedSurface.Connectivity;
import com.rost.cruma.core.geodata.GeoConnectedSurface.Result;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.core.geodata.GeoReachability.Limits;
import com.rost.cruma.viewer.geodata.GeoInspection;
import com.rost.cruma.viewer.geodata.GeoInspection.Selection;
import com.rost.cruma.viewer.geodata.GeoSurfaceFilter;
import com.rost.cruma.viewer.render.GeodataState;
import imgui.ImGui;
import imgui.type.ImInt;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class GeodataSurfacePanel implements AutoCloseable {
    private final ImInt connectivity = new ImInt();
    private final ImInt radius = new ImInt(512);
    private final ImInt maximumNodes = new ImInt(20000);
    private final ImInt maximumMillis = new ImInt(1000);
    private boolean dimOthers;
    private volatile long generation;
    private volatile boolean busy;
    private volatile Completion completed;
    private Future<?> pending;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "geodata-surface");
        thread.setDaemon(true);
        return thread;
    });

    public GeodataSurfacePanel() {}

    GeodataSurfacePanel(int radius, int maximumNodes, int maximumMillis) {
        this.radius.set(radius);
        this.maximumNodes.set(maximumNodes);
        this.maximumMillis.set(maximumMillis);
    }

    public boolean isActive(GeodataState state) {
        return busy || state.surfaceSeed != null;
    }

    public void draw(GeodataState state) {
        poll(state);
        var filter = state.surfaceFilter;
        if (busy) {
            ImGui.textColored(GeodataWidgets.MUTED, "Finding the connected area…");
            if (GeodataWidgets.actionButton("area-cancel", null, "Cancel", -1, false)) {
                cancel();
                state.surfaceStatus = "Area search cancelled.";
            }
        } else if (filter != null) {
            var surface = filter.getSurface();
            var reach = surface.reachability();
            GeodataWidgets.dot(
                    reach.completeness() == Completeness.COMPLETE ? GeodataWidgets.OPEN : GeodataWidgets.WARNING);
            ImGui.textUnformatted(String.format(
                    Locale.ROOT, "%,d connected layers", surface.layers().size()));
            ImGui.sameLine();
            ImGui.textColored(GeodataWidgets.MUTED, "· " + GeodataWidgets.completenessLabel(reach.completeness()));
            if (reach.completeness() != Completeness.COMPLETE) {
                GeodataWidgets.tip("Only proven connections are bright. The rest was not explored; it is not proof of"
                        + " disconnection.");
            }
        } else if (!state.surfaceStatus.isBlank()) {
            ImGui.pushTextWrapPos(0);
            ImGui.textColored(GeodataWidgets.MUTED, state.surfaceStatus);
            ImGui.popTextWrapPos();
        }
        var choice = GeodataWidgets.segmented(
                "area-connectivity", new String[] {"Both ways", "From", "To", "Either"}, connectivity.get());
        if (choice >= 0 && choice != connectivity.get()) {
            connectivity.set(choice);
            if (state.surfaceSeed != null) {
                recomputePinned(state);
            }
        }
        GeodataWidgets.tip(
                "Both ways: cells you can walk to and back from. From: reachable from the cell, which shows one-way"
                        + " drops. To: cells that can reach it. Either: the union.");
        var radiusValue = new int[] {radius.get()};
        GeodataFields.slider(
                "Area radius", radiusValue, 16, 8192, "Maximum horizontal distance from the cell, in world units.");
        if (radiusValue[0] != radius.get()) {
            radius.set(radiusValue[0]);
        }
        if (ImGui.checkbox("Keep other floors faintly visible", dimOthers)) {
            dimOthers = !dimOthers;
            if (state.surfaceFilter != null) {
                state.surfaceFilter = new GeoSurfaceFilter(state.surfaceFilter.getSurface(), dimOthers);
            }
        }
    }

    public void toggle(GeodataState state) {
        if (isActive(state)) {
            clear(state);
        } else {
            pinSelected(state);
        }
    }

    void poll(GeodataState state) {
        var completion = completed;
        if (completion != null) {
            completed = null;
            if (completion.surface() != null
                    && completion.surface().belongsTo(state.queryData())
                    && completion.seed().equals(state.surfaceSeed)) {
                state.surfaceFilter = new GeoSurfaceFilter(completion.surface(), dimOthers);
                state.surfaceStatus = "Calculated the pinned source cell and its connected layers.";
            } else {
                state.surfaceStatus = completion.surface() == null
                        ? completion.error()
                        : "Data coverage or revision changed. Recalculate the pinned surface.";
            }
        }
        if (state.surfaceFilter != null && !state.surfaceFilter.belongsTo(state.queryData())) {
            state.surfaceFilter = null;
            state.surfaceStatus = "Data coverage or revision changed. Recalculate the pinned surface.";
        }
    }

    boolean pinSelected(GeodataState state) {
        state.queryData();
        return compute(state, state.inspection.selected);
    }

    boolean recomputePinned(GeodataState state) {
        state.queryData();
        return compute(state, state.surfaceSeed);
    }

    boolean isBusy() {
        return busy;
    }

    void clear(GeodataState state) {
        cancel();
        state.surfaceFilter = null;
        state.surfaceSeed = null;
        state.surfaceStatus = "Surface filter and pinned seed cleared.";
    }

    @Override
    public void close() {
        cancel();
        worker.shutdownNow();
    }

    private record Completion(Result surface, Selection seed, String error) {}

    private boolean compute(GeodataState state, Selection selected) {
        var data = state.queryData();
        if (GeoInspection.resolve(data, selected) == null) {
            state.surfaceStatus = "The pinned source layer is unavailable or changed. Select a valid seed explicitly.";
            return false;
        }
        cancel();
        state.surfaceSeed = selected;
        state.surfaceFilter = null;
        state.surfaceStatus = "Calculating the pinned surface…";
        busy = true;
        var request = generation;
        var seed = selected.ref();
        var direction = Connectivity.values()[connectivity.get()];
        var selectedRadius = Math.clamp(radius.get(), 16, 32768);
        var selectedMaximum = Math.clamp(maximumNodes.get(), 1, 200000);
        var selectedMillis = Math.clamp(maximumMillis.get(), 1, 60000);
        pending = worker.submit(() -> {
            try {
                var result = GeoConnectedSurface.select(
                        data,
                        seed,
                        direction,
                        new Limits(
                                selectedRadius,
                                selectedMaximum,
                                selectedMillis,
                                () -> generation != request
                                        || Thread.currentThread().isInterrupted()));
                if (generation == request) {
                    completed = new Completion(result, selected, "");
                }
            } catch (RuntimeException error) {
                if (generation == request) {
                    completed = new Completion(
                            null,
                            selected,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                }
            } finally {
                if (generation == request) {
                    busy = false;
                }
            }
        });
        return true;
    }

    private void cancel() {
        generation++;
        if (pending != null) {
            pending.cancel(true);
        }
        pending = null;
        completed = null;
        busy = false;
    }
}
