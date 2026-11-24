package com.rost.cruma.core.world;

import com.rost.cruma.core.unreal.UArchive;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class L2Attach {
    private L2Attach() {}

    public record Carrier(String path, List<String> movementTags) {}

    public record Candidate(String path, String moveEvent, boolean isStatic, boolean movable) {}

    public static List<String> getAttachTags(List<String> saved) {
        var tags = new ArrayList<String>();
        for (var tag : saved) {
            if (tags.size() == L2Swing.MOST_TAGS || UArchive.NONE_NAME.equalsIgnoreCase(tag)) {
                break;
            }
            tags.add(tag);
        }
        return tags;
    }

    public static Map<String, List<Candidate>> attach(List<Carrier> carriers, List<Candidate> candidates) {
        var byEvent = new HashMap<String, List<Candidate>>();
        for (var candidate : candidates) {
            if (!UArchive.NONE_NAME.equalsIgnoreCase(candidate.moveEvent())) {
                byEvent.computeIfAbsent(candidate.moveEvent().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                        .add(candidate);
            }
        }
        var taken = new HashSet<String>();
        var result = new LinkedHashMap<String, List<Candidate>>();
        for (var carrier : carriers) {
            for (var tag : getAttachTags(carrier.movementTags())) {
                for (var candidate : byEvent.getOrDefault(tag.toLowerCase(Locale.ROOT), List.of())) {
                    if (candidate.path().equals(carrier.path())
                            || candidate.isStatic()
                            || !candidate.movable()
                            || taken.contains(candidate.path())) {
                        continue;
                    }
                    taken.add(candidate.path());
                    result.computeIfAbsent(carrier.path(), ignored -> new ArrayList<>())
                            .add(candidate);
                }
            }
        }
        return result;
    }
}
