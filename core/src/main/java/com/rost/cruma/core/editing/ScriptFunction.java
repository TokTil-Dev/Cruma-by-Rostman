package com.rost.cruma.core.editing;

import static com.rost.cruma.core.editing.PackageBytes.malformed;

import com.rost.cruma.core.editing.PackageBytes.Cursor;
import com.rost.cruma.core.editing.TaggedProperties.Names;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

final class ScriptFunction {
    static final int FUNC_FINAL = 0x1;
    static final int FUNC_ITERATOR = 0x4;
    static final int FUNC_LATENT = 0x8;
    static final int FUNC_PRE_OPERATOR = 0x10;
    static final int FUNC_SINGULAR = 0x20;
    static final int FUNC_NET = 0x40;
    static final int FUNC_SIMULATED = 0x100;
    static final int FUNC_EXEC = 0x200;
    static final int FUNC_NATIVE = 0x400;
    static final int FUNC_EVENT = 0x800;
    static final int FUNC_OPERATOR = 0x1000;
    static final int FUNC_STATIC = 0x2000;
    static final int FUNC_CONST = 0x8000;
    static final int FUNC_PRIVATE = 0x40000;
    static final int FUNC_PROTECTED = 0x80000;
    static final int FUNC_DELEGATE = 0x100000;

    static final int LOCAL_VARIABLE = 0x00;
    static final int INSTANCE_VARIABLE = 0x01;
    static final int DEFAULT_VARIABLE = 0x02;
    static final int RETURN = 0x04;
    static final int SWITCH = 0x05;
    static final int JUMP = 0x06;
    static final int JUMP_IF_NOT = 0x07;
    static final int STOP = 0x08;
    static final int ASSERT = 0x09;
    static final int CASE = 0x0A;
    static final int NOTHING = 0x0B;
    static final int GOTO_LABEL = 0x0D;
    static final int EAT_STRING = 0x0E;
    static final int LET = 0x0F;
    static final int DYN_ARRAY_ELEMENT = 0x10;
    static final int NEW = 0x11;
    static final int CLASS_CONTEXT = 0x12;
    static final int META_CAST = 0x13;
    static final int LET_BOOL = 0x14;
    static final int END_FUNCTION_PARMS = 0x16;
    static final int SELF = 0x17;
    static final int SKIP = 0x18;
    static final int CONTEXT = 0x19;
    static final int ARRAY_ELEMENT = 0x1A;
    static final int VIRTUAL_FUNCTION = 0x1B;
    static final int FINAL_FUNCTION = 0x1C;
    static final int INT_CONST = 0x1D;
    static final int FLOAT_CONST = 0x1E;
    static final int STRING_CONST = 0x1F;
    static final int OBJECT_CONST = 0x20;
    static final int NAME_CONST = 0x21;
    static final int ROTATION_CONST = 0x22;
    static final int VECTOR_CONST = 0x23;
    static final int BYTE_CONST = 0x24;
    static final int INT_ZERO = 0x25;
    static final int INT_ONE = 0x26;
    static final int TRUE = 0x27;
    static final int FALSE = 0x28;
    static final int NATIVE_PARM = 0x29;
    static final int NO_OBJECT = 0x2A;
    static final int INT_CONST_BYTE = 0x2C;
    static final int BOOL_VARIABLE = 0x2D;
    static final int DYNAMIC_CAST = 0x2E;
    static final int ITERATOR = 0x2F;
    static final int ITERATOR_POP = 0x30;
    static final int ITERATOR_NEXT = 0x31;
    static final int STRUCT_CMP_EQ = 0x32;
    static final int STRUCT_CMP_NE = 0x33;
    static final int UNICODE_STRING_CONST = 0x34;
    static final int STRUCT_MEMBER = 0x36;
    static final int DYN_ARRAY_LENGTH = 0x37;
    static final int GLOBAL_FUNCTION = 0x38;
    static final int PRIMITIVE_CAST = 0x39;
    static final int DYN_ARRAY_INSERT = 0x40;
    static final int DYN_ARRAY_REMOVE = 0x41;
    static final int DELEGATE_FUNCTION = 0x43;
    static final int DELEGATE_PROPERTY = 0x44;
    static final int LET_DELEGATE = 0x45;
    static final int EXTENDED_NATIVE = 0x60;
    static final int FIRST_NATIVE = 0x70;
    static final int NATIVE = 0x100;

    private static final int NAME_BYTES = 4;
    private static final int MOST_DEPTH = 64;

    int superField;
    int children;
    int friendlyName;
    int line;
    int scriptSize;
    int nativeIndex;
    int precedence;
    int flags;
    List<Node> statements = List.of();

    private ScriptFunction() {}

    record Node(
            int at,
            int token,
            int value,
            int reference,
            @Nullable String text,
            List<Node> kids) {
        Node kid(int index) {
            return kids.get(index);
        }
    }

    static @Nullable ScriptFunction read(byte[] payload, Names names) {
        for (var cppText : new boolean[] {true, false}) {
            for (var pointer : new int[] {4, 8}) {
                var result = read(payload, names, cppText, pointer);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    boolean is(int flag) {
        return (flags & flag) != 0;
    }

    private static @Nullable ScriptFunction read(byte[] payload, Names names, boolean cppText, int pointer) {
        try {
            var cursor = new Cursor(payload, 0, payload.length);
            TaggedProperties.read(cursor, names);
            var function = new ScriptFunction();
            function.superField = cursor.readIndex();
            cursor.readIndex();
            cursor.readIndex();
            function.children = cursor.readIndex();
            function.friendlyName = cursor.readIndex();
            if (cppText) {
                cursor.readIndex();
            }
            function.line = cursor.readI32();
            cursor.readI32();
            function.scriptSize = cursor.readI32();
            if (function.scriptSize < 0 || function.scriptSize > payload.length * pointer) {
                return null;
            }
            var code = new Code(cursor, pointer, function.scriptSize);
            var statements = new ArrayList<Node>();
            while (code.memory < function.scriptSize) {
                statements.add(code.readExpression(0));
            }
            if (code.memory != function.scriptSize) {
                return null;
            }
            function.nativeIndex = cursor.readU16();
            function.precedence = cursor.readU8();
            function.flags = cursor.readI32();
            if ((function.flags & FUNC_NET) != 0) {
                cursor.readU16();
            }
            if (cursor.position != cursor.end) {
                return null;
            }
            function.statements = List.copyOf(statements);
            return function;
        } catch (IOException | RuntimeException otherLayout) {
            return null;
        }
    }

    private static final class Code {
        private final Cursor cursor;
        private final int pointer;
        private final int size;
        int memory;

        Code(Cursor cursor, int pointer, int size) {
            this.cursor = cursor;
            this.pointer = pointer;
            this.size = size;
        }

        Node readExpression(int depth) throws IOException {
            if (depth > MOST_DEPTH) {
                throw malformed("Script code nests too deep");
            }
            var at = memory;
            var token = u8();
            if (token >= FIRST_NATIVE) {
                return new Node(at, NATIVE, token, 0, null, parameters(depth));
            }
            if ((token & 0xF0) == EXTENDED_NATIVE) {
                var number = (token - EXTENDED_NATIVE) << 8 | u8();
                return new Node(at, NATIVE, number, 0, null, parameters(depth));
            }
            return switch (token) {
                case LOCAL_VARIABLE, INSTANCE_VARIABLE, DEFAULT_VARIABLE, NATIVE_PARM, OBJECT_CONST ->
                    node(at, token, 0, object());
                case RETURN, GOTO_LABEL, EAT_STRING, BOOL_VARIABLE, DYN_ARRAY_LENGTH ->
                    node(at, token, 0, 0, readExpression(depth + 1));
                case SWITCH -> {
                    var bytes = u8();
                    yield node(at, token, bytes, 0, readExpression(depth + 1));
                }
                case JUMP -> node(at, token, u16(), 0);
                case JUMP_IF_NOT, SKIP, ASSERT -> {
                    var value = u16();
                    yield node(at, token, value, 0, readExpression(depth + 1));
                }
                case CASE -> {
                    var next = u16();
                    yield next == 0xFFFF
                            ? node(at, token, next, 0)
                            : node(at, token, next, 0, readExpression(depth + 1));
                }
                case NOTHING,
                        STOP,
                        END_FUNCTION_PARMS,
                        SELF,
                        INT_ZERO,
                        INT_ONE,
                        TRUE,
                        FALSE,
                        NO_OBJECT,
                        ITERATOR_POP,
                        ITERATOR_NEXT -> node(at, token, 0, 0);
                case LET, LET_BOOL, LET_DELEGATE, DYN_ARRAY_ELEMENT, ARRAY_ELEMENT ->
                    node(at, token, 0, 0, readExpression(depth + 1), readExpression(depth + 1));
                case NEW ->
                    node(
                            at,
                            token,
                            0,
                            0,
                            readExpression(depth + 1),
                            readExpression(depth + 1),
                            readExpression(depth + 1),
                            readExpression(depth + 1));
                case CLASS_CONTEXT, CONTEXT -> {
                    var owner = readExpression(depth + 1);
                    var skip = u16();
                    u8();
                    yield node(at, token, skip, 0, owner, readExpression(depth + 1));
                }
                case META_CAST, DYNAMIC_CAST, STRUCT_MEMBER -> {
                    var type = object();
                    yield node(at, token, 0, type, readExpression(depth + 1));
                }
                case VIRTUAL_FUNCTION, GLOBAL_FUNCTION -> {
                    var name = name();
                    yield new Node(at, token, 0, name, null, parameters(depth));
                }
                case FINAL_FUNCTION -> {
                    var function = object();
                    yield new Node(at, token, 0, function, null, parameters(depth));
                }
                case INT_CONST, FLOAT_CONST -> node(at, token, i32(), 0);
                case BYTE_CONST, INT_CONST_BYTE -> node(at, token, u8(), 0);
                case NAME_CONST, DELEGATE_PROPERTY -> node(at, token, 0, name());
                case ROTATION_CONST, VECTOR_CONST -> node(at, token, 0, 0, number(i32()), number(i32()), number(i32()));
                case STRING_CONST -> new Node(at, token, 0, 0, string(false), List.of());
                case UNICODE_STRING_CONST -> new Node(at, token, 0, 0, string(true), List.of());
                case ITERATOR -> {
                    var iterator = readExpression(depth + 1);
                    yield node(at, token, u16(), 0, iterator);
                }
                case STRUCT_CMP_EQ, STRUCT_CMP_NE -> {
                    var struct = object();
                    yield node(at, token, 0, struct, readExpression(depth + 1), readExpression(depth + 1));
                }
                case PRIMITIVE_CAST -> {
                    var cast = u8();
                    yield node(at, token, cast, 0, readExpression(depth + 1));
                }
                case DYN_ARRAY_INSERT, DYN_ARRAY_REMOVE ->
                    node(
                            at,
                            token,
                            0,
                            0,
                            readExpression(depth + 1),
                            readExpression(depth + 1),
                            readExpression(depth + 1));
                case DELEGATE_FUNCTION -> {
                    var property = object();
                    var name = name();
                    yield new Node(at, token, property, name, null, parameters(depth));
                }
                default -> throw malformed("Unknown script token 0x" + Integer.toHexString(token));
            };
        }

        private List<Node> parameters(int depth) throws IOException {
            var result = new ArrayList<Node>();
            while (true) {
                var parameter = readExpression(depth + 1);
                if (parameter.token() == END_FUNCTION_PARMS) {
                    return List.copyOf(result);
                }
                result.add(parameter);
            }
        }

        private int u8() throws IOException {
            count(1);
            return cursor.readU8();
        }

        private int u16() throws IOException {
            count(2);
            return cursor.readU16();
        }

        private int i32() throws IOException {
            count(4);
            return cursor.readI32();
        }

        private int object() throws IOException {
            count(pointer);
            return cursor.readIndex();
        }

        private int name() throws IOException {
            count(NAME_BYTES);
            return cursor.readIndex();
        }

        private String string(boolean wide) throws IOException {
            var bytes = new java.io.ByteArrayOutputStream();
            while (true) {
                var unit = wide ? u16() : u8();
                if (unit == 0) {
                    break;
                }
                if (wide) {
                    bytes.write(unit & 0xFF);
                    bytes.write(unit >> 8);
                } else {
                    bytes.write(unit);
                }
            }
            return bytes.toString(wide ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1);
        }

        private void count(int bytes) throws IOException {
            if (bytes > size - memory) {
                throw malformed("Script code runs past ScriptSize");
            }
            memory += bytes;
        }

        private static Node node(int at, int token, int value, int reference, Node... kids) {
            return new Node(at, token, value, reference, null, List.of(kids));
        }

        private static Node number(int bits) {
            return new Node(-1, INT_CONST, bits, 0, null, List.of());
        }
    }
}
