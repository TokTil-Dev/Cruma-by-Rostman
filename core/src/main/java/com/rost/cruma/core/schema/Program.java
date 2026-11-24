package com.rost.cruma.core.schema;

import com.rost.cruma.core.schema.Schema.Basic;
import com.rost.cruma.core.schema.Schema.Operator;
import com.rost.cruma.core.schema.Schema.Where;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

final class Program {
    static final int ROW_LIST_LIMIT = 100_000;
    static final int LIST_LIMIT = 2_000_000;
    static final int TEXT_LIMIT = 65_536;
    static final String END_MARK = "SafePackage";
    static final int HEAD = -1;
    static final long STEPS_PER_BYTE = 4;
    static final long EXTRA_STEPS = 100_000;

    private static final int NO_TRACE = Integer.MIN_VALUE;
    private static final byte[] MARK_BYTES = ((char) 12 + END_MARK + "\0").getBytes(StandardCharsets.ISO_8859_1);

    private static final VarHandle SHORTS =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INTS = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long[] NO_NUMBERS = new long[0];
    private static final boolean[] NO_FLAGS = new boolean[0];

    private final Group top;
    private final List<String> contextNames;

    Program(Group top, List<String> contextNames) {
        this.top = top;
        this.contextNames = contextNames;
    }

    static void run(Op[] ops, Cursor cursor, Frame frame) {
        cursor.step(ops.length);
        for (var op : ops) {
            op.read(cursor, frame);
            if (cursor.stop) {
                return;
            }
        }
    }

    ReadResult read(byte[] data, List<String> names, Map<String, ? extends Number> context, Map<String, Object> set) {
        var cursor = new Cursor(data, names, context, contextNames);
        var values = HashMap.<String, Object>newHashMap(top.keptSize());
        var frame = new Frame(null, top.slots(), values);
        try {
            run(top.ops(), cursor, frame);
        } catch (ReadError failure) {
            if (cursor.failedRow >= 0) {
                var partial = cursor.failedRow >= 100 && cursor.failedRow * 10L >= cursor.rowCount * 9L;
                return result(
                        cursor, values, set, partial ? ReadState.PARTIAL : ReadState.FAILED, cursor.describeFailure());
            }
            return result(cursor, values, set, ReadState.FAILED, locateHead(data, names, context, failure));
        }
        var after = cursor.pos;
        if (after < cursor.end && !endsWithMark(cursor)) {
            var failure = new ReadFailure(-1, after, "", describeTail(data, after), "");
            return result(cursor, values, set, ReadState.FAILED, failure);
        }
        return result(cursor, values, set, ReadState.WHOLE, null);
    }

    Trace trace(byte[] data, List<String> names, Map<String, ? extends Number> context, int row) {
        var cursor = new Cursor(data, names, context, contextNames);
        cursor.traceRow = row;
        var fields = new ArrayList<TraceField>();
        cursor.traced = fields;
        if (row == HEAD) {
            cursor.sink = fields;
        }
        var frame = new Frame(null, top.slots(), HashMap.newHashMap(top.keptSize()));
        try {
            run(top.ops(), cursor, frame);
        } catch (ReadError failure) {
            if (cursor.failedRow >= 0) {
                return new Trace(row, List.copyOf(fields), cursor.describeFailure());
            }
            var where = row == HEAD ? cursor.describeFailure(failure) : locateHead(data, names, context, failure);
            return new Trace(row, List.copyOf(fields), where);
        }
        var after = cursor.pos;
        if (row == HEAD && !cursor.stop && after < cursor.end && !endsWithMark(cursor)) {
            return new Trace(row, List.copyOf(fields), new ReadFailure(-1, after, "", describeTail(data, after), ""));
        }
        return new Trace(row, List.copyOf(fields), null);
    }

    private ReadFailure locateHead(
            byte[] data, List<String> names, Map<String, ? extends Number> context, ReadError first) {
        var cursor = new Cursor(data, names, context, contextNames);
        cursor.traceRow = HEAD;
        cursor.sink = new ArrayList<>();
        try {
            run(top.ops(), cursor, new Frame(null, top.slots(), HashMap.newHashMap(top.keptSize())));
        } catch (ReadError again) {
            return cursor.describeFailure(again);
        }
        return new ReadFailure(-1, 0, "", first.getMessage(), "");
    }

    private static ReadResult result(
            Cursor cursor,
            Map<String, Object> values,
            Map<String, Object> set,
            ReadState state,
            @Nullable ReadFailure failure) {
        var rows = cursor.rows == null ? List.<Map<String, Object>>of() : cursor.rows;
        return new ReadResult(values, rows, set, state, cursor.rowCount, failure);
    }

    private static String describeTail(byte[] data, int after) {
        var mark = MARK_BYTES.length;
        if (data.length - after > mark && Arrays.equals(data, data.length - mark, data.length, MARK_BYTES, 0, mark)) {
            var left = data.length - mark - after;
            return left + (left == 1 ? " byte stands" : " bytes stand") + " between the last field and the " + END_MARK
                    + " mark. A schema of your own passes over them with skip " + left + " after the rows";
        }
        return (data.length - after) + " bytes are left after the last field, and a table may end only with the "
                + END_MARK + " mark";
    }

    private static boolean endsWithMark(Cursor cursor) {
        try {
            var text = cursor.readText(Basic.FSTRING, true);
            return cursor.pos == cursor.end && END_MARK.equals(text);
        } catch (ReadError notMark) {
            return false;
        }
    }

    static final class ReadError extends RuntimeException {
        ReadError(String reason) {
            super(reason, null, false, false);
        }
    }

    record Group(Op[] ops, int slots, int keptSize) {}

    static final class Cursor {
        final byte[] data;
        final int end;
        final List<String> gameNames;
        final List<String> ownNames = new ArrayList<>();
        final long[] context;
        final boolean[] hasContext;
        final List<String> contextNames;
        final long stepLimit;
        final List<String> path = new ArrayList<>();
        int pos;
        long steps;
        @Nullable
        List<TraceField> sink;
        @Nullable
        List<TraceField> traced;

        int traceRow = NO_TRACE;
        boolean stop;

        @Nullable
        List<Map<String, Object>> rows;

        int rowCount = -1;
        int failedRow = -1;
        String failPath = "";
        int failOffset = -1;
        String failWhere = "";
        String failReason = "";

        Cursor(byte[] data, List<String> gameNames, Map<String, ? extends Number> values, List<String> names) {
            this.data = data;
            this.end = data.length;
            this.gameNames = gameNames;
            this.contextNames = names;
            this.context = new long[names.size()];
            this.hasContext = new boolean[names.size()];
            for (var i = 0; i < names.size(); i++) {
                var value = values.get(names.get(i));
                if (value != null) {
                    context[i] = value.longValue();
                    hasContext[i] = true;
                }
            }
            this.stepLimit = STEPS_PER_BYTE * data.length + EXTRA_STEPS;
        }

        ReadError fail(String reason) {
            return new ReadError(reason);
        }

        void need(int bytes) {
            if (end - pos < bytes) {
                throw fail("the field needs " + bytes + (bytes == 1 ? " byte" : " bytes") + ", and only " + (end - pos)
                        + " are left");
            }
        }

        void step() {
            step(1);
        }

        void step(int count) {
            steps += count;
            if (steps > stepLimit) {
                throw fail("the schema reads more than " + stepLimit + " fields, groups and items for a table of "
                        + data.length + " bytes, too many that take no bytes");
            }
        }

        int readByte() {
            need(1);
            return data[pos++] & 0xff;
        }

        int readShort() {
            need(2);
            var value = (short) SHORTS.get(data, pos);
            pos += 2;
            return value;
        }

        int readInt() {
            need(4);
            var value = (int) INTS.get(data, pos);
            pos += 4;
            return value;
        }

        long readLong() {
            need(8);
            var value = (long) LONGS.get(data, pos);
            pos += 8;
            return value;
        }

        int readCompact() {
            var first = readByte();
            var value = first & 0x3f;
            if ((first & 0x40) != 0) {
                var shift = 6;
                int next;
                do {
                    next = readByte();
                    value |= (next & 0x7f) << shift;
                    shift += 7;
                } while ((next & 0x80) != 0 && shift < 32);
            }
            return (first & 0x80) != 0 ? -value : value;
        }

        long readNumber(Basic type) {
            return switch (type) {
                case BYTE -> readByte();
                case SHORT -> readShort();
                case USHORT -> readShort() & 0xffff;
                case INT -> readInt();
                case UINT -> Integer.toUnsignedLong(readInt());
                case LONG -> readLong();
                case COMPACT -> readCompact();
                default -> throw new IllegalStateException(type + " is not a whole number");
            };
        }

        @Nullable
        String readText(Basic type, boolean keep) {
            int bytes;
            var wide = false;
            switch (type) {
                case STRING -> {
                    bytes = readInt();
                    if (bytes < 0) {
                        throw fail("a text of " + bytes + " bytes is below 0");
                    }
                    if ((bytes & 1) != 0) {
                        throw fail("a UTF-16 text of " + bytes + (bytes == 1 ? " byte" : " bytes")
                                + ": its length is odd");
                    }
                    wide = true;
                }
                case FSTRING -> {
                    var length = readCompact();
                    if (Math.abs((long) length) > TEXT_LIMIT) {
                        throw fail("a text of " + Math.abs((long) length) + " letters is longer than 65 536 bytes");
                    }
                    wide = length < 0;
                    bytes = wide ? -length * 2 : length;
                }
                case ANSI -> {
                    bytes = readInt();
                    if (bytes < 0) {
                        throw fail("a text of " + bytes + " bytes is below 0");
                    }
                }
                default -> throw new IllegalStateException(type + " is not a text");
            }
            if (bytes > TEXT_LIMIT) {
                throw fail("a text of " + bytes + " bytes is longer than 65 536");
            }
            if (end - pos < bytes) {
                throw fail("a text of " + bytes + " bytes, and only " + (end - pos) + " bytes are left");
            }
            var start = pos;
            pos += bytes;
            if (!keep) {
                return null;
            }
            var stop = start + bytes;
            if (wide) {
                while (stop - start >= 2 && data[stop - 1] == 0 && data[stop - 2] == 0) {
                    stop -= 2;
                }
                return new String(data, start, stop - start, StandardCharsets.UTF_16LE);
            }
            while (stop > start && data[stop - 1] == 0) {
                stop--;
            }
            return new String(data, start, stop - start, StandardCharsets.ISO_8859_1);
        }

        void enter(String label, Where where) {
            failPath = toPath(label);
            failOffset = pos;
            failWhere = where.source() + ":" + where.line();
        }

        String toPath(String label) {
            if (path.isEmpty()) {
                return label;
            }
            return String.join(".", path) + "." + label;
        }

        void note(String label, String type, int start, @Nullable Object value, Where where) {
            if (sink != null) {
                sink.add(new TraceField(
                        toPath(label), type, start, pos - start, value, where.source() + ":" + where.line()));
            }
        }

        ReadFailure describeFailure() {
            return new ReadFailure(failedRow, failOffset, failPath, failReason, failWhere);
        }

        ReadFailure describeFailure(ReadError error) {
            return new ReadFailure(HEAD, failOffset, failPath, error.getMessage(), failWhere);
        }

        long getContextValue(int index) {
            if (!hasContext[index]) {
                throw fail("the condition uses " + contextNames.get(index) + ", and the reader was given none");
            }
            return context[index];
        }
    }

    static final class Frame {
        final @Nullable Frame parent;
        final long[] numbers;
        final boolean[] read;
        final @Nullable Map<String, Object> out;

        Frame(@Nullable Frame parent, int slots, @Nullable Map<String, Object> out) {
            this.parent = parent;
            this.numbers = slots == 0 ? NO_NUMBERS : new long[slots];
            this.read = slots == 0 ? NO_FLAGS : new boolean[slots];
            this.out = out;
        }

        long getValue(Cursor cursor, int up, int slot, String name) {
            var frame = this;
            for (var i = 0; i < up; i++) {
                frame = frame.parent;
            }
            if (!frame.read[slot]) {
                throw cursor.fail(name + " is used, but this row did not read it");
            }
            return frame.numbers[slot];
        }
    }

    record Ref(int up, int slot, int context, String name) {
        long valueOf(Cursor cursor, Frame frame) {
            return context >= 0 ? cursor.getContextValue(context) : frame.getValue(cursor, up, slot, name);
        }
    }

    abstract static class Op {
        final Where where;
        final String label;
        final long least;

        Op(Where where, String label, long least) {
            this.where = where;
            this.label = label;
            this.least = least;
        }

        abstract void read(Cursor cursor, Frame frame);
    }

    static final class ScalarOp extends Op {
        final Basic type;
        final String word;
        final @Nullable String key;
        final int slot;
        final boolean own;
        boolean collect;

        ScalarOp(Where where, String label, Basic type, String word, @Nullable String key, int slot, boolean own) {
            super(where, label, type.least);
            this.type = type;
            this.word = word;
            this.key = key;
            this.slot = slot;
            this.own = own;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            if (cursor.sink != null) {
                readNoted(cursor, frame, label);
                return;
            }
            switch (type) {
                case BYTE, SHORT, USHORT, INT, UINT, LONG, COMPACT -> {
                    var number = cursor.readNumber(type);
                    if (slot >= 0) {
                        frame.numbers[slot] = number;
                        frame.read[slot] = true;
                    }
                    if (key != null) {
                        frame.out.put(key, box(number));
                    }
                }
                case FLOAT -> {
                    var bits = cursor.readInt();
                    if (key != null) {
                        frame.out.put(key, Float.intBitsToFloat(bits));
                    }
                }
                case DOUBLE -> {
                    var bits = cursor.readLong();
                    if (key != null) {
                        frame.out.put(key, Double.longBitsToDouble(bits));
                    }
                }
                case STRING, FSTRING, ANSI -> {
                    var text = cursor.readText(type, key != null || collect);
                    if (collect) {
                        cursor.ownNames.add(text);
                    }
                    if (key != null) {
                        frame.out.put(key, text);
                    }
                }
                case NAME -> {
                    var text = readName(cursor, key != null);
                    if (key != null) {
                        frame.out.put(key, text);
                    }
                }
            }
        }

        void readNoted(Cursor cursor, Frame frame, String noted) {
            cursor.enter(noted, where);
            var start = cursor.pos;
            var value = readValue(cursor);
            if (collect) {
                cursor.ownNames.add((String) value);
            }
            if (slot >= 0) {
                frame.numbers[slot] = type == Basic.UINT ? Integer.toUnsignedLong((Integer) value) : numberOf(value);
                frame.read[slot] = true;
            }
            if (key != null) {
                frame.out.put(key, value);
            }
            cursor.note(noted, word, start, value, where);
        }

        Object readValue(Cursor cursor) {
            return switch (type) {
                case BYTE, SHORT, USHORT, INT, UINT, LONG, COMPACT -> box(cursor.readNumber(type));
                case FLOAT -> Float.intBitsToFloat(cursor.readInt());
                case DOUBLE -> Double.longBitsToDouble(cursor.readLong());
                case STRING, FSTRING, ANSI -> cursor.readText(type, true);
                case NAME -> readName(cursor, true);
            };
        }

        void skipValue(Cursor cursor) {
            switch (type) {
                case STRING, FSTRING, ANSI -> cursor.readText(type, false);
                case NAME -> readName(cursor, false);
                case COMPACT -> cursor.readCompact();
                default -> {
                    cursor.need(type.size);
                    cursor.pos += type.size;
                }
            }
        }

        private @Nullable String readName(Cursor cursor, boolean keep) {
            var index = cursor.readInt();
            var names = own ? cursor.ownNames : cursor.gameNames;
            if (index < -1 || index >= names.size()) {
                var list = own ? "the list of use names" : "L2GameDataName.dat";
                throw cursor.fail(
                        "name number " + index + " is not in " + list + ", which has " + names.size() + " texts");
            }
            if (!keep) {
                return null;
            }
            return index < 0 ? "" : names.get(index);
        }

        private Object box(long number) {
            return type == Basic.LONG ? (Object) number : (Object) (int) number;
        }

        private static long numberOf(Object value) {
            return ((Number) value).longValue();
        }
    }

    static final class MergedSkipOp extends Op {
        final int bytes;
        final Op[] parts;

        MergedSkipOp(int bytes, Op[] parts) {
            super(parts[0].where, parts[0].label, bytes);
            this.bytes = bytes;
            this.parts = parts;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            if (cursor.sink != null) {
                run(parts, cursor, frame);
                return;
            }
            cursor.need(bytes);
            cursor.pos += bytes;
        }
    }

    static final class SkipOp extends Op {
        final int bytes;

        SkipOp(Where where, int bytes) {
            super(where, "(skip)", bytes);
            this.bytes = bytes;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            if (cursor.sink != null) {
                cursor.enter(label, where);
            }
            var start = cursor.pos;
            cursor.need(bytes);
            cursor.pos += bytes;
            cursor.note(label, "skip " + bytes, start, null, where);
        }
    }

    static final class CountSpec {
        final int fixed;
        final @Nullable Basic type;
        final @Nullable Ref ref;
        final String word;

        CountSpec(int fixed, @Nullable Basic type, @Nullable Ref ref, String word) {
            this.fixed = fixed;
            this.type = type;
            this.ref = ref;
            this.word = word;
        }

        long readCount(Cursor cursor, Frame frame) {
            if (type != null) {
                var count = cursor.readNumber(type);
                if ((type == Basic.UINT || type == Basic.LONG) && count > Integer.MAX_VALUE) {
                    throw cursor.fail(
                            "a " + type.word + " count of " + Long.toUnsignedString(count) + " is over 2 147 483 647");
                }
                return count;
            }
            if (ref != null) {
                return ref.valueOf(cursor, frame);
            }
            return fixed;
        }
    }

    static class ListOp extends Op {
        final CountSpec count;
        final int limit;
        final long itemLeast;
        final @Nullable String key;
        final @Nullable ScalarOp single;
        final @Nullable Group group;
        final boolean rows;

        ListOp(
                Where where,
                String label,
                CountSpec count,
                int limit,
                long itemLeast,
                @Nullable String key,
                @Nullable ScalarOp single,
                @Nullable Group group,
                boolean rows) {
            super(where, label, leastOf(count, itemLeast));
            this.rows = rows;
            this.count = count;
            this.limit = limit;
            this.itemLeast = itemLeast;
            this.key = key;
            this.single = single;
            this.group = group;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            var items = readCount(cursor, frame);
            if (single != null) {
                readSingles(cursor, frame, items);
            } else {
                readGroups(cursor, frame, items);
            }
        }

        int readCount(Cursor cursor, Frame frame) {
            var start = cursor.pos;
            if (cursor.sink != null) {
                cursor.enter(label, where);
            }
            var items = count.readCount(cursor, frame);
            if (items < 0) {
                throw cursor.fail("the list count " + items + " is below 0");
            }
            if (items > limit) {
                throw cursor.fail("a list of " + items + " items is longer than "
                        + (limit == ROW_LIST_LIMIT ? "100 000 in a row" : "2 000 000"));
            }
            var left = cursor.end - cursor.pos;
            if (itemLeast > 0 && items > left) {
                throw cursor.fail("a list of " + items + " items, and only " + left + " bytes are left");
            }
            if (!rows && itemLeast > 1 && items * itemLeast > left) {
                throw cursor.fail("a list of " + items + " items of at least " + itemLeast + " bytes, and only " + left
                        + " bytes are left");
            }
            cursor.note(label, "list " + count.word, start, (int) items, where);
            return (int) items;
        }

        private void readSingles(Cursor cursor, Frame frame, int items) {
            var element = single;
            if (cursor.sink != null) {
                for (var i = 0; i < items; i++) {
                    element.readNoted(cursor, frame, label + "[" + i + "]");
                }
                return;
            }
            if (key != null || element.collect) {
                var values = key != null ? new ArrayList<Object>(items) : null;
                for (var i = 0; i < items; i++) {
                    var value = element.readValue(cursor);
                    if (element.collect) {
                        cursor.ownNames.add((String) value);
                    }
                    if (values != null) {
                        values.add(value);
                    }
                }
                if (key != null) {
                    frame.out.put(key, values);
                }
            } else if (element.type.size > 0 && element.type != Basic.NAME) {
                cursor.pos += items * element.type.size;
            } else {
                for (var i = 0; i < items; i++) {
                    element.skipValue(cursor);
                }
            }
        }

        private void readGroups(Cursor cursor, Frame frame, int items) {
            var inner = group;
            var values = key != null ? new ArrayList<Map<String, Object>>(items) : null;
            for (var i = 0; i < items; i++) {
                cursor.step();
                var item =
                        new Frame(frame, inner.slots(), values != null ? HashMap.newHashMap(inner.keptSize()) : null);
                if (cursor.sink != null) {
                    cursor.path.add(label + "[" + i + "]");
                    try {
                        run(inner.ops(), cursor, item);
                    } finally {
                        cursor.path.removeLast();
                    }
                } else {
                    run(inner.ops(), cursor, item);
                }
                if (values != null) {
                    values.add(item.out);
                }
            }
            if (key != null) {
                frame.out.put(key, values);
            }
        }

        private static long leastOf(CountSpec count, long itemLeast) {
            if (count.type != null) {
                return count.type.least;
            }
            if (count.ref != null) {
                return 0;
            }
            return Math.min(1L << 40, count.fixed * itemLeast);
        }
    }

    static final class RowsOp extends ListOp {
        RowsOp(Where where, CountSpec count, long itemLeast, Group group) {
            super(where, "records", count, LIST_LIMIT, itemLeast, null, null, group, true);
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            var items = readCount(cursor, frame);
            var inner = group;
            var head = cursor.sink;
            cursor.sink = null;
            cursor.rowCount = items;
            cursor.rows = new ArrayList<>(items);
            try {
                for (var i = 0; i < items; i++) {
                    var start = cursor.pos;
                    var steps = cursor.steps;
                    cursor.step();
                    var row = new Frame(frame, inner.slots(), HashMap.newHashMap(inner.keptSize()));
                    var traced = i == cursor.traceRow;
                    if (traced) {
                        cursor.sink = cursor.traced;
                        cursor.path.clear();
                        cursor.path.add("records[" + i + "]");
                    }
                    try {
                        run(inner.ops(), cursor, row);
                    } catch (ReadError failure) {
                        cursor.failedRow = i;
                        cursor.failReason = failure.getMessage();
                        if (!traced) {
                            locate(cursor, frame, start, steps, i);
                        }
                        throw failure;
                    }
                    cursor.rows.add(row.out);
                    if (traced) {
                        cursor.stop = true;
                        return;
                    }
                }
            } finally {
                cursor.sink = head;
                cursor.path.clear();
            }
        }

        private void locate(Cursor cursor, Frame frame, int start, long steps, int row) {
            cursor.pos = start;
            cursor.steps = steps;
            cursor.sink = new ArrayList<>();
            cursor.path.clear();
            cursor.path.add("records[" + row + "]");
            cursor.failPath = "records[" + row + "]";
            cursor.failOffset = start;
            cursor.failWhere = where.source() + ":" + where.line();
            try {
                run(group.ops(), cursor, new Frame(frame, group.slots(), HashMap.newHashMap(group.keptSize())));
            } catch (ReadError again) {
                cursor.failReason = again.getMessage();
            } finally {
                cursor.sink = null;
            }
        }
    }

    static final class GroupOp extends Op {
        final String type;
        final @Nullable String key;
        final Group group;

        GroupOp(Where where, String label, String type, @Nullable String key, Group group, long least) {
            super(where, label, least);
            this.type = type;
            this.key = key;
            this.group = group;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            var inner = new Frame(frame, group.slots(), key != null ? HashMap.newHashMap(group.keptSize()) : null);
            if (cursor.sink != null) {
                cursor.path.add(label);
                try {
                    run(group.ops(), cursor, inner);
                } finally {
                    cursor.path.removeLast();
                }
            } else {
                run(group.ops(), cursor, inner);
            }
            if (key != null) {
                frame.out.put(key, inner.out);
            }
        }
    }

    static final class IfOp extends Op {
        final Test test;
        final Op[] then;
        final Op[] otherwise;

        IfOp(Where where, Test test, Op[] then, Op[] otherwise, long least) {
            super(where, "(if line " + where.line() + ")", least);
            this.test = test;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        void read(Cursor cursor, Frame frame) {
            if (cursor.sink != null) {
                cursor.enter(label, where);
            }
            var holds = test.holds(cursor, frame);
            cursor.note(label, "if", cursor.pos, holds, where);
            run(holds ? then : otherwise, cursor, frame);
        }
    }

    abstract static class Test {
        abstract boolean holds(Cursor cursor, Frame frame);
    }

    static final class CompareTest extends Test {
        final Ref ref;
        final Operator operator;
        final long value;

        CompareTest(Ref ref, Operator operator, long value) {
            this.ref = ref;
            this.operator = operator;
            this.value = value;
        }

        @Override
        boolean holds(Cursor cursor, Frame frame) {
            cursor.step();
            return operator.test(ref.valueOf(cursor, frame), value);
        }
    }

    static final class BothTest extends Test {
        final Test left;
        final Test right;

        BothTest(Test left, Test right) {
            this.left = left;
            this.right = right;
        }

        @Override
        boolean holds(Cursor cursor, Frame frame) {
            return left.holds(cursor, frame) && right.holds(cursor, frame);
        }
    }

    static final class EitherTest extends Test {
        final Test left;
        final Test right;

        EitherTest(Test left, Test right) {
            this.left = left;
            this.right = right;
        }

        @Override
        boolean holds(Cursor cursor, Frame frame) {
            return left.holds(cursor, frame) || right.holds(cursor, frame);
        }
    }
}
