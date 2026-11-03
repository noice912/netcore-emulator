package core.schema;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Wire types a schema field may use. Integers are big-endian; {@code string} is a 16-bit length
 * plus UTF-8; {@code bytes} is a 32-bit length plus raw bytes; {@code array} is a 16-bit element
 * count followed by the elements; {@code struct} embeds a named structure inline.
 */
public enum FieldType {
    U8("u8"), I8("i8"), U16("u16"), I16("i16"), U32("u32"), I32("i32"), I64("i64"),
    F32("f32"), F64("f64"), BOOL("bool"), STRING("string"), BYTES("bytes"),
    STRUCT("struct"), ARRAY("array");

    private final String id;

    FieldType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isInteger() {
        return this == U8 || this == I8 || this == U16 || this == I16 || this == U32 || this == I32 || this == I64;
    }

    public boolean isFloat() {
        return this == F32 || this == F64;
    }

    public static FieldType parse(String id) {
        if (id != null) {
            String wanted = id.trim().toLowerCase();
            for (FieldType type : values()) {
                if (type.id.equals(wanted)) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("unknown field type '" + id + "'; expected one of " + ids());
    }

    public static String ids() {
        return Arrays.stream(values()).map(t -> t.id).collect(Collectors.joining(", "));
    }
}
