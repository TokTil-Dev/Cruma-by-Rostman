package com.rost.cruma.core.editing;

import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainEditorGeometry.SeamHeights;
import com.rost.cruma.core.editing.TerrainHeightField.Brush;
import com.rost.cruma.core.editing.TerrainHeightField.OutsideHeights;
import com.rost.cruma.core.editing.TerrainNativeCodec.Coords;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import java.util.Objects;
import java.util.function.IntBinaryOperator;

public final class TerrainTileLink {
    private static final double WHOLE = 1e-3;
    private static final double SAME = 1e-5;

    private final Info a;
    private final Info b;
    private final String refusal;
    private final int offsetX;
    private final int offsetY;

    private TerrainTileLink(Info a, Info b, String refusal, int offsetX, int offsetY) {
        this.a = a;
        this.b = b;
        this.refusal = refusal;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    public static TerrainTileLink of(Info a, Info b) {
        Objects.requireNonNull(a);
        Objects.requireNonNull(b);
        var reason = alignment(a.toWorld(), "first");
        if (reason.isEmpty()) {
            reason = alignment(b.toWorld(), "second");
        }
        if (!reason.isEmpty()) {
            return new TerrainTileLink(a, b, reason, 0, 0);
        }
        var first = a.toWorld();
        var second = b.toWorld();
        if (!isSame(first.xAxis().x(), second.xAxis().x())
                || !isSame(first.yAxis().y(), second.yAxis().y())) {
            return new TerrainTileLink(
                    a,
                    b,
                    "The two Terrains have another sample spacing ("
                            + first.xAxis().x()
                            + " by "
                            + first.yAxis().y()
                            + " against "
                            + second.xAxis().x()
                            + " by "
                            + second.yAxis().y()
                            + " world units)",
                    0,
                    0);
        }
        var shiftX = (double) first.origin().x() - second.origin().x();
        var shiftY = (double) first.origin().y() - second.origin().y();
        var wholeX = Math.round(shiftX);
        var wholeY = Math.round(shiftY);
        if (Math.abs(shiftX - wholeX) > WHOLE || Math.abs(shiftY - wholeY) > WHOLE) {
            return new TerrainTileLink(
                    a,
                    b,
                    "The second Terrain is not shifted by a whole number of samples (" + shiftX + ", " + shiftY + ")",
                    0,
                    0);
        }
        var beside = (wholeX == 0 || wholeX == a.width() || wholeX == -b.width())
                && (wholeY == 0 || wholeY == a.height() || wholeY == -b.height());
        if (!beside || wholeX == 0 && wholeY == 0) {
            return new TerrainTileLink(
                    a,
                    b,
                    "The two Terrains are not neighbours: the second one starts " + wholeX + ", " + wholeY
                            + " samples away",
                    0,
                    0);
        }
        return new TerrainTileLink(a, b, "", (int) wholeX, (int) wholeY);
    }

    public boolean fits() {
        return refusal.isEmpty();
    }

    public String getRefusal() {
        return refusal;
    }

    public int getOffsetX() {
        requireFit();
        return offsetX;
    }

    public int getOffsetY() {
        requireFit();
        return offsetY;
    }

    public TerrainTileLink reversed() {
        return of(b, a);
    }

    public float sampleX(float x) {
        requireFit();
        return x - offsetX;
    }

    public float sampleY(float y) {
        requireFit();
        return y - offsetY;
    }

    public boolean isOnSecond(int x, int y) {
        requireFit();
        var sx = x - offsetX;
        var sy = y - offsetY;
        return sx >= 0 && sy >= 0 && sx < b.width() && sy < b.height();
    }

    public double toRawHeight(double raw) {
        requireFit();
        var world = (raw - a.toWorld().origin().z()) * a.toWorld().zAxis().z();
        return world / b.toWorld().zAxis().z() + b.toWorld().origin().z();
    }

    public double toRawDelta(double delta) {
        requireFit();
        return delta * a.toWorld().zAxis().z() / b.toWorld().zAxis().z();
    }

    public Shape toShape(Shape shape) {
        requireFit();
        return new Shape(
                shape.brush(),
                shape.radius(),
                (int) Math.round(toRawHeight(shape.target())),
                shape.falloff(),
                shape.seed(),
                (float) toRawDelta(shape.amplitude()),
                shape.scale(),
                shape.noiseX() + offsetX,
                shape.noiseY() + offsetY);
    }

    public TerrainBrushStamp stamp(TerrainBrushStamp stamp, Brush brush) {
        requireFit();
        var strength =
                brush == Brush.RAISE || brush == Brush.LOWER ? (float) toRawDelta(stamp.strength()) : stamp.strength();
        return new TerrainBrushStamp(sampleX(stamp.x()), sampleY(stamp.y()), strength);
    }

    public RampPoint toRampPoint(RampPoint point) {
        requireFit();
        return new RampPoint(sampleX(point.x()), sampleY(point.y()), (int) Math.round(toRawHeight(point.height())));
    }

    public OutsideHeights createOutsideHeights(IntBinaryOperator second) {
        Objects.requireNonNull(second);
        requireFit();
        var back = reversed();
        return (x, y) -> isOnSecond(x, y) ? back.toRawHeight(second.applyAsInt(x - offsetX, y - offsetY)) : Double.NaN;
    }

    public OutsideHeights createOutsideHeights(TerrainHeightField second) {
        Objects.requireNonNull(second);
        return createOutsideHeights(second::getHeightAt);
    }

    public SeamHeights createSeamHeights(IntBinaryOperator second) {
        Objects.requireNonNull(second);
        requireFit();
        var toWorld = b.toWorld();
        return (x, y) -> {
            if (!isOnSecond(x, y)) {
                return Float.NaN;
            }
            var sx = x - offsetX;
            var sy = y - offsetY;
            return toWorld.transform(sx, sy, second.applyAsInt(sx, sy)).z();
        };
    }

    private void requireFit() {
        if (!fits()) {
            throw new IllegalStateException(refusal);
        }
    }

    private static String alignment(Coords coords, String which) {
        var x = coords.xAxis();
        var y = coords.yAxis();
        var z = coords.zAxis();
        if (x.y() != 0 || x.z() != 0 || y.x() != 0 || y.z() != 0 || z.x() != 0 || z.y() != 0) {
            return "The " + which + " Terrain is turned or slanted, not aligned with the world axes";
        }
        if (!isUsable(x.x())
                || !isUsable(y.y())
                || !isUsable(z.z())
                || !Float.isFinite(coords.origin().x())
                || !Float.isFinite(coords.origin().y())
                || !Float.isFinite(coords.origin().z())) {
            return "The " + which + " Terrain has a scale of zero or one that is not a number";
        }
        return "";
    }

    private static boolean isUsable(float scale) {
        return Float.isFinite(scale) && scale != 0;
    }

    private static boolean isSame(float first, float second) {
        return Math.abs((double) first - second) <= SAME * Math.abs((double) first);
    }
}
