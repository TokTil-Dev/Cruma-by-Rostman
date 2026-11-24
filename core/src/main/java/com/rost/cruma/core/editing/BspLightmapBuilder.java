package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BspLightingScene.Light;
import com.rost.cruma.core.editing.BspLightmapTail.Entry;
import com.rost.cruma.core.editing.BspLightmapTail.Lightmap;
import com.rost.cruma.core.editing.NativeBspLightmapTexels.Lit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.Nullable;

final class BspLightmapBuilder {
    static final int ALL_VARIANTS = -1;

    private BspLightmapBuilder() {}

    record Rebuilt(
            int index,
            @Nullable Lightmap replacement,
            int[][] deltas,
            int rebuiltLights,
            int entriesBefore,
            int entriesAfter,
            long changedTexels) {
        boolean changed() {
            return replacement != null || changedTexels > 0;
        }
    }

    private record Tagged(Entry entry, int variant, int light) {}

    @FunctionalInterface
    interface Mask {
        boolean @Nullable [] getTexels(Lightmap lightmap, Light light, int variant, Entry rect);
    }

    static Rebuilt rebuild(BspLightingScene scene, int index, IntPredicate affected) {
        return rebuild(scene, index, affected, (lightmap, light, variant, rect) -> null);
    }

    static Rebuilt rebuild(BspLightingScene scene, int index, IntPredicate affected, Mask mask) {
        return rebuild(scene, index, affected, mask, Map.of());
    }

    static Rebuilt rebuild(
            BspLightingScene scene,
            int index,
            IntPredicate affected,
            Mask mask,
            Map<Integer, NativeBspLightmapTexels.Light> recolored) {
        var lightmap = scene.tail.lightmaps.get(index);
        var variants = scene.getVariants(lightmap);
        var sunZone = scene.isSunZone(lightmap);
        var groups = BspLightingScene.groupByLight(lightmap);
        var before = new ArrayList<Tagged>();
        var after = new ArrayList<Tagged>();
        var handled = new HashSet<Integer>();
        var rebuiltLights = 0;
        var relit = false;
        for (var group : groups) {
            var light = scene.lightsByReference.getOrDefault(group.getFirst().light(), -2);
            if (light < 0) {
                for (var entry : group) {
                    before.add(new Tagged(entry, ALL_VARIANTS, -1));
                    after.add(new Tagged(entry, ALL_VARIANTS, -1));
                }
                continue;
            }
            handled.add(light);
            var target = scene.movedTo.get(light);
            if (target != null) {
                handled.add(target);
                rebuiltLights++;
                relit = true;
                var tags = savedTags(group, sunZone, variants, null);
                for (var i = 0; i < group.size(); i++) {
                    before.add(new Tagged(group.get(i), tags[i], light));
                }
                if (Arrays.stream(scene.candidates[lightmap.surface()]).anyMatch(candidate -> candidate == target)) {
                    var slots = computeSlots(scene, lightmap, target, sunZone, variants);
                    after.addAll(mergeEntries(
                            scene,
                            lightmap,
                            target,
                            slots,
                            new Entry[slots.size()],
                            (map, value, variant, rect) -> null));
                }
                continue;
            }
            if (!affected.test(light)) {
                var tags = savedTags(group, sunZone, variants, null);
                for (var i = 0; i < group.size(); i++) {
                    before.add(new Tagged(group.get(i), tags[i], light));
                    after.add(new Tagged(group.get(i), tags[i], light));
                }
                continue;
            }
            rebuiltLights++;
            var slots = computeSlots(scene, lightmap, light, sunZone, variants);
            var computed = new ArrayList<Tagged>();
            for (var tagged : slots) {
                if (!tagged.entry().empty()) {
                    computed.add(tagged);
                }
            }
            var tags = savedTags(group, sunZone, variants, computed);
            var saved = new Entry[sunZone ? variants : 1];
            for (var i = 0; i < group.size(); i++) {
                before.add(new Tagged(group.get(i), tags[i], light));
                var slot = tags[i] == ALL_VARIANTS ? 0 : tags[i];
                if (slot < saved.length && saved[slot] == null) {
                    saved[slot] = group.get(i);
                }
            }
            after.addAll(mergeEntries(scene, lightmap, light, slots, saved, mask));
        }
        for (var light : scene.candidates[lightmap.surface()]) {
            var moved = scene.placed.contains(light);
            if (handled.contains(light) || !moved && !affected.test(light)) {
                continue;
            }
            rebuiltLights++;
            relit |= moved;
            var slots = computeSlots(scene, lightmap, light, sunZone, variants);
            after.addAll(mergeEntries(
                    scene,
                    lightmap,
                    light,
                    slots,
                    new Entry[slots.size()],
                    moved ? (map, value, variant, rect) -> null : mask));
        }
        var entriesAfter = new ArrayList<Entry>(after.size());
        for (var tagged : after) {
            entriesAfter.add(tagged.entry());
        }
        var replacement = entriesAfter.equals(lightmap.entries()) ? null : lightmap.withEntries(entriesAfter);
        var deltas = new int[variants][];
        long changed = 0;
        var recoloredLights = new HashSet<Integer>();
        for (var tagged : after) {
            if (recolored.containsKey(tagged.light())) {
                recoloredLights.add(tagged.light());
            }
        }
        rebuiltLights += recoloredLights.size();
        if (replacement != null || relit || !recoloredLights.isEmpty()) {
            var surface = scene.surfaces[lightmap.surface()].texelSurface();
            for (var v = 0; v < variants; v++) {
                var base = scene.getBase(lightmap, v);
                var old = NativeBspLightmapTexels.computeTexels(
                        lightmap, surface, base, collectLit(scene, before, v, Map.of()));
                var now = NativeBspLightmapTexels.computeTexels(
                        lightmap, surface, base, collectLit(scene, after, v, recolored));
                if (Arrays.equals(old, now)) {
                    continue;
                }
                var delta = new int[lightmap.sizeX() * lightmap.sizeY() * 3];
                for (var t = 0; t < lightmap.sizeX() * lightmap.sizeY(); t++) {
                    for (var c = 0; c < 3; c++) {
                        var d = (now[t * 4 + 2 - c] & 0xff) - (old[t * 4 + 2 - c] & 0xff);
                        delta[t * 3 + c] = d;
                        if (d != 0) {
                            changed++;
                        }
                    }
                }
                deltas[v] = delta;
            }
        }
        return new Rebuilt(
                index, replacement, deltas, rebuiltLights, lightmap.entries().size(), entriesAfter.size(), changed);
    }

    static byte[][] predictTexels(BspLightingScene scene, Lightmap lightmap, List<Entry> entries) {
        var variants = scene.getVariants(lightmap);
        var sunZone = scene.isSunZone(lightmap);
        var tagged = new ArrayList<Tagged>();
        var groups = new ArrayList<List<Entry>>();
        for (var entry : entries) {
            if (groups.isEmpty() || groups.getLast().getFirst().light() != entry.light()) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(entry);
        }
        for (var group : groups) {
            var light = scene.lightsByReference.getOrDefault(group.getFirst().light(), -2);
            var tags = savedTags(group, sunZone, variants, null);
            for (var i = 0; i < group.size(); i++) {
                tagged.add(new Tagged(group.get(i), tags[i], light));
            }
        }
        var surface = scene.surfaces[lightmap.surface()].texelSurface();
        var result = new byte[variants][];
        for (var v = 0; v < variants; v++) {
            result[v] = NativeBspLightmapTexels.computeTexels(
                    lightmap, surface, scene.getBase(lightmap, v), collectLit(scene, tagged, v, Map.of()));
        }
        return result;
    }

    private static List<Tagged> mergeEntries(
            BspLightingScene scene, Lightmap lightmap, int lightIndex, List<Tagged> slots, Entry[] saved, Mask mask) {
        var light = scene.lights.get(lightIndex);
        var result = new ArrayList<Tagged>();
        for (var i = 0; i < slots.size(); i++) {
            var computed = slots.get(i).entry();
            var flags = mask.getTexels(lightmap, light, Math.max(0, slots.get(i).variant()), computed);
            var entry = computed;
            if (flags != null) {
                var previous = i < saved.length ? saved[i] : null;
                var bitmap = new byte[computed.bitmap().length];
                if (previous != null && isSameRect(previous, computed)) {
                    System.arraycopy(
                            previous.bitmap(), 0, bitmap, 0, Math.min(bitmap.length, previous.bitmap().length));
                }
                for (var y = 0; y < computed.sizeY(); y++) {
                    for (var x = 0; x < computed.sizeX(); x++) {
                        if (!flags[y * computed.sizeX() + x]) {
                            continue;
                        }
                        var at = y * computed.stride() + (x >> 3);
                        var bit = 1 << (x & 7);
                        bitmap[at] = (byte) (computed.bit(x, y) ? bitmap[at] | bit : bitmap[at] & ~bit);
                    }
                }
                entry = new Entry(
                        computed.light(),
                        bitmap,
                        computed.sizeX(),
                        computed.sizeY(),
                        computed.stride(),
                        computed.minX(),
                        computed.minY(),
                        computed.maxX(),
                        computed.maxY());
                if (previous != null && entry.equals(previous)) {
                    entry = previous;
                }
            }
            if (!entry.empty()) {
                result.add(new Tagged(entry, slots.get(i).variant(), lightIndex));
            }
        }
        return result;
    }

    private static boolean isSameRect(Entry a, Entry b) {
        return a.minX() == b.minX()
                && a.minY() == b.minY()
                && a.maxX() == b.maxX()
                && a.maxY() == b.maxY()
                && a.stride() == b.stride()
                && a.sizeY() == b.sizeY();
    }

    private static List<Tagged> computeSlots(
            BspLightingScene scene, Lightmap lightmap, int lightIndex, boolean sunZone, int variants) {
        var light = scene.lights.get(lightIndex);
        var result = new ArrayList<Tagged>();
        var count = sunZone ? variants : 1;
        Entry first = null;
        for (var v = 0; v < count; v++) {
            Entry entry;
            if (!light.sun() && first != null) {
                entry = first;
            } else {
                entry = scene.getEntry(lightmap, light, v, null);
                first = entry;
            }
            result.add(new Tagged(entry, sunZone ? v : ALL_VARIANTS, lightIndex));
        }
        return result;
    }

    private static int[] savedTags(List<Entry> group, boolean sunZone, int variants, @Nullable List<Tagged> computed) {
        var tags = new int[group.size()];
        if (!sunZone) {
            Arrays.fill(tags, ALL_VARIANTS);
            return tags;
        }
        if (group.size() == variants) {
            for (var i = 0; i < tags.length; i++) {
                tags[i] = i;
            }
            return tags;
        }
        if (group.size() > variants || computed == null) {
            for (var i = 0; i < tags.length; i++) {
                tags[i] = i % variants;
            }
            return tags;
        }
        var slots = new Entry[variants];
        for (var tagged : computed) {
            slots[tagged.variant()] = tagged.entry();
        }
        var n = group.size();
        var score = new long[n + 1][variants + 1];
        for (var row : score) {
            Arrays.fill(row, Long.MIN_VALUE / 4);
        }
        for (var v = 0; v <= variants; v++) {
            score[0][v] = 0;
        }
        for (var i = 1; i <= n; i++) {
            for (var v = i; v <= variants; v++) {
                var skip = score[i][v - 1];
                var take = score[i - 1][v - 1] + countAgreement(group.get(i - 1), slots[v - 1]);
                score[i][v] = Math.max(skip, take);
            }
        }
        var i = n;
        var v = variants;
        while (i > 0) {
            if (score[i][v] == score[i][v - 1] && v > i) {
                v--;
            } else {
                tags[i - 1] = v - 1;
                i--;
                v--;
            }
        }
        return tags;
    }

    private static long countAgreement(Entry saved, @Nullable Entry computed) {
        long same = 0;
        for (var y = 0; y < saved.sizeY(); y++) {
            for (var x = 0; x < saved.sizeX(); x++) {
                var bit = computed != null && computed.bit(x, y);
                same += saved.bit(x, y) == bit ? 1 : 0;
            }
        }
        return same;
    }

    private static List<Lit> collectLit(
            BspLightingScene scene,
            List<Tagged> entries,
            int variant,
            Map<Integer, NativeBspLightmapTexels.Light> recolored) {
        var result = new ArrayList<Lit>();
        for (var tagged : entries) {
            if (tagged.light() < 0 || tagged.variant() != ALL_VARIANTS && tagged.variant() != variant) {
                continue;
            }
            var light = recolored.get(tagged.light());
            result.add(new Lit(
                    tagged.entry(), light != null ? light : scene.getTexelLight(scene.lights.get(tagged.light()))));
        }
        return result;
    }
}
