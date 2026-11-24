package com.rost.cruma.core.unreal;

public class USound extends UObject {
    private byte[] data = new byte[0];

    public USound(UArchive archive) {
        super(archive);
    }

    @Override
    protected void deserialize() {
        super.deserialize();
        archive.readIndex();
        archive.readInt();
        data = archive.readByteArray();
    }

    public byte[] getData() {
        return this.data;
    }
}
