package com.rost.cruma.core.world;

import com.rost.cruma.core.env.ResolvedEnv;
import com.rost.cruma.core.world.Entity.DistanceFog;
import glm_.vec3.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class L2FogInfo {
    private final Vec3 position;
    private final Range affectRange;
    private final List<Range> fogRanges;
    private final List<ColorKey> colorKeys;
    private final Vec3 boxInner;
    private final Vec3 boxOuter;
    private final List<Integer> sharedCloudIndices;
    private final ZoneRender zoneRender;
    private final float influenceScale;

    public L2FogInfo(Vec3 position, Range affectRange, List<Range> fogRanges, List<ColorKey> colorKeys) {
        this(position, affectRange, fogRanges, colorKeys, null, null, null);
    }

    public L2FogInfo(
            Vec3 position,
            Range affectRange,
            List<Range> fogRanges,
            List<ColorKey> colorKeys,
            Vec3 boxInner,
            Vec3 boxOuter,
            List<Integer> sharedCloudIndices) {
        this(position, affectRange, fogRanges, colorKeys, boxInner, boxOuter, sharedCloudIndices, null);
    }

    public L2FogInfo(
            Vec3 position,
            Range affectRange,
            List<Range> fogRanges,
            List<ColorKey> colorKeys,
            Vec3 boxInner,
            Vec3 boxOuter,
            List<Integer> sharedCloudIndices,
            ZoneRender zoneRender) {
        if (position == null
                || affectRange == null
                || fogRanges == null
                || fogRanges.isEmpty()
                || colorKeys == null
                || colorKeys.isEmpty()) {
            throw new IllegalArgumentException("L2FogInfo requires position, influence, fog and colour data");
        }
        this.position = new Vec3(position);
        this.affectRange = affectRange;
        this.fogRanges = List.copyOf(fogRanges);
        var orderedKeys = new ArrayList<>(colorKeys);
        orderedKeys.sort(Comparator.comparingDouble(ColorKey::time));
        this.colorKeys = List.copyOf(orderedKeys);
        this.boxInner = boxInner == null ? null : new Vec3(boxInner);
        this.boxOuter = boxOuter == null ? null : new Vec3(boxOuter);
        this.sharedCloudIndices = sharedCloudIndices == null ? null : List.copyOf(sharedCloudIndices);
        this.zoneRender = zoneRender == null ? ZoneRender.EVERY_STATE : zoneRender;
        this.influenceScale = 1f;
    }

    private L2FogInfo(L2FogInfo source, float influenceScale) {
        this.position = source.position;
        this.affectRange = source.affectRange;
        this.fogRanges = source.fogRanges;
        this.colorKeys = source.colorKeys;
        this.boxInner = source.boxInner;
        this.boxOuter = source.boxOuter;
        this.sharedCloudIndices = source.sharedCloudIndices;
        this.zoneRender = source.zoneRender;
        this.influenceScale = influenceScale;
    }

    public record Range(float start, float end) {
        public Range {
            if (!Float.isFinite(start) || !Float.isFinite(end) || end < start) {
                throw new IllegalArgumentException("invalid L2FogInfo range " + start + ".." + end);
            }
        }
    }

    public record ColorKey(float time, Vec3 fog, Vec3 sky, Vec3 cloud1, Vec3 cloud2, Vec3 cloud3, Vec3 haze) {
        public ColorKey {
            if (fog == null || sky == null || cloud1 == null || cloud2 == null || cloud3 == null || haze == null) {
                throw new IllegalArgumentException("L2FogInfo colour key is incomplete");
            }
        }
    }

    private record Colors(Vec3 fog, Vec3 sky, Vec3 cloud1, Vec3 cloud2, Vec3 cloud3, Vec3 haze) {}

    public static List<L2FogInfo> inZoneState(List<L2FogInfo> infos, ZoneStateChoice choice) {
        return inZoneState(infos, choice, null);
    }

    public static List<L2FogInfo> inZoneState(
            List<L2FogInfo> infos, ZoneStateChoice choice, @Nullable ZoneFades fades) {
        var follow = fades != null && fades.getChoice().equals(choice);
        List<L2FogInfo> kept = null;
        for (var index = 0; index < infos.size(); index++) {
            var info = infos.get(index);
            var used = info;
            var fade = !follow || info.zoneRender.blend() == null ? null : fades.find(info.zoneRender);
            if (fade == null) {
                used = info.zoneRender.shows(choice) ? info : null;
            } else if (!fade.isDrawn(info.zoneRender.mask())) {
                used = null;
            } else {
                var factor = fade.getFogFactor(info.zoneRender.mask());
                used = factor == 1f ? info : new L2FogInfo(info, factor);
            }
            if (used == info) {
                if (kept != null) {
                    kept.add(info);
                }
                continue;
            }
            if (kept == null) {
                kept = new ArrayList<>(infos.subList(0, index));
            }
            if (used != null) {
                kept.add(used);
            }
        }
        return kept == null ? infos : kept;
    }

    public static DistanceFog blendFog(
            List<L2FogInfo> infos, Vec3 point, float hour, int rangePreset, DistanceFog base) {
        return blendFog(infos, point, hour, rangePreset, base, false);
    }

    public static DistanceFog blendFogOverExtendedView(
            List<L2FogInfo> infos, Vec3 point, float hour, int rangePreset, DistanceFog base) {
        return blendFog(infos, point, hour, rangePreset, base, true);
    }

    public static ResolvedEnv blendSky(List<L2FogInfo> infos, Vec3 point, float hour, ResolvedEnv base) {
        var result = base;
        for (var info : infos) {
            var influence = info.getInfluenceAt(point);
            if (influence <= 0f) {
                continue;
            }
            var colors = info.colorsAt(hour);
            if (info.sharedCloudIndices != null) {
                var clouds = new Vec3[3 + result.moreClouds().length];
                for (var index = 0; index < clouds.length; index++) {
                    clouds[index] = result.cloud(index);
                    if (clouds[index] != null && info.sharedCloudIndices.contains(index)) {
                        clouds[index] = lerp(clouds[index], colors.cloud1(), influence);
                    }
                }
                result = result.withSkyClouds(
                        lerp(result.skyBox(), colors.sky(), influence),
                        lerp(result.haze(), colors.haze(), influence),
                        clouds);
                continue;
            }
            result = result.withSky(
                    lerp(result.skyBox(), colors.sky(), influence),
                    lerp(result.haze(), colors.haze(), influence),
                    lerp(result.cloud1(), colors.cloud1(), influence),
                    lerp(result.cloud2(), colors.cloud2(), influence),
                    lerp(result.cloud3(), colors.cloud3(), influence));
        }
        return result;
    }

    public Vec3 getPosition() {
        return new Vec3(position);
    }

    public Range getAffectRange() {
        return affectRange;
    }

    public List<Range> getFogRanges() {
        return fogRanges;
    }

    public List<ColorKey> getColorKeys() {
        return colorKeys;
    }

    public ZoneRender getZoneRender() {
        return zoneRender;
    }

    public float getInfluenceAt(Vec3 point) {
        var influence = getOwnInfluenceAt(point);
        return influenceScale == 1f ? influence : influence * influenceScale;
    }

    private float getOwnInfluenceAt(Vec3 point) {
        var dx = point.getX() - position.getX();
        var dy = point.getY() - position.getY();
        var dz = point.getZ() - position.getZ();
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || !Float.isFinite(dz)) {
            return 0f;
        }
        if (boxInner != null && boxOuter != null) {
            return Math.clamp(
                    Math.min(
                            boxAxis(dx, boxInner.getX(), boxOuter.getX()),
                            Math.min(
                                    boxAxis(dy, boxInner.getY(), boxOuter.getY()),
                                    boxAxis(dz, boxInner.getZ(), boxOuter.getZ()))),
                    0f,
                    1f);
        }
        var distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= affectRange.end()) {
            return 0f;
        }
        if (distance <= affectRange.start() || affectRange.end() == affectRange.start()) {
            return 1f;
        }
        return (affectRange.end() - distance) / (affectRange.end() - affectRange.start());
    }

    private Colors colorsAt(float hour) {
        if (colorKeys.size() == 1) {
            return toColors(colorKeys.getFirst());
        }
        var current = colorKeys.getLast();
        var next = current;
        for (var index = 0; index < colorKeys.size() - 1; index++) {
            var candidate = colorKeys.get(index);
            var candidateNext = colorKeys.get(index + 1);
            if (hour >= candidate.time() && hour < candidateNext.time()) {
                current = candidate;
                next = candidateNext;
                break;
            }
        }
        if (current == next || next.time() == current.time()) {
            return toColors(current);
        }
        var alpha = (hour - current.time()) / (float) (next.time() - current.time());
        return new Colors(
                lerp(current.fog(), next.fog(), alpha),
                lerp(current.sky(), next.sky(), alpha),
                lerp(current.cloud1(), next.cloud1(), alpha),
                lerp(current.cloud2(), next.cloud2(), alpha),
                lerp(current.cloud3(), next.cloud3(), alpha),
                lerp(current.haze(), next.haze(), alpha));
    }

    private static float boxAxis(float delta, float inner, float outer) {
        if (!Float.isFinite(inner) || !Float.isFinite(outer) || outer < inner) {
            return 0f;
        }
        var distance = Math.abs(delta);
        if (distance >= outer) {
            return 0f;
        }
        if (distance <= inner) {
            return 1f;
        }
        return (outer - distance) / (outer - inner);
    }

    private static DistanceFog blendFog(
            List<L2FogInfo> infos, Vec3 point, float hour, int rangePreset, DistanceFog base, boolean densityRanges) {
        var result = base;
        for (var info : infos) {
            var influence = info.getInfluenceAt(point);
            if (influence <= 0f) {
                continue;
            }
            var range = info.fogRanges.get(Math.max(0, Math.min(rangePreset - 1, info.fogRanges.size() - 1)));
            var colors = info.colorsAt(hour);
            if (result == null) {
                result = new DistanceFog(colors.fog(), range.start(), range.end());
                continue;
            }
            var blendedRange = densityRanges
                    ? densityRange(result.start(), result.end(), range.start(), range.end(), influence)
                    : new Range(
                            lerp(result.start(), range.start(), influence), lerp(result.end(), range.end(), influence));
            result = new DistanceFog(
                    lerp(result.color(), colors.fog(), influence), blendedRange.start(), blendedRange.end());
        }
        return result;
    }

    private static Range densityRange(
            float baseStart, float baseEnd, float targetStart, float targetEnd, float influence) {
        if (influence <= 0f) {
            return new Range(baseStart, baseEnd);
        }
        if (influence >= 1f) {
            return new Range(targetStart, targetEnd);
        }
        if (baseEnd <= 0f || targetEnd <= 0f) {
            return new Range(lerp(baseStart, targetStart, influence), lerp(baseEnd, targetEnd, influence));
        }
        var end = 1f / (lerp(1f / baseEnd, 1f / targetEnd, influence));
        var baseOnset = Math.clamp(baseStart / baseEnd, 0f, 1f);
        var targetOnset = Math.clamp(targetStart / targetEnd, 0f, 1f);
        var start = end * lerp(baseOnset, targetOnset, influence);
        return new Range(start, end);
    }

    private static Colors toColors(ColorKey key) {
        return new Colors(key.fog(), key.sky(), key.cloud1(), key.cloud2(), key.cloud3(), key.haze());
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, float alpha) {
        return new Vec3(
                lerp(a.getX(), b.getX(), alpha), lerp(a.getY(), b.getY(), alpha), lerp(a.getZ(), b.getZ(), alpha));
    }

    private static float lerp(float a, float b, float alpha) {
        return a + (b - a) * alpha;
    }
}
