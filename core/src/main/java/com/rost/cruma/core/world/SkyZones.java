package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchiveLoader.USearchConfig;
import com.rost.cruma.core.unreal.UPackageLoader;
import glm_.vec3.Vec3;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

public final class SkyZones {
    private static final Map<Path, Boolean> declaredBy = new ConcurrentHashMap<>();

    private final Map<SkyZone, Run> runs = new IdentityHashMap<>();
    private final List<SkyZone> entered = new ArrayList<>();
    private View view = View.NONE;

    public record View(
            float hour,
            int timeEnv,
            boolean found,
            float sharedWeight,
            boolean showSun,
            @Nullable SkyZone timeZone) {
        public static final View NONE = new View(Float.NaN, -1, false, 1f, true, null);

        public boolean hasTime() {
            return timeZone != null;
        }
    }

    private static final class Run {
        float t;
        boolean inList;
        boolean entered;
        boolean active;
        boolean waiting;
    }

    public static boolean isDeclaredBy(Path clientRoot) {
        return declaredBy.computeIfAbsent(clientRoot, root -> {
            try {
                var packages = new UPackageLoader(root, List.of(new USearchConfig("System", "u")));
                try {
                    var engine = packages.loadPackageIfPresent("Engine");
                    if (engine.isEmpty()) {
                        return false;
                    }
                    for (var export : engine.get().getArchive().getExportMap()) {
                        if (export.getObjectName().equals("L2SkyLevelZoneInfo")
                                && "Class".equals(export.getClassName())) {
                            return true;
                        }
                    }
                    return false;
                } finally {
                    packages.clearPackages();
                }
            } catch (RuntimeException unreadable) {
                return false;
            }
        });
    }

    public static View settledAt(List<SkyZone> boxes, ZoneStateChoice choice, @Nullable ZoneFades fades, Vec3 point) {
        var zones = new SkyZones();
        return zones.update(boxes, choice, fades, point, 0f);
    }

    public synchronized View update(
            List<SkyZone> boxes, ZoneStateChoice choice, @Nullable ZoneFades fades, Vec3 point, float deltaSeconds) {
        var playing = deltaSeconds > 0f;
        for (var box : boxes) {
            var run = runs.get(box);
            var fresh = run == null;
            if (fresh) {
                run = new Run();
                runs.put(box, run);
            }
            tick(box, run, choice, fades, playing && !fresh ? deltaSeconds : 0f, playing && !fresh);
        }
        if (runs.size() > boxes.size()) {
            prune(boxes);
        }
        for (var box : boxes) {
            var run = runs.get(box);
            var inside = run.active && box.useTimeZone() && box.contains(point);
            if (inside && !run.entered) {
                run.entered = true;
                entered.add(box);
            } else if (!inside && run.entered) {
                run.entered = false;
                entered.remove(box);
            }
        }
        var timeZone = entered.isEmpty() ? null : entered.getLast();
        var found = false;
        var useDefault = false;
        var ratio = 0f;
        var showSun = true;
        for (var box : boxes) {
            if (!runs.get(box).inList || !box.contains(point)) {
                continue;
            }
            found = true;
            useDefault = box.useDefaultMap();
            ratio = Math.max(ratio, box.getWeight(point));
            showSun = useDefault || box.showSun();
        }
        view = new View(
                timeZone == null ? Float.NaN : Math.max(0f, timeZone.time()),
                timeZone == null ? -1 : timeZone.timeEnv(),
                found,
                found ? useDefault ? ratio : 1f - ratio : 1f,
                showSun,
                timeZone);
        return view;
    }

    public synchronized View getView() {
        return view;
    }

    public synchronized boolean isWaiting() {
        for (var run : runs.values()) {
            if (run.waiting) {
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        runs.clear();
        entered.clear();
        view = View.NONE;
    }

    private void prune(List<SkyZone> boxes) {
        var kept = new IdentityHashMap<SkyZone, Boolean>();
        for (var box : boxes) {
            kept.put(box, Boolean.TRUE);
        }
        runs.keySet().removeIf(box -> !kept.containsKey(box));
        entered.removeIf(box -> !kept.containsKey(box));
    }

    private static void tick(
            SkyZone box, Run run, ZoneStateChoice choice, @Nullable ZoneFades fades, float dt, boolean live) {
        run.waiting = false;
        if (!box.listed()) {
            run.active = true;
            run.inList = true;
            return;
        }
        var result = box.checkActive(choice, fades);
        if (result == ZoneFade.CURRENT) {
            if (live) {
                run.t += dt;
            } else {
                run.t = Math.max(run.t, box.zoneStateChangeTime());
            }
            run.active = run.t >= box.zoneStateChangeTime();
            run.waiting = !run.active;
        } else if (result == ZoneFade.INACTIVE) {
            if (run.t > 0f || !live) {
                run.t = 0f;
                run.inList = false;
            }
            run.active = false;
        } else {
            run.active = false;
        }
        if (run.active) {
            run.inList = true;
        }
    }
}
