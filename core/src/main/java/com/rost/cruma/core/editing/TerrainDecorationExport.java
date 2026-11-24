package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainDecorationSources.Holder;
import com.rost.cruma.core.editing.TerrainDecorationSources.Opened;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TerrainDecorationExport {
    private static final int DXT1_TOLERANCE = 64;

    private TerrainDecorationExport() {}

    public record Edit(
            String owner,
            String densityMap,
            List<Integer> decorations,
            Holder holder,
            int width,
            int height,
            byte[] original,
            byte[] weights,
            int x0,
            int y0,
            int x1,
            int y1,
            int changedTexels,
            String alsoChanges) {
        public Edit {
            decorations = List.copyOf(decorations);
        }
    }

    public record Written(Path source, String sourceSha256, byte[] bytes, List<Edit> edits) {
        public Written {
            edits = List.copyOf(edits);
        }
    }

    public static List<Edit> edits(String owner, Opened opened, List<TerrainPaintHistory.Edit> changes) {
        var result = new ArrayList<Edit>();
        for (var change : changes) {
            var slot = change.layer().index();
            var holder = opened.holders().get(slot);
            if (holder == null) {
                throw new IllegalStateException("A locked density map was painted");
            }
            var readers = opened.decorations().stream()
                    .filter(decoration -> decoration.slot() == slot)
                    .toList();
            result.add(new Edit(
                    owner,
                    change.layer().weightTexture(),
                    readers.stream()
                            .map(TerrainDecorationSources.Decoration::index)
                            .toList(),
                    holder,
                    change.layer().width(),
                    change.layer().height(),
                    change.original(),
                    change.weights(),
                    change.x0(),
                    change.y0(),
                    change.x1(),
                    change.y1(),
                    change.changedTexels(),
                    readers.isEmpty() ? "" : readers.getFirst().getAlsoChanges()));
        }
        return List.copyOf(result);
    }

    public static List<Written> write(List<Edit> edits, Map<Path, byte[]> replaced) throws IOException {
        var byFile = new LinkedHashMap<Path, List<Edit>>();
        var painted = new LinkedHashMap<String, String>();
        for (var edit : edits) {
            var key = edit.densityMap().toLowerCase(Locale.ROOT);
            var other = painted.putIfAbsent(key, edit.owner());
            if (other != null && !other.equals(edit.owner())) {
                throw new IOException("The density map " + edit.densityMap() + " was painted in both " + other + " and "
                        + edit.owner() + ". Revert it in one of them before saving");
            }
            byFile.computeIfAbsent(edit.holder().file().toAbsolutePath().normalize(), ignored -> new ArrayList<>())
                    .add(edit);
        }
        var result = new ArrayList<Written>();
        for (var entry : byFile.entrySet()) {
            var file = entry.getKey();
            var group = entry.getValue();
            var sha = group.getFirst().holder().sha256();
            var base = replaced.get(file);
            if (base == null) {
                base = Files.readAllBytes(file);
                if (!TerrainEditExport.sha256(base).equals(sha)) {
                    throw new IOException("The package " + file.getFileName()
                            + " changed since the Terrain opened. Close and open the Terrain again before saving");
                }
            }
            result.add(new Written(file, sha, write(file, base, group), group));
        }
        return List.copyOf(result);
    }

    private static byte[] write(Path file, byte[] base, List<Edit> edits) throws IOException {
        var asset = new PackageBytes(file, base);
        var body = asset.body.clone();
        var spans = new ArrayList<int[]>();
        var textures = new ArrayList<TerrainDensityTexture>();
        for (var edit : edits) {
            var texture = TerrainDensityTexture.find(asset, edit.densityMap());
            if (texture.getWidth() != edit.width()
                    || texture.getHeight() != edit.height()
                    || !Arrays.equals(texture.read(asset.body), edit.original())) {
                throw new IOException("The density map " + edit.densityMap()
                        + " changed since the Terrain was opened. Close and open the Terrain again before saving");
            }
            texture.write(body, edit.weights(), edit.x0(), edit.y0(), edit.x1(), edit.y1());
            textures.add(texture);
            for (var mip : texture.getMips()) {
                spans.add(new int[] {mip.offset(), mip.offset() + mip.length()});
            }
        }
        var output = asset.encode(body, file);
        var reread = new PackageBytes(file, output);
        if (reread.body.length != asset.body.length) {
            throw new IOException("The written package " + file.getFileName() + " changed its size");
        }
        for (var i = 0; i < asset.body.length; i++) {
            if (asset.body[i] != reread.body[i] && !isInside(spans, i)) {
                throw new IOException(
                        "The written package " + file.getFileName() + " differs outside the painted density maps");
            }
        }
        for (var index = 0; index < edits.size(); index++) {
            var edit = edits.get(index);
            var texture = textures.get(index);
            var values = TerrainDensityTexture.find(reread, edit.densityMap()).read(reread.body);
            var dxt1 = texture.getFormatName().equals("DXT1");
            var tolerance = dxt1 ? DXT1_TOLERANCE : 0;
            var left = dxt1 ? edit.x0() & ~3 : edit.x0();
            var top = dxt1 ? edit.y0() & ~3 : edit.y0();
            var right = dxt1 ? edit.x1() | 3 : edit.x1();
            var bottom = dxt1 ? edit.y1() | 3 : edit.y1();
            for (var texel = 0; texel < values.length; texel++) {
                var x = texel % edit.width();
                var y = texel / edit.width();
                var inside = x >= left && x <= right && y >= top && y <= bottom;
                if (!inside && values[texel] != edit.original()[texel]) {
                    throw new IOException("The density map " + edit.densityMap() + " changed outside its strokes");
                }
                if (inside
                        && edit.weights()[texel] != edit.original()[texel]
                        && Math.abs((values[texel] & 255) - (edit.weights()[texel] & 255))
                                > (texture.getFormatName().equals("P8") ? 255 : tolerance)) {
                    throw new IOException(
                            "The density map " + edit.densityMap() + " did not reopen as painted: texel " + x + ", " + y
                                    + " reads " + (values[texel] & 255) + " for " + (edit.weights()[texel] & 255));
                }
            }
        }
        return output;
    }

    private static boolean isInside(List<int[]> spans, int offset) {
        for (var span : spans) {
            if (offset >= span[0] && offset < span[1]) {
                return true;
            }
        }
        return false;
    }
}
