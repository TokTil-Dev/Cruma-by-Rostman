package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.NpcExport.Character;
import com.rost.cruma.core.export.NpcExport.Npc;
import com.rost.cruma.core.export.NpcExport.Result;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

public final class NpcBatchExport {
    public static final String SCENE = "NPC_scene";

    private NpcBatchExport() {}

    public record Entry(Npc npc, @Nullable Outfit outfit) {
        public static Entry of(Npc npc) {
            return new Entry(npc, null);
        }

        public static Entry of(Character character) {
            return new Entry(character.npc(), character.outfit());
        }
    }

    public record Outcome(
            Entry entry, String name, @Nullable Result result, long bytes, String failure) {
        public boolean isWritten() {
            return result != null;
        }
    }

    public record Summary(List<Outcome> outcomes, int total, boolean cancelled, long millis) {
        public List<Outcome> written() {
            return outcomes.stream().filter(Outcome::isWritten).toList();
        }

        public List<Outcome> failed() {
            return outcomes.stream().filter(outcome -> !outcome.isWritten()).toList();
        }

        public long bytes() {
            var sum = 0L;
            for (var outcome : outcomes) {
                sum += outcome.bytes();
            }
            return sum;
        }
    }

    @FunctionalInterface
    public interface Writer {
        Result write(
                Entry entry, Path parent, String name, boolean replace, Progress progress, BooleanSupplier cancelled)
                throws IOException;
    }

    @FunctionalInterface
    public interface SceneWriter {
        Result add(
                Entry entry,
                RigGlbWriter scene,
                Path sceneFile,
                String name,
                Progress progress,
                BooleanSupplier cancelled)
                throws IOException;
    }

    public static SceneWriter createSceneWriter(
            Path client, WorldVariant variant, ExportScale scale, Function<Npc, @Nullable Outfit> dress) {
        return (entry, scene, sceneFile, name, progress, cancelled) -> {
            var npc = entry.npc();
            var outfit = entry.outfit() != null ? entry.outfit() : dress.apply(npc);
            return NpcExport.addToScene(
                    client, variant, npc, outfit, name, scale, scene, sceneFile, progress, cancelled);
        };
    }

    public static String sceneName(Path parent, boolean replace) {
        var name = SCENE;
        for (var number = 2; !replace && Files.exists(parent.resolve(name)); number++) {
            name = SCENE + "_" + number;
        }
        return name;
    }

    public static Writer createWriter(
            Path client, WorldVariant variant, ExportScale scale, Function<Npc, @Nullable Outfit> dress) {
        return (entry, parent, name, replace, progress, cancelled) -> {
            var npc = entry.npc();
            var outfit = entry.outfit() != null ? entry.outfit() : dress.apply(npc);
            return NpcExport.export(client, variant, npc, outfit, name, scale, parent, replace, progress, cancelled);
        };
    }

    public static List<String> assignFolderNames(List<Entry> entries, Path parent, boolean replace) {
        var taken = new HashSet<String>();
        var result = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            var base = NpcExport.name(entry.npc());
            var name = base;
            for (var number = 2; !free(name, taken, parent, replace); number++) {
                name = base + "_" + number;
            }
            taken.add(name.toLowerCase(Locale.ROOT));
            result.add(name);
        }
        return List.copyOf(result);
    }

    public static Summary run(
            List<Entry> entries,
            Path parent,
            boolean replace,
            Writer writer,
            Progress progress,
            Consumer<Outcome> finished,
            BooleanSupplier cancelled) {
        var started = System.nanoTime();
        var names = assignFolderNames(entries, parent, replace);
        var outcomes = new ArrayList<Outcome>(entries.size());
        var stopped = isLoop(entries, names, progress, finished, cancelled, outcomes, (entry, name, step) -> {
            var result = writer.write(entry, parent, name, replace, step, cancelled);
            return new Outcome(entry, name, result, size(result), "");
        });
        return new Summary(List.copyOf(outcomes), entries.size(), stopped, (System.nanoTime() - started) / 1_000_000);
    }

    public static Summary runScene(
            List<Entry> entries,
            Path parent,
            boolean replace,
            SceneWriter writer,
            Progress progress,
            Consumer<Outcome> finished,
            BooleanSupplier cancelled) {
        var started = System.nanoTime();
        var names = assignFolderNames(entries, parent, true);
        var sceneName = sceneName(parent, replace);
        var folder = parent.resolve(sceneName);
        var file = folder.resolve(sceneName + ".glb");
        var outcomes = new ArrayList<Outcome>(entries.size());
        Path staging = null;
        try {
            Files.createDirectories(parent);
            staging = Files.createTempDirectory(parent, "." + sceneName + "-export-");
            boolean stopped;
            try (var scene = RigGlbWriter.scene(staging)) {
                stopped = isLoop(entries, names, progress, finished, cancelled, outcomes, (entry, name, step) -> {
                    var before = scene.size();
                    var result = writer.add(entry, scene, file, name, step, cancelled);
                    return new Outcome(entry, name, result, scene.size() - before, "");
                });
                if (scene.getModels() > 0) {
                    progress.update(
                            "Writing " + file.getFileName(),
                            stopped ? (double) outcomes.size() / Math.max(1, entries.size()) : 1);
                    scene.finish(staging.resolve(file.getFileName()), sceneName);
                    Files.writeString(
                            staging.resolve(LocationExport.INFO),
                            buildSceneInfo(sceneName, outcomes),
                            StandardCharsets.UTF_8);
                    LocationExport.writeBlenderScript(staging);
                    Files.createDirectories(folder);
                    for (var name : NpcExport.fileNames(sceneName)) {
                        Files.move(staging.resolve(name), folder.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return new Summary(
                    List.copyOf(outcomes), entries.size(), stopped, (System.nanoTime() - started) / 1_000_000);
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not write " + file.getFileName() + ": " + describeFailure(failure), failure);
        } finally {
            if (staging != null) {
                deleteTree(staging);
            }
        }
    }

    @FunctionalInterface
    private interface Step {
        Outcome write(Entry entry, String name, Progress progress) throws IOException;
    }

    private static boolean isLoop(
            List<Entry> entries,
            List<String> names,
            Progress progress,
            Consumer<Outcome> finished,
            BooleanSupplier cancelled,
            List<Outcome> outcomes,
            Step step) {
        var stopped = false;
        var total = entries.size();
        for (var index = 0; index < total; index++) {
            if (cancelled.getAsBoolean()) {
                stopped = true;
                break;
            }
            var entry = entries.get(index);
            var name = names.get(index);
            var prefix = (index + 1) + " of " + total + " · " + entry.npc().label() + " · ";
            var done = index;
            progress.update(prefix + "Starting", (double) done / total);
            Outcome outcome;
            try {
                outcome = step.write(
                        entry,
                        name,
                        (stage, fraction) ->
                                progress.update(prefix + stage, (done + Math.clamp(fraction, 0, 1)) / total));
            } catch (CancellationException cancel) {
                stopped = true;
                break;
            } catch (IOException | RuntimeException failure) {
                outcome = new Outcome(entry, name, null, 0, describeFailure(failure));
            }
            outcomes.add(outcome);
            finished.accept(outcome);
        }
        progress.update(stopped ? "Stopped" : "Done", stopped ? (double) outcomes.size() / Math.max(1, total) : 1);
        return stopped;
    }

    private static String buildSceneInfo(String sceneName, List<Outcome> outcomes) {
        var text = new StringBuilder("Cruma NPC scene\n\n");
        var written = outcomes.stream().filter(Outcome::isWritten).toList();
        text.append(sceneName)
                .append(".glb holds ")
                .append(written.size())
                .append(written.size() == 1 ? " model" : " models")
                .append(" in a row, each with its skeleton and every animation of the client.\n")
                .append("They face +X and stand ")
                .append(formatGap())
                .append(" m apart, from left to right as seen from in front, centred on (0, 0, 0).\n")
                .append("Each animation is named after its model, such as ")
                .append(written.isEmpty() ? "Roxxy_30006" : written.getFirst().name())
                .append("|wait. In Blender each model is an armature with its own actions.\n\n");
        for (var outcome : written) {
            var npc = outcome.entry().npc();
            var result = outcome.result();
            text.append(outcome.name()).append(": ").append(npc.label());
            if (npc.id() > 0) {
                text.append(", id ").append(npc.id());
            }
            text.append(", ")
                    .append(npc.mesh())
                    .append(", ")
                    .append(result.bones())
                    .append(" bones, ")
                    .append(result.animations().size())
                    .append(" animations\n");
        }
        var failed = outcomes.stream().filter(outcome -> !outcome.isWritten()).toList();
        if (!failed.isEmpty()) {
            text.append("\nNot exported:\n");
            for (var outcome : failed) {
                text.append(outcome.entry().npc().label())
                        .append(": ")
                        .append(outcome.failure())
                        .append('\n');
            }
        }
        return text.toString();
    }

    private static String formatGap() {
        var gap = RigGlbWriter.SCENE_GAP_METERS;
        return gap == Math.rint(gap) ? Integer.toString((int) gap) : Float.toString(gap);
    }

    private static void deleteTree(Path root) {
        try (var walk = Files.walk(root)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean free(String name, HashSet<String> taken, Path parent, boolean replace) {
        if (taken.contains(name.toLowerCase(Locale.ROOT))) {
            return false;
        }
        return replace || !Files.exists(parent.resolve(name));
    }

    private static long size(Result result) {
        var bytes = 0L;
        for (var file : result.files()) {
            try {
                bytes += Files.size(file);
            } catch (IOException gone) {
            }
        }
        return bytes;
    }

    private static String describeFailure(Exception failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
