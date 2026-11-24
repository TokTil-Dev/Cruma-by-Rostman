package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TerrainNativeCodec.Box;
import com.rost.cruma.core.world.NativeBspLightAssociation;
import com.rost.cruma.core.world.NativeBspLightAssociation.CandidateFilter;
import com.rost.cruma.core.world.NativeBspPointAdmission;
import com.rost.cruma.core.world.NativeBspShadowProjection.Placement;
import com.rost.cruma.core.world.NativeBspShadowProjection.Plane;
import com.rost.cruma.core.world.NativeBspShadowProjection.Vector;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class P152BspReceiverModel {
    private final int modelReference, fileVersion, licenseVersion;
    private final ConsumerFacts consumerFacts;
    private final List<AtlasExtent> atlasExtents;
    private final int savedAtlasExtentCount;
    private final String sourcePackageName, sourceBodySha256;
    private final List<Integer> atlasVariantCounts;
    private final List<List<NativeBspPointAdmission.Node>> pointNodesBySurface;
    private final List<NativeBspPointAdmission.Node> pointNodesByIndex;
    private final List<Receiver> placements;
    private final List<List<SavedMask>> savedMasksByPlacement;
    private final List<Integer> zoneActorReferences;
    private final boolean beastLighting;
    private final List<Integer> nativeLightReferences;
    private final List<Integer> permeatingLightStarts;
    private final List<NativeBspLightAssociation.Node> associationNodes;
    private final boolean[] twoSidedSurfaces;
    private final List<Surface> surfaces;

    private P152BspReceiverModel(
            PackageBytes source,
            List<Integer> atlasVariantCounts,
            List<List<NativeBspPointAdmission.Node>> pointNodesBySurface,
            List<NativeBspPointAdmission.Node> pointNodesByIndex,
            int modelReference,
            List<Receiver> placements,
            List<List<SavedMask>> savedMasksByPlacement,
            List<Integer> zoneActorReferences,
            boolean beastLighting,
            List<Integer> nativeLightReferences,
            List<Integer> permeatingLightStarts,
            ConsumerFacts consumerFacts,
            List<AtlasExtent> atlasExtents,
            int savedAtlasExtentCount,
            List<NativeBspLightAssociation.Node> associationNodes,
            boolean[] twoSidedSurfaces,
            List<Surface> surfaces) {
        this.modelReference = modelReference;
        this.fileVersion = source.fileVersion;
        this.licenseVersion = source.licenseVersion;
        this.consumerFacts = consumerFacts;
        this.atlasExtents = List.copyOf(atlasExtents);
        this.savedAtlasExtentCount = savedAtlasExtentCount;
        this.sourcePackageName = source.packageName;
        this.sourceBodySha256 = bodyHash(source);
        this.atlasVariantCounts = List.copyOf(atlasVariantCounts);
        this.pointNodesBySurface =
                pointNodesBySurface.stream().map(List::copyOf).toList();
        this.pointNodesByIndex = List.copyOf(pointNodesByIndex);
        this.placements = List.copyOf(placements);
        this.savedMasksByPlacement =
                savedMasksByPlacement.stream().map(List::copyOf).toList();
        this.zoneActorReferences = List.copyOf(zoneActorReferences);
        this.beastLighting = beastLighting;
        this.nativeLightReferences = List.copyOf(nativeLightReferences);
        this.permeatingLightStarts = List.copyOf(permeatingLightStarts);
        this.associationNodes = List.copyOf(associationNodes);
        this.twoSidedSurfaces = twoSidedSurfaces.clone();
        this.surfaces = List.copyOf(surfaces);
    }

    record Receiver(
            int index,
            int atlasIndex,
            int surfaceIndex,
            int zone,
            int offsetX,
            int offsetY,
            Placement projection,
            Vector savedOrigin,
            Vector savedAxisU,
            Vector savedAxisV,
            List<Integer> lightReferences,
            int levelReference) {
        Receiver {
            lightReferences = List.copyOf(lightReferences);
        }
    }

    record AtlasExtent(int width, int height) {}

    record TextureStorage(
            int atlas,
            int variant,
            int format,
            int width,
            int height,
            int revision,
            int staticRevision,
            int fullOffset,
            int fullBytes,
            int halfOffset,
            int halfBytes) {}

    record TextureCapture(P152BspReceiverModel model, List<TextureStorage> textures) {
        TextureCapture {
            textures = List.copyOf(textures);
        }
    }

    record MaskStorage(int placement, int ordinal, int offset, int byteCount) {}

    record MaskCapture(P152BspReceiverModel model, List<MaskStorage> masks) {
        MaskCapture {
            masks = List.copyOf(masks);
        }
    }

    record SavedMask(
            int ordinal,
            int lightReference,
            int width,
            int height,
            int packedStride,
            int minX,
            int minY,
            int maxX,
            int maxY,
            int byteCount,
            boolean anyNonzero,
            Optional<Boolean> validBitNonzero) {
        SavedMask {
            Objects.requireNonNull(validBitNonzero);
        }

        Optional<String> metadataRestriction() {
            return Optional.ofNullable(
                    maskMetadataRestriction(width, height, packedStride, minX, minY, maxX, maxY, byteCount));
        }

        Optional<String> metadataRestriction(int placementWidth, int placementHeight) {
            var restriction = metadataRestriction();
            if (restriction.isPresent()) {
                return restriction;
            }
            if (placementWidth <= 0 || placementHeight <= 0 || maxX >= placementWidth || maxY >= placementHeight) {
                return Optional.of("saved mask crop lies outside the placement");
            }
            return Optional.empty();
        }
    }

    record DormantFacts(
            int nodes,
            int surfaces,
            int zones,
            int leaves,
            int savedAtlasExtents,
            List<Integer> nodePlacementLinks,
            List<Integer> placementAtlasLinks,
            List<Integer> sectionAtlasLinks,
            List<Integer> placementLevelReferences) {
        DormantFacts {
            nodePlacementLinks = List.copyOf(nodePlacementLinks);
            placementAtlasLinks = List.copyOf(placementAtlasLinks);
            sectionAtlasLinks = List.copyOf(sectionAtlasLinks);
            placementLevelReferences = List.copyOf(placementLevelReferences);
        }

        void requireLevelReference(int expected) throws IOException {
            if (expected <= 0 || placementLevelReferences.stream().anyMatch(reference -> reference != expected)) {
                throw invalidModel("dormant placement Level differs from native Level.Model binding");
            }
        }
    }

    private enum Purpose {
        RECEIVERS,
        DORMANT,
        SIMPLE_COLLISION_BOUNDS
    }

    private record Parsed(P152BspReceiverModel model, DormantFacts dormant, Box simpleBounds) {}

    record ConsumerFacts(
            int nodes,
            int sections,
            int placements,
            int atlases,
            int nodeLightmapLinks,
            int sectionAtlasLinks,
            long atlasPlacementLinks,
            long savedVariantPlacementLinks) {
        boolean noSavedReceivers() {
            return placements == 0
                    && atlases == 0
                    && nodeLightmapLinks == 0
                    && sectionAtlasLinks == 0
                    && atlasPlacementLinks == 0
                    && savedVariantPlacementLinks == 0;
        }

        boolean hasSingleUnreferencedAtlas() {
            return placements == 0
                    && atlases == 1
                    && nodeLightmapLinks == 0
                    && sectionAtlasLinks == 0
                    && atlasPlacementLinks == 0
                    && savedVariantPlacementLinks == 0;
        }
    }

    private record Node(
            Plane plane,
            Vector sphereCenter,
            float sphereRadius,
            int vertexPool,
            int surface,
            int back,
            int front,
            int coplanar,
            int renderBound,
            int zone0,
            int zone1,
            int vertices,
            int leaf0,
            int leaf1,
            int section,
            int firstVertex,
            int lightmap) {}

    private record Surface(float[] plane, int flags, Vector normal) {}

    private record Section(int vertices, int atlas) {}

    static P152BspReceiverModel decode(PackageBytes source, int modelReference) throws IOException {
        try {
            return read(source, modelReference, Purpose.RECEIVERS, () -> false, null, null)
                    .model();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid native BSP receiver data", e);
        }
    }

    static TextureCapture captureTextures(PackageBytes source, int modelReference, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var textures = new ArrayList<TextureStorage>();
        try {
            var model = read(source, modelReference, Purpose.RECEIVERS, cancelled, textures, null)
                    .model();
            P152FreshHashQuery.checkCancelled(cancelled);
            return new TextureCapture(model, textures);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid native BSP texture storage", e);
        }
    }

    static MaskCapture captureMasks(PackageBytes source, int modelReference, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        var masks = new ArrayList<MaskStorage>();
        try {
            var model = read(source, modelReference, Purpose.RECEIVERS, cancelled, null, masks)
                    .model();
            P152FreshHashQuery.checkCancelled(cancelled);
            return new MaskCapture(model, masks);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid native BSP visibility storage", e);
        }
    }

    static DormantFacts inspectDormantStructure(PackageBytes source, int modelReference, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        if (source.fileVersion != 132 || source.licenseVersion != 40 || source.body.length > 1024 * 1024) {
            throw invalidModel("dormant structure requires bounded file132/license40");
        }
        try {
            return read(source, modelReference, Purpose.DORMANT, cancelled, null, null)
                    .dormant();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IOException("Invalid dormant native BSP structure", e);
        }
    }

    static Box captureSimpleCollisionBounds(PackageBytes source, int reference, BooleanSupplier cancelled)
            throws IOException {
        P152FreshHashQuery.checkCancelled(cancelled);
        try {
            var result = read(source, reference, Purpose.SIMPLE_COLLISION_BOUNDS, cancelled, null, null)
                    .simpleBounds();
            P152FreshHashQuery.checkCancelled(cancelled);
            return result;
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("Invalid simple collision Model bounds", failure);
        }
    }

    NativeBspLightAssociation getLightAssociation(BooleanSupplier cancelled) throws IOException {
        return NativeBspLightAssociation.capture(
                associationNodes,
                twoSidedSurfaces,
                permeatingLightStarts.stream().mapToInt(Integer::intValue).toArray(),
                nativeLightReferences.stream().mapToInt(Integer::intValue).toArray(),
                cancelled);
    }

    NativeBspLightAssociation getLightAssociation(CandidateFilter filter, BooleanSupplier cancelled)
            throws IOException {
        return NativeBspLightAssociation.capture(
                associationNodes,
                twoSidedSurfaces,
                permeatingLightStarts.stream().mapToInt(Integer::intValue).toArray(),
                nativeLightReferences.stream().mapToInt(Integer::intValue).toArray(),
                filter,
                cancelled);
    }

    List<NativeBspPointAdmission.Node> getPointNodesByIndex() {
        return pointNodesByIndex;
    }

    ConsumerFacts getConsumerFacts() {
        return consumerFacts;
    }

    List<AtlasExtent> getAtlasExtents() {
        return atlasExtents;
    }

    int getSavedAtlasExtentCount() {
        return savedAtlasExtentCount;
    }

    int getFileVersion() {
        return fileVersion;
    }

    int getLicenseVersion() {
        return licenseVersion;
    }

    void requireSource(PackageBytes source) throws IOException {
        if (!sourcePackageName.equals(source.packageName) || !sourceBodySha256.equals(bodyHash(source))) {
            throw invalidModel("receiver snapshot belongs to a different frozen source");
        }
    }

    List<NativeBspPointAdmission.Node> pointNodes(int surfaceIndex) {
        return pointNodesBySurface.get(surfaceIndex);
    }

    Vector getSurfaceNormal(int surfaceIndex) {
        return surfaces.get(surfaceIndex).normal();
    }

    int getSurfaceFlags(int surfaceIndex) {
        return surfaces.get(surfaceIndex).flags();
    }

    List<Integer> getAtlasVariantCounts() {
        return atlasVariantCounts;
    }

    int getModelReference() {
        return modelReference;
    }

    List<Receiver> getPlacements() {
        return placements;
    }

    List<SavedMask> getSavedMasks(int placementIndex) {
        return savedMasksByPlacement.get(placementIndex);
    }

    List<Integer> getZoneActorReferences() {
        return zoneActorReferences;
    }

    List<Integer> getNativeLightReferences() {
        return nativeLightReferences;
    }

    List<Integer> getPermeatingLightStarts() {
        return permeatingLightStarts;
    }

    boolean isBeastLighting() {
        return beastLighting;
    }

    boolean areOrphanResourcesExcluded() {
        return !beastLighting && consumerFacts.hasSingleUnreferencedAtlas();
    }

    private static String bodyHash(PackageBytes source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Parsed read(
            PackageBytes source,
            int reference,
            Purpose purpose,
            BooleanSupplier cancelled,
            List<TextureStorage> textures,
            List<MaskStorage> maskStorage)
            throws IOException {
        var dormant = purpose == Purpose.DORMANT;
        var simple = purpose == Purpose.SIMPLE_COLLISION_BOUNDS;
        if (source.fileVersion < 123
                || source.fileVersion > 132
                || source.licenseVersion < 16
                || source.licenseVersion > 40) {
            throw invalidModel("requires audited file123..132/license16..40");
        }
        if (reference <= 0 || reference > source.exports.size()) {
            throw invalidModel("requires a local Model export");
        }
        var entry = source.exports.get(reference - 1);
        P152ActorDomain.requireEngineClass(source, entry, "Model");
        requireEditorLoad(source, reference);
        if (entry.outer() != 0) {
            throw invalidModel("Model outer load effects require a separately audited profile");
        }
        if ((entry.flags() & 0x02000000) != 0) {
            throw invalidModel("Model has a state frame");
        }
        var c = new Cursor(source.body, entry.serialOffset(), entry.serialOffset() + entry.serialSize());
        if (!source.getName(c.readIndex()).equals("None")) {
            throw invalidModel("native-only Model has tagged properties");
        }
        var primitiveBounds = readBox(c, simple);
        readFloats(c, 4);
        var vectors = count(c, 12);
        var vectorCoordinates = readFloats(c, vectors * 3);
        var points = count(c, 12);
        readFloats(c, points * 3);
        var nodes = new ArrayList<Node>();
        for (int n = count(c, 87); n > 0; n--) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var nodePlane = readFloats(c, 4);
            c.skip(8);
            c.readU8();
            var pool = c.readIndex();
            var surface = c.readIndex();
            var back = c.readIndex();
            var front = c.readIndex();
            var coplanar = c.readIndex();
            c.readIndex();
            var bound = c.readIndex();
            var sphere = readFloats(c, 8);
            var zone0 = c.readIndex();
            var zone1 = c.readIndex();
            var vertices = c.readU8();
            nodes.add(new Node(
                    new Plane(nodePlane[0], nodePlane[1], nodePlane[2], nodePlane[3]),
                    new Vector(sphere[0], sphere[1], sphere[2]),
                    sphere[3],
                    pool,
                    surface,
                    back,
                    front,
                    coplanar,
                    bound,
                    zone0,
                    zone1,
                    vertices,
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32(),
                    c.readI32()));
        }
        var surfaces = new ArrayList<Surface>();
        for (int n = count(c, source.licenseVersion > 20 ? 35 : 31); n > 0; n--) {
            P152FreshHashQuery.checkCancelled(cancelled);
            readObjectReference(source, c);
            var surfaceFlags = c.readI32();
            index(c.readIndex(), points, false, "surface base point");
            var normal = c.readIndex();
            index(normal, vectors, false, "surface normal");
            for (int i = 0; i < 2; i++) {
                index(c.readIndex(), vectors, false, "surface vector");
            }
            c.readIndex();
            readObjectReference(source, c);
            surfaces.add(new Surface(
                    readFloats(c, 4),
                    surfaceFlags,
                    new Vector(
                            vectorCoordinates[normal * 3],
                            vectorCoordinates[normal * 3 + 1],
                            vectorCoordinates[normal * 3 + 2])));
            readFiniteFloat(c);
            if (source.licenseVersion > 20) {
                c.readI32();
            }
        }
        var vertices = count(c, 2);
        var vertexPoints = new int[vertices];
        for (int i = 0; i < vertices; i++) {
            vertexPoints[i] = c.readIndex();
            c.readIndex();
        }
        if (c.readI32() < 0) {
            throw invalidModel("negative shared-side count");
        }
        var numZones = c.readI32();
        if (numZones < 0 || numZones > 64) {
            throw invalidModel("invalid fixed zone count");
        }
        var zones = new ArrayList<Integer>(64);
        for (int i = 0; i < numZones; i++) {
            zones.add(readObjectReference(source, c));
            c.skip(16);
            readFiniteFloat(c);
        }
        while (zones.size() < 64) {
            zones.add(0);
        }
        readObjectReference(source, c);
        var bounds = count(c, 25);
        for (int i = 0; i < bounds; i++) {
            readBox(c, false);
        }
        c.skip(count(c, 4) * 4);
        var leaves = count(c, 11);
        var permeating = new ArrayList<Integer>(leaves);
        for (int i = 0; i < leaves; i++) {
            c.readIndex();
            permeating.add(c.readIndex());
            c.readIndex();
            c.skip(8);
        }
        var nativeLights = new ArrayList<Integer>();
        for (int n = count(c, 1); n > 0; n--) {
            nativeLights.add(readObjectReference(source, c));
        }
        var terminated = new boolean[nativeLights.size()];
        boolean foundNull = false;
        for (int i = nativeLights.size() - 1; i >= 0; i--) {
            foundNull |= nativeLights.get(i) == 0;
            terminated[i] = foundNull;
        }
        for (var start : permeating) {
            if (start != -1 && (start < 0 || start >= terminated.length || !terminated[start])) {
                throw invalidModel("unterminated or out-of-range native permeating light list");
            }
        }
        readBool(c);
        readBool(c);
        var sections = new ArrayList<Section>();
        for (int n = count(c, 18); n > 0; n--) {
            var count = count(c, 40);
            for (int i = 0; i < count; i++) {
                readFloats(c, 3);
                c.skip(28);
            }
            c.readI32();
            readObjectReference(source, c);
            if (c.readI32() < 0) {
                throw invalidModel("negative section node count");
            }
            c.readI32();
            sections.add(new Section(count, c.readI32()));
        }
        var placements = new ArrayList<Receiver>();
        var savedMasksByPlacement = new ArrayList<List<SavedMask>>();
        for (int n = count(c, 113); n > 0; n--) {
            P152FreshHashQuery.checkCancelled(cancelled);
            var atlas = c.readIndex();
            var surface = c.readIndex();
            var zone = c.readIndex();
            var x = c.readIndex();
            var y = c.readIndex();
            var width = c.readIndex();
            var height = c.readIndex();
            index(surface, surfaces.size(), false, "placement surface");
            if (zone < 0 || zone >= numZones || x < 0 || y < 0) {
                throw invalidModel("invalid placement zone/offset");
            }
            var matrix = readFloats(c, 16);
            var origin = readVector(c);
            var axisU = readVector(c);
            var axisV = readVector(c);
            if (isZero(axisU) || isZero(axisV)) {
                throw invalidModel("zero saved placement axis");
            }
            var lightRefs = new ArrayList<Integer>();
            var savedMasks = new ArrayList<SavedMask>();
            for (int m = count(c, 30); m > 0; m--) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var light = readObjectReference(source, c);
                lightRefs.add(light);
                var byteCount = count(c, 1);
                var dataOffset = c.position;
                if (maskStorage != null) {
                    maskStorage.add(new MaskStorage(placements.size(), savedMasks.size(), dataOffset, byteCount));
                }
                c.skip(byteCount);
                var maskWidth = c.readI32();
                var maskHeight = c.readI32();
                var stride = c.readI32();
                var minX = c.readI32();
                var minY = c.readI32();
                var maxX = c.readI32();
                var maxY = c.readI32();
                savedMasks.add(savedMask(
                        source.body,
                        dataOffset,
                        savedMasks.size(),
                        light,
                        maskWidth,
                        maskHeight,
                        stride,
                        minX,
                        minY,
                        maxX,
                        maxY,
                        byteCount,
                        cancelled));
            }
            savedMasksByPlacement.add(savedMasks);
            var level = readObjectReference(source, c);
            c.readI32();
            var p = surfaces.get(surface).plane;
            placements.add(new Receiver(
                    placements.size(),
                    atlas,
                    surface,
                    zone,
                    x,
                    y,
                    new Placement(width, height, matrix, new Plane(p[0], p[1], p[2], p[3])),
                    origin,
                    axisU,
                    axisV,
                    lightRefs,
                    level));
        }
        var atlases = count(c, 3);
        var variants = new ArrayList<Integer>(atlases);
        long atlasPlacementLinks = 0, savedVariantPlacementLinks = 0;
        for (int a = 0; a < atlases; a++) {
            var variantCount = count(c, 37);
            variants.add(variantCount);
            for (int n = variantCount; n > 0; n--) {
                P152FreshHashQuery.checkCancelled(cancelled);
                readObjectReference(source, c);
                savedVariantPlacementLinks += lightmapIndices(c, placements.size());
                c.skip(8);
                var revision = c.readI32();
                var bytes = skipLazyArray(c);
                var fullOffset = c.position - bytes;
                var halfBytes = skipLazyArray(c);
                var halfOffset = c.position - halfBytes;
                var format = c.readU8();
                var width = c.readI32();
                var height = c.readI32();
                var staticRevision = c.readI32();
                var empty = width == 0 && height == 0 && bytes == 0 && halfBytes == 0;
                if (!empty && (width <= 0 || height <= 0 || width > 65536 || height > 65536)) {
                    throw invalidModel("invalid native atlas extent");
                }
                if (textures != null) {
                    textures.add(new TextureStorage(
                            a,
                            variantCount - n,
                            format,
                            width,
                            height,
                            revision,
                            staticRevision,
                            fullOffset,
                            bytes,
                            halfOffset,
                            halfBytes));
                }
            }
            atlasPlacementLinks += lightmapIndices(c, placements.size());
            if (source.fileVersion > 125) {
                readObjectReference(source, c);
            }
        }
        var savedExtents = source.fileVersion >= 125 ? count(c, 8) : 0;
        var extents = Math.max(savedExtents, atlases);
        var widths = new int[extents];
        var heights = new int[extents];
        var atlasExtents = new ArrayList<AtlasExtent>(extents);
        for (int i = 0; i < extents; i++) {
            widths[i] = i < savedExtents ? c.readI32() : 512;
            heights[i] = i < savedExtents ? c.readI32() : 512;
            if (widths[i] <= 0 || heights[i] <= 0 || widths[i] > 65536 || heights[i] > 65536) {
                throw invalidModel("invalid saved atlas extent");
            }
            atlasExtents.add(new AtlasExtent(widths[i], heights[i]));
        }
        var beast = source.fileVersion > 126 && readBool(c);
        if (c.position != c.end) {
            throw invalidModel("unknown or trailing native Model bytes");
        }
        if (dormant && (atlases != 0 || beast || placements.isEmpty())) {
            throw invalidModel("dormant structure requires retained placements, no atlas and no Beast lighting");
        }
        if (simple
                && (numZones != 0
                        || atlases != 0
                        || beast
                        || !placements.isEmpty()
                        || !sections.isEmpty()
                        || !nativeLights.isEmpty()
                        || savedExtents != 0)) {
            throw invalidModel("simple collision bounds require an unzoned brush without lighting resources");
        }
        for (var p : placements) {
            if (dormant) {
                if (p.atlasIndex != 0) {
                    throw invalidModel("dormant placement must retain the audited dangling atlas zero");
                }
            } else {
                index(p.atlasIndex, atlases, false, "placement atlas");
            }
            if (!dormant
                    && ((long) p.offsetX + p.projection.getWidth() > widths[p.atlasIndex]
                            || (long) p.offsetY + p.projection.getHeight() > heights[p.atlasIndex])) {
                throw invalidModel("placement exceeds saved atlas extent");
            }
        }
        for (var section : sections) {
            if (dormant) {
                if (section.atlas != -1 && section.atlas != 0) {
                    throw invalidModel("dormant section must be unlit or retain dangling atlas zero");
                }
            } else {
                index(section.atlas, atlases, true, "section atlas");
            }
        }
        for (var node : nodes) {
            P152FreshHashQuery.checkCancelled(cancelled);
            index(node.surface, surfaces.size(), false, "node surface");
            index(node.back, nodes.size(), true, "back child");
            index(node.front, nodes.size(), true, "front child");
            index(node.coplanar, nodes.size(), true, "coplanar node");
            index(node.renderBound, bounds, true, "node bound");
            index(node.leaf0, leaves, true, "back leaf");
            index(node.leaf1, leaves, true, "front leaf");
            var zoneLimit = simple ? 1 : numZones;
            if (node.zone0 < 0 || node.zone0 >= zoneLimit || node.zone1 < 0 || node.zone1 >= zoneLimit) {
                throw invalidModel("node zone outside fixed source zones");
            }
            if (node.vertices > 0 && (node.vertexPool < 0 || (long) node.vertexPool + node.vertices > vertices)) {
                throw invalidModel("node vertex span outside source pool");
            }
            for (int i = 0; i < node.vertices; i++) {
                index(vertexPoints[node.vertexPool + i], points, false, "used vertex point");
            }
            index(node.section, sections.size(), true, "node section");
            if (node.section >= 0
                    && (node.firstVertex < 0
                            || (long) node.firstVertex + node.vertices > sections.get(node.section).vertices)) {
                throw invalidModel("node render-vertex span outside source section");
            }
            index(node.lightmap, placements.size(), true, "node lightmap");
            if (node.lightmap >= 0 && placements.get(node.lightmap).surfaceIndex != node.surface) {
                throw invalidModel("node and placement name different surfaces");
            }
        }
        if (simple) {
            requireAcyclic(nodes, cancelled);
            if (primitiveBounds.valid() != 1
                    || primitiveBounds.min().x() > primitiveBounds.max().x()
                    || primitiveBounds.min().y() > primitiveBounds.max().y()
                    || primitiveBounds.min().z() > primitiveBounds.max().z()) {
                throw invalidModel("simple collision Model requires a valid ordered primitive box");
            }
            return new Parsed(null, null, primitiveBounds);
        }
        if (dormant) {
            requireAcyclic(nodes, cancelled);
            for (var node : nodes) {
                if (node.sphereRadius < 0) {
                    throw invalidModel("negative dormant node sphere radius");
                }
            }
            return new Parsed(
                    null,
                    new DormantFacts(
                            nodes.size(),
                            surfaces.size(),
                            numZones,
                            leaves,
                            savedExtents,
                            nodes.stream().map(Node::lightmap).toList(),
                            placements.stream().map(Receiver::atlasIndex).toList(),
                            sections.stream().map(Section::atlas).toList(),
                            placements.stream().map(Receiver::levelReference).toList()),
                    null);
        }
        var pointNodes = new ArrayList<List<NativeBspPointAdmission.Node>>(surfaces.size());
        var indexedPointNodes = new ArrayList<NativeBspPointAdmission.Node>(nodes.size());
        for (int i = 0; i < surfaces.size(); i++) {
            pointNodes.add(new ArrayList<>());
        }
        for (var node : nodes) {
            var geometry = new NativeBspPointAdmission.Node(
                    node.plane, node.sphereCenter, node.sphereRadius, (surfaces.get(node.surface).flags & 0x100) != 0);
            pointNodes.get(node.surface).add(geometry);
            indexedPointNodes.add(geometry);
        }
        var associationNodes = new ArrayList<NativeBspLightAssociation.Node>(nodes.size());
        for (var node : nodes) {
            associationNodes.add(new NativeBspLightAssociation.Node(
                    node.surface, node.front, node.back, node.coplanar, node.leaf1, node.leaf0));
        }
        var twoSided = new boolean[surfaces.size()];
        for (int i = 0; i < twoSided.length; i++) {
            twoSided[i] = (surfaces.get(i).flags & 0x100) != 0;
        }
        var consumerFacts = new ConsumerFacts(
                nodes.size(),
                sections.size(),
                placements.size(),
                atlases,
                (int) nodes.stream().filter(node -> node.lightmap >= 0).count(),
                (int) sections.stream().filter(section -> section.atlas >= 0).count(),
                atlasPlacementLinks,
                savedVariantPlacementLinks);
        return new Parsed(
                new P152BspReceiverModel(
                        source,
                        variants,
                        pointNodes,
                        indexedPointNodes,
                        reference,
                        placements,
                        savedMasksByPlacement,
                        zones,
                        beast,
                        nativeLights,
                        permeating,
                        consumerFacts,
                        atlasExtents,
                        savedExtents,
                        associationNodes,
                        twoSided,
                        surfaces),
                null,
                null);
    }

    private static SavedMask savedMask(
            byte[] body,
            int offset,
            int ordinal,
            int light,
            int width,
            int height,
            int stride,
            int minX,
            int minY,
            int maxX,
            int maxY,
            int byteCount,
            BooleanSupplier cancelled) {
        var consistent = maskMetadataRestriction(width, height, stride, minX, minY, maxX, maxY, byteCount) == null;
        var any = false;
        var valid = false;
        var remainder = width & 7;
        var lastMask = remainder == 0 ? 0xff : (1 << remainder) - 1;
        for (int i = 0; i < byteCount; i++) {
            if ((i & 4095) == 0) {
                P152FreshHashQuery.checkCancelled(cancelled);
            }
            var value = body[offset + i] & 0xff;
            any |= value != 0;
            if (consistent && value != 0) {
                valid |= (value & (i % stride == stride - 1 ? lastMask : 0xff)) != 0;
            }
            if (any && (!consistent || valid)) {
                break;
            }
        }
        P152FreshHashQuery.checkCancelled(cancelled);
        return new SavedMask(
                ordinal,
                light,
                width,
                height,
                stride,
                minX,
                minY,
                maxX,
                maxY,
                byteCount,
                any,
                consistent ? Optional.of(valid) : Optional.empty());
    }

    private static String maskMetadataRestriction(
            int width, int height, int stride, int minX, int minY, int maxX, int maxY, int byteCount) {
        if (width <= 0 || height <= 0) {
            return "saved mask dimensions are not positive";
        }
        if (minX < 0 || minY < 0 || maxX < minX || maxY < minY) {
            return "saved mask crop is invalid";
        }
        if ((long) maxX - minX + 1 != width || (long) maxY - minY + 1 != height) {
            return "saved mask dimensions differ from the inclusive crop";
        }
        if (stride != ((long) width + 7) / 8) {
            return "saved mask packed stride differs from the width";
        }
        if ((long) stride * height != byteCount) {
            return "saved mask byte count differs from stride times height";
        }
        return null;
    }

    private static void requireAcyclic(List<Node> nodes, BooleanSupplier cancelled) throws IOException {
        var state = new byte[nodes.size()];
        var stack = new int[nodes.size()];
        var edge = new byte[nodes.size()];
        for (int root = 0; root < nodes.size(); root++) {
            if (state[root] != 0) {
                continue;
            }
            var depth = 0;
            stack[0] = root;
            state[root] = 1;
            while (depth >= 0) {
                P152FreshHashQuery.checkCancelled(cancelled);
                var current = stack[depth];
                if (edge[depth] == 3) {
                    state[current] = 2;
                    edge[depth--] = 0;
                    continue;
                }
                var node = nodes.get(current);
                var child = switch (edge[depth]++) {
                    case 0 -> node.front;
                    case 1 -> node.back;
                    default -> node.coplanar;
                };
                if (child < 0 || state[child] == 2) {
                    continue;
                }
                if (state[child] == 1) {
                    throw invalidModel("cycle in native Model node graph");
                }
                state[child] = 1;
                stack[++depth] = child;
            }
        }
    }

    private static void requireEditorLoad(PackageBytes source, int reference) throws IOException {
        var seen = new HashSet<Integer>();
        while (reference != 0) {
            if (reference < 0 || reference > source.exports.size() || !seen.add(reference)) {
                throw invalidModel("unresolved or cyclic local Model outer chain");
            }
            var entry = source.exports.get(reference - 1);
            if ((entry.flags() & 0x40000) == 0 || (entry.flags() & 0x400000) != 0) {
                throw invalidModel("Model or outer is excluded from native editor loading");
            }
            reference = entry.outer();
        }
    }

    private static int count(Cursor c, int minimumBytes) throws IOException {
        var n = PackageBytes.count(c.readIndex());
        if (n > (c.end - c.position) / minimumBytes) {
            throw invalidModel("array count exceeds bounded payload");
        }
        return n;
    }

    private static void index(int i, int count, boolean none, String label) throws IOException {
        if (i < (none ? -1 : 0) || i >= count) {
            throw invalidModel("invalid " + label + ": " + i + " / " + count);
        }
    }

    private static int readObjectReference(PackageBytes source, Cursor c) throws IOException {
        var value = c.readIndex();
        source.checkReference(value);
        return value;
    }

    private static float readFiniteFloat(Cursor c) throws IOException {
        var value = Float.intBitsToFloat(c.readI32());
        if (!Float.isFinite(value)) {
            throw invalidModel("non-finite native field at " + (c.position - 4));
        }
        return value;
    }

    private static float[] readFloats(Cursor c, int n) throws IOException {
        var result = new float[n];
        for (int i = 0; i < n; i++) {
            result[i] = readFiniteFloat(c);
        }
        return result;
    }

    private static Vector readVector(Cursor c) throws IOException {
        return new Vector(readFiniteFloat(c), readFiniteFloat(c), readFiniteFloat(c));
    }

    private static boolean isZero(Vector v) {
        return v.x() == 0 && v.y() == 0 && v.z() == 0;
    }

    private static Box readBox(Cursor c, boolean capture) throws IOException {
        var values = readFloats(c, 6);
        var valid = c.readU8();
        if (valid > 1) {
            throw invalidModel("invalid native FBox validity byte");
        }
        return !capture
                ? null
                : new Box(
                        new TerrainNativeCodec.Vector(values[0], values[1], values[2]),
                        new TerrainNativeCodec.Vector(values[3], values[4], values[5]),
                        valid);
    }

    private static boolean readBool(Cursor c) throws IOException {
        var value = c.readI32();
        if (value != 0 && value != 1) {
            throw invalidModel("invalid native BOOL");
        }
        return value != 0;
    }

    private static int skipLazyArray(Cursor c) throws IOException {
        var end = c.readI32();
        var count = count(c, 1);
        c.skip(count);
        if (end != c.position) {
            throw invalidModel("lazy atlas end offset mismatch");
        }
        return count;
    }

    private static int lightmapIndices(Cursor c, int count) throws IOException {
        var entries = count(c, 4);
        for (int n = entries; n > 0; n--) {
            index(c.readI32(), count, false, "atlas lightmap index");
        }
        return entries;
    }

    private static IOException invalidModel(String reason) {
        return new IOException("P152 BSP receiver Model: " + reason);
    }
}
