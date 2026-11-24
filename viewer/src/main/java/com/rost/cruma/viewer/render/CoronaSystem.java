package com.rost.cruma.viewer.render;

import com.rost.cruma.core.geometry.Frustum;
import com.rost.cruma.core.unreal.primitives.URotator;
import com.rost.cruma.core.world.CoronaRules;
import com.rost.cruma.core.world.LightCorona;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import org.jetbrains.annotations.Nullable;

public final class CoronaSystem {
    private static final float CELL = 8192f;
    private static final double LOST_SECONDS = 2.0;

    private final Map<String, List<LightCorona>> byMap = new LinkedHashMap<>();
    private final Map<Long, List<Placed>> grid = new HashMap<>();
    private final Slot[] slots = new Slot[CoronaRules.MOST_CORONAS];
    private final Map<String, LineTest> lineTests = new HashMap<>();
    private final Map<String, Double> askedAt = new HashMap<>();
    private final List<LightCorona> wanted = new ArrayList<>();
    private final List<LightCorona> gathered = new ArrayList<>();
    private final List<Placed> carried = new ArrayList<>();
    private Edits edits = Edits.NONE;
    private @Nullable Carriers carriers;
    private long carriersLayout = -1L;
    private long carriersMotion = -1L;
    private boolean gridDirty;
    private double lastTime = Double.NaN;
    private long random = 0x9E3779B97F4A7C15L;
    private long revision;

    public record Edits(Map<String, Moved> moved, Set<String> gone, Map<String, Copied> copies) {
        public static final Edits NONE = new Edits(Map.of(), Set.of(), Map.of());

        public Edits {
            moved = Map.copyOf(moved);
            gone = Set.copyOf(gone);
            copies = Map.copyOf(copies);
        }

        public Edits(Map<String, Moved> moved, Set<String> gone) {
            this(moved, gone, Map.of());
        }
    }

    public record Copied(String template, Vec3 location, URotator rotation) {}

    public record Moved(
            Vec3 location,
            int hue,
            int saturation,
            float radius,
            @Nullable URotator rotation) {
        public Moved(Vec3 location, int hue, int saturation, float radius) {
            this(location, hue, saturation, radius, null);
        }
    }

    public interface Carriers {
        boolean carries(String actorPath);

        LightCorona place(LightCorona corona);

        long getLayout();

        long getMotion();
    }

    public record View(Vec3 eye, Vec3 look, Vec3 right, Vec3 up, Frustum frustum, float fovDegrees) {}

    public record Drawn(LightCorona corona, float red, float green, float blue, float[] corners) {}

    private record LineTest(boolean clear, double time, double keepSeconds) {}

    private static final class Placed {
        private final String map;
        private final LightCorona base;
        private LightCorona corona;

        private Placed(String map, LightCorona base) {
            this.map = map;
            this.base = base;
            corona = base;
        }
    }

    private static final class Slot {
        private LightCorona corona;
        private float brightness;
    }

    static Drawn quad(LightCorona corona, float brightness, View view) {
        var place = corona.location();
        var distance = (float) Math.sqrt(distanceSquared(place, view.eye()));
        var factor = CoronaRules.distanceFactor(distance);
        var color = CoronaRules.color(corona.hue(), corona.saturation());
        var strength = brightness;
        Vec3 across;
        Vec3 upward;
        if (CoronaRules.isFlat(corona.drawScale3d())) {
            var axes = axes(corona);
            across = axes[0];
            upward = axes[1];
            strength *= CoronaRules.flatFactor(across, upward, view.look());
        } else {
            across = view.right();
            upward = view.up();
        }
        var turn = CoronaRules.rotation(corona.rotationRate(), corona.rotationOffset(), factor);
        if (turn != 0) {
            across = CoronaRules.rotateAngleAxis(across, turn, view.look());
            upward = CoronaRules.rotateAngleAxis(upward, turn, view.look());
        }
        var scale3d = corona.drawScale3d();
        var halfWidth =
                CoronaRules.halfSize(corona.drawScale(), scale3d.getX(), factor, corona.minSize(), corona.maxSize());
        var halfHeight =
                CoronaRules.halfSize(corona.drawScale(), scale3d.getY(), factor, corona.minSize(), corona.maxSize());
        var corners = new float[12];
        corner(corners, 0, place, across, upward, -halfWidth, halfHeight);
        corner(corners, 1, place, across, upward, halfWidth, halfHeight);
        corner(corners, 2, place, across, upward, halfWidth, -halfHeight);
        corner(corners, 3, place, across, upward, -halfWidth, -halfHeight);
        return new Drawn(
                corona, toByte(color[0] * strength), toByte(color[1] * strength), toByte(color[2] * strength), corners);
    }

    public void add(String map, List<LightCorona> coronas) {
        if (coronas == null || coronas.isEmpty()) {
            remove(map);
            return;
        }
        byMap.put(map, List.copyOf(coronas));
        gridDirty = true;
        revision++;
    }

    public void remove(String map) {
        if (byMap.remove(map) != null) {
            gridDirty = true;
            revision++;
        }
    }

    public void clear() {
        byMap.clear();
        grid.clear();
        lineTests.clear();
        askedAt.clear();
        for (var index = 0; index < slots.length; index++) {
            slots[index] = null;
        }
        revision++;
    }

    public boolean isEmpty() {
        return byMap.isEmpty();
    }

    public int getCount() {
        var count = 0;
        for (var coronas : byMap.values()) {
            count += coronas.size();
        }
        return count;
    }

    public long getRevision() {
        return revision;
    }

    public void setCarriers(@Nullable Carriers next) {
        if (next == carriers) {
            return;
        }
        carriers = next;
        carriersLayout = -1L;
        carriersMotion = -1L;
        gridDirty = true;
        revision++;
    }

    public void setEdits(Edits next) {
        var value = next == null ? Edits.NONE : next;
        if (value.equals(edits)) {
            return;
        }
        edits = value;
        gridDirty = true;
        revision++;
    }

    public List<Drawn> update(double now, @Nullable View view, BiPredicate<String, Vec3> zones) {
        var carrying = carriers;
        if (carrying != null && carrying.getLayout() != carriersLayout) {
            carriersLayout = carrying.getLayout();
            gridDirty = true;
        }
        if (gridDirty) {
            rebuildGrid();
            carriersMotion = -1L;
        }
        if (carrying != null && carrying.getMotion() != carriersMotion) {
            carriersMotion = carrying.getMotion();
            for (var placed : carried) {
                placed.corona = carrying.place(placed.base);
            }
        }
        var step = Double.isNaN(lastTime) ? 1f : CoronaRules.fadeStep((float) (now - lastTime));
        lastTime = now;
        for (var index = 0; index < slots.length; index++) {
            var slot = slots[index];
            if (slot == null) {
                continue;
            }
            slot.brightness = CoronaRules.dim(slot.brightness, slot.corona.scaleGlow(), step);
            if (slot.brightness < 0f) {
                slots[index] = null;
            }
        }
        wanted.clear();
        if (view == null) {
            return collectDrawn(null);
        }
        gather(view, zones);
        for (var corona : gathered) {
            var test = lineTests.get(corona.actorPath());
            if (test == null || now - test.time() >= test.keepSeconds()) {
                var asked = askedAt.get(corona.actorPath());
                if (asked == null || now - asked > LOST_SECONDS) {
                    wanted.add(corona);
                }
            }
            if (test == null || !test.clear()) {
                continue;
            }
            var slot = find(corona.actorPath());
            if (slot != null) {
                slot.corona = corona;
                slot.brightness = CoronaRules.brighten(slot.brightness, corona.scaleGlow(), step);
            } else {
                var free = freeSlot();
                if (free >= 0) {
                    var created = new Slot();
                    created.corona = corona;
                    created.brightness = CoronaRules.brighten(0f, corona.scaleGlow(), step);
                    slots[free] = created;
                }
            }
        }
        return collectDrawn(view);
    }

    public List<LightCorona> takeLineTests() {
        if (wanted.isEmpty()) {
            return List.of();
        }
        var taken = List.copyOf(wanted);
        wanted.clear();
        var now = lastTime;
        for (var corona : taken) {
            askedAt.put(corona.actorPath(), now);
        }
        return taken;
    }

    public boolean wantsLineTests() {
        return !wanted.isEmpty();
    }

    public void acceptLineTest(String actorPath, boolean clear) {
        askedAt.remove(actorPath);
        var time = Double.isNaN(lastTime) ? 0.0 : lastTime;
        lineTests.put(actorPath, new LineTest(clear, time, CoronaRules.recheckSeconds(nextRandom())));
    }

    public boolean hasLineTest(String actorPath) {
        return lineTests.containsKey(actorPath);
    }

    public void dropLineTest(String actorPath) {
        askedAt.remove(actorPath);
    }

    private void gather(View view, BiPredicate<String, Vec3> zones) {
        gathered.clear();
        var eye = view.eye();
        var cellX = cell(eye.getX());
        var cellY = cell(eye.getY());
        for (var x = cellX - 1; x <= cellX + 1; x++) {
            for (var y = cellY - 1; y <= cellY + 1; y++) {
                var lights = grid.get(key(x, y));
                if (lights == null) {
                    continue;
                }
                for (var placed : lights) {
                    if (admits(placed, view, zones)) {
                        gathered.add(placed.corona);
                    }
                }
            }
        }
        if (gathered.size() > CoronaRules.MOST_CORONAS) {
            gathered.sort(Comparator.comparingDouble(corona -> distanceSquared(corona.location(), eye)));
            gathered.subList(CoronaRules.MOST_CORONAS, gathered.size()).clear();
        }
    }

    private List<Drawn> collectDrawn(@Nullable View view) {
        if (view == null) {
            return List.of();
        }
        var result = new ArrayList<Drawn>();
        for (var slot : slots) {
            if (slot == null || slot.brightness <= 0f) {
                continue;
            }
            result.add(quad(slot.corona, slot.brightness, view));
        }
        return result;
    }

    private void rebuildGrid() {
        gridDirty = false;
        grid.clear();
        carried.clear();
        var present = new HashSet<String>();
        for (var entry : byMap.entrySet()) {
            for (var corona : entry.getValue()) {
                if (edits.gone().contains(corona.actorPath())) {
                    continue;
                }
                var moved = edits.moved().get(corona.actorPath());
                var placed = moved == null
                        ? corona
                        : corona.movedTo(moved.location(), moved.hue(), moved.saturation(), moved.radius());
                if (moved != null && moved.rotation() != null) {
                    placed = placed.carriedTo(placed.location(), moved.rotation());
                }
                present.add(placed.actorPath());
                var place = placed.location();
                var cellOf = new Placed(entry.getKey(), placed);
                grid.computeIfAbsent(key(cell(place.getX()), cell(place.getY())), ignored -> new ArrayList<>())
                        .add(cellOf);
                if (carriers != null && carriers.carries(placed.actorPath())) {
                    carried.add(cellOf);
                }
            }
        }
        for (var copy : edits.copies().entrySet()) {
            for (var entry : byMap.entrySet()) {
                for (var corona : entry.getValue()) {
                    if (!corona.actorPath().equals(copy.getValue().template())
                            || edits.gone().contains(copy.getKey())) {
                        continue;
                    }
                    var placed = corona.withActorPath(copy.getKey())
                            .carriedTo(
                                    copy.getValue().location(), copy.getValue().rotation());
                    present.add(placed.actorPath());
                    var place = placed.location();
                    var cellOf = new Placed(entry.getKey(), placed);
                    grid.computeIfAbsent(key(cell(place.getX()), cell(place.getY())), ignored -> new ArrayList<>())
                            .add(cellOf);
                    if (carriers != null && carriers.carries(placed.actorPath())) {
                        carried.add(cellOf);
                    }
                }
            }
        }
        for (var index = 0; index < slots.length; index++) {
            var slot = slots[index];
            if (slot != null && !present.contains(slot.corona.actorPath())) {
                slots[index] = null;
            }
        }
        lineTests
                .keySet()
                .removeIf(path -> !present.contains(path) || edits.moved().containsKey(path));
        askedAt.keySet().removeIf(path -> !present.contains(path));
    }

    private @Nullable Slot find(String actorPath) {
        for (var slot : slots) {
            if (slot != null && slot.corona.actorPath().equals(actorPath)) {
                return slot;
            }
        }
        return null;
    }

    private int freeSlot() {
        for (var index = 0; index < slots.length; index++) {
            if (slots[index] == null) {
                return index;
            }
        }
        return -1;
    }

    private float nextRandom() {
        random ^= random << 13;
        random ^= random >>> 7;
        random ^= random << 17;
        return (random >>> 40) / (float) (1L << 24);
    }

    private static boolean admits(Placed placed, View view, BiPredicate<String, Vec3> zones) {
        var corona = placed.corona;
        var place = corona.location();
        var distanceSquared = distanceSquared(place, view.eye());
        var reach = corona.reach();
        if (!(distanceSquared < reach * reach)) {
            return false;
        }
        if (!CoronaRules.passesCullDistance((float) distanceSquared, corona.cullDistance(), view.fovDegrees())) {
            return false;
        }
        var x = place.getX();
        var y = place.getY();
        var z = place.getZ();
        if (!view.frustum().intersects(x, y, z, x, y, z)) {
            return false;
        }
        return zones.test(placed.map, place);
    }

    private static Vec3[] axes(LightCorona corona) {
        var rotation = corona.rotation();
        var yaw = rotation.yaw() * (Math.PI * 2 / 65536);
        var pitch = rotation.pitch() * (Math.PI * 2 / 65536);
        var roll = rotation.roll() * (Math.PI * 2 / 65536);
        var cy = Math.cos(yaw);
        var sy = Math.sin(yaw);
        var cp = Math.cos(pitch);
        var sp = Math.sin(pitch);
        var cr = Math.cos(roll);
        var sr = Math.sin(roll);
        var axisX = new Vec3((float) (cp * cy), (float) (cp * sy), (float) sp);
        var axisY = new Vec3((float) (sr * sp * cy - cr * sy), (float) (sr * sp * sy + cr * cy), (float) (-sr * cp));
        return new Vec3[] {axisX, axisY};
    }

    private static void corner(float[] target, int index, Vec3 place, Vec3 across, Vec3 upward, float x, float y) {
        target[index * 3] = place.getX() + across.getX() * x + upward.getX() * y;
        target[index * 3 + 1] = place.getY() + across.getY() * x + upward.getY() * y;
        target[index * 3 + 2] = place.getZ() + across.getZ() * x + upward.getZ() * y;
    }

    private static float toByte(float value) {
        var level = (int) Math.floor(value * 255f);
        return Math.clamp(level, 0, 255) / 255f;
    }

    private static double distanceSquared(Vec3 a, Vec3 b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int cell(float coordinate) {
        return (int) Math.floor(coordinate / CELL);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }
}
