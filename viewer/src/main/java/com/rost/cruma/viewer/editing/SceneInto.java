package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.LobbyEdits;
import com.rost.cruma.core.editing.LobbySceneWriter;
import com.rost.cruma.core.editing.SceneCheck;
import com.rost.cruma.core.editing.SceneCheck.Lack;
import com.rost.cruma.core.editing.SceneCheck.PathError;
import com.rost.cruma.core.editing.SceneCheck.Stock;
import com.rost.cruma.core.editing.SceneClassWriter;
import com.rost.cruma.core.editing.SceneScript;
import com.rost.cruma.core.editing.ShotBake;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.world.GameScenes;
import com.rost.cruma.core.world.GameScenes.Scene;
import com.rost.cruma.core.world.SceneDocument;
import com.rost.cruma.core.world.SceneRig;
import com.rost.cruma.core.world.SceneSources;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.editing.PackageExportService.ReplacedAssetInput;
import com.rost.cruma.viewer.render.ShotBakeReplay;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

public final class SceneInto {
    public static final List<Integer> RATES = List.of(30, 60, 144);

    private SceneInto() {}

    public record Prepared(
            SceneDocument document,
            String file,
            @Nullable SceneCheck check,
            boolean keptObjects,
            @Nullable LobbyEdits lobby,
            @Nullable PackageExportService service,
            @Nullable Plan plan,
            List<String> notes) {
        public Prepared {
            notes = List.copyOf(notes);
        }

        public boolean canWrite() {
            return service != null && plan != null && plan.isValid();
        }
    }

    public static Prepared scene(Path client, WorldVariant variant, SceneDocument document, String target)
            throws IOException {
        var file = SceneSources.clientFile(client);
        if (file == null) {
            throw new IOException("This client has no LineageSceneInfo.u.");
        }
        var original = find(GameScenes.find(client), target);
        if (original == null) {
            throw new IOException("This client has no scene " + target + ".");
        }
        var baked = ShotBake.bake(document);
        var bytes = Files.readAllBytes(file);
        var writer = SceneClassWriter.open(file, bytes, SceneScript.read(client));
        var report = writer.replace(target, baked.document());
        var written = writer.getBytes(file);
        var notes = new ArrayList<String>();
        var path = new ArrayList<PathError>();
        if (!document.paths().isEmpty()) {
            for (var rate : RATES) {
                path.add(ShotBakeReplay.measure(
                        document.scene(), baked.document().scene(), rate));
            }
        }
        var film = baked.document().scene();
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            var rigs = rigs(client, variant, loader, notes);
            var check = SceneCheck.of(
                    original,
                    baked.document(),
                    report,
                    baked.replaced(),
                    stock(client, variant, rigs, notes),
                    SceneCheck.marks(film, rigs),
                    path);
            var changed = writer.isChanged();
            var service = new PackageExportService(client);
            var hash = PackageExportService.fileFingerprint(file);
            var plan = changed
                    ? service.prepare(
                            List.of(), List.of(), List.of(), List.of(new ReplacedAssetInput(file, hash, written)))
                    : null;
            return new Prepared(
                    document,
                    relative(client, file),
                    check,
                    report.keptObjects(),
                    null,
                    changed ? service : null,
                    plan,
                    notes);
        } finally {
            loader.trimCaches();
        }
    }

    public static Prepared lobby(Path client, SceneDocument document, boolean changedInEdit) throws IOException {
        var file = SceneSources.mapFile(client, document.map());
        if (file == null) {
            throw new IOException("This client has no map " + document.map() + ".");
        }
        var bytes = Files.readAllBytes(file);
        var writer = LobbySceneWriter.open(file, bytes);
        var edits = LobbyEdits.of(document, writer);
        var notes = new ArrayList<String>();
        var hash = PackageExportService.fileFingerprint(file);
        if (!document.basedOnSha256().isEmpty() && !document.basedOnSha256().equals(hash)) {
            notes.add("The file of this map changed after the copy was made. The numbers below come from the file as it"
                    + " is now.");
        }
        if (changedInEdit || !edits.canWrite() || edits.edits().isEmpty()) {
            return new Prepared(document, relative(client, file), null, false, edits, null, null, notes);
        }
        var written = writer.write(edits.edits(), file);
        var service = new PackageExportService(client);
        var plan =
                service.prepare(List.of(), List.of(), List.of(), List.of(new ReplacedAssetInput(file, hash, written)));
        return new Prepared(document, relative(client, file), null, false, edits, service, plan, notes);
    }

    public static String text(Lack lack) {
        return switch (lack.need()) {
            case NPC -> "NPC " + lack.name();
            case SKILL -> "Skill " + lack.name();
            case SEQUENCE -> "Sequence " + lack.name();
        };
    }

    private static @Nullable Scene find(List<Scene> scenes, String name) {
        for (var scene : scenes) {
            if (scene.name().equalsIgnoreCase(name)) {
                return scene;
            }
        }
        return null;
    }

    private static String relative(Path client, Path file) {
        return client.relativize(file).toString().replace('\\', '/');
    }

    private static IntFunction<@Nullable SceneRig> rigs(
            Path client, WorldVariant variant, UnrealLoader loader, List<String> notes) {
        NpcGrpTable looks;
        try {
            looks = NpcGrpTable.read(system(client), variant);
        } catch (IOException | RuntimeException unreadable) {
            LogManager.getLogger(SceneInto.class)
                    .warn("The NPCs of the client could not be read: {}", unreadable.toString());
            notes.add("The NPCs of this client could not be read, so the marks of animations were not looked at.");
            return _ -> null;
        }
        var read = new HashMap<Integer, @Nullable SceneRig>();
        return npc -> read.computeIfAbsent(npc, id -> {
            var appearance = looks.getAppearance(id);
            try {
                return appearance == null ? null : loader.loadSceneRig(appearance.mesh(), 1);
            } catch (RuntimeException unreadable) {
                LogManager.getLogger(SceneInto.class)
                        .warn("The skeleton of NPC {} could not be read: {}", id, unreadable.toString());
                return null;
            }
        });
    }

    private static Stock stock(
            Path client, WorldVariant variant, IntFunction<@Nullable SceneRig> rigs, List<String> notes) {
        try {
            return SceneCheck.stock(client, variant, rigs);
        } catch (IOException | RuntimeException unreadable) {
            LogManager.getLogger(SceneInto.class)
                    .warn("The tables of the client could not be read: {}", unreadable.toString());
            notes.add("The tables of this client could not be read, so what it lacks was not looked at.");
            return new Stock() {
                @Override
                public boolean hasNpc(int npc) {
                    return true;
                }

                @Override
                public boolean hasSkill(int skill) {
                    return true;
                }

                @Override
                public boolean hasSequence(int npc, String sequence) {
                    return true;
                }
            };
        }
    }

    private static Path system(Path client) throws IOException {
        try (var entries = Files.list(client)) {
            return entries.filter(entry -> entry.getFileName().toString().equalsIgnoreCase("System"))
                    .findFirst()
                    .orElse(client.resolve("System"));
        }
    }
}
