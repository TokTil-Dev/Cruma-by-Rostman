package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainHeightField;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainNativeCodec.Vector;
import com.rost.cruma.core.editing.TerrainTileLink;
import java.util.ArrayList;
import java.util.List;

public final class OpenGround {
    private final Info base;
    private final TerrainHeightField field;
    private final List<Part> others;

    OpenGround(Info base, TerrainHeightField field, List<Part> others) {
        this.base = base;
        this.field = field;
        this.others = List.copyOf(others);
    }

    record Part(TerrainTileLink link, Info info, TerrainHeightField field) {}

    public static OpenGround ofHistories(TerrainDocument base, List<TerrainDocument> open) {
        var parts = new ArrayList<Part>();
        for (var other : open) {
            if (other == base) {
                continue;
            }
            var link = TerrainTileLink.of(base.getInfo(), other.getInfo());
            if (link.fits()) {
                parts.add(new Part(link, other.getInfo(), field(other)));
            }
        }
        return new OpenGround(base.getInfo(), field(base), parts);
    }

    public Info getBase() {
        return base;
    }

    TerrainHeightField getField() {
        return field;
    }

    float sampleZ(int x, int y) {
        if (x >= 0 && y >= 0 && x < field.getWidth() && y < field.getHeight()) {
            return base.toWorld().transform(x, y, field.getHeightAt(x, y)).z();
        }
        for (var part : others) {
            var sx = (int) part.link().sampleX(x);
            var sy = (int) part.link().sampleY(y);
            var other = part.field();
            if (sx >= 0 && sy >= 0 && sx < other.getWidth() && sy < other.getHeight()) {
                return part.info()
                        .toWorld()
                        .transform(sx, sy, other.getHeightAt(sx, sy))
                        .z();
            }
        }
        return Float.NaN;
    }

    public Vector getWorldAt(float x, float y) {
        var x0 = (int) Math.floor(x);
        var y0 = (int) Math.floor(y);
        var fx = x - x0;
        var fy = y - y0;
        var a = cornerZ(x0, y0);
        var b = cornerZ(x0 + 1, y0);
        var c = cornerZ(x0, y0 + 1);
        var d = cornerZ(x0 + 1, y0 + 1);
        var z = (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy;
        var flat = base.toWorld().transform(x, y, 0);
        return new Vector(flat.x(), flat.y(), z);
    }

    private float cornerZ(int x, int y) {
        var z = sampleZ(x, y);
        if (!Float.isNaN(z)) {
            return z;
        }
        var cx = Math.clamp(x, 0, field.getWidth() - 1);
        var cy = Math.clamp(y, 0, field.getHeight() - 1);
        return base.toWorld().transform(cx, cy, field.getHeightAt(cx, cy)).z();
    }

    private static TerrainHeightField field(TerrainDocument document) {
        var info = document.getInfo();
        return new TerrainHeightField(
                info.width(), info.height(), document.getHistory().getSamples(), info.toWorld());
    }
}
