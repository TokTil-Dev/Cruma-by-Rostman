package com.rost.cruma.viewer.agent.edit;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Refusals {
    private static final int EXAMPLES = 3;

    private final Map<String, List<String>> byReason = new LinkedHashMap<>();

    void add(String reason, String actor) {
        byReason.computeIfAbsent(reason, _ -> new ArrayList<>()).add(actor);
    }

    int getCount() {
        var count = 0;
        for (var actors : byReason.values()) {
            count += actors.size();
        }
        return count;
    }

    JSONArray write() {
        var entries = new ArrayList<>(byReason.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        var result = new JSONArray();
        for (var entry : entries) {
            var actors = entry.getValue();
            var item = new JSONObject();
            item.put("reason", entry.getKey());
            item.put("count", actors.size());
            item.put("examples", actors.subList(0, Math.min(EXAMPLES, actors.size())));
            result.add(item);
        }
        return result;
    }
}
