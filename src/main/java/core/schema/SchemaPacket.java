package core.schema;

import com.google.gson.JsonElement;
import core.handlers.BasePacket;
import core.handlers.PacketDecodeException;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A packet whose layout comes from a {@link PacketDef} instead of Java code. Field values live in
 * an ordered map and are read and written by walking the definition, so adding a packet to the
 * emulator is a schema edit, not a rebuild.
 */
public final class SchemaPacket extends BasePacket {

    private final PacketDef def;
    private final SchemaSet set;
    private Map<String, Object> values;

    public SchemaPacket(PacketDef def, SchemaSet set) {
        super(def.opcode());
        this.def = Objects.requireNonNull(def, "def");
        this.set = Objects.requireNonNull(set, "set");
        this.values = SchemaValues.defaults(def.fields(), set, 0);
    }

    public PacketDef def() {
        return def;
    }

    public String name() {
        return def.name();
    }

    /** Field values: integers as Long, floats as Double, Boolean, String, byte[], Map, List. */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /** Dotted-path read ({@code position.x}, {@code items.0.id}); null when absent. */
    public Object get(String path) {
        return SchemaValues.lookup(values, path);
    }

    public long getLong(String path) {
        return SchemaValues.toLong(require(path), path);
    }

    public double getDouble(String path) {
        return SchemaValues.toDouble(require(path), path);
    }

    public boolean getBoolean(String path) {
        return SchemaValues.toBoolean(require(path), path);
    }

    public String getString(String path) {
        return SchemaValues.toStringValue(require(path));
    }

    private Object require(String path) {
        Object value = get(path);
        if (value == null) {
            throw new IllegalArgumentException("packet " + def.name() + " has no value at '" + path + "'");
        }
        return value;
    }

    /** Set a top-level field, coercing the value to the field's type. */
    public SchemaPacket set(String field, Object value) {
        FieldDef definition = def.field(field).orElseThrow(() ->
                new IllegalArgumentException("packet " + def.name() + " has no field '" + field + "'"));
        values.put(field, SchemaValues.coerce(value, definition, set, 0));
        return this;
    }

    public SchemaPacket setAll(Map<String, ?> fields) {
        fields.forEach(this::set);
        return this;
    }

    /** The values as a JSON tree (bytes as hex), for logging and the admin API. */
    public JsonElement toJsonTree() {
        return SchemaValues.toJsonForDisplay(values);
    }

    // ---- codec ----------------------------------------------------------------------------------

    @Override
    public void decode(ByteBuf in) throws PacketDecodeException {
        values = readFields(in, def.fields(), 0);
    }

    @Override
    public void encode(ByteBuf out) {
        writeFields(out, def.fields(), values, 0);
    }

    private Map<String, Object> readFields(ByteBuf in, List<FieldDef> fields, int depth) throws PacketDecodeException {
        if (depth > SchemaValues.MAX_DEPTH) {
            throw new PacketDecodeException("struct nesting deeper than " + SchemaValues.MAX_DEPTH);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (FieldDef field : fields) {
            out.put(field.name(), readValue(in, field, depth));
        }
        return out;
    }

    private Object readValue(ByteBuf in, FieldDef field, int depth) throws PacketDecodeException {
        String name = field.name();
        switch (field.type()) {
            case U8:
                return (long) readU8(in, name);
            case I8:
                require(in, 1, name);
                return (long) in.readByte();
            case U16:
                return (long) readU16(in, name);
            case I16:
                require(in, 2, name);
                return (long) in.readShort();
            case U32:
                require(in, 4, name);
                return in.readUnsignedInt();
            case I32:
                return (long) readInt(in, name);
            case I64:
                return readLong(in, name);
            case F32:
                require(in, 4, name);
                return (double) in.readFloat();
            case F64:
                require(in, 8, name);
                return in.readDouble();
            case BOOL:
                return readBoolean(in, name);
            case STRING:
                return readString(in, name);
            case BYTES:
                return readBytes(in, name);
            case STRUCT:
                return readFields(in, SchemaValues.structOf(field, set).fields(), depth + 1);
            case ARRAY: {
                int count = readU16(in, name + ".count");
                FieldDef element = field.element();
                List<Object> list = new ArrayList<>(Math.min(count, 256));
                for (int i = 0; i < count; i++) {
                    list.add(readValue(in, element, depth + 1));
                }
                return list;
            }
            default:
                throw new PacketDecodeException("unhandled field type " + field.type());
        }
    }

    private void writeFields(ByteBuf out, List<FieldDef> fields, Map<String, Object> source, int depth) {
        for (FieldDef field : fields) {
            writeValue(out, field, source == null ? null : source.get(field.name()), depth);
        }
    }

    @SuppressWarnings("unchecked")
    private void writeValue(ByteBuf out, FieldDef field, Object raw, int depth) {
        Object value = SchemaValues.coerce(raw, field, set, depth);
        String name = field.name();
        switch (field.type()) {
            case U8 -> out.writeByte((int) ranged(value, name, 0, 0xFF));
            case I8 -> out.writeByte((int) ranged(value, name, Byte.MIN_VALUE, Byte.MAX_VALUE));
            case U16 -> out.writeShort((int) ranged(value, name, 0, 0xFFFF));
            case I16 -> out.writeShort((int) ranged(value, name, Short.MIN_VALUE, Short.MAX_VALUE));
            case U32 -> out.writeInt((int) ranged(value, name, 0, 0xFFFF_FFFFL));
            case I32 -> out.writeInt((int) ranged(value, name, Integer.MIN_VALUE, Integer.MAX_VALUE));
            case I64 -> out.writeLong((Long) value);
            case F32 -> out.writeFloat((float) (double) (Double) value);
            case F64 -> out.writeDouble((Double) value);
            case BOOL -> writeBoolean(out, (Boolean) value);
            case STRING -> writeString(out, (String) value);
            case BYTES -> writeBytes(out, (byte[]) value);
            case STRUCT -> writeFields(out, SchemaValues.structOf(field, set).fields(),
                    (Map<String, Object>) value, depth + 1);
            case ARRAY -> {
                List<Object> list = (List<Object>) value;
                out.writeShort(list.size());
                FieldDef element = field.element();
                for (Object item : list) {
                    writeValue(out, element, item, depth + 1);
                }
            }
            default -> throw new IllegalStateException("unhandled field type " + field.type());
        }
    }

    private static long ranged(Object value, String field, long min, long max) {
        long n = (Long) value;
        if (n < min || n > max) {
            throw new IllegalArgumentException("field '" + field + "' value " + n + " is outside " + min + ".." + max);
        }
        return n;
    }

    @Override
    public String toString() {
        return def.name() + SchemaValues.describe(values);
    }
}
