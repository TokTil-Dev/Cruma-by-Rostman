package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.NativePackageChanges;
import com.rost.cruma.core.editing.P152TerrainExport;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.TerrainActorShadows;
import com.rost.cruma.core.editing.TerrainDecorationExport;
import com.rost.cruma.core.editing.TerrainDecorationSources;
import com.rost.cruma.core.editing.TerrainEditExport;
import com.rost.cruma.core.editing.TerrainEditExport.OwnerEdits;
import com.rost.cruma.core.editing.TerrainEditExport.Prepared;
import com.rost.cruma.core.editing.TerrainEditExport.Report;
import com.rost.cruma.core.editing.TerrainEditHistory;
import com.rost.cruma.core.editing.TerrainEditHistory.Preview;
import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainHeightConsumers;
import com.rost.cruma.core.editing.TerrainHeightPatch;
import com.rost.cruma.core.editing.TerrainLightingFidelity.Stats;
import com.rost.cruma.core.editing.TerrainLightingScene;
import com.rost.cruma.core.editing.TerrainLightingScene.Summary;
import com.rost.cruma.core.editing.TerrainMappingHistory;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainPackageSnapshot;
import com.rost.cruma.core.editing.TerrainPackageSnapshot.Terrain;
import com.rost.cruma.core.editing.TerrainPaintHistory;
import com.rost.cruma.core.editing.TerrainPaintHistory.Edit;
import com.rost.cruma.core.editing.TerrainQuadHistory;
import com.rost.cruma.viewer.editing.EditingTimeline.Status;
import com.rost.cruma.viewer.editing.PackageExportService.ReplacedAssetInput;
import com.rost.cruma.viewer.editing.TerrainRecoveryCodec.Shared;
import com.rost.cruma.viewer.editing.TerrainRecoveryCodec.State;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;

public final class TerrainEditingController implements AutoCloseable {
    public static final int MOST_OPEN = TerrainRecoveryCodec.MOST_DOCUMENTS;

    private final Path clientRoot;
    private volatile TerrainPackageSnapshot inspected;
    private List<Capability> capabilities = List.of();
    private volatile List<TerrainDocument> documents = List.of();
    private volatile @Nullable TerrainDocument active;
    private volatile @Nullable TerrainStroke stroke;
    private final ArrayDeque<OrderStep> undoOrder = new ArrayDeque<>();
    private final ArrayDeque<OrderStep> redoOrder = new ArrayDeque<>();
    private long recordedStrokes;
    private String lastStrokeLabel = "";
    private long orderGeneration;
    private long @Nullable [] heldSteps;
    private PreparedMap prepared;
    private long recoveryBase;
    private volatile boolean operationActive;
    private boolean exportLocked;
    private RecoveryPreview recoveryPreview;

    private volatile StableRecovery stableRecovery = new StableRecovery(null, 0);
    private volatile StableRecovery frozenRecovery;

    public TerrainEditingController(Path clientRoot) {
        this.clientRoot = clientRoot.toAbsolutePath().normalize();
    }

    public enum Tool {
        SCULPT,
        PAINT
    }

    enum Step {
        HEIGHTS,
        WEIGHTS,
        MAPPING,
        QUADS,
        DECORATIONS
    }

    record OrderStep(Step kind, Set<String> owners, long serial) {
        OrderStep {
            owners = Set.copyOf(owners);
        }
    }

    public interface RecoveryPreview {
        Preview install(Path map, Terrain terrain, short[] samples);

        default void clear() {}
    }

    public record Capability(
            String owner,
            boolean heightsDecoded,
            String exportRestriction,
            String profileId,
            String heightPackage,
            boolean lit) {
        public Capability(String owner, boolean heightsDecoded, String exportRestriction) {
            this(owner, heightsDecoded, exportRestriction, TerrainEditExport.PROFILE_ID, "", false);
        }

        public boolean isNativeExportSupported() {
            return exportRestriction.isEmpty();
        }
    }

    public record Lighting(Summary summary, Stats fidelity, List<String> sharedWith, String error) {
        public Lighting {
            sharedWith = List.copyOf(sharedWith);
        }
    }

    public static final class PreparedMap {
        private final TerrainEditingController owner;
        private final TerrainDocument document;
        private final Path path;
        private final long revision;
        private final long paintRevision;
        private final long mappingRevision;
        private final long quadsRevision;
        private final byte[] bytes;

        private PreparedMap(
                TerrainEditingController owner,
                TerrainDocument document,
                Path path,
                long revision,
                long paintRevision,
                long mappingRevision,
                long quadsRevision,
                byte[] bytes) {
            this.owner = owner;
            this.document = document;
            this.path = path;
            this.revision = revision;
            this.paintRevision = paintRevision;
            this.mappingRevision = mappingRevision;
            this.quadsRevision = quadsRevision;
            this.bytes = bytes;
        }

        public Path getPath() {
            return path;
        }

        public long getRevision() {
            return revision;
        }

        public byte[] getBytes() {
            return bytes.clone();
        }
    }

    public static final class PreparedChanges {
        private final ExportInput input;
        private final List<Prepared> prepared;
        private final List<ReplacedAssetInput> replaced;
        private final List<TerrainDecorationExport.Edit> decorations;

        private PreparedChanges(
                ExportInput input,
                List<Prepared> prepared,
                List<ReplacedAssetInput> replaced,
                List<TerrainDecorationExport.Edit> decorations) {
            this.input = input;
            this.prepared = List.copyOf(prepared);
            this.replaced = List.copyOf(replaced);
            this.decorations = List.copyOf(decorations);
        }

        ExportInput getInput() {
            return input;
        }

        public List<NativePackageChanges> getChanges() {
            var result = new ArrayList<NativePackageChanges>();
            for (var each : prepared) {
                if (each.getChanges() != null) {
                    result.add(each.getChanges());
                }
            }
            return result;
        }

        List<ReplacedAssetInput> getReplaced() {
            return replaced;
        }

        public @Nullable Report getReport() {
            return prepared.isEmpty() ? null : prepared.getFirst().getReport();
        }

        public List<TerrainDecorationExport.Edit> getDecorations() {
            return decorations;
        }

        public List<Report> getReports() {
            return prepared.stream().map(Prepared::getReport).toList();
        }

        List<Path> getMaps() {
            return prepared.stream()
                    .map(each -> each.getMap().toAbsolutePath().normalize())
                    .toList();
        }

        List<Integer> getActorShadowVertices() {
            return prepared.stream().map(Prepared::getActorShadowVertices).toList();
        }

        Map<Path, List<TerrainChange>> getBspChanges() {
            var result = new LinkedHashMap<Path, List<TerrainChange>>();
            for (var each : prepared) {
                if (!each.getBspChange().isEmpty()) {
                    result.computeIfAbsent(each.getMap().toAbsolutePath().normalize(), ignored -> new ArrayList<>())
                            .add(each.getBspChange());
                }
            }
            return result;
        }

        Map<Path, Set<String>> getOwners() {
            var result = new LinkedHashMap<Path, Set<String>>();
            for (var each : prepared) {
                result.computeIfAbsent(each.getMap().toAbsolutePath().normalize(), ignored -> new LinkedHashSet<>())
                        .add(each.getReport().owner());
            }
            return result;
        }

        void verifySourcesUnchanged() throws IOException {
            for (var each : prepared) {
                each.verifySourcesUnchanged();
            }
        }
    }

    @FunctionalInterface
    interface ActorShadows {
        TerrainActorShadows of(Path map) throws IOException;
    }

    static final class ExportInput {
        final TerrainEditingController owner;
        final long recoveryRevision;
        final List<DocumentInput> documents;

        private ExportInput(TerrainEditingController owner, List<TerrainDocument> documents) {
            this.owner = owner;
            recoveryRevision = owner.getRecoveryRevision();
            var inputs = new ArrayList<DocumentInput>();
            for (var document : documents) {
                inputs.add(new DocumentInput(owner.clientRoot, document));
            }
            this.documents = List.copyOf(inputs);
        }

        PreparedChanges prepare(BooleanSupplier cancelled) throws IOException {
            return prepare(map -> TerrainActorShadows.NONE, cancelled);
        }

        PreparedChanges prepare(ActorShadows actors, BooleanSupplier cancelled) throws IOException {
            var prepared = new ArrayList<Prepared>();
            var replaced = new LinkedHashMap<Path, ReplacedAssetInput>();
            var decorations = new ArrayList<TerrainDecorationExport.Edit>();
            for (var document : documents) {
                decorations.addAll(document.decorationEdits);
                if (!document.terrainChanged) {
                    continue;
                }
                var each = document.prepare(actors.of(document.snapshot.getSourcePath()), cancelled);
                prepared.add(each);
                if (each.getHeightPackage().isPresent()) {
                    var height = each.getHeightPackage().orElseThrow();
                    replaced.put(
                            height.source().toAbsolutePath().normalize(),
                            new ReplacedAssetInput(height.source(), height.sourceSha256(), height.bytes()));
                }
            }
            if (!decorations.isEmpty()) {
                var written = new LinkedHashMap<Path, byte[]>();
                replaced.forEach((path, input) -> written.put(path, input.bytes()));
                for (var each : TerrainDecorationExport.write(decorations, written)) {
                    var path = each.source().toAbsolutePath().normalize();
                    var before = replaced.get(path);
                    replaced.put(
                            path,
                            new ReplacedAssetInput(
                                    before == null ? each.source() : before.source(),
                                    before == null ? each.sourceSha256() : before.sourceHash(),
                                    each.bytes()));
                }
            }
            return new PreparedChanges(this, prepared, List.copyOf(replaced.values()), decorations);
        }
    }

    static final class DocumentInput {
        final TerrainDocument document;
        final TerrainEditHistory history;
        final long revision;
        final TerrainPackageSnapshot snapshot;
        final Path root;
        final String selectedOwner;
        final TerrainHeightPatch patch;
        final TerrainPaintHistory paint;
        final long paintRevision;
        final List<Edit> paintEdits;
        final TerrainMappingHistory mapping;
        final long mappingRevision;
        final TerrainQuadHistory quads;
        final long quadsRevision;
        final TerrainPaintHistory decorations;
        final long decorationsRevision;
        final List<TerrainDecorationExport.Edit> decorationEdits;
        final boolean terrainChanged;
        final OwnerEdits ownerEdits;
        final TerrainLightingScene scene;
        final List<String> sharedWith;

        private DocumentInput(Path root, TerrainDocument document) {
            this.document = document;
            this.root = root;
            history = document.getHistory();
            revision = history.getRevision();
            snapshot = document.getSnapshot();
            selectedOwner = document.getOwner();
            patch = history.getSourceChanges();
            paint = document.getPaint();
            paintRevision = paint == null ? 0 : paint.getRevision();
            paintEdits = paint == null ? List.of() : paint.getSourceChanges();
            mapping = document.getMapping();
            mappingRevision = mapping == null ? 0 : mapping.getRevision();
            quads = document.getQuads();
            quadsRevision = quads == null ? 0 : quads.getRevision();
            decorations = document.getDecorations();
            decorationsRevision = decorations == null ? 0 : decorations.getRevision();
            var sources = document.getDecorationSources();
            decorationEdits = decorations == null || sources == null
                    ? List.of()
                    : TerrainDecorationExport.edits(document.getOwner(), sources, decorations.getSourceChanges());
            terrainChanged = document.hasTerrainChanges() || decorationEdits.isEmpty();
            var edited = quads != null && quads.hasSourceChanges();
            ownerEdits = new OwnerEdits(
                    mapping == null ? Map.of() : mapping.getSourceChanges(),
                    edited ? quads.getVisible() : null,
                    edited ? quads.getTurns() : null);
            scene = document.getScene();
            var facts = document.getLighting();
            sharedWith = facts == null ? null : facts.sharedWith();
        }

        Prepared prepare(TerrainActorShadows actors, BooleanSupplier cancelled) throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("Export cancelled");
            }
            var lighting =
                    scene != null ? scene : TerrainLightingScene.capture(root, snapshot, selectedOwner, cancelled);
            var shared = sharedWith;
            if (shared == null) {
                var terrain = document.getTerrain();
                shared = terrain.heightmapReference() > 0
                        ? List.of()
                        : TerrainHeightConsumers.scan(
                                root, terrain.heightmapObjectPath(), snapshot.getSourcePath(), cancelled);
            }
            return TerrainEditExport.prepare(
                    snapshot, lighting, selectedOwner, patch, paintEdits, ownerEdits, shared, actors, cancelled);
        }

        boolean isCurrent() {
            return history.getRevision() == revision
                    && document.getPaint() == paint
                    && paintRevision == (paint == null ? 0 : paint.getRevision())
                    && document.getMapping() == mapping
                    && mappingRevision == (mapping == null ? 0 : mapping.getRevision())
                    && document.getQuads() == quads
                    && quadsRevision == (quads == null ? 0 : quads.getRevision())
                    && document.getDecorations() == decorations
                    && decorationsRevision == (decorations == null ? 0 : decorations.getRevision());
        }

        void markExported() {
            history.markExported(revision);
            if (paint != null) {
                paint.markExported(paintRevision);
            }
            if (mapping != null) {
                mapping.markExported(mappingRevision);
            }
            if (quads != null) {
                quads.markExported(quadsRevision);
            }
            if (decorations != null) {
                decorations.markExported(decorationsRevision);
            }
        }
    }

    private record StableRecovery(State state, long revision) {}

    private record Restored(
            Path map,
            Terrain terrain,
            TerrainPackageSnapshot snapshot,
            TerrainEditHistory history,
            Preview[] callback) {}

    public static float sampleSpacing(Info info) {
        var a = info.toWorld().transform(0, 0, 0);
        var b = info.toWorld().transform(1, 0, 0);
        return (float) Math.hypot(b.x() - a.x(), b.y() - a.y());
    }

    public static float rawUnit(Info info) {
        var a = info.toWorld().transform(0, 0, 0);
        var b = info.toWorld().transform(0, 0, 1);
        return Math.max(1e-4f, Math.abs(b.z() - a.z()));
    }

    public static Path mapFile(Path root, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("Enter a map name without a folder or extension");
        }
        try (var files = Files.list(root.resolve("Maps"))) {
            var matches = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name + ".unr"))
                    .toList();
            if (matches.size() != 1) {
                throw new IOException("The source map is missing or ambiguous: " + name);
            }
            return matches.getFirst();
        }
    }

    static TerrainDocument prepareDocument(
            TerrainPackageSnapshot source, String owner, Preview preview, List<TerrainDocument> open)
            throws IOException {
        var terrain = source.getTerrains().stream()
                .filter(t -> t.path().equals(owner))
                .findFirst()
                .orElseThrow(() -> new IOException("Unknown Terrain owner " + owner));
        var restriction = TerrainEditExport.findRestriction(terrain);
        if (!restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        var refusal = refusal(source, terrain, open);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        return new TerrainDocument(
                source,
                terrain,
                new TerrainEditHistory(
                        terrain.heightmap().getFirst(), terrain.info().toWorld(), preview));
    }

    static @Nullable String refusal(TerrainPackageSnapshot source, Terrain terrain, List<TerrainDocument> open) {
        var quick = quickRefusal(terrain.path(), open);
        if (quick != null) {
            return quick;
        }
        var height = source.findHeightSource(terrain.path())
                .map(each -> each.path().toAbsolutePath().normalize())
                .orElse(null);
        for (var document : open) {
            var other = document.getHeightSource()
                    .map(each -> each.path().toAbsolutePath().normalize())
                    .orElse(null);
            if (height != null && height.equals(other)) {
                return "This Terrain keeps its heights in "
                        + height.getFileName()
                        + ", as the open Terrain of "
                        + document.getMap()
                        + " does. Close that Terrain first.";
            }
        }
        return null;
    }

    static @Nullable String quickRefusal(String owner, List<TerrainDocument> open) {
        if (open.size() >= MOST_OPEN) {
            return MOST_OPEN + " Terrains are open, the most at once. Close one first.";
        }
        var map = EditingSession.mapOfPath(owner);
        for (var document : open) {
            if (!document.getOwner().equals(owner) && document.getMap().equalsIgnoreCase(map)) {
                return "Another Terrain of " + map + " is open. Close it first.";
            }
        }
        return null;
    }

    public List<Capability> inspect(Path map) throws IOException {
        requireNotExporting();
        var candidate = TerrainPackageSnapshot.open(map, clientRoot);
        var found = new ArrayList<Capability>();
        for (var terrain : candidate.getTerrains()) {
            var height = candidate
                    .findHeightSource(terrain.path())
                    .map(source -> source.path().getFileName().toString())
                    .orElse("");
            var lit = terrain.sectors().stream()
                    .anyMatch(sector -> !sector.lights().isEmpty() || sector.hasTimeMaps() != 0);
            found.add(new Capability(
                    terrain.path(),
                    !terrain.heightmap().isEmpty(),
                    TerrainEditExport.findRestriction(terrain),
                    TerrainEditExport.PROFILE_ID,
                    height,
                    lit));
        }
        inspected = candidate;
        capabilities = List.copyOf(found);
        return capabilities;
    }

    public List<Capability> getCapabilities() {
        return capabilities;
    }

    public List<TerrainDocument> getDocuments() {
        return documents;
    }

    public @Nullable TerrainDocument findDocument(String owner) {
        for (var document : documents) {
            if (document.getOwner().equals(owner)) {
                return document;
            }
        }
        return null;
    }

    public @Nullable TerrainDocument getActive() {
        return active;
    }

    public @Nullable TerrainDocument getStrokeAnchor() {
        var running = stroke;
        if (running != null) {
            return running.getAnchor();
        }
        for (var document : documents) {
            if (document.isStrokeActive()) {
                return document;
            }
        }
        return null;
    }

    public @Nullable TerrainStroke getSculptStroke() {
        return stroke;
    }

    public TerrainStroke getSculptStroke(TerrainDocument anchor, boolean reach) {
        var running = stroke;
        if (running != null && running.getStarted().isEmpty() && running.getAnchor() != anchor) {
            running = null;
        }
        if (running != null) {
            if (running.getAnchor() != anchor) {
                throw new IllegalStateException(
                        "Finish the stroke on " + running.getAnchor().getMap() + " first");
            }
            return running;
        }
        var created = new TerrainStroke(this, anchor, reach);
        stroke = created;
        return created;
    }

    void endStroke(TerrainStroke ended) {
        if (stroke == ended) {
            stroke = null;
        }
    }

    public String getSelectedOwner() {
        var current = active;
        return current == null ? null : current.getOwner();
    }

    public Path getClientRoot() {
        return clientRoot;
    }

    public Path getSourcePath() {
        var current = active;
        if (current != null) {
            return current.getSourcePath();
        }
        var read = inspected;
        return read == null ? null : read.getSourcePath();
    }

    public String getSourceFingerprint() {
        var current = active;
        if (current != null) {
            return current.getSourceFingerprint();
        }
        var read = inspected;
        return read == null ? null : read.getOriginalSha256();
    }

    public Info getOwnerInfo() {
        return getTerrain().info();
    }

    public Terrain getTerrain() {
        var current = active;
        if (current == null) {
            throw new IllegalStateException("No Terrain owner is open");
        }
        return current.getTerrain();
    }

    public TerrainLightingScene getScene() {
        var current = active;
        return current == null ? null : current.getScene();
    }

    public Lighting getLighting() {
        var current = active;
        return current == null ? null : current.getLighting();
    }

    public String getHeightPackage() {
        var current = active;
        return current == null ? "" : current.getHeightPackage();
    }

    boolean isExportLocked() {
        return exportLocked;
    }

    void exportLocked(boolean value) {
        exportLocked = value;
    }

    void requireNotExporting() {
        if (exportLocked) {
            throw new IllegalStateException("Wait for export to finish or cancel it before editing Terrain.");
        }
    }

    public boolean isOpen() {
        return active != null;
    }

    public boolean isDirty() {
        for (var document : documents) {
            if (document.isDirty()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasSourceChanges() {
        for (var document : documents) {
            if (document.hasSourceChanges()) {
                return true;
            }
        }
        return false;
    }

    public boolean isRecoveryGestureActive() {
        return frozenRecovery == null && (operationActive || isStrokeActive());
    }

    public long getRecoveryRevision() {
        if (frozenRecovery != null) {
            return frozenRecovery.revision();
        }
        var sum = recoveryBase;
        for (var document : documents) {
            sum += document.getRevision();
        }
        return sum;
    }

    public void setOperationActive(boolean active) {
        operationActive = active;
    }

    public void setRecoveryPreview(RecoveryPreview publisher) {
        recoveryPreview = publisher;
    }

    public State getRecoveryState() {
        if (frozenRecovery != null) {
            return frozenRecovery.state();
        }
        if (isRecoveryGestureActive()) {
            throw new IllegalStateException("Terrain operation is still in progress");
        }
        return captureRecovery();
    }

    public void cacheCommittedRecovery() {
        if (frozenRecovery == null && !isHeightStrokeActive()) {
            stableRecovery = new StableRecovery(captureRecovery(), getRecoveryRevision());
        }
    }

    public void freezeCommittedRecovery() {
        frozenRecovery = stableRecovery;
    }

    public void restoreRecovery(State state) throws IOException {
        requireNotExporting();
        if (isRecoveryGestureActive()) {
            throw new IllegalStateException("Finish the Terrain operation before restoring recovery");
        }
        if (state == null) {
            if (recoveryPreview != null) {
                recoveryPreview.clear();
            }
            close();
            return;
        }
        var restored = new ArrayList<Restored>();
        for (var part : state.documents()) {
            restored.add(validate(part));
        }
        for (var index = 1; index < restored.size(); index++) {
            var refusal = refusal(
                    restored.get(index).snapshot(),
                    restored.get(index).terrain(),
                    restored.subList(0, index).stream()
                            .map(each -> new TerrainDocument(each.snapshot(), each.terrain(), each.history()))
                            .toList());
            if (refusal != null) {
                throw new IOException(refusal);
            }
        }
        if (recoveryPreview != null) {
            recoveryPreview.clear();
            try {
                for (var each : restored) {
                    each.callback()[0] = recoveryPreview.install(
                            each.map(), each.terrain(), each.history().getSamples());
                }
            } catch (RuntimeException failure) {
                recoveryPreview.clear();
                throw failure;
            }
        }
        var opened = new ArrayList<TerrainDocument>();
        for (var each : restored) {
            opened.add(new TerrainDocument(each.snapshot(), each.terrain(), each.history()));
        }
        var shared = state.shared();
        synchronized (this) {
            var before = getRecoveryRevision();
            documents = List.copyOf(opened);
            active = opened.get(Math.clamp(shared.active(), 0, opened.size() - 1));
            prepared = null;
            restoreOrder(opened, shared);
            settleRecovery(before);
        }
        inspected = opened.getFirst().getSnapshot();
        capabilities = opened.stream()
                .map(document -> new Capability(
                        document.getOwner(), true, "", TerrainEditExport.PROFILE_ID, document.getHeightPackage(), true))
                .toList();
        cacheCommittedRecovery();
    }

    public TerrainEditHistory getHistory() {
        var current = active;
        if (current == null) {
            throw new IllegalStateException("No supported Terrain owner is open");
        }
        return current.getHistory();
    }

    public TerrainPaintHistory getPaint() {
        var current = active;
        return current == null ? null : current.getPaint();
    }

    public String getPaintError() {
        var current = active;
        return current == null ? "" : current.getPaintError();
    }

    public void beginPaint(List<LayerSource> layers, TerrainPaintHistory.Preview preview) {
        var current = active;
        if (current != null) {
            current.beginPaint(layers, preview);
        }
    }

    public @Nullable TerrainPaintHistory getDecorations() {
        var current = active;
        return current == null ? null : current.getDecorations();
    }

    public TerrainDecorationSources.@Nullable Opened getDecorationSources() {
        var current = active;
        return current == null ? null : current.getDecorationSources();
    }

    public String getDecorationError() {
        var current = active;
        return current == null ? "" : current.getDecorationError();
    }

    public TerrainMappingHistory getMapping() {
        var current = active;
        return current == null ? null : current.getMapping();
    }

    public String getMappingError() {
        var current = active;
        return current == null ? "" : current.getMappingError();
    }

    public void beginMapping(TerrainMappingHistory.Preview preview) {
        var current = active;
        if (current != null) {
            current.beginMapping(preview);
        }
    }

    public TerrainQuadHistory getQuads() {
        var current = active;
        return current == null ? null : current.getQuads();
    }

    public String getQuadsError() {
        var current = active;
        return current == null ? "" : current.getQuadsError();
    }

    public void beginQuads(TerrainQuadHistory.Preview preview) {
        var current = active;
        if (current != null) {
            current.beginQuads(preview);
        }
    }

    void recordStroke(Tool tool, String label) {
        recordStep(tool == Tool.SCULPT ? Step.HEIGHTS : Step.WEIGHTS, label);
    }

    void recordStep(Step step, String label) {
        var current = active;
        if (current == null) {
            throw new IllegalStateException("No Terrain owner is open");
        }
        recordStep(step, Set.of(current.getOwner()), label);
    }

    void recordStep(Step step, Set<String> owners, String label) {
        if (owners.isEmpty()) {
            throw new IllegalArgumentException("A Terrain step names no owner");
        }
        var dropRedo = new ArrayList<TerrainDocument>();
        synchronized (this) {
            recordedStrokes++;
            lastStrokeLabel = label;
            undoOrder.addLast(new OrderStep(step, owners, recordedStrokes));
            if (!redoOrder.isEmpty()) {
                redoOrder.clear();
                for (var document : documents) {
                    if (!owners.contains(document.getOwner())) {
                        dropRedo.add(document);
                    }
                }
            }
            var limit = Math.max(1, documents.size())
                    * (TerrainEditHistory.MAX_COMMANDS
                            + TerrainPaintHistory.MAX_COMMANDS
                            + TerrainMappingHistory.MAX_COMMANDS
                            + TerrainQuadHistory.MAX_COMMANDS
                            + TerrainPaintHistory.MAX_COMMANDS);
            while (undoOrder.size() > limit) {
                undoOrder.removeFirst();
            }
            heldSteps = null;
        }
        for (var document : dropRedo) {
            document.clearRedo();
        }
    }

    void undo() {
        while (true) {
            OrderStep step;
            synchronized (this) {
                step = undoOrder.pollLast();
                heldSteps = null;
            }
            if (step == null) {
                return;
            }
            var done = false;
            for (var owner : step.owners()) {
                var document = findDocument(owner);
                if (document != null && document.canUndo(step.kind())) {
                    document.undo(step.kind());
                    done = true;
                }
            }
            if (!done) {
                continue;
            }
            synchronized (this) {
                redoOrder.addLast(step);
                heldSteps = null;
            }
            return;
        }
    }

    void clearRedo() {
        synchronized (this) {
            redoOrder.clear();
            heldSteps = null;
        }
        for (var document : documents) {
            document.clearRedo();
        }
    }

    void redo() {
        while (true) {
            OrderStep step;
            synchronized (this) {
                step = redoOrder.pollLast();
                heldSteps = null;
            }
            if (step == null) {
                return;
            }
            var done = false;
            for (var owner : step.owners()) {
                var document = findDocument(owner);
                if (document != null && document.canRedo(step.kind())) {
                    document.redo(step.kind());
                    done = true;
                }
            }
            if (!done) {
                continue;
            }
            synchronized (this) {
                undoOrder.addLast(step);
                heldSteps = null;
            }
            return;
        }
    }

    synchronized Status getHistoryStatus() {
        var open = !documents.isEmpty();
        if (heldSteps == null) {
            var held = new long[undoOrder.size() + redoOrder.size()];
            var index = 0;
            for (var step : undoOrder) {
                held[index++] = step.serial();
            }
            for (var step : redoOrder) {
                held[index++] = step.serial();
            }
            heldSteps = held;
        }
        return new Status(
                orderGeneration,
                recordedStrokes,
                open ? undoOrder.size() : 0,
                open ? redoOrder.size() : 0,
                lastStrokeLabel,
                open ? heldSteps : new long[0]);
    }

    public synchronized Set<String> getNewestStepOwners() {
        var newest = undoOrder.peekLast();
        return newest == null ? Set.of() : newest.owners();
    }

    synchronized List<Set<String>> getUndoOwners() {
        return undoOrder.stream().map(OrderStep::owners).toList();
    }

    public synchronized boolean canUndo() {
        return !documents.isEmpty() && !isStrokeActive() && !undoOrder.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !documents.isEmpty() && !isStrokeActive() && !redoOrder.isEmpty();
    }

    public boolean isStrokeActive() {
        for (var document : documents) {
            if (document.isStrokeActive()) {
                return true;
            }
        }
        return false;
    }

    public TerrainEditHistory begin(String owner, Preview preview) throws IOException {
        var document = addDocument(owner, preview);
        activate(owner);
        return document.getHistory();
    }

    public TerrainDocument addDocument(String owner, Preview preview) throws IOException {
        requireNotExporting();
        var open = findDocument(owner);
        if (open != null) {
            return open;
        }
        var source = inspected;
        if (source == null) {
            throw new IllegalStateException("Inspect a Terrain map first");
        }
        return addPrepared(prepareDocument(source, owner, preview, documents));
    }

    TerrainDocument addPrepared(TerrainDocument document) {
        requireNotExporting();
        if (findDocument(document.getOwner()) != null) {
            throw new IllegalStateException("The Terrain " + document.getOwner() + " is open already");
        }
        var refusal = refusal(document.getSnapshot(), document.getTerrain(), documents);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        synchronized (this) {
            var before = getRecoveryRevision();
            var next = new ArrayList<>(documents);
            next.add(document);
            documents = List.copyOf(next);
            settleRecovery(before);
        }
        return document;
    }

    public void activate(String owner) {
        var document = findDocument(owner);
        if (document == null) {
            throw new IllegalArgumentException("The Terrain " + owner + " is not open");
        }
        var current = active;
        if (current == document) {
            return;
        }
        if (isStrokeActive() && !document.isStrokeActive() && getStrokeAnchor() != document) {
            throw new IllegalStateException("Finish the stroke first");
        }
        active = document;
    }

    public void closeDocument(String owner) {
        requireNotExporting();
        var document = findDocument(owner);
        if (document == null) {
            return;
        }
        if (document.isStrokeActive()) {
            throw new IllegalStateException("Finish the stroke first");
        }
        synchronized (this) {
            var before = getRecoveryRevision();
            var next = new ArrayList<>(documents);
            next.remove(document);
            documents = List.copyOf(next);
            if (active == document) {
                active = next.isEmpty() ? null : next.getLast();
            }
            dropSteps(undoOrder, owner);
            dropSteps(redoOrder, owner);
            heldSteps = null;
            if (prepared != null && prepared.document == document) {
                prepared = null;
            }
            settleRecovery(before);
        }
        cacheCommittedRecovery();
    }

    public void prepareLighting(BooleanSupplier cancelled) {
        var current = active;
        if (current != null) {
            prepareLighting(current, cancelled);
        }
    }

    public void prepareLighting(TerrainDocument document, BooleanSupplier cancelled) {
        document.prepareLighting(clientRoot, cancelled);
    }

    ExportInput captureExport() throws IOException {
        if (isRecoveryGestureActive()) {
            throw new IOException("Finish the Terrain operation before validating export");
        }
        var changed = new ArrayList<TerrainDocument>();
        for (var document : documents) {
            if (document.isDirty() || document.hasSourceChanges()) {
                changed.add(document);
            }
        }
        var current = active;
        if (changed.isEmpty() && current != null) {
            changed.add(current);
        }
        if (changed.isEmpty()) {
            throw new IllegalStateException("No supported Terrain owner is open");
        }
        for (var document : changed) {
            var facts = document.getLighting();
            var decorationsOnly = !document.hasTerrainChanges()
                    && document.getDecorations() != null
                    && document.getDecorations().hasSourceChanges();
            if (facts != null && !facts.error().isEmpty() && !decorationsOnly) {
                throw new IOException((changed.size() > 1
                                ? "Terrain lighting cannot be rebuilt for " + document.getOwner() + ": "
                                : "Terrain lighting cannot be rebuilt for this owner: ")
                        + facts.error());
            }
        }
        return new ExportInput(this, changed);
    }

    public PreparedChanges prepareNativeChanges() throws IOException {
        return captureExport().prepare(() -> false);
    }

    public boolean isPreparedCurrent(PreparedChanges result) {
        return result != null && isCapturedCurrent(result.input);
    }

    boolean isCapturedCurrent(ExportInput input) {
        if (input == null
                || input.owner != this
                || isRecoveryGestureActive()
                || input.recoveryRevision != getRecoveryRevision()) {
            return false;
        }
        for (var each : input.documents) {
            if (findDocument(each.document.getOwner()) != each.document || !each.isCurrent()) {
                return false;
            }
        }
        return true;
    }

    public void markExported(PreparedChanges result) {
        if (!isPreparedCurrent(result)) {
            throw new IllegalStateException("Terrain changed before its joint Save checkpoint");
        }
        for (var each : result.input.documents) {
            each.markExported();
        }
        prepared = null;
        cacheCommittedRecovery();
    }

    public PreparedMap prepareNativeMap(Path output) throws IOException {
        var current = active;
        if (current == null) {
            throw new IllegalStateException("No supported Terrain owner is open");
        }
        if (current.getHistory().isStrokeActive()) {
            throw new IllegalStateException("Finish the Terrain stroke before exporting");
        }
        if (isRecoveryGestureActive()) {
            throw new IOException("Finish the Terrain operation before validating export");
        }
        var facts = current.getLighting();
        if (facts != null && !facts.error().isEmpty()) {
            throw new IOException("Terrain lighting cannot be rebuilt for this owner: " + facts.error());
        }
        var changes = new ExportInput(this, List.of(current)).prepare(() -> false);
        if (!changes.getDecorations().isEmpty()) {
            throw new IOException("Painted decorations are saved into the packages of their density maps. Use the"
                    + " Export page to write them");
        }
        if (!changes.getReplaced().isEmpty()) {
            throw new IOException("This Terrain keeps its heights in " + current.getHeightPackage()
                    + ". Use the Export page to write the map and its height package together");
        }
        var target = output.toAbsolutePath().normalize();
        var bytes = PackageEditDocument.open(current.getSourcePath())
                .prepareSave(target, changes.getChanges().getFirst());
        var paint = current.getPaint();
        var mapping = current.getMapping();
        var quads = current.getQuads();
        prepared = new PreparedMap(
                this,
                current,
                target,
                current.getHistory().getRevision(),
                paint == null ? 0 : paint.getRevision(),
                mapping == null ? 0 : mapping.getRevision(),
                quads == null ? 0 : quads.getRevision(),
                bytes);
        return prepared;
    }

    public void markExported(PreparedMap result) {
        if (result == null
                || result != prepared
                || result.owner != this
                || findDocument(result.document.getOwner()) != result.document) {
            throw new IllegalArgumentException("Terrain export does not belong to this prepared revision");
        }
        var document = result.document;
        document.getHistory().markExported(result.getRevision());
        if (document.getPaint() != null) {
            document.getPaint().markExported(result.paintRevision);
        }
        if (document.getMapping() != null) {
            document.getMapping().markExported(result.mappingRevision);
        }
        if (document.getQuads() != null) {
            document.getQuads().markExported(result.quadsRevision);
        }
        prepared = null;
    }

    @Override
    public void close() {
        synchronized (this) {
            var before = getRecoveryRevision();
            inspected = null;
            capabilities = List.of();
            documents = List.of();
            active = null;
            stroke = null;
            orderGeneration++;
            lastStrokeLabel = "";
            undoOrder.clear();
            redoOrder.clear();
            heldSteps = null;
            prepared = null;
            settleRecovery(before);
        }
        cacheCommittedRecovery();
    }

    private State captureRecovery() {
        var open = documents;
        if (open.isEmpty()) {
            return null;
        }
        var first = open.getFirst().createRecoveryState(clientRoot);
        if (open.size() == 1) {
            return first;
        }
        var others = new ArrayList<State>();
        var places = new HashMap<String, Integer>();
        places.put(open.getFirst().getOwner(), 0);
        for (var index = 1; index < open.size(); index++) {
            others.add(open.get(index).createRecoveryState(clientRoot));
            places.put(open.get(index).getOwner(), index);
        }
        List<List<Integer>> undo, redo;
        synchronized (this) {
            undo = heightSteps(undoOrder, places);
            redo = heightSteps(redoOrder, places);
        }
        for (var index = 0; index < open.size(); index++) {
            var history = open.get(index).getHistory();
            fitSteps(undo, index, history.getUndoCount());
            fitSteps(redo, index, history.getRedoCount());
        }
        var current = active;
        return first.withShared(new Shared(others, undo, redo, Math.max(0, open.indexOf(current))));
    }

    private Restored validate(State state) throws IOException {
        if (!List.of(TerrainEditExport.PROFILE_ID, TerrainRecoveryCodec.UNLIT_PROFILE, P152TerrainExport.PROFILE_ID)
                .contains(state.profileId())) {
            throw new IOException("Unknown Terrain recovery profile");
        }
        var map = clientRoot.resolve(state.relativeMap()).normalize();
        if (!map.startsWith(clientRoot) || !map.toRealPath().startsWith(clientRoot.toRealPath())) {
            throw new IOException("Terrain recovery map leaves its source client");
        }
        var candidate = TerrainPackageSnapshot.open(map, clientRoot);
        if (!candidate.getOriginalSha256().equals(state.originalSha256())) {
            throw new IOException("Terrain recovery source fingerprint changed");
        }
        var owner = candidate.getTerrains().stream()
                .filter(t -> t.path().equals(state.owner()))
                .findFirst()
                .orElseThrow(() -> new IOException("Terrain recovery owner is missing"));
        var restriction = TerrainEditExport.findRestriction(owner);
        if (!restriction.isEmpty()) {
            throw new IOException(restriction);
        }
        if (!state.heightSha256().isEmpty()) {
            var height = candidate.findHeightSource(owner.path());
            if (height.isEmpty() || !height.orElseThrow().sha256().equals(state.heightSha256())) {
                throw new IOException("Recovered Terrain height package changed since the draft was saved");
            }
        }
        var callback = new Preview[] {ignored -> {}};
        var history = new TerrainEditHistory(
                owner.heightmap().getFirst(), owner.info().toWorld(), revision -> callback[0].publish(revision));
        history.restore(state.checkpoint());
        return new Restored(map, owner, candidate, history, callback);
    }

    private void restoreOrder(List<TerrainDocument> opened, Shared shared) {
        orderGeneration++;
        lastStrokeLabel = "";
        undoOrder.clear();
        redoOrder.clear();
        heldSteps = null;
        var undo = shared.undo();
        var redo = shared.redo();
        var fits = opened.size() == shared.documents().size() + 1
                || opened.size() == 1 && undo.isEmpty() && redo.isEmpty();
        for (var index = 0; fits && index < opened.size(); index++) {
            fits = named(undo, index) == opened.get(index).getHistory().getUndoCount()
                    && named(redo, index) == opened.get(index).getHistory().getRedoCount();
        }
        if (!fits || opened.size() == 1) {
            undo = new ArrayList<>();
            redo = new ArrayList<>();
            for (var index = 0; index < opened.size(); index++) {
                var history = opened.get(index).getHistory();
                for (var i = 0; i < history.getUndoCount(); i++) {
                    undo.add(List.of(index));
                }
                for (var i = 0; i < history.getRedoCount(); i++) {
                    redo.add(List.of(index));
                }
            }
        }
        for (var step : undo) {
            undoOrder.addLast(heightStep(opened, step));
        }
        for (var step : redo) {
            redoOrder.addLast(heightStep(opened, step));
        }
    }

    private OrderStep heightStep(List<TerrainDocument> opened, List<Integer> places) {
        var owners = new LinkedHashSet<String>();
        for (var place : places) {
            owners.add(opened.get(place).getOwner());
        }
        return new OrderStep(Step.HEIGHTS, owners, ++recordedStrokes);
    }

    private boolean isHeightStrokeActive() {
        for (var document : documents) {
            if (document.getHistory().isStrokeActive()) {
                return true;
            }
        }
        return false;
    }

    private void settleRecovery(long before) {
        if (frozenRecovery != null) {
            return;
        }
        var sum = 0L;
        for (var document : documents) {
            sum += document.getRevision();
        }
        recoveryBase = before + 1 - sum;
    }

    private static List<List<Integer>> heightSteps(Collection<OrderStep> order, Map<String, Integer> places) {
        var steps = new ArrayList<List<Integer>>();
        for (var step : order) {
            if (step.kind() != Step.HEIGHTS) {
                continue;
            }
            var named = new TreeSet<Integer>();
            for (var owner : step.owners()) {
                var place = places.get(owner);
                if (place != null) {
                    named.add(place);
                }
            }
            if (!named.isEmpty()) {
                steps.add(new ArrayList<>(named));
            }
        }
        return steps;
    }

    private static void fitSteps(List<List<Integer>> steps, int place, int held) {
        var named = 0;
        for (var step : steps) {
            if (step.contains(place)) {
                named++;
            }
        }
        for (var iterator = steps.iterator(); named > held && iterator.hasNext(); ) {
            var step = iterator.next();
            if (step.remove(Integer.valueOf(place))) {
                named--;
                if (step.isEmpty()) {
                    iterator.remove();
                }
            }
        }
        for (; named < held; named++) {
            steps.addFirst(new ArrayList<>(List.of(place)));
        }
    }

    private static int named(List<List<Integer>> steps, int place) {
        var count = 0;
        for (var step : steps) {
            if (step.contains(place)) {
                count++;
            }
        }
        return count;
    }

    private static void dropSteps(ArrayDeque<OrderStep> order, String owner) {
        var kept = new ArrayList<OrderStep>();
        for (var step : order) {
            if (!step.owners().contains(owner)) {
                kept.add(step);
            } else if (step.owners().size() > 1) {
                var others = new LinkedHashSet<>(step.owners());
                others.remove(owner);
                kept.add(new OrderStep(step.kind(), others, step.serial()));
            }
        }
        order.clear();
        order.addAll(kept);
    }
}
