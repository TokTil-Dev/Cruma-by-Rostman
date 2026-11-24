package com.rost.cruma.viewer.editing;

import com.rost.cruma.core.editing.TerrainBrushStamp;
import com.rost.cruma.core.editing.TerrainBrushes.Falloff;
import com.rost.cruma.core.editing.TerrainBrushes.RampPoint;
import com.rost.cruma.core.editing.TerrainBrushes.Shape;
import com.rost.cruma.core.editing.TerrainNativeCodec.Info;
import com.rost.cruma.core.editing.TerrainTileLink;
import com.rost.cruma.viewer.editing.TerrainEditingController.Step;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

public final class TerrainStroke {
    private final TerrainEditingController controller;
    private final TerrainDocument anchor;
    private final boolean reach;
    private final Set<TerrainDocument> started = new LinkedHashSet<>();

    TerrainStroke(TerrainEditingController controller, TerrainDocument anchor, boolean reach) {
        this.controller = controller;
        this.anchor = anchor;
        this.reach = reach;
    }

    record Member(TerrainDocument document, @Nullable TerrainTileLink link) {
        int offsetX() {
            return link == null ? 0 : link.getOffsetX();
        }

        int offsetY() {
            return link == null ? 0 : link.getOffsetY();
        }
    }

    public TerrainDocument getAnchor() {
        return anchor;
    }

    public boolean reaches() {
        return reach;
    }

    public List<TerrainDocument> getStarted() {
        return List.copyOf(started);
    }

    public void stamps(Shape shape, List<TerrainBrushStamp> stamps) {
        for (var member : getMembers()) {
            var link = member.link();
            var history = member.document().getHistory();
            if (link == null) {
                begin(member.document());
                history.stamps(shape, stamps);
                continue;
            }
            var info = member.document().getInfo();
            var moved = new ArrayList<TerrainBrushStamp>();
            for (var stamp : stamps) {
                var converted = link.stamp(stamp, shape.brush());
                if (touches(info, converted.x(), converted.y(), converted.x(), converted.y(), shape.radius())) {
                    moved.add(converted);
                }
            }
            if (moved.isEmpty()) {
                continue;
            }
            begin(member.document());
            history.stamps(link.toShape(shape), moved);
        }
    }

    public void ramp(RampPoint a, RampPoint b, float width, Falloff falloff) {
        var edge = width * .5f + Math.max(1, width * .25f);
        for (var member : getMembers()) {
            var link = member.link();
            if (link == null) {
                begin(member.document());
                member.document().getHistory().ramp(a, b, width, falloff);
                continue;
            }
            var from = link.toRampPoint(a);
            var to = link.toRampPoint(b);
            if (!touches(
                    member.document().getInfo(),
                    Math.min(from.x(), to.x()),
                    Math.min(from.y(), to.y()),
                    Math.max(from.x(), to.x()),
                    Math.max(from.y(), to.y()),
                    edge)) {
                continue;
            }
            begin(member.document());
            member.document().getHistory().ramp(from, to, width, falloff);
        }
    }

    public void moveSelection(float[] weights, float raw) {
        begin(anchor);
        anchor.getHistory().moveSelection(weights, raw);
    }

    public void revertToSource() {
        begin(anchor);
        anchor.getHistory().revertToSource();
    }

    public Set<String> finish(String label) {
        var owners = new LinkedHashSet<String>();
        try {
            for (var document : started) {
                if (document.getHistory().isStrokeActive()
                        && document.getHistory().finishStroke()) {
                    owners.add(document.getOwner());
                }
            }
        } finally {
            controller.endStroke(this);
        }
        if (!owners.isEmpty()) {
            controller.recordStep(Step.HEIGHTS, owners, label);
        }
        return Set.copyOf(owners);
    }

    public void cancel() {
        try {
            for (var document : started) {
                document.getHistory().cancelStroke();
            }
        } finally {
            controller.endStroke(this);
        }
    }

    List<Member> getMembers() {
        var members = new ArrayList<Member>();
        members.add(new Member(anchor, null));
        if (reach) {
            for (var document : controller.getDocuments()) {
                if (document == anchor) {
                    continue;
                }
                var link = TerrainTileLink.of(anchor.getInfo(), document.getInfo());
                if (link.fits()) {
                    members.add(new Member(document, link));
                }
            }
        }
        members.sort(Comparator.comparingInt(Member::offsetY)
                .thenComparingInt(Member::offsetX)
                .reversed());
        return members;
    }

    private void begin(TerrainDocument document) {
        if (started.contains(document)) {
            return;
        }
        var paint = document.getPaint();
        var quads = document.getQuads();
        if (paint != null && paint.isStrokeActive() || quads != null && quads.isStrokeActive()) {
            throw new IllegalStateException("Finish the paint stroke first");
        }
        if (!document.getHistory().isStrokeActive()) {
            document.getHistory().beginStroke();
        }
        started.add(document);
    }

    private static boolean touches(Info info, float minX, float minY, float maxX, float maxY, float reach) {
        return Math.floor(minX - reach) <= info.width() - 1
                && Math.ceil(maxX + reach) >= 0
                && Math.floor(minY - reach) <= info.height() - 1
                && Math.ceil(maxY + reach) >= 0;
    }
}
