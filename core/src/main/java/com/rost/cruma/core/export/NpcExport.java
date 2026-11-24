package com.rost.cruma.core.export;

import com.rost.cruma.core.export.LocationExport.Progress;
import com.rost.cruma.core.export.RigBuilder.Animation;
import com.rost.cruma.core.npc.ClientCatalog;
import com.rost.cruma.core.spawn.NpcGrpTable;
import com.rost.cruma.core.texture.DxtMipCodec.Options;
import com.rost.cruma.core.unreal.UMeshAnimation;
import com.rost.cruma.core.unreal.USkeletalMesh;
import com.rost.cruma.core.unreal.USkeletalMesh.Bone;
import com.rost.cruma.core.unreal.USkeletalMesh.Face;
import com.rost.cruma.core.unreal.USkeletalMesh.Influence;
import com.rost.cruma.core.unreal.USkeletalMesh.Wedge;
import com.rost.cruma.core.world.Entity.EntityMesh;
import com.rost.cruma.core.world.PlayerFigures;
import com.rost.cruma.core.world.PlayerRigInput;
import com.rost.cruma.core.world.UnrealLoader;
import com.rost.cruma.core.world.UnrealLoader.Outfit;
import com.rost.cruma.core.world.WorldVariant;
import glm_.vec3.Vec3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class NpcExport {
    public static final List<Race> RACES = List.of(
            new Race("Human fighter, male", "Fighter.MFighter_m000_f"),
            new Race("Human fighter, female", "Fighter.FFighter_m000_f"),
            new Race("Human mystic, male", "Magic.MMagic_m000_f"),
            new Race("Human mystic, female", "Magic.FMagic_m000_f"),
            new Race("Elf, male", "Elf.MElf_m000_f"),
            new Race("Elf, female", "Elf.FElf_m000_f"),
            new Race("Dark Elf, male", "DarkElf.MDarkElf_m000_f"),
            new Race("Dark Elf, female", "DarkElf.FDarkElf_m000_f"),
            new Race("Orc fighter, male", "Orc.MOrc_m000_f"),
            new Race("Orc fighter, female", "Orc.FOrc_m000_f"),
            new Race("Orc mystic, male", "Shaman.MShaman_m000_f"),
            new Race("Orc mystic, female", "Shaman.FShaman_m000_f"),
            new Race("Dwarf, male", "Dwarf.MDwarf_m000_f"),
            new Race("Dwarf, female", "Dwarf.FDwarf_m000_f"),
            new Race("Kamael, male", "Kamael.MKamael_m000_f"),
            new Race("Kamael, female", "Kamael.FKamael_m000_f"),
            new Race("Ertheia, male", "Ertheia.MErtheia_m000_f"),
            new Race("Ertheia, female", "Ertheia.FErtheia_m000_f"),
            new Race("Sylph, male", "Ertheia.MErtheia_m000_f", "MSylph"),
            new Race("Sylph, female", "Ertheia.FErtheia_m000_f", "FSylph"),
            new Race("High Elf, male", "HighElf.MHighElf_m000_f", "MHighElf"),
            new Race("High Elf, female", "HighElf.FHighElf_m000_f", "FHighElf"),
            new Race("Crow, male", "Crow.Crow_m000_f", "Crow"),
            new Race("Human Death Knight, male", "Fighter.MFighter_m000_f", "MHuman_DeathKnight"),
            new Race("Elf Death Knight, male", "Elf.MElf_m000_f", "MElf_DeathKnight"),
            new Race("Dark Elf Death Knight, male", "DarkElf.MDarkElf_m000_f", "MDarkElf_DeathKnight"),
            new Race("Human Death Fighter, male", "Fighter.MFighter_m000_f", "MHuman_DeathFighter"),
            new Race("Orc Vanguard Rider, male", "Orc.MOrc_m000_f", "MOrc_Rider"),
            new Race("Human Assassin, male", "Fighter.MFighter_m000_f", "MHuman_Assassin"),
            new Race("Dark Elf Assassin, female", "DarkElf.FDarkElf_m000_f", "FDarkElf_Assassin"),
            new Race("Dwarf Shine Maker, female", "Dwarf.FDwarf_m000_f", "FDwarf_Maker"),
            new Race("Human Warg, male", "Fighter.MFighter_m000_f", "MHuman_WereWolf"),
            new Race("Dark Elf Blood Rose, female", "DarkElf.FDarkElf_m000_f", "FDarkElf_RoseVain"),
            new Race("Human Waker, male", "Fighter.MFighter_m000_f", "MHuman_Waker"));

    private NpcExport() {}

    public record Npc(
            int id,
            String name,
            String title,
            String mesh,
            List<String> textures,
            float drawScale,
            boolean playerLike) {
        public String label() {
            if (!name.isBlank()) {
                return name;
            }
            var dot = mesh.lastIndexOf('.');
            return dot >= 0 ? mesh.substring(dot + 1) : mesh;
        }
    }

    public record Result(
            Path folder, List<Path> files, int bones, List<String> animations, long triangles, int textures) {}

    public static List<Npc> list(Path client, WorldVariant variant) throws IOException {
        var table = NpcGrpTable.read(client.resolve("system"), variant);
        var catalog = ClientCatalog.read(client, variant);
        var loader = table.hasScales() ? null : new UnrealLoader(client, new Options(false, 0), true);
        var classScales = new HashMap<String, Float>();
        var result = new ArrayList<Npc>(table.size());
        try {
            if (loader != null) {
                loader.worldVariant(variant);
            }
            for (var appearance : table.getAppearances()) {
                if (appearance.mesh() == null || appearance.mesh().isBlank()) {
                    continue;
                }
                var named = catalog.getNpc(appearance.id());
                var classScale = loader == null
                        ? 1f
                        : classScales.computeIfAbsent(
                                appearance.npcClass(),
                                name -> loader.loadNpcBody(name).drawScale());
                result.add(new Npc(
                        appearance.id(),
                        named == null ? "" : named.name(),
                        named == null ? "" : named.title(),
                        appearance.mesh(),
                        appearance.textures(),
                        table.getScale(appearance, classScale),
                        UnrealLoader.isPlayerFace(appearance.mesh())));
            }
        } finally {
            if (loader != null) {
                loader.trimCaches();
            }
        }
        result.sort(Comparator.comparingInt(Npc::id));
        return List.copyOf(result);
    }

    public static String name(Npc npc) {
        return npc.id() > 0
                ? LocationExport.toSafeName(npc.label()) + "_" + npc.id()
                : LocationExport.toSafeName(npc.label()).replaceAll("_{2,}", "_");
    }

    public record Race(String label, String face, String figure) {
        public Race(String label, String face) {
            this(label, face, PlayerFigures.figureOf(face));
        }
    }

    public record Character(String label, String face, Outfit outfit) {
        public Npc npc() {
            return new Npc(0, label, "", face, List.of(), 1f, true);
        }
    }

    public static Result exportCharacter(
            Path client,
            WorldVariant variant,
            Character character,
            ExportScale scale,
            Path parent,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return export(
                client, variant, character.npc(), character.outfit(), scale, parent, replace, progress, cancelled);
    }

    public static List<String> fileNames(String name) {
        return List.of(name + ".glb", LocationExport.BLENDER_SCRIPT, LocationExport.INFO);
    }

    public static List<Path> findExistingFiles(Path parent, Npc npc) {
        return findExistingFiles(parent, name(npc));
    }

    static List<Path> findExistingFiles(Path parent, String name) {
        var result = new ArrayList<Path>();
        for (var file : fileNames(name)) {
            var path = parent.resolve(name).resolve(file);
            if (Files.exists(path)) {
                result.add(path);
            }
        }
        return List.copyOf(result);
    }

    public static Result export(
            Path client,
            WorldVariant variant,
            Npc npc,
            ExportScale scale,
            Path parent,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return export(client, variant, npc, null, scale, parent, replace, progress, cancelled);
    }

    public static Result export(
            Path client,
            WorldVariant variant,
            Npc npc,
            @Nullable Outfit outfit,
            ExportScale scale,
            Path parent,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        return export(client, variant, npc, outfit, name(npc), scale, parent, replace, progress, cancelled);
    }

    static Result export(
            Path client,
            WorldVariant variant,
            Npc npc,
            @Nullable Outfit outfit,
            String name,
            ExportScale scale,
            Path parent,
            boolean replace,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        if (!replace && !findExistingFiles(parent, name).isEmpty()) {
            throw new IOException(
                    "The folder already has an export of " + npc.label() + ". Choose Replace to overwrite it.");
        }
        progress.update("Reading " + npc.mesh(), 0);
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        Files.createDirectories(parent);
        var staging = Files.createTempDirectory(parent, "." + name + "-export-");
        try {
            var rig = writeRig(loader, npc, outfit, scale, staging.resolve(name + ".glb"), progress, cancelled);
            Files.writeString(
                    staging.resolve(LocationExport.INFO), buildInfoText(npc, rig, scale), StandardCharsets.UTF_8);
            LocationExport.writeBlenderScript(staging);
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            var folder = parent.resolve(name);
            Files.createDirectories(folder);
            var files = new ArrayList<Path>();
            for (var file : fileNames(name)) {
                var target = folder.resolve(file);
                Files.move(staging.resolve(file), target, StandardCopyOption.REPLACE_EXISTING);
                files.add(target);
            }
            progress.update("Done", 1);
            return new Result(
                    folder, List.copyOf(files), rig.bones(), rig.animations(), rig.triangles(), rig.textures());
        } finally {
            loader.trimCaches();
            try (var walk = Files.walk(staging)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    static Result addToScene(
            Path client,
            WorldVariant variant,
            Npc npc,
            @Nullable Outfit outfit,
            String name,
            ExportScale scale,
            RigGlbWriter scene,
            Path sceneFile,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        progress.update("Reading " + npc.mesh(), 0);
        var loader = new UnrealLoader(client, new Options(false, 0), true);
        loader.worldVariant(variant);
        try {
            var rig = addRig(loader, npc, outfit, scale, scene, name, progress, cancelled);
            progress.update("Done", 1);
            return new Result(
                    sceneFile.getParent(),
                    List.of(sceneFile),
                    rig.bones(),
                    rig.animations(),
                    rig.triangles(),
                    rig.textures());
        } finally {
            loader.trimCaches();
        }
    }

    record Rig(int bones, List<String> animations, long triangles, int textures) {}

    static Rig writeRig(UnrealLoader loader, Npc npc, @Nullable Outfit outfit, ExportScale scale, Path file)
            throws IOException {
        return writeRig(loader, npc, outfit, scale, file, (message, fraction) -> {}, () -> false);
    }

    static Rig writeStill(UnrealLoader loader, Npc npc, ExportScale scale, Path file) throws IOException {
        var model = loader.loadStaticModel(npc.mesh());
        if (model == null) {
            throw new IOException(npc.mesh() + " cannot be read from this client.");
        }
        var built = encoded(
                RigBuilder.still(npc.label(), model.entityMesh(), scale), (message, fraction) -> {}, () -> false);
        RigGlbWriter.write(built.model(), built.png(), file);
        return built.rig();
    }

    static Rig addRig(
            UnrealLoader loader,
            Npc npc,
            @Nullable Outfit outfit,
            ExportScale scale,
            RigGlbWriter scene,
            String name,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var built = build(loader, npc, outfit, scale, progress, cancelled);
        progress.update("Adding " + name + " to the scene", .85);
        scene.add(built.model(), built.png(), name);
        return built.rig();
    }

    private record Built(RigModel model, List<byte[]> png, Rig rig) {}

    static float[] computePlacement(USkeletalMesh mesh, float lift, float drawScale) {
        var origin = mesh.getMeshOrigin();
        var scale = drawScale > 0 ? drawScale : 1f;
        var translation = mesh.convertMeshToLocal(new Vec3(0f));
        var result = new float[16];
        for (var axis = 0; axis < 3; axis++) {
            var unit = new Vec3(
                    origin.getX() + (axis == 0 ? 1 : 0),
                    origin.getY() + (axis == 1 ? 1 : 0),
                    origin.getZ() + (axis == 2 ? 1 : 0));
            var column = mesh.convertMeshToLocal(unit);
            result[axis * 4] = column.getX() * scale;
            result[axis * 4 + 1] = column.getY() * scale;
            result[axis * 4 + 2] = column.getZ() * scale;
        }
        result[12] = translation.getX() * scale;
        result[13] = translation.getY() * scale;
        result[14] = (translation.getZ() + lift) * scale;
        result[15] = 1;
        return result;
    }

    private static Rig writeRig(
            UnrealLoader loader,
            Npc npc,
            @Nullable Outfit outfit,
            ExportScale scale,
            Path file,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        var built = build(loader, npc, outfit, scale, progress, cancelled);
        progress.update("Writing " + file.getFileName(), .85);
        RigGlbWriter.write(built.model(), built.png(), file);
        return built.rig();
    }

    private static Built build(
            UnrealLoader loader,
            Npc npc,
            @Nullable Outfit outfit,
            ExportScale scale,
            Progress progress,
            BooleanSupplier cancelled)
            throws IOException {
        Bone[] bones;
        Vec3[] points;
        Influence[] influences;
        Wedge[] wedges;
        Face[] faces;
        long[] flags;
        EntityMesh drawn;
        UMeshAnimation animation;
        float[] placement;
        if (npc.playerLike()) {
            var player = loader.loadPlayerRig(npc.mesh(), npc.textures(), outfit == null ? Outfit.BARE : outfit);
            if (player == null) {
                throw new IOException(npc.mesh() + " cannot be built from this client's player parts.");
            }
            var input = PlayerRigInput.of(player);
            bones = input.bones();
            points = input.points();
            influences = input.influences();
            wedges = input.wedges();
            faces = input.faces();
            flags = input.flags();
            drawn = player.model().entityMesh();
            animation = player.animation();
            var drawScale = npc.id() == 0
                    ? loader.loadNpcBody(PlayerFigures.getPawnClass(figureOf(npc, outfit)))
                            .drawScale()
                    : npc.drawScale();
            placement = computePlacement(player.frame(), player.model().lowest(), drawScale);
        } else {
            var rig = loader.loadNpcRig(npc.mesh(), npc.textures(), outfit == null ? List.of() : outfit.held());
            if (rig == null) {
                throw new IOException(npc.mesh() + " cannot be read from this client.");
            }
            var mesh = rig.mesh();
            if (rig.parts().isEmpty()) {
                bones = mesh.getBones();
                points = mesh.getPoints();
                influences = mesh.getInfluences();
                wedges = mesh.getWedges();
                faces = PlayerRigInput.toSectionFaces(mesh);
                flags = rig.materialFlags();
            } else {
                var input = PlayerRigInput.of(mesh, rig.parts());
                bones = input.bones();
                points = input.points();
                influences = input.influences();
                wedges = input.wedges();
                faces = input.faces();
                flags = input.flags();
            }
            drawn = rig.model().entityMesh();
            animation = rig.animation();
            placement = computePlacement(mesh, rig.model().lowest(), npc.drawScale());
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("export cancelled");
        }
        var animations = new ArrayList<Animation>();
        if (animation != null) {
            for (var index = 0; index < animation.getSequences().length; index++) {
                var sequence = animation.getSequences()[index];
                try {
                    animations.add(new Animation(
                            sequence.name(), sequence.rate(), animation.decodeMotion(index), animation.getBones()));
                } catch (RuntimeException unreadable) {
                }
            }
        }
        progress.update("Building the skeleton and " + animations.size() + " animations", .3);
        var model = RigBuilder.build(
                npc.label(), bones, points, influences, wedges, faces, flags, drawn, animations, placement, scale);
        return encoded(model, progress, cancelled);
    }

    private static Built encoded(RigModel model, Progress progress, BooleanSupplier cancelled) {
        progress.update("Encoding " + model.images().size() + " textures", .6);
        var png = new ArrayList<byte[]>(model.images().size());
        for (var image : model.images()) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("export cancelled");
            }
            var rgba = image.rgba().get();
            png.add(PngWriter.encode(image.width(), image.height(), rgba, PngWriter.hasAlpha(rgba)));
        }
        var names = new ArrayList<String>(model.clips().size());
        for (var clip : model.clips()) {
            names.add(clip.name());
        }
        var triangles = 0L;
        for (var primitive : model.primitives()) {
            triangles += primitive.indices().length / 3;
        }
        return new Built(
                model,
                png,
                new Rig(
                        model.joints().size(),
                        List.copyOf(names),
                        triangles,
                        model.images().size()));
    }

    private static String buildInfoText(Npc npc, Rig rig, ExportScale scale) {
        var text = new StringBuilder();
        text.append("Cruma NPC export\n\n");
        text.append("NPC: ").append(npc.label());
        if (!npc.title().isBlank()) {
            text.append(" (").append(npc.title()).append(')');
        }
        text.append(", id ").append(npc.id()).append('\n');
        text.append("Mesh: ").append(npc.mesh()).append('\n');
        text.append("Bones: ")
                .append(rig.bones())
                .append(", triangles: ")
                .append(rig.triangles())
                .append(", textures: ")
                .append(rig.textures())
                .append("\n\n");
        text.append("Animations (")
                .append(rig.animations().size())
                .append("), at the client's 30 frames per second:\n");
        text.append(String.join(", ", rig.animations())).append("\n\n");
        text.append("Scale: ").append(scale.describe()).append(".\n");
        text.append(
                "A location export made with the same scale fits this one. The figure stands on (0, 0, 0) in its idle"
                        + " pose.\n");
        text.append("Axes: Y is up in the file, as glTF defines. Blender turns it to Z up on import.\n");
        text.append("In Blender each animation is an action of the armature: pick it in the Action Editor or the NLA"
                + " Editor.\n");
        return text.toString();
    }

    private static String figureOf(Npc npc, @Nullable Outfit outfit) {
        return outfit == null || outfit.figure() == null ? PlayerFigures.figureOf(npc.mesh()) : outfit.figure();
    }
}
