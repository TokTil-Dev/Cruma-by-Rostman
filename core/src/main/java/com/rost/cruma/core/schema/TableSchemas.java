package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.SchemaFolder.Entry;
import com.rost.cruma.core.schema.TableReport.Attempt;
import com.rost.cruma.core.schema.TableReport.Outcome;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class TableSchemas {
    static final int CACHED_CLIENTS = 8;

    private static final Logger log = LogManager.getLogger(TableSchemas.class);
    private static final Map<Path, SchemaFolder> folders = Collections.synchronizedMap(new LeastUsed<>());
    private static final Map<Path, TableReport> reports = new ConcurrentHashMap<>();
    private static final Map<Path, Names> nameCache = Collections.synchronizedMap(new LeastUsed<>());
    private static volatile @Nullable Path userFolder;
    private static volatile @Nullable SchemaFolder userSchemas;

    private TableSchemas() {}

    private record Names(long size, long time, List<String> names) {}

    private record Candidate(Entry entry, @Nullable Schema schema, String problem) {}

    public record Listed(
            String title, String source, @Nullable Schema schema, String problem) {}

    private static final class LeastUsed<V> extends LinkedHashMap<Path, V> {
        private LeastUsed() {
            super(16, 0.75f, true);
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, V> eldest) {
            return size() > CACHED_CLIENTS;
        }
    }

    private static final class BuiltIns {
        static final List<String> FILES = readIndex();
        static final Map<String, String> TEXTS = readTexts(FILES);
        static final List<Entry> ENTRIES = parse(FILES, TEXTS);

        private BuiltIns() {}

        private static List<String> readIndex() {
            var text = readResource("index.txt");
            var files = new ArrayList<String>();
            for (var line : text.lines().toList()) {
                var name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    files.add(name);
                }
            }
            return List.copyOf(files);
        }

        private static Map<String, String> readTexts(List<String> files) {
            var texts = new LinkedHashMap<String, String>();
            for (var file : files) {
                texts.put(file, readResource(file));
            }
            return Collections.unmodifiableMap(texts);
        }

        private static List<Entry> parse(List<String> files, Map<String, String> texts) {
            var schemas = new ArrayList<@Nullable Schema>();
            var problems = new ArrayList<@Nullable SchemaException>();
            for (var file : files) {
                try {
                    schemas.add(Schema.parse(texts.get(file), "built-in " + file, null, true));
                    problems.add(null);
                } catch (SchemaException mistake) {
                    log.error("A built-in schema has a mistake: {}", mistake.getMessage());
                    schemas.add(null);
                    problems.add(mistake);
                }
            }
            var entries = new ArrayList<Entry>();
            for (var i = 0; i < files.size(); i++) {
                var schema = schemas.get(i);
                var problem = problems.get(i);
                if (schema != null && !schema.isResolved()) {
                    var pool = new ArrayList<Schema>();
                    for (var other : schemas) {
                        if (other != null && other.getTarget().equalsIgnoreCase(schema.getTarget())) {
                            pool.add(other);
                        }
                    }
                    try {
                        schema = schema.resolve(pool);
                    } catch (SchemaException mistake) {
                        log.error("A built-in schema has a mistake: {}", mistake.getMessage());
                        schema = null;
                        problem = mistake;
                    }
                }
                entries.add(new Entry(TableReport.BUILT_IN, schema, problem, null));
            }
            return List.copyOf(entries);
        }

        private static String readResource(String name) {
            try (var in = TableSchemas.class.getResourceAsStream("tables/" + name)) {
                if (in == null) {
                    throw new IllegalStateException("The built-in schema " + name + " is missing");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    public static synchronized void setUserFolder(@Nullable Path folder) {
        userFolder = folder;
        userSchemas =
                folder == null ? null : new SchemaFolder(folder.toAbsolutePath().normalize());
        L2DataFile.setUserSchemaFolder(folder);
    }

    public static @Nullable Path getUserFolder() {
        return userFolder;
    }

    public static Path getClientFolder(Path system) {
        return system.toAbsolutePath().normalize().resolveSibling("cruma").resolve("schemas");
    }

    public static List<Schema> getBuiltIns() {
        var schemas = new ArrayList<Schema>();
        for (var entry : BuiltIns.ENTRIES) {
            if (entry.schema() != null) {
                schemas.add(entry.schema());
            }
        }
        return List.copyOf(schemas);
    }

    public static @Nullable Path findTable(TableSpec spec, Path system, WorldVariant variant) {
        if (!spec.localized()) {
            return TableFiles.find(system, variant, spec.fileName());
        }
        var name = spec.fileName();
        var stem = name.toLowerCase(Locale.ROOT).endsWith(".dat") ? name.substring(0, name.length() - 4) : name;
        for (var folder : TableFiles.getFolders(system)) {
            for (var world : variant.getTableWorlds()) {
                var found = findLocalized(folder, stem + world.getTableSuffix());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    public static TableData read(TableSpec spec, Path system, WorldVariant variant) {
        var file = findTable(spec, system, variant);
        if (file == null) {
            var where = system.resolve(spec.localized() ? spec.fileName().replace(".dat", "-e.dat") : spec.fileName());
            var report = new TableReport(spec.id(), where, State.NO_FILE, "", "", 0, spec.lost(), "", List.of());
            return store(new TableData(List.of(), Map.of(), Map.of(), null, report), true);
        }
        var onlyWith = spec.onlyWith();
        var withoutOther = onlyWith != null && TableFiles.find(system, variant, onlyWith.fileName()) == null;
        if (withoutOther && !onlyWith.ifUnread()) {
            var report = new TableReport(spec.id(), file, State.NOT_USED, "", "", 0, "", onlyWith.reason(), List.of());
            return store(new TableData(List.of(), Map.of(), Map.of(), null, report), true);
        }
        byte[] table;
        try {
            table = L2DataFile.read(file);
        } catch (IOException | RuntimeException failure) {
            var report = new TableReport(
                    spec.id(),
                    file,
                    State.NOT_READ,
                    "",
                    "",
                    0,
                    spec.lost(),
                    "the file cannot be opened: " + failure.getMessage(),
                    List.of());
            log.warn("{} cannot be opened: {}", file, failure.getMessage());
            return store(new TableData(List.of(), Map.of(), Map.of(), null, report), true);
        }
        var data = read(spec, file, table, readGameDataNames(system, variant), getClientFolder(system));
        if (withoutOther && data.report().state() == State.NOT_READ) {
            var read = data.report();
            var report =
                    new TableReport(spec.id(), file, State.NOT_USED, "", "", 0, "", onlyWith.reason(), read.attempts());
            return store(new TableData(List.of(), Map.of(), Map.of(), null, report), true);
        }
        return data;
    }

    public static List<String> readGameDataNames(Path system, WorldVariant variant) {
        var file = TableFiles.find(system, variant, "L2GameDataName.dat");
        if (file == null) {
            return List.of();
        }
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            var key = file.toAbsolutePath().normalize();
            var time = attributes.lastModifiedTime().toMillis();
            var cached = nameCache.get(key);
            if (cached != null && cached.size() == attributes.size() && cached.time() == time) {
                return cached.names();
            }
            var names = List.copyOf(GameDataNames.read(file, L2DataFile.read(file), getClientFolder(system)));
            nameCache.put(key, new Names(attributes.size(), time, names));
            return names;
        } catch (IOException | RuntimeException failure) {
            log.warn("{} cannot be read: {}", file, failure.getMessage());
            return List.of();
        }
    }

    public static TableData read(
            TableSpec spec, Path file, byte[] table, List<String> names, @Nullable Path clientSchemas) {
        var client = clientSchemas != null;
        var attempts = new ArrayList<Attempt>();
        var wanted = spec.getWanted();
        Candidate partial = null;
        ReadResult partialResult = null;
        for (var candidate : collectCandidates(file.getFileName().toString(), clientSchemas)) {
            var entry = candidate.entry();
            var schema = candidate.schema();
            if (schema == null) {
                attempts.add(new Attempt("", entry.source(), Outcome.SKIPPED, 0, -1, candidate.problem()));
                continue;
            }
            var skip = findSkipReason(spec, schema, names);
            if (skip != null) {
                attempts.add(new Attempt(schema.getTitle(), entry.source(), Outcome.SKIPPED, 0, -1, skip));
                continue;
            }
            ReadResult result;
            try {
                result = SchemaReader.of(schema, wanted).read(table, names, Map.of());
            } catch (RuntimeException failure) {
                log.error("Reading {} by {} failed", file, schema, failure);
                attempts.add(new Attempt(
                        schema.getTitle(), entry.source(), Outcome.FAILED, 0, -1, "the reader failed: " + failure));
                continue;
            }
            var rows = result.rows().size();
            var failure = result.failure();
            if (result.state() == ReadState.FAILED) {
                attempts.add(new Attempt(
                        schema.getTitle(),
                        entry.source(),
                        Outcome.FAILED,
                        rows,
                        failure != null ? failure.offset() : -1,
                        failure != null ? failure.describe() : "it stopped early"));
                continue;
            }
            var problem = checkRows(spec, result.rows());
            if (problem != null) {
                attempts.add(new Attempt(
                        schema.getTitle(),
                        entry.source(),
                        Outcome.CHECK_FAILED,
                        rows,
                        -1,
                        "it read the table, but the values make no sense: " + problem));
                continue;
            }
            if (result.state() == ReadState.WHOLE) {
                attempts.add(new Attempt(
                        schema.getTitle(),
                        entry.source(),
                        Outcome.READ,
                        rows,
                        table.length,
                        "it read the whole table"));
                var report = new TableReport(
                        spec.id(), file, State.READ, schema.getTitle(), entry.source(), rows, "", "", attempts);
                return store(new TableData(result.rows(), result.values(), result.constants(), schema, report), client);
            }
            attempts.add(new Attempt(
                    schema.getTitle(),
                    entry.source(),
                    Outcome.PARTIAL,
                    rows,
                    failure != null ? failure.offset() : -1,
                    "it read " + rows + " of " + result.rowCount() + " rows, up to a damaged tail: "
                            + (failure != null ? failure.describe() : "")));
            if (partialResult == null || rows > partialResult.rows().size()) {
                partial = candidate;
                partialResult = result;
            }
        }
        if (partial != null) {
            var schema = partial.schema();
            var rows = partialResult.rows().size();
            var report = new TableReport(
                    spec.id(),
                    file,
                    State.PARTIAL,
                    schema.getTitle(),
                    partial.entry().source(),
                    rows,
                    "",
                    "the last " + (partialResult.rowCount() - rows) + " of " + partialResult.rowCount()
                            + " rows are damaged and not read",
                    attempts);
            log.info("{}: {}", file, report.problem());
            return store(
                    new TableData(
                            partialResult.rows(), partialResult.values(), partialResult.constants(), schema, report),
                    client);
        }
        var report = new TableReport(spec.id(), file, State.NOT_READ, "", "", 0, spec.lost(), "", attempts);
        log.warn("{} fits no schema: {}", file, describe(attempts));
        return store(new TableData(List.of(), Map.of(), Map.of(), null, report), client);
    }

    public static @Nullable Trace trace(TableSpec spec, Path system, WorldVariant variant, String title, int row) {
        return trace(spec, system, variant, title, null, row);
    }

    public static @Nullable Trace trace(
            TableSpec spec, Path system, WorldVariant variant, String title, @Nullable String source, int row) {
        var file = findTable(spec, system, variant);
        if (file == null) {
            return null;
        }
        byte[] table;
        try {
            table = L2DataFile.read(file);
        } catch (IOException | RuntimeException failure) {
            return null;
        }
        for (var candidate : collectCandidates(file.getFileName().toString(), getClientFolder(system))) {
            var schema = candidate.schema();
            if (schema != null
                    && schema.getTitle().equalsIgnoreCase(title)
                    && (source == null || source.equals(candidate.entry().source()))) {
                return SchemaReader.of(schema, Set.of())
                        .trace(table, readGameDataNames(system, variant), Map.of(), row);
            }
        }
        return null;
    }

    public static List<Listed> listSchemas(TableSpec spec, @Nullable Path system) {
        var name = spec.fileName();
        if (system != null) {
            var file = findTable(spec, system, WorldVariant.MAIN);
            if (file != null) {
                name = file.getFileName().toString();
            }
        }
        if (name.equals(spec.fileName()) && spec.localized()) {
            name = name.substring(0, name.length() - ".dat".length()) + "-e.dat";
        }
        var listed = new ArrayList<Listed>();
        for (var candidate : collectCandidates(name, system == null ? null : getClientFolder(system))) {
            var entry = candidate.entry();
            var written = entry.schema();
            var title = written != null ? written.getTitle() : "";
            listed.add(new Listed(title, entry.source(), candidate.schema(), candidate.problem()));
        }
        return List.copyOf(listed);
    }

    public static List<TableReport> check(Path system, WorldVariant variant) {
        var checked = new ArrayList<TableReport>();
        for (var spec : Tables.ALL) {
            checked.add(read(spec, system, variant).report());
        }
        return checked;
    }

    public static @Nullable TableReport getLastReport(Path file) {
        return reports.get(file.toAbsolutePath().normalize());
    }

    public static List<TableReport> getLastReports() {
        var all = new ArrayList<>(reports.values());
        all.sort(Comparator.comparing(report -> report.file().toString()));
        return all;
    }

    public static List<Path> saveBuiltIn(Path folder) throws IOException {
        Files.createDirectories(folder);
        var written = new ArrayList<Path>();
        for (var file : BuiltIns.FILES) {
            var target = folder.resolve(file);
            Files.writeString(target, BuiltIns.TEXTS.get(file), StandardCharsets.UTF_8);
            written.add(target);
        }
        return written;
    }

    public static List<String> listBrokenSchemas(@Nullable Path system) {
        var messages = new ArrayList<String>();
        for (var snapshot : loadFolders(system == null ? null : getClientFolder(system))) {
            messages.addAll(snapshot.problems());
            for (var entry : snapshot.entries()) {
                if (entry.problem() != null) {
                    messages.add(entry.problem().getMessage());
                }
            }
        }
        return messages;
    }

    static int getCachedFolderCount() {
        return folders.size();
    }

    static int getCachedNamesCount() {
        return nameCache.size();
    }

    private static List<Candidate> collectCandidates(String fileName, @Nullable Path clientSchemas) {
        var mainName = toMainName(fileName);
        var entries = new ArrayList<Entry>();
        for (var snapshot : loadFolders(clientSchemas)) {
            entries.addAll(snapshot.entries());
        }
        entries.addAll(BuiltIns.ENTRIES);
        var matching = new ArrayList<Entry>();
        var pool = new ArrayList<Schema>();
        for (var entry : entries) {
            if (entry.targets(fileName, mainName)) {
                matching.add(entry);
                if (entry.schema() != null) {
                    pool.add(entry.schema());
                }
            }
        }
        var candidates = new ArrayList<Candidate>();
        for (var entry : matching) {
            var schema = entry.schema();
            if (schema == null) {
                var problem = entry.problem();
                candidates.add(new Candidate(entry, null, problem != null ? problem.getMessage() : "it has a mistake"));
                continue;
            }
            try {
                candidates.add(new Candidate(entry, schema.resolve(pool), ""));
            } catch (SchemaException mistake) {
                candidates.add(new Candidate(entry, null, mistake.getMessage()));
            } catch (RuntimeException failure) {
                log.error("Resolving {} failed", entry.source(), failure);
                candidates.add(new Candidate(entry, null, "the schema cannot be used: " + failure));
            }
        }
        return candidates;
    }

    private static @Nullable String findSkipReason(TableSpec spec, Schema schema, List<String> names) {
        var problem = SchemaKinds.findProblem(schema, spec);
        if (problem != null) {
            return problem;
        }
        if (schema.needsGameDataNames() && names.isEmpty()) {
            return "its name fields need L2GameDataName.dat, which this client does not have";
        }
        return null;
    }

    private static @Nullable String checkRows(TableSpec spec, List<Map<String, Object>> rows) {
        var check = spec.check();
        if (check == null) {
            return null;
        }
        try {
            return check.findProblem(rows.subList(0, Math.min(rows.size(), TableSpec.CHECKED_ROWS)));
        } catch (RuntimeException failure) {
            return "the check failed: " + failure;
        }
    }

    private static TableData store(TableData data, boolean client) {
        if (client) {
            reports.put(data.report().file().toAbsolutePath().normalize(), data.report());
        }
        return data;
    }

    private static List<SchemaFolder.Snapshot> loadFolders(@Nullable Path clientSchemas) {
        var snapshots = new ArrayList<SchemaFolder.Snapshot>(2);
        if (clientSchemas != null) {
            SchemaFolder folder;
            synchronized (folders) {
                folder = folders.computeIfAbsent(clientSchemas.toAbsolutePath().normalize(), SchemaFolder::new);
            }
            snapshots.add(folder.load());
        }
        var user = userSchemas;
        if (user != null) {
            snapshots.add(user.load());
        }
        return snapshots;
    }

    private static @Nullable Path findLocalized(Path folder, String stem) {
        var english = WorldVariant.MAIN.find(folder, stem + "-e.dat");
        if (english != null) {
            return english;
        }
        var prefix = (stem + "-").toLowerCase(Locale.ROOT);
        try (var files = Files.list(folder)) {
            return files.filter(path -> {
                        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith(prefix) && name.endsWith(".dat");
                    })
                    .min(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElse(null);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private static String toMainName(String fileName) {
        var lower = fileName.toLowerCase(Locale.ROOT);
        for (var variant : WorldVariant.values()) {
            var suffix = variant.getTableSuffix().toLowerCase(Locale.ROOT);
            if (suffix.isEmpty()) {
                continue;
            }
            var at = lower.lastIndexOf(suffix);
            if (at > 0) {
                var rest = lower.substring(at + suffix.length());
                if (rest.startsWith(".") || rest.startsWith("-")) {
                    return fileName.substring(0, at) + fileName.substring(at + suffix.length());
                }
            }
        }
        return fileName;
    }

    private static String describe(List<Attempt> attempts) {
        var lines = new ArrayList<String>();
        for (var attempt : attempts) {
            lines.add(attempt.title() + " (" + attempt.source() + "): " + attempt.reason());
        }
        return String.join("; ", lines);
    }
}
