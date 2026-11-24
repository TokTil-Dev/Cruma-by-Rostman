package com.rost.cruma.viewer.agent.export;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.rost.cruma.core.schema.TableReport;
import com.rost.cruma.core.schema.TableReport.Attempt;
import com.rost.cruma.core.schema.TableReport.State;
import com.rost.cruma.core.schema.TableSchemas;
import com.rost.cruma.core.schema.TableSchemas.Listed;
import com.rost.cruma.core.schema.TableSpec;
import com.rost.cruma.core.schema.Tables;
import com.rost.cruma.viewer.agent.ToolProblem;
import com.rost.cruma.viewer.launch.ClientTables;
import com.rost.cruma.viewer.launch.ClientTables.Key;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

final class TableAnswers {
    private static final long MOST_SCHEMA_BYTES = 1 << 20;

    private TableAnswers() {}

    static JSONObject tables(Key key, @Nullable String query, @Nullable Integer row, @Nullable String schema, int limit)
            throws ToolProblem {
        if (schema != null && row == null) {
            throw new ToolProblem("schema names the schema that reads a row: give row too.");
        }
        if (row != null && (query == null || query.isBlank())) {
            throw new ToolProblem("row reads one table: name it in query, such as npcgrp.dat.");
        }
        var specs = findSpecs(key, query);
        if (specs.isEmpty()) {
            throw new ToolProblem("No table of Cruma matches \"" + query + "\". The tables are " + listTables() + ".");
        }
        if (row != null && specs.size() != 1) {
            throw new ToolProblem("row reads one table: name it in query, such as npcgrp.dat. \"" + query
                    + "\" matches " + specs.size() + ".");
        }
        var reports = new ArrayList<TableReport>();
        if (specs.size() == Tables.ALL.size()) {
            reports.addAll(ClientTables.check(key, (done, total, file) -> {}).reports());
        } else {
            for (var spec : specs) {
                reports.add(TableSchemas.read(spec, key.system(), key.variant()).report());
            }
        }
        var answer = new JSONObject();
        answer.put("variant", key.variant().getKey());
        var rows = new JSONArray();
        for (var report : reports.subList(0, Math.min(limit, reports.size()))) {
            rows.add(describe(report, key.system(), specs.size() == 1));
        }
        answer.put("tables", rows);
        if (reports.size() > rows.size()) {
            answer.put("more", reports.size() - rows.size());
        }
        var counts = new ClientTables.Check(key, reports, List.of(), List.of(), false);
        answer.put("counts", counts.describeCounts());
        if (row != null) {
            answer.put("trace", trace(key, specs.getFirst(), reports.getFirst(), schema, row, limit));
        }
        var schemaProblems = TableSchemas.listBrokenSchemas(key.system());
        if (!schemaProblems.isEmpty()) {
            answer.put("schema_problems", schemaProblems);
        }
        var keyProblems = ClientTables.findKeyProblems(key.system());
        if (!keyProblems.isEmpty()) {
            answer.put("key_problems", keyProblems);
        }
        answer.put("folders", folders(key));
        if (counts.count(State.NOT_READ) > 0) {
            answer.put(
                    "next",
                    "To read a table, write a schema (docs/TABLE_SCHEMAS.md): kind schema with query the table gives"
                            + " the schemas to start from, and row shows where a schema stops.");
        }
        return answer;
    }

    static JSONObject schemas(Key key, @Nullable String query, int limit) throws ToolProblem {
        if (query == null || query.isBlank()) {
            throw new ToolProblem("kind schema needs query with a table, such as npcgrp.dat.");
        }
        var specs = findSpecs(key, query);
        if (specs.size() != 1) {
            throw new ToolProblem("kind schema reads one table: \"" + query + "\" matches " + specs.size()
                    + ". The tables are " + listTables() + ".");
        }
        var spec = specs.getFirst();
        var listed = TableSchemas.listSchemas(spec, key.system());
        var list = new JSONArray();
        for (var schema : listed.subList(0, Math.min(limit, listed.size()))) {
            list.add(describeSchema(schema, key.system()));
        }
        var answer = new JSONObject();
        var file = TableSchemas.findTable(spec, key.system(), key.variant());
        answer.put(
                "table",
                file == null
                        ? ClientTables.missingName(spec)
                        : file.getFileName().toString());
        if (file == null) {
            answer.put("note", "This client has no such table. The schemas are still the start of a new one.");
        }
        answer.put("needs", spec.required());
        if (!spec.optional().isEmpty()) {
            answer.put("uses", spec.optional());
        }
        answer.put("schemas", list);
        if (listed.size() > list.size()) {
            answer.put("more", listed.size() - list.size());
        }
        answer.put("folders", folders(key));
        answer.put(
                "how",
                "Write a .schema file into one of folders, such as a schema with `like <title>` and the fields that"
                        + " differ. Cruma reads a changed file at the next call: check it with kind table and this"
                        + " query.");
        return answer;
    }

    private static JSONObject describe(TableReport report, Path system, boolean attempts) {
        var row = new JSONObject();
        row.put("file", report.file().getFileName().toString());
        row.put("state", ClientTables.describeState(report.state()));
        if (!report.schemaTitle().isEmpty()) {
            row.put("schema", report.schemaTitle());
            row.put("source", ClientTables.describeSource(report.schemaSource(), system));
            row.put("rows", report.rows());
        }
        if (!report.lost().isEmpty()) {
            row.put("lost", report.lost());
        }
        var unopened = ClientTables.describeOpenProblem(report);
        if (!unopened.isEmpty()) {
            row.put("problem", unopened);
            row.put("reason", ClientTables.shortenPaths(report.problem(), system));
        } else if (report.state() == State.NOT_USED) {
            row.put("note", report.problem());
        } else if (!report.problem().isEmpty()) {
            row.put("problem", report.problem());
        }
        if (attempts || report.state() == State.NOT_READ || report.state() == State.PARTIAL) {
            var tried = new JSONArray();
            for (var attempt : report.attempts()) {
                tried.add(describe(attempt, system));
            }
            if (!tried.isEmpty()) {
                row.put("tried", tried);
            }
        }
        return row;
    }

    private static JSONObject describe(Attempt attempt, Path system) {
        var row = new JSONObject();
        row.put("schema", attempt.title());
        row.put("source", ClientTables.describeSource(attempt.source(), system));
        row.put("outcome", attempt.outcome().name().toLowerCase(Locale.ROOT));
        row.put("rows_read", attempt.rowsRead());
        if (attempt.offset() >= 0) {
            row.put("byte", attempt.offset());
        }
        row.put("reason", ClientTables.shortenPaths(attempt.reason(), system));
        return row;
    }

    private static JSONObject describeSchema(Listed listed, Path system) {
        var row = new JSONObject();
        row.put("title", listed.title());
        row.put("source", ClientTables.describeSource(listed.source(), system));
        if (!listed.source().equals(TableReport.BUILT_IN)) {
            row.put("path", listed.source());
        }
        var schema = listed.schema();
        var text = schema != null ? schema.getText() : readText(listed.source());
        if (text != null) {
            row.put("text", text);
        }
        if (!listed.problem().isEmpty()) {
            row.put("problem", ClientTables.shortenPaths(listed.problem(), system));
        }
        return row;
    }

    private static JSONObject trace(
            Key key, TableSpec spec, TableReport report, @Nullable String asked, int row, int limit)
            throws ToolProblem {
        var chosen = ClientTables.chooseSchema(spec, key.system(), report, asked);
        if (chosen == null) {
            throw new ToolProblem(
                    asked == null
                            ? "No schema of " + spec.fileName() + " could be tried, so no row can be read."
                            : "No schema of " + spec.fileName() + " has the title \"" + asked
                                    + "\". kind schema lists them.");
        }
        var trace = TableSchemas.trace(spec, key.system(), key.variant(), chosen.title(), chosen.source(), row);
        if (trace == null) {
            throw new ToolProblem(
                    spec.fileName() + " cannot be read by " + chosen.title() + ", so no row can be read. See tried.");
        }
        var answer = new JSONObject();
        answer.put("schema", chosen.title());
        answer.put("source", ClientTables.describeSource(chosen.source(), key.system()));
        if (!chosen.others().isEmpty()) {
            var others = new JSONArray();
            for (var source : chosen.others()) {
                others.add(ClientTables.describeSource(source, key.system()));
            }
            answer.put("same_title", others);
            answer.put("note", "Other schemas share this title. This is the first that Cruma tries.");
        }
        answer.put("row", row);
        var lines = ClientTables.listTraceLines(trace);
        var fields = new JSONArray();
        for (var index = 0; index < lines.size() && fields.size() < limit; index++) {
            var line = lines.get(index);
            var item = new JSONObject();
            item.put("path", line.path());
            item.put("type", line.type());
            item.put("byte", line.offset());
            item.put("value", line.value());
            if (index < trace.fields().size()) {
                item.put("size", trace.fields().get(index).size());
                item.put("where", trace.fields().get(index).where());
            }
            if (line.failed()) {
                item.put("failed", true);
            }
            fields.add(item);
        }
        answer.put("fields", fields);
        if (lines.size() > fields.size()) {
            answer.put("more", lines.size() - fields.size());
        }
        var failure = trace.failure();
        if (failure != null) {
            var stop = new JSONObject();
            stop.put("row", failure.row());
            stop.put("byte", failure.offset());
            stop.put("path", failure.path());
            stop.put("where", failure.where());
            stop.put("reason", failure.reason());
            answer.put("stopped", stop);
        }
        return answer;
    }

    private static JSONObject folders(Key key) {
        var folders = new JSONObject();
        folders.put("client", TableSchemas.getClientFolder(key.system()).toString());
        var user = TableSchemas.getUserFolder();
        if (user != null) {
            folders.put("yours", user.toString());
        }
        return folders;
    }

    private static List<TableSpec> findSpecs(Key key, @Nullable String query) {
        if (query == null || query.isBlank()) {
            return Tables.ALL;
        }
        var named = ClientTables.findSpec(query, key.system(), key.variant());
        if (named != null) {
            return List.of(named);
        }
        var words = query.toLowerCase(Locale.ROOT).split("\\s+");
        var found = new ArrayList<TableSpec>();
        for (var spec : Tables.ALL) {
            var text = (spec.fileName() + " " + spec.id()).toLowerCase(Locale.ROOT);
            var all = true;
            for (var word : words) {
                all &= text.contains(word);
            }
            if (all) {
                found.add(spec);
            }
        }
        return found;
    }

    private static String listTables() {
        var names = new ArrayList<String>();
        for (var spec : Tables.ALL) {
            names.add(spec.fileName());
        }
        return String.join(", ", names);
    }

    private static @Nullable String readText(String source) {
        try {
            var file = Path.of(source);
            if (!Files.isRegularFile(file) || Files.size(file) > MOST_SCHEMA_BYTES) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}
