package com.rost.cruma.core.unreal;

import com.rost.cruma.core.unreal.UArchive.ObjectWithOuter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

public class UPackage {
    private final UArchive archive;
    private final Map<String, List<?>> loadedByClass = new HashMap<>();
    private @Nullable List<?> emitterActors;

    public UPackage(UArchive archive) {
        this.archive = archive;
    }

    public String getName() {
        return archive.getName();
    }

    public UArchive getArchive() {
        return archive;
    }

    @SuppressWarnings("unchecked")
    public <T> void loadObjects(String className, List<T> objects) {
        var loaded = (List<T>) loadedByClass.get(className);
        if (loaded == null) {
            loaded = new ArrayList<>();
            archive.loadObjects(className, loaded);
            loadedByClass.put(className, loaded);
        }
        objects.addAll(loaded);
    }

    @SuppressWarnings("unchecked")
    public <T> void loadEmitterActors(List<T> objects) {
        if (emitterActors == null) {
            var loaded = new ArrayList<>();
            archive.loadEmitterActors(loaded);
            emitterActors = loaded;
        }
        objects.addAll((List<T>) emitterActors);
    }

    public <T> void loadObjectsUnder(String className, String outerName, List<T> objects) {
        archive.loadObjectsUnder(className, outerName, objects);
    }

    public <T> void loadObjectsWithOuter(String className, List<ObjectWithOuter<T>> objects) {
        archive.loadObjectsWithOuter(className, objects);
    }

    public @Nullable UObject findObject(@Nullable String className, String objectName) {
        for (var objectExport : archive.getExportMap()) {
            if (!objectExport.getObjectName().equalsIgnoreCase(objectName)
                    || objectExport.getClassName().equals("Package")
                    || className != null && !objectExport.getClassName().equals(className)) {
                continue;
            }
            return archive.getObjectLoader().exportObject(objectExport);
        }
        return null;
    }

    public @Nullable UObject loadObject(int index) {
        return index == 0 ? null : archive.getObjectLoader().loadObject(index);
    }
}
