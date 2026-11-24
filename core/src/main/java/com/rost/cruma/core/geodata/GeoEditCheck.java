package com.rost.cruma.core.geodata;

import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner.Confidence;
import com.rost.cruma.core.geodata.GeoDataScanner.Finding;
import com.rost.cruma.core.geodata.GeoDataScanner.Limits;
import com.rost.cruma.core.geodata.GeoDataScanner.Rule;
import com.rost.cruma.core.geodata.GeoEditDocument.CellKey;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

public final class GeoEditCheck {
    public static final int MARGIN = 32;

    private GeoEditCheck() {}

    public record Result(List<Finding> introduced, int information, int scannedCells, boolean partial) {
        public Result {
            introduced = List.copyOf(introduced);
        }
    }

    public static Result check(
            GeoDataAccess before,
            GeoDataAccess after,
            Collection<CellKey> changed,
            int maxCells,
            int abruptHeight,
            BooleanSupplier cancelled) {
        var areas = findScanAreas(changed);
        var known = new HashSet<String>();
        var found = new LinkedHashMap<String, Finding>();
        var scanned = 0;
        var partial = false;
        for (var area : areas) {
            if (cancelled.getAsBoolean() || scanned >= maxCells) {
                partial = true;
                break;
            }
            var limits = new Limits(maxCells - scanned, 4096, 8_000_000, abruptHeight, 64, cancelled, ignored -> {});
            var old = new GeoDataScanner(before).scan(area, limits);
            var now = new GeoDataScanner(after).scan(area, limits);
            scanned += now.scannedCells();
            partial |=
                    now.completeness() != Completeness.COMPLETE && now.completeness() != Completeness.DATA_UNAVAILABLE
                            || now.findingsTruncated();
            for (var finding : old.findings()) {
                for (var part : partsOf(finding)) {
                    known.add(findingKey(part));
                }
            }
            for (var finding : now.findings()) {
                found.putIfAbsent(findingKey(finding), finding);
            }
        }
        var introduced = new ArrayList<Finding>();
        var information = 0;
        for (var finding : found.values()) {
            var fresh = false;
            for (var part : partsOf(finding)) {
                fresh |= !known.contains(findingKey(part));
            }
            if (!fresh
                    || finding.rule() == Rule.UNAVAILABLE_REGION
                    || finding.rule() == Rule.CLIENT_GEOMETRY_INCOMPLETE) {
                continue;
            }
            if (finding.confidence() == Confidence.INFORMATION) {
                information++;
            } else {
                introduced.add(finding);
            }
        }
        return new Result(introduced, information, scanned, partial);
    }

    static List<Bounds> findScanAreas(Collection<CellKey> changed) {
        var sectors = new TreeSet<Long>();
        for (var key : changed) {
            var sx = Math.floorDiv(key.x(), 128);
            var sy = Math.floorDiv(key.y(), 128);
            sectors.add(((long) sy << 32) | (sx & 0xffffffffL));
        }
        var runs = new ArrayList<int[]>();
        int[] run = null;
        for (var packed : sectors) {
            var sy = (int) (packed >> 32);
            var sx = (int) (long) packed;
            if (run != null && run[2] == sy && sx == run[1] + 1) {
                run[1] = sx;
                continue;
            }
            run = new int[] {sx, sx, sy, sy};
            runs.add(run);
        }
        runs.sort(Comparator.<int[]>comparingInt(each -> each[0])
                .thenComparingInt(each -> each[1])
                .thenComparingInt(each -> each[2]));
        var result = new ArrayList<Bounds>();
        int[] box = null;
        for (var each : runs) {
            if (box != null && box[0] == each[0] && box[1] == each[1] && box[3] + 1 == each[2]) {
                box[3] = each[2];
                continue;
            }
            if (box != null) {
                result.add(toBounds(box));
            }
            box = each.clone();
        }
        if (box != null) {
            result.add(toBounds(box));
        }
        return result;
    }

    private static Bounds toBounds(int[] box) {
        return new Bounds(
                box[0] * 128 - MARGIN, box[2] * 128 - MARGIN, (box[1] + 1) * 128 + MARGIN, (box[3] + 1) * 128 + MARGIN);
    }

    private static List<Finding> partsOf(Finding finding) {
        return finding.joined().isEmpty() ? List.of(finding) : finding.joined();
    }

    private static String findingKey(Finding finding) {
        return finding.rule()
                + ":"
                + (int) Math.floor(finding.position().x())
                + ":"
                + (int) Math.floor(finding.position().y())
                + ":"
                + finding.direction();
    }
}
