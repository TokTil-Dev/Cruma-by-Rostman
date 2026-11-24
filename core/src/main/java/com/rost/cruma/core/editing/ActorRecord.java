package com.rost.cruma.core.editing;

import java.util.List;

public record ActorRecord(
        String sourcePath,
        int exportIndex,
        String className,
        ActorTransform transform,
        List<Integer> skinReferences,
        boolean editable,
        String restriction,
        String meshSourcePath,
        String templateSourcePath,
        boolean created,
        boolean pawn,
        boolean emitter) {
    public ActorRecord {
        skinReferences = List.copyOf(skinReferences);
    }

    public ActorRecord(
            String sourcePath,
            int exportIndex,
            String className,
            ActorTransform transform,
            List<Integer> skinReferences,
            boolean editable,
            String restriction,
            String meshSourcePath,
            String templateSourcePath,
            boolean created,
            boolean pawn) {
        this(
                sourcePath,
                exportIndex,
                className,
                transform,
                skinReferences,
                editable,
                restriction,
                meshSourcePath,
                templateSourcePath,
                created,
                pawn,
                false);
    }

    public ActorRecord(
            String sourcePath,
            int exportIndex,
            String className,
            ActorTransform transform,
            List<Integer> skinReferences,
            boolean editable,
            String restriction,
            String meshSourcePath,
            String templateSourcePath,
            boolean created) {
        this(
                sourcePath,
                exportIndex,
                className,
                transform,
                skinReferences,
                editable,
                restriction,
                meshSourcePath,
                templateSourcePath,
                created,
                PackageEditDocument.PAWN.equals(className));
    }
}
