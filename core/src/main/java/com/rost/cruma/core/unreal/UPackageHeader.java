package com.rost.cruma.core.unreal;

import java.util.List;

public class UPackageHeader {
    public static final int PACKAGE_MAGIC = 0x9E2A83C1;

    private final int magic;
    private final short fileVersion;
    private final short licenseVersion;
    private final long flags;
    private final int nameCount;
    private final int nameOffset;
    private final int exportCount;
    private final int exportOffset;
    private final int importCount;
    private final int importOffset;
    private final UGuid guid;
    private final List<UGenerationInfo> generations;

    UPackageHeader(
            int magic,
            short fileVersion,
            short licenseVersion,
            long flags,
            int nameCount,
            int nameOffset,
            int exportCount,
            int exportOffset,
            int importCount,
            int importOffset,
            UGuid guid,
            List<UGenerationInfo> generations) {
        this.magic = magic;
        this.fileVersion = fileVersion;
        this.licenseVersion = licenseVersion;
        this.flags = flags;
        this.nameCount = nameCount;
        this.nameOffset = nameOffset;
        this.exportCount = exportCount;
        this.exportOffset = exportOffset;
        this.importCount = importCount;
        this.importOffset = importOffset;
        this.guid = guid;
        this.generations = generations;
    }

    public static UPackageHeaderBuilder builder() {
        return new UPackageHeaderBuilder();
    }

    public static final class UPackageHeaderBuilder {
        private int magic;
        private short fileVersion;
        private short licenseVersion;
        private long flags;
        private int nameCount;
        private int nameOffset;
        private int exportCount;
        private int exportOffset;
        private int importCount;
        private int importOffset;
        private UGuid guid;
        private List<UGenerationInfo> generations;

        private UPackageHeaderBuilder() {}

        public UPackageHeaderBuilder magic(int magic) {
            this.magic = magic;
            return this;
        }

        public UPackageHeaderBuilder fileVersion(short fileVersion) {
            this.fileVersion = fileVersion;
            return this;
        }

        public UPackageHeaderBuilder licenseVersion(short licenseVersion) {
            this.licenseVersion = licenseVersion;
            return this;
        }

        public UPackageHeaderBuilder flags(long flags) {
            this.flags = flags;
            return this;
        }

        public UPackageHeaderBuilder nameCount(int nameCount) {
            this.nameCount = nameCount;
            return this;
        }

        public UPackageHeaderBuilder nameOffset(int nameOffset) {
            this.nameOffset = nameOffset;
            return this;
        }

        public UPackageHeaderBuilder exportCount(int exportCount) {
            this.exportCount = exportCount;
            return this;
        }

        public UPackageHeaderBuilder exportOffset(int exportOffset) {
            this.exportOffset = exportOffset;
            return this;
        }

        public UPackageHeaderBuilder importCount(int importCount) {
            this.importCount = importCount;
            return this;
        }

        public UPackageHeaderBuilder importOffset(int importOffset) {
            this.importOffset = importOffset;
            return this;
        }

        public UPackageHeaderBuilder guid(UGuid guid) {
            this.guid = guid;
            return this;
        }

        public UPackageHeaderBuilder generations(List<UGenerationInfo> generations) {
            this.generations = generations;
            return this;
        }

        public UPackageHeader build() {
            return new UPackageHeader(
                    magic,
                    fileVersion,
                    licenseVersion,
                    flags,
                    nameCount,
                    nameOffset,
                    exportCount,
                    exportOffset,
                    importCount,
                    importOffset,
                    guid,
                    generations);
        }
    }

    public int getMagic() {
        return this.magic;
    }

    public short getFileVersion() {
        return this.fileVersion;
    }

    public short getLicenseVersion() {
        return this.licenseVersion;
    }

    public long getFlags() {
        return this.flags;
    }

    public int getNameCount() {
        return this.nameCount;
    }

    public int getNameOffset() {
        return this.nameOffset;
    }

    public int getExportCount() {
        return this.exportCount;
    }

    public int getExportOffset() {
        return this.exportOffset;
    }

    public int getImportCount() {
        return this.importCount;
    }

    public int getImportOffset() {
        return this.importOffset;
    }

    public UGuid getGuid() {
        return this.guid;
    }

    public List<UGenerationInfo> getGenerations() {
        return this.generations;
    }
}
