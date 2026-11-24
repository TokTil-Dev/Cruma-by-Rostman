package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class P152TerrainExport {
    public static final String PROFILE_ID = "p152-12_24-owner0-brush-v2";

    public static final String SINGLE_SAMPLE_PROFILE_ID = "p152-12_24-owner0-190-230-v1";

    public static final String LIGHTING_CONTRACT =
            "per-Level native lighting build; editor seamless-neighbour Terrain (g_SWLevelList) not reproduced";

    public static final float ADVICE_MARGIN = 64;

    private static final String HEIGHT_PATH = "Textures/T_12_24.utx";
    private static final String CLONE_NAME = "C_12_24";

    private P152TerrainExport() {}

    public record DerivedAsset(Path source, String sourceSha256, String filename, byte[] bytes) {
        public DerivedAsset {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record Evidence(
            String profileId,
            long clearRays,
            long blockedRays,
            int changedPositions,
            int changedNormals,
            int unchangedSectors,
            int excludedTerrainActors,
            long bspPointProjections,
            long bspPointPairsRejected,
            long bspSunProjections,
            double minimumBspPointMargin,
            double minimumBspSunMargin,
            String lightingContract) {}

    public static final class Prepared {
        private final Path source;
        private final String sourceSha256;
        private final byte[] mapBytes;
        private final List<DerivedAsset> derivedAssets;
        private final SourceVerifier verifier;
        private final Optional<Evidence> evidence;

        private Prepared(
                Path source,
                String sourceSha256,
                byte[] mapBytes,
                List<DerivedAsset> derivedAssets,
                Optional<Evidence> evidence,
                SourceVerifier verifier) {
            this.source = source;
            this.sourceSha256 = sourceSha256;
            this.mapBytes = mapBytes.clone();
            this.derivedAssets = List.copyOf(derivedAssets);
            this.verifier = verifier;
            this.evidence = evidence;
        }

        public Path getSource() {
            return source;
        }

        public String getSourceSha256() {
            return sourceSha256;
        }

        public byte[] getMapBytes() {
            return mapBytes.clone();
        }

        public List<DerivedAsset> getDerivedAssets() {
            return derivedAssets;
        }

        public void verifySourcesUnchanged() throws IOException {
            verifier.verify();
        }

        public Optional<Evidence> getEvidence() {
            return evidence;
        }
    }

    public static String getHeightSourceRelativePath() {
        return HEIGHT_PATH;
    }

    public static String heightSourceSha256() {
        return P152TerrainSunBake.HEIGHT_HASH;
    }

    public static String exportRestriction() {
        return "";
    }

    public static boolean isCandidate(String mapSha256, String owner) {
        return P152TerrainSunBake.MAP_HASH.equals(mapSha256) && P152TerrainSunBake.OWNER.equals(owner);
    }

    public static boolean supportsPatch(TerrainHeightPatch patch) {
        if (patch == null || patch.getWidth() != 256 || patch.getHeight() != 256) {
            return false;
        }
        if (patch.size() == 0) {
            return true;
        }
        try {
            P152TerrainEditScope.of(patch);
            return true;
        } catch (IOException outside) {
            return false;
        }
    }

    public static int maximumVerifiedSamples() {
        return P152TerrainEditScope.MAX_SAMPLES;
    }

    public static int maximumVerifiedSectors() {
        return P152TerrainEditScope.MAX_WINDOWS;
    }

    public record SectorAdvice(int sectorX, int sectorY, String restriction) {
        public boolean isEditable() {
            return restriction.isEmpty();
        }
    }

    public static List<SectorAdvice> sectorAdvice(Path root, Path map, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        var scene = P152TerrainSunBake.captureScene(
                root.toRealPath(), map.toAbsolutePath().normalize(), cancelled);
        var terrain = scene.inputs().getTerrain();
        var field = new TerrainHeightField(
                scene.height().getCapturedHeightmap(), terrain.info().toWorld());
        var model = scene.model();
        var advice = P152BspReceiverExclusion.sectorAdvice(
                scene.context(), model, scene.inputs(), field, ADVICE_MARGIN, cancelled);
        var result = new ArrayList<SectorAdvice>();
        for (var entry : advice.entrySet()) {
            var detail = entry.getValue();
            result.add(new SectorAdvice(
                    entry.getKey().x(),
                    entry.getKey().y(),
                    detail.isEmpty() ? "" : Stage.BSP_LIGHTMAPS.explanation + ". Details: " + detail));
        }
        scene.heightSource().verifyUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        return List.copyOf(result);
    }

    public static String patchRestriction(TerrainHeightPatch patch) {
        if (patch == null || patch.size() == 0) {
            return "";
        }
        try {
            P152TerrainEditScope.of(patch);
            return "";
        } catch (IOException outside) {
            return outside.getMessage();
        }
    }

    public static Prepared prepare(
            Path root, Path map, String owner, TerrainHeightPatch patch, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        if (!P152TerrainSunBake.OWNER.equals(owner) || !supportsPatch(patch)) {
            throw new IOException("Terrain export supports only 12_24.TerrainInfo0 edits inside the verified profile: "
                    + patchRestriction(patch));
        }
        var restriction = exportRestriction();
        if (patch.size() != 0 && !restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        var source = map.toAbsolutePath().normalize();
        var realRoot = root.toRealPath();
        var realSource = source.toRealPath();
        if (!realSource.startsWith(realRoot) || !source.getFileName().toString().equals("12_24.unr")) {
            throw new IOException("Terrain export requires the original 12_24 map inside its client root");
        }
        if (patch.size() == 0) {
            return prepareOriginal(realRoot, source, realSource, cancelled);
        }
        var bake = runStage(Stage.SUNLIGHT, () -> P152TerrainSunBake.prepare(realRoot, source, patch, cancelled));
        var cpu = runStage(Stage.GEOMETRY, () -> P152TerrainCpuReceiverExclusion.capture(bake, cancelled));
        var bsp = runStage(Stage.BSP_LIGHTMAPS, () -> P152BspReceiverExclusion.capture(bake, cancelled));
        P152FreshHashQuery.checkCancelled(cancelled);
        var clone = bake.getHeightPackage().prepare(CLONE_NAME, patch);
        var document = PackageEditDocument.open(source);
        var imported = document.importHeightTexture(clone);
        var binding = P152TerrainMapBinding.prepare(document, realRoot, owner, clone, imported, bake.getEdited());
        var target = source.resolveSibling("cruma-prepared").resolve(source.getFileName());
        var bytes = document.prepareSave(target, binding.changes());
        var geometry = cpu.getReport().geometry();
        var projections = bsp.getSummary();
        var evidence = new Evidence(
                PROFILE_ID,
                bake.getClearRays(),
                bake.getBlockedRays(),
                geometry.changedPositions(),
                geometry.changedNormals(),
                geometry.unchangedSectors(),
                cpu.getReport().excludedTerrainActors(),
                projections.pointProjections(),
                projections.pointPairsRejected(),
                projections.sunProjections(),
                projections.minimumPointMargin(),
                projections.minimumSunMargin(),
                LIGHTING_CONTRACT);
        var result = new Prepared(
                source,
                document.getOriginalSha256(),
                bytes,
                List.of(new DerivedAsset(clone.source(), clone.originalSha256(), CLONE_NAME + ".utx", clone.bytes())),
                Optional.of(evidence),
                () -> {
                    cpu.verifySourcesUnchanged();
                    bsp.verifySourcesUnchanged();
                    binding.verifySourcesUnchanged();
                });
        result.verifySourcesUnchanged();
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    enum Stage {
        SUNLIGHT("Cruma could not rebuild the time-of-day sunlight for this edit"),
        GEOMETRY("The edit changes Terrain data that Cruma cannot verify"),
        BSP_LIGHTMAPS(
                "The edited area can cast shadows on baked lightmaps of 12_24 surfaces, which Cruma cannot rebuild"
                        + " yet. Undo the stroke or edit another area");

        final String explanation;

        Stage(String explanation) {
            this.explanation = explanation;
        }
    }

    @FunctionalInterface
    private interface Step<T> {
        T run() throws IOException;
    }

    private static <T> T runStage(Stage stage, Step<T> step) throws IOException {
        try {
            return step.run();
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (IOException | IllegalArgumentException | IllegalStateException | ArithmeticException rejected) {
            var detail = rejected.getMessage() == null ? rejected.getClass().getSimpleName() : rejected.getMessage();
            throw new IOException(stage.explanation + ". Details: " + detail, rejected);
        }
    }

    private static Prepared prepareOriginal(Path root, Path source, Path realSource, BooleanSupplier cancelled)
            throws IOException {
        var bytes = Files.readAllBytes(source);
        var hash = hash(bytes);
        if (!hash.equals(P152TerrainSunBake.MAP_HASH)) {
            throw new IOException("Terrain restore requires the authenticated original 12_24 map");
        }
        var mapIdentity = new Source(source, realSource, hash);
        var heightPath = root.resolve(HEIGHT_PATH);
        var heightReal = heightPath.toRealPath();
        if (!heightReal.startsWith(root)) {
            throw new IOException("Terrain height source is outside the client root");
        }
        var heightIdentity = new Source(heightPath, heightReal, heightSourceSha256());
        var result = new Prepared(source, hash, bytes, List.of(), Optional.empty(), () -> {
            mapIdentity.verifyUnchanged();
            heightIdentity.verifyUnchanged();
        });
        result.verifySourcesUnchanged();
        P152FreshHashQuery.checkCancelled(cancelled);
        return result;
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    @FunctionalInterface
    private interface SourceVerifier {
        void verify() throws IOException;
    }
}
