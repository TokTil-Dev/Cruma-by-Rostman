package com.rost.cruma.core.world;

import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.UMeshAnimation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public final class PlayerFigures {
    private static final Pattern PLAYER_FACE = Pattern.compile("(?i)^([a-z]+)\\.([mf][a-z]+)_m(\\d+)_f$");

    private static final Map<String, Face> OTHER_FACES = Map.of("crow.crow_m000_f", new Face("Crow", "Crow"));

    private static final Map<String, String> PAWN_CLASSES = Map.of("crow", "MCrow");

    private static final Set<String> ESSENCE_FIGURES = Set.of("msylph", "fsylph", "mhighelf", "fhighelf", "crow");

    private static final List<ClassFigure> CLASS_FIGURES = List.of(
            new ClassFigure("MHuman_DeathKnight", "MFighter", "Human", "Death Knight", 4, ""),
            new ClassFigure("MElf_DeathKnight", "MElf", "Elf", "Death Knight", 9, ""),
            new ClassFigure("MDarkElf_DeathKnight", "MDarkElf", "Dark Elf", "Death Knight", 14, ""),
            new ClassFigure("MHuman_DeathFighter", "MFighter", "Human", "Death Fighter", 32, ""),
            new ClassFigure("MOrc_Rider", "MOrc", "Orc", "Vanguard Rider", 19, "orc_ride_wolf1_m_ad00"),
            new ClassFigure("MHuman_Assassin", "MFighter", "Human", "Assassin", 30, ""),
            new ClassFigure("FDarkElf_Assassin", "FDarkElf", "Dark Elf", "Assassin", 31, ""),
            new ClassFigure("FDwarf_Maker", "FDwarf", "Dwarf", "Shine Maker", 33, ""),
            new ClassFigure("MHuman_WereWolf", "MFighter", "Human", "Warg", 36, ""),
            new ClassFigure("FDarkElf_RoseVain", "FDarkElf", "Dark Elf", "Blood Rose", 37, ""),
            new ClassFigure("MHuman_Waker", "MFighter", "Human", "Waker", 38, ""));

    private static final Map<String, ClassFigure> CLASS_FIGURES_BY_NAME = byName();

    private PlayerFigures() {}

    public record Face(String packageName, String race) {}

    public record WholeBody(String mesh, List<String> textures) {}

    public record ClassFigure(String figure, String base, String people, String name, int slot, String mount) {
        public boolean isFemale() {
            return base.startsWith("F");
        }
    }

    public static boolean isFace(String mesh) {
        return parseFace(mesh) != null;
    }

    public static @Nullable Face parseFace(String face) {
        var other = OTHER_FACES.get(face.toLowerCase(Locale.ROOT));
        if (other != null) {
            return other;
        }
        var matcher = PLAYER_FACE.matcher(face);
        return matcher.matches() ? new Face(matcher.group(1), matcher.group(2)) : null;
    }

    public static String figureOf(String face) {
        var parsed = parseFace(face);
        if (parsed != null) {
            return parsed.race();
        }
        var dot = face.indexOf('.');
        var end = face.toLowerCase(Locale.ROOT).lastIndexOf("_m000_f");
        return face.substring(dot + 1, end < 0 ? face.length() : end);
    }

    public static String figureOf(String face, @Nullable String npcClass) {
        var figure = figureOf(face);
        if (npcClass == null) {
            return figure;
        }
        var made = findClassFigure(npcClass.substring(npcClass.indexOf('.') + 1));
        return made != null && made.base().equalsIgnoreCase(figure) ? made.figure() : figure;
    }

    public static boolean isEssenceFigure(String figure) {
        return ESSENCE_FIGURES.contains(figure.toLowerCase(Locale.ROOT));
    }

    public static boolean isOffered(
            String figure,
            boolean hasFace,
            Predicate<String> hasLooks,
            Predicate<String> hasRow,
            IntPredicate creates) {
        var made = findClassFigure(figure);
        if (made != null) {
            return hasFace && creates.test(made.slot());
        }
        return hasFace && (!isEssenceFigure(figure) || hasLooks.test(figure) && hasRow.test(figure));
    }

    public static Set<Integer> readCreated(Path client, WorldVariant variant) {
        try {
            var table = TableSchemas.read(Tables.CHARACTER_CLASSES, client.resolve("system"), variant);
            return table.schema() == null ? Set.of() : findSlots(table.rows());
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
    }

    static Set<Integer> findSlots(List<Map<String, Object>> rows) {
        var slots = new HashSet<Integer>();
        for (var row : rows) {
            if (row.get("slot") instanceof Number slot) {
                slots.add(slot.intValue());
            }
        }
        return Set.copyOf(slots);
    }

    public static List<ClassFigure> getClassFigures() {
        return CLASS_FIGURES;
    }

    public static @Nullable ClassFigure findClassFigure(String figure) {
        return CLASS_FIGURES_BY_NAME.get(figure.toLowerCase(Locale.ROOT));
    }

    public static String getLookFigure(String figure) {
        var made = findClassFigure(figure);
        return made == null ? figure : made.base();
    }

    public static String getPawnClass(String figure) {
        return "LineageWarrior." + getPawnClassName(figure);
    }

    public static String getPawnClassName(String figure) {
        return PAWN_CLASSES.getOrDefault(figure.toLowerCase(Locale.ROOT), figure);
    }

    public static boolean sharesRaceAnimation(String figure, Map<String, String> sharedRaces) {
        return sharedRaces.containsKey(figure.toLowerCase(Locale.ROOT))
                || sharedRaces.values().stream().anyMatch(race -> race.equalsIgnoreCase(figure))
                || !getSharers(figure).isEmpty();
    }

    public static List<String> getSharers(String figure) {
        var made = findClassFigure(figure);
        var base = made == null ? figure : made.base();
        var sharers = new ArrayList<String>();
        for (var candidate : CLASS_FIGURES) {
            if (candidate.base().equalsIgnoreCase(base)) {
                sharers.add(candidate.figure());
            }
        }
        if (sharers.isEmpty()) {
            return List.of();
        }
        sharers.add(base);
        sharers.removeIf(sharer -> sharer.equalsIgnoreCase(figure));
        return List.copyOf(sharers);
    }

    public static boolean plays(
            String figure, String sequence, Set<String> named, Set<String> others, Map<String, String> sharedRaces) {
        var name = sequence.strip().toLowerCase(Locale.ROOT);
        if (named.contains(name)) {
            return true;
        }
        var lower = figure.toLowerCase(Locale.ROOT);
        if (sharedRaces.containsKey(lower)) {
            return name.endsWith("_" + lower);
        }
        for (var shared : sharedRaces.entrySet()) {
            if (shared.getValue().equalsIgnoreCase(figure) && name.endsWith("_" + shared.getKey())) {
                return false;
            }
        }
        return !others.contains(name);
    }

    public static UMeshAnimation narrow(
            UMeshAnimation animation,
            String figure,
            Set<String> named,
            Set<String> others,
            Map<String, String> sharedRaces) {
        var lower = figure.toLowerCase(Locale.ROOT);
        var own = "_" + lower;
        var classFigure = CLASS_FIGURES_BY_NAME.containsKey(lower);
        return animation.select(sequence -> plays(figure, sequence, named, others, sharedRaces), sequence -> {
            var name = sequence.strip().toLowerCase(Locale.ROOT);
            return sharedRaces.containsKey(lower)
                    ? name.endsWith(own)
                    : classFigure && named.contains(name) && !others.contains(name);
        });
    }

    private static Map<String, ClassFigure> byName() {
        var map = new LinkedHashMap<String, ClassFigure>();
        for (var made : CLASS_FIGURES) {
            map.put(made.figure().toLowerCase(Locale.ROOT), made);
        }
        return Map.copyOf(map);
    }
}
