package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.NativeTerrainLayerPasses.Weights;
import com.rost.cruma.core.editing.TerrainEditorGeometry.LayerSource;
import com.rost.cruma.core.editing.TerrainPaintHistory.Edit;
import com.rost.cruma.core.editing.TerrainPaintHistory.Layer;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

public final class TerrainPaintSources {
    private TerrainPaintSources() {}

    public record Opened(List<Layer> layers, List<byte[]> weights) {}

    public static Opened open(TerrainPackageSnapshot snapshot, String ownerPath, List<LayerSource> sources)
            throws IOException {
        var map = snapshot.getPackageBytes();
        PackageBytes heights = null;
        var heightSource = snapshot.findHeightSource(ownerPath);
        if (heightSource.isPresent()) {
            heights = new PackageBytes(
                    heightSource.orElseThrow().path(),
                    Files.readAllBytes(heightSource.orElseThrow().path()));
        }
        var passes = "";
        var checked = savesLayerPasses(snapshot, ownerPath);
        var drawn = List.<String>of();
        if (checked) {
            try {
                var found = drawnLayers(snapshot, ownerPath, heights);
                found.read(null, null);
                drawn = found.getPaths();
            } catch (IOException unreadable) {
                passes = "Cruma cannot rebuild which layers the game draws: " + unreadable.getMessage() + '.';
            }
        }
        var layers = new ArrayList<Layer>();
        var weights = new ArrayList<byte[]>();
        for (var source : sources) {
            var restriction = "";
            TerrainAlphaTexture texture = null;
            PackageBytes holder = null;
            if (source.index() == 0) {
                restriction = "The base layer lies under every other layer. Paint the layers above it instead.";
            } else if (!source.drawn()) {
                restriction = source.texture().isEmpty()
                        ? "This layer has no texture."
                        : "The viewer does not draw this layer.";
            } else if (source.weightTexture().isEmpty()) {
                restriction = "This layer has no weight texture.";
            } else {
                var packageName = source.weightTexture()
                        .substring(0, Math.max(0, source.weightTexture().indexOf('.')));
                if (packageName.equalsIgnoreCase(map.packageName)) {
                    holder = map;
                } else if (heights != null && packageName.equalsIgnoreCase(heights.packageName)) {
                    holder = heights;
                } else {
                    restriction = "Its weights are in " + packageName + ", which other maps can share.";
                }
            }
            if (holder != null && !passes.isEmpty()) {
                restriction = passes;
                holder = null;
            } else if (holder != null && checked && source.index() >= drawn.size()) {
                restriction =
                        "The game does not draw this layer, because in the map this layer or an earlier one has no"
                                + " texture or weight texture.";
                holder = null;
            } else if (holder != null
                    && checked
                    && !drawn.get(source.index()).equalsIgnoreCase(source.weightTexture())) {
                restriction = "The game reads another weight texture for this layer.";
                holder = null;
            }
            if (holder != null) {
                try {
                    texture = TerrainAlphaTexture.find(holder, source.weightTexture());
                } catch (IOException unsupported) {
                    restriction = unsupported.getMessage() + '.';
                }
            }
            layers.add(new Layer(
                    source.index(),
                    source.texture(),
                    source.weightTexture(),
                    texture == null ? 0 : texture.getWidth(),
                    texture == null ? 0 : texture.getHeight(),
                    restriction));
            weights.add(texture == null ? null : texture.read(holder.body));
        }
        return new Opened(List.copyOf(layers), Collections.unmodifiableList(weights));
    }

    static final class DrawnLayers {
        private final List<String> paths;
        private final List<PackageBytes> holders;
        private final List<TerrainAlphaTexture> textures;
        private final PackageBytes map;
        private final @Nullable PackageBytes heights;

        private DrawnLayers(
                List<String> paths,
                List<PackageBytes> holders,
                List<TerrainAlphaTexture> textures,
                PackageBytes map,
                @Nullable PackageBytes heights) {
            this.paths = List.copyOf(paths);
            this.holders = List.copyOf(holders);
            this.textures = List.copyOf(textures);
            this.map = map;
            this.heights = heights;
        }

        List<String> getPaths() {
            return paths;
        }

        List<Weights> read(byte @Nullable [] mapBody, byte @Nullable [] heightBody) {
            var result = new ArrayList<Weights>();
            for (var i = 0; i < paths.size(); i++) {
                var holder = holders.get(i);
                var body = holder == map && mapBody != null
                        ? mapBody
                        : holder == heights && heightBody != null ? heightBody : holder.body;
                var texture = textures.get(i);
                result.add(new Weights(texture.getWidth(), texture.getHeight(), texture.readAsGame(body)));
            }
            return List.copyOf(result);
        }
    }

    static boolean savesLayerPasses(TerrainPackageSnapshot snapshot, String ownerPath) {
        return snapshot.getTerrains().stream()
                .filter(terrain -> terrain.path().equals(ownerPath))
                .flatMap(terrain -> terrain.sectors().stream())
                .anyMatch(sector -> !sector.layerUniformity().isEmpty()
                        || !sector.quadRenderPasses().isEmpty());
    }

    static DrawnLayers drawnLayers(TerrainPackageSnapshot snapshot, String ownerPath, @Nullable PackageBytes heights)
            throws IOException {
        var map = snapshot.getPackageBytes();
        if (heights == null && snapshot.findHeightSource(ownerPath).isPresent()) {
            var path = snapshot.findHeightSource(ownerPath).orElseThrow().path();
            heights = new PackageBytes(path, Files.readAllBytes(path));
        }
        var others = new HashMap<String, PackageBytes>();
        var paths = snapshot.getDrawnLayerWeights(ownerPath);
        var holders = new ArrayList<PackageBytes>();
        var textures = new ArrayList<TerrainAlphaTexture>();
        for (var slot = 0; slot < paths.size(); slot++) {
            var layer = slot;
            var path = paths.get(slot);
            var packageName = path.substring(0, Math.max(0, path.indexOf('.')));
            PackageBytes holder;
            if (packageName.equalsIgnoreCase(map.packageName)) {
                holder = map;
            } else if (heights != null && packageName.equalsIgnoreCase(heights.packageName)) {
                holder = heights;
            } else {
                holder = others.get(packageName.toLowerCase(Locale.ROOT));
                if (holder == null) {
                    var file = snapshot.findTexturePackage(packageName)
                            .orElseThrow(() -> new IOException("the weights of layer " + layer + " are in "
                                    + packageName + ", which was not found"));
                    holder = new PackageBytes(file, Files.readAllBytes(file));
                    others.put(packageName.toLowerCase(Locale.ROOT), holder);
                }
            }
            try {
                textures.add(TerrainAlphaTexture.findFirstMip(holder, path));
            } catch (IOException unreadable) {
                throw new IOException(
                        "layer " + layer + " cannot be read: " + lowerFirst(unreadable.getMessage()), unreadable);
            }
            holders.add(holder);
        }
        return new DrawnLayers(paths, holders, textures, map, heights);
    }

    static boolean holds(PackageBytes source, Edit edit) {
        var path = edit.layer().weightTexture();
        return path.regionMatches(true, 0, source.packageName + '.', 0, source.packageName.length() + 1);
    }

    static TerrainAlphaTexture write(PackageBytes source, byte[] body, Edit edit) throws IOException {
        var texture = TerrainAlphaTexture.find(source, edit.layer().weightTexture());
        if (texture.getWidth() != edit.layer().width()
                || texture.getHeight() != edit.layer().height()
                || !Arrays.equals(texture.read(source.body), edit.original())) {
            throw new IOException("The weight texture " + edit.layer().weightTexture()
                    + " changed since the Terrain was opened. Close and open the Terrain again before exporting");
        }
        texture.write(body, edit.weights(), edit.x0(), edit.y0(), edit.x1(), edit.y1());
        return texture;
    }

    static byte[] reread(PackageBytes written, Edit edit) throws IOException {
        return TerrainAlphaTexture.find(written, edit.layer().weightTexture()).read(written.body);
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
