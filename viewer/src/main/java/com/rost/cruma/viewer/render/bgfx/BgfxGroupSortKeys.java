package com.rost.cruma.viewer.render.bgfx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

final class BgfxGroupSortKeys {
    private boolean[] orderSensitive = new boolean[0];
    private int[] program = new int[0];
    private int[] binding = new int[0];
    private long[] state = new long[0];
    private double[] distance = new double[0];
    private long[] primitive = new long[0];
    private long[] object = new long[0];

    void prepare(int count, int workers, IntConsumer prepareGroup) {
        ensureCapacity(count);
        workers = Math.clamp(workers, 1, 4);
        if (workers == 1 || count < 512) {
            for (var index = 0; index < count; index++) {
                prepareGroup.accept(index);
            }
            return;
        }
        var next = new AtomicInteger();
        var work = new CompletableFuture<?>[workers];
        for (var worker = 0; worker < workers; worker++) {
            work[worker] = CompletableFuture.runAsync(
                    () -> {
                        for (var start = next.getAndAdd(32); start < count; start = next.getAndAdd(32)) {
                            var limit = Math.min(count, start + 32);
                            for (var index = start; index < limit; index++) {
                                prepareGroup.accept(index);
                            }
                        }
                    },
                    PreparationExecutor.INSTANCE);
        }
        CompletableFuture.allOf(work).join();
    }

    void ensureCapacity(int count) {
        if (distance.length >= count) {
            return;
        }
        var capacity = Math.max(count, Math.max(16, distance.length * 2));
        orderSensitive = new boolean[capacity];
        program = new int[capacity];
        binding = new int[capacity];
        state = new long[capacity];
        distance = new double[capacity];
        primitive = new long[capacity];
        object = new long[capacity];
    }

    void set(
            int group,
            boolean sensitive,
            int programBin,
            int bindingBin,
            long stateBin,
            long primitiveId,
            long objectId) {
        orderSensitive[group] = sensitive;
        program[group] = programBin;
        binding[group] = bindingBin;
        state[group] = stateBin;
        primitive[group] = primitiveId;
        object[group] = objectId;
    }

    void setDistance(int group, double value) {
        distance[group] = value;
    }

    double getDistance(int group) {
        return distance[group];
    }

    int compare(int left, int right) {
        if (orderSensitive[left] || orderSensitive[right]) {
            if (orderSensitive[left] != orderSensitive[right]) {
                return orderSensitive[left] ? 1 : -1;
            }
            return Integer.compare(left, right);
        }
        var comparison = Integer.compare(program[left], program[right]);
        if (comparison == 0) {
            comparison = Integer.compare(binding[left], binding[right]);
        }
        if (comparison == 0) {
            comparison = Long.compare(state[left], state[right]);
        }
        if (comparison == 0) {
            comparison = Double.compare(distance[left], distance[right]);
        }
        if (comparison == 0) {
            comparison = Long.compare(primitive[left], primitive[right]);
        }
        return comparison != 0 ? comparison : Long.compare(object[left], object[right]);
    }

    private static final class PreparationExecutor {
        private static final ExecutorService INSTANCE = Executors.newFixedThreadPool(
                4, Thread.ofPlatform().daemon().name("group-sort-keys-", 0).factory());
    }
}
