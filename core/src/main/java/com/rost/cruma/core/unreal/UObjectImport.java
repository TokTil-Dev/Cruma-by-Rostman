package com.rost.cruma.core.unreal;

public class UObjectImport {
    private final String classPackage;
    private final String className;
    private final int packageIndex;
    private final String objectName;

    UObjectImport(String classPackage, String className, int packageIndex, String objectName) {
        this.classPackage = classPackage;
        this.className = className;
        this.packageIndex = packageIndex;
        this.objectName = objectName;
    }

    public static UObjectImportBuilder builder() {
        return new UObjectImportBuilder();
    }

    public static final class UObjectImportBuilder {
        private String classPackage;
        private String className;
        private int packageIndex;
        private String objectName;

        private UObjectImportBuilder() {}

        public UObjectImportBuilder classPackage(String classPackage) {
            this.classPackage = classPackage;
            return this;
        }

        public UObjectImportBuilder className(String className) {
            this.className = className;
            return this;
        }

        public UObjectImportBuilder packageIndex(int packageIndex) {
            this.packageIndex = packageIndex;
            return this;
        }

        public UObjectImportBuilder objectName(String objectName) {
            this.objectName = objectName;
            return this;
        }

        public UObjectImport build() {
            return new UObjectImport(classPackage, className, packageIndex, objectName);
        }
    }

    public String getClassPackage() {
        return this.classPackage;
    }

    public String getClassName() {
        return this.className;
    }

    public int getPackageIndex() {
        return this.packageIndex;
    }

    public String getObjectName() {
        return this.objectName;
    }

    @Override
    public String toString() {
        return "UObjectImport(classPackage="
                + this.classPackage
                + ", className="
                + this.className
                + ", packageIndex="
                + this.packageIndex
                + ", objectName="
                + this.objectName
                + ")";
    }
}
