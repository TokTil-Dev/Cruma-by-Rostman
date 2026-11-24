package com.rost.cruma.core.npc;

import com.rost.cruma.core.npc.ClientCatalog.SkillSound;
import com.rost.cruma.core.npc.ClientCatalog.SkillSound.Moment;
import com.rost.cruma.core.schema.TableFiles;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

final class SkillSounds {
    private static final String[] MOMENTS = {"cast", "shot", "hit"};

    static final List<String> VOICE_FIGURES = List.of(
            "MFighter",
            "FFighter",
            "MDarkElf",
            "FDarkElf",
            "MDwarf",
            "FDwarf",
            "MElf",
            "FElf",
            "MMagic",
            "FMagic",
            "MOrc",
            "FOrc",
            "MShaman",
            "FShaman",
            "MKamael",
            "FKamael",
            "MErtheia",
            "FErtheia",
            "MSylph",
            "FSylph",
            "MHighElf",
            "FHighElf",
            "Crow");

    static final SkillSounds EMPTY = new SkillSounds(Map.of());

    private final Map<Integer, TreeMap<Integer, Entry>> entries;

    private record Entry(
            List<SkillSound> sounds,
            List<String> castVoices,
            List<String> shotVoices,
            float voiceVolume,
            float voiceRadius) {}

    private SkillSounds(Map<Integer, TreeMap<Integer, Entry>> entries) {
        this.entries = entries;
    }

    static SkillSounds read(Path system, WorldVariant variant, List<String> names, List<String> notes) {
        if (TableFiles.find(system, variant, "SkillSoundgrp.dat") == null) {
            return EMPTY;
        }
        var sources = TableFiles.find(system, variant, "SkillSoundSource.dat") == null
                ? Map.<Integer, int[]>of()
                : sourcesOf(readTable(system, variant, Tables.SKILL_SOUND_SOURCES, names, notes));
        return of(readTable(system, variant, Tables.SKILL_SOUNDS, names, notes), sources);
    }

    static Map<Integer, int[]> sourcesOf(List<Map<String, Object>> records) {
        var sources = new HashMap<Integer, int[]>();
        for (var record : records) {
            if (!(record.get("id") instanceof Integer id)) {
                continue;
            }
            var source = new int[9];
            for (var slot = 0; slot < 3; slot++) {
                for (var moment = 0; moment < 3; moment++) {
                    source[slot * 3 + moment] =
                            record.get(MOMENTS[moment] + "Source" + (slot + 1)) instanceof Integer place ? place : 0;
                }
            }
            sources.put(id, source);
        }
        return sources;
    }

    static SkillSounds of(List<Map<String, Object>> records, Map<Integer, int[]> sources) {
        var shared = new HashMap<String, String>();
        var entries = new HashMap<Integer, TreeMap<Integer, Entry>>();
        for (var record : records) {
            if (!(record.get("id") instanceof Integer id) || !(record.get("level") instanceof Integer level)) {
                continue;
            }
            var source = sources.get(id);
            var sounds = new ArrayList<SkillSound>();
            for (var slot = 1; slot <= 3; slot++) {
                for (var moment = 0; moment < 3; moment++) {
                    var name = MOMENTS[moment];
                    var sound = soundName(record.get(name + slot));
                    var volume = number(record.get(name + "Volume" + slot));
                    var radius = number(record.get(name + "Radius" + slot));
                    if (sound.isEmpty() || !(volume > 0f) || !(radius > 0f)) {
                        continue;
                    }
                    var placed = source == null ? 0 : source[(slot - 1) * 3 + moment];
                    var onTarget = moment == 2 ? placed != 1 : placed == 2;
                    sounds.add(new SkillSound(
                            shared.computeIfAbsent(sound, key -> key),
                            Moment.values()[moment],
                            onTarget,
                            volume / 255f,
                            radius,
                            Math.max(0f, number(record.get(name + "Delay" + slot)))));
                }
            }
            var entry = new Entry(
                    List.copyOf(sounds),
                    voices(record.get("castVoices"), shared),
                    voices(record.get("shotVoices"), shared),
                    number(record.get("voiceVolume")) / 255f,
                    number(record.get("voiceRadius")));
            entries.computeIfAbsent(id, ignored -> new TreeMap<>()).put(level, entry);
        }
        return new SkillSounds(entries);
    }

    List<SkillSound> of(int id, int level, @Nullable String figure) {
        var levels = entries.get(id);
        if (levels == null) {
            return List.of();
        }
        var found = levels.floorEntry(level);
        var entry = found != null ? found.getValue() : levels.firstEntry().getValue();
        var index = figure == null ? -1 : indexOf(figure);
        if (index < 0 || !(entry.voiceVolume() > 0f) || !(entry.voiceRadius() > 0f)) {
            return entry.sounds();
        }
        var sounds = new ArrayList<>(entry.sounds());
        if (index < entry.castVoices().size() - 1
                && !entry.castVoices().get(index).isEmpty()) {
            sounds.add(new SkillSound(
                    entry.castVoices().get(index), Moment.CAST, false, entry.voiceVolume(), entry.voiceRadius(), 0f));
        }
        if (index < entry.shotVoices().size() - 1
                && !entry.shotVoices().get(index).isEmpty()) {
            sounds.add(new SkillSound(
                    entry.shotVoices().get(index), Moment.SHOT, false, entry.voiceVolume(), entry.voiceRadius(), 0f));
        }
        return List.copyOf(sounds);
    }

    int size() {
        return entries.size();
    }

    private static int indexOf(String figure) {
        for (var index = 0; index < VOICE_FIGURES.size(); index++) {
            if (VOICE_FIGURES.get(index).equalsIgnoreCase(figure)) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> voices(@Nullable Object value, Map<String, String> shared) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        var voices = new ArrayList<String>(list.size());
        for (var item : list) {
            var name = soundName(item);
            voices.add(shared.computeIfAbsent(name, key -> key));
        }
        return voices;
    }

    private static String soundName(@Nullable Object value) {
        return value instanceof String name && !name.isBlank() && !name.equalsIgnoreCase("None") ? name.trim() : "";
    }

    private static float number(@Nullable Object value) {
        return value instanceof Number number && Float.isFinite(number.floatValue()) ? number.floatValue() : 0f;
    }

    private static List<Map<String, Object>> readTable(
            Path system, WorldVariant variant, TableSpec spec, List<String> names, List<String> notes) {
        var file = TableFiles.find(system, variant, spec.fileName());
        if (file == null) {
            return List.of();
        }
        byte[] bytes;
        try {
            bytes = L2DataFile.read(file);
        } catch (IOException | RuntimeException failure) {
            notes.add(spec.fileName() + ": " + failure.getMessage());
            return List.of();
        }
        var table = TableSchemas.read(spec, file, bytes, names, TableSchemas.getClientFolder(system));
        if (table.report().state() == State.NOT_READ) {
            notes.add(spec.fileName() + " matches no known layout");
        }
        return table.rows();
    }
}
