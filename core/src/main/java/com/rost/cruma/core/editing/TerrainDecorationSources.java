package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainPaintHistory.Layer;
import com.rost.cruma.core.world.UnrealLoader.DecorationDensity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class TerrainDecorationSources {
    private TerrainDecorationSources() {}

    public record Source(int index, String mesh, String densityMap) {}

    public record Decoration(
            int index,
            String mesh,
            String densityMap,
            int slot,
            String restriction,
            List<Integer> sameTerrain,
            List<String> otherMaps) {
        public Decoration {
            sameTerrain = List.copyOf(sameTerrain);
            otherMaps = List.copyOf(otherMaps);
        }

        public boolean isPaintable() {
            return restriction.isEmpty();
        }

        public String getAlsoChanges() {
            var parts = new ArrayList<String>();
            if (!sameTerrain.isEmpty()) {
                parts.add((sameTerrain.size() == 1 ? "decoration " : "decorations ")
                        + String.join(
                                ", ", sameTerrain.stream().map(String::valueOf).toList())
                        + " of this Terrain");
            }
            if (!otherMaps.isEmpty()) {
                parts.add("the decorations of " + String.join(", ", otherMaps));
            }
            return String.join(" and ", parts);
        }
    }

    public record Holder(Path file, String sha256, boolean heightPackage) {}

    public record Opened(
            List<Decoration> decorations,
            List<Layer> slots,
            List<byte @Nullable []> weights,
            List<@Nullable Holder> holders) {}

    public static Opened open(
            TerrainPackageSnapshot snapshot,
            String ownerPath,
            List<Source> sources,
            List<LayerSource> layers,
            TerrainTextureUsers users)
            throws IOException {
        var map = snapshot.getPackageBytes();
        var mapName = map.packageName;
        var heightSource = snapshot.findHeightSource(ownerPath);
        var heightPackage = heightSource
                .map(source -> withoutExtension(source.path().getFileName().toString()))
                .orElse("");
        var otherDecorations = snapshot.getTerrains().stream()
                .anyMatch(terrain -> !terrain.path().equals(ownerPath) && terrain.hasDecorations());
        var slotOf = new LinkedHashMap<String, Integer>();
        var first = new ArrayList<Source>();
        for (var source : sources) {
            if (!source.densityMap().isEmpty()) {
                var key = source.densityMap().toLowerCase(Locale.ROOT);
                if (!slotOf.containsKey(key)) {
                    slotOf.put(key, slotOf.size());
                    first.add(source);
                }
            }
        }
        var packages = new HashMap<String, PackageBytes>();
        var hashes = new HashMap<String, String>();
        var slots = new ArrayList<Layer>();
        var weights = new ArrayList<byte[]>();
        var holders = new ArrayList<Holder>();
        var restrictions = new ArrayList<String>();
        var twins = new ArrayList<List<String>>();
        for (var slot = 0; slot < first.size(); slot++) {
            var source = first.get(slot);
            var path = source.densityMap();
            var packageName = path.substring(0, Math.max(0, path.indexOf('.')));
            var restriction = "";
            Path file = null;
            TerrainDensityTexture texture = null;
            PackageBytes holder = null;
            var others = new ArrayList<String>();
            var strangers = new ArrayList<String>();
            for (var user : users.findMaps(path)) {
                if (user.equalsIgnoreCase(mapName)) {
                    continue;
                }
                (TerrainTextureUsers.isTwin(mapName, user) ? others : strangers).add(user);
            }
            var weightOf = layers.stream()
                    .filter(layer -> layer.weightTexture().equalsIgnoreCase(path)
                            || layer.texture().equalsIgnoreCase(path))
                    .findFirst();
            if (packageName.isEmpty()) {
                restriction = "Its density map " + path + " has no package.";
            } else if (packageName.equalsIgnoreCase(mapName)) {
                restriction = "Its density map is inside the map, which Cruma cannot write yet.";
            } else if (!strangers.isEmpty()) {
                restriction = "Its density map " + path + " also grows the decorations of " + names(strangers)
                        + ", so a change would reach them too.";
            } else if (weightOf.isPresent()) {
                restriction = "Terrain layer " + weightOf.orElseThrow().index() + " also uses " + path + ".";
            } else if (otherDecorations) {
                restriction = "This map has another Terrain with decorations, which may read the same density map.";
            } else {
                if (!heightPackage.isEmpty() && packageName.equalsIgnoreCase(heightPackage)) {
                    file = heightSource.orElseThrow().path();
                } else {
                    file = snapshot.findTexturePackage(packageName).orElse(null);
                }
                if (file == null) {
                    restriction = "Its density map is in " + packageName + ", which was not found in Textures.";
                } else {
                    var key = file.toAbsolutePath().normalize().toString();
                    holder = packages.get(key);
                    if (holder == null) {
                        var bytes = Files.readAllBytes(file);
                        holder = new PackageBytes(file, bytes);
                        packages.put(key, holder);
                        hashes.put(key, TerrainEditExport.sha256(bytes));
                    }
                    try {
                        texture = TerrainDensityTexture.find(holder, path);
                    } catch (IOException unsupported) {
                        restriction = unsupported.getMessage() + '.';
                    }
                }
            }
            var paintable = restriction.isEmpty() && texture != null;
            slots.add(new Layer(
                    slot,
                    source.mesh(),
                    path,
                    texture == null ? 0 : texture.getWidth(),
                    texture == null ? 0 : texture.getHeight(),
                    paintable ? "" : restriction));
            weights.add(paintable ? texture.read(holder.body) : null);
            holders.add(
                    paintable
                            ? new Holder(
                                    file,
                                    hashes.get(file.toAbsolutePath().normalize().toString()),
                                    !heightPackage.isEmpty() && packageName.equalsIgnoreCase(heightPackage))
                            : null);
            restrictions.add(restriction);
            twins.add(others);
        }
        var decorations = new ArrayList<Decoration>();
        for (var source : sources) {
            if (source.densityMap().isEmpty()) {
                decorations.add(new Decoration(
                        source.index(),
                        source.mesh(),
                        "",
                        -1,
                        "This decoration has no density map.",
                        List.of(),
                        List.of()));
                continue;
            }
            var slot = slotOf.get(source.densityMap().toLowerCase(Locale.ROOT));
            var sameTerrain = new ArrayList<Integer>();
            for (var other : sources) {
                if (other.index() != source.index() && other.densityMap().equalsIgnoreCase(source.densityMap())) {
                    sameTerrain.add(other.index());
                }
            }
            decorations.add(new Decoration(
                    source.index(),
                    source.mesh(),
                    source.densityMap(),
                    slot,
                    restrictions.get(slot),
                    sameTerrain,
                    twins.get(slot)));
        }
        return new Opened(
                List.copyOf(decorations),
                List.copyOf(slots),
                Collections.unmodifiableList(weights),
                Collections.unmodifiableList(holders));
    }

    public static int densityAt(byte[] weights, int width, int height, int samples, int x, int y) {
        return new DecorationDensity(width, height, weights).quadValue(x, y, samples, samples);
    }

    private static String names(List<String> maps) {
        if (maps.size() <= 3) {
            return String.join(", ", maps);
        }
        return String.join(", ", maps.subList(0, 3)) + " and " + (maps.size() - 3) + " more maps";
    }

    private static String withoutExtension(String file) {
        var dot = file.lastIndexOf('.');
        return dot < 0 ? file : file.substring(0, dot);
    }
}
