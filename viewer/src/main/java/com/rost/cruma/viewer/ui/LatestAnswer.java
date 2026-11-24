package com.rost.cruma.viewer.ui;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class LatestAnswer<Q, A> {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "cruma-settings-disk");
        thread.setDaemon(true);
        return thread;
    });

    private final Function<Q, A> work;
    private final A none;
    private final ExecutorService executor;
    private final AtomicReference<Answer<Q, A>> last;
    private volatile @Nullable Q asked;
    private @Nullable Future<?> pending;

    private record Answer<Q, A>(@Nullable Q question, A value) {}

    public LatestAnswer(Function<Q, A> work, A none) {
        this(work, none, WORKER);
    }

    public LatestAnswer(Function<Q, A> work, A none, ExecutorService executor) {
        this.work = work;
        this.none = none;
        this.executor = executor;
        last = new AtomicReference<>(new Answer<>(null, none));
    }

    public A get(Q question) {
        var answer = last.get();
        if (Objects.equals(question, answer.question())) {
            return answer.value();
        }
        if (!Objects.equals(question, asked)) {
            if (pending != null) {
                pending.cancel(true);
            }
            asked = question;
            pending = executor.submit(() -> {
                A value;
                try {
                    value = work.apply(question);
                } catch (RuntimeException failure) {
                    value = none;
                }
                if (Objects.equals(question, asked)) {
                    last.set(new Answer<>(question, value));
                }
            });
        }
        return answer.value();
    }

    public boolean isAnswered(Q question) {
        return Objects.equals(question, last.get().question());
    }
}
