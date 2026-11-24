package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.BeastCalibration.Agreement;
import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.NativeTerrainLayerPasses.Weights;
import com.rost.cruma.core.editing.NativeTerrainLayerPasses.Words;
import com.rost.cruma.core.editing.TerrainLightingRebuild.Result;
import com.rost.cruma.core.editing.TerrainLightingRebuild.Stats;
import com.rost.cruma.core.editing.TerrainLightingScene.MeshActor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainNativeCodec.Sector;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainPaintHistory.Edit;
import com.rost.cruma.core.editing.TerrainPaintSources.DrawnLayers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainEditExport {
    public static final String PROFILE_ID = "terrain-lit-v1";

    private TerrainEditExport() {}

    public record ReplacedPackage(Path source, String sourceSha256, String filename, byte[] bytes) {
        public ReplacedPackage {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Report(
            String owner,
            Stats lighting,
            String heightPackage,
            List<String> heightSharedWith,
            int bspSurfaces,
            List<MeshActor> meshActors,
            Box area,
            List<String> warnings,
            @Nullable BeastStats beast,
            List<PaintedLayer> paintedLayers,
            int layerPassSectors,
            List<Integer> mappedLayers) {
        public Report {
            heightSharedWith = List.copyOf(heightSharedWith);
            meshActors = List.copyOf(meshActors);
            warnings = List.copyOf(warnings);
            paintedLayers = List.copyOf(paintedLayers);
            mappedLayers = List.copyOf(mappedLayers);
        }

        public Report(
                String owner,
                Stats lighting,
                String heightPackage,
                List<String> heightSharedWith,
                int bspSurfaces,
                List<MeshActor> meshActors,
                Box area,
                List<String> warnings,
                @Nullable BeastStats beast,
                List<PaintedLayer> paintedLayers,
                int layerPassSectors) {
            this(
                    owner,
                    lighting,
                    heightPackage,
                    heightSharedWith,
                    bspSurfaces,
                    meshActors,
                    area,
                    warnings,
                    beast,
                    paintedLayers,
                    layerPassSectors,
                    List.of());
        }

        public Report(
                String owner,
                Stats lighting,
                String heightPackage,
                List<String> heightSharedWith,
                int bspSurfaces,
                List<MeshActor> meshActors,
                Box area,
                List<String> warnings,
                @Nullable BeastStats beast,
                List<PaintedLayer> paintedLayers) {
            this(
                    owner,
                    lighting,
                    heightPackage,
                    heightSharedWith,
                    bspSurfaces,
                    meshActors,
                    area,
                    warnings,
                    beast,
                    paintedLayers,
                    0);
        }

        public Report(
                String owner,
                Stats lighting,
                String heightPackage,
                List<String> heightSharedWith,
                int bspSurfaces,
                List<MeshActor> meshActors,
                Box area,
                List<String> warnings,
                @Nullable BeastStats beast) {
            this(
                    owner,
                    lighting,
                    heightPackage,
                    heightSharedWith,
                    bspSurfaces,
                    meshActors,
                    area,
                    warnings,
                    beast,
                    List.of());
        }

        public Report(
                String owner,
                Stats lighting,
                String heightPackage,
                List<String> heightSharedWith,
                int bspSurfaces,
                List<MeshActor> meshActors,
                Box area,
                List<String> warnings) {
            this(owner, lighting, heightPackage, heightSharedWith, bspSurfaces, meshActors, area, warnings, null);
        }
    }

    public record PaintedLayer(int layer, String weightTexture, int changedTexels) {}

    public record OwnerEdits(
            Map<Integer, TerrainLayerMapping> mappings,
            @Nullable BitSet visibleQuads,
            @Nullable BitSet edgeTurns) {
        public static final OwnerEdits NONE = new OwnerEdits(Map.of());

        public OwnerEdits {
            mappings = Map.copyOf(mappings);
            if (visibleQuads == null != (edgeTurns == null)) {
                throw new IllegalArgumentException("A Terrain quad edit needs both bitmaps");
            }
            visibleQuads = visibleQuads == null ? null : (BitSet) visibleQuads.clone();
            edgeTurns = edgeTurns == null ? null : (BitSet) edgeTurns.clone();
        }

        public OwnerEdits(Map<Integer, TerrainLayerMapping> mappings) {
            this(mappings, null, null);
        }

        @Override
        public @Nullable BitSet visibleQuads() {
            return visibleQuads == null ? null : (BitSet) visibleQuads.clone();
        }

        @Override
        public @Nullable BitSet edgeTurns() {
            return edgeTurns == null ? null : (BitSet) edgeTurns.clone();
        }

        public boolean hasQuads() {
            return visibleQuads != null;
        }

        public boolean isEmpty() {
            return mappings.isEmpty() && !hasQuads();
        }
    }

    public record BeastStats(
            int sectors,
            int texels,
            int changedTexels,
            Agreement slot0,
            Agreement slot1,
            int samples,
            boolean keptFlat) {}

    public static final class Prepared {
        private final NativePackageChanges changes;
        private final Optional<ReplacedPackage> height;
        private final Report report;
        private final Path map;
        private final String mapSha256;
        private final TerrainChange bspChange;
        private final int actorShadowVertices;

        private Prepared(
                NativePackageChanges changes,
                Optional<ReplacedPackage> height,
                Report report,
                Path map,
                String mapSha256,
                TerrainChange bspChange,
                int actorShadowVertices) {
            this.changes = changes;
            this.height = height;
            this.report = report;
            this.map = map;
            this.mapSha256 = mapSha256;
            this.bspChange = bspChange;
            this.actorShadowVertices = actorShadowVertices;
        }

        public int getActorShadowVertices() {
            return actorShadowVertices;
        }

        public Path getMap() {
            return map;
        }

        public TerrainChange getBspChange() {
            return bspChange;
        }

        public NativePackageChanges getChanges() {
            return changes;
        }

        public Optional<ReplacedPackage> getHeightPackage() {
            return height;
        }

        public Report getReport() {
            return report;
        }

        public void verifySourcesUnchanged() throws IOException {
            if (!sha256(Files.readAllBytes(map)).equals(mapSha256)) {
                throw new IOException("Terrain source map changed after validation: " + map.getFileName());
            }
            if (height.isPresent()
                    && !sha256(Files.readAllBytes(height.orElseThrow().source()))
                            .equals(height.orElseThrow().sourceSha256())) {
                throw new IOException("Terrain height package changed after validation: "
                        + height.orElseThrow().filename());
            }
        }
    }

    public static String findRestriction(Terrain terrain) {
        if (terrain.heightmap().isEmpty()) {
            return terrain.editRestrictions().isEmpty()
                    ? "Cruma could not read the Terrain heights"
                    : "Cruma could not read the Terrain heights: "
                            + terrain.editRestrictions().getFirst();
        }
        var info = terrain.info();
        if (info.width() != 256
                || info.height() != 256
                || info.sectorsX() != 16
                || info.sectorsY() != 16
                || terrain.sectors().size() != 256) {
            return "Only 256 by 256 Terrain with 16 by 16 sectors can be edited";
        }
        for (var sector : terrain.sectors()) {
            if (sector.sizeX() != 16 && !(sector.offsetX() == 240 && sector.sizeX() == 15)
                    || sector.sizeY() != 16 && !(sector.offsetY() == 240 && sector.sizeY() == 15)) {
                return "This Terrain has sectors of an unknown size";
            }
        }
        var colors = info.vertexColors().size();
        if (colors != 0 && colors != 256 * 256 * 4) {
            return "This Terrain has saved vertex colours of an unknown size";
        }
        return NativeTerrainSavedGeometry.findRestriction(info);
    }

    public static Prepared prepare(
            TerrainPackageSnapshot snapshot,
            TerrainLightingScene scene,
            String ownerPath,
            TerrainHeightPatch patch,
            List<String> sharedWith,
            BooleanSupplier cancelled)
            throws IOException {
        return prepare(snapshot, scene, ownerPath, patch, sharedWith, TerrainActorShadows.NONE, cancelled);
    }

    public static Prepared prepare(
            TerrainPackageSnapshot snapshot,
            TerrainLightingScene scene,
            String ownerPath,
            TerrainHeightPatch patch,
            List<String> sharedWith,
            TerrainActorShadows actors,
            BooleanSupplier cancelled)
            throws IOException {
        return prepare(snapshot, scene, ownerPath, patch, List.of(), sharedWith, actors, cancelled);
    }

    public static Prepared prepare(
            TerrainPackageSnapshot snapshot,
            TerrainLightingScene scene,
            String ownerPath,
            TerrainHeightPatch patch,
            List<Edit> paint,
            List<String> sharedWith,
            TerrainActorShadows actors,
            BooleanSupplier cancelled)
            throws IOException {
        return prepare(snapshot, scene, ownerPath, patch, paint, OwnerEdits.NONE, sharedWith, actors, cancelled);
    }

    public static Prepared prepare(
            TerrainPackageSnapshot snapshot,
            TerrainLightingScene scene,
            String ownerPath,
            TerrainHeightPatch patch,
            List<Edit> paint,
            OwnerEdits ownerEdits,
            List<String> sharedWith,
            TerrainActorShadows actors,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(actors);
        Objects.requireNonNull(paint);
        Objects.requireNonNull(ownerEdits);
        Objects.requireNonNull(cancelled);
        var terrain = snapshot.getTerrains().stream()
                .filter(t -> t.path().equals(ownerPath))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown Terrain owner " + ownerPath));
        var restriction = findRestriction(terrain);
        if (!restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        if (!scene.getOwnerPath().equals(ownerPath)) {
            throw new IOException("The lighting scene belongs to another Terrain owner");
        }
        var source = snapshot.getPackageBytes();
        var mapPath = snapshot.getSourcePath();
        if (!sha256(Files.readAllBytes(mapPath)).equals(snapshot.getOriginalSha256())) {
            throw new IOException("The source map changed. Close and open the Terrain again before exporting");
        }
        var base = terrain.heightmap().getFirst();
        var original = new TerrainHeightField(base, terrain.info().toWorld());
        var edited = new TerrainHeightField(base, terrain.info().toWorld());
        try {
            edited.apply(patch, false);
        } catch (IllegalArgumentException | IllegalStateException stale) {
            throw new IOException(
                    "The Terrain edit no longer applies to the source heights: " + stale.getMessage(), stale);
        }
        var savedTime = snapshot.findAuthoredTimeMaps(ownerPath);
        var after = scene;
        if (ownerEdits.hasQuads()) {
            after = scene.withQuads(ownerEdits.visibleQuads(), ownerEdits.edgeTurns());
        }
        var changedQuads = scene.getVisibleQuads();
        changedQuads.xor(after.getVisibleQuads());
        var changedTurns = scene.getEdgeTurns();
        changedTurns.xor(after.getEdgeTurns());
        changedQuads.or(changedTurns);
        var shadows = actors.select(after, edited, cancelled);
        var rebuild =
                TerrainLightingRebuild.rebuild(scene, after, terrain, savedTime, original, edited, shadows, cancelled);

        var replacements = new LinkedHashMap<Integer, byte[]>();
        var relocated = new HashSet<Integer>();
        var expectedSectors = new ArrayList<>(terrain.sectors());
        for (var entry : rebuild.sectors().entrySet()) {
            var exportIndex = terrain.info().sectors().get(entry.getKey());
            var payload = snapshot.getPayloadWithTail(
                    exportIndex,
                    TerrainNativeCodec.writeSector(
                            snapshot.getFileVersion(), snapshot.getLicenseVersion(), entry.getValue()));
            expectedSectors.set(entry.getKey(), entry.getValue());
            var sectorEntry = source.exports.get(exportIndex - 1);
            if (Arrays.equals(
                    payload,
                    0,
                    payload.length,
                    source.body,
                    sectorEntry.serialOffset(),
                    sectorEntry.serialOffset() + sectorEntry.serialSize())) {
                continue;
            }
            replacements.put(exportIndex, payload);
            if (payload.length != sectorEntry.serialSize()) {
                relocated.add(exportIndex);
            }
        }
        var geometry = NativeTerrainSavedGeometry.rebuild(
                terrain.info().savedGeometry(), original, edited, scene.getEdgeTurns(), after.getEdgeTurns());
        var expectedInfo = terrain.info().withLighting(rebuild.colors(), geometry);
        var ownerPayload = snapshot.getPayloadWithTail(
                terrain.exportIndex(),
                TerrainNativeCodec.writeInfo(snapshot.getFileVersion(), snapshot.getLicenseVersion(), expectedInfo));
        var ownerEntry = source.exports.get(terrain.exportIndex() - 1);
        byte[] expectedTime = null;
        if (rebuild.time().isPresent() && savedTime.isPresent()) {
            var span = snapshot.findTimeMapSpan(ownerPath).orElseThrow();
            expectedTime = rebuild.time().orElseThrow().getOriginalBytes();
            if (expectedTime.length != span[1]) {
                throw new IOException("Rebuilt time maps changed their saved size");
            }
            System.arraycopy(expectedTime, 0, ownerPayload, span[0] - ownerEntry.serialOffset(), expectedTime.length);
        }
        var expectedMatrices = new LinkedHashMap<Integer, float[]>();
        if (!ownerEdits.mappings().isEmpty()) {
            if (ownerPayload.length != ownerEntry.serialSize()) {
                throw new IOException(
                        "The rebuilt lighting changes the size of the Terrain owner, so its layer mapping cannot be"
                                + " written in the same export. Export the strokes first, then the mapping");
            }
            var fields = snapshot.getLayerFields(ownerPath);
            var toWorld = TerrainLayerMapping.coords(terrain.info().toWorld());
            var toHeightMap = TerrainLayerMapping.coords(terrain.info().toHeightmap());
            for (var entry : new TreeMap<>(ownerEdits.mappings()).entrySet()) {
                var layer = fields.stream()
                        .filter(each -> each.order() == entry.getKey())
                        .findFirst()
                        .orElseThrow(() -> new IOException("The Terrain has no layer " + entry.getKey()));
                var matrix = entry.getValue().matrix(toWorld, toHeightMap);
                TerrainPackageSnapshot.writeLayer(
                        ownerPayload, ownerEntry.serialOffset(), layer, entry.getValue(), matrix);
                expectedMatrices.put(entry.getKey(), matrix);
            }
        }
        if (ownerEdits.hasQuads()) {
            if (ownerPayload.length != ownerEntry.serialSize()) {
                throw new IOException(
                        "The rebuilt lighting changes the size of the Terrain owner, so its holes and turned edges"
                                + " cannot be written in the same export. Export the strokes first");
            }
            snapshot.writeQuads(
                    ownerPayload,
                    ownerEntry.serialOffset(),
                    ownerPath,
                    ownerEdits.visibleQuads(),
                    ownerEdits.edgeTurns());
        }
        if (!Arrays.equals(
                ownerPayload,
                0,
                ownerPayload.length,
                source.body,
                ownerEntry.serialOffset(),
                ownerEntry.serialOffset() + ownerEntry.serialSize())) {
            replacements.put(terrain.exportIndex(), ownerPayload);
            if (ownerPayload.length != ownerEntry.serialSize()) {
                relocated.add(terrain.exportIndex());
            }
        }
        var localPaint = paint.stream()
                .filter(edit -> TerrainPaintSources.holds(source, edit))
                .toList();
        var externalPaint = paint.stream()
                .filter(edit -> !TerrainPaintSources.holds(source, edit))
                .toList();
        var expectedLocalPaint = new ArrayList<byte[]>();
        byte[] paintedMap = null;
        PackageBytes heightAsset = null;
        byte[] paintedHeights = null;
        if (!localPaint.isEmpty()) {
            var body = source.body.clone();
            paintedMap = body;
            var textures = new LinkedHashMap<Integer, Boolean>();
            for (var edit : localPaint) {
                textures.put(TerrainPaintSources.write(source, body, edit).getExportIndex(), true);
            }
            for (var index : textures.keySet()) {
                var entry = source.exports.get(index - 1);
                replacements.put(
                        index,
                        Arrays.copyOfRange(body, entry.serialOffset(), entry.serialOffset() + entry.serialSize()));
            }
            var written = new PackageBytes(mapPath, source.encode(body, mapPath));
            for (var edit : localPaint) {
                expectedLocalPaint.add(TerrainPaintSources.reread(written, edit));
            }
        }
        Optional<ReplacedPackage> heightPackage = Optional.empty();
        var heightName = "";
        if (!externalPaint.isEmpty() && terrain.heightmapReference() > 0) {
            throw new IOException("A painted layer keeps its weights outside the map and its height package");
        }
        if (terrain.heightmapReference() > 0 && patch.size() > 0) {
            var texture = source.exports.get(terrain.heightmapReference() - 1);
            var payload = Arrays.copyOfRange(
                    source.body, texture.serialOffset(), texture.serialOffset() + texture.serialSize());
            patchSamples(payload, base.dataOffset() - texture.serialOffset(), patch);
            replacements.put(texture.index(), payload);
        } else if (patch.size() > 0 || !externalPaint.isEmpty()) {
            var height = snapshot.findHeightSource(ownerPath)
                    .orElseThrow(() -> new IOException("The height texture package was not found"));
            var bytes = Files.readAllBytes(height.path());
            if (!sha256(bytes).equals(height.sha256())) {
                throw new IOException(
                        "The height texture package changed. Close and open the Terrain again before exporting");
            }
            var asset = new PackageBytes(height.path(), bytes);
            var body = asset.body.clone();
            heightAsset = asset;
            paintedHeights = body;
            patchSamples(body, base.dataOffset(), patch);
            var spans = new ArrayList<int[]>();
            for (var edit : externalPaint) {
                if (!TerrainPaintSources.holds(asset, edit)) {
                    throw new IOException("A painted layer keeps its weights outside the map and its height package");
                }
                var texture = TerrainPaintSources.write(asset, body, edit);
                spans.add(new int[] {texture.getDataOffset(), texture.getDataOffset() + texture.getDataLength()});
            }
            var output = asset.encode(body, height.path());
            verifyHeightPackage(
                    asset,
                    output,
                    height.path(),
                    terrain.heightmapObjectPath(),
                    edited,
                    base.dataOffset(),
                    patch,
                    spans);
            var reread = new PackageBytes(height.path(), output);
            var expected = new PackageBytes(height.path(), asset.encode(body, height.path()));
            for (var edit : externalPaint) {
                if (!Arrays.equals(
                        TerrainPaintSources.reread(reread, edit), TerrainPaintSources.reread(expected, edit))) {
                    throw new IOException("The painted weights did not reopen as written");
                }
            }
            heightName = height.path().getFileName().toString();
            heightPackage = Optional.of(new ReplacedPackage(height.path(), height.sha256(), heightName, output));
        }
        var passes = paint.isEmpty() && changedQuads.isEmpty()
                ? PassChange.NONE
                : paintedPasses(
                        snapshot, terrain, scene, after, changedQuads, paint, paintedMap, heightAsset, paintedHeights);
        var passSectors = 0;
        for (var entry : passes.sectors().entrySet()) {
            var index = entry.getKey();
            var exportIndex = terrain.info().sectors().get(index);
            var unpainted = expectedSectors.get(index);
            var sector =
                    entry.getValue().after().applyTo(unpainted, entry.getValue().before());
            if (sector.equals(unpainted)) {
                continue;
            }
            passSectors++;
            var payload = snapshot.getPayloadWithTail(
                    exportIndex,
                    TerrainNativeCodec.writeSector(snapshot.getFileVersion(), snapshot.getLicenseVersion(), sector));
            expectedSectors.set(index, sector);
            var sectorEntry = source.exports.get(exportIndex - 1);
            if (Arrays.equals(
                    payload,
                    0,
                    payload.length,
                    source.body,
                    sectorEntry.serialOffset(),
                    sectorEntry.serialOffset() + sectorEntry.serialSize())) {
                replacements.remove(exportIndex);
                relocated.remove(exportIndex);
                continue;
            }
            replacements.put(exportIndex, payload);
            if (payload.length != sectorEntry.serialSize()) {
                relocated.add(exportIndex);
            } else {
                relocated.remove(exportIndex);
            }
        }
        BeastTerrainRebake.Result beast = null;
        var beastLightmaps = terrain.info().beastLightmaps().isPresent()
                && terrain.info().beastLightmaps().orElseThrow().references().stream()
                        .anyMatch(reference -> reference != 0);
        if (beastLightmaps && scene.getBeast() == null) {
            throw new IOException("This Terrain is lit by Beast lightmaps that the lighting scene could not read");
        }
        if (scene.getBeast() != null && patch.size() > 0) {
            var calibrated = scene.getBeast();
            beast = BeastTerrainRebake.rebake(
                    calibrated,
                    original,
                    edited,
                    after.getEdgeTurns(),
                    after.getVisibleQuads(),
                    scene.isInverted(),
                    actors.getBeast(),
                    cancelled);
            for (var slot = 0; slot < 2; slot++) {
                var index = slot == 0 ? calibrated.slot0Export() : calibrated.slot1Export();
                var texture = slot == 0 ? beast.slot0() : beast.slot1();
                var entry = source.exports.get(index - 1);
                var payload = BeastTextureCodec.writeBody(
                        BeastTextureCodec.readTemplate(source, entry),
                        texture.width(),
                        texture.height(),
                        texture.mips(),
                        entry.serialOffset());
                if (payload.length != entry.serialSize()) {
                    throw new IOException("A rebuilt Beast lightmap changed its size");
                }
                if (!Arrays.equals(
                        payload,
                        0,
                        payload.length,
                        source.body,
                        entry.serialOffset(),
                        entry.serialOffset() + entry.serialSize())) {
                    replacements.put(index, payload);
                }
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Terrain export cancelled");
        }
        final var time = expectedTime;
        var localHeights = terrain.heightmapReference() > 0;
        var expectedHeights = edited;
        var changes = NativePackageChanges.create(
                mapPath,
                snapshot.getOriginalSha256(),
                source,
                replacements,
                Set.copyOf(relocated),
                (target, encoded) -> {
                    verifyMap(
                            target,
                            encoded,
                            terrain.exportIndex(),
                            expectedInfo,
                            expectedSectors,
                            time,
                            localHeights ? expectedHeights : null);
                    verifyLayers(target, encoded, terrain.exportIndex(), ownerEdits.mappings(), expectedMatrices);
                    verifyQuads(target, encoded, terrain.exportIndex(), ownerEdits);
                    var written =
                            TerrainPackageSnapshot.fromBytes(target, encoded).getPackageBytes();
                    for (var i = 0; i < localPaint.size(); i++) {
                        if (!Arrays.equals(
                                TerrainPaintSources.reread(written, localPaint.get(i)), expectedLocalPaint.get(i))) {
                            throw new IOException("The painted weights did not reopen as written");
                        }
                    }
                });
        var report = report(
                scene,
                terrain,
                original,
                edited,
                rebuild,
                heightName,
                sharedWith,
                snapshot,
                beast,
                paint,
                passes.unexplained(),
                passSectors,
                List.copyOf(new TreeMap<>(ownerEdits.mappings()).keySet()),
                quadWarnings(scene, after, terrain, patch));
        var bspChange = BspLightmapRebuild.terrainChange(ownerPath, original, edited, patch);
        if (!changedQuads.isEmpty()) {
            var box = bspChange.box();
            for (var quad = changedQuads.nextSetBit(0); quad >= 0; quad = changedQuads.nextSetBit(quad + 1)) {
                for (var corner = 0; corner < 4; corner++) {
                    var x = Math.min(edited.getWidth() - 1, quad % edited.getWidth() + (corner & 1));
                    var y = Math.min(edited.getHeight() - 1, quad / edited.getWidth() + (corner >> 1));
                    for (var field : List.of(original, edited)) {
                        var point = field.getWorldAt(x, y);
                        box[0] = Math.min(box[0], point.x());
                        box[1] = Math.min(box[1], point.y());
                        box[2] = Math.min(box[2], point.z());
                        box[3] = Math.max(box[3], point.x());
                        box[4] = Math.max(box[4], point.y());
                        box[5] = Math.max(box[5], point.z());
                    }
                }
            }
            bspChange =
                    new TerrainChange(ownerPath, edited.withQuads(after.getVisibleQuads(), after.getEdgeTurns()), box);
        }
        return new Prepared(
                changes,
                heightPackage,
                report,
                mapPath,
                snapshot.getOriginalSha256(),
                bspChange,
                shadows.getVertexCount());
    }

    private record SectorPasses(Words before, Words after) {}

    private record PassChange(Map<Integer, SectorPasses> sectors, int unexplained) {
        static final PassChange NONE = new PassChange(Map.of(), 0);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static PassChange paintedPasses(
            TerrainPackageSnapshot snapshot,
            Terrain terrain,
            TerrainLightingScene scene,
            TerrainLightingScene edited,
            BitSet changedQuads,
            List<Edit> paint,
            byte @Nullable [] paintedMap,
            @Nullable PackageBytes heights,
            byte @Nullable [] paintedHeights)
            throws IOException {
        if (!TerrainPaintSources.savesLayerPasses(snapshot, terrain.path())) {
            return PassChange.NONE;
        }
        DrawnLayers drawn;
        try {
            drawn = TerrainPaintSources.drawnLayers(snapshot, terrain.path(), heights);
        } catch (IOException unreadable) {
            throw new IOException(
                    "Cruma cannot rebuild which layers the game draws: " + unreadable.getMessage(), unreadable);
        }
        var paths = drawn.getPaths();
        for (var edit : paint) {
            var layer = edit.layer().index();
            if (layer >= paths.size()
                    || !paths.get(layer).equalsIgnoreCase(edit.layer().weightTexture())) {
                throw new IOException("The painted layer " + layer + " is not a layer that the game draws");
            }
        }
        var before = drawn.read(null, null);
        var after = drawn.read(paintedMap, paintedHeights);
        var samples = terrain.info().width();
        var visible = scene.getVisibleQuads();
        var turned = scene.getEdgeTurns();
        var newVisible = edited.getVisibleQuads();
        var newTurned = edited.getEdgeTurns();
        var boxes = changedTexels(before, after);
        var changes = new LinkedHashMap<Integer, SectorPasses>();
        var unexplained = 0;
        for (var index = 0; index < terrain.sectors().size(); index++) {
            var sector = terrain.sectors().get(index);
            if (!readsChange(boxes, after, samples, sector) && !holdsQuad(changedQuads, samples, sector)) {
                continue;
            }
            var old = NativeTerrainLayerPasses.build(before, visible, turned, samples, sector);
            var now = NativeTerrainLayerPasses.build(after, newVisible, newTurned, samples, sector);
            if (now.equals(old)) {
                continue;
            }
            if (!now.layers().equals(old.layers()) && !old.matches(sector)) {
                unexplained++;
            }
            changes.put(index, new SectorPasses(old, now));
        }
        return new PassChange(Map.copyOf(changes), unexplained);
    }

    private static int[][] changedTexels(List<Weights> before, List<Weights> after) {
        var boxes = new int[before.size()][];
        for (var layer = 0; layer < before.size(); layer++) {
            var a = before.get(layer);
            var b = after.get(layer);
            if (Arrays.equals(a.values(), b.values())) {
                continue;
            }
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            for (var y = 0; y < a.height(); y++) {
                for (var x = 0; x < a.width(); x++) {
                    if (a.at(x, y) != b.at(x, y)) {
                        x0 = Math.min(x0, x);
                        y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x);
                        y1 = Math.max(y1, y);
                    }
                }
            }
            boxes[layer] = new int[] {x0, y0, x1, y1};
        }
        return boxes;
    }

    private static boolean holdsQuad(BitSet quads, int samples, Sector sector) {
        var sx = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), samples);
        var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), samples);
        for (var y = sector.offsetY(); y < sector.offsetY() + sy; y++) {
            var row = y * samples + sector.offsetX();
            var next = quads.nextSetBit(row);
            if (next >= 0 && next < row + sx) {
                return true;
            }
        }
        return false;
    }

    private static boolean readsChange(int[][] boxes, List<Weights> after, int samples, Sector sector) {
        for (var layer = 0; layer < boxes.length; layer++) {
            var box = boxes[layer];
            if (box != null
                    && NativeTerrainLayerPasses.reads(
                            after.get(layer), samples, sector, box[0], box[1], box[2], box[3])) {
                return true;
            }
        }
        return false;
    }

    private static void patchSamples(byte[] body, int dataOffset, TerrainHeightPatch patch) throws IOException {
        for (var i = 0; i < patch.size(); i++) {
            var offset = Math.addExact(dataOffset, patch.getIndex(i) * 2);
            if (offset < 0 || offset + 1 >= body.length) {
                throw new IOException("Height sample lies outside its texture");
            }
            var before = (body[offset] & 0xff) | (body[offset + 1] & 0xff) << 8;
            if (before != patch.getBefore(i)) {
                throw new IOException("The saved height differs from the edit's starting height");
            }
            body[offset] = (byte) patch.getAfter(i);
            body[offset + 1] = (byte) (patch.getAfter(i) >>> 8);
        }
    }

    private static void verifyHeightPackage(
            PackageBytes source,
            byte[] output,
            Path path,
            String objectPath,
            TerrainHeightField expected,
            int dataOffset,
            TerrainHeightPatch patch,
            List<int[]> paintedSpans)
            throws IOException {
        var reread = new PackageBytes(path, output);
        var heights = TerrainPackageSnapshot.readHeights(reread, objectPath).getFirst();
        for (var y = 0; y < expected.getHeight(); y++) {
            for (var x = 0; x < expected.getWidth(); x++) {
                if (heights.heightAt(x, y) != expected.getHeightAt(x, y)) {
                    throw new IOException("The changed height texture did not reopen with the edited heights");
                }
            }
        }
        var changed = new HashSet<Integer>();
        for (var i = 0; i < patch.size(); i++) {
            changed.add(dataOffset + patch.getIndex(i) * 2);
            changed.add(dataOffset + patch.getIndex(i) * 2 + 1);
        }
        if (reread.body.length != source.body.length) {
            throw new IOException("The changed height texture has a different size");
        }
        for (var i = 0; i < source.body.length; i++) {
            if (source.body[i] != reread.body[i] && !changed.contains(i) && !isInside(paintedSpans, i)) {
                throw new IOException(
                        "The changed height texture differs outside the edited samples and painted weights");
            }
        }
    }

    private static boolean isInside(List<int[]> spans, int offset) {
        for (var span : spans) {
            if (offset >= span[0] && offset < span[1]) {
                return true;
            }
        }
        return false;
    }

    private static void verifyMap(
            Path target,
            byte[] encoded,
            int ownerExport,
            Info expectedInfo,
            List<Sector> expectedSectors,
            byte[] expectedTime,
            TerrainHeightField localHeights)
            throws IOException {
        var reread = TerrainPackageSnapshot.fromBytes(target, encoded);
        var owner = reread.getTerrains().stream()
                .filter(t -> t.exportIndex() == ownerExport)
                .findFirst()
                .orElseThrow(() -> new IOException("The prepared map lost its Terrain owner"));
        if (!owner.info().equals(expectedInfo) || !owner.sectors().equals(expectedSectors)) {
            throw new IOException("The prepared Terrain lighting or bounds did not reopen as written");
        }
        if (expectedTime != null) {
            var time = reread.findAuthoredTimeMaps(owner.path())
                    .orElseThrow(() -> new IOException("The prepared map lost its time maps"));
            if (!Arrays.equals(time.getOriginalBytes(), expectedTime)) {
                throw new IOException("The prepared time maps did not reopen as written");
            }
        }
        if (localHeights != null) {
            var heights = owner.heightmap().getFirst();
            for (var y = 0; y < localHeights.getHeight(); y++) {
                for (var x = 0; x < localHeights.getWidth(); x++) {
                    if (heights.heightAt(x, y) != localHeights.getHeightAt(x, y)) {
                        throw new IOException("The prepared map did not reopen with the edited heights");
                    }
                }
            }
        }
    }

    private static List<String> quadWarnings(
            TerrainLightingScene scene, TerrainLightingScene after, Terrain terrain, TerrainHeightPatch patch) {
        var hidden = scene.getVisibleQuads();
        hidden.andNot(after.getVisibleQuads());
        var shown = after.getVisibleQuads();
        shown.andNot(scene.getVisibleQuads());
        var turned = scene.getEdgeTurns();
        turned.xor(after.getEdgeTurns());
        if (hidden.isEmpty() && shown.isEmpty() && turned.isEmpty()) {
            return List.of();
        }
        var warnings = new ArrayList<String>();
        var parts = new ArrayList<String>();
        if (!hidden.isEmpty()) {
            parts.add(hidden.cardinality() + (hidden.cardinality() == 1 ? " quad hidden" : " quads hidden"));
        }
        if (!shown.isEmpty()) {
            parts.add(shown.cardinality() + (shown.cardinality() == 1 ? " quad shown" : " quads shown"));
        }
        if (!turned.isEmpty()) {
            parts.add(turned.cardinality() + (turned.cardinality() == 1 ? " quad turned" : " quads turned"));
        }
        warnings.add(String.join(", ", parts) + " in " + terrain.path()
                + ". Generate the geodata of this map again: the server's geodata does not know the new ground");
        var beast = terrain.info()
                .beastLightmaps()
                .map(maps -> maps.references().stream().anyMatch(reference -> reference != 0))
                .orElse(false);
        if (beast && patch.size() == 0) {
            warnings.add("The Beast lightmap of this Terrain keeps the old holes and edges, because no height changed");
        }
        return List.copyOf(warnings);
    }

    private static String ownerOf(TerrainPackageSnapshot reread, int ownerExport) throws IOException {
        return reread.getTerrains().stream()
                .filter(each -> each.exportIndex() == ownerExport)
                .findFirst()
                .orElseThrow(() -> new IOException("The prepared map lost its Terrain owner"))
                .path();
    }

    private static void verifyQuads(Path target, byte[] encoded, int ownerExport, OwnerEdits edits) throws IOException {
        if (!edits.hasQuads()) {
            return;
        }
        var reread = TerrainPackageSnapshot.fromBytes(target, encoded);
        var bitmaps = reread.getQuadBitmaps(ownerOf(reread, ownerExport));
        if (!bitmaps[0].equals(edits.visibleQuads()) || !bitmaps[1].equals(edits.edgeTurns())) {
            throw new IOException("The prepared Terrain did not reopen with its new holes and turned edges");
        }
    }

    private static void verifyLayers(
            Path target,
            byte[] encoded,
            int ownerExport,
            Map<Integer, TerrainLayerMapping> mappings,
            Map<Integer, float[]> matrices)
            throws IOException {
        if (mappings.isEmpty()) {
            return;
        }
        var reread = TerrainPackageSnapshot.fromBytes(target, encoded);
        var fields = reread.getLayerFields(ownerOf(reread, ownerExport));
        for (var entry : mappings.entrySet()) {
            var layer = fields.stream()
                    .filter(each -> each.order() == entry.getKey())
                    .findFirst()
                    .orElseThrow(() -> new IOException("The prepared map lost Terrain layer " + entry.getKey()));
            if (!entry.getValue().equals(layer.mapping())
                    || !Arrays.equals(matrices.get(entry.getKey()), layer.matrix())) {
                throw new IOException(
                        "The prepared Terrain layer " + entry.getKey() + " did not reopen with its new mapping");
            }
        }
    }

    private static Report report(
            TerrainLightingScene scene,
            Terrain terrain,
            TerrainHeightField original,
            TerrainHeightField edited,
            Result rebuild,
            String heightName,
            List<String> sharedWith,
            TerrainPackageSnapshot snapshot,
            @Nullable BeastTerrainRebake.Result beast,
            List<Edit> paint,
            int unexplainedPasses,
            int passSectors,
            List<Integer> mappedLayers,
            List<String> quadWarnings) {
        var warnings = new ArrayList<String>(quadWarnings);
        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var index : rebuild.sectors().keySet()) {
            var sector = terrain.sectors().get(index);
            var sx = TerrainLightingRebuild.effectiveSize(sector.offsetX(), sector.sizeX(), original.getWidth());
            var sy = TerrainLightingRebuild.effectiveSize(sector.offsetY(), sector.sizeY(), original.getHeight());
            if (!holdsChange(sector, sx, sy, rebuild.changedVertices(), original.getWidth())) {
                continue;
            }
            for (var field : List.of(original, edited)) {
                var bounds = field.getBounds(sector.offsetX(), sector.offsetY(), sx, sy);
                minX = Math.min(minX, bounds.min().x());
                minY = Math.min(minY, bounds.min().y());
                minZ = Math.min(minZ, bounds.min().z());
                maxX = Math.max(maxX, bounds.max().x());
                maxY = Math.max(maxY, bounds.max().y());
                maxZ = Math.max(maxZ, bounds.max().z());
            }
        }
        Box area = null;
        var bsp = 0;
        var meshes = new ArrayList<MeshActor>();
        if (minX <= maxX) {
            area = new Box(new Vector(minX, minY, minZ - 256f), new Vector(maxX, maxY, maxZ + 1024f), 1);
            for (var surface : scene.getBspSurfaces()) {
                if (intersects(surface, area)) {
                    bsp++;
                }
            }
            for (var actor : scene.getMeshActors()) {
                if (intersects(actor.bounds(), area)) {
                    meshes.add(actor);
                }
            }
        }
        var shared = new ArrayList<String>(sharedWith);
        for (var other : snapshot.getTerrains()) {
            if (!other.path().equals(terrain.path())
                    && other.heightmapObjectPath().equalsIgnoreCase(terrain.heightmapObjectPath())) {
                shared.add(other.path());
            }
        }
        if (!shared.isEmpty()) {
            warnings.add("The height texture is shared with " + String.join(", ", shared)
                    + ". Their Terrain changes too, but their lighting is not rebuilt");
        }
        if (!meshes.isEmpty()) {
            warnings.add(meshes.size()
                    + (meshes.size() == 1 ? " static mesh actor" : " static mesh actors")
                    + " near the edit keep their old baked lighting");
        }
        if (terrain.heightmap().size() > 1) {
            warnings.add("Only the first mip of the height texture is changed");
        }
        BeastStats beastStats = null;
        if (beast != null && scene.getBeast() != null) {
            var fit = scene.getBeast().fit();
            beastStats = new BeastStats(
                    beast.sectors(),
                    beast.texels(),
                    beast.changedTexels(),
                    fit.slot0After(),
                    fit.slot1After(),
                    fit.samples(),
                    beast.keptFlat());
            if (beast.keptFlat()) {
                warnings.add(
                        "The saved Beast lightmap of this Terrain is one flat colour without light or shadow, so the"
                                + " edit keeps it");
            }
        }
        if (unexplainedPasses > 0) {
            warnings.add(
                    unexplainedPasses + (unexplainedPasses == 1 ? " painted sector saved" : " painted sectors saved")
                            + " other layer passes than Cruma rebuilds from the saved weights, and the paint changes"
                            + " which layers they draw. Export wrote all passes of the painted weights there");
        }
        var painted = paint.stream()
                .map(edit -> new PaintedLayer(edit.layer().index(), edit.layer().weightTexture(), edit.changedTexels()))
                .toList();
        return new Report(
                terrain.path(),
                rebuild.stats(),
                heightName,
                shared,
                bsp,
                meshes,
                area,
                warnings,
                beastStats,
                painted,
                passSectors,
                mappedLayers);
    }

    private static boolean holdsChange(Sector sector, int sx, int sy, Set<Integer> changed, int width) {
        for (var vertex : changed) {
            var x = vertex % width;
            var y = vertex / width;
            if (x >= sector.offsetX()
                    && x <= sector.offsetX() + sx
                    && y >= sector.offsetY()
                    && y <= sector.offsetY() + sy) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Box a, Box b) {
        return a.min().x() <= b.max().x()
                && a.max().x() >= b.min().x()
                && a.min().y() <= b.max().y()
                && a.max().y() >= b.min().y()
                && a.min().z() <= b.max().z()
                && a.max().z() >= b.min().z();
    }
}
