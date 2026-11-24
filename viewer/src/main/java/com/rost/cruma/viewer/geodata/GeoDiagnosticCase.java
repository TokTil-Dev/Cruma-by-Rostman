package com.rost.cruma.viewer.geodata;

import com.rost.cruma.core.geodata.GeoDataAccess;
import com.rost.cruma.core.geodata.GeoDataAccess.Cell;
import com.rost.cruma.core.geodata.GeoDataDiff;
import com.rost.cruma.core.geodata.GeoDataDiff.Bounds;
import com.rost.cruma.core.geodata.GeoDataScanner;
import com.rost.cruma.core.geodata.GeoDataScanner.ClientSample;
import com.rost.cruma.core.geodata.GeoDataScanner.ClientSurface;
import com.rost.cruma.core.geodata.GeoDataScanner.Passage;
import com.rost.cruma.core.geodata.GeoDataScanner.SurfaceProbe;
import com.rost.cruma.core.geodata.GeoPoint;
import com.rost.cruma.core.geodata.GeoQueryEngine.Completeness;
import com.rost.cruma.viewer.geodata.GeodataDiagnosticJobs.Kind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record GeoDiagnosticCase(
        int version,
        String algorithm,
        String fingerprintScope,
        Request request,
        String sourceATrace,
        String sourceBTrace,
        String outcome,
        String clientTrace) {
    public static final int VERSION = 1;

    public static final int MAX_REPORTS = 16384;

    public static final String ALGORITHM =
            "cruma-geodata-diagnostics-6/" + GeoScenario.ALGORITHM + "/" + GeoClientSurfaceIndex.ALGORITHM;
    public static final String FINGERPRINT_SCOPE = "visited-cells-v1";

    public GeoDiagnosticCase(
            int version,
            String algorithm,
            String fingerprintScope,
            Request request,
            String sourceATrace,
            String sourceBTrace,
            String outcome) {
        this(version, algorithm, fingerprintScope, request, sourceATrace, sourceBTrace, outcome, "");
    }

    public GeoDiagnosticCase {
        if (version != VERSION || !ALGORITHM.equals(algorithm) || !FINGERPRINT_SCOPE.equals(fingerprintScope)) {
            throw new IllegalArgumentException("Unsupported diagnostic case version, algorithm or fingerprint scope");
        }
        Objects.requireNonNull(request, "request");
        requireHash(sourceATrace);
        requireHash(outcome);
        if (request.clientGeometry()) {
            requireHash(clientTrace);
        } else if (!"".equals(clientTrace)) {
            throw new IllegalArgumentException("A case without client geometry cannot contain client input");
        }
        if (request.kind() == Kind.COMPARE) {
            requireHash(sourceBTrace);
        } else if (!"".equals(sourceBTrace)) {
            throw new IllegalArgumentException("A scan case cannot contain comparison-source input");
        }
    }

    public enum ReplayStatus {
        REPRODUCED,
        REPRODUCED_PARTIAL,
        INPUT_CHANGED,
        OUTCOME_CHANGED
    }

    public record Request(
            Kind kind,
            Bounds bounds,
            int maxCells,
            int maxReports,
            int maxTransitions,
            int abruptHeight,
            long maxLayerComparisons,
            boolean clientGeometry,
            int surfaceTolerance,
            int maxClientTriangleTests) {
        public Request(
                Kind kind,
                Bounds bounds,
                int maxCells,
                int maxReports,
                int maxTransitions,
                int abruptHeight,
                long maxLayerComparisons) {
            this(
                    kind,
                    bounds,
                    maxCells,
                    maxReports,
                    maxTransitions,
                    abruptHeight,
                    maxLayerComparisons,
                    false,
                    32,
                    2_000_000);
        }

        public Request {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(bounds, "bounds");
            if (maxCells < 1
                    || maxCells > 262144
                    || maxReports < 0
                    || maxReports > MAX_REPORTS
                    || maxTransitions < 1
                    || maxTransitions > 2_000_000
                    || abruptHeight < 8
                    || abruptHeight > 4096
                    || maxLayerComparisons < 1
                    || maxLayerComparisons > 20_000_000
                    || surfaceTolerance < 0
                    || surfaceTolerance > 4096
                    || kind == Kind.COMPARE && clientGeometry
                    || maxClientTriangleTests < 1
                    || maxClientTriangleTests > 20_000_000) {
                throw new IllegalArgumentException("Diagnostic case exceeds supported work limits");
            }
        }
    }

    static GeoDiagnosticCase scan(Request request, Trace source, SurfaceTrace client, GeoDataScanner.Result result) {
        var hash = new Hash();
        hash.text("scan");
        hash.number(result.scannedCells());
        hash.number(result.testedTransitions());
        hash.number(result.unavailableCells());
        hash.text(result.completeness().name());
        hash.flag(result.findingsTruncated());
        hash.flag(result.clientGeometryChecked());
        hash.number(result.clientCheckedCells());
        hash.number(result.clientUnavailableCells());
        hash.number(result.findings().size());
        for (var finding : result.findings()) {
            hash.text(finding.rule().name());
            hash.text(finding.confidence().name());
            hash.point(finding.position());
            hash.point(finding.target());
            hash.cell(finding.source());
            hash.cell(finding.neighbor());
            hash.text(finding.direction() == null ? "" : finding.direction().name());
            hash.text(finding.completeness().name());
            hash.number(finding.joined().size());
            for (var joined : finding.joined()) {
                hash.point(joined.position());
                hash.text(joined.direction() == null ? "" : joined.direction().name());
            }
        }
        return new GeoDiagnosticCase(
                VERSION,
                ALGORITHM,
                FINGERPRINT_SCOPE,
                request,
                source.finish(),
                "",
                hash.finish(),
                client == null ? "" : client.finish());
    }

    static GeoDiagnosticCase comparison(Request request, Trace first, Trace second, GeoDataDiff.Result result) {
        var hash = new Hash();
        hash.text("compare");
        hash.number(result.comparedCells());
        hash.number(result.changedCells());
        hash.number(result.ambiguousCells());
        hash.number(result.unavailableCells());
        hash.text(result.completeness().name());
        hash.flag(result.reportsTruncated());
        hash.number(result.cells().size());
        for (var cell : result.cells()) {
            hash.number(cell.x());
            hash.number(cell.y());
            hash.cell(cell.before());
            hash.cell(cell.after());
            hash.flag(cell.ambiguous());
            hash.flag(cell.complete());
            hash.flag(cell.selectionOrderChanged());
            hash.flag(cell.storageSemanticsChanged());
            hash.number(cell.matches().size());
            for (var match : cell.matches()) {
                hash.number(match.beforeIndex());
                hash.number(match.afterIndex());
                hash.flag(match.heightChanged());
                hash.flag(match.nsweChanged());
                hash.flag(match.ambiguous());
            }
        }
        return new GeoDiagnosticCase(
                VERSION, ALGORITHM, FINGERPRINT_SCOPE, request, first.finish(), second.finish(), hash.finish());
    }

    static boolean isComplete(GeoDataScanner.Result scan, GeoDataDiff.Result difference) {
        return scan != null
                ? scan.completeness() == Completeness.COMPLETE && !scan.findingsTruncated()
                : difference.completeness() == Completeness.COMPLETE && !difference.reportsTruncated();
    }

    static final class Trace implements GeoDataAccess {
        private final GeoDataAccess source;
        private final Hash hash = new Hash();

        Trace(GeoDataAccess source) {
            this.source = source;
        }

        @Override
        public Cell cellAt(int worldX, int worldY) {
            var cell = source.cellAt(worldX, worldY);
            hash.number(worldX);
            hash.number(worldY);
            hash.cell(cell);
            return cell;
        }

        @Override
        public long revision() {
            return source.revision();
        }

        @Override
        public String getSourceId() {
            return source.getSourceId();
        }

        String finish() {
            return hash.finish();
        }
    }

    static final class SurfaceTrace implements SurfaceProbe {
        private final SurfaceProbe source;
        private final Hash hash = new Hash();

        SurfaceTrace(SurfaceProbe source) {
            this.source = source;
        }

        @Override
        public List<ClientSurface> getSurfacesAt(int x, int y) {
            return sampleAt(x, y).surfaces();
        }

        @Override
        public ClientSample sampleAt(int x, int y) {
            var sample = source.sampleAt(x, y);
            hash.number(x);
            hash.number(y);
            hash.text(sample.completeness().name());
            hash.number(sample.surfaces().size());
            for (var surface : sample.surfaces()) {
                hash.number(Double.doubleToLongBits(surface.height()));
                hash.text(surface.kind().name());
                hash.text(surface.version());
            }
            return sample;
        }

        @Override
        public Passage checkPassage(GeoPoint from, GeoPoint to) {
            var passage = source.checkPassage(from, to);
            hash.text("passage");
            hash.point(from);
            hash.point(to);
            hash.text(passage.name());
            return passage;
        }

        @Override
        public boolean isValid() {
            return source.isValid();
        }

        String finish() {
            return hash.finish();
        }
    }

    public ReplayStatus compare(GeoDiagnosticCase actual, boolean complete) {
        if (!request.equals(actual.request)
                || !sourceATrace.equals(actual.sourceATrace)
                || !sourceBTrace.equals(actual.sourceBTrace)
                || !clientTrace.equals(actual.clientTrace)) {
            return ReplayStatus.INPUT_CHANGED;
        }
        if (!outcome.equals(actual.outcome)) {
            return ReplayStatus.OUTCOME_CHANGED;
        }
        return complete ? ReplayStatus.REPRODUCED : ReplayStatus.REPRODUCED_PARTIAL;
    }

    private static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid diagnostic SHA-256 fingerprint");
        }
    }

    private static final class Hash {
        private final MessageDigest digest;
        private final ByteBuffer numbers = ByteBuffer.allocate(Long.BYTES);

        Hash() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        void number(long value) {
            numbers.clear();
            numbers.putLong(value);
            digest.update(numbers.array());
        }

        void flag(boolean value) {
            digest.update((byte) (value ? 1 : 0));
        }

        void text(String value) {
            var bytes = value.getBytes(StandardCharsets.UTF_8);
            number(bytes.length);
            digest.update(bytes);
        }

        void point(GeoPoint point) {
            flag(point != null);
            if (point != null) {
                number(Double.doubleToLongBits(point.x()));
                number(Double.doubleToLongBits(point.y()));
                number(Double.doubleToLongBits(point.z()));
            }
        }

        void cell(Cell cell) {
            flag(cell != null);
            if (cell == null) {
                return;
            }
            number(cell.x());
            number(cell.y());
            text(cell.availability().name());
            text(cell.storage().name());
            number(cell.layers().size());
            for (var layer : cell.layers()) {
                number(layer.index());
                number(layer.height());
                number(layer.nswe());
            }
        }

        String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
