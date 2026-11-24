package com.rost.cruma.core.unreal;

import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public class UObject {
    private static final Logger log = LogManager.getLogger(UObject.class);

    protected final UArchive archive;
    private @Nullable String name;
    private @Nullable String sourceIdentity;
    private @Nullable String sourcePath;
    private long flags;

    private @Nullable StateFrame stateFrame;

    public UObject(UArchive archive) {
        this.archive = archive;
    }

    public record StateFrame(int node, int stateNode, long probeMask, int latentAction, int offset) {
        public static StateFrame deserialize(UArchive archive) {
            var node = archive.readIndex();
            var stateNode = archive.readIndex();
            var probeMask = archive.readUInt64();
            var latentAction = archive.readInt();
            var offset = node != 0 ? archive.readIndex() : 0;
            return new StateFrame(node, stateNode, probeMask, latentAction, offset);
        }
    }

    public enum ObjectFlags {
        RF_Transactional(0x00000001),
        RF_Unreachable(0x00000002),
        RF_Public(0x00000004),
        RF_TagImp(0x00000008),
        RF_TagExp(0x00000010),
        RF_SourceModified(0x00000020),
        RF_TagGarbage(0x00000040),
        RF_Final(0x00000080),
        RF_PerObjectLocalized(0x00000100),
        RF_NeedLoad(0x00000200),
        RF_HighlightedName(0x00000400),
        RF_EliminateObject(0x00000400),
        RF_InSingularFunc(0x00000800),
        RF_RemappedName(0x00000800),
        RF_Suppress(0x00001000),
        RF_StateChanged(0x00001000),
        RF_InEndState(0x00002000),
        RF_Transient(0x00004000),
        RF_Preloading(0x00008000),
        RF_LoadForClient(0x00010000),
        RF_LoadForServer(0x00020000),
        RF_LoadForEdit(0x00040000),
        RF_Standalone(0x00080000),
        RF_NotForClient(0x00100000),
        RF_NotForServer(0x00200000),
        RF_NotForEdit(0x00400000),
        RF_Destroyed(0x00800000),
        RF_NeedPostLoad(0x01000000),
        RF_HasStack(0x02000000),
        RF_Native(0x04000000),
        RF_Marked(0x08000000),
        RF_ErrorShutdown(0x10000000),
        RF_DebugPostLoad(0x20000000),
        RF_DebugSerialize(0x40000000),
        RF_DebugDestroy(0x80000000),
        RF_ContextFlags(RF_NotForClient.id | RF_NotForServer.id | RF_NotForEdit.id),
        RF_LoadContextFlags(RF_LoadForClient.id | RF_LoadForServer.id | RF_LoadForEdit.id),
        RF_Load(RF_ContextFlags.id
                | RF_LoadContextFlags.id
                | RF_Public.id
                | RF_Final.id
                | RF_Standalone.id
                | RF_Native.id
                | RF_SourceModified.id
                | RF_Transactional.id
                | RF_HasStack.id
                | RF_PerObjectLocalized.id),
        RF_Keep(RF_Native.id | RF_Marked.id | RF_PerObjectLocalized.id),
        RF_ScriptMask(RF_Transactional.id
                | RF_Public.id
                | RF_Final.id
                | RF_Transient.id
                | RF_NotForClient.id
                | RF_NotForServer.id
                | RF_NotForEdit.id
                | RF_Standalone.id);
        private final long id;

        ObjectFlags(long id) {
            this.id = id;
        }

        public static ObjectFlags fromId(long id) {
            for (var value : values()) {
                if (value.id == id) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown object flag: " + id);
        }

        public long getId() {
            return this.id;
        }
    }

    protected void deserialize() {
        if ((flags & ObjectFlags.RF_HasStack.id) != 0) {
            stateFrame = StateFrame.deserialize(archive);
        }

        var properties = archive.getPropertyExtractor().extractProperties();
        for (var property : properties) {
            if (!setProperty(property)) {
                log.debug("Unconsumed property: {}", property);
            }
        }
    }

    public boolean setProperty(UProperty property) {
        return false;
    }

    public Set<String> getClassDefaultNames() {
        return Set.of();
    }

    public String getFullName() {
        return archive.getName() + "." + name;
    }

    public @Nullable String getSourceIdentity() {
        return sourceIdentity;
    }

    public void setSourceIdentity(String sourceIdentity) {
        this.sourceIdentity = sourceIdentity;
    }

    public @Nullable String getSourcePath() {
        return sourcePath != null ? sourcePath : archive == null ? name : getFullName();
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getFlags() {
        return this.flags;
    }

    public void setFlags(long flags) {
        this.flags = flags;
    }

    public StateFrame getStateFrame() {
        return this.stateFrame;
    }
}
