package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UDecryptor.DecryptedBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class DecryptedPackages {
    private final long idleBytes;
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(256, 0.75f, true);
    private long idle;

    public DecryptedPackages(long idleBytes) {
        if (idleBytes < 0L) {
            throw new IllegalArgumentException("idle package bytes cannot be negative");
        }
        this.idleBytes = idleBytes;
    }

    public DecryptedBuffer open(Path path) {
        Entry entry;
        boolean reader = false;
        synchronized (this) {
            entry = entries.get(path);
            if (entry == null) {
                entry = new Entry(path);
                entries.put(path, entry);
                reader = true;
            }
            if (entry.users++ == 0 && entry.size > 0L) {
                idle -= entry.size;
            }
        }
        if (reader) {
            read(entry);
        }
        var bytes = await(entry);
        var source = entry;
        return bytes.shared(() -> release(source));
    }

    public synchronized void clearIdle() {
        for (var iterator = entries.values().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            if (entry.users == 0 && entry.size > 0L) {
                iterator.remove();
                idle -= entry.size;
                entry.bytes.join().close();
            }
        }
    }

    public synchronized long getIdleBytes() {
        return idle;
    }

    public synchronized long getHeldBytes() {
        var held = 0L;
        for (var entry : entries.values()) {
            held = Math.addExact(held, entry.size);
        }
        return held;
    }

    private void read(Entry entry) {
        try {
            var bytes = UDecryptor.decryptManaged(entry.path);
            synchronized (this) {
                entry.size = bytes.getBuffer().capacity();
            }
            entry.bytes.complete(bytes);
        } catch (Throwable failure) {
            synchronized (this) {
                entries.remove(entry.path, entry);
            }
            entry.bytes.completeExceptionally(failure);
        }
    }

    private DecryptedBuffer await(Entry entry) {
        try {
            return entry.bytes.get();
        } catch (InterruptedException interrupted) {
            release(entry);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while a package was read: " + entry.path, interrupted);
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new IllegalStateException("Cannot read client file " + entry.path, cause);
        }
    }

    private void release(Entry entry) {
        synchronized (this) {
            if (--entry.users > 0 || entry.size == 0L) {
                return;
            }
            idle += entry.size;
            for (var iterator = entries.values().iterator(); idle > idleBytes && iterator.hasNext(); ) {
                var oldest = iterator.next();
                if (oldest.users == 0 && oldest.size > 0L) {
                    iterator.remove();
                    idle -= oldest.size;
                    oldest.bytes.join().close();
                }
            }
        }
    }

    private static final class Entry {
        private final Path path;
        private final CompletableFuture<DecryptedBuffer> bytes = new CompletableFuture<>();
        private long size;
        private int users;

        private Entry(Path path) {
            this.path = path;
        }
    }
}
