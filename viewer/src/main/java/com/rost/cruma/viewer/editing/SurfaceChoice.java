package com.rost.cruma.viewer.editing;

import com.rost.cruma.viewer.editing.EditingSession.SurfaceRef;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SurfaceChoice {
    private final ArrayList<SurfaceRef> surfaces = new ArrayList<>();
    private final Set<SurfaceRef> members = new HashSet<>();
    private List<SurfaceRef> shown = List.of();
    private long revision;

    List<SurfaceRef> getList() {
        return shown;
    }

    long getRevision() {
        return revision;
    }

    boolean contains(SurfaceRef surface) {
        return members.contains(surface);
    }

    boolean isEmpty() {
        return surfaces.isEmpty();
    }

    int size() {
        return surfaces.size();
    }

    SurfaceRef getFirst() {
        return surfaces.getFirst();
    }

    void clear() {
        if (!surfaces.isEmpty()) {
            surfaces.clear();
            members.clear();
            update();
        }
    }

    void set(List<SurfaceRef> choice) {
        surfaces.clear();
        members.clear();
        for (var surface : choice) {
            if (members.add(surface)) {
                surfaces.add(surface);
            }
        }
        update();
    }

    boolean addFirst(SurfaceRef surface) {
        if (!members.add(surface)) {
            return false;
        }
        surfaces.addFirst(surface);
        update();
        return true;
    }

    boolean remove(SurfaceRef surface) {
        if (!members.remove(surface)) {
            return false;
        }
        surfaces.remove(surface);
        update();
        return true;
    }

    private void update() {
        shown = Collections.unmodifiableList(new ArrayList<>(surfaces));
        revision++;
    }
}
