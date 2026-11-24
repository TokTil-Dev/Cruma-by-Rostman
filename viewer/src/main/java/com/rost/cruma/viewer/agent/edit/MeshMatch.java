package com.rost.cruma.viewer.agent.edit;

import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.editing.MeshLibrary.MeshEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MeshMatch {
    private static final int NAMES_SHOWN = 8;

    private MeshMatch() {}

    static MeshEntry find(List<MeshEntry> meshes, String text) throws ToolProblem {
        var byName = new ArrayList<MeshEntry>();
        var byWords = new ArrayList<MeshEntry>();
        var wanted = text.toLowerCase(Locale.ROOT);
        for (var mesh : meshes) {
            if (mesh.path().equalsIgnoreCase(text)) {
                return mesh;
            }
            if (mesh.name().equalsIgnoreCase(text)) {
                byName.add(mesh);
            } else if (mesh.path().toLowerCase(Locale.ROOT).contains(wanted)) {
                byWords.add(mesh);
            }
        }
        var found = byName.isEmpty() ? byWords : byName;
        if (found.size() == 1) {
            return found.getFirst();
        }
        if (found.isEmpty()) {
            throw new ToolProblem("The client has no mesh named " + text + ". Call assets_find to search.");
        }
        var names = new ArrayList<String>();
        for (var mesh : found.subList(0, Math.min(NAMES_SHOWN, found.size()))) {
            names.add(mesh.path());
        }
        throw new ToolProblem(found.size()
                + " meshes fit "
                + text
                + ", such as "
                + String.join(", ", names)
                + ". Name one by its path.");
    }
}
