package core.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import core.engine.EngineConfig;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Value model shared by schema packets and templates: integers are {@code Long}, floats are
 * {@code Double}, plus {@code Boolean}, {@code String}, {@code byte[]}, {@code Map} for structs
 * and {@code List} for arrays. Converts JSON into that model and coerces loosely typed inputs
 * (template results, plugin calls) into exactly what a field needs.
 */
final class SchemaValues {

    static final int MAX_DEPTH = 8;
    static final int MAX_ARRAY_LENGTH = 65_535;

    private SchemaValues() {
    }

    // ---- JSON -> model --------------------------------------------------------------------------

    static Object fromJson(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                BigDecimal number = primitive.getAsBigDecimal();
                if (number.scale() <= 0 && number.abs().compareTo(new BigDecimal(Long.MAX_VALUE)) <= 0) {
                    return number.longValue();
                }
                return number.doubleValue();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(fromJson(item));
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            map.put(entry.getKey(), fromJson(entry.getValue()));
        }
        return map;
    }

    // ---- defaults -------------------------------------------------------------------------------

    static Map<String, Object> defaults(List<FieldDef> fields, SchemaSet set, int depth) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (FieldDef field : fields) {
            values.put(field.name(), defaultFor(field, set, depth));
        }
        return values;
    }

    static Object defaultFor(FieldDef field, SchemaSet set, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("struct nesting deeper than " + MAX_DEPTH + " at field '" + field.name() + "'");
        }
        FieldType type = field.type();
        if (type.isInteger()) {
            return 0L;
        }
        if (type.isFloat()) {
            return 0.0d;
        }
        return switch (type) {
            case BOOL -> Boolean.FALSE;
            case STRING -> "";
            case BYTES -> new byte[0];
            case STRUCT -> defaults(structOf(field, set).fields(), set, depth + 1);
            case ARRAY -> new ArrayList<>();
            default -> throw new IllegalStateException("unhandled type " + type);
        };
    }

    // ---- coercion -------------------------------------------------------------------------------

    /** Convert {@code value} to exactly the Java type the field's wire type needs. */
    static Object coerce(Object value, FieldDef field, SchemaSet set, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("nesting deeper than " + MAX_DEPTH + " at field '" + field.name() + "'");
        }
        if (value instanceof JsonElement json) {
            value = fromJson(json);
        }
        if (value == null) {
            return defaultFor(field, set, depth);
        }
        FieldType type = field.type();
        if (type.isInteger()) {
            return toLong(value, field.name());
        }
        if (type.isFloat()) {
            return toDouble(value, field.name());
        }
        switch (type) {
            case BOOL:
                return toBoolean(value, field.name());
            case STRING:
                return toStringValue(value);
            case BYTES:
                return toBytes(value, field.name());
            case STRUCT: {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("field '" + field.name() + "' expects an object, got "
                            + value.getClass().getSimpleName());
                }
                StructDef struct = structOf(field, set);
                Map<String, Object> out = new LinkedHashMap<>();
                for (FieldDef inner : struct.fields()) {
                    out.put(inner.name(), coerce(map.get(inner.name()), inner, set, depth + 1));
                }
                return out;
            }
            case ARRAY: {
                if (!(value instanceof List<?> list)) {
                    throw new IllegalArgumentException("field '" + field.name() + "' expects an array, got "
                            + value.getClass().getSimpleName());
                }
                if (list.size() > MAX_ARRAY_LENGTH) {
                    throw new IllegalArgumentException("field '" + field.name() + "' has " + list.size()
                            + " elements; limit is " + MAX_ARRAY_LENGTH);
                }
                FieldDef element = field.element();
                List<Object> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(coerce(item, element, set, depth + 1));
                }
                return out;
            }
            default:
                throw new IllegalStateException("unhandled type " + type);
        }
    }

    static long toLong(Object value, String field) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof String s) {
            String text = s.trim();
            try {
                if (text.startsWith("0x") || text.startsWith("0X")) {
                    return Long.parseLong(text.substring(2), 16);
                }
                return text.contains(".") ? (long) Double.parseDouble(text) : Long.parseLong(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("field '" + field + "' expects an integer, got '" + s + "'");
            }
        }
        throw new IllegalArgumentException("field '" + field + "' expects an integer, got " + value.getClass().getSimpleName());
    }

    static double toDouble(Object value, String field) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("field '" + field + "' expects a number, got '" + s + "'");
            }
        }
        throw new IllegalArgumentException("field '" + field + "' expects a number, got " + value.getClass().getSimpleName());
    }

    static boolean toBoolean(Object value, String field) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.longValue() != 0;
        }
        if (value instanceof String s) {
            String text = s.trim().toLowerCase();
            if (text.equals("true") || text.equals("1") || text.equals("yes")) {
                return true;
            }
            if (text.equals("false") || text.equals("0") || text.equals("no") || text.isEmpty()) {
                return false;
            }
        }
        throw new IllegalArgumentException("field '" + field + "' expects a boolean, got " + value);
    }

    static String toStringValue(Object value) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof byte[] bytes) {
            return ByteBufUtil.hexDump(bytes);
        }
        return String.valueOf(value);
    }

    static byte[] toBytes(Object value, String field) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String s) {
            try {
                return EngineConfig.decodeHex(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("field '" + field + "' expects hex bytes: " + e.getMessage());
            }
        }
        if (value instanceof List<?> list) {
            byte[] out = new byte[list.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) toLong(list.get(i), field + "[" + i + "]");
            }
            return out;
        }
        throw new IllegalArgumentException("field '" + field + "' expects bytes, got " + value.getClass().getSimpleName());
    }

    // ---- navigation & display -------------------------------------------------------------------

    /** Dotted-path lookup: {@code position.x}, {@code items.2.id}. Returns null when absent. */
    static Object lookup(Map<String, Object> values, String path) {
        Object current = values;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(part);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static String describe(Object value) {
        if (value instanceof byte[] bytes) {
            return "bytes[" + bytes.length + "]=" + ByteBufUtil.hexDump(Unpooled.wrappedBuffer(bytes), 0, Math.min(16, bytes.length))
                    + (bytes.length > 16 ? "..." : "");
        }
        if (value instanceof String s) {
            return "'" + s + "'";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            map.forEach((k, v) -> sb.append(sb.length() > 1 ? ", " : "").append(k).append('=').append(describe(v)));
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                sb.append(i > 0 ? ", " : "").append(describe(list.get(i)));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    static StructDef structOf(FieldDef field, SchemaSet set) {
        return set.struct(field.structName()).orElseThrow(() -> new IllegalStateException(
                "field '" + field.name() + "' references unknown struct '" + field.structName() + "'"));
    }

    static JsonElement toJsonForDisplay(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof byte[] bytes) {
            return new JsonPrimitive(ByteBufUtil.hexDump(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            map.forEach((k, v) -> object.add(String.valueOf(k), toJsonForDisplay(v)));
            return object;
        }
        if (value instanceof List<?> list) {
            JsonArray array = new JsonArray();
            list.forEach(v -> array.add(toJsonForDisplay(v)));
            return array;
        }
        if (value instanceof Number n) {
            return new JsonPrimitive(n);
        }
        if (value instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        return new JsonPrimitive(String.valueOf(value));
    }
}
