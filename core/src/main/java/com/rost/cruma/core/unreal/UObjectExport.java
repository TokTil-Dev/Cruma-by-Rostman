package com.rost.cruma.core.unreal;

public class UObjectExport {
    private final int classIndex;
    private final String className;
    private final String superName;
    private final int packageIndex;
    private final String objectName;
    private final long objectFlags;
    private final int serialSize;
    private final int serialOffset;
    private UObject object;

    UObjectExport(
            int classIndex,
            String className,
            String superName,
            int packageIndex,
            String objectName,
            long objectFlags,
            int serialSize,
            int serialOffset,
            UObject object) {
        this.classIndex = classIndex;
        this.className = className;
        this.superName = superName;
        this.packageIndex = packageIndex;
        this.objectName = objectName;
        this.objectFlags = objectFlags;
        this.serialSize = serialSize;
        this.serialOffset = serialOffset;
        this.object = object;
    }

    public static UObjectExportBuilder builder() {
        return new UObjectExportBuilder();
    }

    public static final class UObjectExportBuilder {
        private int classIndex;
        private String className;
        private String superName;
        private int packageIndex;
        private String objectName;
        private long objectFlags;
        private int serialSize;
        private int serialOffset;
        private UObject object;
        private boolean objectSet;

        private UObjectExportBuilder() {}

        public UObjectExportBuilder classIndex(int classIndex) {
            this.classIndex = classIndex;
            return this;
        }

        public UObjectExportBuilder className(String className) {
            this.className = className;
            return this;
        }

        public UObjectExportBuilder superName(String superName) {
            this.superName = superName;
            return this;
        }

        public UObjectExportBuilder packageIndex(int packageIndex) {
            this.packageIndex = packageIndex;
            return this;
        }

        public UObjectExportBuilder objectName(String objectName) {
            this.objectName = objectName;
            return this;
        }

        public UObjectExportBuilder objectFlags(long objectFlags) {
            this.objectFlags = objectFlags;
            return this;
        }

        public UObjectExportBuilder serialSize(int serialSize) {
            this.serialSize = serialSize;
            return this;
        }

        public UObjectExportBuilder serialOffset(int serialOffset) {
            this.serialOffset = serialOffset;
            return this;
        }

        public UObjectExportBuilder object(UObject object) {
            this.object = object;
            this.objectSet = true;
            return this;
        }

        public UObjectExport build() {
            return new UObjectExport(
                    classIndex,
                    className,
                    superName,
                    packageIndex,
                    objectName,
                    objectFlags,
                    serialSize,
                    serialOffset,
                    objectSet ? object : null);
        }
    }

    public int getClassIndex() {
        return this.classIndex;
    }

    public String getClassName() {
        return this.className;
    }

    public String getSuperName() {
        return this.superName;
    }

    public int getPackageIndex() {
        return this.packageIndex;
    }

    public String getObjectName() {
        return this.objectName;
    }

    public long getObjectFlags() {
        return this.objectFlags;
    }

    public int getSerialSize() {
        return this.serialSize;
    }

    public int getSerialOffset() {
        return this.serialOffset;
    }

    public UObject getObject() {
        return this.object;
    }

    public void setObject(UObject object) {
        this.object = object;
    }
}
