package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.Bsp.UModel;
import com.rost.cruma.core.unreal.UActor.AmbientSoundActor;
import com.rost.cruma.core.unreal.UActor.AmbientSoundWObjectActor;
import com.rost.cruma.core.unreal.UActor.AmbientVolumeActor;
import com.rost.cruma.core.unreal.UActor.AntiPortalActor;
import com.rost.cruma.core.unreal.UActor.BlockingVolumeActor;
import com.rost.cruma.core.unreal.UActor.BrushActor;
import com.rost.cruma.core.unreal.UActor.EmitterActor;
import com.rost.cruma.core.unreal.UActor.L2FogInfoActor;
import com.rost.cruma.core.unreal.UActor.L2SkyLevelZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.LightActor;
import com.rost.cruma.core.unreal.UActor.MusicVolumeActor;
import com.rost.cruma.core.unreal.UActor.NCelestialActor;
import com.rost.cruma.core.unreal.UActor.PhysicsVolumeActor;
import com.rost.cruma.core.unreal.UActor.ProjectorActor;
import com.rost.cruma.core.unreal.UActor.SkyZoneInfoActor;
import com.rost.cruma.core.unreal.UActor.StaticMeshActor;
import com.rost.cruma.core.unreal.UActor.WaterVolumeActor;
import com.rost.cruma.core.unreal.UActor.ZoneInfoActor;
import com.rost.cruma.core.unreal.UEmitter.UBeamEmitter;
import com.rost.cruma.core.unreal.UEmitter.UMeshEmitter;
import com.rost.cruma.core.unreal.UEmitter.USparkEmitter;
import com.rost.cruma.core.unreal.UEmitter.USpriteEmitter;
import com.rost.cruma.core.unreal.UEmitter.UTrailEmitter;
import com.rost.cruma.core.unreal.UEmitter.UVertMeshEmitter;
import com.rost.cruma.core.unreal.UMaterial.UColorModifier;
import com.rost.cruma.core.unreal.UMaterial.UCombiner;
import com.rost.cruma.core.unreal.UMaterial.UConstantColor;
import com.rost.cruma.core.unreal.UMaterial.UCubemap;
import com.rost.cruma.core.unreal.UMaterial.UFadeColor;
import com.rost.cruma.core.unreal.UMaterial.UFinalBlend;
import com.rost.cruma.core.unreal.UMaterial.UModifier;
import com.rost.cruma.core.unreal.UMaterial.UOpacityModifier;
import com.rost.cruma.core.unreal.UMaterial.UPalette;
import com.rost.cruma.core.unreal.UMaterial.UShader;
import com.rost.cruma.core.unreal.UMaterial.UTexCoordSource;
import com.rost.cruma.core.unreal.UMaterial.UTexEnvMap;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillator;
import com.rost.cruma.core.unreal.UMaterial.UTexOscillatorTriggered;
import com.rost.cruma.core.unreal.UMaterial.UTexPanner;
import com.rost.cruma.core.unreal.UMaterial.UTexPannerTriggered;
import com.rost.cruma.core.unreal.UMaterial.UTexRotator;
import com.rost.cruma.core.unreal.UMaterial.UTexScaler;
import com.rost.cruma.core.unreal.UMaterial.UTexture;
import com.rost.cruma.core.unreal.UMaterial.UVertexColor;
import com.rost.cruma.core.unreal.UTerrain.UTerrainInfoActor;
import com.rost.cruma.core.unreal.UTerrain.UTerrainSector;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UObjectLoader {
    private static final Set<String> REPORTED_UNSUPPORTED_TYPES = ConcurrentHashMap.newKeySet();
    private static final Set<String> REPORTED_UNREADABLE_TEXTURES = ConcurrentHashMap.newKeySet();

    private static final Logger log = LogManager.getLogger(UObjectLoader.class);
    private final UArchive archive;
    private final UArchiveLoader archiveLoader;
    private volatile ExportIndex exportIndex;
    private final Set<String> reportedMissingObjects = new HashSet<>();
    private final Map<Integer, List<UArchiveLoader.ClassLink>> emitterChains = new HashMap<>();

    public UObjectLoader(UArchive archive, UArchiveLoader archiveLoader) {
        this.archive = archive;
        this.archiveLoader = archiveLoader;
    }

    public @Nullable UObject loadObject(int index) {
        if (index == 0) {
            throw new IllegalArgumentException("Index 0 is not a valid object index");
        }
        if (index < 0) {
            if (-index > archive.getImportMap().size()) {
                throw new IllegalArgumentException("Index out of importMap bounds: " + index);
            }
            var importObject = archive.getImportMap().get(-index - 1);
            if (importObject.getPackageIndex() == 0) {
                throw new IllegalArgumentException(
                        "Invalid import object - package index can't be equal to zero: " + importObject);
            }

            if ("Class".equalsIgnoreCase(importObject.getClassName())) {
                return null;
            }

            var packageImport = importObject;
            var outerNames = new ArrayList<String>();
            var remaining = archive.getImportMap().size();
            do {
                var outerIndex = -(long) packageImport.getPackageIndex() - 1;
                if (outerIndex < 0 || outerIndex >= archive.getImportMap().size() || remaining-- == 0) {
                    throw new IllegalArgumentException(
                            "Invalid or cyclic import outer chain: " + importObject.getObjectName());
                }
                packageImport = archive.getImportMap().get((int) outerIndex);
                if (packageImport.getPackageIndex() != 0) {
                    outerNames.add(packageImport.getObjectName());
                }
            } while (packageImport.getPackageIndex() != 0);

            var archive = archiveLoader.loadArchive(packageImport.getObjectName());
            return archive == null ? null : archive.getObjectLoader().loadObject(importObject, outerNames);
        }

        if (index > archive.getExportMap().size()) {
            throw new IllegalArgumentException("Index out of exportMap bounds: " + index);
        }
        return exportObject(archive.getExportMap().get(index - 1));
    }

    public @Nullable String peekObjectFullName(int index) {
        if (index == 0) {
            return null;
        }
        if (index < 0) {
            if (-index > archive.getImportMap().size()) {
                return null;
            }
            var importObject = archive.getImportMap().get(-index - 1);
            if (importObject.getPackageIndex() == 0) {
                return null;
            }
            var packageImport = importObject;
            do {
                if (-packageImport.getPackageIndex() > archive.getImportMap().size()) {
                    return null;
                }
                packageImport = archive.getImportMap().get(-packageImport.getPackageIndex() - 1);
            } while (packageImport.getPackageIndex() != 0);
            return packageImport.getObjectName() + "." + importObject.getObjectName();
        }
        if (index > archive.getExportMap().size()) {
            return null;
        }
        return archive.getName() + "." + archive.getExportMap().get(index - 1).getObjectName();
    }

    public @Nullable String peekExportSourcePath(int index) {
        return index > 0 && index <= archive.getExportMap().size()
                ? exportSourcePath(archive.getExportMap().get(index - 1))
                : null;
    }

    public @Nullable String peekObjectClassName(int index) {
        if (index > 0 && index <= archive.getExportMap().size()) {
            return archive.getExportMap().get(index - 1).getClassName();
        }
        var imported = -(long) index - 1;
        if (index < 0 && imported < archive.getImportMap().size()) {
            return archive.getImportMap().get((int) imported).getClassName();
        }
        return null;
    }

    public UObject exportObject(UObjectExport objectExport) {
        if (objectExport.getObject() != null) {
            return objectExport.getObject();
        }

        UObject object = switch (objectExport.getClassName()) {
            case "Model" -> new UModel(archive);
            case "Polys" -> new UPolys(archive);
            case "ConvexVolume" -> new UConvexVolume(archive);
            case "Texture", "WetTexture" -> new UTexture(archive);
            case "TexModifier", "GlowModifier" -> new UModifier(archive);
            case "MaterialSequence",
                    "MaterialSwitch",
                    "UserDefinableMaterial",
                    "FireTexture",
                    "WaterTexture",
                    "WaveTexture",
                    "IceTexture",
                    "FractalTexture" -> new UMaterial(archive);
            case "TexCoordSource" -> new UTexCoordSource(archive);
            case "TexScaler" -> new UTexScaler(archive);
            case "TexEnvMap" -> new UTexEnvMap(archive);
            case "ColorModifier" -> new UColorModifier(archive);
            case "OpacityModifier" -> new UOpacityModifier(archive);
            case "TexPanner" -> new UTexPanner(archive);
            case "TexPannerTriggered" -> new UTexPannerTriggered(archive);
            case "TexRotator" -> new UTexRotator(archive);
            case "TexOscillator" -> new UTexOscillator(archive);
            case "TexOscillatorTriggered" -> new UTexOscillatorTriggered(archive);
            case "Combiner" -> new UCombiner(archive);
            case "FinalBlend" -> new UFinalBlend(archive);
            case "Shader" -> new UShader(archive);
            case "Cubemap" -> new UCubemap(archive);
            case "ConstantColor" -> new UConstantColor(archive);
            case "FadeColor" -> new UFadeColor(archive);
            case "VertexColor" -> new UVertexColor(archive);
            case "StaticMesh" -> new UStaticMesh(archive);
            case "VertMesh" -> new UVertMesh(archive);
            case "SkeletalMesh" -> new USkeletalMesh(archive);
            case "MeshAnimation" -> new UMeshAnimation(archive);
            case "StaticMeshInstance" -> new UStaticMeshInstance(archive);
            case "TerrainInfo" -> new UTerrainInfoActor(archive);
            case "TerrainSector" -> new UTerrainSector(archive);
            case "Level" -> new ULevel(archive);
            case "Actor" -> new UActor(archive);
            case "PlayerStart" -> new UActor(archive);
            case "AntiPortalActor" -> new AntiPortalActor(archive);
            case "Brush" -> new BrushActor(archive);
            case "BlockingVolume" -> new BlockingVolumeActor(archive);
            case "PhysicsVolume" -> new PhysicsVolumeActor(archive);
            case "WaterVolume" -> new WaterVolumeActor(archive);
            case "MusicVolume" -> new MusicVolumeActor(archive);
            case "AmbientVolume" -> new AmbientVolumeActor(archive);
            case "AmbientVolumeSound" -> new UAmbientVolumeSound(archive);
            case "AmbientSoundObject", "AmbientSound" -> new AmbientSoundActor(archive);
            case "AmbientSoundWObject" -> new AmbientSoundWObjectActor(archive);
            case "L2MovableStaticMeshActor" -> new UL2MovableStaticMeshActor(archive);
            case "Mover" -> new UMover(archive);
            case "LineagePawn" -> new ULineagePawn(archive);
            case "StaticMeshActor" -> new StaticMeshActor(archive);
            case "MovableStaticMeshActor" -> new UMovableStaticMeshActor(archive);
            case "SkyMeshActor" -> new StaticMeshActor(archive, false);
            case "Projector" -> new ProjectorActor(archive);
            case "Light" -> new LightActor(archive);
            case "Spotlight" -> new LightActor(archive, LightActor.LE_SPOTLIGHT);
            case "NMovableSunLight" -> new LightActor(archive, LightActor.LE_SUNLIGHT, true);
            case "ZoneInfo", "LevelInfo" -> new ZoneInfoActor(archive);
            case "L2FogInfo" -> new L2FogInfoActor(archive);
            case "L2SkyLevelZoneInfo" -> new L2SkyLevelZoneInfoActor(archive);
            case "SkyZoneInfo" -> new SkyZoneInfoActor(archive);
            case "NSun", "NMoon" -> new NCelestialActor(archive);
            case "Emitter" -> new EmitterActor(archive);
            case "SpriteEmitter" -> new USpriteEmitter(archive);
            case "SparkEmitter" -> new USparkEmitter(archive);
            case "MeshEmitter" -> new UMeshEmitter(archive);
            case "VertMeshEmitter" -> new UVertMeshEmitter(archive);
            case "TrailEmitter" -> new UTrailEmitter(archive);
            case "BeamEmitter" -> new UBeamEmitter(archive);
            case "Palette" -> new UPalette(archive);
            case "Sound" -> new USound(archive);
            default -> {
                var className = objectExport.getClassName();
                if (!findEmitterChain(objectExport).isEmpty()) {
                    yield new EmitterActor(archive);
                }
                if (REPORTED_UNSUPPORTED_TYPES.add(className)) {
                    log.warn("Unsupported object type {}; further occurrences are DEBUG-only", className);
                } else {
                    log.debug("Repeated unsupported object type {} in {}", className, archive.getName());
                }
                yield new UObject(archive);
            }
        };

        if (object instanceof EmitterActor emitter) {
            takeEmitterDefaults(objectExport, emitter);
        } else {
            takeClassDefaults(objectExport, object);
        }
        try {
            read(objectExport, object);
        } catch (RuntimeException failure) {
            if (!(object instanceof UTexture)) {
                throw failure;
            }
            object = unreadableTexture(objectExport, failure);
        }
        objectExport.setObject(object);
        return object;
    }

    public UActor exportActor(UObjectExport objectExport) {
        var actor = new UActor(archive);
        read(objectExport, actor);
        return actor;
    }

    public List<UProperty> exportProperties(UObjectExport objectExport) {
        var properties = new ArrayList<UProperty>();
        read(objectExport, new UObject(archive) {
            @Override
            public boolean setProperty(UProperty property) {
                properties.add(property);
                return true;
            }
        });
        return properties;
    }

    public boolean isEmitterExport(UObjectExport objectExport) {
        if (objectExport.getObject() != null) {
            return objectExport.getObject() instanceof EmitterActor;
        }
        return "Emitter".equals(objectExport.getClassName())
                || !findEmitterChain(objectExport).isEmpty();
    }

    public @Nullable UObject loadObjectOf(String packageName, int index) {
        var other = archiveLoader.loadArchive(packageName);
        return other == null || index == 0 ? null : other.getObjectLoader().loadObject(index);
    }

    public @Nullable String peekObjectFullNameOf(String packageName, int index) {
        var other = archiveLoader.loadArchive(packageName);
        return other == null ? null : other.getObjectLoader().peekObjectFullName(index);
    }

    private void takeClassDefaults(UObjectExport objectExport, UObject object) {
        var names = object.getClassDefaultNames();
        if (names.isEmpty()) {
            return;
        }
        var classPackage = findClassPackage(objectExport);
        if (classPackage == null) {
            return;
        }
        for (var property : archiveLoader.findClassDefaults(classPackage, objectExport.getClassName())) {
            if (names.contains(property.getName())) {
                object.setProperty(property);
            }
        }
    }

    private void takeEmitterDefaults(UObjectExport objectExport, EmitterActor emitter) {
        var chain = "Emitter".equals(objectExport.getClassName())
                ? List.of(new UArchiveLoader.ClassLink("Engine", "Emitter"))
                : findEmitterChain(objectExport);
        if (chain.isEmpty()) {
            return;
        }
        emitter.markPlaced();
        String lastPackage = null;
        for (var i = chain.size() - 1; i >= 0; i--) {
            var link = chain.get(i);
            var nearer = i == 0 ? null : chain.get(i - 1);
            if (nearer != null && nearer.packageName().equalsIgnoreCase(link.packageName())) {
                continue;
            }
            var defaults = archiveLoader.findClassDefaults(link.packageName(), link.className());
            if (lastPackage == null) {
                emitter.takeEngineLifetime(defaults);
            } else {
                emitter.takeClassDefaults(defaults, link.packageName());
            }
            lastPackage = link.packageName();
        }
    }

    private List<UArchiveLoader.ClassLink> findEmitterChain(UObjectExport objectExport) {
        var index = objectExport.getClassIndex();
        if (index >= 0) {
            return List.of();
        }
        var known = emitterChains.get(index);
        if (known == null) {
            var classPackage = findClassPackage(objectExport);
            known = classPackage == null
                    ? List.of()
                    : archiveLoader.findEmitterChain(classPackage, objectExport.getClassName());
            emitterChains.put(index, known);
        }
        return known;
    }

    private @Nullable String findClassPackage(UObjectExport objectExport) {
        var imports = archive.getImportMap();
        var index = objectExport.getClassIndex();
        if (index >= 0 || -(long) index > imports.size()) {
            return null;
        }
        var outer = imports.get(-index - 1).getPackageIndex();
        for (var steps = 0; outer < 0 && -(long) outer <= imports.size() && steps < imports.size(); steps++) {
            var parent = imports.get(-outer - 1);
            if (parent.getPackageIndex() == 0) {
                return parent.getObjectName();
            }
            outer = parent.getPackageIndex();
        }
        return null;
    }

    private @Nullable UObject loadObject(UObjectImport objectImport, List<String> outerNames) {
        var objectName = objectImport.getObjectName();
        var nameKey = objectName.toLowerCase(Locale.ROOT);
        var lookup = exportLookup();
        var candidates = lookup.duplicatesByName().get(nameKey);
        UObjectExport rootFallback = null;
        if (candidates != null) {
            for (var objectExport : candidates) {
                if (objectExport.getClassName().equalsIgnoreCase(objectImport.getClassName())
                        && !objectExport.getClassName().equals("Package")) {
                    if (matchesOuter(objectExport, outerNames)) {
                        return exportObject(objectExport);
                    }
                    if (objectExport.getPackageIndex() == 0 && rootFallback == null) {
                        rootFallback = objectExport;
                    }
                }
            }
        } else {
            var objectExport = lookup.firstByName().get(nameKey);
            if (objectExport != null
                    && objectExport.getClassName().equalsIgnoreCase(objectImport.getClassName())
                    && !objectExport.getClassName().equals("Package")
                    && (matchesOuter(objectExport, outerNames) || objectExport.getPackageIndex() == 0)) {
                return exportObject(objectExport);
            }
        }
        if (rootFallback != null) {
            return exportObject(rootFallback);
        }
        if (reportedMissingObjects.add(objectName)) {
            log.warn(
                    "Object not found: {} " + "(identical misses in {} will be DEBUG-only)",
                    objectName,
                    archive.getName());
        } else {
            log.debug("Repeated missing object in {}: {}", archive.getName(), objectName);
        }
        return null;
    }

    private boolean matchesOuter(UObjectExport objectExport, List<String> outerNames) {
        var outer = objectExport.getPackageIndex();
        for (var name : outerNames) {
            if (outer <= 0 || outer > archive.getExportMap().size()) {
                return false;
            }
            var parent = archive.getExportMap().get(outer - 1);
            if (!parent.getObjectName().equalsIgnoreCase(name)) {
                return false;
            }
            outer = parent.getPackageIndex();
        }
        return outer == 0;
    }

    private ExportIndex exportLookup() {
        var lookup = exportIndex;
        if (lookup != null) {
            return lookup;
        }
        synchronized (this) {
            lookup = exportIndex;
            if (lookup == null) {
                var firstByName = new HashMap<String, UObjectExport>();
                var duplicatesByName = new HashMap<String, List<UObjectExport>>();
                for (var objectExport : archive.getExportMap()) {
                    var objectName = objectExport.getObjectName().toLowerCase(Locale.ROOT);
                    var first = firstByName.putIfAbsent(objectName, objectExport);
                    if (first != null) {
                        duplicatesByName
                                .computeIfAbsent(objectName, ignored -> {
                                    var duplicates = new ArrayList<UObjectExport>(2);
                                    duplicates.add(first);
                                    return duplicates;
                                })
                                .add(objectExport);
                    }
                }
                lookup = new ExportIndex(firstByName, duplicatesByName);
                exportIndex = lookup;
            }
        }
        return lookup;
    }

    private record ExportIndex(
            Map<String, UObjectExport> firstByName, Map<String, List<UObjectExport>> duplicatesByName) {}

    private UTexture unreadableTexture(UObjectExport objectExport, RuntimeException failure) {
        var texture = new UTexture(archive);
        identify(objectExport, texture);
        texture.markUnreadable();
        var path = texture.getSourcePath();
        if (REPORTED_UNREADABLE_TEXTURES.add(path)) {
            log.warn(
                    "Cannot read texture {} (bytes {} to {} of the package): {}; it draws with the default material"
                            + " (identical failures are DEBUG-only)",
                    path,
                    objectExport.getSerialOffset(),
                    objectExport.getSerialOffset() + objectExport.getSerialSize(),
                    failure.toString());
        } else {
            log.debug("Repeated unreadable texture {}: {}", path, failure.toString());
        }
        return texture;
    }

    private void read(UObjectExport objectExport, UObject object) {
        identify(objectExport, object);

        var outerObjectEnd = archive.getObjectEnd();
        if (objectExport.getSerialSize() > 0) {
            archive.setPosition(objectExport.getSerialOffset());
            archive.setObjectEnd(objectExport.getSerialOffset() + objectExport.getSerialSize());
        } else {
            archive.setObjectEnd(-1);
        }
        try {
            object.deserialize();
        } finally {
            archive.setObjectEnd(outerObjectEnd);
        }
    }

    private void identify(UObjectExport objectExport, UObject object) {
        object.setName(objectExport.getObjectName());
        object.setSourceIdentity(
                (archive.getName() + ':' + objectExport.getPackageIndex() + ':' + objectExport.getObjectName())
                        .toLowerCase(Locale.ROOT));
        object.setSourcePath(exportSourcePath(objectExport));
        object.setFlags(objectExport.getObjectFlags());
    }

    private String exportSourcePath(UObjectExport exported) {
        var names = new ArrayList<String>();
        names.add(exported.getObjectName());
        var outer = exported.getPackageIndex();
        var remaining = archive.getExportMap().size();
        while (outer > 0 && outer <= archive.getExportMap().size() && remaining-- > 0) {
            var parent = archive.getExportMap().get(outer - 1);
            names.add(parent.getObjectName());
            outer = parent.getPackageIndex();
        }
        if (outer != 0) {
            return null;
        }
        Collections.reverse(names);
        return archive.getName() + "." + String.join(".", names);
    }
}
