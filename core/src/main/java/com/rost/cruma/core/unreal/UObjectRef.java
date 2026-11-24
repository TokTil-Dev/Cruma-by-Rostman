package com.rost.cruma.core.unreal;

import org.jetbrains.annotations.Nullable;

public class UObjectRef<T, R extends UObjectRef.ObjectRefRequirement> {
    private final boolean required;
    private int index;
    private UObjectLoader loader;
    private T object;

    private UObjectRef(boolean required) {
        this.required = required;
    }

    public sealed interface ObjectRefRequirement permits UObjectRef.Required, UObjectRef.Optional {}

    public static final class Required implements ObjectRefRequirement {}

    public static final class Optional implements ObjectRefRequirement {}

    public static <T> UObjectRef<T, Required> required() {
        return new UObjectRef<>(true);
    }

    public static <T> UObjectRef<T, Optional> optional() {
        return new UObjectRef<>(false);
    }

    public void fromProperty(UArchive archive, UProperty property) {
        if (loader != null) {
            throw new IllegalStateException("Object loader must not be initialized");
        }
        index = property.getIndexValue();
        if (index == Integer.MIN_VALUE) {
            throw new IllegalStateException("Index is required");
        }
        loader = archive.getObjectLoader();
    }

    public void fromArchive(UArchive archive) {
        if (loader != null) {
            throw new IllegalStateException("Object loader must not be initialized");
        }
        index = archive.readIndex();
        loader = archive.getObjectLoader();
    }

    public @Nullable T get() {
        return loadObject();
    }

    public int getIndex() {
        return index;
    }

    public @Nullable String peekFullName() {
        if (index == 0 || loader == null) {
            return null;
        }
        return loader.peekObjectFullName(index);
    }

    public @Nullable String peekExportSourcePath() {
        return loader == null ? null : loader.peekExportSourcePath(index);
    }

    public @Nullable String peekClassName() {
        return loader == null ? null : loader.peekObjectClassName(index);
    }

    public boolean isNull() {
        return index == 0;
    }

    @SuppressWarnings("unchecked")
    private T loadObject() {
        if (!required && index == 0) {
            return null;
        }
        if (object != null) {
            return object;
        }
        if (loader == null) {
            throw new IllegalStateException("Object loader must be initialized");
        }
        if (index == 0) {
            throw new IllegalStateException("Index can't be equal to zero");
        }
        object = (T) loader.loadObject(index);
        if (object == null && required) {
            throw new UMissingObjectException("Required object could not be resolved (index=" + index + ")");
        }
        return object;
    }
}
