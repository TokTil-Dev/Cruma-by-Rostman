package com.rost.cruma.viewer.diagnostics;

import com.rost.cruma.viewer.config.ConfigurationProblem;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;

public final class Failures {
    private Failures() {}

    public static String describe(String subject, Throwable failure, long heapLimitBytes) {
        if (isOutOfMemory(failure)) {
            return subject + " ran out of memory. Cruma can use up to " + formatGigabytes(heapLimitBytes)
                    + " on this computer. Load fewer tiles, close other programs, or set Settings > Graphics > Texture"
                    + " starting mip to 1.";
        }
        var problem = settingsProblem(failure);
        if (problem != null) {
            return problem.getMessage();
        }
        var root = rootCause(failure);
        var message = root.getMessage();
        return subject
                + " stopped because of an error: "
                + (message == null || message.isBlank() ? root.getClass().getSimpleName() : message);
    }

    public static boolean isOutOfMemory(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof OutOfMemoryError error && !isOutOfStackSpace(error)) {
                return true;
            }
        }
        return false;
    }

    static String formatGigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static boolean isOutOfStackSpace(OutOfMemoryError error) {
        var message = error.getMessage();
        return message != null && message.startsWith("Out of stack space");
    }

    private static ConfigurationProblem settingsProblem(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof ConfigurationProblem problem && problem.getMessage() != null) {
                return problem;
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var root = failure;
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        return root;
    }
}
