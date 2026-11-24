package com.rost.cruma.core.schema;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SchemaReader {
    private final Schema schema;
    private final Program program;

    private SchemaReader(Schema schema, Set<String> wanted) {
        this.schema = schema;
        this.program = SchemaCompiler.compile(schema.getParts(), wanted);
    }

    public static SchemaReader of(Schema schema, Set<String> wanted) {
        if (!schema.isResolved()) {
            throw new IllegalStateException(schema + " is like \"" + schema.getLike() + "\": resolve it first");
        }
        return schema.readers.computeIfAbsent(Set.copyOf(wanted), names -> new SchemaReader(schema, names));
    }

    public Schema getSchema() {
        return schema;
    }

    public ReadResult read(byte[] table, List<String> names, Map<String, ? extends Number> context) {
        return program.read(table, names, context, schema.getConstants());
    }

    public Trace trace(byte[] table, List<String> names, Map<String, ? extends Number> context, int row) {
        return program.trace(table, names, context, row);
    }
}
