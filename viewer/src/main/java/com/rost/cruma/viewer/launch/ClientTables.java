package com.rost.cruma.viewer.launch;

import com.rost.cruma.core.schema.Schema;
import com.rost.cruma.core.schema.TableReport;
import com.rost.cruma.core.schema.TableReport.Attempt;
import com.rost.cruma.core.schema.TableReport.Outcome;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSchemas.Listed;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.schema.Trace;
import com.rost.cruma.core.schema.TraceField;
import com.rost.cruma.core.unreal.L2DataFile;
import com.rost.cruma.core.world.WorldVariant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

public final class ClientTables {
    public static final String USER_FOLDER = "schemas";

    public static final String BUILT_IN_FOLDER = "built-in schemas";

    private static final Logger log = LogManager.getLogger(ClientTables.class);

    private static final Pattern ROW_OF_FAILURE = Pattern.compile("^row (\\d+), ");

    private static final int LONGEST_VALUE = 120;

    private static final List<String> KEY_FAILURES = List.of(
            "decrypted to no payload",
            "invalid uncompressed size",
            "inflated to",
            "holds more data than its header says",
            "corrupt zlib stream");

    private static final Map<Key, Check> checked = new ConcurrentHashMap<>();
    private static final Map<Key, Future<?>> queued = new ConcurrentHashMap<>();
    private static final Map<Key, Running> running = new ConcurrentHashMap<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "cruma-client-tables");
        thread.setDaemon(true);
        return thread;
    });

    private ClientTables() {}

    public record Key(Path system, WorldVariant variant) {
        public Key {
            system = system.toAbsolutePath().normalize();
        }

        public static Key of(Path client, WorldVariant variant) {
            return new Key(findSystem(client), variant);
        }
    }

    public record Check(
            Key key,
            List<TableReport> reports,
            List<String> schemaProblems,
            List<String> keyProblems,
            boolean complete) {
        public Check {
            reports = List.copyOf(reports);
            schemaProblems = List.copyOf(schemaProblems);
            keyProblems = List.copyOf(keyProblems);
        }

        public int count(State state) {
            var count = 0;
            for (var report : reports) {
                if (report.state() == state) {
                    count++;
                }
            }
            return count;
        }

        public int countTroubled() {
            return count(State.NOT_READ) + count(State.PARTIAL);
        }

        public String describeCounts() {
            var parts = new ArrayList<String>();
            for (var state : State.values()) {
                var count = count(state);
                if (count > 0) {
                    parts.add(count + " " + describeState(state));
                }
            }
            return parts.isEmpty() ? "No table read yet" : String.join(" · ", parts);
        }
    }

    public static final class Running {
        private final int total = Tables.ALL.size();
        private volatile int done;
        private volatile String file = "";

        public int getDone() {
            return done;
        }

        public int getTotal() {
            return total;
        }

        public String getFile() {
            return file;
        }
    }

    public record TraceLine(String path, String type, int offset, String value, boolean failed) {}

    public record Chosen(String title, String source, List<String> others) {
        public Chosen {
            others = List.copyOf(others);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void report(int done, int total, String file);
    }

    public static Path findSystem(Path client) {
        try (var entries = Files.list(client)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                if (entry.getFileName().toString().equalsIgnoreCase("system") && Files.isDirectory(entry)) {
                    return entry;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
        }
        return client.resolve("system");
    }

    public static Path userFolderOf(Path configDirectory) {
        return configDirectory.toAbsolutePath().normalize().resolve(USER_FOLDER);
    }

    public static Check check(Key key, Progress progress) {
        var reports = new ArrayList<TableReport>();
        var total = Tables.ALL.size();
        for (var spec : Tables.ALL) {
            progress.report(reports.size(), total, spec.fileName());
            reports.add(TableSchemas.read(spec, key.system(), key.variant()).report());
        }
        progress.report(total, total, "");
        var result = new Check(
                key, reports, TableSchemas.listBrokenSchemas(key.system()), findKeyProblems(key.system()), true);
        checked.put(key, result);
        return result;
    }

    public static @Nullable Check getChecked(Key key) {
        return checked.get(key);
    }

    public static Check findLast(Key key) {
        var reports = new ArrayList<TableReport>();
        for (var spec : Tables.ALL) {
            var file = TableSchemas.findTable(spec, key.system(), key.variant());
            var where = file != null ? file : key.system().resolve(missingName(spec));
            var report = TableSchemas.getLastReport(where);
            if (report != null) {
                reports.add(report);
            }
        }
        var done = reports.size() == Tables.ALL.size();
        return new Check(
                key, reports, TableSchemas.listBrokenSchemas(key.system()), findKeyProblems(key.system()), done);
    }

    public static synchronized void start(Key key) {
        if (queued.containsKey(key) || running.containsKey(key)) {
            return;
        }
        for (var other : List.copyOf(queued.entrySet())) {
            if (other.getValue().cancel(false)) {
                queued.remove(other.getKey());
            }
        }
        queued.put(key, worker.submit(() -> run(key)));
    }

    public static @Nullable Running getRunning(Key key) {
        return running.get(key);
    }

    public static boolean isQueued(Key key) {
        return queued.containsKey(key);
    }

    public static boolean isUsed(TableReport report, Attempt attempt) {
        return (attempt.outcome() == Outcome.READ || attempt.outcome() == Outcome.PARTIAL)
                && !report.schemaTitle().isEmpty()
                && attempt.title().equals(report.schemaTitle())
                && attempt.source().equals(report.schemaSource());
    }

    public static String describeState(State state) {
        return switch (state) {
            case READ -> "read";
            case PARTIAL -> "partly read";
            case NOT_READ -> "not read";
            case NOT_USED -> "not used";
            case NO_FILE -> "no file";
        };
    }

    public static String describeSource(String source, Path system) {
        if (source.isEmpty() || source.equals(TableReport.BUILT_IN)) {
            return source;
        }
        Path file;
        try {
            file = Path.of(source).toAbsolutePath().normalize();
        } catch (InvalidPathException notPath) {
            return source;
        }
        var name = file.getFileName() == null ? source : file.getFileName().toString();
        if (file.startsWith(TableSchemas.getClientFolder(system))) {
            return "this client's folder, " + name;
        }
        var user = TableSchemas.getUserFolder();
        if (user != null && file.startsWith(user.toAbsolutePath().normalize())) {
            return "your folder, " + name;
        }
        return source;
    }

    public static String describe(TableReport report, Path system) {
        return switch (report.state()) {
            case READ ->
                report.schemaTitle() + " · " + describeSource(report.schemaSource(), system) + " · "
                        + countRows(report.rows());
            case PARTIAL ->
                report.schemaTitle() + " · " + describeSource(report.schemaSource(), system) + " · "
                        + countRows(report.rows()) + " · " + report.problem();
            case NOT_READ -> {
                var unopened = describeOpenProblem(report);
                yield unopened.isEmpty() ? report.lost() : unopened;
            }
            case NOT_USED -> report.problem();
            case NO_FILE -> "";
        };
    }

    public static String describeOpenProblem(TableReport report) {
        var problem = report.problem();
        if (report.state() != State.NOT_READ || !report.attempts().isEmpty() || problem.isEmpty()) {
            return "";
        }
        if (problem.contains("is not a Lineage2Ver411")) {
            return "The file is not a table of the game's form: it has no Lineage2Ver411, 412 or 413 head.";
        }
        for (var words : KEY_FAILURES) {
            if (problem.contains(words)) {
                return "The file cannot be opened with any key Cruma knows. A keys.txt can name its key.";
            }
        }
        return "The file cannot be read from the disk.";
    }

    public static String describe(Attempt attempt, Path system) {
        var title = attempt.title().isEmpty() ? "A schema with a mistake" : attempt.title();
        var source = describeSource(attempt.source(), system);
        var reason = attempt.source().isEmpty() || attempt.source().equals(TableReport.BUILT_IN)
                ? attempt.reason()
                : attempt.reason().replace(attempt.source(), nameOf(attempt.source()));
        var how = switch (attempt.outcome()) {
            case READ -> "read the whole table, " + countRows(attempt.rowsRead());
            case PARTIAL, CHECK_FAILED -> reason;
            case FAILED -> "stopped at " + reason;
            case SKIPPED -> "not tried: " + reason;
        };
        return title + " · " + source + ": " + how;
    }

    public static @Nullable Attempt findClosest(TableReport report) {
        Attempt best = null;
        for (var attempt : report.attempts()) {
            if (attempt.outcome() == Outcome.SKIPPED || attempt.title().isEmpty()) {
                continue;
            }
            if (isUsed(report, attempt)) {
                return attempt;
            }
            if (best == null
                    || attempt.rowsRead() > best.rowsRead()
                    || (attempt.rowsRead() == best.rowsRead() && attempt.offset() > best.offset())) {
                best = attempt;
            }
        }
        return best;
    }

    public static int findFailingRow(Attempt attempt) {
        if (attempt.outcome() != Outcome.FAILED && attempt.outcome() != Outcome.PARTIAL) {
            return 0;
        }
        var reason = attempt.reason();
        var inRows = reason.startsWith("it read ") ? reason.substring(reason.indexOf(": ") + 2) : reason;
        var matcher = ROW_OF_FAILURE.matcher(inRows);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return attempt.offset() >= 0 ? -1 : 0;
    }

    public static @Nullable Chosen chooseSchema(
            TableSpec spec, Path system, TableReport report, @Nullable String asked) {
        if (asked == null) {
            if (!report.schemaTitle().isEmpty()) {
                return new Chosen(report.schemaTitle(), report.schemaSource(), List.of());
            }
            var closest = findClosest(report);
            return closest == null ? null : new Chosen(closest.title(), closest.source(), List.of());
        }
        Listed first = null;
        var others = new ArrayList<String>();
        for (var listed : TableSchemas.listSchemas(spec, system)) {
            if (listed.schema() == null || !listed.title().equalsIgnoreCase(asked.strip())) {
                continue;
            }
            if (first == null) {
                first = listed;
            } else {
                others.add(listed.source());
            }
        }
        return first == null ? null : new Chosen(first.title(), first.source(), others);
    }

    public static List<TraceLine> listTraceLines(Trace trace) {
        var failure = trace.failure();
        var lines = new ArrayList<TraceLine>();
        var shown = false;
        for (var field : trace.fields()) {
            var failed =
                    failure != null && !failure.path().isEmpty() && field.path().equals(failure.path());
            shown |= failed;
            lines.add(new TraceLine(field.path(), field.type(), field.offset(), describeValue(field), failed));
        }
        if (failure != null && !shown) {
            var path = failure.path().isEmpty() ? "(the bytes left)" : failure.path();
            lines.add(new TraceLine(path, "", failure.offset(), "stopped here", true));
        }
        return lines;
    }

    public static String describeValue(TraceField field) {
        var value = field.value();
        if (value == null) {
            return "";
        }
        var text = value instanceof String string ? "\"" + string + "\"" : String.valueOf(value);
        text = text.replace("\r", "\\r").replace("\n", "\\n");
        return text.length() > LONGEST_VALUE ? text.substring(0, LONGEST_VALUE - 1) + "…" : text;
    }

    public static @Nullable String findSaveProblem(Path folder) {
        var target = folder.toAbsolutePath().normalize();
        var user = TableSchemas.getUserFolder();
        var inUser = user != null && target.startsWith(user.toAbsolutePath().normalize());
        var inClient = false;
        for (var index = 0; index + 1 < target.getNameCount(); index++) {
            inClient |= target.getName(index).toString().equalsIgnoreCase("cruma")
                    && target.getName(index + 1).toString().equalsIgnoreCase("schemas");
        }
        if (!inUser && !inClient) {
            return null;
        }
        return "Cruma reads the schemas of this folder, so copies there would come before the built-in schemas and"
                + " hide their later fixes. Choose another folder.";
    }

    public static @Nullable TableSpec findSpec(String word, @Nullable Path system, WorldVariant variant) {
        var wanted = word.strip().toLowerCase(Locale.ROOT);
        var bare = wanted.endsWith(".dat") ? wanted.substring(0, wanted.length() - 4) : wanted;
        for (var spec : Tables.ALL) {
            var file = spec.fileName().toLowerCase(Locale.ROOT);
            if (file.equals(wanted) || spec.id().toLowerCase(Locale.ROOT).equals(bare)) {
                return spec;
            }
            if (system != null) {
                var found = TableSchemas.findTable(spec, system, variant);
                if (found != null && found.getFileName().toString().equalsIgnoreCase(word.strip())) {
                    return spec;
                }
            }
        }
        var dash = bare.lastIndexOf('-');
        if (dash > 0) {
            return findSpec(bare.substring(0, dash), null, variant);
        }
        return null;
    }

    public static List<Schema> findBuiltIns(TableSpec spec) {
        var schemas = new ArrayList<Schema>();
        for (var schema : TableSchemas.getBuiltIns()) {
            if (schema.matchesFile(spec.fileName()) || schema.matchesFile(missingName(spec))) {
                schemas.add(schema);
            }
        }
        return schemas;
    }

    public static String missingName(TableSpec spec) {
        return spec.localized() ? spec.fileName().replace(".dat", "-e.dat") : spec.fileName();
    }

    public static String countRows(int rows) {
        return String.format(Locale.ROOT, "%,d", rows).replace(',', ' ') + (rows == 1 ? " row" : " rows");
    }

    public static String shortenPaths(String message, Path system) {
        var text = message;
        var client = TableSchemas.getClientFolder(system).getParent().getParent();
        if (client != null) {
            text = text.replace(client + java.io.File.separator, "");
        }
        var user = TableSchemas.getUserFolder();
        var settings = user == null ? null : user.toAbsolutePath().normalize().getParent();
        if (settings != null) {
            text = text.replace(settings + java.io.File.separator, "");
        }
        return text;
    }

    public static List<String> findKeyProblems(Path system) {
        try {
            return L2DataFile.findKeyProblems(system);
        } catch (RuntimeException unreadable) {
            return List.of("keys.txt cannot be read: " + unreadable.getMessage());
        }
    }

    private static void run(Key key) {
        var progress = new Running();
        running.put(key, progress);
        queued.remove(key);
        try {
            check(key, (done, total, file) -> {
                progress.done = done;
                progress.file = file;
            });
        } catch (RuntimeException failure) {
            log.error("Checking the tables of {} failed", key.system(), failure);
        } finally {
            running.remove(key);
        }
    }

    private static String nameOf(String source) {
        try {
            var name = Path.of(source).getFileName();
            return name == null ? source : name.toString();
        } catch (InvalidPathException notPath) {
            return source;
        }
    }
}
