package com.rost.cruma.core.unreal;

import java.util.ArrayList;
import java.util.List;

public final class ULineagePawn extends UActor {
    private boolean clientSide;
    private String savedMesh = UArchive.NONE_NAME;
    private final List<String> animations = new ArrayList<>();
    private String oldAnimation = UArchive.NONE_NAME;
    private boolean random;
    private String interval = UArchive.NONE_NAME;
    private String first = UArchive.NONE_NAME;
    private boolean actorShadows = true;

    public ULineagePawn(UArchive archive) {
        super(archive);
    }

    public static List<Integer> decodeNameIndices(short[] data) {
        var indices = new ArrayList<Integer>();
        var pos = 0;
        while (pos < data.length) {
            var b = data[pos++] & 0xFF;
            var negative = (b & 0x80) != 0;
            var value = b & 0x3F;
            var more = (b & 0x40) != 0;
            var shift = 6;
            while (more && pos < data.length) {
                b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
                more = (b & 0x80) != 0;
            }
            indices.add(negative ? -value : value);
        }
        return indices;
    }

    @Override
    public boolean setProperty(UProperty property) {
        if (super.setProperty(property)) {
            return true;
        }
        return switch (property.getName()) {
            case "IsClientSidePawn" -> {
                clientSide = property.isTrue();
                yield true;
            }
            case "SavedMeshName" -> {
                savedMesh = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "ClientSidePawnAnimList" -> {
                animations.clear();
                var data = property.getDataValue();
                if (data != null) {
                    for (var index : decodeNameIndices(data)) {
                        animations.add(archive.resolveName(index));
                    }
                }
                yield true;
            }
            case "ClientSidePawnAnim" -> {
                oldAnimation = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "bPlayingRandomly" -> {
                random = property.isTrue();
                yield true;
            }
            case "IntervalAnim" -> {
                interval = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "ForcedPlayAnimFirst" -> {
                first = archive.resolveName(property.getIndexValue());
                yield true;
            }
            case "bActorShadows" -> {
                actorShadows = property.isTrue();
                yield true;
            }
            default -> false;
        };
    }

    public boolean isClientSide() {
        return clientSide;
    }

    public String getSavedMesh() {
        return savedMesh;
    }

    public List<String> getAnimations() {
        var all = new ArrayList<>(animations);
        if (!oldAnimation.equalsIgnoreCase(UArchive.NONE_NAME) && !oldAnimation.isBlank()) {
            all.add(oldAnimation);
        }
        return List.copyOf(all);
    }

    public boolean isRandom() {
        return random;
    }

    public String getInterval() {
        return interval;
    }

    public String getFirst() {
        return first;
    }

    public boolean hasActorShadows() {
        return actorShadows;
    }

    public List<String> getSkinNames() {
        var refs = getSkinRefs();
        if (refs == null) {
            return List.of();
        }
        var names = new ArrayList<String>(refs.length);
        for (var ref : refs) {
            var name = ref == 0 ? null : archive.getObjectLoader().peekObjectFullName(ref);
            names.add(name == null ? "" : name);
        }
        return List.copyOf(names);
    }
}
