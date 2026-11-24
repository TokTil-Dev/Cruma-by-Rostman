package com.rost.cruma.viewer.ui;

import com.rost.cruma.viewer.render.CameraView;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

final class IsolateFraming {
    enum Move {
        NONE,
        FRAME,
        RESTORE
    }

    private @Nullable CameraView view;
    private @Nullable Object target;
    private boolean moved;

    boolean isMoved() {
        return moved;
    }

    Move next(CameraView currentView, @Nullable Object currentTarget) {
        var viewChanged = currentView != view;
        var targetChanged = !isSameObject(currentTarget, target);
        view = currentView;
        target = currentTarget;
        if (currentView.isOrtho()) {
            if (currentTarget != null && (viewChanged || targetChanged)) {
                moved = true;
                return Move.FRAME;
            }
            return Move.NONE;
        }
        if (viewChanged && moved) {
            moved = false;
            return Move.RESTORE;
        }
        return Move.NONE;
    }

    private static boolean isSameObject(@Nullable Object now, @Nullable Object before) {
        return Objects.equals(now, before)
                || now instanceof Set<?> nowActors
                        && before instanceof Set<?> beforeActors
                        && !Collections.disjoint(nowActors, beforeActors);
    }
}
