package com.rost.cruma.viewer.cli;

import com.rost.cruma.core.schema.TableReport;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.core.schema.Trace;
import com.rost.cruma.core.world.WorldVariant;
import com.rost.cruma.viewer.config.ClientProfiles;
import com.rost.cruma.viewer.config.ConfigLoader;
import com.rost.cruma.viewer.config.ConfigurationProblem;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.ClientTables.Chosen;
import com.rost.cruma.viewer.launch.ClientTables.Key;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

public final class TablesCommand {
    public static final int ALL_READ = 0;

    public static final int NOT_READ = 1;

    public static final int WRONG_ARGUMENT = 2;

    private static final int STATE_COLUMN = 13;
    private static final int TYPE_COLUMN = 16;
    private static final int BYTE_COLUMN = 10;

    private TablesCommand() {}

    public record Request(
            @Nullable Path config,
            @Nullable Path client,
            @Nullable WorldVariant variant,
            @Nullable String table,
            @Nullable Integer row,
            @Nullable String schema,
            @Nullable Path saveBuiltIn,
            boolean help) {}

    public static void main(String[] arguments) {
        var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        System.setProperty("java.awt.headless", "true");
        var code = run(arguments, out, System.err);
        out.flush();
        System.exit(code);
    }

    public static int run(String[] arguments, PrintStream out, PrintStream err) {
        Request request;
        try {
            request = parse(arguments);
        } catch (ConfigurationProblem | InvalidPathException wrong) {
            err.println(wrong.getMessage());
            err.println();
            err.println(usage());
            return WRONG_ARGUMENT;
        }
        if (request.help()) {
            out.println(usage());
            return ALL_READ;
        }
        try {
            var loader = new ConfigLoader(new ConfigLoader.Options(request.config(), false, false));
            TableSchemas.setUserFolder(ClientTables.userFolderOf(loader.getConfigDirectory()));
            if (request.saveBuiltIn() != null) {
                return saveBuiltIn(request.saveBuiltIn(), out, err);
            }
            return check(request, loader, out);
        } catch (ConfigurationProblem wrong) {
            err.println(firstLine(wrong.getMessage()));
            return WRONG_ARGUMENT;
        } catch (RuntimeException failure) {
            err.println("Cruma tables stopped: " + firstLine(String.valueOf(failure.getMessage())));
            return WRONG_ARGUMENT;
        }
    }

    public static Request parse(String[] arguments) {
        Path config = null;
        Path client = null;
        WorldVariant variant = null;
        String table = null;
        Integer row = null;
        String schema = null;
        Path saveBuiltIn = null;
        var help = false;
        for (var i = 0; i < arguments.length; i++) {
            var argument = arguments[i];
            switch (argument) {
                case "-c", "--config" -> config = Path.of(valueOf(arguments, i++, "a settings folder"));
                case "--client" -> client = Path.of(valueOf(arguments, i++, "the folder of a client"));
                case "--variant" -> variant = parseVariant(valueOf(arguments, i++, WorldVariant.describeKeys()));
                case "--table" -> table = valueOf(arguments, i++, "a table, such as npcgrp.dat");
                case "--row" -> row = parseRow(valueOf(arguments, i++, "a row from 0"));
                case "--schema" -> schema = valueOf(arguments, i++, "the title of a schema, such as Interlude");
                case "--save-built-in" -> saveBuiltIn = Path.of(valueOf(arguments, i++, "a folder"));
                case "-h", "--help" -> help = true;
                default -> {
                    if (argument.startsWith("--config=")) {
                        config = Path.of(argument.substring("--config=".length()));
                    } else {
                        throw new ConfigurationProblem("Unknown option '" + argument + "'.");
                    }
                }
            }
        }
        if (row != null && table == null) {
            throw new ConfigurationProblem("--row needs --table, such as --table npcgrp.dat --row 0.");
        }
        if (schema != null && row == null) {
            throw new ConfigurationProblem("--schema names the schema that reads a row: give --table and --row too.");
        }
        return new Request(config, client, variant, table, row, schema, saveBuiltIn, help);
    }

    public static String usage() {
        return """
            Usage: cruma tables [options]

            Says how Cruma reads the tables of a client (system/*.dat): which schema read each table, and for a table
            that was not read, how far each schema got. It opens no window and writes nothing.

            Options:
              -c, --config <dir>       the settings folder (default: the OS user configuration directory)
                  --client <dir>       the client folder, which holds system (default: the client of the settings)
                  --variant <world>    main, classic or classic_aden (default: the client's world in the settings)
                  --table <file>       only this table, such as npcgrp.dat, with every schema it tried
                  --row <n>            with --table: every field of row n, from 0, as the schema reads it.
                                       -1 shows the fields outside the rows
                  --schema <title>     with --row: the schema to read with, such as "Interlude and Gracia",
                                       the first of that title in the order Cruma tries them (default: the
                                       schema that read the table, or the one that came closest)
                  --save-built-in <dir>  write the built-in schemas into this folder, to read or to copy. Not into a
                                       folder Cruma reads schemas from

            Exit code: 0 when every table the client has was read (a table with a damaged tail counts as read),
            1 when one was not, 2 for a wrong argument, a folder that is not a client, or a failure.\
            """;
    }

    private static int saveBuiltIn(Path folder, PrintStream out, PrintStream err) {
        var refused = ClientTables.findSaveProblem(folder);
        if (refused != null) {
            err.println(refused);
            return WRONG_ARGUMENT;
        }
        try {
            var written = TableSchemas.saveBuiltIn(folder);
            out.println("Wrote " + written.size() + " built-in schemas into " + folder.toAbsolutePath());
            return ALL_READ;
        } catch (IOException | RuntimeException failure) {
            err.println("The built-in schemas cannot be written into " + folder + ": "
                    + firstLine(String.valueOf(failure.getMessage())));
            return WRONG_ARGUMENT;
        }
    }

    private static int check(Request request, ConfigLoader loader, PrintStream out) {
        var settings = request.client() != null && request.variant() != null ? null : loader.readSettings();
        var client = request.client();
        if (client == null) {
            var configured = settings.getString("viewer.world.client_path");
            if (configured.isBlank()) {
                throw new ConfigurationProblem(
                        "No client is set in the settings. Name one with --client <folder>, the folder that holds"
                                + " system.");
            }
            client = Path.of(configured);
        }
        client = client.toAbsolutePath().normalize();
        if (!Files.isDirectory(client)) {
            throw new ConfigurationProblem(client + " is not a folder. --client names the folder that holds system.");
        }
        var variant = request.variant() != null
                ? request.variant()
                : ClientProfiles.variantFor(settings, client, loader.getConfigDirectory());
        var key = Key.of(client, variant);
        if (!Files.isDirectory(key.system())) {
            throw new ConfigurationProblem(client + " has no system folder, so it is not the folder of a client.");
        }
        if (!holdsTables(key)) {
            throw new ConfigurationProblem(client + " is not the folder of a client: its system folder holds none of"
                    + " the tables Cruma reads, such as npcgrp.dat.");
        }
        if (request.table() == null) {
            out.println("Client tables of " + client + ", " + variant.getLabel() + " world");
            var checked = ClientTables.check(key, (done, total, file) -> {});
            printTables(checked, true, out);
            printProblems(checked, out);
            return exitCode(checked.reports());
        }
        var spec = ClientTables.findSpec(request.table(), key.system(), variant);
        if (spec == null) {
            throw new ConfigurationProblem("Cruma reads no table " + request.table() + ". It reads " + listTables());
        }
        var found = TableSchemas.findTable(spec, key.system(), variant);
        if (found == null) {
            throw new ConfigurationProblem("This client has no " + ClientTables.missingName(spec) + ".");
        }
        var asked = request.table().strip();
        if (asked.contains("-") && !found.getFileName().toString().equalsIgnoreCase(asked)) {
            throw new ConfigurationProblem("This client has no " + asked + ". It has " + found.getFileName() + ".");
        }
        out.println("Client tables of " + client + ", " + variant.getLabel() + " world");
        var report = TableSchemas.read(spec, key.system(), variant).report();
        var checked = new ClientTables.Check(
                key,
                List.of(report),
                TableSchemas.listBrokenSchemas(key.system()),
                ClientTables.findKeyProblems(key.system()),
                false);
        printTables(checked, false, out);
        printAttempts(report, key.system(), out, true);
        if (request.row() != null) {
            var chosen = ClientTables.chooseSchema(spec, key.system(), report, request.schema());
            if (chosen == null) {
                throw new ConfigurationProblem(
                        request.schema() == null
                                ? "No schema of " + spec.fileName() + " could be tried, so there is no row to show."
                                : "No schema of " + spec.fileName() + " has the title " + request.schema()
                                        + ". The titles are in the list above.");
            }
            var trace = TableSchemas.trace(spec, key.system(), variant, chosen.title(), chosen.source(), request.row());
            if (trace == null) {
                throw new ConfigurationProblem(
                        spec.fileName() + " cannot be read by " + chosen.title() + ", so there is no row to show.");
            }
            out.println();
            printTrace(trace, chosen, key.system(), report.file().getFileName().toString(), out);
        }
        printProblems(checked, out);
        return exitCode(List.of(report));
    }

    private static void printTables(ClientTables.Check checked, boolean attempts, PrintStream out) {
        var system = checked.key().system();
        var width = 0;
        for (var report : checked.reports()) {
            width = Math.max(width, fileOf(report).length());
        }
        for (var report : checked.reports()) {
            var detail = ClientTables.describe(report, system);
            out.println((pad(ClientTables.describeState(report.state()), STATE_COLUMN)
                            + pad(fileOf(report), width + 2)
                            + detail)
                    .stripTrailing());
        }
        out.println(checked.describeCounts());
        if (!attempts) {
            return;
        }
        for (var report : checked.reports()) {
            if (report.state() == State.NOT_READ || report.state() == State.PARTIAL) {
                printAttempts(report, system, out, false);
            }
        }
    }

    private static void printAttempts(TableReport report, Path system, PrintStream out, boolean always) {
        if (report.attempts().isEmpty()) {
            var unopened = ClientTables.describeOpenProblem(report);
            if ((always || !unopened.isEmpty()) && report.state() != State.NO_FILE) {
                out.println();
                out.println(fileOf(report) + ": no schema was tried. "
                        + (unopened.isEmpty() ? ClientTables.shortenPaths(report.problem(), system) : unopened));
            }
            return;
        }
        out.println();
        out.println(fileOf(report) + ", the schemas in the order Cruma tried them:");
        for (var attempt : report.attempts()) {
            out.println("  " + ClientTables.shortenPaths(ClientTables.describe(attempt, system), system));
        }
    }

    private static void printProblems(ClientTables.Check checked, PrintStream out) {
        if (!checked.schemaProblems().isEmpty()) {
            out.println();
            out.println("Schema files with a mistake:");
            for (var problem : checked.schemaProblems()) {
                out.println(
                        "  " + ClientTables.shortenPaths(problem, checked.key().system()));
            }
        }
        if (!checked.keyProblems().isEmpty()) {
            out.println();
            out.println("Lines of keys.txt that could not be read:");
            for (var problem : checked.keyProblems()) {
                out.println(
                        "  " + ClientTables.shortenPaths(problem, checked.key().system()));
            }
        }
    }

    private static void printTrace(Trace trace, Chosen chosen, Path system, String file, PrintStream out) {
        var row = trace.row() < 0 ? "The fields outside the rows" : "Row " + trace.row();
        out.println(row + " of " + file + " by " + chosen.title() + " · "
                + ClientTables.describeSource(chosen.source(), system) + ":");
        if (!chosen.others().isEmpty()) {
            var others = new ArrayList<String>();
            for (var source : chosen.others()) {
                others.add(ClientTables.describeSource(source, system));
            }
            out.println("Other schemas share the title " + chosen.title() + ": " + String.join(" and ", others)
                    + ". This is the first that Cruma tries.");
        }
        var lines = ClientTables.listTraceLines(trace);
        var width = "field".length();
        for (var line : lines) {
            width = Math.max(width, line.path().length());
        }
        out.println(("  " + pad("field", width + 2) + pad("type", TYPE_COLUMN) + pad("byte", BYTE_COLUMN) + "value"));
        for (var line : lines) {
            out.println(((line.failed() ? "! " : "  ")
                            + pad(line.path(), width + 2)
                            + pad(line.type(), TYPE_COLUMN)
                            + pad(Integer.toString(line.offset()), BYTE_COLUMN)
                            + line.value())
                    .stripTrailing());
        }
        if (lines.isEmpty()) {
            out.println("  No field: the row is past the last row.");
        }
        var failure = trace.failure();
        if (failure != null) {
            out.println("Stopped at " + ClientTables.shortenPaths(failure.describe(), system));
        }
    }

    private static boolean holdsTables(Key key) {
        for (var spec : Tables.ALL) {
            if (TableSchemas.findTable(spec, key.system(), key.variant()) != null) {
                return true;
            }
        }
        return false;
    }

    private static int exitCode(List<TableReport> reports) {
        for (var report : reports) {
            if (report.state() == State.NOT_READ) {
                return NOT_READ;
            }
        }
        return ALL_READ;
    }

    private static String fileOf(TableReport report) {
        return report.file().getFileName().toString();
    }

    private static String listTables() {
        var names = new ArrayList<String>();
        for (TableSpec spec : Tables.ALL) {
            names.add(spec.fileName());
        }
        return String.join(", ", names) + ".";
    }

    private static String firstLine(String text) {
        var end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String valueOf(String[] arguments, int at, String what) {
        if (at + 1 >= arguments.length || arguments[at + 1].isBlank()) {
            throw new ConfigurationProblem(arguments[at] + " needs " + what + ".");
        }
        return arguments[at + 1];
    }

    private static WorldVariant parseVariant(String word) {
        try {
            return WorldVariant.parse(word);
        } catch (IllegalArgumentException wrong) {
            throw new ConfigurationProblem("--variant is " + WorldVariant.describeKeys() + ", not " + word + ".");
        }
    }

    private static int parseRow(String word) {
        try {
            var row = Integer.parseInt(word.strip());
            if (row < -1) {
                throw new ConfigurationProblem("--row is a row from 0, or -1 for the fields outside the rows.");
            }
            return row;
        } catch (NumberFormatException notNumber) {
            throw new ConfigurationProblem("--row is a row from 0, not " + word + ".");
        }
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }
}
