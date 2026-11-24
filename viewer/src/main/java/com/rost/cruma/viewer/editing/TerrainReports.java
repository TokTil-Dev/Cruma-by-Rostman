package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainDecorationExport;
import com.rost.cruma.core.editing.TerrainEditExport.Report;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TerrainReports {
    static final String BEAST_LINE = "Beast lightmaps approximated in ";

    private TerrainReports() {}

    static Map<String, Object> manifestEntry(Report report) {
        return manifestEntry(report, 0);
    }

    static Map<String, Object> manifestEntry(Report report, int actorShadowVertices) {
        var lighting = report.lighting();
        var result = new LinkedHashMap<String, Object>();
        result.put("owner", report.owner());
        result.put("changedSamples", lighting.changedSamples());
        result.put("changedVertices", lighting.changedVertices());
        result.put("rebuiltSectors", lighting.affectedSectors());
        result.put("movedSectorBounds", lighting.movedBounds());
        result.put("steadyLightsAdded", lighting.lightsAdded());
        result.put("steadyLightsRemoved", lighting.lightsRemoved());
        result.put("steadyBitsChanged", lighting.steadyBitsChanged());
        result.put("vertexColorsChanged", lighting.colorsChanged());
        result.put("timeSamplesChanged", lighting.timeSamplesChanged());
        result.put("actorShadowVertices", actorShadowVertices);
        result.put("tracedRays", lighting.rays());
        result.put("heightPackage", report.heightPackage());
        result.put("heightSharedWith", report.heightSharedWith());
        result.put("bspSurfacesNearEdit", report.bspSurfaces());
        result.put(
                "paintedLayers",
                report.paintedLayers().stream()
                        .map(layer -> {
                            var entry = new LinkedHashMap<String, Object>();
                            entry.put("layer", layer.layer());
                            entry.put("weightTexture", layer.weightTexture());
                            entry.put("changedWeights", layer.changedTexels());
                            return entry;
                        })
                        .toList());
        result.put("layerPassSectors", report.layerPassSectors());
        result.put("mappedLayers", report.mappedLayers());
        result.put(
                "staleStaticMeshActors",
                report.meshActors().stream().map(actor -> actor.path()).toList());
        var beast = report.beast();
        if (beast != null) {
            result.put("beastSectors", beast.sectors());
            result.put("beastTexelsChanged", beast.changedTexels());
            result.put("beastCalibrationSamples", beast.samples());
            result.put("beastSunMeanDifference", beast.slot0().meanAbsolute());
            result.put("beastLocalMeanDifference", beast.slot1().meanAbsolute());
            result.put("beastKeptFlat", beast.keptFlat());
        }
        result.put("warnings", report.warnings());
        return result;
    }

    static void addDecorations(List<Map<String, Object>> entries, List<TerrainDecorationExport.Edit> edits) {
        for (var edit : edits) {
            Map<String, Object> entry = null;
            for (var each : entries) {
                if (edit.owner().equals(each.get("owner"))) {
                    entry = each;
                }
            }
            if (entry == null) {
                entry = new LinkedHashMap<>();
                entry.put("owner", edit.owner());
                entries.add(entry);
            }
            @SuppressWarnings("unchecked")
            var painted = (List<Map<String, Object>>)
                    entry.computeIfAbsent("paintedDecorations", ignored -> new ArrayList<Map<String, Object>>());
            var item = new LinkedHashMap<String, Object>();
            item.put("decorations", edit.decorations());
            item.put("densityMap", edit.densityMap());
            item.put("package", edit.holder().file().getFileName().toString());
            item.put("heightPackage", edit.holder().heightPackage());
            item.put("changedTexels", edit.changedTexels());
            item.put("alsoChanges", edit.alsoChanges());
            painted.add(item);
        }
    }

    static List<String> decorationLines(List<TerrainDecorationExport.Edit> edits) {
        var lines = new ArrayList<String>();
        var byOwner = new LinkedHashMap<String, List<TerrainDecorationExport.Edit>>();
        for (var edit : edits) {
            byOwner.computeIfAbsent(edit.owner(), ignored -> new ArrayList<>()).add(edit);
        }
        for (var entry : byOwner.entrySet()) {
            lines.add(entry.getKey()
                    + ": painted "
                    + String.join(
                            ", ",
                            entry.getValue().stream()
                                    .map(edit -> (edit.decorations().size() == 1 ? "decoration " : "decorations ")
                                            + String.join(
                                                    ", ",
                                                    edit.decorations().stream()
                                                            .map(String::valueOf)
                                                            .toList())
                                            + " (" + edit.changedTexels() + " density texels)")
                                    .toList()));
        }
        var packages = new LinkedHashSet<String>();
        for (var edit : edits) {
            packages.add(edit.holder().file().getFileName().toString());
        }
        for (var name : packages) {
            lines.add("Saves the edited package " + name + " under its own name");
        }
        for (var edit : edits) {
            if (!edit.alsoChanges().isEmpty()) {
                lines.add("Also changes " + edit.alsoChanges() + ": they read the density map " + edit.densityMap());
            }
        }
        return lines;
    }

    static String beastTip(Report report) {
        var beast = report.beast();
        if (beast == null) {
            return "";
        }
        return String.format(
                Locale.ROOT,
                "Beast bakes cannot be rebuilt exactly. Cruma fitted its approximate baker to %d texels of this"
                        + " Terrain's own lightmap. On sectors it did not use, the sun bake differs by %.0f of 255 on"
                        + " average (%.0f%% within 16) and the local light bake by %.0f. Texels whose rays no stroke or"
                        + " actor edit changes keep their saved bytes.",
                beast.samples(),
                beast.slot0().meanAbsolute(),
                100 * beast.slot0().within16(),
                beast.slot1().meanAbsolute());
    }

    static List<String> summaryLines(Report report) {
        var lighting = report.lighting();
        var lines = new ArrayList<String>();
        if (lighting.changedSamples() != 0
                || report.paintedLayers().isEmpty() && report.mappedLayers().isEmpty()) {
            lines.add(report.owner()
                    + ": "
                    + lighting.changedSamples()
                    + " height samples, lighting rebuilt in "
                    + lighting.affectedSectors()
                    + (lighting.affectedSectors() == 1 ? " sector" : " sectors"));
        }
        if (!report.paintedLayers().isEmpty()) {
            lines.add(report.owner()
                    + ": painted "
                    + String.join(
                            ", ",
                            report.paintedLayers().stream()
                                    .map(layer -> "layer " + layer.layer() + " (" + layer.changedTexels() + " weights)")
                                    .toList())
                    + (report.layerPassSectors() == 0
                            ? ""
                            : ", drawn layers rebuilt in "
                                    + report.layerPassSectors()
                                    + (report.layerPassSectors() == 1 ? " sector" : " sectors")));
        }
        if (!report.mappedLayers().isEmpty()) {
            lines.add(report.owner()
                    + ": new texture mapping of "
                    + (report.mappedLayers().size() == 1 ? "layer " : "layers ")
                    + String.join(
                            ", ",
                            report.mappedLayers().stream().map(String::valueOf).toList()));
        }
        if (!report.heightPackage().isEmpty()) {
            lines.add("Saves the edited height package " + report.heightPackage() + " next to the map");
        }
        var beast = report.beast();
        if (beast != null) {
            lines.add(BEAST_LINE
                    + beast.sectors()
                    + (beast.sectors() == 1 ? " sector" : " sectors")
                    + ", "
                    + beast.changedTexels()
                    + " texels changed");
        }
        lines.addAll(report.warnings());
        return lines;
    }
}
