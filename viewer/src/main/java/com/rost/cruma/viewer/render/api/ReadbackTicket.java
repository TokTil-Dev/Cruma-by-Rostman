package com.rost.cruma.viewer.render.api;

import com.rost.cruma.viewer.render.api.RenderExtents.Extent;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ReadbackTicket {
    private final long id;
    private final Extent extent;
    private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
    private volatile ByteBuffer data;
    private volatile String failure;

    public ReadbackTicket(long id, Extent extent) {
        if (id < 0) {
            throw new IllegalArgumentException("readback id cannot be negative");
        }
        this.id = id;
        this.extent = Objects.requireNonNull(extent, "extent");
    }

    public enum State {
        QUEUED,
        SUBMITTED,
        READY,
        CANCELLED,
        FAILED
    }

    public long getId() {
        return id;
    }

    public Extent getExtent() {
        return extent;
    }

    public State getState() {
        return state.get();
    }

    public ByteBuffer getData() {
        var value = data;
        return value == null ? null : value.asReadOnlyBuffer();
    }

    public String getFailure() {
        return failure;
    }

    public ByteBuffer release() {
        var value = data;
        data = null;
        return value;
    }

    public boolean markSubmitted() {
        return state.compareAndSet(State.QUEUED, State.SUBMITTED);
    }

    public boolean isComplete(ByteBuffer completedData) {
        Objects.requireNonNull(completedData, "completedData");
        if (!state.compareAndSet(State.SUBMITTED, State.READY)) {
            return false;
        }
        data = completedData;
        return true;
    }

    public boolean cancel() {
        while (true) {
            var current = state.get();
            if (current == State.READY || current == State.CANCELLED || current == State.FAILED) {
                return false;
            }
            if (state.compareAndSet(current, State.CANCELLED)) {
                return true;
            }
        }
    }

    public boolean fail(String message) {
        Objects.requireNonNull(message, "message");
        while (true) {
            var current = state.get();
            if (current == State.READY || current == State.CANCELLED || current == State.FAILED) {
                return false;
            }
            if (state.compareAndSet(current, State.FAILED)) {
                failure = message;
                return true;
            }
        }
    }
}
