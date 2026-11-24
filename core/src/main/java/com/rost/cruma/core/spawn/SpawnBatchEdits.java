package com.rost.cruma.core.spawn;

import com.rost.cruma.core.spawn.NpcPosDocument.State;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class SpawnBatchEdits {
    private static final Pattern TIME = Pattern.compile("(\\d+)(sec|min|hour|day)");

    private SpawnBatchEdits() {}

    public static State scaleRespawn(State from, Collection<Integer> makers, double factor, boolean random) {
        return editMakers(from, makers, maker -> {
            var npcs = new ArrayList<SpawnNpc>();
            var changed = false;
            for (var npc : maker.npcs()) {
                var respawn = scaledTime(npc.respawn(), factor);
                var spread = random ? scaledTime(npc.respawnRandom(), factor) : npc.respawnRandom();
                if (!respawn.equals(npc.respawn()) || !spread.equals(npc.respawnRandom())) {
                    npcs.add(npc.withRespawn(respawn, spread));
                    changed = true;
                } else {
                    npcs.add(npc);
                }
            }
            return changed ? maker.withNpcs(npcs) : maker;
        });
    }

    public static State scaleCounts(
            State from, Collection<Integer> makers, double factor, boolean totals, boolean maximum) {
        return editMakers(from, makers, maker -> {
            var result = maker;
            if (totals) {
                var npcs = new ArrayList<SpawnNpc>();
                var changed = false;
                for (var npc : maker.npcs()) {
                    var total = scaleValue(npc.total(), factor);
                    changed |= total != npc.total();
                    npcs.add(total == npc.total() ? npc : npc.withTotal(total));
                }
                if (changed) {
                    result = result.withNpcs(npcs);
                }
            }
            if (maximum && maker.maximumNpc() >= 0) {
                var cap = scaleValue(maker.maximumNpc(), factor);
                if (cap != maker.maximumNpc()) {
                    result = result.withMaximumNpc(cap);
                }
            }
            return result;
        });
    }

    public static State setAiParameter(State from, Collection<Integer> makers, String name, @Nullable String value) {
        var written = value == null ? null : AiParameters.formatValue(value);
        return editMakers(from, makers, maker -> {
            if (!maker.extended()) {
                return maker;
            }
            var parameters = new LinkedHashMap<>(AiParameters.parse(maker.aiParameters()));
            if (written == null) {
                if (parameters.remove(name) == null) {
                    return maker;
                }
            } else if (written.equals(parameters.put(name, written))) {
                return maker;
            }
            return maker.withAi(maker.ai(), AiParameters.format(parameters));
        });
    }

    public static State setAi(State from, Collection<Integer> makers, String ai, @Nullable MakerAiCatalog catalog) {
        var known = catalog == null ? null : catalog.getAi(ai);
        return editMakers(from, makers, maker -> {
            if (!maker.extended() || maker.ai().equals(ai)) {
                return maker;
            }
            var kept = new LinkedHashMap<String, String>();
            AiParameters.parse(maker.aiParameters()).forEach((name, value) -> {
                if (catalog == null || known != null && known.parameter(name) != null) {
                    kept.put(name, value);
                }
            });
            return maker.withAi(ai, AiParameters.format(kept));
        });
    }

    public static State delete(State from, Collection<Integer> makers, boolean zones) {
        var removed = new HashMap<Integer, SpawnMaker>();
        for (var index : makers) {
            if (index >= 0 && index < from.makers().size() && from.makers().get(index) != null) {
                removed.put(index, null);
            }
        }
        if (removed.isEmpty()) {
            return from;
        }
        var next = from.withMakers(removed);
        if (!zones) {
            return next;
        }
        var named = new HashSet<String>();
        for (var index : removed.keySet()) {
            var maker = from.makers().get(index);
            named.addAll(maker.territoryNames());
            named.addAll(maker.bannedTerritoryNames());
        }
        for (var maker : next.makers()) {
            if (maker != null) {
                maker.territoryNames().forEach(named::remove);
                maker.bannedTerritoryNames().forEach(named::remove);
            }
        }
        var orphans = new ArrayList<Integer>();
        for (var name : named) {
            var zone = next.territory(name);
            if (zone != null) {
                orphans.add(zone.index());
            }
        }
        return next.withoutTerritories(orphans);
    }

    public static Set<Integer> changedMakers(State before, State after) {
        var changed = new TreeSet<Integer>();
        var count = Math.max(before.makers().size(), after.makers().size());
        for (var i = 0; i < count; i++) {
            var old = i < before.makers().size() ? before.makers().get(i) : null;
            var now = i < after.makers().size() ? after.makers().get(i) : null;
            if (old != now) {
                changed.add(i);
            }
        }
        return changed;
    }

    public static Set<Integer> changedTerritories(State before, State after) {
        var changed = new TreeSet<Integer>();
        var count = Math.max(before.territories().size(), after.territories().size());
        for (var i = 0; i < count; i++) {
            var old = i < before.territories().size() ? before.territories().get(i) : null;
            var now = i < after.territories().size() ? after.territories().get(i) : null;
            if (old != now) {
                changed.add(i);
            }
        }
        return changed;
    }

    public static String scaledTime(String value, double factor) {
        var matcher = TIME.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        var unit = secondsOf(matcher.group(2));
        var total = Long.parseLong(matcher.group(1)) * unit;
        var scaled = Math.max(0, Math.round(total * factor));
        if (scaled == total) {
            return value;
        }
        for (var candidate : List.of("day", "hour", "min", "sec")) {
            var size = secondsOf(candidate);
            if (size <= unit && scaled % size == 0) {
                return scaled / size + candidate;
            }
        }
        return scaled + "sec";
    }

    public static long parseSeconds(String time) {
        var matcher = TIME.matcher(time);
        return matcher.matches() ? Long.parseLong(matcher.group(1)) * secondsOf(matcher.group(2)) : -1;
    }

    public static String formatFactor(double factor) {
        return "×" + BigDecimal.valueOf(factor).stripTrailingZeros().toPlainString();
    }

    private static long secondsOf(String unit) {
        return switch (unit) {
            case "sec" -> 1;
            case "min" -> 60;
            case "hour" -> 3600;
            default -> 86400;
        };
    }

    private static int scaleValue(int value, double factor) {
        return Math.max(1, (int) Math.round(value * factor));
    }

    private static State editMakers(State from, Collection<Integer> makers, UnaryOperator<SpawnMaker> change) {
        var changed = new HashMap<Integer, SpawnMaker>();
        for (var index : makers) {
            if (index < 0 || index >= from.makers().size()) {
                continue;
            }
            var maker = from.makers().get(index);
            if (maker == null) {
                continue;
            }
            var next = change.apply(maker);
            if (next != maker) {
                changed.put(index, next);
            }
        }
        return from.withMakers(changed);
    }
}
