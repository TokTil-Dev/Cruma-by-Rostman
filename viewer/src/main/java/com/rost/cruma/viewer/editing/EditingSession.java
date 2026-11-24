package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.ActorCarry;
import com.rost.cruma.core.editing.ActorLightingCapture;
import com.rost.cruma.core.editing.ActorLightingChanges;
import com.rost.cruma.core.editing.ActorLightingRebuild;
import com.rost.cruma.core.editing.ActorLightingRebuild.Options;
import com.rost.cruma.core.editing.ActorLightingRebuild.Report;
import com.rost.cruma.core.editing.ActorLightingRebuild.Result;
import com.rost.cruma.core.editing.ActorLightingScene.Settings;
import com.rost.cruma.core.editing.ActorRecord;
import com.rost.cruma.core.editing.ActorTransform;
import com.rost.cruma.core.editing.ActorTransform.Rotator;
import com.rost.cruma.core.editing.ActorTransform.Vector3;
import com.rost.cruma.core.editing.BspLightmapRebuild;
import com.rost.cruma.core.editing.BspLightmapRebuild.TerrainChange;
import com.rost.cruma.core.editing.BspSurfaceFlag;
import com.rost.cruma.core.editing.BspSurfaceMapping;
import com.rost.cruma.core.editing.BspSurfaceSelect;
import com.rost.cruma.core.editing.LightColor;
import com.rost.cruma.core.editing.LightPlacement;
import com.rost.cruma.core.editing.LightRecord;
import com.rost.cruma.core.editing.LightShape;
import com.rost.cruma.core.editing.MapEdgeLighting;
import com.rost.cruma.core.editing.MaterialReference;
import com.rost.cruma.core.editing.NativePackageChanges;
import com.rost.cruma.core.editing.PackageEditDocument;
import com.rost.cruma.core.editing.PackageEditDocument.BrushFacts;
import com.rost.cruma.core.editing.PackageEditDocument.LightTemplate;
import com.rost.cruma.core.editing.PackageEditDocument.RecoveryBundle;
import com.rost.cruma.core.editing.PackageEditDocument.Snapshot;
import com.rost.cruma.core.editing.PackageInventory;
import com.rost.cruma.core.editing.TerrainActorShadows;
import com.rost.cruma.core.unreal.ClientFiles;
import com.rost.cruma.core.world.Entity;
import com.rost.cruma.core.world.L2Attach;
import com.rost.cruma.core.world.L2Swing;
import com.rost.cruma.core.world.WorldDimensions;
import com.rost.cruma.core.world.WorldTile;
import com.rost.cruma.viewer.editing.EditingLightingPreview.Job;
import com.rost.cruma.viewer.editing.EditingTimeline.History;
import com.rost.cruma.viewer.editing.EditingTimeline.Kind;
import com.rost.cruma.viewer.editing.EditingTimeline.Status;
import com.rost.cruma.viewer.editing.PackageExportService.AssetInput;
import com.rost.cruma.viewer.editing.PackageExportService.DerivedAssetInput;
import com.rost.cruma.viewer.editing.PackageExportService.MapInput;
import com.rost.cruma.viewer.editing.PackageExportService.Plan;
import com.rost.cruma.viewer.editing.PackageExportService.ReplacedAssetInput;
import com.rost.cruma.viewer.editing.PackageExportService.SourceVerifier;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.eclipse.collections.impl.list.mutable.primitive.FloatArrayList;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.IntIntHashMap;
import org.jetbrains.annotations.Nullable;

public final class EditingSession {
    public static final String MOVEMENT_LINE = "Moves by itself. A change starts its movement again.";
    public static final String SAVED_PLACE_LINE = "The values above are its saved place.";
    public static final String NOT_STATIC_LINE = "Not static: the game may move it. It keeps its mesh and its map.";
    public static final String CARRIED_LIGHT_KEPT = "Edit moves a light that lights the map only in the Lights tool.";
    public static final String CARRIED_UNKNOWN_KEPT = "Edit finds no such actor in the map.";

    static final String EDGE_TIP =
            "The original lighting of each map was built from that map alone: no saved shadow comes from the next map,"
                    + " and no map uses a light of another map. Cruma follows the saved data, so edits near a map edge"
                    + " change only the lighting of their own map.";

    private static final float LIGHT_PREVIEW_MARGIN = 1024;

    private final Path clientRoot;
    private final Preview preview;
    private final PackageExportService exports;
    private final Map<String, PackageEditDocument> documents = new LinkedHashMap<>();
    private final Map<String, State> originals = new LinkedHashMap<>();
    private final Map<String, Asset> assets = new LinkedHashMap<>();
    private final Map<Path, FileStamp> assetStamps = new HashMap<>();
    private long assetHashReads;
    private final Map<String, float[]> previewDeltas = new LinkedHashMap<>();
    private final Map<String, float[]> previewDeltaView = Collections.unmodifiableMap(previewDeltas);
    private Map<String, Asset> savedAssets = Map.of();
    private final Set<String> selection = new LinkedHashSet<>();
    private final ArrayDeque<Command> undo = new ArrayDeque<>();
    private final ArrayDeque<Command> redo = new ArrayDeque<>();
    private Map<String, State> gesture;
    private final Map<String, State> gestureCarried = new LinkedHashMap<>();
    private final Map<String, String> lightsLeftBehind = new HashMap<>();
    private final Map<String, String> copyNotes = new HashMap<>();
    private @Nullable String stepNote;
    private final Map<String, String> leftThisStep = new LinkedHashMap<>();
    private Map<String, String> gestureNotes = Map.of();
    private long revision;
    private long selectionRequests;
    private @Nullable Checkpoint lightGesture;
    private Map<String, LightColor> lightGestureColors = Map.of();
    private @Nullable Checkpoint placementGesture;
    private Map<String, LightRecord> lightGestureLights = Map.of();
    private Map<String, LightRecord> placementGestureLights = Map.of();
    private Map<String, LightPlacement> placementGestureValues = Map.of();
    private @Nullable Checkpoint shapeGesture;
    private Map<String, LightRecord> shapeGestureLights = Map.of();
    private Map<String, LightShape> shapeGestureValues = Map.of();
    private final Set<String> dragShownActors = new LinkedHashSet<>();
    private final Set<String> dragShownMaps = new LinkedHashSet<>();
    private Map<String, LightRecord> followedLights = Map.of();
    private long committedCommands;
    private long recordedCommands;
    private String lastLabel = "";
    private long historyGeneration;
    private Plan preparedExport;
    private long preparedExportRevision;
    private long exportabilityRevision = -1;
    private boolean exportable;
    private PivotMode pivotMode = PivotMode.CENTER;
    private TextureImporter textureImporter;
    private boolean exportLocked;
    private EditingExportCoordinator exportCoordinator;
    private boolean rebuildAllLighting;
    private boolean rebuildAllBspLightmaps;
    private boolean rebuildAllTerrainLighting;
    private boolean keepNeighborBeast;
    private Settings lightingSettings;
    private EditingLightingPreview lightingPreview;
    private final Map<String, Map<Integer, ShownSurface>> shownSurfaces = new HashMap<>();
    private long surfacePreviewRevision = -1;
    private final Map<String, SurfaceJob> surfaceJobs = new HashMap<>();
    private final Set<PackageEditDocument> brushReads = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Long> surfaceGenerations = new HashMap<>();
    private final Map<String, Map<Integer, ShownSurface>> overlayFailures = new HashMap<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Set<String> locked = new LinkedHashSet<>();
    private final ActorGroups groups = new ActorGroups();
    private @Nullable Checkpoint stepStart;
    private long stepRecorded;
    private final Map<String, Snapshot> stepOpened = new LinkedHashMap<>();
    private final Map<String, State> stepOriginals = new LinkedHashMap<>();
    private @Nullable Checkpoint materialPreview;
    private Set<String> materialPreviewMaps = Set.of();
    private List<Change> changes = List.of();
    private long changesRevision = -1;
    private Set<String> materialPreviewActors = Set.of();
    private final EditingTimeline timeline = new EditingTimeline();
    private @Nullable GroundProbe groundProbe;
    private volatile Set<String> loadedMaps = Set.of();
    private volatile EditingMaps mapFiles = EditingMaps.MAIN;
    private final Map<String, PackageEditDocument> readMaps = new ConcurrentHashMap<>();

    public EditingSession(Path clientRoot, Preview preview) {
        this(clientRoot, preview, new PackageExportService(clientRoot));
    }

    EditingSession(Path clientRoot, Preview preview, PackageExportService exports) {
        this.clientRoot = clientRoot.toAbsolutePath().normalize();
        this.preview = preview;
        this.exports = exports;
        timeline.bind(Kind.ACTORS, new ActorHistory());
    }

    private final class ActorHistory implements History {
        @Override
        public Status getStatus() {
            return getHistoryStatus();
        }

        @Override
        public void undo() {
            EditingSession.this.undo();
        }

        @Override
        public void redo() {
            EditingSession.this.redo();
        }

        @Override
        public void clearRedo() {
            EditingSession.this.clearRedo();
        }

        @Override
        public boolean isReady() {
            return !isGestureActive();
        }
    }

    public interface Preview {
        boolean accepts(String sourcePath);

        void apply(Map<String, float[]> deltas);

        default boolean isMoving(String sourcePath) {
            return false;
        }

        default void place(Map<String, ActorTransform> placements) {}

        default @Nullable String describeMotion(String sourcePath, List<CarriedActor> carried) {
            return null;
        }

        default Map<String, L2Swing.Kind> getCarried(String carrier) {
            return Map.of();
        }

        default boolean holdsMapOf(String actorPath) {
            return true;
        }

        default void applyChanges(Map<String, float[]> updates, Set<String> restores, Map<String, float[]> allDeltas) {
            apply(Map.copyOf(allDeltas));
        }

        default void rebuild(Rebuild change) {}

        default @Nullable Map<String, List<Entity>> load(Rebuild change) {
            return null;
        }

        default void replace(Map<String, List<Entity>> actors) {}

        default void showLight(String map, EditingLightSurfaces.Result light) {}

        default LoadedSurfaces loadSurfaces(String map, SurfacePreview surfaces) {
            return new LoadedSurfaces(List.of(), null);
        }

        default SurfaceShow showSurfaces(String map, SurfacePreview surfaces, LoadedSurfaces loaded) {
            return SurfaceShow.NOTHING;
        }

        default boolean isSurfaceMapDrawn(String map) {
            return true;
        }

        default Executor getSurfaceExecutor() {
            return Runnable::run;
        }

        default int @Nullable [] getMappingSize(String material, Map<String, Path> assetPackages) {
            return null;
        }

        default void hide(Set<String> actors) {}

        default void show(Set<String> actors) {}
    }

    public record SurfacePreview(
            byte[] layoutBody,
            byte @Nullable [] overlayBody,
            Map<String, Path> assetPackages,
            Set<Integer> written,
            Set<Integer> collapsed,
            @Nullable Set<Integer> overlay) {
        public SurfacePreview {
            written = Set.copyOf(written);
            collapsed = Set.copyOf(collapsed);
            overlay = overlay == null ? null : Set.copyOf(overlay);
        }
    }

    public enum SurfaceShow {
        NOTHING,
        WITHOUT_OVERLAY,
        ALL
    }

    public record LoadedSurfaces(
            List<Entity> layout, @Nullable List<Entity> overlay) {
        public LoadedSurfaces {
            layout = List.copyOf(layout);
            overlay = overlay == null ? null : List.copyOf(overlay);
        }
    }

    private record SurfaceJob(
            Map<Integer, ShownSurface> state,
            long generation,
            SurfacePreview surfaces,
            CompletableFuture<LoadedSurfaces> loaded) {}

    private record ShownSurface(@Nullable BspSurfaceMapping mapping, String material, boolean hidden, int flags) {
        boolean isCollapsed() {
            return hidden || !material.isEmpty() || flags >= 0;
        }

        boolean isOverlay() {
            return !hidden && (!material.isEmpty() || flags >= 0);
        }
    }

    public record Rebuild(
            Map<String, PackageEditDocument> documents,
            Map<String, ActorTransform> baselines,
            Set<String> affectedActors,
            Map<String, Path> assetPackages,
            Map<String, ActorLightingChanges> lighting) {
        public Rebuild(
                Map<String, PackageEditDocument> documents,
                Map<String, ActorTransform> baselines,
                Set<String> affectedActors,
                Map<String, Path> assetPackages) {
            this(documents, baselines, affectedActors, assetPackages, Map.of());
        }
    }

    @FunctionalInterface
    public interface BaseReader {
        @Nullable
        Float getBaseZ(String actorPath);
    }

    public interface TextureImporter {
        ImportedTexture create(Path image, String packageName, String textureName) throws IOException;
    }

    public record ImportedTexture(Path packageFile, String objectPath) {}

    public record Placement(
            String map,
            String mesh,
            @Nullable Path packageFile,
            ActorTransform transform,
            List<@Nullable MaterialChoice> materials,
            boolean liftFromScene) {
        public Placement {
            materials = Collections.unmodifiableList(new ArrayList<>(materials));
        }
    }

    public record MaterialChoice(String path, @Nullable Path packageFile) {}

    public enum ChangeKind {
        MOVED,
        ROTATED,
        SCALED,
        MATERIAL,
        NEW,
        COPY,
        MESH,
        TRANSFERRED,
        DELETED,
        LIGHT,
        COLOR,
        RADIUS,
        SURFACE,
        CONE,
        MAPPING,
        HIDDEN,
        TEXTURE,
        SHEET,
        BRUSH,
        TWO_SIDED,
        ONE_SIDED,
        UNLIT,
        LIT
    }

    public record Change(
            String path,
            String map,
            Set<ChangeKind> kinds,
            float distance,
            Vector3 location,
            @Nullable Vector3 source,
            String from,
            List<Integer> slots,
            String mesh) {
        public Change {
            kinds = Set.copyOf(kinds);
            slots = List.copyOf(slots);
        }

        public Change(
                String path,
                String map,
                Set<ChangeKind> kinds,
                float distance,
                Vector3 location,
                @Nullable Vector3 source,
                String from,
                List<Integer> slots) {
            this(path, map, kinds, distance, location, source, from, slots, "");
        }

        public String title() {
            return shortName(mesh.isBlank() ? path : mesh);
        }

        public String detail() {
            var parts = new ArrayList<String>();
            if (kinds.contains(ChangeKind.LIGHT) && kinds.contains(ChangeKind.COPY)) {
                parts.add("New light, copy of " + shortName(from));
            } else if (kinds.contains(ChangeKind.LIGHT) && kinds.contains(ChangeKind.DELETED)) {
                parts.add("Deleted light");
            } else if (kinds.contains(ChangeKind.LIGHT)) {
                parts.add(kinds.contains(ChangeKind.COLOR) ? "Light colour" : "Light");
            } else if (kinds.contains(ChangeKind.BRUSH)) {
                parts.add("Deleted brush");
            } else if (kinds.contains(ChangeKind.SURFACE)) {
                if (kinds.contains(ChangeKind.HIDDEN)) {
                    parts.add(kinds.contains(ChangeKind.SHEET) ? "Deleted sheet" : "Hidden");
                }
                if (kinds.contains(ChangeKind.TEXTURE)) {
                    parts.add("new texture " + shortName(from));
                }
                if (kinds.contains(ChangeKind.MAPPING)) {
                    parts.add("texture mapping");
                }
                if (kinds.contains(ChangeKind.TWO_SIDED)) {
                    parts.add("two-sided");
                }
                if (kinds.contains(ChangeKind.ONE_SIDED)) {
                    parts.add("two-sided off");
                }
                if (kinds.contains(ChangeKind.UNLIT)) {
                    parts.add("unlit");
                }
                if (kinds.contains(ChangeKind.LIT)) {
                    parts.add("unlit off");
                }
            } else if (kinds.contains(ChangeKind.DELETED)) {
                parts.add("Deleted");
            } else if (kinds.contains(ChangeKind.TRANSFERRED)) {
                parts.add("Moved here from " + mapOfPath(from));
            } else if (kinds.contains(ChangeKind.MESH)) {
                parts.add("New mesh");
            } else if (kinds.contains(ChangeKind.COPY)) {
                parts.add("Copy of " + shortName(from));
            } else if (kinds.contains(ChangeKind.NEW)) {
                parts.add("New");
            }
            if (kinds.contains(ChangeKind.MOVED)) {
                var units = Math.round(distance);
                parts.add(String.format(Locale.ROOT, units == 1 ? "moved %,d unit" : "moved %,d units", units));
            }
            if (kinds.contains(ChangeKind.ROTATED)) {
                parts.add("turned");
            }
            if (kinds.contains(ChangeKind.SCALED)) {
                parts.add("sized");
            }
            if (kinds.contains(ChangeKind.RADIUS)) {
                parts.add("radius");
            }
            if (kinds.contains(ChangeKind.CONE)) {
                parts.add("cone");
            }
            if (kinds.contains(ChangeKind.MATERIAL)) {
                var numbers = new ArrayList<String>();
                slots.forEach(slot -> numbers.add(String.valueOf(slot)));
                parts.add((slots.size() == 1 ? "material slot " : "material slots ") + String.join(", ", numbers));
            }
            var text = String.join(", ", parts);
            return (text.isEmpty() ? "Changed" : Character.toUpperCase(text.charAt(0)) + text.substring(1))
                    + (mesh.isBlank() || kinds.contains(ChangeKind.BRUSH) ? "" : " · " + shortName(path))
                    + " · "
                    + map;
        }
    }

    public enum PivotMode {
        CENTER,
        ACTIVE,
        INDIVIDUAL
    }

    record Asset(Path path, String fingerprint) {}

    private record FileStamp(long size, FileTime modified, String fingerprint) {}

    record State(ActorTransform transform, List<Integer> skins) {
        static State of(ActorRecord actor) {
            return new State(actor.transform(), actor.skinReferences());
        }
    }

    public record CarriedActor(
            String path, L2Swing.Kind kind, @Nullable String kept) {}

    sealed interface Command permits TransformCommand, DocumentCommand {}

    record TransformCommand(
            Map<String, State> before,
            Map<String, State> after,
            Map<String, String> notesBefore,
            Map<String, String> notesAfter)
            implements Command {
        TransformCommand(Map<String, State> before, Map<String, State> after) {
            this(before, after, Map.of(), Map.of());
        }
    }

    record Checkpoint(
            Map<String, Snapshot> documents,
            Map<String, State> originals,
            Set<String> selection,
            Map<String, Asset> assets) {}

    record DocumentCommand(Checkpoint before, Checkpoint after, Set<String> affected) implements Command {}

    record RecoveryState(
            Map<String, PackageEditDocument> documents,
            Map<String, State> originals,
            Map<String, Asset> assets,
            Map<String, Asset> savedAssets,
            Set<String> selection,
            List<Command> undo,
            List<Command> redo,
            PivotMode pivot,
            long revision,
            long committedCommands,
            Map<String, List<String>> groups) {
        RecoveryState(
                Map<String, PackageEditDocument> documents,
                Map<String, State> originals,
                Map<String, Asset> assets,
                Map<String, Asset> savedAssets,
                Set<String> selection,
                List<Command> undo,
                List<Command> redo,
                PivotMode pivot,
                long revision,
                long committedCommands) {
            this(
                    documents,
                    originals,
                    assets,
                    savedAssets,
                    selection,
                    undo,
                    redo,
                    pivot,
                    revision,
                    committedCommands,
                    Map.of());
        }
    }

    record ExportSnapshot(
            long revision,
            Map<Path, RecoveryBundle> documents,
            Map<Path, String> sourceHashes,
            List<AssetInput> assets,
            List<AssetInput> clientPackages,
            Set<Path> sourceChanges,
            boolean rebuildAllLighting,
            boolean rebuildAllBspLightmaps,
            boolean rebuildAllTerrainLighting,
            boolean keepNeighborBeast) {
        ExportSnapshot(
                long revision,
                Map<Path, RecoveryBundle> documents,
                Map<Path, String> sourceHashes,
                List<AssetInput> assets,
                Set<Path> sourceChanges) {
            this(revision, documents, sourceHashes, assets, List.of(), sourceChanges, false, false, false, false);
        }
    }

    @FunctionalInterface
    interface LightingProgress {
        void update(String map, int done, int total);
    }

    @FunctionalInterface
    interface BspProgress {
        void update(String map, int done, int total);
    }

    @FunctionalInterface
    interface TerrainProgress {
        void update(String map, int done, int total);
    }

    static String ofLights(String step, int count) {
        return count == 1 ? step : step + " of " + count + " lights";
    }

    static String turnLabel(int count) {
        return count == 1 ? "Turn light" : "Turn " + count + " lights";
    }

    static String shapeLabel(@Nullable LightShape before, LightShape after) {
        return before != null && before.rotation().equals(after.rotation()) ? "Light cone" : "Turn light";
    }

    static String moveLabel(int count) {
        return count == 1 ? "Move light" : "Move " + count + " lights";
    }

    static @Nullable Path clientRelative(Path clientRoot, Path file) {
        var root = realPath(clientRoot);
        var path = realPath(file);
        return path.startsWith(root) && !path.equals(root) ? root.relativize(path) : null;
    }

    static @Nullable String edgeLine(String map, List<String> neighbors) {
        if (neighbors.isEmpty()) {
            return null;
        }
        var names = neighbors.size() == 1
                ? neighbors.getFirst()
                : String.join(", ", neighbors.subList(0, neighbors.size() - 1)) + " and " + neighbors.getLast();
        return "Edits in "
                + map
                + " do not change the lighting of the neighbouring "
                + (neighbors.size() == 1 ? "map " : "maps ")
                + names
                + ": the saved lighting of each map ignores the other maps.";
    }

    static @Nullable String beastWorldLine(Report report) {
        var beast = report.beast();
        if (beast == null || beast.bspSurfaces() + beast.terrainOwners() == 0) {
            return null;
        }
        var parts = new ArrayList<String>();
        if (beast.bspSurfaces() > 0) {
            parts.add(beast.bspSurfaces() + (beast.bspSurfaces() == 1 ? " BSP surface" : " BSP surfaces"));
        }
        if (beast.terrainOwners() > 0) {
            parts.add(beast.terrainOwners() + (beast.terrainOwners() == 1 ? " Terrain owner" : " Terrain owners"));
        }
        var texels = beast.bspTexels() + beast.terrainTexels();
        return "Beast lightmaps of "
                + String.join(" and ", parts)
                + " updated where the edit changes their light in "
                + report.map()
                + " ("
                + texels
                + (texels == 1 ? " texel)." : " texels).");
    }

    static String beastWorldTip(Report report) {
        var beast = report.beast();
        if (beast == null) {
            return "";
        }
        var text = new StringBuilder("Cruma fits its approximate Beast baker to the saved lightmaps of each kind. ");
        if (beast.bspSlot0() != null) {
            text.append(String.format(
                    Locale.ROOT,
                    "BSP: on surfaces it did not use, the sun bake differs by %.0f of 255 on average (%.0f%% within"
                            + " 16). ",
                    beast.bspSlot0().meanAbsolute(),
                    100 * beast.bspSlot0().within16()));
        }
        if (beast.terrainSlot0() != null) {
            text.append(String.format(
                    Locale.ROOT,
                    "Terrain: on sectors it did not use, the sun bake differs by %.0f (%.0f%% within 16). ",
                    beast.terrainSlot0().meanAbsolute(),
                    100 * beast.terrainSlot0().within16()));
        }
        return text.append("Only texels whose sun, sky or local light rays meet a changed actor or Terrain take new"
                        + " values.")
                .toString();
    }

    static @Nullable String bspLine(BspLightmapRebuild.Report report) {
        if (report.lightmaps() == 0) {
            return report.notes().isEmpty() ? null : String.join(". ", report.notes()) + ".";
        }
        var line = new StringBuilder("BSP lightmaps rebuilt for ")
                .append(report.surfaces())
                .append(report.surfaces() == 1 ? " surface in " : " surfaces in ")
                .append(report.map())
                .append(" (")
                .append(report.lightmaps())
                .append(report.lightmaps() == 1 ? " lightmap, " : " lightmaps, ")
                .append(report.lights())
                .append(report.lights() == 1 ? " light rebuilt)" : " lights rebuilt)");
        for (var note : report.notes()) {
            line.append(". ").append(note);
        }
        return line.append('.').toString();
    }

    static @Nullable String terrainLine(TerrainActorShadows.Report report) {
        if (report.owners().isEmpty()) {
            return report.notes().isEmpty() ? null : String.join(". ", report.notes()) + ".";
        }
        var vertices = report.vertices();
        var line = new StringBuilder("Terrain lighting rebuilt for ")
                .append(vertices)
                .append(vertices == 1 ? " vertex in " : " vertices in ")
                .append(report.map())
                .append(report.all() ? " (all vertices" : " (actor shadows");
        if (report.owners().size() > 1) {
            line.append(", ").append(report.owners().size()).append(" Terrain owners");
        }
        line.append(')');
        for (var note : report.notes()) {
            line.append(". ").append(note);
        }
        return line.append('.').toString();
    }

    static @Nullable String lightingLine(Report report) {
        var updated = report.beast() == null ? 0 : report.beast().neighborsUpdated();
        if (report.rebuilt() == 0 && report.edited() == 0 && report.beast() != null && updated > 0) {
            return "Beast lightmaps updated where the edit changes the light of "
                    + updated
                    + (updated == 1 ? " nearby actor in " : " nearby actors in ")
                    + report.map()
                    + ".";
        }
        if (report.rebuilt() == 0 && report.edited() == 0) {
            return report.notes().isEmpty()
                    ? null
                    : report.map() + ": " + report.notes().getFirst() + ".";
        }
        var line = new StringBuilder("Lighting rebuilt for ")
                .append(report.rebuilt())
                .append(report.rebuilt() == 1 ? " actor in " : " actors in ")
                .append(report.map())
                .append(" (")
                .append(report.edited())
                .append(" edited, ")
                .append(report.neighbors())
                .append(" nearby)");
        if (report.noInstance() > 0) {
            line.append(". ").append(report.noInstance()).append(" use no baked lighting in this client");
        }
        if (report.beastKept() > 0) {
            line.append(". ")
                    .append(report.beastKept())
                    .append(report.beastKept() == 1 ? " nearby actor keeps its" : " nearby actors keep their")
                    .append(" Beast lightmap bake");
        }
        if (report.skipped() > 0) {
            line.append(". ").append(report.skipped()).append(" could not be written and keep their old lighting");
        }
        var beast = report.beast();
        if (beast != null && beast.baked() + beast.transferred() + beast.neighborsUpdated() > 0) {
            line.append(". Beast lightmaps approximated for ")
                    .append(beast.baked() + beast.transferred())
                    .append(beast.baked() + beast.transferred() == 1 ? " edited instance" : " edited instances");
            if (beast.neighborsUpdated() > 0) {
                line.append(" and updated where the edit changes the light of ")
                        .append(beast.neighborsUpdated())
                        .append(beast.neighborsUpdated() == 1 ? " nearby actor" : " nearby actors");
            }
        }
        return line.append('.').toString();
    }

    static String lightingTip(Report report) {
        var beast = report.beast();
        if (beast == null) {
            return "";
        }
        return String.format(
                Locale.ROOT,
                "Beast bakes cannot be rebuilt exactly: the baker is not part of the client. Cruma fitted its"
                    + " approximate baker to %d saved texels of this map. On texels it did not use, the sun bake"
                    + " differs by %.0f of 255 on average (%.0f%% within 16) and the local light bake by %.0f (%.0f%%"
                    + " within 16). %d edited instances started from a saved bake of the same mesh, %d were baked from"
                    + " scratch. %d nearby actors keep every texel, %d texels of %d nearby actors changed.",
                beast.samples(),
                beast.slot0().meanAbsolute(),
                100 * beast.slot0().within16(),
                beast.slot1().meanAbsolute(),
                100 * beast.slot1().within16(),
                beast.transferred(),
                beast.baked(),
                beast.neighborsUnchanged(),
                beast.texels(),
                beast.neighborsUpdated());
    }

    public static String mapOfPath(String path) {
        var dot = path.indexOf('.');
        return dot <= 0 ? path : path.substring(0, dot);
    }

    public static Vector3 center(List<float[]> polygons) {
        var sum = new double[3];
        var count = 0;
        for (var polygon : polygons) {
            for (var i = 0; i + 2 < polygon.length; i += 3) {
                sum[0] += polygon[i];
                sum[1] += polygon[i + 1];
                sum[2] += polygon[i + 2];
                count++;
            }
        }
        return count == 0
                ? Vector3.ZERO
                : new Vector3((float) (sum[0] / count), (float) (sum[1] / count), (float) (sum[2] / count));
    }

    static String shortName(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    public static String describeBrush(BrushFacts facts) {
        var text = new StringBuilder(facts.name()).append(" · ");
        text.append(
                switch (facts.csgOper()) {
                    case BrushFacts.ADD -> "adds solid";
                    case BrushFacts.SUBTRACT -> "cuts solid out";
                    default -> "CSG operation " + facts.csgOper();
                });
        if (facts.isSemisolid()) {
            text.append(" · semisolid");
        }
        if (facts.isNotSolid()) {
            text.append(" · not solid");
        }
        return text.append(" · ")
                .append(facts.sides())
                .append(facts.sides() == 1 ? " side" : " sides")
                .toString();
    }

    public EditingExportCoordinator getExportCoordinator() {
        if (exportCoordinator == null) {
            exportCoordinator = new EditingExportCoordinator(this);
        }
        return exportCoordinator;
    }

    public void pollExport() {
        if (exportCoordinator != null) {
            exportCoordinator.poll();
        }
    }

    public void cancelExport() {
        if (exportCoordinator != null) {
            exportCoordinator.cancel();
        }
    }

    public void closeExport() {
        if (exportCoordinator != null) {
            exportCoordinator.close();
        }
    }

    public boolean isExportLocked() {
        return exportLocked;
    }

    public boolean isRebuildAllLighting() {
        return rebuildAllLighting;
    }

    public void rebuildAllLighting(boolean value) {
        if (value != rebuildAllLighting) {
            rebuildAllLighting = value;
            preparedExport = null;
            revision++;
        }
    }

    public boolean isRebuildAllBspLightmaps() {
        return rebuildAllBspLightmaps;
    }

    public void rebuildAllBspLightmaps(boolean value) {
        if (value != rebuildAllBspLightmaps) {
            rebuildAllBspLightmaps = value;
            preparedExport = null;
            revision++;
        }
    }

    public boolean isRebuildAllTerrainLighting() {
        return rebuildAllTerrainLighting;
    }

    public void rebuildAllTerrainLighting(boolean value) {
        if (value != rebuildAllTerrainLighting) {
            rebuildAllTerrainLighting = value;
            preparedExport = null;
            revision++;
        }
    }

    public boolean isKeepNeighborBeast() {
        return keepNeighborBeast;
    }

    public void keepNeighborBeast(boolean value) {
        if (value != keepNeighborBeast) {
            keepNeighborBeast = value;
            preparedExport = null;
            revision++;
        }
    }

    public void enableLightingPreview() {
        if (lightingPreview == null) {
            lightingPreview = new EditingLightingPreview();
        }
    }

    public void closeLightingPreview() {
        if (lightingPreview != null) {
            lightingPreview.close();
            lightingPreview = null;
        }
    }

    public boolean isLightingPreviewBusy() {
        return lightingPreview != null && (lightingPreview.isBusy() || lightingPreview.isWaiting());
    }

    public void pollLightingPreview() {
        if (lightingPreview == null || gesture != null || exportLocked) {
            return;
        }
        var result = lightingPreview.poll(this::captureLightingJob);
        if (result == null || result.lighting().isEmpty() && result.surfaces().isEmpty()) {
            return;
        }
        if (result.revision() != revision && !isLightDragging()) {
            lightingPreview.retry(result);
            return;
        }
        var affected = new LinkedHashSet<String>();
        result.lighting()
                .values()
                .forEach(changes -> affected.addAll(changes.getActors().keySet()));
        try {
            if (result.actors() == null) {
                rebuild(affected, result.lighting());
            } else if (!result.actors().isEmpty()) {
                assetPackages();
                preview.replace(result.actors());
            }
        } catch (RuntimeException failure) {
        }
        result.surfaces().forEach((map, light) -> {
            try {
                preview.showLight(map, light);
            } catch (RuntimeException failure) {
            }
        });
    }

    public void pollSurfacePreview() {
        if (gesture != null || exportLocked) {
            return;
        }
        finishSurfaceJobs();
        if (surfacePreviewRevision == revision) {
            return;
        }
        surfacePreviewRevision = revision;
        var maps = new LinkedHashSet<>(shownSurfaces.keySet());
        documents.forEach((map, document) -> {
            if (!document.getEditedSurfaces().isEmpty()
                    || !document.getDeletedBrushes().isEmpty()) {
                maps.add(map);
            }
        });
        maps.removeIf(map -> !preview.isSurfaceMapDrawn(map));
        maps.forEach(this::showSurfacesOf);
    }

    public List<String> getSurfaceMapsNotShown() {
        var result = new ArrayList<String>();
        var maps = new TreeSet<>(shownSurfaces.keySet());
        documents.forEach((map, document) -> {
            if (!document.getEditedSurfaces().isEmpty()
                    || !document.getDeletedBrushes().isEmpty()) {
                maps.add(map);
            }
        });
        for (var map : maps) {
            if (surfaceJobs.containsKey(map) || !preview.isSurfaceMapDrawn(map)) {
                continue;
            }
            var current = getSurfaceState(map);
            if (current == null || !current.equals(shownSurfaces.getOrDefault(map, Map.of()))) {
                result.add(map);
            }
        }
        return result;
    }

    public boolean isSurfacePreviewSettled() {
        return surfaceJobs.isEmpty() && (surfacePreviewRevision == revision || gesture != null || exportLocked);
    }

    public void awaitSurfaceReads() {
        for (var job : surfaceJobs.values()) {
            try {
                job.loaded().join();
            } catch (RuntimeException failed) {
            }
        }
        surfaceJobs.clear();
    }

    public void restoreSurfacePreviews() {
        awaitSurfaceReads();
        for (var entry : shownSurfaces.entrySet()) {
            var document = documents.get(entry.getKey());
            if (document == null || !preview.isSurfaceMapDrawn(entry.getKey())) {
                continue;
            }
            try {
                var surfaces = new SurfacePreview(
                        document.previewSourceSurfaceBody(),
                        null,
                        Map.of(),
                        entry.getValue().keySet(),
                        Set.of(),
                        Set.of());
                preview.showSurfaces(entry.getKey(), surfaces, preview.loadSurfaces(entry.getKey(), surfaces));
            } catch (IOException | RuntimeException failure) {
            }
        }
        shownSurfaces.clear();
    }

    boolean isLightDragging() {
        return lightGesture != null || placementGesture != null || shapeGesture != null;
    }

    Settings getLightingSettings() {
        if (lightingSettings == null) {
            lightingSettings = ActorLightingCapture.readSettings(clientRoot);
        }
        return lightingSettings;
    }

    void exportLocked(boolean value) {
        if (value) {
            endMaterialPreview();
        }
        exportLocked = value;
    }

    public long getRevision() {
        return revision;
    }

    public long getSelectionRequests() {
        return selectionRequests;
    }

    public long getDraftRevision() {
        return revision + groups.getRevision();
    }

    public long getGroupsRevision() {
        return groups.getRevision();
    }

    public List<String> getGroupNames() {
        return groups.getNames(this::exists);
    }

    public Set<String> getGroupMembers(String name) {
        return groups.getMembers(name, this::exists);
    }

    public @Nullable String findGroupOf(String actor) {
        return groups.findGroupOf(actor);
    }

    public String nextGroupName() {
        return groups.nextName();
    }

    public String groupSelection(String name) {
        return group(name, selection);
    }

    public String group(String name, Collection<String> actors) {
        requireEditable();
        return groups.create(name, List.copyOf(actors));
    }

    public void ungroup(String name) {
        groups.ungroup(name);
    }

    public void renameGroup(String name, String next) {
        groups.rename(name, next);
    }

    public @Nullable String getSelectedGroup() {
        return groups.findSelectedWhole(selection, this::isSelectable);
    }

    public @Nullable String getEnteredGroup() {
        return groups.getEntered(selection, this::isSelectable);
    }

    public void selectGroup(String name, boolean additive) {
        selectMany(getGroupMembers(name), additive);
    }

    public void selectInGroup(String name, String actor) {
        select(actor, false);
        groups.enter(name);
    }

    public Set<String> getClickTargets(String actor) {
        return groups.click(actor, Set.copyOf(selection), this::isSelectable);
    }

    public Set<String> getGroupTargets(String actor) {
        var group = groups.findGroupOf(actor);
        if (group == null || group.equals(getEnteredGroup())) {
            return Set.of(actor);
        }
        return getSelectableMembers(group);
    }

    public boolean leaveGroup() {
        var members = groups.leave(Set.copyOf(selection), this::isSelectable);
        if (members == null) {
            return false;
        }
        selectMany(members, false);
        return true;
    }

    public Set<String> getSelectableMembers(String name) {
        return groups.getMembers(name, this::isSelectable);
    }

    public boolean isGestureActive() {
        return gesture != null || exportLocked || stepStart != null;
    }

    public boolean isLightDragActive() {
        return lightGesture != null || placementGesture != null;
    }

    public long getCommittedCommands() {
        return committedCommands;
    }

    public EditingTimeline getTimeline() {
        return timeline;
    }

    public GroundProbe getGroundProbe() {
        if (groundProbe == null) {
            groundProbe = new GroundProbe(clientRoot);
            groundProbe.setLoadedMaps(loadedMaps);
        }
        return groundProbe;
    }

    public void setMapFiles(EditingMaps files) {
        mapFiles = files;
        loadedMaps = Set.copyOf(files.getSources(loadedMaps));
        if (groundProbe != null) {
            groundProbe.setLoadedMaps(loadedMaps);
        }
    }

    public EditingMaps getMapFiles() {
        return mapFiles;
    }

    public void setLoadedMaps(Collection<String> maps) {
        loadedMaps = Set.copyOf(mapFiles.getSources(maps));
        if (groundProbe != null) {
            groundProbe.setLoadedMaps(loadedMaps);
        }
    }

    public String getMapAt(double x, double y) {
        return mapFiles.getSource(WorldTile.mapAt(x, y, loadedMaps));
    }

    public void closeGroundProbe() {
        if (groundProbe != null) {
            groundProbe.close();
            groundProbe = null;
        }
    }

    RecoveryState getRecoveryState() {
        if (gesture != null) {
            throw new IllegalStateException("Finish the current drag before saving recovery state.");
        }
        endMaterialPreview();
        return new RecoveryState(
                Map.copyOf(documents),
                Map.copyOf(originals),
                Map.copyOf(assets),
                Map.copyOf(savedAssets),
                Collections.unmodifiableSet(new LinkedHashSet<>(selection)),
                List.copyOf(undo),
                List.copyOf(redo),
                pivotMode,
                revision,
                committedCommands,
                groups.getStored());
    }

    void installRecovery(RecoveryState recovered) {
        requireEditable();
        if (gesture != null) {
            throw new IllegalStateException("Finish the current drag before restoring recovery state.");
        }
        documents.clear();
        documents.putAll(recovered.documents());
        originals.clear();
        originals.putAll(recovered.originals());
        assets.clear();
        assets.putAll(recovered.assets());
        savedAssets = Map.copyOf(recovered.savedAssets());
        selection.clear();
        selection.addAll(recovered.selection());
        undo.clear();
        undo.addAll(recovered.undo());
        redo.clear();
        redo.addAll(recovered.redo());
        pivotMode = recovered.pivot();
        previewDeltas.clear();
        preparedExport = null;
        exportabilityRevision = -1;
        revision = Math.max(revision, recovered.revision()) + 1;
        committedCommands = recovered.committedCommands();
        historyGeneration++;
        lastLabel = "";
        groups.restore(recovered.groups());
    }

    public boolean willExportMap(String mapName) {
        var document = documents.get(mapName);
        return document != null && (document.isDirty() || document.hasChangesFromSource());
    }

    public boolean isMapDirty(String mapName) {
        var document = documents.get(mapName);
        return document != null && document.isDirty();
    }

    public boolean hasSourceChanges(String mapName) {
        var document = documents.get(mapName);
        return document != null && document.hasChangesFromSource();
    }

    public Set<String> getOpenedMaps() {
        return Set.copyOf(documents.keySet());
    }

    public List<LightRecord> getLights(String mapName) {
        return documentFor(mapFiles.getSource(mapName) + ".lights").getLights();
    }

    public Optional<LightRecord> getLight(String path) {
        var document = documents.get(mapOfPath(path));
        return document == null ? Optional.empty() : document.getLight(path);
    }

    public List<LightRecord> getOpenedChangedLights() {
        var lights = new ArrayList<LightRecord>();
        for (var document : documents.values()) {
            lights.addAll(document.getChangedSavedLights());
        }
        return lights;
    }

    public List<LightRecord> getOpenedCreatedLights() {
        var lights = new ArrayList<LightRecord>();
        for (var document : documents.values()) {
            document.getLights().stream().filter(LightRecord::created).forEach(lights::add);
        }
        return lights;
    }

    public @Nullable String getCarrierOf(String path) {
        return documentFor(path).getCarrierOf(path);
    }

    public List<LightRecord> getOpenedDeletedLights() {
        var lights = new ArrayList<LightRecord>();
        for (var document : documents.values()) {
            lights.addAll(document.getDeletedLights());
        }
        return lights;
    }

    public String getLightColorRestriction(String path, LightColor color) {
        return documentFor(path).getLightColorRestriction(path, color);
    }

    public void previewLightColor(String path, LightColor color) {
        previewLightColors(Map.of(path, color));
    }

    public void previewLightColors(Map<String, LightColor> colors) {
        requireEditable();
        openLightMaps(colors.keySet());
        if (lightGesture == null || !lightGestureColors.keySet().equals(colors.keySet())) {
            finishLightColor();
            finishGesture();
            lightGesture = checkpoint();
            lightGestureLights = lightRecords();
            var start = new LinkedHashMap<String, LightColor>();
            colors.keySet()
                    .forEach(path -> start.put(
                            path, documentFor(path).getLight(path).orElseThrow().color()));
            lightGestureColors = start;
        }
        colors.forEach((path, color) -> documentFor(path).updateLightColor(path, color));
        revision++;
        followLightPreview(lightGestureLights);
    }

    public void finishLightColor() {
        if (lightGesture == null) {
            return;
        }
        var before = lightGesture;
        lightGesture = null;
        var changed = false;
        for (var entry : lightGestureColors.entrySet()) {
            var light = getLight(entry.getKey());
            changed |= light.isPresent() && !light.orElseThrow().color().equals(entry.getValue());
        }
        if (changed) {
            remember(
                    new DocumentCommand(before, checkpoint(), Set.of()),
                    ofLights("Light colour", lightGestureColors.size()));
            revision++;
            scheduleLightPreview(lightGestureLights);
        }
        scheduleDragShown();
    }

    public void setLightColor(String path, LightColor color, String label) {
        setLightColors(Map.of(path, color), label);
    }

    public void setLightColors(Map<String, LightColor> colors, String label) {
        finishLightColor();
        openLightMaps(colors.keySet());
        mutate(
                label,
                Set.of(),
                () -> colors.forEach((path, color) -> {
                    var document = documentFor(path);
                    refuse(path, colors.size(), () -> document.getLightColorRestriction(path, color));
                    document.updateLightColor(path, color);
                }));
    }

    public String getLightMoveRestriction(String path) {
        return documentFor(path).getLightMoveRestriction(path);
    }

    public void resetLight(String path, String label) {
        resetLights(List.of(path), label);
    }

    public void resetLights(Collection<String> paths, String label) {
        requireEditable();
        var lights = new ArrayList<LightRecord>();
        for (var path : paths) {
            lights.add(
                    getLight(path).orElseThrow(() -> new IllegalArgumentException("Unknown light " + shortName(path))));
        }
        finishLightColor();
        finishLightPlacement();
        finishLightShape();
        mutate(label, Set.of(), () -> {
            for (var light : lights) {
                var path = light.sourcePath();
                var document = documentFor(path);
                document.updateLightPlacement(path, light.sourcePlacement());
                document.updateLightColor(path, light.sourceColor());
                document.updateLightShape(path, light.sourceShape());
            }
        });
    }

    public String getLightShapeRestriction(String path, LightShape shape) {
        return documentFor(path).getLightShapeRestriction(path, shape);
    }

    public void previewLightShape(String path, LightShape shape) {
        previewLightShapes(Map.of(path, shape));
    }

    public void previewLightShapes(Map<String, LightShape> shapes) {
        requireEditable();
        openLightMaps(shapes.keySet());
        if (shapeGesture == null || !shapeGestureValues.keySet().equals(shapes.keySet())) {
            finishLightShape();
            finishGesture();
            shapeGesture = checkpoint();
            shapeGestureLights = lightRecords();
            var start = new LinkedHashMap<String, LightShape>();
            shapes.keySet()
                    .forEach(path -> start.put(
                            path, documentFor(path).getLight(path).orElseThrow().shape()));
            shapeGestureValues = start;
        }
        shapes.forEach((path, shape) -> documentFor(path).updateLightShape(path, shape));
        revision++;
        followLightPreview(shapeGestureLights);
    }

    public void finishLightShape() {
        if (shapeGesture == null) {
            return;
        }
        var before = shapeGesture;
        shapeGesture = null;
        var changed = false;
        var turned = false;
        for (var entry : shapeGestureValues.entrySet()) {
            var light = getLight(entry.getKey());
            if (light.isPresent() && !light.orElseThrow().shape().equals(entry.getValue())) {
                changed = true;
                turned |= shapeLabel(entry.getValue(), light.orElseThrow().shape())
                        .equals("Turn light");
            }
        }
        if (changed) {
            var count = shapeGestureValues.size();
            remember(
                    new DocumentCommand(before, checkpoint(), Set.of()),
                    turned ? turnLabel(count) : ofLights("Light cone", count));
            revision++;
            scheduleLightPreview(shapeGestureLights);
        }
        scheduleDragShown();
    }

    public void cancelLightShape() {
        if (shapeGesture == null) {
            return;
        }
        var before = shapeGesture;
        shapeGesture = null;
        restore(before);
        revision++;
        scheduleDragShown();
    }

    public void setLightShape(String path, LightShape shape, String label) {
        setLightShapes(Map.of(path, shape), label);
    }

    public void setLightShapes(Map<String, LightShape> shapes, String label) {
        finishLightShape();
        openLightMaps(shapes.keySet());
        mutate(
                label,
                Set.of(),
                () -> shapes.forEach((path, shape) -> {
                    var document = documentFor(path);
                    refuse(path, shapes.size(), () -> document.getLightShapeRestriction(path, shape));
                    document.updateLightShape(path, shape);
                }));
    }

    public String getLightPlacementRestriction(String path, LightPlacement placement) {
        return documentFor(path).getLightPlacementRestriction(path, placement);
    }

    public void previewLightPlacement(String path, LightPlacement placement) {
        previewLightPlacements(Map.of(path, placement));
    }

    public void previewLightPlacements(Map<String, LightPlacement> placements) {
        requireEditable();
        openLightMaps(placements.keySet());
        if (placementGesture == null || !placementGestureValues.keySet().equals(placements.keySet())) {
            finishLightPlacement();
            finishGesture();
            placementGesture = checkpoint();
            placementGestureLights = lightRecords();
            var start = new LinkedHashMap<String, LightPlacement>();
            placements
                    .keySet()
                    .forEach(path -> start.put(
                            path, documentFor(path).getLight(path).orElseThrow().placement()));
            placementGestureValues = start;
        }
        placements.forEach((path, placement) -> documentFor(path).updateLightPlacement(path, placement));
        revision++;
        followLightPreview(placementGestureLights);
    }

    public void finishLightPlacement() {
        if (placementGesture == null) {
            return;
        }
        var before = placementGesture;
        placementGesture = null;
        var changed = false;
        var moved = false;
        for (var entry : placementGestureValues.entrySet()) {
            var light = getLight(entry.getKey());
            if (light.isPresent() && !light.orElseThrow().placement().equals(entry.getValue())) {
                changed = true;
                moved |= !light.orElseThrow()
                        .placement()
                        .location()
                        .equals(entry.getValue().location());
            }
        }
        if (changed) {
            var count = placementGestureValues.size();
            remember(
                    new DocumentCommand(before, checkpoint(), Set.of()),
                    moved ? moveLabel(count) : ofLights("Light radius", count));
            revision++;
            scheduleLightPreview(placementGestureLights);
        }
        scheduleDragShown();
    }

    public void cancelLightPlacement() {
        if (placementGesture == null) {
            return;
        }
        var before = placementGesture;
        placementGesture = null;
        restore(before);
        revision++;
        scheduleDragShown();
    }

    public void setLightPlacement(String path, LightPlacement placement, String label) {
        setLightPlacements(Map.of(path, placement), label);
    }

    public void setLightPlacements(Map<String, LightPlacement> placements, String label) {
        finishLightPlacement();
        openLightMaps(placements.keySet());
        mutate(
                label,
                Set.of(),
                () -> placements.forEach((path, placement) -> {
                    var document = documentFor(path);
                    refuse(path, placements.size(), () -> document.getLightPlacementRestriction(path, placement));
                    document.updateLightPlacement(path, placement);
                }));
    }

    public String getLightCopyRestriction(String path) {
        return documentFor(path).getLightCopyRestriction(path);
    }

    public String duplicateLight(String path, LightPlacement placement, String label) {
        return duplicateLights(Map.of(path, placement), label).getFirst();
    }

    public @Nullable LightPlacement placeBeside(String path) {
        var light = getLight(path).orElseThrow(() -> new IllegalArgumentException("There is no such light."));
        var at = light.location();
        var step = 64f;
        for (var offset : new float[][] {{step, 0}, {-step, 0}, {0, step}, {0, -step}}) {
            var place = new LightPlacement(new Vector3(at.x() + offset[0], at.y() + offset[1], at.z()), light.radius());
            if (getLightPlacementRestriction(path, place).isEmpty()) {
                return place;
            }
        }
        return null;
    }

    public List<String> duplicateLights(Map<String, LightPlacement> placements, String label) {
        finishLightColor();
        finishLightPlacement();
        openLightMaps(placements.keySet());
        var created = new ArrayList<String>();
        mutate(label, Set.of(), () -> {
            created.clear();
            for (var entry : placements.entrySet()) {
                created.add(documentFor(entry.getKey())
                        .duplicateLight(entry.getKey(), entry.getValue())
                        .sourcePath());
            }
        });
        return List.copyOf(created);
    }

    public float getSolidBelow(String mapName, Vector3 point, float depth) {
        var document = documents.get(mapFiles.getSource(mapName));
        return document == null ? Float.NaN : document.getSolidBelow(point, depth);
    }

    @FunctionalInterface
    public interface Floors {
        float getSolidBelow(Vector3 point, float depth);
    }

    public Floors getFloors(String mapName) {
        return documentFor(mapFiles.getSource(mapName) + ".floors")::getSolidBelow;
    }

    public LightTemplate getLightTemplate(String mapName, boolean spot) {
        return documentFor(mapFiles.getSource(mapName) + ".lights").getLightTemplate(spot);
    }

    public String addLight(String template, LightPlacement placement, LightColor color, String label) {
        return addLight(template, placement, color, null, label);
    }

    public String addLight(
            String template, LightPlacement placement, LightColor color, @Nullable Vector3 aim, String label) {
        finishLightColor();
        finishLightPlacement();
        var created = new String[1];
        mutate(label, Set.of(), () -> {
            var document = documentFor(template);
            var light = document.duplicateLight(template, placement);
            created[0] = light.sourcePath();
            if (!light.color().equals(color)) {
                document.updateLightColor(created[0], color);
            }
            if (aim != null && light.shape().isSpot()) {
                var turned = light.shape().withRotation(light.shape().facing(aim));
                if (document.getLightShapeRestriction(created[0], turned).isEmpty()) {
                    document.updateLightShape(created[0], turned);
                }
            }
        });
        return created[0];
    }

    public String getLightDeleteRestriction(String path) {
        return documentFor(path).getLightDeleteRestriction(path);
    }

    public void deleteLight(String path) {
        deleteLight(path, "Delete light");
    }

    public void deleteLight(String path, String label) {
        deleteLights(List.of(path), label);
    }

    public void deleteLights(Collection<String> paths, String label) {
        finishLightColor();
        finishLightPlacement();
        openLightMaps(paths);
        mutate(label, Set.of(), () -> {
            for (var path : paths) {
                documentFor(path).deleteLight(path);
            }
        });
    }

    public record BrushRef(String map, int brush) {}

    public record SurfaceRef(String map, int surface) {
        private static final String NAME = ".Surface";

        public static @Nullable SurfaceRef ofPath(String path) {
            var at = path.indexOf(NAME);
            if (at <= 0 || path.indexOf('.') != at) {
                return null;
            }
            try {
                return new SurfaceRef(path.substring(0, at), Integer.parseInt(path.substring(at + NAME.length())));
            } catch (NumberFormatException notASurface) {
                return null;
            }
        }

        public String path() {
            return map + NAME + surface;
        }
    }

    public String getSurfaceEditRestriction(String map) {
        return documentFor(map + SurfaceRef.NAME).getSurfaceEditRestriction();
    }

    public @Nullable SurfaceRef pickSurface(String map, Vector3 point) {
        var source = mapFiles.getSource(map);
        var surface = documentFor(source + SurfaceRef.NAME).pickSurface(point, 2);
        return surface < 0 ? null : new SurfaceRef(source, surface);
    }

    public @Nullable SurfaceRef pickChangedSurface(String map, Vector3 point) {
        var source = mapFiles.getSource(map);
        var document = documents.get(source);
        if (document == null
                || document.getEditedSurfaces().isEmpty()
                        && document.getDeletedBrushes().isEmpty()) {
            return null;
        }
        var surface = document.pickSurface(point, 2);
        return surface < 0 ? null : new SurfaceRef(source, surface);
    }

    public String getSurfaceRestriction(SurfaceRef surface) {
        return documentFor(surface.path()).getSurfaceRestriction(surface.surface());
    }

    public BspSurfaceMapping getSurfaceMapping(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSurfaceMapping(surface.surface());
    }

    public BspSurfaceMapping getSourceSurfaceMapping(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSourceSurfaceMapping(surface.surface());
    }

    public Vector3 getSurfaceNormal(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSurfaceNormal(surface.surface());
    }

    public List<float[]> getSurfacePolygons(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSurfacePolygons(surface.surface());
    }

    public float @Nullable [] getSurfaceTile(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSurfaceTile(surface.surface());
    }

    public int editSurfaces(
            List<SurfaceRef> surfaces,
            String label,
            BiFunction<BspSurfaceMapping, Vector3, @Nullable BspSurfaceMapping> change) {
        var changes = new LinkedHashMap<SurfaceRef, BspSurfaceMapping>();
        try {
            for (var surface : surfaces) {
                var document = documentFor(surface.path());
                if (!document.getSurfaceRestriction(surface.surface()).isEmpty()) {
                    continue;
                }
                var current = document.getSurfaceMapping(surface.surface());
                var next = change.apply(current, document.getSurfaceNormal(surface.surface()));
                if (next != null && !next.equals(current) && next.restriction().isEmpty()) {
                    changes.put(surface, next);
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(unreadable.getMessage(), unreadable);
        }
        if (changes.isEmpty()) {
            return 0;
        }
        mutate(label, Set.of(), () -> {
            for (var entry : changes.entrySet()) {
                documentFor(entry.getKey().path())
                        .updateSurfaceMapping(entry.getKey().surface(), entry.getValue());
            }
        });
        return changes.size();
    }

    public void resetSurfaces(List<SurfaceRef> surfaces, String label) {
        mutate(label, Set.of(), () -> {
            for (var surface : surfaces) {
                var document = documentFor(surface.path());
                if (document.isSurfaceInDeletedBrush(surface.surface())) {
                    document.updateBrushDeleted(document.getSurfaceBrush(surface.surface()), false);
                } else {
                    document.revertSurface(surface.surface());
                }
            }
        });
    }

    public List<SurfaceRef> getEditedSurfaces() {
        var result = new ArrayList<SurfaceRef>();
        documents.forEach((map, document) ->
                document.getEditedSurfaces().forEach(surface -> result.add(new SurfaceRef(map, surface))));
        return result;
    }

    public String getSurfaceHideRestriction(SurfaceRef surface) {
        return documentFor(surface.path()).getSurfaceHideRestriction(surface.surface());
    }

    public boolean isSurfaceHidden(SurfaceRef surface) {
        var document = documents.get(surface.map());
        return document != null && document.isSurfaceHidden(surface.surface());
    }

    public List<SurfaceRef> getHiddenSurfaces() {
        var result = new ArrayList<SurfaceRef>();
        documents.forEach((map, document) ->
                document.getHiddenSurfaces().forEach(surface -> result.add(new SurfaceRef(map, surface))));
        return result;
    }

    public int setSurfacesHidden(List<SurfaceRef> surfaces, boolean hidden, String label) {
        var chosen = new ArrayList<SurfaceRef>();
        for (var surface : surfaces) {
            var document = documentFor(surface.path());
            if (document.isSurfaceHidden(surface.surface()) == hidden) {
                continue;
            }
            if (hidden && !document.getSurfaceHideRestriction(surface.surface()).isEmpty()) {
                continue;
            }
            chosen.add(surface);
        }
        if (chosen.isEmpty()) {
            return 0;
        }
        mutate(label, Set.of(), () -> {
            for (var surface : chosen) {
                documentFor(surface.path()).updateSurfaceHidden(surface.surface(), hidden);
            }
        });
        return chosen.size();
    }

    public String getSurfaceFlagRestriction(SurfaceRef surface, BspSurfaceFlag flag, boolean on) {
        return documentFor(surface.path()).getSurfaceFlagRestriction(surface.surface(), flag, on);
    }

    public boolean isSurfaceFlagOn(SurfaceRef surface, BspSurfaceFlag flag) throws IOException {
        return documentFor(surface.path()).isSurfaceFlagOn(surface.surface(), flag);
    }

    public boolean isSourceSurfaceFlagOn(SurfaceRef surface, BspSurfaceFlag flag) throws IOException {
        return documentFor(surface.path()).isSourceSurfaceFlagOn(surface.surface(), flag);
    }

    public int setSurfaceFlag(List<SurfaceRef> surfaces, BspSurfaceFlag flag, boolean on, String label) {
        return setSurfaceFlags(surfaces, Map.of(flag, on), label);
    }

    public int setSurfaceFlags(List<SurfaceRef> surfaces, Map<BspSurfaceFlag, Boolean> flags, String label) {
        requireEditable();
        var chosen = new LinkedHashMap<SurfaceRef, List<BspSurfaceFlag>>();
        for (var surface : new LinkedHashSet<>(surfaces)) {
            var document = documentFor(surface.path());
            var changing = new ArrayList<BspSurfaceFlag>();
            for (var entry : new TreeMap<>(flags).entrySet()) {
                try {
                    if (document.isSurfaceFlagOn(surface.surface(), entry.getKey()) == entry.getValue()
                            || !document.getSurfaceFlagRestriction(surface.surface(), entry.getKey(), entry.getValue())
                                    .isEmpty()) {
                        continue;
                    }
                } catch (IOException | IllegalArgumentException unreadable) {
                    continue;
                }
                changing.add(entry.getKey());
            }
            if (!changing.isEmpty()) {
                chosen.put(surface, changing);
            }
        }
        if (chosen.isEmpty()) {
            return 0;
        }
        mutate(label, Set.of(), () -> {
            for (var entry : chosen.entrySet()) {
                var document = documentFor(entry.getKey().path());
                for (var flag : entry.getValue()) {
                    document.updateSurfaceFlag(entry.getKey().surface(), flag, flags.get(flag));
                }
            }
        });
        return chosen.size();
    }

    public boolean isSurfaceSheet(SurfaceRef surface) {
        return documentFor(surface.path()).isSurfaceSheet(surface.surface());
    }

    public String getSurfaceDeleteRestriction(SurfaceRef surface) {
        return documentFor(surface.path()).getSurfaceDeleteRestriction(surface.surface());
    }

    public int deleteSurfaces(List<SurfaceRef> surfaces, String label) {
        var chosen = new ArrayList<SurfaceRef>();
        for (var surface : surfaces) {
            var document = documentFor(surface.path());
            if (!document.isSurfaceHidden(surface.surface())
                    && document.getSurfaceDeleteRestriction(surface.surface()).isEmpty()) {
                chosen.add(surface);
            }
        }
        if (chosen.isEmpty()) {
            return 0;
        }
        mutate(label, Set.of(), () -> {
            for (var surface : chosen) {
                documentFor(surface.path()).updateSurfaceHidden(surface.surface(), true);
            }
        });
        return chosen.size();
    }

    public @Nullable BrushRef getSurfaceBrush(SurfaceRef surface) {
        var document = documentFor(surface.path());
        if (document.isSurfaceSheet(surface.surface())) {
            return null;
        }
        var brush = document.getSurfaceBrush(surface.surface());
        return brush == 0 ? null : new BrushRef(surface.map(), brush);
    }

    public String getBrushName(BrushRef brush) {
        return documentFor(brush.map() + SurfaceRef.NAME).getBrushName(brush.brush());
    }

    public List<SurfaceRef> getBrushSurfaces(BrushRef brush) {
        return documentFor(brush.map() + SurfaceRef.NAME).getBrushSurfaces(brush.brush()).stream()
                .map(surface -> new SurfaceRef(brush.map(), surface))
                .toList();
    }

    public String getBrushDeleteRestriction(BrushRef brush) {
        return documentFor(brush.map() + SurfaceRef.NAME).getBrushDeleteRestriction(brush.brush());
    }

    public boolean prepareBrushes(String map) {
        var document = documents.get(map);
        if (document == null || document.isBrushDeleteRead()) {
            return true;
        }
        if (brushReads.add(document)) {
            preview.getSurfaceExecutor().execute(document::readBrushDelete);
        }
        return document.isBrushDeleteRead();
    }

    public @Nullable BrushRef getSurfaceBrushActor(SurfaceRef surface) {
        var brush = documentFor(surface.path()).getSurfaceBrush(surface.surface());
        return brush == 0 ? null : new BrushRef(surface.map(), brush);
    }

    public @Nullable BrushFacts getBrushFacts(BrushRef brush) {
        return documentFor(brush.map() + SurfaceRef.NAME).describeBrush(brush.brush());
    }

    public List<SurfaceRef> selectSurfaces(
            List<SurfaceRef> chosen, BspSurfaceSelect.Command command, @Nullable String map) {
        var byMap = new TreeMap<String, IntArrayList>();
        for (var surface : chosen) {
            byMap.computeIfAbsent(surface.map(), _ -> new IntArrayList()).add(surface.surface());
        }
        if (byMap.isEmpty()) {
            if (map == null || command != BspSurfaceSelect.Command.ALL && command != BspSurfaceSelect.Command.REVERSE) {
                return List.of();
            }
            byMap.put(map, new IntArrayList());
        }
        var materials = new HashSet<String>();
        if (command == BspSurfaceSelect.Command.TEXTURE) {
            for (var surface : chosen) {
                try {
                    materials.add(documentFor(surface.path())
                            .getSurfaceMaterialPath(surface.surface())
                            .toLowerCase(Locale.ROOT));
                } catch (IOException | RuntimeException unreadable) {
                }
            }
        }
        var results = new HashMap<String, BitSet>();
        var added = new ArrayList<SurfaceRef>();
        for (var entry : byMap.entrySet()) {
            int[] selected;
            try {
                selected = documentFor(entry.getKey() + SurfaceRef.NAME)
                        .selectSurfaces(command, entry.getValue().toArray(), materials);
            } catch (IOException unreadable) {
                throw new IllegalArgumentException(unreadable.getMessage(), unreadable);
            }
            var bits = new BitSet();
            var before = new BitSet();
            entry.getValue().forEach(before::set);
            for (var surface : selected) {
                bits.set(surface);
                if (!before.get(surface)) {
                    added.add(new SurfaceRef(entry.getKey(), surface));
                }
            }
            results.put(entry.getKey(), bits);
        }
        var result = new ArrayList<SurfaceRef>(chosen.size() + added.size());
        var kept = new HashSet<SurfaceRef>();
        for (var surface : chosen) {
            if (results.get(surface.map()).get(surface.surface()) && kept.add(surface)) {
                result.add(surface);
            }
        }
        result.addAll(added);
        return result;
    }

    public record Outline(float[] points, int[] edges, int firstEdges) {
        public static final Outline NONE = new Outline(new float[0], new int[0], 0);
    }

    public Outline getSurfaceOutline(List<SurfaceRef> surfaces) {
        var points = new FloatArrayList();
        var edges = new IntArrayList();
        var places = new HashMap<String, IntIntHashMap>();
        var firstEdges = 0;
        for (var i = 0; i < surfaces.size(); i++) {
            var surface = surfaces.get(i);
            var document = documents.get(surface.map());
            if (document != null) {
                try {
                    var outline = document.getSurfaceOutline(surface.surface());
                    var place = places.computeIfAbsent(surface.map(), _ -> new IntIntHashMap());
                    var coordinates = new float[3];
                    for (var point : outline) {
                        var at = place.getIfAbsent(point, -1);
                        if (at < 0) {
                            at = points.size() / 3;
                            place.put(point, at);
                            document.getBspPoint(point, coordinates, 0);
                            points.addAll(coordinates);
                        }
                        edges.add(at);
                    }
                } catch (IOException | RuntimeException unreadable) {
                }
            }
            if (i == 0) {
                firstEdges = edges.size() / 2;
            }
        }
        return new Outline(points.toArray(), edges.toArray(), firstEdges);
    }

    public String getSurfaceItemName(SurfaceRef surface) {
        return documentFor(surface.path()).getSurfaceItemName(surface.surface());
    }

    public List<BrushRef> getSurfaceBrushActors(List<SurfaceRef> surfaces) {
        var result = new LinkedHashSet<BrushRef>();
        for (var surface : surfaces) {
            var document = documents.get(surface.map());
            var brush = document == null ? 0 : document.getSurfaceBrush(surface.surface());
            if (brush != 0) {
                result.add(new BrushRef(surface.map(), brush));
            }
        }
        return List.copyOf(result);
    }

    public Outline getBrushWireframe(List<BrushRef> brushes) {
        var points = new FloatArrayList();
        var edges = new IntArrayList();
        for (var brush : brushes) {
            var document = documents.get(brush.map());
            if (document == null) {
                continue;
            }
            try {
                var wireframe = document.getBrushWireframe(brush.brush());
                var first = points.size() / 3;
                points.addAll(wireframe.points());
                for (var edge : wireframe.edges()) {
                    edges.add(first + edge);
                }
            } catch (RuntimeException unreadable) {
            }
        }
        return new Outline(points.toArray(), edges.toArray(), 0);
    }

    public boolean isSurfaceInDeletedBrush(SurfaceRef surface) {
        var document = documents.get(surface.map());
        return document != null && document.isSurfaceInDeletedBrush(surface.surface());
    }

    public List<BrushRef> getDeletedBrushes() {
        var result = new ArrayList<BrushRef>();
        documents.forEach(
                (map, document) -> document.getDeletedBrushes().forEach(brush -> result.add(new BrushRef(map, brush))));
        return result;
    }

    public int deleteBrushes(List<BrushRef> brushes, List<SurfaceRef> sheets, String label) {
        var chosenBrushes = new ArrayList<BrushRef>();
        for (var brush : new LinkedHashSet<>(brushes)) {
            var document = documentFor(brush.map() + SurfaceRef.NAME);
            if (!document.isBrushDeleted(brush.brush())
                    && document.getBrushDeleteRestriction(brush.brush()).isEmpty()) {
                chosenBrushes.add(brush);
            }
        }
        var chosenSheets = new ArrayList<SurfaceRef>();
        for (var surface : sheets) {
            var document = documentFor(surface.path());
            if (!document.isSurfaceHidden(surface.surface())
                    && document.getSurfaceDeleteRestriction(surface.surface()).isEmpty()) {
                chosenSheets.add(surface);
            }
        }
        if (chosenBrushes.isEmpty() && chosenSheets.isEmpty()) {
            return 0;
        }
        mutate(label, Set.of(), () -> {
            for (var brush : chosenBrushes) {
                documentFor(brush.map() + SurfaceRef.NAME).updateBrushDeleted(brush.brush(), true);
            }
            for (var surface : chosenSheets) {
                documentFor(surface.path()).updateSurfaceHidden(surface.surface(), true);
            }
        });
        return chosenBrushes.size() + chosenSheets.size();
    }

    public void showAll(List<SurfaceRef> surfaces, List<BrushRef> brushes, String label) {
        var hidden = surfaces.stream().filter(this::isSurfaceHidden).toList();
        var deleted = brushes.stream()
                .filter(brush -> documentFor(brush.map() + SurfaceRef.NAME).isBrushDeleted(brush.brush()))
                .toList();
        if (hidden.isEmpty() && deleted.isEmpty()) {
            return;
        }
        mutate(label, Set.of(), () -> {
            for (var surface : hidden) {
                documentFor(surface.path()).updateSurfaceHidden(surface.surface(), false);
            }
            for (var brush : deleted) {
                documentFor(brush.map() + SurfaceRef.NAME).updateBrushDeleted(brush.brush(), false);
            }
        });
    }

    public void restoreBrushes(List<BrushRef> brushes, String label) {
        var chosen = brushes.stream()
                .filter(brush -> documentFor(brush.map() + SurfaceRef.NAME).isBrushDeleted(brush.brush()))
                .toList();
        if (chosen.isEmpty()) {
            return;
        }
        mutate(label, Set.of(), () -> {
            for (var brush : chosen) {
                documentFor(brush.map() + SurfaceRef.NAME).updateBrushDeleted(brush.brush(), false);
            }
        });
    }

    public String getSurfaceMaterialRestriction(SurfaceRef surface) {
        return documentFor(surface.path()).getSurfaceMaterialRestriction(surface.surface());
    }

    public String getSurfaceMaterial(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSurfaceMaterialPath(surface.surface());
    }

    public String getSourceSurfaceMaterial(SurfaceRef surface) throws IOException {
        return documentFor(surface.path()).getSourceSurfaceMaterialPath(surface.surface());
    }

    public int setSurfaceMaterials(List<SurfaceRef> surfaces, @Nullable MaterialChoice material, String label) {
        requireEditable();
        var chosen = surfacesTaking(surfaces, material);
        if (chosen.isEmpty()) {
            return 0;
        }
        var size = material == null ? null : getMappingSize(material);
        mutate(label, Set.of(), () -> giveSurfaceMaterial(chosen, material, size));
        return chosen.size();
    }

    public void previewSurfaceMaterial(List<SurfaceRef> surfaces, @Nullable MaterialChoice material) {
        requireEditable();
        var chosen = surfacesTaking(surfaces, material);
        if (chosen.isEmpty()) {
            return;
        }
        var size = material == null ? null : getMappingSize(material);
        var before = checkpoint();
        var maps = new LinkedHashSet<String>();
        chosen.forEach(surface -> maps.add(surface.map()));
        try {
            giveSurfaceMaterial(chosen, material, size);
        } catch (IOException | RuntimeException failure) {
            restore(before);
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        materialPreview = before;
        materialPreviewMaps = Set.copyOf(maps);
        maps.forEach(this::showSurfacesOf);
    }

    public Set<String> getSourceActors(String mapName) {
        var result = new LinkedHashSet<String>();
        for (var path : originals.keySet()) {
            if (path.startsWith(mapName + '.')) {
                result.add(path);
            }
        }
        return result;
    }

    public Path getClientRoot() {
        return clientRoot;
    }

    public boolean isClientPackage(Path file) {
        return clientRelative(clientRoot, file) != null;
    }

    long getAssetHashReads() {
        return assetHashReads;
    }

    public PivotMode getPivotMode() {
        return pivotMode;
    }

    public void setPivotMode(PivotMode value) {
        requireEditable();
        finishGesture();
        pivotMode = value;
        revision++;
    }

    public void setTextureImporter(TextureImporter importer) {
        textureImporter = importer;
    }

    public boolean canImportTextures() {
        return textureImporter != null;
    }

    public Set<String> getSelection() {
        return Collections.unmodifiableSet(selection);
    }

    public String getPrimary() {
        return selection.isEmpty() ? null : selection.iterator().next();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public boolean isDirty() {
        return !assets.equals(savedAssets) || documents.values().stream().anyMatch(PackageEditDocument::isDirty);
    }

    public int getDirtyMaps() {
        return (int)
                documents.values().stream().filter(PackageEditDocument::isDirty).count();
    }

    public @Nullable Boolean checkEditable(String path) {
        var dot = path.indexOf('.');
        if (dot <= 0 || !documents.containsKey(path.substring(0, dot))) {
            return null;
        }
        return originals.containsKey(path);
    }

    public boolean isPawn(String path) {
        var dot = path.indexOf('.');
        var document = dot <= 0 ? null : documents.get(path.substring(0, dot));
        var record = document == null ? null : document.findActor(path).orElse(null);
        return record != null ? record.pawn() : EditorUi.isPawn(path);
    }

    public boolean isEmitter(String path) {
        try {
            return documentFor(path).isEmitter(path);
        } catch (IllegalArgumentException noMap) {
            return false;
        }
    }

    public int getEmitterCount(String path) {
        return documentFor(path).getEmitterCount(path);
    }

    public boolean isNonStatic(String path) {
        return documentFor(path).isNonStatic(path);
    }

    public ActorRecord getActor(String path) {
        return documentFor(path)
                .findActor(path)
                .orElseThrow(() -> new IllegalArgumentException(UserMessages.NOT_IN_MAP + path));
    }

    public List<ActorRecord> getActorsOf(String mapName) {
        return documentFor(mapName + ".actors").getActors();
    }

    public List<String> getMeshesOf(String mapName) throws IOException {
        return documentFor(mapName + ".meshes").getMeshReferences();
    }

    public String transformRestriction(String path, ActorTransform value) {
        var reason = changeRestriction(path);
        return reason.isEmpty() ? documentFor(path).transformRestriction(path, value) : reason;
    }

    public String deleteRestriction(String path) {
        var reason = changeRestriction(path);
        return reason.isEmpty() ? documentFor(path).deleteRestriction(path) : reason;
    }

    public String copyRestriction(String path) {
        var reason = changeRestriction(path);
        return reason.isEmpty() ? documentFor(path).copyRestriction(path) : reason;
    }

    public List<MaterialReference> getMaterials(String path) {
        return documentFor(path).getMaterials();
    }

    public void restoreMapPreview(String mapName) {
        shownSurfaces.remove(mapName);
        overlayFailures.remove(mapName);
        surfaceGenerations.merge(mapName, 1L, Long::sum);
        surfacePreviewRevision = -1;
        var document = documents.get(mapName);
        if (document == null) {
            return;
        }
        var affected = new LinkedHashSet<>(document.getDeletedActorPaths());
        for (var actor : document.getActors()) {
            var baseline = originals.get(actor.sourcePath());
            if (actor.created()
                    || baseline != null
                            && (!actor.transform().equals(baseline.transform())
                                    || !actor.skinReferences().equals(baseline.skins()))) {
                affected.add(actor.sourcePath());
            }
        }
        if (affected.isEmpty()) {
            return;
        }
        rebuild(affected);
        var updates = new LinkedHashMap<String, float[]>();
        var restores = new LinkedHashSet<String>();
        var placements = new LinkedHashMap<String, ActorTransform>();
        for (var path : affected) {
            if (preview.isMoving(path)) {
                document.findActor(path).ifPresent(actor -> placements.put(path, actor.transform()));
                continue;
            }
            var current = document.findActor(path).orElse(null);
            var baseline = originals.get(path);
            if (current == null || baseline == null || current.transform().equals(baseline.transform())) {
                previewDeltas.remove(path);
                restores.add(path);
            } else {
                var delta = EditorTransforms.delta(baseline.transform(), current.transform());
                previewDeltas.put(path, delta);
                updates.put(path, delta);
            }
        }
        preview.applyChanges(Map.copyOf(updates), Set.copyOf(restores), previewDeltaView);
        if (!placements.isEmpty()) {
            preview.place(Collections.unmodifiableMap(placements));
        }
    }

    public @Nullable String takeStepNote() {
        var note = stepNote;
        stepNote = null;
        return note;
    }

    public @Nullable String getMotionDescription(String path) {
        var text = describeOwnMotion(path);
        var uncopied = copyNotes.get(path);
        return text == null || uncopied == null ? text : text + " " + uncopied;
    }

    public List<CarriedActor> getCarriedActors(String carrier) {
        return getCarriedActors(carrier, true);
    }

    public String getStructuralRestriction() {
        return getPrimary() == null ? "" : documentFor(getPrimary()).getStructuralRestriction();
    }

    public String getMaterialName(String path, int objectIndex) {
        return getMaterials(path).stream()
                .filter(m -> m.objectIndex() == objectIndex)
                .map(MaterialReference::sourcePath)
                .findFirst()
                .orElse("Unresolved reference " + objectIndex);
    }

    public void select(String path, boolean additive) {
        requireEditable();
        finishGesture();
        selectionRequests++;
        if (path == null) {
            if (!additive) {
                selection.clear();
            }
        } else {
            var actor = getActor(path);
            if (!actor.editable()) {
                throw new IllegalArgumentException(actor.restriction());
            }
            if (!preview.accepts(path)) {
                throw new IllegalArgumentException("This placement's tile has not loaded for Edit yet.");
            }
            if (!additive) {
                selection.clear();
            }
            if (!selection.add(path) && additive) {
                selection.remove(path);
            }
        }
        revision++;
    }

    public void selectMany(Iterable<String> paths, boolean additive) {
        requireEditable();
        finishGesture();
        selectionRequests++;
        var accepted = new LinkedHashSet<String>();
        for (var path : paths) {
            if (hidden.contains(path) || locked.contains(path)) {
                continue;
            }
            try {
                if (getActor(path).editable() && preview.accepts(path)) {
                    accepted.add(path);
                }
            } catch (IllegalArgumentException unsupported) {
            }
        }
        if (!additive) {
            selection.clear();
        }
        selection.addAll(accepted);
        revision++;
    }

    public void setTransform(String path, ActorTransform value) {
        requireEditable();
        execute(Map.of(path, new State(value, getActor(path).skinReferences())), null);
    }

    public void setMaterial(String path, int slot, String materialPath) {
        requireEditable();
        var current = getActor(path);
        var skins = new ArrayList<>(current.skinReferences());
        if (slot < 0 || slot > 63) {
            throw new IllegalArgumentException("Material slot must be between 0 and 63.");
        }
        var reference = materialPath == null
                ? 0
                : getMaterials(path).stream()
                        .filter(m -> m.sourcePath().equals(materialPath))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalArgumentException("Material must already be referenced by this map."))
                        .objectIndex();
        if ((slot < skins.size() ? skins.get(slot) : 0) == reference) {
            return;
        }
        while (skins.size() <= slot) {
            skins.add(0);
        }
        skins.set(slot, reference);
        var authoredSlots = originals.get(path).skins().size();
        while (skins.size() > authoredSlots && skins.getLast() == 0) {
            skins.removeLast();
        }
        execute(Map.of(path, new State(current.transform(), List.copyOf(skins))), "Material of " + shortName(path));
    }

    public Vector3 getPivot() {
        if (selection.isEmpty()) {
            return Vector3.ZERO;
        }
        if (pivotMode == PivotMode.ACTIVE) {
            return getActor(getPrimary()).transform().location();
        }
        double x = 0, y = 0, z = 0;
        for (var path : selection) {
            var p = getActor(path).transform().location();
            x += p.x();
            y += p.y();
            z += p.z();
        }
        return new Vector3(
                (float) (x / selection.size()), (float) (y / selection.size()), (float) (z / selection.size()));
    }

    public void move(Vector3 offset) {
        move(offset, Map.of());
    }

    public void move(Vector3 offset, Map<String, Float> heights) {
        requireEditable();
        var after = new LinkedHashMap<String, State>();
        for (var path : movable()) {
            var value = getActor(path);
            var t = value.transform();
            var p = t.location();
            var dz = heights.getOrDefault(path, offset.z());
            after.put(
                    path,
                    new State(
                            new ActorTransform(
                                    new Vector3(p.x() + offset.x(), p.y() + offset.y(), p.z() + dz),
                                    t.rotation(),
                                    t.drawScale(),
                                    t.drawScale3d(),
                                    t.prePivot()),
                            value.skinReferences()));
        }
        execute(after, null);
    }

    public void transformGroup(float[] delta) {
        requireEditable();
        execute(transformStates(snapshot(), delta), null);
    }

    public void snapPivotTo(Vector3 point) {
        requireEditable();
        var pivot = getPivot();
        move(new Vector3(point.x() - pivot.x(), point.y() - pivot.y(), point.z() - pivot.z()));
    }

    public List<String> duplicateSelected(Vector3 offset) {
        requireEditable();
        stepNote = null;
        if (selection.isEmpty()) {
            return List.of();
        }
        var sources = List.copyOf(selection);
        var placements = new LinkedHashMap<String, ActorTransform>();
        for (var path : sources) {
            placements.put(path, translated(getActor(path).transform(), offset));
        }
        return copyWithCarried(placements, "Duplicate " + subject(sources));
    }

    public List<String> copyActors(Map<String, ActorTransform> placements, String label) {
        requireEditable();
        stepNote = null;
        if (placements.isEmpty()) {
            return List.of();
        }
        return copyWithCarried(placements, label);
    }

    public String createFromSelectedMesh(Vector3 position) {
        requireEditable();
        if (getPrimary() == null) {
            throw new IllegalArgumentException("Select a mesh to use as the asset template.");
        }
        var source = getActor(getPrimary());
        var doc = documentFor(getPrimary());
        var result = new ArrayList<String>();
        var affected = new LinkedHashSet<String>();
        mutate("Create " + shortName(source.meshSourcePath()), affected, () -> {
            var t = new ActorTransform(position, new Rotator(0, 0, 0), 1, Vector3.ONE, Vector3.ZERO);
            validateTransform(source.transform(), t);
            var created = doc.createStaticMeshActor(source.meshSourcePath(), t);
            originals.put(created.sourcePath(), State.of(created));
            affected.add(created.sourcePath());
            result.add(created.sourcePath());
            selection.clear();
            selection.add(created.sourcePath());
        });
        return result.getFirst();
    }

    public void deleteSelected() {
        requireEditable();
        if (selection.isEmpty()) {
            return;
        }
        var affected = new LinkedHashSet<>(selection);
        mutate("Delete " + subject(selection), affected, () -> {
            for (var path : List.copyOf(selection)) {
                documentFor(path).deleteActor(path);
            }
            selection.clear();
        });
        groups.removeDeleted(affected, this::exists);
    }

    public String createFromMeshPackage(Path packageFile, String meshPath, Vector3 position) {
        requireEditable();
        if (getPrimary() == null) {
            throw new IllegalArgumentException("Select an actor in the destination map first.");
        }
        var source = getActor(getPrimary());
        var doc = documentFor(getPrimary());
        var result = new ArrayList<String>();
        var affected = new LinkedHashSet<String>();
        mutate("Create " + shortName(meshPath), affected, () -> {
            var t = new ActorTransform(position, new Rotator(0, 0, 0), 1, Vector3.ONE, Vector3.ZERO);
            validateTransform(source.transform(), t);
            var mesh = doc.importMesh(packageFile, meshPath);
            registerAsset(mesh, packageFile);
            var created = doc.createStaticMeshActor(mesh, t);
            originals.put(created.sourcePath(), State.of(created));
            affected.add(created.sourcePath());
            result.add(created.sourcePath());
            selection.clear();
            selection.add(created.sourcePath());
        });
        return result.getFirst();
    }

    public void transferSelected(String targetMap) {
        requireEditable();
        if (selection.isEmpty()) {
            return;
        }
        if (selection.stream().anyMatch(this::isPawn)) {
            throw new IllegalArgumentException(PackageEditDocument.PAWN_MAP);
        }
        var target = documentFor(targetMap + ".target");
        var sources = List.copyOf(selection);
        if (sources.stream().allMatch(path -> documentFor(path) == target)) {
            return;
        }
        var targets = new LinkedHashMap<String, String>();
        sources.forEach(path -> targets.put(path, targetMap));
        transfer(targets, "Move " + subject(sources) + " to " + targetMap);
    }

    public List<String> moveToTheirTiles(Predicate<String> loaded) {
        requireEditable();
        var targets = new LinkedHashMap<String, String>();
        for (var path : movable()) {
            var tile = getTileOutsideMap(path);
            if (tile != null && loaded.test(tile)) {
                targets.put(path, tile);
            }
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        var tiles = List.copyOf(new LinkedHashSet<>(targets.values()));
        for (var tile : tiles) {
            documentFor(tile + ".target");
        }
        transfer(
                targets,
                tiles.size() == 1
                        ? "Move " + subject(targets.keySet()) + " to " + tiles.getFirst()
                        : "Move " + subject(targets.keySet()) + " to their tiles");
        return tiles;
    }

    public @Nullable String getTileOutsideMap(String path) {
        var dot = path.indexOf('.');
        if (dot <= 0 || keepsMap(path)) {
            return null;
        }
        var map = mapFiles.getTile(path.substring(0, dot));
        if (!map.matches("[0-9]+_[0-9]+")) {
            return null;
        }
        var location = getActor(path).transform().location();
        var x = (int) Math.floor(location.x() / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_X;
        var y = (int) Math.floor(location.y() / WorldDimensions.TILE_SIZE) + WorldDimensions.TILE_ZERO_COORD_Y;
        var tile = x + "_" + y;
        return tile.equals(map) ? null : mapFiles.getSource(tile);
    }

    public String place(
            String mapName, String meshPath, @Nullable Path packageFile, Vector3 position, @Nullable BaseReader base) {
        requireEditable();
        var doc = documentFor(mapName + ".place");
        var result = new ArrayList<String>();
        var affected = new LinkedHashSet<String>();
        mutate("Create " + shortName(meshPath), affected, () -> {
            var mesh = referencedMesh(doc, meshPath);
            if (mesh == null) {
                var file = packageFile != null ? packageFile : clientMeshPackage(meshPath);
                if (file == null) {
                    throw new IllegalArgumentException(
                            shortName(meshPath) + " belongs to another map and cannot be placed in " + mapName + ".");
                }
                mesh = doc.importMesh(file, meshPath);
                registerAsset(mesh, file);
            }
            var created = doc.createStaticMeshActor(
                    mesh, new ActorTransform(position, new Rotator(0, 0, 0), 1, Vector3.ONE, Vector3.ZERO));
            var path = created.sourcePath();
            originals.put(path, State.of(created));
            affected.add(path);
            result.add(path);
            selection.clear();
            selection.add(path);
            if (base != null) {
                rebuild(affected);
                publish(affected);
                var bottom = base.getBaseZ(path);
                if (bottom != null && Float.isFinite(bottom) && bottom != position.z()) {
                    var t = created.transform();
                    doc.updateTransform(
                            path,
                            new ActorTransform(
                                    new Vector3(position.x(), position.y(), position.z() + position.z() - bottom),
                                    t.rotation(),
                                    t.drawScale(),
                                    t.drawScale3d(),
                                    t.prePivot()));
                    originals.put(path, State.of(getActor(path)));
                }
            }
        });
        return result.getFirst();
    }

    public List<String> placeMany(List<Placement> placements, String label, @Nullable BaseReader base) {
        requireEditable();
        if (placements.isEmpty()) {
            return List.of();
        }
        for (var placement : placements) {
            documentFor(placement.map() + ".place");
        }
        var result = new ArrayList<String>();
        var affected = new LinkedHashSet<String>();
        mutate(label, affected, () -> {
            var lifted = new ArrayList<String>();
            for (var placement : placements) {
                var doc = documentFor(placement.map() + ".place");
                var mesh = meshFor(doc, placement.map(), placement.mesh(), placement.packageFile());
                var created = doc.createStaticMeshActor(mesh, placement.transform());
                var path = created.sourcePath();
                for (var slot = 0; slot < placement.materials().size(); slot++) {
                    var material = placement.materials().get(slot);
                    if (material != null) {
                        doc.updateSkin(path, slot, materialFor(doc, material));
                    }
                }
                originals.put(path, State.of(getActor(path)));
                affected.add(path);
                result.add(path);
                if (placement.liftFromScene()) {
                    lifted.add(path);
                }
            }
            selection.clear();
            selection.addAll(result);
            if (base != null && !lifted.isEmpty()) {
                rebuild(affected);
                publish(affected);
                for (var path : lifted) {
                    var t = getActor(path).transform();
                    var bottom = base.getBaseZ(path);
                    if (bottom != null
                            && Float.isFinite(bottom)
                            && bottom != t.location().z()) {
                        var p = t.location();
                        documentFor(path)
                                .updateTransform(
                                        path,
                                        new ActorTransform(
                                                new Vector3(p.x(), p.y(), p.z() + p.z() - bottom),
                                                t.rotation(),
                                                t.drawScale(),
                                                t.drawScale3d(),
                                                t.prePivot()));
                        originals.put(path, State.of(getActor(path)));
                    }
                }
            }
        });
        return List.copyOf(result);
    }

    public void deleteActors(Collection<String> paths, String label) {
        requireEditable();
        var chosen = new ArrayList<String>();
        for (var path : paths) {
            if (!locked.contains(path)) {
                chosen.add(path);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        chosen.forEach(this::documentFor);
        var affected = new LinkedHashSet<>(chosen);
        mutate(label, affected, () -> {
            for (var path : chosen) {
                documentFor(path).deleteActor(path);
                selection.remove(path);
            }
        });
        groups.removeDeleted(chosen, this::exists);
    }

    public List<String> replaceMesh(String meshPath, @Nullable Path packageFile, int keptSlots) {
        requireEditable();
        var sources = movable();
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Select unlocked actors to replace their mesh.");
        }
        return replaceMesh(sources, meshPath, packageFile, keptSlots);
    }

    public List<String> replaceMesh(
            Collection<String> actors, String meshPath, @Nullable Path packageFile, int keptSlots) {
        requireEditable();
        var sources = new ArrayList<String>();
        for (var path : actors) {
            if (!locked.contains(path)) {
                sources.add(path);
            }
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("The actors are locked, so their mesh stays.");
        }
        var affected = new LinkedHashSet<>(sources);
        var result = new ArrayList<String>();
        var replaced = new LinkedHashMap<String, String>();
        mutate("Replace mesh of " + subject(sources), affected, () -> {
            replaced.clear();
            for (var path : sources) {
                var doc = documentFor(path);
                var mesh = meshFor(doc, path.substring(0, path.indexOf('.')), meshPath, packageFile);
                var created = doc.replaceActorMesh(path, mesh, keptSlots);
                originals.put(created.sourcePath(), State.of(created));
                affected.add(created.sourcePath());
                replaced.put(path, created.sourcePath());
                result.add(created.sourcePath());
            }
            var next = new ArrayList<String>();
            for (var path : selection) {
                next.add(replaced.getOrDefault(path, path));
            }
            selection.clear();
            selection.addAll(next);
        });
        groups.moved(replaced);
        return List.copyOf(result);
    }

    public void setMaterials(Collection<String> paths, int slot, @Nullable MaterialChoice material, String label) {
        requireEditable();
        if (slot < 0 || slot > 63) {
            throw new IllegalArgumentException("Material slot must be between 0 and 63.");
        }
        var chosen = new ArrayList<String>();
        for (var path : paths) {
            if (!Objects.equals(
                    slotMaterial(path, slot),
                    material == null ? null : material.path().toLowerCase(Locale.ROOT))) {
                chosen.add(path);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        var affected = new LinkedHashSet<>(chosen);
        mutate(label, affected, () -> {
            for (var path : chosen) {
                var doc = documentFor(path);
                doc.updateSkin(path, slot, material == null ? null : materialFor(doc, material));
            }
        });
    }

    public void previewMaterial(String path, int slot, @Nullable MaterialChoice material) {
        requireEditable();
        var before = checkpoint();
        var affected = Set.of(path);
        try {
            var doc = documentFor(path);
            doc.updateSkin(path, slot, material == null ? null : materialFor(doc, material));
            rebuild(affected);
        } catch (IOException | RuntimeException failure) {
            restore(before);
            rebuild(affected);
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
        materialPreview = before;
        materialPreviewActors = affected;
    }

    public void endMaterialPreview() {
        var before = materialPreview;
        if (before == null) {
            return;
        }
        materialPreview = null;
        restore(before);
        rebuild(materialPreviewActors);
        materialPreviewActors = Set.of();
        if (!materialPreviewMaps.isEmpty()) {
            surfacePreviewRevision = -1;
        }
        materialPreviewMaps = Set.of();
    }

    public boolean isPreviewingMaterial() {
        return materialPreview != null;
    }

    public @Nullable Path getPackageFile(String objectPath) {
        var dot = objectPath.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var asset = assets.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(objectPath.substring(0, dot)))
                .findFirst()
                .orElse(null);
        if (asset != null) {
            return asset.getValue().path();
        }
        try {
            return clientPackage(objectPath, ".usx", ".utx");
        } catch (IOException unreadable) {
            return null;
        }
    }

    public Map<String, List<String>> getMeshReferences() {
        var result = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        documents.forEach((map, document) -> {
            try {
                for (var mesh : document.getMeshReferences()) {
                    result.computeIfAbsent(mesh, ignored -> new ArrayList<>()).add(map);
                }
            } catch (IOException unreadable) {
            }
        });
        return result;
    }

    public void readMap(String mapName) throws IOException {
        var source = mapFiles.getSource(mapName);
        readMaps.putIfAbsent(source, PackageEditDocument.open(sourceOfMap(source)));
    }

    public void openMap(String mapName) {
        documentFor(mapFiles.getSource(mapName) + ".map");
    }

    public void importMaterial(String actorPath, int slot, Path packageFile, String materialPath) {
        requireEditable();
        documentFor(actorPath);
        var affected = new LinkedHashSet<>(Set.of(actorPath));
        mutate("Material of " + shortName(actorPath), affected, () -> {
            var doc = documentFor(actorPath);
            var reference = doc.importMaterial(packageFile, materialPath);
            doc.updateSkin(actorPath, slot, reference.sourcePath());
            registerAsset(reference.sourcePath(), packageFile);
        });
    }

    public void importTexture(String actorPath, int slot, Path image, String packageName, String textureName) {
        requireEditable();
        if (textureImporter == null) {
            throw new IllegalArgumentException("Image import is unavailable.");
        }
        try {
            var imported = textureImporter.create(image, packageName, textureName);
            importMaterial(actorPath, slot, imported.packageFile(), imported.objectPath());
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    public MaterialChoice importImage(Path image, String packageName, String textureName) {
        requireEditable();
        if (textureImporter == null) {
            throw new IllegalArgumentException("Image import is unavailable.");
        }
        try {
            var imported = textureImporter.create(image, packageName, textureName);
            return new MaterialChoice(imported.objectPath(), imported.packageFile());
        } catch (IOException failure) {
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    public void beginGesture() {
        requireEditable();
        if (gesture == null) {
            gestureNotes = Map.copyOf(lightsLeftBehind);
            leftThisStep.clear();
            gesture = snapshot();
            gestureCarried.clear();
        }
    }

    public void previewGesture(float[] delta) {
        beginGesture();
        apply(withGestureCarried(transformStates(gesture, delta)));
        revision++;
    }

    public void previewTranslationGesture(Vector3 offset) {
        previewTranslationGesture(offset, Map.of());
    }

    public void previewTranslationGesture(Vector3 offset, Map<String, Float> heights) {
        beginGesture();
        var after = new LinkedHashMap<String, State>();
        gesture.forEach((path, state) -> {
            var t = state.transform();
            var p = t.location();
            var dz = heights.getOrDefault(path, offset.z());
            after.put(
                    path,
                    new State(
                            new ActorTransform(
                                    new Vector3(p.x() + offset.x(), p.y() + offset.y(), p.z() + dz),
                                    t.rotation(),
                                    t.drawScale(),
                                    t.drawScale3d(),
                                    t.prePivot()),
                            state.skins()));
        });
        apply(withGestureCarried(after));
        revision++;
    }

    public void finishGesture() {
        finishLightColor();
        finishLightPlacement();
        finishLightShape();
        if (gesture != null) {
            var before = new LinkedHashMap<>(gesture);
            gestureCarried.forEach(before::putIfAbsent);
            var after = new LinkedHashMap<String, State>();
            before.keySet().forEach(path -> after.put(path, stateOf(path)));
            var direct = new LinkedHashMap<String, State>();
            gesture.keySet().forEach(path -> direct.put(path, after.get(path)));
            dropUnchangedFollowers(before, after, gesture.keySet());
            if (!before.equals(after)) {
                remember(
                        new TransformCommand(
                                Map.copyOf(before), Map.copyOf(after), gestureNotes, Map.copyOf(lightsLeftBehind)),
                        transformLabel(gesture, direct));
            }
            stepNote = leftThisStep.isEmpty() ? null : String.join(" ", leftThisStep.values());
            var moved = after.keySet();
            gesture = null;
            gestureCarried.clear();
            scheduleLightingPreview(moved);
        }
    }

    public void cancelGesture() {
        requireEditable();
        if (gesture != null) {
            var start = new LinkedHashMap<>(gesture);
            gestureCarried.forEach(start::putIfAbsent);
            apply(start);
            lightsLeftBehind.clear();
            lightsLeftBehind.putAll(gestureNotes);
            gesture = null;
            gestureCarried.clear();
            revision++;
        }
    }

    public void resetSelected() {
        requireEditable();
        var values = new LinkedHashMap<String, State>();
        for (var path : selection) {
            values.put(path, originals.get(path));
        }
        execute(values, "Revert " + subject(values.keySet()));
    }

    public void transformActors(Map<String, ActorTransform> values, String label) {
        requireEditable();
        var after = new LinkedHashMap<String, State>();
        values.forEach((path, value) -> {
            if (!locked.contains(path)) {
                after.put(path, new State(value, getActor(path).skinReferences()));
            }
        });
        if (!after.isEmpty()) {
            execute(after, label);
        }
    }

    public void mirror(int axis) {
        mirror(movable(), axis);
    }

    public void mirror(Collection<String> actors, int axis) {
        requireEditable();
        if (axis < 0 || axis > 2) {
            throw new IllegalArgumentException("Mirror needs the axis X, Y or Z.");
        }
        var after = new LinkedHashMap<String, State>();
        for (var path : actors) {
            if (locked.contains(path)) {
                continue;
            }
            var value = getActor(path);
            var t = value.transform();
            var s3 = t.drawScale3d();
            var flipped = new Vector3(
                    axis == 0 ? -s3.x() : s3.x(), axis == 1 ? -s3.y() : s3.y(), axis == 2 ? -s3.z() : s3.z());
            after.put(
                    path,
                    new State(
                            new ActorTransform(t.location(), t.rotation(), t.drawScale(), flipped, t.prePivot()),
                            value.skinReferences()));
        }
        if (!after.isEmpty()) {
            execute(after, "Mirror " + subject(after.keySet()) + " on " + "XYZ".charAt(axis));
        }
    }

    public void randomize(long seed, boolean yaw, boolean scale, float minScale, float maxScale) {
        randomize(movable(), seed, yaw, scale, minScale, maxScale);
    }

    public void randomize(
            Collection<String> actors, long seed, boolean yaw, boolean scale, float minScale, float maxScale) {
        requireEditable();
        if (scale && (!(minScale > 0) || !(maxScale >= minScale) || !Float.isFinite(maxScale))) {
            throw new IllegalArgumentException(
                    "The scale range needs two positive factors, the first not above the second.");
        }
        var random = new Random(seed);
        var after = new LinkedHashMap<String, State>();
        for (var path : actors) {
            if (locked.contains(path)) {
                continue;
            }
            var value = getActor(path);
            var t = value.transform();
            var rotation = yaw
                    ? new Rotator(
                            t.rotation().pitch(),
                            random.nextInt(65536),
                            t.rotation().roll())
                    : t.rotation();
            var factor = minScale + random.nextFloat() * (maxScale - minScale);
            var source = originals.get(path);
            var drawScale = scale ? (source == null ? 1 : source.transform().drawScale()) * factor : t.drawScale();
            after.put(
                    path,
                    new State(
                            new ActorTransform(t.location(), rotation, drawScale, t.drawScale3d(), t.prePivot()),
                            value.skinReferences()));
        }
        if (!after.isEmpty()) {
            execute(after, "Randomize " + subject(after.keySet()));
        }
    }

    public void beginStep() {
        requireEditable();
        finishGesture();
        if (stepStart != null) {
            return;
        }
        stepStart = checkpoint();
        stepRecorded = recordedCommands;
        stepOpened.clear();
        stepOriginals.clear();
    }

    public String endStep(@Nullable String label) {
        if (stepStart == null) {
            return "";
        }
        finishGesture();
        var start = stepStart;
        stepStart = null;
        var count = (int) Math.min(recordedCommands - stepRecorded, undo.size());
        if (count <= 0) {
            return "";
        }
        if (count > 1) {
            var affected = new LinkedHashSet<String>();
            for (var i = 0; i < count; i++) {
                affected.addAll(commandActors(undo.pop()));
            }
            var documentsBefore = new LinkedHashMap<>(start.documents());
            documentsBefore.putAll(stepOpened);
            var originalsBefore = new LinkedHashMap<>(start.originals());
            stepOriginals.forEach(originalsBefore::putIfAbsent);
            var before = new Checkpoint(
                    Map.copyOf(documentsBefore), Map.copyOf(originalsBefore), start.selection(), start.assets());
            undo.push(new DocumentCommand(before, checkpoint(), Set.copyOf(affected)));
            recordedCommands = stepRecorded + 1;
        }
        if (label != null) {
            lastLabel = label;
        }
        stepOpened.clear();
        stepOriginals.clear();
        revision++;
        return lastLabel;
    }

    public void clearRedo() {
        if (redo.isEmpty()) {
            return;
        }
        redo.clear();
        committedCommands++;
        revision++;
    }

    public Set<String> getHiddenActors() {
        return Collections.unmodifiableSet(hidden);
    }

    public Set<String> getLockedActors() {
        return Collections.unmodifiableSet(locked);
    }

    public void hide(Collection<String> actors) {
        requireEditable();
        finishGesture();
        var added = new LinkedHashSet<String>();
        for (var path : actors) {
            if (hidden.contains(path)) {
                continue;
            }
            try {
                documentFor(path);
            } catch (IllegalArgumentException noMap) {
                continue;
            }
            added.add(path);
        }
        if (added.isEmpty()) {
            return;
        }
        preview.hide(Set.copyOf(added));
        hidden.addAll(added);
        selection.removeAll(added);
        revision++;
    }

    public void show(Collection<String> actors) {
        requireEditable();
        finishGesture();
        var shown = new LinkedHashSet<String>();
        for (var path : actors) {
            if (hidden.remove(path)) {
                shown.add(path);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        preview.show(Set.copyOf(shown));
        var present = new LinkedHashSet<String>();
        for (var path : shown) {
            try {
                if (documentFor(path).findActor(path).isPresent()) {
                    present.add(path);
                }
            } catch (IllegalArgumentException unreadable) {
            }
        }
        rebuild(present);
        var updates = new LinkedHashMap<String, float[]>();
        var restores = new LinkedHashSet<String>();
        for (var path : present) {
            if (preview.isMoving(path)) {
                continue;
            }
            var delta = previewDeltas.get(path);
            if (delta == null) {
                restores.add(path);
            } else {
                updates.put(path, delta);
            }
        }
        preview.applyChanges(Map.copyOf(updates), Set.copyOf(restores), previewDeltaView);
        revision++;
    }

    public void setLocked(Collection<String> actors, boolean value) {
        var changed = false;
        for (var path : actors) {
            changed |= value ? locked.add(path) : locked.remove(path);
        }
        if (changed) {
            revision++;
        }
    }

    public boolean isChanged(String path) {
        var dot = path.indexOf('.');
        var document = dot <= 0 ? null : documents.get(path.substring(0, dot));
        var actor = document == null ? null : document.findActor(path).orElse(null);
        if (actor == null) {
            return false;
        }
        if (actor.created()) {
            return true;
        }
        var original = originals.get(path);
        return original != null && !original.equals(State.of(actor));
    }

    public List<Change> getChanges() {
        if (changesRevision == revision) {
            return changes;
        }
        var result = new ArrayList<Change>();
        var replaced = new LinkedHashSet<String>();
        documents.forEach((map, document) -> {
            for (var actor : document.getActors()) {
                var path = actor.sourcePath();
                var kinds = new LinkedHashSet<ChangeKind>();
                var from = "";
                var baseline = originals.get(path);
                Vector3 source = null;
                if (actor.created()) {
                    from = actor.templateSourcePath();
                    var templateMap = from.isEmpty() ? "" : mapOfPath(from);
                    var template = documents.get(templateMap);
                    if (from.isEmpty()) {
                        kinds.add(ChangeKind.NEW);
                    } else if (template != null && template.findActor(from).isEmpty()) {
                        kinds.add(templateMap.equals(map) ? ChangeKind.MESH : ChangeKind.TRANSFERRED);
                        replaced.add(from);
                        var old = originals.get(from);
                        source = old == null ? null : old.transform().location();
                    } else {
                        kinds.add(ChangeKind.COPY);
                    }
                }
                if (baseline != null && !actor.created()) {
                    source = baseline.transform().location();
                }
                if (baseline != null) {
                    var before = baseline.transform();
                    var after = actor.transform();
                    if (!before.location().equals(after.location()) && !actor.created()) {
                        kinds.add(ChangeKind.MOVED);
                    }
                    if (!before.rotation().equals(after.rotation())) {
                        kinds.add(ChangeKind.ROTATED);
                    }
                    if (before.drawScale() != after.drawScale()
                            || !before.drawScale3d().equals(after.drawScale3d())) {
                        kinds.add(ChangeKind.SCALED);
                    }
                    if (actor.created() && !before.location().equals(after.location())) {
                        kinds.add(ChangeKind.MOVED);
                    }
                }
                var slots = new ArrayList<Integer>();
                if (baseline != null) {
                    var was = baseline.skins();
                    var now = actor.skinReferences();
                    for (var slot = 0; slot < Math.max(was.size(), now.size()); slot++) {
                        var a = slot < was.size() ? was.get(slot) : 0;
                        var b = slot < now.size() ? now.get(slot) : 0;
                        if (a != b) {
                            slots.add(slot);
                        }
                    }
                    if (!slots.isEmpty()) {
                        kinds.add(ChangeKind.MATERIAL);
                    }
                }
                if (kinds.isEmpty()) {
                    continue;
                }
                var location = actor.transform().location();
                var distance =
                        baseline == null ? 0 : distance(baseline.transform().location(), location);
                result.add(
                        new Change(path, map, kinds, distance, location, source, from, slots, actor.meshSourcePath()));
            }
        });
        documents.forEach((map, document) -> {
            for (var light : document.getLights()) {
                if (light.created()) {
                    result.add(new Change(
                            light.sourcePath(),
                            map,
                            EnumSet.of(ChangeKind.LIGHT, ChangeKind.COPY),
                            0,
                            light.location(),
                            null,
                            light.template(),
                            List.of()));
                } else if (light.isChanged()) {
                    var kinds = EnumSet.of(ChangeKind.LIGHT);
                    if (!light.color().equals(light.sourceColor())) {
                        kinds.add(ChangeKind.COLOR);
                    }
                    if (!light.location().equals(light.sourceLocation())) {
                        kinds.add(ChangeKind.MOVED);
                    }
                    if (Float.compare(light.radius(), light.sourceRadius()) != 0) {
                        kinds.add(ChangeKind.RADIUS);
                    }
                    if (!light.shape().rotation().equals(light.sourceShape().rotation())) {
                        kinds.add(ChangeKind.ROTATED);
                    }
                    if (light.shape().cone() != light.sourceShape().cone()) {
                        kinds.add(ChangeKind.CONE);
                    }
                    result.add(new Change(
                            light.sourcePath(),
                            map,
                            kinds,
                            distance(light.sourceLocation(), light.location()),
                            light.location(),
                            light.sourceLocation(),
                            "",
                            List.of()));
                }
            }
            for (var light : document.getDeletedLights()) {
                result.add(new Change(
                        light.sourcePath(),
                        map,
                        EnumSet.of(ChangeKind.LIGHT, ChangeKind.DELETED),
                        0,
                        light.sourceLocation(),
                        light.sourceLocation(),
                        "",
                        List.of()));
            }
        });
        documents.forEach((map, document) -> {
            var mapped = new HashSet<Integer>();
            document.getSurfaceMappingEdits().forEach(edit -> mapped.add(edit.surface()));
            for (var index : document.getEditedSurfaces()) {
                var surface = new SurfaceRef(map, index);
                Vector3 center;
                var material = "";
                try {
                    center = center(document.getSurfacePolygons(index));
                    if (!document.getSurfaceMaterialPath(index).equals(document.getSourceSurfaceMaterialPath(index))) {
                        material = document.getSurfaceMaterialPath(index);
                    }
                } catch (IOException unreadable) {
                    center = Vector3.ZERO;
                }
                var kinds = EnumSet.of(ChangeKind.SURFACE);
                if (mapped.contains(index)) {
                    kinds.add(ChangeKind.MAPPING);
                }
                if (document.isSurfaceHidden(index)) {
                    kinds.add(ChangeKind.HIDDEN);
                }
                if (document.isSurfaceSheet(index)) {
                    kinds.add(ChangeKind.SHEET);
                }
                if (!material.isEmpty()) {
                    kinds.add(ChangeKind.TEXTURE);
                }
                kinds.addAll(flagKinds(document, index));
                result.add(new Change(surface.path(), map, kinds, 0, center, center, material, List.of()));
            }
            for (var brush : document.getDeletedBrushes()) {
                var surfaces = document.getBrushSurfaces(brush);
                var polygons = new ArrayList<float[]>();
                try {
                    for (var surface : surfaces) {
                        polygons.addAll(document.getSurfacePolygons(surface));
                    }
                } catch (IOException unreadable) {
                    polygons.clear();
                }
                var center = polygons.isEmpty() ? Vector3.ZERO : center(polygons);
                var path = surfaces.isEmpty()
                        ? map + SurfaceRef.NAME + "0"
                        : new SurfaceRef(map, surfaces.getFirst()).path();
                result.add(new Change(
                        path,
                        map,
                        EnumSet.of(ChangeKind.SURFACE, ChangeKind.BRUSH),
                        0,
                        center,
                        center,
                        "",
                        List.of(),
                        document.getBrushName(brush)));
            }
        });
        documents.forEach((map, document) -> {
            for (var path : document.getDeletedSourceActors()) {
                if (replaced.contains(path)) {
                    continue;
                }
                var baseline = originals.get(path);
                var deleted = document.getDeletedActor(path);
                var location = baseline != null
                        ? baseline.transform().location()
                        : deleted == null ? Vector3.ZERO : deleted.transform().location();
                result.add(new Change(
                        path,
                        map,
                        Set.of(ChangeKind.DELETED),
                        0,
                        location,
                        location,
                        "",
                        List.of(),
                        deleted == null ? "" : deleted.meshSourcePath()));
            }
        });
        result.sort((a, b) ->
                a.map().equals(b.map()) ? a.path().compareTo(b.path()) : a.map().compareTo(b.map()));
        changes = List.copyOf(result);
        changesRevision = revision;
        return changes;
    }

    public @Nullable ActorTransform getSourceTransform(String path) {
        var baseline = originals.get(path);
        return baseline == null ? null : baseline.transform();
    }

    public void revert(Change change) {
        requireEditable();
        var path = change.path();
        var label = "Revert " + (change.kinds().contains(ChangeKind.BRUSH) ? change.title() : shortName(path));
        var kinds = change.kinds();
        if (kinds.contains(ChangeKind.LIGHT) && kinds.contains(ChangeKind.COPY)) {
            mutate(label, Set.of(), () -> documentFor(path).deleteLight(path));
        } else if (kinds.contains(ChangeKind.LIGHT) && kinds.contains(ChangeKind.DELETED)) {
            mutate(label, Set.of(), () -> documentFor(path).undeleteLight(path));
        } else if (kinds.contains(ChangeKind.LIGHT)) {
            resetLight(path, label);
        } else if (kinds.contains(ChangeKind.BRUSH)) {
            var surface = Objects.requireNonNull(SurfaceRef.ofPath(path));
            var brush = documentFor(path).getSurfaceBrush(surface.surface());
            restoreBrushes(List.of(new BrushRef(surface.map(), brush)), label);
        } else if (kinds.contains(ChangeKind.SURFACE)) {
            resetSurfaces(List.of(Objects.requireNonNull(SurfaceRef.ofPath(path))), label);
        } else if (kinds.contains(ChangeKind.DELETED)) {
            var document = documentFor(path);
            mutate(label, new LinkedHashSet<>(Set.of(path)), () -> {
                document.undeleteActor(path);
                var baseline = originals.get(path);
                if (baseline != null) {
                    writeState(path, baseline);
                }
            });
        } else if (kinds.contains(ChangeKind.TRANSFERRED) || kinds.contains(ChangeKind.MESH)) {
            var old = change.from();
            var oldDocument = documentFor(old);
            var affected = new LinkedHashSet<>(List.of(path, old));
            mutate(label, affected, () -> {
                documentFor(path).deleteActor(path);
                oldDocument.undeleteActor(old);
                var baseline = originals.get(old);
                if (baseline != null) {
                    writeState(old, baseline);
                }
                selection.remove(path);
            });
        } else if (kinds.contains(ChangeKind.NEW) || kinds.contains(ChangeKind.COPY)) {
            locked.remove(path);
            deleteActors(List.of(path), label);
        } else {
            var baseline = originals.get(path);
            if (baseline == null) {
                throw new IllegalArgumentException("The client's state of " + shortName(path) + " is not known.");
            }
            locked.remove(path);
            execute(Map.of(path, baseline), label);
        }
    }

    public void moveToTiles(Map<String, String> targets, String label) {
        requireEditable();
        var chosen = new LinkedHashMap<String, String>();
        targets.forEach((path, tile) -> {
            if (!locked.contains(path)) {
                chosen.put(path, tile);
            }
        });
        if (chosen.isEmpty()) {
            return;
        }
        for (var tile : new LinkedHashSet<>(chosen.values())) {
            documentFor(tile + ".target");
        }
        transfer(chosen, label);
    }

    public void undo() {
        requireEditable();
        finishGesture();
        if (!undo.isEmpty()) {
            var command = undo.peek();
            var lightsBefore = lightRecords();
            restoreCommand(command, false);
            undo.pop();
            redo.push(command);
            committedCommands++;
            revision++;
            scheduleLightingPreview(commandActors(command));
            scheduleLightPreview(lightsBefore);
        }
    }

    public void redo() {
        requireEditable();
        finishGesture();
        if (!redo.isEmpty()) {
            var command = redo.peek();
            var lightsBefore = lightRecords();
            restoreCommand(command, true);
            redo.pop();
            undo.push(command);
            committedCommands++;
            revision++;
            scheduleLightingPreview(commandActors(command));
            scheduleLightPreview(lightsBefore);
        }
    }

    public boolean hasExportableChanges() {
        if (exportabilityRevision != revision) {
            exportable = !assets.isEmpty()
                    || documents.values().stream()
                            .anyMatch(document -> document.isDirty() || document.hasChangesFromSource());
            exportabilityRevision = revision;
        }
        return exportable;
    }

    public Plan prepareExport() throws IOException {
        return prepareExport(List.of());
    }

    Plan prepareExport(List<NativePackageChanges> nativeEdits) throws IOException {
        var captured = captureExport();
        var result = prepareCapturedExport(captured, nativeEdits, List.of(), List.of());
        adoptPreparedExport(captured, result);
        return result;
    }

    ExportSnapshot captureExport() {
        requireEditable();
        finishGesture();
        var captured = new LinkedHashMap<Path, RecoveryBundle>();
        var hashes = new LinkedHashMap<Path, String>();
        var changes = new LinkedHashSet<Path>();
        for (var document : documents.values()) {
            if (document.hasChangesFromSource()) {
                changes.add(document.getSource());
            }
            if (document.isDirty() || document.hasChangesFromSource()) {
                captured.put(document.getSource(), document.captureRecovery(List.of()));
                hashes.put(document.getSource(), document.getOriginalSha256());
            }
        }
        var imported = new ArrayList<AssetInput>();
        var required = new ArrayList<AssetInput>();
        for (var asset : assets.values()) {
            (isClientPackage(asset.path()) ? required : imported)
                    .add(new AssetInput(asset.path(), asset.fingerprint()));
        }
        return new ExportSnapshot(
                revision,
                Map.copyOf(captured),
                Map.copyOf(hashes),
                List.copyOf(imported),
                List.copyOf(required),
                Set.copyOf(changes),
                rebuildAllLighting,
                rebuildAllBspLightmaps,
                rebuildAllTerrainLighting,
                keepNeighborBeast);
    }

    Plan prepareCapturedExport(
            ExportSnapshot captured,
            List<NativePackageChanges> nativeEdits,
            List<MapInput> preparedMaps,
            List<DerivedAssetInput> derived)
            throws IOException {
        return prepareCapturedExport(
                captured, nativeEdits, preparedMaps, derived, List.of(), () -> false, (map, done, total) -> {});
    }

    Plan prepareCapturedExport(
            ExportSnapshot captured,
            List<NativePackageChanges> nativeEdits,
            List<MapInput> preparedMaps,
            List<DerivedAssetInput> derived,
            BooleanSupplier cancelled,
            LightingProgress progress)
            throws IOException {
        return prepareCapturedExport(captured, nativeEdits, preparedMaps, derived, List.of(), cancelled, progress);
    }

    Plan prepareCapturedExport(
            ExportSnapshot captured,
            List<NativePackageChanges> nativeEdits,
            List<MapInput> preparedMaps,
            List<DerivedAssetInput> derived,
            List<ReplacedAssetInput> replaced,
            BooleanSupplier cancelled,
            LightingProgress progress)
            throws IOException {
        return prepareCapturedExport(
                captured,
                nativeEdits,
                preparedMaps,
                derived,
                replaced,
                Map.of(),
                cancelled,
                progress,
                (map, done, total) -> {});
    }

    Plan prepareCapturedExport(
            ExportSnapshot captured,
            List<NativePackageChanges> nativeEdits,
            List<MapInput> preparedMaps,
            List<DerivedAssetInput> derived,
            List<ReplacedAssetInput> replaced,
            Map<Path, List<TerrainChange>> terrainChanges,
            BooleanSupplier cancelled,
            LightingProgress progress,
            BspProgress bspProgress)
            throws IOException {
        return prepareCapturedExport(
                captured,
                nativeEdits,
                preparedMaps,
                derived,
                replaced,
                terrainChanges,
                Map.of(),
                cancelled,
                progress,
                bspProgress,
                (map, done, total) -> {});
    }

    Plan prepareCapturedExport(
            ExportSnapshot captured,
            List<NativePackageChanges> nativeEdits,
            List<MapInput> preparedMaps,
            List<DerivedAssetInput> derived,
            List<ReplacedAssetInput> replaced,
            Map<Path, List<TerrainChange>> terrainChanges,
            Map<Path, Set<String>> terrainOwners,
            BooleanSupplier cancelled,
            LightingProgress progress,
            BspProgress bspProgress,
            TerrainProgress terrainProgress)
            throws IOException {
        for (var entry : captured.sourceHashes().entrySet()) {
            if (!entry.getValue().equals(fileFingerprint(entry.getKey()))) {
                throw new IOException("Source package changed after editing began: "
                        + entry.getKey().getFileName());
            }
        }
        var nativeMaps = new LinkedHashMap<Path, NativePackageChanges>();
        for (var changes : nativeEdits) {
            var prior = nativeMaps.putIfAbsent(changes.getSourcePath(), changes);
            if (prior != null) {
                nativeMaps.put(changes.getSourcePath(), prior.combine(changes));
            }
        }
        var complete = new LinkedHashMap<Path, MapInput>();
        for (var input : preparedMaps) {
            if (captured.sourceChanges().contains(input.source())) {
                throw new IOException(
                        "This Terrain profile requires the original actor scene. Reset all actor and material changes"
                                + " in "
                                + input.source().getFileName() + ", including changes already exported.");
            }
            if (complete.putIfAbsent(input.source(), input) != null) {
                throw new IOException("Duplicate prepared native map");
            }
        }
        var maps = new ArrayList<MapInput>(complete.values());
        var lightingLines = new ArrayList<String>();
        var lightingTips = new ArrayList<String>();
        for (var entry : captured.documents().entrySet()) {
            if (complete.containsKey(entry.getKey())) {
                continue;
            }
            var document = PackageEditDocument.restoreRecovery(
                            entry.getKey(), entry.getValue().encode())
                    .document();
            var terrain = terrainChanges.getOrDefault(entry.getKey(), List.of());
            var lighting = rebuildLighting(document, captured, terrain, cancelled, progress);
            var changes = nativeMaps.remove(entry.getKey());
            if (lighting != null) {
                addLighting(lightingLines, lightingTips, lighting.report());
                changes = combine(changes, lighting.bsp());
            }
            var bsp = rebuildBspLightmaps(document, captured, terrain, cancelled, bspProgress);
            if (bsp != null) {
                addLine(lightingLines, lightingTips, bspLine(bsp.report()));
                changes = combine(changes, bsp.changes());
            }
            var shadows = rebuildTerrainShadows(
                    document,
                    captured,
                    terrainOwners.getOrDefault(normalized(entry.getKey()), Set.of()),
                    cancelled,
                    terrainProgress);
            if (shadows != null) {
                addLine(lightingLines, lightingTips, terrainLine(shadows.report()));
                changes = combine(changes, shadows.changes());
            }
            addEdgeNote(lightingLines, lightingTips, document, captured, terrain, cancelled);
            maps.add(prepareMap(document, changes, lighting == null ? null : lighting.changes()));
        }
        for (var changes : nativeMaps.values()) {
            if (complete.containsKey(changes.getSourcePath())) {
                throw new IOException("Overlapping native map producers");
            }
            var document = PackageEditDocument.open(changes.getSourcePath());
            var terrain = terrainChanges.getOrDefault(normalized(changes.getSourcePath()), List.of());
            var lighting = rebuildLighting(document, captured, terrain, cancelled, progress);
            var composed = changes;
            if (lighting != null) {
                addLighting(lightingLines, lightingTips, lighting.report());
                composed = combine(composed, lighting.bsp());
            }
            var bsp = rebuildBspLightmaps(document, captured, terrain, cancelled, bspProgress);
            if (bsp != null) {
                addLine(lightingLines, lightingTips, bspLine(bsp.report()));
                composed = combine(changes, bsp.changes());
            }
            var shadows = rebuildTerrainShadows(
                    document,
                    captured,
                    terrainOwners.getOrDefault(normalized(changes.getSourcePath()), Set.of()),
                    cancelled,
                    terrainProgress);
            if (shadows != null) {
                addLine(lightingLines, lightingTips, terrainLine(shadows.report()));
                composed = combine(composed, shadows.changes());
            }
            addEdgeNote(lightingLines, lightingTips, document, captured, terrain, cancelled);
            maps.add(
                    lighting == null
                            ? prepareMap(document, composed)
                            : prepareMap(document, composed, lighting.changes()));
        }
        return exports.prepare(maps, captured.assets(), derived, replaced, captured.clientPackages())
                .withLighting(lightingLines, lightingTips);
    }

    TerrainActorShadows getTerrainActorShadows(ExportSnapshot captured, Path map) throws IOException {
        var all = captured.rebuildAllTerrainLighting();
        for (var entry : captured.documents().entrySet()) {
            if (!normalized(entry.getKey()).equals(normalized(map))) {
                continue;
            }
            var document = PackageEditDocument.restoreRecovery(
                            entry.getKey(), entry.getValue().encode())
                    .document();
            if (!ActorLightingRebuild.isNeeded(document)) {
                break;
            }
            var archives = new LightingArchives(clientRoot, LightingArchives.byPackageName(suppliedPackages(captured)));
            try {
                return TerrainActorShadows.of(document, archives).withAll(all);
            } finally {
                archives.clearArchives();
            }
        }
        return TerrainActorShadows.NONE.withAll(all);
    }

    void adoptPreparedExport(ExportSnapshot captured, Plan plan) throws IOException {
        if (revision != captured.revision() || gesture != null) {
            throw new IOException("The editing session changed. Validate the export again.");
        }
        preparedExport = plan;
        preparedExportRevision = captured.revision();
    }

    List<Path> publishCapturedExport(Plan plan, Path output, BooleanSupplier cancelled, SourceVerifier verifier)
            throws IOException {
        return exports.publish(plan, output, cancelled, verifier);
    }

    boolean isPreparedExportCurrent(Plan plan) {
        return plan != null && plan == preparedExport && preparedExportRevision == revision && gesture == null;
    }

    List<Path> publishPreparedExport(Plan plan, Path outputDirectory, BooleanSupplier cancelled) throws IOException {
        if (!isPreparedExportCurrent(plan)) {
            throw new IOException("The editing session changed. Validate the export again.");
        }
        return exports.publish(plan, outputDirectory, () -> cancelled.getAsBoolean() || !isPreparedExportCurrent(plan));
    }

    void markPreparedExportSaved(Plan plan) {
        if (!isPreparedExportCurrent(plan)) {
            throw new IllegalStateException("The editing session changed before its Save checkpoint");
        }
        documents.values().forEach(PackageEditDocument::markSaved);
        savedAssets = Map.copyOf(assets);
        revision++;
        preparedExport = null;
    }

    public List<Path> savePreparedExport(Plan plan, Path outputDirectory) throws IOException {
        finishGesture();
        var saved = publishPreparedExport(plan, outputDirectory, () -> false);
        if (!saved.isEmpty()) {
            markPreparedExportSaved(plan);
        } else {
            preparedExport = null;
        }
        return saved;
    }

    public List<Path> saveAs(Path outputDirectory) throws IOException {
        return savePreparedExport(prepareExport(), outputDirectory);
    }

    public void addDependency(Path file) {
        requireEditable();
        mutate("Add package " + file.getFileName(), new LinkedHashSet<>(), () -> {
            var inventory = PackageInventory.inspect(file, Files.readAllBytes(file));
            registerAsset(inventory.name() + ".Dependency", file);
        });
    }

    Status getHistoryStatus() {
        return new Status(historyGeneration, recordedCommands, undo.size(), redo.size(), lastLabel);
    }

    private void scheduleLightingPreview(Set<String> paths) {
        if (lightingPreview == null || paths.isEmpty()) {
            return;
        }
        var lit = paths;
        if (paths.stream().anyMatch(EditorUi::isPawn)) {
            lit = new LinkedHashSet<>(paths);
            lit.removeIf(EditorUi::isPawn);
        }
        if (lit.stream().anyMatch(path -> isEmitter(path) || isCarriedLight(path))) {
            lit = new LinkedHashSet<>(lit);
            lit.removeIf(path -> isEmitter(path) || isCarriedLight(path));
        }
        if (!lit.isEmpty()) {
            lightingPreview.schedule(lit);
        }
    }

    private Map<String, LightRecord> lightRecords() {
        if (lightingPreview == null) {
            return Map.of();
        }
        var result = new HashMap<String, LightRecord>();
        documents
                .values()
                .forEach(document -> document.getLights().forEach(light -> result.put(light.sourcePath(), light)));
        return result;
    }

    private void scheduleLightPreview(Map<String, LightRecord> before) {
        var targets = lightPreviewTargets(before);
        if (targets != null) {
            lightingPreview.scheduleMaps(targets.maps());
            scheduleLightingPreview(targets.actors());
        }
        scheduleDragShown();
    }

    private void followLightPreview(Map<String, LightRecord> before) {
        if (lightingPreview == null || lightingPreview.isWaiting()) {
            return;
        }
        var now = lightRecords();
        if (now.equals(followedLights)) {
            return;
        }
        followedLights = now;
        var targets = lightPreviewTargets(before);
        if (targets != null) {
            dragShownActors.addAll(targets.actors());
            dragShownMaps.addAll(targets.maps());
            lightingPreview.follow(targets.actors(), targets.maps());
        }
    }

    private void scheduleDragShown() {
        if (lightingPreview != null) {
            lightingPreview.scheduleMaps(Set.copyOf(dragShownMaps));
            scheduleLightingPreview(Set.copyOf(dragShownActors));
        }
        dragShownActors.clear();
        dragShownMaps.clear();
        followedLights = Map.of();
    }

    private record PreviewTargets(Set<String> maps, Set<String> actors) {}

    private @Nullable PreviewTargets lightPreviewTargets(Map<String, LightRecord> before) {
        if (lightingPreview == null) {
            return null;
        }
        var after = lightRecords();
        var reached = new ArrayList<LightRecord>();
        var paths = new HashSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (var path : paths) {
            var old = before.get(path);
            var now = after.get(path);
            if (!Objects.equals(old, now)) {
                if (old != null) {
                    reached.add(old);
                }
                if (now != null) {
                    reached.add(now);
                }
            }
        }
        if (reached.isEmpty()) {
            return null;
        }
        var maps = new LinkedHashSet<String>();
        for (var light : reached) {
            maps.add(mapOfPath(light.sourcePath()));
        }
        var actors = new LinkedHashSet<String>();
        documents.forEach((map, document) -> {
            for (var actor : document.getActors()) {
                var location = actor.transform().location();
                for (var light : reached) {
                    if (mapOfPath(light.sourcePath()).equals(map)
                            && distance(location, light.location()) <= light.reach() + LIGHT_PREVIEW_MARGIN) {
                        actors.add(actor.sourcePath());
                        break;
                    }
                }
            }
        });
        return new PreviewTargets(maps, actors);
    }

    private Job captureLightingJob(Set<String> actors, Set<String> lightMaps) {
        var byMap = new LinkedHashMap<String, Set<String>>();
        for (var path : actors) {
            var dot = path.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            var map = path.substring(0, dot);
            var document = documents.get(map);
            if (document != null && document.findActor(path).isPresent()) {
                byMap.computeIfAbsent(map, ignored -> new LinkedHashSet<>()).add(path);
            }
        }
        var lit = new LinkedHashSet<String>();
        for (var map : lightMaps) {
            if (documents.containsKey(map)) {
                lit.add(map);
            }
        }
        var sources = new LinkedHashMap<String, Path>();
        var bundles = new LinkedHashMap<String, RecoveryBundle>();
        var captured = new LinkedHashSet<>(byMap.keySet());
        captured.addAll(lit);
        for (var map : captured) {
            var document = documents.get(map);
            sources.put(map, document.getSource());
            bundles.put(map, document.captureRecovery(List.of()));
        }
        var packages = new LinkedHashMap<String, Path>();
        assets.forEach((name, asset) -> packages.put(name, asset.path()));
        var frozen = new LinkedHashMap<String, Set<String>>();
        var baselines = new LinkedHashMap<String, ActorTransform>();
        byMap.forEach((map, paths) -> {
            frozen.put(map, Set.copyOf(paths));
            for (var path : paths) {
                var original = originals.get(path);
                if (original != null) {
                    baselines.put(path, original.transform());
                }
            }
        });
        return new Job(
                revision,
                Map.copyOf(sources),
                Map.copyOf(bundles),
                Map.copyOf(frozen),
                Set.copyOf(lit),
                Map.copyOf(packages),
                getLightingSettings(),
                clientRoot,
                Map.copyOf(baselines),
                preview::load);
    }

    private void requireEditable() {
        if (exportLocked) {
            throw new IllegalStateException("Wait for export to finish or cancel it before editing.");
        }
        endMaterialPreview();
    }

    private boolean keepsMap(String path) {
        var dot = path.indexOf('.');
        var document = dot <= 0 ? null : documents.get(path.substring(0, dot));
        return document != null ? document.keepsMap(path) : EditorUi.isPawn(path);
    }

    private boolean exists(String path) {
        var dot = path.indexOf('.');
        var document = dot <= 0 ? null : documents.get(path.substring(0, dot));
        return document != null && document.findActor(path).isPresent();
    }

    private boolean isSelectable(String path) {
        return !hidden.contains(path) && !locked.contains(path) && exists(path);
    }

    private void openLightMaps(Collection<String> paths) {
        for (var path : paths) {
            documentFor(path);
        }
    }

    private String changeRestriction(String path) {
        if (locked.contains(path)) {
            return "This object is locked in Cruma.";
        }
        if (hidden.contains(path)) {
            return "This object is hidden in Cruma.";
        }
        return preview.accepts(path) ? "" : "This placement's tile has not loaded for Edit yet.";
    }

    private void transfer(Map<String, String> targets, String label) {
        var affected = new LinkedHashSet<>(targets.keySet());
        var moved = new LinkedHashMap<String, String>();
        mutate(label, affected, () -> {
            moved.clear();
            for (var entry : targets.entrySet()) {
                var path = entry.getKey();
                var targetMap = entry.getValue();
                var source = documentFor(path);
                var target = documentFor(targetMap + ".target");
                if (source == target) {
                    moved.put(path, path);
                    continue;
                }
                var location = getActor(path).transform().location();
                var from = new Vector3(
                        (float) Math.floor(location.x() / WorldDimensions.TILE_SIZE) * WorldDimensions.TILE_SIZE,
                        (float) Math.floor(location.y() / WorldDimensions.TILE_SIZE) * WorldDimensions.TILE_SIZE,
                        0);
                var to = mapOrigin(targetMap);
                var placement =
                        translated(getActor(path).transform(), new Vector3(to.x() - from.x(), to.y() - from.y(), 0));
                var movedActor = source.transferActorTo(path, target, placement);
                originals.put(movedActor.sourcePath(), State.of(movedActor));
                affected.add(movedActor.sourcePath());
                moved.put(path, movedActor.sourcePath());
            }
            var next = new ArrayList<String>();
            for (var path : selection) {
                next.add(moved.getOrDefault(path, path));
            }
            selection.clear();
            selection.addAll(next);
        });
        groups.moved(moved);
    }

    private @Nullable String slotMaterial(String path, int slot) {
        var skins = getActor(path).skinReferences();
        var reference = slot < skins.size() ? skins.get(slot) : 0;
        return reference == 0 ? null : getMaterialName(path, reference).toLowerCase(Locale.ROOT);
    }

    private String meshFor(PackageEditDocument doc, String mapName, String meshPath, @Nullable Path packageFile)
            throws IOException {
        var mesh = referencedMesh(doc, meshPath);
        if (mesh != null) {
            return mesh;
        }
        var file = packageFile != null && Files.isRegularFile(packageFile) ? packageFile : clientMeshPackage(meshPath);
        if (file == null) {
            throw new IllegalArgumentException(
                    shortName(meshPath) + " belongs to another map and cannot be placed in " + mapName + ".");
        }
        mesh = doc.importMesh(file, meshPath);
        registerAsset(mesh, file);
        return mesh;
    }

    private List<SurfaceRef> surfacesTaking(List<SurfaceRef> surfaces, @Nullable MaterialChoice material) {
        var chosen = new ArrayList<SurfaceRef>();
        try {
            for (var surface : surfaces) {
                var document = documentFor(surface.path());
                if (!document.getSurfaceMaterialRestriction(surface.surface()).isEmpty()) {
                    continue;
                }
                var now = document.getSurfaceMaterialPath(surface.surface());
                var wanted =
                        material == null ? document.getSourceSurfaceMaterialPath(surface.surface()) : material.path();
                if (!now.equalsIgnoreCase(wanted)) {
                    chosen.add(surface);
                }
            }
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(unreadable.getMessage(), unreadable);
        }
        return chosen;
    }

    private int[] getMappingSize(MaterialChoice material) {
        var packages = new LinkedHashMap<>(assetPackages());
        var dot = material.path().indexOf('.');
        if (material.packageFile() != null && dot > 0 && Files.isRegularFile(material.packageFile())) {
            packages.putIfAbsent(material.path().substring(0, dot), material.packageFile());
        }
        var size = preview.getMappingSize(material.path(), packages);
        if (size == null) {
            throw new IllegalArgumentException("The size of " + shortName(material.path()) + " cannot be read.");
        }
        if (size[0] <= 0 || size[1] <= 0) {
            throw new IllegalArgumentException(
                    shortName(material.path()) + " has no size, so the game could not lay it on a surface.");
        }
        return size;
    }

    private void giveSurfaceMaterial(
            List<SurfaceRef> surfaces, @Nullable MaterialChoice material, int @Nullable [] size) throws IOException {
        for (var surface : surfaces) {
            var document = documentFor(surface.path());
            if (material == null) {
                document.updateSurfaceMaterial(surface.surface(), null, 0, 0);
                continue;
            }
            var path = materialFor(document, material);
            var sizes = Objects.requireNonNull(size);
            document.updateSurfaceMaterial(surface.surface(), path, sizes[0], sizes[1]);
        }
    }

    private @Nullable Map<Integer, ShownSurface> getSurfaceState(String map) {
        var current = new HashMap<Integer, ShownSurface>();
        var document = documents.get(map);
        if (document == null) {
            return current;
        }
        var mappings = new HashMap<Integer, BspSurfaceMapping>();
        document.getSurfaceMappingEdits().forEach(edit -> mappings.put(edit.surface(), edit.current()));
        try {
            for (var surface : document.getEditedSurfaces()) {
                var path = document.getSurfaceMaterialPath(surface);
                var material = path.equals(document.getSourceSurfaceMaterialPath(surface)) ? "" : path;
                current.put(
                        surface,
                        new ShownSurface(
                                mappings.get(surface),
                                material,
                                document.isSurfaceHidden(surface),
                                changedFlags(document, surface)));
            }
            for (var surface : document.getDeletedBrushSurfaces()) {
                current.putIfAbsent(surface, new ShownSurface(null, "", true, -1));
            }
        } catch (IOException unreadable) {
            return null;
        }
        return current;
    }

    private void showSurfacesOf(String map) {
        if (surfaceJobs.containsKey(map) || !preview.isSurfaceMapDrawn(map)) {
            return;
        }
        var current = getSurfaceState(map);
        if (current == null) {
            return;
        }
        var shown = shownSurfaces.getOrDefault(map, Map.of());
        var changed = new TreeSet<Integer>();
        var overlayChanged = false;
        for (var entry : current.entrySet()) {
            var before = shown.get(entry.getKey());
            if (!entry.getValue().equals(before)) {
                changed.add(entry.getKey());
                overlayChanged |= entry.getValue().isOverlay() || before != null && before.isOverlay();
            }
        }
        for (var entry : shown.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                changed.add(entry.getKey());
                overlayChanged |= entry.getValue().isOverlay();
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        var collapsed = new TreeSet<Integer>();
        var overlay = new TreeSet<Integer>();
        current.forEach((surface, state) -> {
            if (state.isCollapsed()) {
                collapsed.add(surface);
            }
            if (state.isOverlay()) {
                overlay.add(surface);
            }
        });
        SurfacePreview surfaces;
        try {
            var edited = documentFor(map + SurfaceRef.NAME);
            surfaces = new SurfacePreview(
                    edited.previewSurfaceBody(false),
                    overlayChanged && !overlay.isEmpty() ? edited.previewSurfaceBody(true) : null,
                    assetPackages(),
                    changed,
                    collapsed,
                    overlayChanged ? overlay : null);
        } catch (IOException | RuntimeException failure) {
            return;
        }
        var loaded = new CompletableFuture<LoadedSurfaces>();
        surfaceJobs.put(
                map, new SurfaceJob(Map.copyOf(current), surfaceGenerations.getOrDefault(map, 0L), surfaces, loaded));
        try {
            preview.getSurfaceExecutor().execute(() -> {
                try {
                    loaded.complete(preview.loadSurfaces(map, surfaces));
                } catch (RuntimeException | Error failure) {
                    loaded.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException refused) {
            loaded.completeExceptionally(refused);
        }
        finishSurfaceJobs();
    }

    private void finishSurfaceJobs() {
        if (surfaceJobs.isEmpty()) {
            return;
        }
        for (var entry : new ArrayList<>(surfaceJobs.entrySet())) {
            var job = entry.getValue();
            if (!job.loaded().isDone()) {
                continue;
            }
            var map = entry.getKey();
            surfaceJobs.remove(map);
            var stale = job.generation() != surfaceGenerations.getOrDefault(map, 0L)
                    || !job.state().equals(getSurfaceState(map));
            LoadedSurfaces loaded;
            try {
                loaded = job.loaded().join();
            } catch (RuntimeException failed) {
                if (stale) {
                    surfacePreviewRevision = -1;
                }
                continue;
            }
            if (stale) {
                surfacePreviewRevision = -1;
                continue;
            }
            SurfaceShow shown;
            try {
                shown = preview.showSurfaces(map, job.surfaces(), loaded);
            } catch (RuntimeException failure) {
                shown = SurfaceShow.NOTHING;
            }
            var state = switch (shown) {
                case NOTHING -> null;
                case ALL -> {
                    overlayFailures.remove(map);
                    yield job.state();
                }
                case WITHOUT_OVERLAY -> {
                    if (!job.state().equals(overlayFailures.put(map, job.state()))) {
                        surfacePreviewRevision = -1;
                    }
                    yield withoutOverlay(job.state());
                }
            };
            if (state == null) {
                continue;
            }
            if (state.isEmpty()) {
                shownSurfaces.remove(map);
            } else {
                shownSurfaces.put(map, state);
            }
        }
    }

    private String materialFor(PackageEditDocument doc, MaterialChoice material) throws IOException {
        for (var known : doc.getMaterials()) {
            if (known.sourcePath().equalsIgnoreCase(material.path())) {
                return known.sourcePath();
            }
        }
        var file = material.packageFile() != null && Files.isRegularFile(material.packageFile())
                ? material.packageFile()
                : clientPackage(material.path(), ".utx", ".usx");
        if (file == null) {
            throw new IllegalArgumentException(
                    "The package of " + shortName(material.path()) + " was not found in the client.");
        }
        var reference = doc.importMaterial(file, material.path());
        registerAsset(reference.sourcePath(), file);
        return reference.sourcePath();
    }

    private Result rebuildLighting(
            PackageEditDocument document,
            ExportSnapshot captured,
            List<TerrainChange> terrain,
            BooleanSupplier cancelled,
            LightingProgress progress)
            throws IOException {
        if (!captured.rebuildAllLighting()
                && !ActorLightingRebuild.isNeeded(document)
                && terrain.stream().allMatch(TerrainChange::isEmpty)) {
            return null;
        }
        var archives = new LightingArchives(clientRoot, LightingArchives.byPackageName(suppliedPackages(captured)));
        var name = mapName(document);
        try {
            return ActorLightingRebuild.rebuild(
                    document,
                    archives,
                    getLightingSettings(),
                    Options.export(captured.rebuildAllLighting(), captured.keepNeighborBeast()),
                    terrain,
                    (done, total) -> progress.update(name, done, total),
                    cancelled);
        } finally {
            archives.clearArchives();
        }
    }

    private BspLightmapRebuild.Result rebuildBspLightmaps(
            PackageEditDocument document,
            ExportSnapshot captured,
            List<TerrainChange> terrain,
            BooleanSupplier cancelled,
            BspProgress progress)
            throws IOException {
        var options = BspLightmapRebuild.Options.export(captured.rebuildAllBspLightmaps());
        if (!BspLightmapRebuild.isNeeded(document, terrain, options)) {
            return null;
        }
        var archives = new LightingArchives(clientRoot, LightingArchives.byPackageName(suppliedPackages(captured)));
        var name = mapName(document);
        try {
            return BspLightmapRebuild.rebuild(
                    document,
                    archives,
                    getLightingSettings(),
                    terrain,
                    options,
                    (done, total) -> progress.update(name, done, total),
                    cancelled);
        } finally {
            archives.clearArchives();
        }
    }

    private TerrainActorShadows.Result rebuildTerrainShadows(
            PackageEditDocument document,
            ExportSnapshot captured,
            Set<String> skipOwners,
            BooleanSupplier cancelled,
            TerrainProgress progress)
            throws IOException {
        if (!TerrainActorShadows.isNeeded(document, captured.rebuildAllTerrainLighting())) {
            return null;
        }
        var archives = new LightingArchives(clientRoot, LightingArchives.byPackageName(suppliedPackages(captured)));
        var name = mapName(document);
        try {
            return TerrainActorShadows.rebuild(
                    document,
                    clientRoot,
                    archives,
                    skipOwners,
                    captured.rebuildAllTerrainLighting(),
                    (done, total) -> progress.update(name, done, total),
                    cancelled);
        } finally {
            archives.clearArchives();
        }
    }

    private void addEdgeNote(
            List<String> lines,
            List<String> tips,
            PackageEditDocument document,
            ExportSnapshot captured,
            List<TerrainChange> terrain,
            BooleanSupplier cancelled)
            throws IOException {
        if (cancelled.getAsBoolean()
                || !ActorLightingRebuild.isNeeded(document) && terrain.stream().allMatch(TerrainChange::isEmpty)) {
            return;
        }
        var archives = new LightingArchives(clientRoot, LightingArchives.byPackageName(suppliedPackages(captured)));
        List<String> neighbors;
        try {
            neighbors = MapEdgeLighting.findNeighbors(
                    mapName(document), MapEdgeLighting.changedBoxes(document, archives, terrain));
        } finally {
            archives.clearArchives();
        }
        var installed = new ArrayList<String>();
        for (var neighbor : neighbors) {
            if (mapFile(neighbor) != null) {
                installed.add(neighbor);
            }
        }
        var line = edgeLine(mapName(document), installed);
        if (line != null) {
            lines.add(line);
            tips.add(EDGE_TIP);
        }
    }

    private @Nullable Path mapFile(String name) throws IOException {
        Path mapDirectory = null;
        try (var entries = Files.list(clientRoot)) {
            mapDirectory = entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("maps"))
                    .findFirst()
                    .orElse(null);
        }
        if (mapDirectory == null) {
            return null;
        }
        try (var entries = Files.list(mapDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name + ".unr"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private @Nullable Path clientMeshPackage(String meshPath) throws IOException {
        return clientPackage(meshPath, ".usx");
    }

    private @Nullable Path clientPackage(String objectPath, String... extensions) throws IOException {
        var dot = objectPath.indexOf('.');
        if (dot <= 0) {
            return null;
        }
        var name = objectPath.substring(0, dot);
        List<Path> found;
        try (var files = Files.walk(clientRoot, 2)) {
            found = files.filter(Files::isRegularFile)
                    .filter(path -> !ClientFiles.isAppleDouble(path))
                    .filter(path -> {
                        var file = path.getFileName().toString();
                        for (var extension : extensions) {
                            if (file.equalsIgnoreCase(name + extension)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .toList();
        }
        for (var extension : extensions) {
            for (var path : found) {
                if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension)) {
                    return path;
                }
            }
        }
        return null;
    }

    private Path sourceOfMap(String name) throws IOException {
        Path mapDirectory;
        try (var entries = Files.list(clientRoot)) {
            mapDirectory = entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("maps"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Client MAPS folder was not found."));
        }
        try (var entries = Files.list(mapDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name + ".unr"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Map file was not found: " + name));
        }
    }

    private PackageEditDocument documentFor(String actorPath) {
        var dot = actorPath.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("This object has no editable map source.");
        }
        var name = actorPath.substring(0, dot);
        var open = documents.get(name);
        if (open != null) {
            return open;
        }
        if (!name.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid source map name.");
        }
        return documents.computeIfAbsent(name, _ -> {
            try {
                var read = readMaps.remove(name);
                var document = read != null ? read : PackageEditDocument.open(sourceOfMap(name));
                for (var actor : document.getActors()) {
                    originals.put(actor.sourcePath(), State.of(actor));
                    if (stepStart != null) {
                        stepOriginals.put(actor.sourcePath(), State.of(actor));
                    }
                }
                if (stepStart != null) {
                    stepOpened.put(name, document.snapshot());
                }
                return document;
            } catch (IOException failure) {
                throw new IllegalArgumentException("Cannot open map: " + failure.getMessage(), failure);
            }
        });
    }

    private List<String> movable() {
        var result = new ArrayList<String>(selection.size());
        for (var path : selection) {
            if (!locked.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private Map<String, State> snapshot() {
        var result = new LinkedHashMap<String, State>();
        for (var path : movable()) {
            result.put(path, State.of(getActor(path)));
        }
        return result;
    }

    private Map<String, State> transformStates(Map<String, State> before, float[] delta) {
        var after = new LinkedHashMap<String, State>();
        before.forEach((path, state) -> {
            var operation = delta;
            if (pivotMode == PivotMode.INDIVIDUAL && before.size() > 1) {
                operation = delta.clone();
                var p = state.transform().location();
                operation[12] = p.x() - operation[0] * p.x() - operation[4] * p.y() - operation[8] * p.z();
                operation[13] = p.y() - operation[1] * p.x() - operation[5] * p.y() - operation[9] * p.z();
                operation[14] = p.z() - operation[2] * p.x() - operation[6] * p.y() - operation[10] * p.z();
            }
            var t = state.transform();
            var moved = EditorTransforms.fromModel(
                    EditorTransforms.multiply(operation, EditorTransforms.modelMatrix(t)), t);
            if (isEmitter(path)) {
                moved = new ActorTransform(
                        moved.location(), moved.rotation(), t.drawScale(), t.drawScale3d(), t.prePivot());
            }
            after.put(path, new State(moved, state.skins()));
        });
        return after;
    }

    private void execute(Map<String, State> after, @Nullable String label) {
        requireEditable();
        finishGesture();
        var direct = after;
        var notesBefore = Map.copyOf(lightsLeftBehind);
        leftThisStep.clear();
        var all = withCarried(direct, path -> stateOf(path));
        stepNote = leftThisStep.isEmpty() ? null : String.join(" ", leftThisStep.values());
        var before = new LinkedHashMap<String, State>();
        all.keySet().forEach(path -> before.put(path, stateOf(path)));
        apply(all);
        if (!before.equals(all)) {
            remember(
                    new TransformCommand(
                            Map.copyOf(before), Map.copyOf(all), notesBefore, Map.copyOf(lightsLeftBehind)),
                    label == null ? transformLabel(before, direct) : label);
        }
        revision++;
        scheduleLightingPreview(all.keySet());
    }

    private @Nullable String describeOwnMotion(String path) {
        var text = preview.describeMotion(path, getCarriedActors(path));
        if (text != null) {
            return text;
        }
        var document = documentFor(path);
        var record = document.findActor(path).orElse(null);
        if (record == null) {
            return null;
        }
        if (record.emitter()) {
            return null;
        }
        if (preview.isMoving(path)) {
            return record.className().equals("L2MovableStaticMeshActor")
                    ? MOVEMENT_LINE + " " + SAVED_PLACE_LINE
                    : "Moves by itself. " + SAVED_PLACE_LINE;
        }
        return !record.pawn() && document.isNonStatic(path) ? NOT_STATIC_LINE : null;
    }

    private List<String> copyWithCarried(Map<String, ActorTransform> placements, String label) {
        placements.keySet().forEach(this::documentFor);
        stepNote = null;
        var carried = new HashSet<String>();
        for (var path : placements.keySet()) {
            addFollowers(path, carried);
        }
        var affected = new LinkedHashSet<String>();
        var created = new ArrayList<String>();
        mutate(label, affected, () -> {
            var notes = new ArrayList<String>();
            var taken = new HashSet<String>();
            for (var entry : placements.entrySet()) {
                var path = entry.getKey();
                if (carried.contains(path)) {
                    continue;
                }
                validateTransform(originals.get(path).transform(), entry.getValue());
                var copy = documentFor(path).duplicateActor(path, entry.getValue());
                originals.put(copy.sourcePath(), State.of(copy));
                created.add(copy.sourcePath());
                affected.add(copy.sourcePath());
                copyCarried(
                        path, copy.sourcePath(), getActor(path).transform(), entry.getValue(), affected, notes, taken);
            }
            stepNote = notes.isEmpty() ? null : String.join(" ", notes);
            selection.clear();
            selection.addAll(created);
        });
        return List.copyOf(created);
    }

    private void addFollowers(String carrier, Set<String> result) {
        for (var follower : getCarriedActors(carrier, false)) {
            if (follower.kept() == null && result.add(follower.path())) {
                addFollowers(follower.path(), result);
            }
        }
    }

    private void copyCarried(
            String carrier,
            String carrierCopy,
            ActorTransform from,
            ActorTransform to,
            Set<String> affected,
            List<String> notes,
            Set<String> taken)
            throws IOException {
        var followers = getCarriedActors(carrier, false);
        copyNotes.remove(carrierCopy);
        if (followers.isEmpty()) {
            return;
        }
        var document = documentFor(carrier);
        var events = new LinkedHashMap<String, String>();
        for (var follower : followers) {
            if (follower.kept() == null) {
                events.put(follower.path(), document.getMoveEvent(follower.path()));
            }
        }
        var names = new LinkedHashMap<String, String>();
        for (var tag : L2Attach.getAttachTags(document.getMovementTags(carrier))) {
            var key = tag.toLowerCase(Locale.ROOT);
            if (!names.containsKey(key) && events.values().stream().anyMatch(event -> event.equalsIgnoreCase(tag))) {
                var name = document.createCarryName(taken);
                taken.add(name);
                names.put(key, name);
            }
        }
        var uncopied = new ArrayList<String>();
        for (var follower : followers) {
            var path = follower.path();
            if (follower.kept() != null) {
                uncopied.add(shortName(path) + ": " + stripStop(follower.kept()));
                continue;
            }
            var name = names.get(events.get(path).toLowerCase(Locale.ROOT));
            try {
                if (follower.kind() == L2Swing.Kind.LIGHT) {
                    var light = document.getLight(path).orElseThrow();
                    var at = ActorCarry.carry(
                            from,
                            to,
                            new ActorTransform(
                                    light.location(), light.shape().rotation(), 1, Vector3.ONE, Vector3.ZERO));
                    var copy = document.duplicateCarriedLight(path, at.location(), at.rotation());
                    if (name != null) {
                        document.setCopyMoveEvent(copy.sourcePath(), name);
                    }
                    continue;
                }
                var start = getActor(path).transform();
                var at = toSource(path, ActorCarry.carry(from, to, start));
                var copy = document.duplicateActor(path, at);
                if (name != null) {
                    document.setCopyMoveEvent(copy.sourcePath(), name);
                }
                originals.put(
                        copy.sourcePath(),
                        State.of(document.findActor(copy.sourcePath()).orElseThrow()));
                affected.add(copy.sourcePath());
                if (follower.kind() == L2Swing.Kind.SWING) {
                    copyCarried(path, copy.sourcePath(), start, at, affected, notes, taken);
                }
            } catch (IllegalArgumentException | NoSuchElementException refused) {
                uncopied.add(shortName(path) + ": " + stripStop(refused.getMessage()));
            }
        }
        if (!names.isEmpty()) {
            document.setCopyMovementTags(carrierCopy, List.copyOf(names.values()));
        }
        if (!uncopied.isEmpty()) {
            var text = "Duplicate did not copy " + String.join(", ", uncopied) + ".";
            copyNotes.put(carrierCopy, text);
            notes.add(text);
        }
    }

    private String stripStop(String reason) {
        return reason.endsWith(".") ? reason.substring(0, reason.length() - 1) : reason;
    }

    private List<CarriedActor> getCarriedActors(String carrier, boolean leftBehind) {
        var carried = preview.holdsMapOf(carrier)
                ? preview.getCarried(carrier)
                : documentFor(carrier).getCarried(carrier);
        if (carried.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CarriedActor>(carried.size());
        carried.forEach((path, kind) -> {
            String kept;
            if (kind == L2Swing.Kind.LIGHT) {
                var document = documentFor(path);
                var light = document.getLight(path).orElse(null);
                if (light == null) {
                    if (document.getDeletedLights().stream()
                            .anyMatch(gone -> gone.sourcePath().equals(path))) {
                        return;
                    }
                    kept = CARRIED_UNKNOWN_KEPT;
                } else {
                    var reason = document.getLightCarryRestriction(
                            path, light.location(), light.shape().rotation());
                    kept = !reason.isEmpty() ? CARRIED_LIGHT_KEPT : leftBehind ? lightsLeftBehind.get(path) : null;
                }
            } else {
                var document = documentFor(path);
                var record = document.findActor(path).orElse(null);
                if (record == null && document.getDeletedActor(path) != null) {
                    return;
                }
                if (record != null && record.editable()) {
                    kept = null;
                } else {
                    kept = record == null || record.restriction().isBlank()
                            ? CARRIED_UNKNOWN_KEPT
                            : record.restriction();
                }
            }
            result.add(new CarriedActor(path, kind, kept));
        });
        return List.copyOf(result);
    }

    private Map<String, State> withCarried(Map<String, State> after, Function<String, State> start) {
        var result = new LinkedHashMap<>(after);
        var queue = new ArrayDeque<>(after.keySet());
        while (!queue.isEmpty()) {
            var carrier = queue.poll();
            var from = start.apply(carrier).transform();
            var to = result.get(carrier).transform();
            if (from.equals(to)) {
                continue;
            }
            for (var follower : getCarriedActors(carrier, false)) {
                var path = follower.path();
                if (follower.kept() != null && !result.containsKey(path)) {
                    leftThisStep.put(
                            path, shortName(path) + " keeps its saved place: " + stripStop(follower.kept()) + ".");
                }
                if (follower.kept() != null || result.containsKey(path)) {
                    continue;
                }
                var old = start.apply(path);
                var moved = toSource(path, ActorCarry.carry(from, to, old.transform()));
                if (follower.kind() == L2Swing.Kind.LIGHT) {
                    var reason = documentFor(path).getLightCarryRestriction(path, moved.location(), moved.rotation());
                    if (!reason.isEmpty()) {
                        lightsLeftBehind.put(path, reason + ".");
                        leftThisStep.put(path, shortName(path) + " keeps its saved place: " + reason + ".");
                        continue;
                    }
                    lightsLeftBehind.remove(path);
                }
                result.put(path, new State(moved, old.skins()));
                queue.add(path);
            }
        }
        return result;
    }

    private Map<String, State> withGestureCarried(Map<String, State> after) {
        lightsLeftBehind.clear();
        lightsLeftBehind.putAll(gestureNotes);
        leftThisStep.clear();
        var result = withCarried(after, path -> {
            var direct = gesture.get(path);
            return direct != null ? direct : gestureCarried.computeIfAbsent(path, carried -> stateOf(carried));
        });
        gestureCarried.forEach(result::putIfAbsent);
        return result;
    }

    private void apply(Map<String, State> values) {
        var rollback = new LinkedHashMap<String, State>();
        values.keySet().forEach(path -> rollback.put(path, stateOf(path)));
        if (values.equals(rollback)) {
            return;
        }
        values.forEach((path, value) -> {
            var original = originals.get(path);
            if (original != null) {
                validateTransform(original.transform(), value.transform());
            }
        });
        try {
            values.forEach(this::writeState);
            var materialsChanged = new LinkedHashSet<String>();
            values.forEach((path, value) -> {
                if (!value.skins().equals(rollback.get(path).skins())) {
                    materialsChanged.add(path);
                }
            });
            if (!materialsChanged.isEmpty()) {
                rebuild(materialsChanged);
            }
            publish(values.keySet());
        } catch (RuntimeException failure) {
            rollback.forEach(this::writeState);
            try {
                var materialsChanged = new LinkedHashSet<String>();
                values.forEach((path, value) -> {
                    if (!value.skins().equals(rollback.get(path).skins())) {
                        materialsChanged.add(path);
                    }
                });
                if (!materialsChanged.isEmpty()) {
                    rebuild(materialsChanged);
                }
                publish(values.keySet());
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void writeState(String path, State state) {
        var doc = documentFor(path);
        if (isCarriedLight(path)) {
            var transform = state.transform();
            doc.carryLight(path, transform.location(), transform.rotation());
            return;
        }
        doc.updateTransform(path, state.transform());
        doc.updateSkins(path, state.skins());
    }

    private boolean isCarriedLight(String path) {
        var doc = documentFor(path);
        return doc.findActor(path).isEmpty() && doc.getLight(path).isPresent();
    }

    private State stateOf(String path) {
        var doc = documentFor(path);
        var actor = doc.findActor(path);
        if (actor.isPresent()) {
            return State.of(actor.get());
        }
        var light = doc.getLight(path).orElseThrow(() -> new IllegalArgumentException(UserMessages.NOT_IN_MAP + path));
        return new State(
                new ActorTransform(light.location(), light.shape().rotation(), 1, Vector3.ONE, Vector3.ZERO),
                List.of());
    }

    private ActorTransform toSource(String path, ActorTransform carried) {
        ActorTransform source;
        var original = originals.get(path);
        if (original != null) {
            source = original.transform();
        } else {
            var light = documentFor(path).getLight(path).orElse(null);
            if (light == null || light.created()) {
                return carried;
            }
            source = new ActorTransform(
                    light.sourceLocation(), light.sourceShape().rotation(), 1, Vector3.ONE, Vector3.ZERO);
        }
        var a = carried.location();
        var b = source.location();
        var near =
                Math.abs(a.x() - b.x()) < 1e-2f && Math.abs(a.y() - b.y()) < 1e-2f && Math.abs(a.z() - b.z()) < 1e-2f;
        return near
                        && carried.rotation().equals(source.rotation())
                        && carried.drawScale() == source.drawScale()
                        && carried.drawScale3d().equals(source.drawScale3d())
                        && carried.prePivot().equals(source.prePivot())
                ? source
                : carried;
    }

    private void publish(Set<String> paths) {
        var updates = new LinkedHashMap<String, float[]>();
        var restores = new LinkedHashSet<String>();
        var placements = new LinkedHashMap<String, ActorTransform>();
        for (var path : paths) {
            var current = documentFor(path).findActor(path).orElse(null);
            var baseline = originals.get(path);
            if (current == null && baseline == null) {
                continue;
            }
            if (preview.isMoving(path)) {
                if (previewDeltas.remove(path) != null) {
                    restores.add(path);
                }
                var placed = current != null ? current.transform() : baseline == null ? null : baseline.transform();
                if (placed != null) {
                    placements.put(path, placed);
                }
                continue;
            }
            if (current == null || baseline == null || baseline.transform().equals(current.transform())) {
                if (previewDeltas.remove(path) != null) {
                    restores.add(path);
                }
            } else {
                var delta = EditorTransforms.delta(baseline.transform(), current.transform());
                var previous = previewDeltas.put(path, delta);
                if (!Arrays.equals(previous, delta)) {
                    updates.put(path, delta);
                }
            }
        }
        if (!updates.isEmpty() || !restores.isEmpty()) {
            preview.applyChanges(Map.copyOf(updates), Set.copyOf(restores), previewDeltaView);
        }
        if (!placements.isEmpty()) {
            preview.place(Collections.unmodifiableMap(placements));
        }
    }

    private void remember(Command command, String label) {
        committedCommands++;
        recordedCommands++;
        lastLabel = label;
        undo.push(command);
        while (undo.size() > 256) {
            undo.removeLast();
        }
        redo.clear();
    }

    private void restoreCommand(Command command, boolean forward) {
        if (command instanceof TransformCommand transform) {
            apply(forward ? transform.after() : transform.before());
            lightsLeftBehind.clear();
            lightsLeftBehind.putAll(forward ? transform.notesAfter() : transform.notesBefore());
        } else if (command instanceof DocumentCommand document) {
            var before = checkpoint();
            try {
                restore(forward ? document.after() : document.before());
                rebuild(document.affected());
                publish(document.affected());
            } catch (RuntimeException failure) {
                restore(before);
                try {
                    rebuild(document.affected());
                    publish(document.affected());
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface Mutation {
        void run() throws IOException;
    }

    private void mutate(String label, Set<String> affected, Mutation action) {
        requireEditable();
        finishGesture();
        var before = checkpoint();
        var lightsBefore = lightRecords();
        var given = Set.copyOf(affected);
        try {
            action.run();
            rebuild(affected);
            publish(affected);
            remember(new DocumentCommand(before, checkpoint(), Set.copyOf(affected)), label);
            revision++;
            var created = new LinkedHashSet<>(affected);
            created.removeAll(given);
            groups.forget(created);
            scheduleLightingPreview(affected);
            scheduleLightPreview(lightsBefore);
        } catch (IOException | RuntimeException failure) {
            restore(before);
            try {
                rebuild(affected);
                publish(affected);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalArgumentException(failure.getMessage(), failure);
        }
    }

    private Checkpoint checkpoint() {
        var states = new LinkedHashMap<String, Snapshot>();
        documents.forEach((name, document) -> states.put(name, document.snapshot()));
        return new Checkpoint(
                Map.copyOf(states),
                Map.copyOf(originals),
                Collections.unmodifiableSet(new LinkedHashSet<>(selection)),
                Map.copyOf(assets));
    }

    private void restore(Checkpoint checkpoint) {
        checkpoint.documents().forEach((name, snapshot) -> documents.get(name).restore(snapshot));
        originals
                .keySet()
                .removeIf(path -> !checkpoint.originals().containsKey(path)
                        && checkpoint.documents().containsKey(path.substring(0, path.indexOf('.'))));
        originals.putAll(checkpoint.originals());
        selection.clear();
        selection.addAll(checkpoint.selection());
        assets.clear();
        assets.putAll(checkpoint.assets());
    }

    private void rebuild(Set<String> affected) {
        rebuild(affected, Map.of());
    }

    private void rebuild(Set<String> affected, Map<String, ActorLightingChanges> lighting) {
        if (affected.isEmpty()) {
            return;
        }
        var baselines = new LinkedHashMap<String, ActorTransform>();
        originals.forEach((path, state) -> {
            if (!affected.contains(path) || !preview.isMoving(path)) {
                baselines.put(path, state.transform());
            }
        });
        preview.rebuild(new Rebuild(
                Map.copyOf(documents),
                Map.copyOf(baselines),
                Set.copyOf(affected),
                assetPackages(),
                Map.copyOf(lighting)));
    }

    private Map<String, Path> assetPackages() {
        var packages = new LinkedHashMap<String, Path>();
        assets.forEach((name, asset) -> {
            try {
                if (!asset.fingerprint().equals(stampedFingerprint(asset.path()))) {
                    throw new IllegalArgumentException("An imported package changed during editing: "
                            + asset.path().getFileName());
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
            packages.put(name, asset.path());
        });
        return Map.copyOf(packages);
    }

    private void registerAsset(String objectPath, Path file) throws IOException {
        var packageName = objectPath.substring(0, objectPath.indexOf('.'));
        var path = file.toAbsolutePath().normalize();
        var fileName = path.getFileName().toString();
        var extension = fileName.lastIndexOf('.');
        if (extension <= 0 || !fileName.substring(0, extension).equalsIgnoreCase(packageName)) {
            throw new IllegalArgumentException("The imported package filename must match its package name.");
        }
        var asset = new Asset(path, hashWithStamp(path));
        for (var entry : assets.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(packageName)) {
                if (!entry.getValue().fingerprint().equals(asset.fingerprint())) {
                    throw new IllegalArgumentException(
                            "A different package with that name is already used. Choose a different package name.");
                }
                return;
            }
        }
        assets.put(packageName, asset);
    }

    private String stampedFingerprint(Path file) throws IOException {
        var stamp = assetStamps.get(file);
        if (stamp != null
                && stamp.size() == Files.size(file)
                && stamp.modified().equals(Files.getLastModifiedTime(file))) {
            return stamp.fingerprint();
        }
        return hashWithStamp(file);
    }

    private String hashWithStamp(Path file) throws IOException {
        var size = Files.size(file);
        var modified = Files.getLastModifiedTime(file);
        var fingerprint = fileFingerprint(file);
        assetHashReads++;
        assetStamps.put(file, new FileStamp(size, modified, fingerprint));
        return fingerprint;
    }

    private static int changedFlags(PackageEditDocument document, int surface) throws IOException {
        var bits = 0;
        var changed = false;
        for (var flag : BspSurfaceFlag.values()) {
            var on = document.isSurfaceFlagOn(surface, flag);
            changed |= on != document.isSourceSurfaceFlagOn(surface, flag);
            bits |= on ? flag.getBit() : 0;
        }
        return changed ? bits : -1;
    }

    private static Set<ChangeKind> flagKinds(PackageEditDocument document, int surface) {
        var kinds = EnumSet.noneOf(ChangeKind.class);
        try {
            for (var flag : BspSurfaceFlag.values()) {
                var on = document.isSurfaceFlagOn(surface, flag);
                if (on == document.isSourceSurfaceFlagOn(surface, flag)) {
                    continue;
                }
                kinds.add(
                        switch (flag) {
                            case TWO_SIDED -> on ? ChangeKind.TWO_SIDED : ChangeKind.ONE_SIDED;
                            case UNLIT -> on ? ChangeKind.UNLIT : ChangeKind.LIT;
                        });
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            return Set.of();
        }
        return kinds;
    }

    private static Map<Integer, ShownSurface> withoutOverlay(Map<Integer, ShownSurface> state) {
        var result = new HashMap<Integer, ShownSurface>();
        state.forEach((surface, shown) ->
                result.put(surface, shown.isOverlay() ? new ShownSurface(shown.mapping(), "", false, -1) : shown));
        return Map.copyOf(result);
    }

    private static void refuse(String path, int count, Supplier<String> restriction) {
        if (count < 2) {
            return;
        }
        var reason = restriction.get();
        if (!reason.isEmpty()) {
            throw new IllegalArgumentException(shortName(path) + ": " + reason);
        }
    }

    private static List<Path> suppliedPackages(ExportSnapshot captured) {
        var supplied = new ArrayList<Path>();
        captured.assets().forEach(asset -> supplied.add(asset.source()));
        captured.clientPackages().forEach(asset -> supplied.add(asset.source()));
        return supplied;
    }

    private static NativePackageChanges combine(NativePackageChanges first, NativePackageChanges second)
            throws IOException {
        if (second == null) {
            return first;
        }
        return first == null ? second : first.combine(second);
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static void addLighting(List<String> lines, List<String> tips, Report report) {
        var line = lightingLine(report);
        if (line != null) {
            lines.add(line);
            tips.add(lightingTip(report));
        }
        var world = beastWorldLine(report);
        if (world != null) {
            lines.add(world);
            tips.add(beastWorldTip(report));
        }
    }

    private static void addLine(List<String> lines, List<String> tips, @Nullable String line) {
        if (line != null) {
            lines.add(line);
            tips.add("");
        }
    }

    private static String mapName(PackageEditDocument document) {
        var file = document.getSource().getFileName().toString();
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }

    private static MapInput prepareMap(PackageEditDocument document, NativePackageChanges changes) throws IOException {
        return prepareMap(document, changes, null);
    }

    private static MapInput prepareMap(
            PackageEditDocument document, NativePackageChanges changes, ActorLightingChanges lighting)
            throws IOException {
        var previewPath = document.getSource()
                .getParent()
                .resolve("__cruma_export_preview__")
                .resolve(document.getSource().getFileName());
        return new MapInput(
                document.getSource(),
                document.getOriginalSha256(),
                document.prepareSave(previewPath, changes, lighting));
    }

    private static @Nullable String referencedMesh(PackageEditDocument doc, String meshPath) throws IOException {
        for (var mesh : doc.getMeshReferences()) {
            if (mesh.equalsIgnoreCase(meshPath)) {
                return mesh;
            }
        }
        return null;
    }

    private static String transformLabel(Map<String, State> before, Map<String, State> after) {
        var changed = new ArrayList<String>();
        var rotated = false;
        var scaled = false;
        var moved = false;
        for (var entry : after.entrySet()) {
            var old = before.get(entry.getKey());
            if (old == null || old.equals(entry.getValue())) {
                continue;
            }
            changed.add(entry.getKey());
            var from = old.transform();
            var to = entry.getValue().transform();
            rotated |= !from.rotation().equals(to.rotation());
            scaled |= from.drawScale() != to.drawScale() || !from.drawScale3d().equals(to.drawScale3d());
            moved |= !from.location().equals(to.location());
        }
        var verb = rotated ? "Turn " : scaled ? "Scale " : moved ? "Move " : "Material of ";
        return verb + subject(changed.isEmpty() ? after.keySet() : changed);
    }

    private static void dropUnchangedFollowers(
            Map<String, State> before, Map<String, State> after, Set<String> direct) {
        before.keySet()
                .removeIf(path -> !direct.contains(path) && before.get(path).equals(after.get(path)));
        after.keySet().retainAll(before.keySet());
    }

    private static String subject(Collection<String> paths) {
        return paths.size() == 1 ? shortName(paths.iterator().next()) : paths.size() + " actors";
    }

    private static float distance(Vector3 a, Vector3 b) {
        return (float) Math.sqrt((a.x() - b.x()) * (a.x() - b.x())
                + (a.y() - b.y()) * (a.y() - b.y())
                + (a.z() - b.z()) * (a.z() - b.z()));
    }

    private static void validateTransform(ActorTransform original, ActorTransform edited) {
        var s = edited.drawScale3d();
        var d = edited.drawScale();
        if (!Float.isFinite(d * s.x())
                || !Float.isFinite(d * s.y())
                || !Float.isFinite(d * s.z())
                || Math.abs(d * s.x()) < 1e-6f
                || Math.abs(d * s.y()) < 1e-6f
                || Math.abs(d * s.z()) < 1e-6f) {
            throw new IllegalArgumentException("Scale must be finite and nonzero on every axis.");
        }
    }

    private static Set<String> commandActors(Command command) {
        if (command instanceof TransformCommand transform) {
            return transform.after().keySet();
        }
        return ((DocumentCommand) command).affected();
    }

    private static ActorTransform translated(ActorTransform t, Vector3 offset) {
        var p = t.location();
        return new ActorTransform(
                new Vector3(p.x() + offset.x(), p.y() + offset.y(), p.z() + offset.z()),
                t.rotation(),
                t.drawScale(),
                t.drawScale3d(),
                t.prePivot());
    }

    private static Vector3 mapOrigin(String name) {
        if (!WorldTile.isMapName(name)) {
            throw new IllegalArgumentException("Transfers require a map tile named X_Y.");
        }
        try {
            var tile = WorldTile.fromMapName(name);
            return new Vector3(
                    (tile.x() - WorldDimensions.TILE_ZERO_COORD_X) * WorldDimensions.TILE_SIZE,
                    (tile.y() - WorldDimensions.TILE_ZERO_COORD_Y) * WorldDimensions.TILE_SIZE,
                    0);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Transfers require a map tile named X_Y.");
        }
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException missing) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String fileFingerprint(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
