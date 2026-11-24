package com.rost.cruma.core.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public final class ZoneFades {
    private final Map<String, Zone> zones = new HashMap<>();
    private final List<Zone> order = new ArrayList<>();
    private ZoneStateChoice choice = ZoneStateChoice.EMPTY;
    private long revision;

    private record Zone(ZoneFade fade, int zoneStart, String map) {}

    public synchronized @Nullable ZoneFade find(ZoneRender render) {
        var blend = render.blend();
        if (blend == null || render.zoneStart() == ZoneRender.NO_ZONE_STATES) {
            return null;
        }
        var zone = zones.get(blend.zone());
        if (zone == null) {
            var fade = new ZoneFade(blend, render.zoneStart());
            var state = stateOf(render.zoneStart(), render.map());
            if (state != render.zoneStart()) {
                fade.changeState(state);
                fade.settle();
            }
            fade.endStuckFade();
            zone = new Zone(fade, render.zoneStart(), render.map());
            zones.put(blend.zone(), zone);
            order.add(zone);
            revision++;
        }
        return zone.fade();
    }

    public synchronized ZoneStateChoice getChoice() {
        return choice;
    }

    public synchronized void choose(ZoneStateChoice choice, boolean fade) {
        if (choice.equals(this.choice)) {
            if (!fade) {
                settle();
            }
            return;
        }
        this.choice = choice;
        for (var zone : order) {
            var state = stateOf(zone.zoneStart(), zone.map());
            if (state != zone.fade().getCurZoneState()) {
                zone.fade().changeState(state);
            }
            if (!fade) {
                zone.fade().settle();
            }
            zone.fade().endStuckFade();
        }
        revision++;
    }

    public synchronized void settle() {
        for (var zone : order) {
            var fade = zone.fade();
            var before = fade.getRevision();
            if (fade.getBlendingState() != ZoneFade.IDLE) {
                fade.settle();
            }
            fade.endStuckFade();
            if (fade.getRevision() != before) {
                revision++;
            }
        }
    }

    public synchronized boolean advance(float deltaSeconds) {
        if (!(deltaSeconds > 0f)) {
            return false;
        }
        var changed = false;
        for (var index = 0; index < order.size(); index++) {
            var fade = order.get(index).fade();
            if (fade.isRunning()) {
                fade.update(deltaSeconds);
                changed = true;
            }
            if (fade.endStuckFade()) {
                changed = true;
            }
        }
        if (changed) {
            revision++;
        }
        return changed;
    }

    public synchronized boolean isRunning() {
        for (var index = 0; index < order.size(); index++) {
            if (order.get(index).fade().isRunning()) {
                return true;
            }
        }
        return false;
    }

    public synchronized long getRevision() {
        return revision;
    }

    private int stateOf(int zoneStart, String map) {
        if (zoneStart == ZoneRender.NO_ZONE_STATES) {
            return zoneStart;
        }
        var chosen = choice.getState(map, ZoneStateChoice.defaultOf(zoneStart, true));
        return ZoneStateChoice.isState(chosen) ? chosen : zoneStart;
    }
}
