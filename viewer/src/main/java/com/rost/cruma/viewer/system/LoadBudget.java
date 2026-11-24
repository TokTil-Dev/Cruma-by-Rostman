package com.rost.cruma.viewer.system;

final class LoadBudget {
    private final long bytes;
    private long waiting;
    private int next;
    private boolean stopped;

    LoadBudget(long bytes) {
        if (bytes < 1L) {
            throw new IllegalArgumentException("load budget must be positive");
        }
        this.bytes = bytes;
    }

    synchronized void awaitRoom(int index) throws InterruptedException {
        while (!stopped && waiting > bytes && index != next) {
            wait();
        }
    }

    synchronized void finished(long size) {
        waiting = Math.addExact(waiting, size);
    }

    synchronized void published(long size) {
        waiting -= size;
        next++;
        notifyAll();
    }

    synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    synchronized long getWaitingBytes() {
        return waiting;
    }
}
