package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.P152ActorDomain.Actor;
import com.rost.cruma.core.editing.P152ActorDomain.Disposition;
import com.rost.cruma.core.editing.P152ActorDomain.Identity;
import com.rost.cruma.core.editing.P152ActorDomain.Query;
import com.rost.cruma.core.editing.P152ActorDomain.Report;
import com.rost.cruma.core.editing.P152EffectiveInputs.Input;
import com.rost.cruma.core.editing.P152EffectiveInputs.MapInputs;
import com.rost.cruma.core.editing.P152EffectiveInputs.Source;
import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.ShadowTriangleQuery.Triangle;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

final class P152FreshBakeContext {
    private static final int MAX_MAP_BYTES = 512 * 1024 * 1024;
    private final Path clientRoot;
    private final Source mapSource;
    private final byte[] mapBytes;
    private final Actors actors;
    private final LevelFacts level;
    private final List<String> gaps;

    private P152FreshBakeContext(
            Path clientRoot, Source mapSource, byte[] mapBytes, Actors actors, LevelFacts level, List<String> gaps) {
        this.clientRoot = clientRoot;
        this.mapSource = mapSource;
        this.mapBytes = mapBytes.clone();
        this.actors = actors;
        this.level = level;
        this.gaps = List.copyOf(gaps);
    }

    interface Actors {
        Report getReport();

        List<String> getNonIndexGaps();

        boolean isCurrentLevelInfoExcluded();

        MapInputs getMapInputs();

        Query query(Vector start, Vector end);

        default List<Triangle> collectCollisionAdmittedRenderTriangles(BooleanSupplier cancelled) throws IOException {
            throw new IOException("No render-triangle capture was supplied for this actor domain");
        }

        void verifySourcesUnchanged() throws IOException;

        default void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
            P152FreshHashQuery.checkCancelled(cancelled);
            verifySourcesUnchanged();
            P152FreshHashQuery.checkCancelled(cancelled);
        }
    }

    record LevelFacts(Identity identity, int modelReference, List<String> gaps) {
        LevelFacts {
            gaps = List.copyOf(gaps);
        }
    }

    static P152FreshBakeContext capture(Path clientRoot, Path map) throws IOException {
        return capture(clientRoot, map, () -> false);
    }

    static P152FreshBakeContext capture(Path clientRoot, Path map, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var capture = P152ActorDomain.capture(clientRoot, map, cancelled);
        return capture(
                clientRoot,
                map,
                new Actors() {
                    @Override
                    public Report getReport() {
                        return capture.getReport();
                    }

                    @Override
                    public List<String> getNonIndexGaps() {
                        return capture.getNonIndexGaps();
                    }

                    @Override
                    public boolean isCurrentLevelInfoExcluded() {
                        return capture.isCurrentLevelInfoExcluded();
                    }

                    @Override
                    public MapInputs getMapInputs() {
                        return capture.getMapInputs();
                    }

                    @Override
                    public Query query(Vector start, Vector end) {
                        return capture.query(start, end);
                    }

                    @Override
                    public List<Triangle> collectCollisionAdmittedRenderTriangles(BooleanSupplier token)
                            throws IOException {
                        return capture.collectCollisionAdmittedRenderTriangles(token);
                    }

                    @Override
                    public void verifySourcesUnchanged() throws IOException {
                        capture.verifySourcesUnchanged();
                    }

                    @Override
                    public void verifySourcesUnchanged(BooleanSupplier token) throws IOException {
                        capture.verifySourcesUnchanged(token);
                    }
                },
                cancelled);
    }

    static P152FreshBakeContext capture(Path clientRoot, Path map, Actors actors) throws IOException {
        return capture(clientRoot, map, actors, () -> false);
    }

    static P152FreshBakeContext capture(Path clientRoot, Path map, Actors actors, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
        var root = clientRoot.toAbsolutePath().normalize();
        var logical = map.toAbsolutePath().normalize();
        var real = logical.toRealPath();
        if (!real.startsWith(root.toRealPath()) || Files.size(real) > MAX_MAP_BYTES) {
            throw new IOException("Fresh bake map is outside the bounded client root");
        }
        var bytes = P152CaptureIo.read(real, MAX_MAP_BYTES, cancelled);
        var source = new Source(logical, real, P152CaptureIo.hash(bytes, cancelled));
        if (!source.sha256().equals(actors.getReport().level().packageSha256())) {
            throw new IOException("Fresh bake map changed after actor capture");
        }
        var data = new PackageBytes(logical, bytes);
        P152FreshHashQuery.checkCancelled(cancelled);
        var level = inspectLevel(data, source.sha256(), actors.getReport());
        P152FreshHashQuery.checkCancelled(cancelled);
        var gaps = new ArrayList<>(level.gaps());
        gaps.addAll(actors.getNonIndexGaps());
        if (!actors.isCurrentLevelInfoExcluded()) {
            gaps.add("Current LevelInfo hit identity cannot be excluded");
        }
        for (var actor : actors.getReport().actors()) {
            P152FreshHashQuery.checkCancelled(cancelled);
            if (actor.disposition() == Disposition.UNSUPPORTED) {
                gaps.add(actor.identity().objectPath() + ": native load or primitive effects remain unqualified");
            }
            if (actor.classPath().equals("Engine.DefaultPhysicsVolume")) {
                gaps.add("SetActorCollision may replace a serialized DefaultPhysicsVolume");
            }
        }
        var result = new P152FreshBakeContext(root, source, bytes, actors, level, gaps);
        result.verifySourcesUnchanged(cancelled);
        return result;
    }

    Path getClientRoot() {
        return clientRoot;
    }

    Path getMapPath() {
        return mapSource.logicalPath();
    }

    String getMapSha256() {
        return mapSource.sha256();
    }

    byte[] getMapBytes() {
        return mapBytes.clone();
    }

    List<Input> getRuntimeInputs() {
        return actors.getReport().runtimeInputs();
    }

    MapInputs getMapInputs() {
        return actors.getMapInputs();
    }

    Identity getLevelIdentity() {
        return level.identity();
    }

    int getModelReference() {
        return level.modelReference();
    }

    List<String> getGaps() {
        return gaps;
    }

    Actors getActorDomain() {
        return actors;
    }

    void verifySourcesUnchanged() throws IOException {
        verifySourcesUnchanged(() -> false);
    }

    void verifySourcesUnchanged(BooleanSupplier cancelled) throws IOException {
        mapSource.verifyUnchanged(cancelled);
        actors.verifySourcesUnchanged(cancelled);
        P152FreshHashQuery.checkCancelled(cancelled);
    }

    private static LevelFacts inspectLevel(PackageBytes source, String hash, Report report) throws IOException {
        if (source.fileVersion != 128 || source.licenseVersion != 39) {
            throw new IOException("Fresh native bake requires P152 map 128/39");
        }
        var nativeLevel = NativeLevelEditor.read(source);
        var entry = nativeLevel.entry;
        P152ActorDomain.requireEngineClass(source, entry, "Level");
        var identity = new Identity(hash, entry.index(), source.getObjectPath(entry.index()));
        if (!identity.equals(report.level())) {
            throw new IOException("Fresh bake Level identity differs from actor capture");
        }
        if (nativeLevel.actors.isEmpty()
                || nativeLevel.actors.getFirst() <= 0
                || !new Identity(
                                hash,
                                nativeLevel.actors.getFirst(),
                                source.getObjectPath(nativeLevel.actors.getFirst()))
                        .equals(report.currentLevelInfo())) {
            throw new IOException("Fresh bake current LevelInfo differs from actor capture");
        }
        var gaps = new ArrayList<String>();
        if (entry.outer() != 0 || (entry.flags() & 0x02000000) != 0) {
            throw new IOException("Fresh Level requires the package outer and no state frame");
        }
        if ((entry.flags() & 0x40000) == 0) {
            gaps.add("Native Level editor-load object flag is absent");
        }
        if ((entry.flags() & 0x400000) != 0) {
            gaps.add("Native Level has RF_NotForEdit");
        }
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if (!source.getName(c.readIndex()).equalsIgnoreCase("None")) {
            throw new IOException("Native-only Level has unaudited authored properties");
        }
        skipReferences(source, c);
        skipReferences(source, c);
        for (var i = 0; i < 4; i++) {
            c.readString();
        }
        var options = PackageBytes.count(c.readIndex());
        for (var i = 0; i < options; i++) {
            c.readString();
        }
        c.readI32();
        c.skip(3);
        if (c.readIndex() != 0) {
            throw new IOException("Fresh Level navigation data is not qualified");
        }
        var model = c.readIndex();
        source.checkReference(model);
        if (!Float.isFinite(Float.intBitsToFloat(c.readI32()))) {
            throw new IOException("Fresh Level time is not finite");
        }
        if (c.readIndex() != 0) {
            gaps.add("Fresh Level has a serialized FirstDeleted actor chain");
        }
        for (var i = 0; i < 16; i++) {
            source.checkReference(c.readIndex());
        }
        if (c.readIndex() != 0 || c.position != c.end) {
            throw new IOException("Fresh Level has an unaudited travel map or native suffix");
        }
        var registered = new HashMap<Integer, Actor>();
        for (var actor : report.actors()) {
            if (registered.put(actor.identity().reference(), actor) != null) {
                throw new IOException("Duplicate actor capture identity");
            }
        }
        var nonzero = 0;
        for (var ref : nativeLevel.actors) {
            if (ref == 0) {
                continue;
            }
            nonzero++;
            var captured = registered.get(ref);
            if (captured == null
                    || !captured.identity().packageSha256().equals(hash)
                    || !captured.identity().objectPath().equals(source.getObjectPath(ref))) {
                throw new IOException("Fresh native registry differs from actor capture");
            }
            if (ref < 0) {
                gaps.add("Fresh native registry contains an imported actor");
                continue;
            }
            var actor = source.exports.get(ref - 1);
            if (!captured.classPath().equals(source.getObjectPath(actor.classIndex()))) {
                throw new IOException("Fresh native actor class differs from capture");
            }
            if (actor.outer() != entry.outer()) {
                gaps.add(source.getObjectPath(ref) + ": Level PostLoad does not assign the same native Level outer");
            }
            if ((actor.flags() & 0x40000) == 0) {
                gaps.add(source.getObjectPath(ref) + ": native editor-load object flag is absent");
            }
            if ((actor.flags() & 0x400000) != 0) {
                gaps.add(source.getObjectPath(ref) + ": RF_NotForEdit excludes native editor loading");
            }
        }
        if (nonzero != registered.size()
                || nativeLevel.actors.size() != report.registrySlots()
                || nativeLevel.actors.size() - nonzero != report.nullSlots()) {
            throw new IOException("Fresh native registry counts differ from actor capture");
        }
        return new LevelFacts(identity, model, gaps);
    }

    private static void skipReferences(PackageBytes source, Cursor c) throws IOException {
        var count = PackageBytes.count(c.readI32());
        var repeatedCount = PackageBytes.count(c.readI32());
        if (count != repeatedCount) {
            throw new IOException("Invalid native Level reference array");
        }
        for (var i = 0; i < count; i++) {
            source.checkReference(c.readIndex());
        }
    }
}
