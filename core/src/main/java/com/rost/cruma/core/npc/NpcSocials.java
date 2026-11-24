package com.rost.cruma.core.npc;

import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.spawn.PlayerEquipment;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.unreal.UDecryptor;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class NpcSocials implements PlayerEquipment.Waits {
    private static final int SOCIAL_KEY = 0x5C;
    private static final int WAIT_KEY = 0x08;
    private static final int SHIELD_WAIT_KEY = 0x66;
    private static final String DEATH = "death";
    private static final Map<Integer, String> KINDS = buildKinds();
    private static final String REFERENCE_CLASS = "mfighter";
    private static final Map<Integer, String> ANCHORS = Map.of(
            0x08, "wait_hand",
            0x09, "atkwait_hand",
            0x14, "atk01_hand",
            0x38, "shieldatk_1hs",
            0x3C, "death",
            0x3E, "deathwait",
            0x42, "castshort",
            0x4A, "picitem",
            0x57, "castshort",
            0x5D, "fishing_fly");
    private static final Pattern SECTION = Pattern.compile("^\\[(.+)]");
    private static final Pattern NAME =
            Pattern.compile("^NpcSocialAnimName\\[(\\d+)]\\s*=\\s*(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KIND =
            Pattern.compile("^(\\w+)AnimName\\[0]\\s*=\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    private final Path system;
    private final Map<String, Names> classes = new HashMap<>();
    private final Map<String, Boolean> readPackages = new HashMap<>();
    private final boolean fromTable;

    record Names(
            Map<Integer, String> socials,
            Map<String, String> kinds,
            Map<Integer, String> waits,
            @Nullable String shieldWait,
            Set<String> sequences) {
        Names(Map<Integer, String> socials, Map<String, String> kinds) {
            this(socials, kinds, Map.of(), null, Set.of());
        }
    }

    record KeyLayout(String name, NavigableMap<Integer, Integer> shifts) {
        static final KeyLayout P196 = of("196", 0x00, 0, 0x0B, 1, 0x3A, 2, 0x5C, 3);
        static final KeyLayout ESSENCE = of(
                "Essence", 0x00, 0, 0x01, 1, 0x02, 2, 0x03, 4, 0x09, 5, 0x0A, 7, 0x0B, 8, 0x3A, 9, 0x4B, 10, 0x4C, 11,
                0x5C, 12);
        static final KeyLayout CLASSIC_162 = of("Classic of 162", 0x00, 0, 0x3D, -1);
        static final KeyLayout P152 = of("P152", 0x00, 0);
        static final List<KeyLayout> ALL = List.of(P152, P196, ESSENCE, CLASSIC_162);

        int toKey(int p152Key) {
            return p152Key + shifts.floorEntry(p152Key).getValue();
        }

        private static KeyLayout of(String name, int... startsAndShifts) {
            var shifts = new TreeMap<Integer, Integer>();
            for (var i = 0; i < startsAndShifts.length; i += 2) {
                shifts.put(startsAndShifts[i], startsAndShifts[i + 1]);
            }
            return new KeyLayout(name, shifts);
        }
    }

    private NpcSocials(Path system, Map<String, Names> table, boolean fromTable) {
        this.system = system;
        this.fromTable = fromTable;
        classes.putAll(table);
    }

    public static NpcSocials read(Path client, WorldVariant variant) {
        var system = client.resolve("system");
        var table = TableFiles.find(system, variant, "PawnAnimData.dat");
        var names = TableFiles.find(system, variant, "L2GameDataName.dat");
        if (table != null && names != null) {
            try {
                var read = TableSchemas.read(
                        Tables.PAWN_ANIMATIONS,
                        table,
                        L2DataFile.read(table),
                        ClientCatalog.gameDataNames(L2DataFile.read(names)),
                        TableSchemas.getClientFolder(system));
                return new NpcSocials(system, namesOf(read.rows()), true);
            } catch (IOException | RuntimeException unreadable) {
                return new NpcSocials(system, Map.of(), true);
            }
        }
        return new NpcSocials(system, Map.of(), false);
    }

    static Map<String, Names> parseText(String text) {
        var read = new HashMap<String, Names>();
        Names section = null;
        for (var raw : text.split("\r?\n")) {
            var line = raw.strip();
            var start = SECTION.matcher(line);
            if (start.find()) {
                section = read.computeIfAbsent(
                        start.group(1).toLowerCase(Locale.ROOT), _ -> new Names(new HashMap<>(), new HashMap<>()));
                continue;
            }
            if (section == null) {
                continue;
            }
            var name = NAME.matcher(line);
            var kind = KIND.matcher(line);
            if (name.find()) {
                section.socials().put(Integer.parseInt(name.group(1)), name.group(2));
            } else if (kind.find()) {
                section.kinds().put(kind.group(1).toLowerCase(Locale.ROOT), kind.group(2));
            }
        }
        return read;
    }

    static Map<String, Names> parseTable(byte[] payload, List<String> names) {
        return namesOf(TableSchemas.read(Tables.PAWN_ANIMATIONS, Path.of("PawnAnimData.dat"), payload, names, null)
                .rows());
    }

    static Map<String, Names> namesOf(List<Map<String, Object>> rows) {
        var layout = findLayout(rows);
        var kinds = new HashMap<Integer, String>();
        KINDS.forEach((key, kind) -> kinds.put(layout.toKey(key), kind));
        var socialKey = layout.toKey(SOCIAL_KEY);
        var waitKey = layout.toKey(WAIT_KEY);
        var shieldWaitKey = layout.toKey(SHIELD_WAIT_KEY);
        var read = new HashMap<String, Names>();
        for (var row : rows) {
            var socials = new HashMap<Integer, String>();
            var named = new HashMap<String, String>();
            var waits = new HashMap<Integer, String>();
            String shieldWait = null;
            var sequences = new HashSet<String>();
            for (var entry : entries(row)) {
                var key = entry.key();
                sequences.add(entry.anim().toLowerCase(Locale.ROOT));
                var kind = kinds.get(key);
                if (key == socialKey) {
                    socials.put(entry.index(), entry.anim());
                } else if (kind != null && entry.index() == 0) {
                    named.put(kind, entry.anim());
                } else if (key == shieldWaitKey && entry.index() == 0) {
                    shieldWait = entry.anim();
                }
                if (key == waitKey) {
                    waits.put(entry.index(), entry.anim());
                }
            }
            read.put(
                    String.valueOf(row.get("class")).toLowerCase(Locale.ROOT),
                    new Names(socials, named, waits, shieldWait, Set.copyOf(sequences)));
        }
        return read;
    }

    static KeyLayout findLayout(List<Map<String, Object>> rows) {
        Map<Integer, String> reference = null;
        for (var row : rows) {
            if (String.valueOf(row.get("class")).equalsIgnoreCase(REFERENCE_CLASS)) {
                reference = new HashMap<>();
                for (var entry : entries(row)) {
                    if (entry.index() == 0) {
                        reference.put(entry.key(), withoutClass(entry.anim()));
                    }
                }
                break;
            }
        }
        if (reference != null) {
            for (var layout : KeyLayout.ALL) {
                var named = reference;
                if (ANCHORS.entrySet().stream()
                        .allMatch(anchor -> anchor.getValue().equals(named.get(layout.toKey(anchor.getKey()))))) {
                    return layout;
                }
            }
        }
        LogManager.getLogger(NpcSocials.class)
                .warn(
                        "PawnAnimData.dat fits no known numbering of its keys{}; reading it as P152 numbers them",
                        reference == null ? " and has no class MFighter" : "");
        return KeyLayout.P152;
    }

    public @Nullable String getSequence(String npcClass, int social) {
        var names = names(npcClass);
        return names == null ? null : names.socials().get(social - 1);
    }

    public @Nullable String getDeath(String npcClass) {
        return getKind(npcClass, DEATH);
    }

    public @Nullable String getKind(String npcClass, String kind) {
        var names = names(npcClass);
        return names == null ? null : names.kinds().get(kind.toLowerCase(Locale.ROOT));
    }

    public Map<String, String> getKinds(String npcClass) {
        var names = names(npcClass);
        return names == null ? Map.of() : Map.copyOf(names.kinds());
    }

    public Map<Integer, String> getSocials(String npcClass) {
        var names = names(npcClass);
        if (names == null) {
            return Map.of();
        }
        var socials = new HashMap<Integer, String>();
        names.socials().forEach((index, sequence) -> socials.put(index + 1, sequence));
        return Map.copyOf(socials);
    }

    public Set<String> getSequences(String npcClass) {
        var names = names(npcClass);
        if (names == null) {
            return Set.of();
        }
        var all = new HashSet<>(names.sequences());
        for (var name : names.socials().values()) {
            all.add(name.toLowerCase(Locale.ROOT));
        }
        for (var name : names.kinds().values()) {
            all.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(all);
    }

    @Override
    public @Nullable String getWait(String figure, int handness, boolean shield) {
        var names = fromTable ? names(figure) : null;
        if (names == null) {
            return null;
        }
        return shield ? names.shieldWait() : names.waits().get(handness);
    }

    private @Nullable Names names(String npcClass) {
        var dot = npcClass.indexOf('.');
        if (!fromTable && dot > 0) {
            readPackage(npcClass.substring(0, dot).toLowerCase(Locale.ROOT));
        }
        return classes.get(npcClass.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private void readPackage(String name) {
        if (readPackages.putIfAbsent(name, true) != null) {
            return;
        }
        var file = find(name + ".int");
        if (file != null) {
            try {
                var buffer = UDecryptor.decrypt(file);
                var bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                var unicode = bytes.length > 1 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE;
                classes.putAll(parseText(
                        new String(bytes, unicode ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1)));
            } catch (RuntimeException unreadable) {
            }
        }
    }

    private @Nullable Path find(String lowerName) {
        try (var files = Files.list(system)) {
            return files.filter(file -> file.getFileName().toString().equalsIgnoreCase(lowerName))
                    .findFirst()
                    .orElse(null);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static List<Entry> entries(Map<String, Object> row) {
        if (!(row.get("anims") instanceof List<?> anims)) {
            return List.of();
        }
        var entries = new ArrayList<Entry>(anims.size());
        for (var item : anims) {
            if (item instanceof Map<?, ?> entry
                    && entry.get("key") instanceof Integer key
                    && entry.get("index") instanceof Integer index) {
                entries.add(new Entry(key, index, String.valueOf(entry.get("anim"))));
            }
        }
        return entries;
    }

    private record Entry(int key, int index, String anim) {}

    private static String withoutClass(String anim) {
        var lower = anim.toLowerCase(Locale.ROOT);
        var suffix = "_" + REFERENCE_CLASS;
        return lower.endsWith(suffix) ? lower.substring(0, lower.length() - suffix.length()) : lower;
    }

    private static Map<Integer, String> buildKinds() {
        var kinds = new HashMap<Integer, String>();
        kinds.put(0x08, "wait");
        kinds.put(0x09, "atkwait");
        kinds.put(0x14, "atk01");
        kinds.put(0x15, "atk02");
        kinds.put(0x16, "atk03");
        for (var i = 1; i <= 28; i++) {
            kinds.put(0x1B + i, String.format(Locale.ROOT, "spatk%02d", i));
        }
        kinds.put(0x38, "shieldatk");
        kinds.put(0x3C, DEATH);
        kinds.put(0x3E, "deathwait");
        kinds.put(0x42, "castshort");
        kinds.put(0x43, "castmid");
        kinds.put(0x44, "castlong");
        kinds.put(0x45, "castend");
        kinds.put(0x46, "magicthrow");
        kinds.put(0x47, "magicshot");
        kinds.put(0x48, "magicnotarget");
        kinds.put(0x49, "magicfriend");
        kinds.put(0x4A, "picitem");
        kinds.put(0x57, "castshort2");
        kinds.put(0x58, "castmid2");
        kinds.put(0x59, "castlong2");
        kinds.put(0x5A, "castend2");
        kinds.put(0x67, "specialsocial1");
        kinds.put(0x68, "specialsocial2");
        kinds.put(0x69, "specialsocial3");
        return Map.copyOf(kinds);
    }
}
